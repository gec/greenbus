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
package io.greenbus.measproc.pipeline

import io.greenbus.measproc.PointMap
import io.greenbus.measproc.processing._
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.jmx.MetricsManager
import scala.annotation.tailrec
import io.greenbus.client.service.proto.Processing.{ TriggerSet, MeasOverride }
import io.greenbus.client.service.proto.EventRequests.EventTemplate
import io.greenbus.client.service.proto.Model.ModelUUID

class ProcessingPipeline(
    endpointName: String,
    pointKeys: Seq[String],
    postMeasurements: Seq[(String, Measurement)] => Unit,
    getMeasurement: String => Option[Measurement],
    eventBatch: Seq[EventTemplate.Builder] => Unit,
    getPointMap: () => PointMap,
    triggerStateCache: ObjectCache[Boolean],
    overrideValueCache: ObjectCache[(Option[Measurement], Option[Measurement])]) {

  private val metricsMgr = MetricsManager("io.greenbus.measproc", endpointName)

  private val lastValueCache = new MapCache[Measurement]

  private var eventBuffer = List.empty[EventTemplate.Builder]

  private val whitelistProcessor = new MeasurementWhiteList(pointKeys, metricsMgr.metrics("Whitelist"))

  private val overrideProcessor = new OverrideProcessor(handleRestoreOnOverrideRemove, handleEngFromOverrides, handleTestReplaceValue, overrideValueCache, getCurrentValue, metricsMgr.metrics("Overrides"))

  private val triggerFactory = new TriggerProcessingFactory(eventSink, lastValueCache, getPointMap)
  private val triggerProcessor = new TriggerProcessor(triggerFactory, triggerStateCache, metricsMgr.metrics("Triggers"))

  private val processingSteps = List(whitelistProcessor, overrideProcessor, triggerProcessor)

  @tailrec
  private def processSteps(key: String, m: Measurement, steps: List[ProcessingStep]): Option[Measurement] = {
    steps match {
      case Nil => Some(m)
      case head :: tail =>
        head.process(key, m) match {
          case None => None
          case Some(result) => processSteps(key, result, tail)
        }
    }
  }

  def modifyLastValues(measurements: Seq[(String, Measurement)]): Unit = {
    measurements.foreach { case (key, m) => lastValueCache.put(key, m) }
  }

  def process(measurements: Seq[(String, Measurement)], batchTime: Option[Long]) {

    val results = measurements.flatMap {
      case (uuid, measurement) =>
        processSteps(uuid.toString, measurement, processingSteps).map { result =>
          (uuid, result)
        }
    }

    lazy val now = System.currentTimeMillis()
    val timeResolved = results.map {
      case (key, m) =>
        if (!m.hasTime) {
          val time = batchTime getOrElse now
          val withTime = Measurement.newBuilder().mergeFrom(m).setTime(time).build()
          (key, withTime)
        } else {
          (key, m)
        }
    }

    postMeasurements(timeResolved)
    val events = popEvents()
    eventBatch(events)
  }

  def updatePointsList(points: Seq[String]) {
    whitelistProcessor.updatePointList(points.map(_.toString))
    lastValueCache.reset()
  }

  def addOverride(over: MeasOverride) {
    overrideProcessor.add(over)
  }
  def removeOverride(over: MeasOverride) {
    overrideProcessor.remove(over)
  }
  def removeOverride(point: ModelUUID) {
    overrideProcessor.remove(point)
  }

  def addTriggerSet(uuid: ModelUUID, set: TriggerSet) {
    triggerProcessor.add(uuid, set)
  }
  def removeTriggerSet(point: ModelUUID) {
    triggerProcessor.remove(point)
  }

  private def popEvents(): Seq[EventTemplate.Builder] = {
    val events = eventBuffer.reverse
    eventBuffer = List.empty[EventTemplate.Builder]
    events
  }

  private def eventSink(event: EventTemplate.Builder) {
    eventBuffer ::= event
  }

  private def getCurrentValue(pointKey: String): Option[Measurement] = {
    getMeasurement(pointKey)
  }

  private def handleTestReplaceValue(pointKey: String, replaceValue: Measurement, previousValueOpt: Option[Measurement], alreadyReplaced: Boolean): Unit = {

    val processedOpt = triggerProcessor.process(pointKey, replaceValue)

    processedOpt match {
      case None => {
        if (!alreadyReplaced) {
          previousValueOpt match {
            case Some(prev) =>
              postMeasurements(Seq((pointKey, OverrideProcessor.transformNIS(prev))))
            case None =>
              postMeasurements(Seq((pointKey, OverrideProcessor.blankNIS())))
          }
        }
      }
      case Some(processed) =>
        postMeasurements(Seq((pointKey, OverrideProcessor.transformSubstituted(processed))))
    }

    val events = popEvents()
    if (events.nonEmpty) {
      eventBatch(events)
    }
  }

  private def handleRestoreOnOverrideRemove(pointKey: String, previouslyPublishedOpt: Option[Measurement], latestUpdateOpt: Option[Measurement]) {

    val latestProcessedOpt = latestUpdateOpt.flatMap(m => triggerProcessor.process(pointKey, m))

    latestProcessedOpt match {
      case None => {
        // no update or update was suppressed, restore previously published
        previouslyPublishedOpt.foreach(m => postMeasurements(Seq((pointKey, m))))
      }
      case Some(latestProcessed) => {
        postMeasurements(Seq((pointKey, latestProcessed)))
      }
    }

    // fire events even if suppressed
    val events = popEvents()
    if (events.nonEmpty) {
      eventBatch(events)
    }
  }

  private def handleEngFromOverrides(pointKey: String, measurement: Measurement) {
    postMeasurements(Seq((pointKey, measurement)))
  }

}
