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

import io.greenbus.client.service.proto.Measurements.{ Quality, Measurement }
import io.greenbus.sim.impl.RandomValue
import scala.annotation.tailrec

object EndpointSimulation {

  case class MeasRecord(name: String, value: RandomValue) {
    def measurement: (String, Measurement) = {
      val meas = Measurement.newBuilder
        .setQuality(Quality.newBuilder.build)

      value.apply(meas)

      (name, meas.build())
    }
  }
}

class EndpointSimulation(config: Seq[MeasurementSimConfig]) {
  import EndpointSimulation._

  private var currentValues: List[MeasRecord] = config.map { measConfig =>
    MeasRecord(measConfig.name, RandomValue(measConfig))
  }.toList

  def tick(): Seq[(String, Measurement)] = {
    val (nextValues, toPublish) = updated(currentValues, Nil, Nil)
    currentValues = nextValues
    toPublish
  }

  @tailrec
  private def updated(current: List[MeasRecord], after: List[MeasRecord], results: List[(String, Measurement)]): (List[MeasRecord], List[(String, Measurement)]) = {
    current match {
      case Nil => (after.reverse, results.reverse)
      case head :: tail => {
        head.value.next() match {
          case None => updated(tail, head :: after, results)
          case Some(next) =>
            val recordAfter = head.copy(value = next)
            updated(tail, recordAfter :: after, recordAfter.measurement :: results)
        }
      }
    }
  }

}
