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
package io.greenbus.calc.lib

import io.greenbus.client.service.proto.Calculations.InputQuality
import io.greenbus.client.service.proto.Measurements.{ Quality, Measurement }

trait QualityInputStrategy {
  def checkInputs[A](inputs: Map[A, MeasContainer]): Option[Map[A, MeasContainer]]
}

object QualityInputStrategy {

  def build(config: InputQuality.Strategy): QualityInputStrategy = config match {
    case InputQuality.Strategy.ACCEPT_ALL => AcceptAll
    case InputQuality.Strategy.ONLY_WHEN_ALL_OK => WhenAllOk
    case InputQuality.Strategy.REMOVE_BAD_AND_CALC => FilterOutBad
    case _ => throw new Exception("Unknown quality input strategy")
  }

  private def isGoodQuality(m: Measurement): Boolean = m.getQuality.getValidity == Quality.Validity.GOOD

  object AcceptAll extends QualityInputStrategy {
    def checkInputs[A](inputs: Map[A, MeasContainer]): Option[Map[A, MeasContainer]] = {
      Some(inputs)
    }
  }

  object WhenAllOk extends QualityInputStrategy {
    def checkInputs[A](inputs: Map[A, MeasContainer]): Option[Map[A, MeasContainer]] = {

      val allGood = inputs.values.forall {
        case SingleMeas(m) => isGoodQuality(m)
        case MeasRange(seq) => seq.forall(isGoodQuality)
      }

      if (allGood) Some(inputs) else None
    }
  }

  object FilterOutBad extends QualityInputStrategy {
    def checkInputs[A](inputs: Map[A, MeasContainer]): Option[Map[A, MeasContainer]] = {

      val filtMap = inputs.flatMap {
        case kv @ (_, SingleMeas(m)) => if (isGoodQuality(m)) Some(kv) else None
        case kv @ (_, MeasRange(seq)) =>
          val filtered = seq.filter(isGoodQuality)
          if (filtered.nonEmpty) Some(kv) else None
      }

      Some(filtMap)
    }
  }
}
