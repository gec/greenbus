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

import collection.JavaConversions._
import collection.immutable

import com.typesafe.scalalogging.slf4j.Logging

import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.client.service.proto.Processing.TriggerSet
import io.greenbus.jmx.Metrics
import io.greenbus.client.service.proto.Model.ModelUUID

class TriggerProcessor(
  protected val factory: TriggerFactory,
  protected val stateCache: ObjectCache[Boolean],
  metrics: Metrics)
    extends ProcessingStep
    with Logging {

  protected var map = immutable.Map[String, List[Trigger]]()

  private val triggersActive = metrics.gauge("triggersActive")

  def process(pointKey: String, measurement: Measurement): Option[Measurement] = {
    val triggerList = map.get(pointKey)

    triggerList match {
      case Some(triggers) =>
        logger.trace("Applying triggers: " + triggers.size + " to meas: " + measurement)
        val res = Trigger.processAll(pointKey, measurement, stateCache, triggers)
        logger.trace("Trigger result: " + res)
        res
      case None =>
        Some(measurement)
    }
  }

  def add(uuid: ModelUUID, set: TriggerSet) {
    logger.trace("TriggerSet received: " + set)
    val pointKey = uuid.getValue
    val trigList = set.getTriggersList.toList.map(proto => factory.buildTrigger(proto, pointKey, uuid))
    map += (pointKey -> trigList)
    updateMetrics()
  }

  def remove(uuid: ModelUUID) {
    logger.trace("TriggerSet removed: " + uuid.getValue)
    map -= uuid.getValue
    updateMetrics()
  }

  def clear() {
    map = immutable.Map[String, List[Trigger]]()
    updateMetrics()
  }

  private def updateMetrics() {
    triggersActive(map.size)
  }
}
