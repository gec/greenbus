/**
 * Copyright 2011-2016 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the GNU Affero General Public License
 * Version 3.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.gnu.org/licenses/agpl.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.greenbus.measproc.processing

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.service.proto.Processing.MeasOverride
import io.greenbus.client.service.proto.Measurements.{ DetailQual, Quality, Measurement }
import io.greenbus.jmx.Metrics
import io.greenbus.client.service.proto.Model.ModelUUID

object OverrideProcessor {
  def transformSubstituted(meas: Measurement): Measurement = {
    val q = Quality.newBuilder(meas.getQuality).setSource(Quality.Source.SUBSTITUTED).setOperatorBlocked(true)
    val now = System.currentTimeMillis
    Measurement.newBuilder(meas).setQuality(q).setTime(now).setSystemTime(now).build
  }

  def transformNIS(meas: Measurement): Measurement = {
    val dq = DetailQual.newBuilder(meas.getQuality.getDetailQual).setOldData(true)
    val q = Quality.newBuilder(meas.getQuality).setDetailQual(dq).setOperatorBlocked(true)
    val now = System.currentTimeMillis
    Measurement.newBuilder(meas).setQuality(q).setTime(now).setSystemTime(now).build
  }

  def blankNIS(): Measurement = {
    val dq = DetailQual.newBuilder().setOldData(true)
    val q = Quality.newBuilder().setDetailQual(dq).setOperatorBlocked(true)
    val now = System.currentTimeMillis
    Measurement.newBuilder().setType(Measurement.Type.NONE).setQuality(q).setTime(now).setSystemTime(now).build
  }
}

// TODO: OLD should not be set until a new field measurement comes in (61850-7-3).
class OverrideProcessor(
  handleRemove: (String, Option[Measurement], Option[Measurement]) => Unit,
  publishEng: (String, Measurement) => Unit,
  handleTest: (String, Measurement, Option[Measurement], Boolean) => Unit,
  maskedValueCache: ObjectCache[(Option[Measurement], Option[Measurement])],
  current: String => Option[Measurement],
  metrics: Metrics)
    extends ProcessingStep with LazyLogging {

  import OverrideProcessor._

  private var blockedPointMap = scala.collection.immutable.Map[String, Option[Measurement]]()

  private val measSupressed = metrics.counter("measSupressed")
  private val overrideCurrentValueMiss = metrics.counter("overrideCurrentValueMiss")
  private val overridenCacheMiss = metrics.counter("overridenCacheMiss")
  private val overridesActive = metrics.gauge("overridesActive")

  // cache is (published, latest subsequently arriving unpublished)
  // if the later arrival is suppressed by triggers, the previously published value is restored

  def process(pointKey: String, measurement: Measurement): Option[Measurement] = {
    blockedPointMap.get(pointKey) match {
      case None => Some(measurement)
      case Some(_) => {
        measSupressed(1)
        maskedValueCache.get(pointKey) match {
          case None => maskedValueCache.put(pointKey, (None, Some(measurement)))
          case Some((publishedOpt, _)) => maskedValueCache.put(pointKey, (publishedOpt, Some(measurement)))
        }
        None
      }
    }
  }

  def add(over: MeasOverride) {
    val pointUuid = over.getPointUuid
    val pointKey = pointUuid.getValue
    val currentlyNIS = blockedPointMap.contains(pointKey)
    val replaceMeas = if (over.hasMeasurement) Some(over.getMeasurement) else None
    val isTest = over.hasTestValue && over.getTestValue

    logger.debug("Adding measurement override on: " + pointKey)

    (currentlyNIS, replaceMeas) match {

      // new NIS request, no replace specified
      case (false, None) => {
        val updateValue = cacheCurrent(pointKey).map(transformNIS).getOrElse(blankNIS())
        blockedPointMap += (pointKey -> None)
        publishEng(pointKey, updateValue)
      }

      // new NIS request, replace specified
      case (false, Some(repl)) => {
        val currentOpt = cacheCurrent(pointKey)
        blockedPointMap += (pointKey -> replaceMeas)
        if (isTest) {
          handleTest(pointKey, repl, currentOpt, false)
        } else {
          publishEng(pointKey, transformSubstituted(repl))
        }
      }

      // point already NIS, no replace specified
      case (true, None) => logger.debug("NIS to point already NIS, ignoring")

      // point already NIS, replace specified, treat as simple replace
      case (true, Some(repl)) => {
        blockedPointMap += (pointKey -> replaceMeas)
        if (isTest) {
          handleTest(pointKey, repl, None, true)
        } else {
          publishEng(pointKey, transformSubstituted(repl))
        }
      }
    }

    updateMetrics()
  }

  def remove(uuid: ModelUUID) {
    val pointKey = uuid.getValue

    logger.debug("Removing measurement override on: " + pointKey)

    blockedPointMap -= pointKey
    updateMetrics()
    maskedValueCache.get(pointKey) match {
      case None =>
        overridenCacheMiss(1)
      case Some((publishedOpt, latestOpt)) => {
        val now = System.currentTimeMillis

        val publishUpdatedOpt = publishedOpt.map(cached => Measurement.newBuilder(cached).setTime(now).build())
        val latestUpdatedOpt = latestOpt.map(cached => Measurement.newBuilder(cached).setTime(now).build())

        handleRemove(pointKey, publishUpdatedOpt, latestUpdatedOpt)

        maskedValueCache.delete(pointKey)
      }
    }
  }

  def remove(over: MeasOverride) {
    remove(over.getPointUuid)
  }

  def clear() {
    blockedPointMap = scala.collection.immutable.Map[String, Option[Measurement]]()
    updateMetrics()
  }

  private def updateMetrics() = {
    overridesActive(blockedPointMap.size)
  }

  private def cacheCurrent(pointKey: String): Option[Measurement] = {
    current(pointKey) match {
      case Some(curr) =>
        maskedValueCache.put(pointKey, (Some(curr), None))
        Some(curr)
      case None =>
        overrideCurrentValueMiss(1)
        None
    }
  }

}