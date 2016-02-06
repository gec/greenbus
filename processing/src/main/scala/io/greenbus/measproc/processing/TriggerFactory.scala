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

import java.util.UUID

import io.greenbus.client.service.proto.Measurements._
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.client.service.proto.Processing.{ Action => ActionProto, ActivationType => TypeProto, AnalogLimit => AnalogLimitProto, Trigger => TriggerProto }
import io.greenbus.util.Optional._

import scala.collection.JavaConversions._

object TriggerFactory {
  import io.greenbus.measproc.processing.Triggers._

  /**
   * Converts proto activation type to scala activation type
   * @param proto   Proto activation type
   * @return        Scala activation type
   */
  def convertActivation(proto: TypeProto) = proto match {
    case TypeProto.HIGH => Action.High
    case TypeProto.LOW => Action.Low
    case TypeProto.RISING => Action.Rising
    case TypeProto.FALLING => Action.Falling
    case TypeProto.TRANSITION => Action.Transition
  }

  /**
   * Constructs either an upper,lower or, most commonly, range condition
   * @param proto   Proto configuration
   * @return        Trigger condition
   */
  def limitCondition(proto: AnalogLimitProto): Trigger.Condition = {
    // TODO: do we need non-deadbanded limit checks, does deadband == 0 work for all floats
    if (proto.hasLowerLimit && proto.hasUpperLimit) {
      if (proto.hasDeadband)
        new RangeLimitDeadband(proto.getUpperLimit, proto.getLowerLimit, proto.getDeadband)
      else
        new RangeLimit(proto.getUpperLimit, proto.getLowerLimit)
    } else if (proto.hasUpperLimit()) {
      if (proto.hasDeadband)
        new UpperLimitDeadband(proto.getUpperLimit, proto.getDeadband)
      else
        new UpperLimit(proto.getUpperLimit)
    } else if (proto.hasLowerLimit()) {
      if (proto.hasDeadband)
        new LowerLimitDeadband(proto.getLowerLimit, proto.getDeadband)
      else
        new LowerLimit(proto.getLowerLimit)
    } else throw new IllegalArgumentException("Upper and Lower limit not set in AnalogLimit")
  }
}

/**
 * Factory for trigger implementation objects.
 */
trait TriggerFactory { self: ActionFactory with ProcessingResources =>
  import io.greenbus.measproc.processing.TriggerFactory._
  import io.greenbus.measproc.processing.Triggers._

  /**
   * Creates trigger objects given protobuf configuration.
   * @param proto         Configuration object
   * @param pointKey      Associated measurement point
   * @return              Implementation object
   */
  def buildTrigger(proto: TriggerProto, pointKey: String, pointUuid: ModelUUID): Trigger = {
    val cacheId = UUID.randomUUID().toString //TODO: this won't work for persistent caches
    val stopProc = proto.hasStopProcessingWhen thenGet convertActivation(proto.getStopProcessingWhen)
    val conditions = List(
      optGet(proto.hasAnalogLimit, proto.getAnalogLimit).map(limitCondition),
      optGet(proto.hasQuality, proto.getQuality).map(new QualityCondition(_)),
      optGet(proto.hasValueType, proto.getValueType).map(new TypeCondition(_)),
      optGet(proto.hasBoolValue, proto.getBoolValue).map(new BoolValue(_)),
      optGet(proto.hasIntValue, proto.getIntValue).map(new IntegerValue(_)),
      optGet(proto.hasStringValue, proto.getStringValue).map(new StringValue(_)),
      optGet(proto.hasFilter, proto.getFilter).map(FilterTrigger(_, lastCache))).flatten

    val actions = proto.getActionsList.toList.map(buildAction(_, pointUuid))
    new BasicTrigger(cacheId, conditions, actions, stopProc)
  }
}

/**
 * Implementations of corresponding proto Trigger types (see Triggers.proto)
 */
object Triggers {
  class BoolValue(b: Boolean) extends Trigger.SimpleCondition {
    def apply(m: Measurement, prev: Boolean): Boolean = {
      (m.getType == Measurement.Type.BOOL) &&
        (m.getBoolVal == b)
    }
  }

  // integer as in "not floating point", not integer as in 2^32, values on measurements are actually Longs
  class IntegerValue(i: Long) extends Trigger.SimpleCondition {
    def apply(m: Measurement, prev: Boolean): Boolean = {
      (m.getType == Measurement.Type.INT) &&
        (m.getIntVal == i)
    }
  }

