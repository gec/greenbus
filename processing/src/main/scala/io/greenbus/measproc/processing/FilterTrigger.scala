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

import io.greenbus.client.service.proto.Measurements.{ DetailQual, Quality, Measurement }
import io.greenbus.client.service.proto.Processing.{ Filter => FilterProto }
import FilterProto.{ FilterType => Type }
import io.greenbus.util.Optional._

object FilterTrigger {

  def apply(config: FilterProto, cache: ObjectCache[Measurement]) = {
    val cond = config.getType match {
      case Type.DUPLICATES_ONLY => new NoDuplicates
      case Type.DEADBAND => new Deadband(optGet(config.hasDeadbandValue, config.getDeadbandValue).getOrElse(0))
    }
    new FilterTrigger(cache, cond)
  }

  sealed trait Filter {
    def allow(m: Measurement, current: Measurement): Boolean
  }

  class NoDuplicates extends Filter {
    def allow(m: Measurement, current: Measurement) = {
      optGet(m.hasIntVal, m.getIntVal) != optGet(current.hasIntVal, current.getIntVal) ||
        optGet(m.hasDoubleVal, m.getDoubleVal) != optGet(current.hasDoubleVal, current.getDoubleVal) ||
        optGet(m.hasBoolVal, m.getBoolVal) != optGet(current.hasBoolVal, current.getBoolVal) ||
        optGet(m.hasStringVal, m.getStringVal) != optGet(current.hasStringVal, current.getStringVal)
    }
  }

  class Deadband(band: Double) extends Filter {
    def allow(m: Measurement, current: Measurement) = {
      if (m.hasDoubleVal && current.hasDoubleVal) {
        math.abs(m.getDoubleVal - current.getDoubleVal) > band
      } else if (m.hasIntVal && current.hasIntVal) {
        math.abs(m.getIntVal - current.getIntVal).asInstanceOf[Double] > band
      } else {
        true
      }
    }
  }

  def sameDetailQuality(lhs: Option[DetailQual], rhs: Option[DetailQual]): Boolean = {
    (lhs, rhs) match {
      case (None, None) => true
      case (Some(l), Some(r)) => sameDetailQuality(l, r)
      case _ => false
    }
  }

  def sameDetailQuality(lhs: DetailQual, rhs: DetailQual): Boolean = {
    optGet(lhs.hasOverflow, lhs.getOverflow) == optGet(rhs.hasOverflow, rhs.getOverflow) &&
      optGet(lhs.hasOutOfRange, lhs.getOutOfRange) == optGet(rhs.hasOutOfRange, rhs.getOutOfRange) &&
      optGet(lhs.hasBadReference, lhs.getBadReference) == optGet(rhs.hasBadReference, rhs.getBadReference) &&
      optGet(lhs.hasOscillatory, lhs.getOscillatory) == optGet(rhs.hasOscillatory, rhs.getOscillatory) &&
      optGet(lhs.hasFailure, lhs.getFailure) == optGet(rhs.hasFailure, rhs.getFailure) &&
      optGet(lhs.hasOldData, lhs.getOldData) == optGet(rhs.hasOldData, rhs.getOldData) &&
      optGet(lhs.hasInconsistent, lhs.getInconsistent) == optGet(rhs.hasInconsistent, rhs.getInconsistent) &&
      optGet(lhs.hasInaccurate, lhs.getInaccurate) == optGet(rhs.hasInaccurate, rhs.getInaccurate)
  }

  def sameQuality(lhs: Quality, rhs: Quality): Boolean = {
    optGet(lhs.hasValidity, lhs.getValidity) == optGet(rhs.hasValidity, rhs.getValidity) &&
      optGet(lhs.hasSource, lhs.getSource) == optGet(rhs.hasSource, rhs.getSource) &&
      optGet(lhs.hasTest, lhs.getTest) == optGet(rhs.hasTest, rhs.getTest) &&
      optGet(lhs.hasOperatorBlocked, lhs.getOperatorBlocked) == optGet(rhs.hasOperatorBlocked, rhs.getOperatorBlocked) &&
      sameDetailQuality(optGet(lhs.hasDetailQual, lhs.getDetailQual), optGet(rhs.hasDetailQual, rhs.getDetailQual))
  }
}

class FilterTrigger(cache: ObjectCache[Measurement], band: FilterTrigger.Filter) extends Trigger.KeyedCondition {
  import FilterTrigger.sameQuality

  def apply(pointKey: String, m: Measurement, prev: Boolean): Boolean = {
    cache.get(pointKey) match {
      case None => {
        cache.put(pointKey, m)
        true
      }
      case Some(current) => {
        if (!sameQuality(m.getQuality, current.getQuality) ||
          band.allow(m, current)) {

          cache.put(pointKey, m)
          true
        } else {
          false
        }
      }
    }
  }
}