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
package io.greenbus.sim

object StatusSimConfig {
  import play.api.libs.json._
  implicit val writer = Json.writes[StatusSimConfig]
  implicit val reader = Json.reads[StatusSimConfig]
}
case class StatusSimConfig(initialValue: Boolean)

object AnalogSimConfig {
  import play.api.libs.json._
  implicit val writer = Json.writes[AnalogSimConfig]
  implicit val reader = Json.reads[AnalogSimConfig]
}
case class AnalogSimConfig(initialValue: Double, min: Double, max: Double, maxDelta: Double)

object MeasurementSimConfig {
  import play.api.libs.json._
  implicit val writer = Json.writes[MeasurementSimConfig]
  implicit val reader = Json.reads[MeasurementSimConfig]
}
case class MeasurementSimConfig(
  name: String,
  measurementType: String,
  changeProbability: Option[Double],
  statusConfig: Option[StatusSimConfig],
  analogConfig: Option[AnalogSimConfig])

object SimulatorEndpointConfig {
  import play.api.libs.json._
  implicit val writer = Json.writes[SimulatorEndpointConfig]
  implicit val reader = Json.reads[SimulatorEndpointConfig]
}
case class SimulatorEndpointConfig(delay: Option[Long], measurements: Seq[MeasurementSimConfig])