  class StringValue(s: String) extends Trigger.SimpleCondition {
    def apply(m: Measurement, prev: Boolean): Boolean = {
      (m.getType == Measurement.Type.STRING) &&
        (m.getStringVal == s)
    }
  }

  class RangeLimit(upper: Double, lower: Double) extends Trigger.SimpleCondition {
    def apply(m: Measurement, prev: Boolean): Boolean = {
      val x = Trigger.analogValue(m) getOrElse { return false }
      x <= lower || x >= upper
    }
  }

  class RangeLimitDeadband(upper: Double, lower: Double, deadband: Double) extends Trigger.SimpleCondition {
    def apply(m: Measurement, prev: Boolean): Boolean = {
      val x = Trigger.analogValue(m) getOrElse { return false }
      (prev && (x <= (lower + deadband) || x >= (upper - deadband))) || (!prev && (x <= lower || x >= upper))
    }
  }

  class UpperLimitDeadband(limit: Double, deadband: Double) extends Trigger.SimpleCondition {
    def apply(m: Measurement, prev: Boolean): Boolean = {
      val x = Trigger.analogValue(m) getOrElse { return false }
      (prev && x >= (limit - deadband)) || (!prev && x >= limit)
    }
  }
  class UpperLimit(limit: Double) extends Trigger.SimpleCondition {
    def apply(m: Measurement, prev: Boolean): Boolean = {
      val x = Trigger.analogValue(m) getOrElse { return false }
      x >= limit
    }
  }

  class LowerLimitDeadband(limit: Double, deadband: Double) extends Trigger.SimpleCondition {
    def apply(m: Measurement, prev: Boolean): Boolean = {
      val x = Trigger.analogValue(m) getOrElse { return false }
      (prev && x <= (limit + deadband)) || (!prev && x <= limit)
    }
  }
  class LowerLimit(limit: Double) extends Trigger.SimpleCondition {
    def apply(m: Measurement, prev: Boolean): Boolean = {
      val x = Trigger.analogValue(m) getOrElse { return false }
      x <= limit
    }
  }

  def detailQualityMatches(lhs: DetailQual, rhs: DetailQual): Boolean = {
    lhs.hasOverflow && rhs.hasOverflow && (lhs.getOverflow == rhs.getOverflow) ||
      lhs.hasOutOfRange && rhs.hasOutOfRange && (lhs.getOutOfRange == rhs.getOutOfRange) ||
      lhs.hasBadReference && rhs.hasBadReference && (lhs.getBadReference == rhs.getBadReference) ||
      lhs.hasOscillatory && rhs.hasOscillatory && (lhs.getOscillatory == rhs.getOscillatory) ||
      lhs.hasFailure && rhs.hasFailure && (lhs.getFailure == rhs.getFailure) ||
      lhs.hasOldData && rhs.hasOldData && (lhs.getOldData == rhs.getOldData) ||
      lhs.hasInconsistent && rhs.hasInconsistent && (lhs.getInconsistent == rhs.getInconsistent) ||
      lhs.hasInaccurate && rhs.hasInaccurate && (lhs.getInaccurate == rhs.getInaccurate)
  }

  def qualityMatches(qual: Quality, q: Quality): Boolean = {
    qual.hasValidity && q.hasValidity && (qual.getValidity == q.getValidity) ||
      qual.hasSource && q.hasSource && (qual.getSource == q.getSource) ||
      qual.hasTest && q.hasTest && (qual.getTest == q.getTest) ||
      qual.hasOperatorBlocked && q.hasOperatorBlocked && (qual.getOperatorBlocked == q.getOperatorBlocked) ||
      qual.hasDetailQual && q.hasDetailQual && detailQualityMatches(qual.getDetailQual, q.getDetailQual)
  }

  class QualityCondition(qual: Quality) extends Trigger.SimpleCondition {

    def apply(m: Measurement, prev: Boolean): Boolean = {
      qualityMatches(qual, m.getQuality)
    }
  }

  class TypeCondition(typ: Measurement.Type) extends Trigger.SimpleCondition {
    def apply(m: Measurement, prev: Boolean): Boolean = {
      m.getType == typ
    }
  }
}

