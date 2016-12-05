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

import io.greenbus.client.service.proto.Measurements.Measurement
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.jmx.Metrics

/**
 * checks to see if the measurements are on the whitelist provided with the endpoint and filters
 * out the unexpected measurements and adds a log message indicating what is being ignored.
 */
class MeasurementWhiteList(expectedKeys: Seq[String], metrics: Metrics)
    extends ProcessingStep with LazyLogging {

  private var whiteList: Set[String] = expectedKeys.toSet
  private var ignored = Set.empty[String]

  private val ignoredMeasurements = metrics.counter("ignoredMeasurements")

  def process(pointKey: String, measurement: Measurement): Option[Measurement] = {
    if (whiteList.contains(pointKey)) {
      Some(measurement)
    } else {
      ignoredMeasurements(1)
      if (!ignored.contains(pointKey)) {
        ignored += pointKey
        logger.info("Ignoring unexpected measurement: " + pointKey)
      }
      None
    }
  }

  def updatePointList(expectedPoints: Seq[String]) {
    whiteList = expectedPoints.toSet
    ignored = Set.empty[String]
  }
}