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
package io.greenbus.sim.impl

import io.greenbus.client.service.proto.Measurements
import java.util.Random
import io.greenbus.client.service.proto.Measurements.Measurement.Type
import io.greenbus.sim.MeasurementSimConfig

object RandomValue {

  private val rand = new Random

  def apply(config: MeasurementSimConfig): RandomValue = {

    config.measurementType match {
      case "BOOL" =>
        config.statusConfig.map(cfg => BooleanValue(cfg.initialValue, config.changeProbability.getOrElse(0.3))).getOrElse(BooleanValue(false, 0.3))
      case "INT" =>
        config.analogConfig.map(cfg => IntValue(cfg.initialValue.toInt, cfg.min.toInt, cfg.max.toInt, cfg.maxDelta.toInt, config.changeProbability.getOrElse(0.3)))
          .getOrElse(IntValue(0, 0, 100, 2, 0.3))
      case "DOUBLE" =>
        config.analogConfig.map(cfg => DoubleValue(cfg.initialValue, cfg.min, cfg.max, cfg.maxDelta, config.changeProbability.getOrElse(0.3)))
          .getOrElse(DoubleValue(0, -100, 100, 2, 0.3))
      case x => throw new Exception(s"Can't generate random value for type $x")
    }
  }

  case class DoubleValue(value: Double, min: Double, max: Double, maxChange: Double, changeProbability: Double) extends RandomValue {
    def generate() = this.copy(value = (value + maxChange * 2 * (rand.nextDouble - 0.5)).max(min).min(max))
    def apply(meas: Measurements.Measurement.Builder) = meas.setDoubleVal(value).setType(Type.DOUBLE)
    def newChangeProbability(p: Double) = this.copy(changeProbability = p)
  }
  case class IntValue(value: Int, min: Int, max: Int, maxChange: Int, changeProbability: Double) extends RandomValue {
    def generate() = this.copy(value = (value + rand.nextInt(2 * maxChange + 1) - maxChange).max(min).min(max))
    def apply(meas: Measurements.Measurement.Builder) = meas.setIntVal(value).setType(Type.INT)
    def newChangeProbability(p: Double) = this.copy(changeProbability = p)
  }
  case class BooleanValue(value: Boolean, changeProbability: Double) extends RandomValue {
    def generate() = this.copy(value = !value, changeProbability)
    def apply(meas: Measurements.Measurement.Builder) = meas.setBoolVal(value) setType (Type.BOOL)
    def newChangeProbability(p: Double) = this.copy(changeProbability = p)
  }
}

trait RandomValue {

  def changeProbability: Double

  def newChangeProbability(p: Double): RandomValue

  def next(): Option[RandomValue] = {
    if (RandomValue.rand.nextDouble > changeProbability) None
    else Some(generate)
  }

  def generate(): RandomValue

  def apply(meas: Measurements.Measurement.Builder)
}