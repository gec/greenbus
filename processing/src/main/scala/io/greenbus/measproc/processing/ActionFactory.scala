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

import io.greenbus.client.service.proto.EventRequests.EventTemplate
import io.greenbus.client.service.proto.Events._
import io.greenbus.client.service.proto.Measurements._
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.client.service.proto.Processing.{ Action => ActionProto, ActivationType => TypeProto, LinearTransform => LinearProto }

import scala.collection.JavaConversions._

/**
 * Trigger/Action factory with constructor dependencies.
 */
class TriggerProcessingFactory(protected val publishEvent: EventTemplate.Builder => Unit, protected val lastCache: ObjectCache[Measurement])
  extends ProcessingResources
  with TriggerFactory
  with ActionFactory

/**
 * Internal interface for Trigger/Action factory dependencies
 */
trait ProcessingResources {
  protected val publishEvent: (EventTemplate.Builder) => Unit
  protected val lastCache: ObjectCache[Measurement]
}

/**
 * Factory for Action implementation objects
 */
trait ActionFactory { self: ProcessingResources =>
  import io.greenbus.measproc.processing.Actions._
  import io.greenbus.measproc.processing.TriggerFactory._

  /**
   * Creates Action objects given protobuf representation
   * @param proto   Action configuration proto
   * @return        Constructed action
   */
  def buildAction(proto: ActionProto, pointUuid: ModelUUID): Action = {
    val name = proto.getActionName
    val typ = convertActivation(proto.getType)
    val disabled = proto.getDisabled

    if (proto.hasSuppress && proto.getSuppress) {
      new SuppressAction(name, disabled, typ)
    } else {
      val eval = if (proto.hasLinearTransform) {
        new LinearTransform(proto.getLinearTransform.getScale, proto.getLinearTransform.getOffset, proto.getLinearTransform.getForceToDouble)
      } else if (proto.hasQualityAnnotation) {
        new AnnotateQuality(proto.getQualityAnnotation)
      } else if (proto.hasStripValue && proto.getStripValue) {
        new StripValue
      } else if (proto.hasSetBool) {
        new SetBool(proto.getSetBool)
      } else if (proto.hasEvent) {
        new EventGenerator(publishEvent, proto.getEvent.getEventType, pointUuid)
      } else if (proto.hasBoolTransform) {
        new BoolEnumTransformer(proto.getBoolTransform.getFalseString, proto.getBoolTransform.getTrueString)
      } else if (proto.hasIntTransform) {
        import scala.collection.JavaConversions._
        val otherwiseValOpt = if (proto.getIntTransform.hasDefaultValue) Some(proto.getIntTransform.getDefaultValue) else None
        val intMapping = proto.getIntTransform.getMappingsList.toList.map { vm => vm.getValue -> vm.getString }.toMap
        new IntegerEnumTransformer(intMapping, otherwiseValOpt)
      } else {
        throw new Exception("Must specify at least one action")
      }

      new BasicAction(name, disabled, typ, eval)
    }
  }
}

object Actions {

  class AnnotateQuality(qual: Quality) extends Action.Evaluation {
    def apply(m: Measurement): Measurement = {
      Measurement.newBuilder(m).setQuality(Quality.newBuilder(m.getQuality).mergeFrom(qual)).build
    }
  }
  class StripValue extends Action.Evaluation {
    def apply(m: Measurement): Measurement = {
      Measurement.newBuilder(m)
        .clearDoubleVal
        .clearIntVal
        .clearStringVal
        .clearBoolVal
        .setType(Measurement.Type.NONE)
        .build
    }
  }
  class SetBool(b: Boolean) extends Action.Evaluation {
    def apply(m: Measurement): Measurement = {
      Measurement.newBuilder(m)
        .clearDoubleVal
        .clearIntVal
        .clearStringVal
        .setBoolVal(b)
        .setType(Measurement.Type.BOOL)
        .build
    }
  }
  class LinearTransform(scale: Double, offset: Double, forceToDouble: Boolean) extends Action.Evaluation {
    def apply(m: Measurement): Measurement = {
      m.getType match {
        case Measurement.Type.DOUBLE =>
          if (m.hasDoubleVal) Measurement.newBuilder(m).setDoubleVal(m.getDoubleVal * scale + offset).build else m
        case Measurement.Type.INT =>
          if (m.hasIntVal) {
            val scaledValue = m.getIntVal * scale + offset
            if (!forceToDouble) Measurement.newBuilder(m).setIntVal(scaledValue.toLong).build
            else Measurement.newBuilder(m).setDoubleVal(scaledValue).setType(Measurement.Type.DOUBLE).build
          } else m
        case _ => m
      }
    }
  }

  private def attribute[A](name: String, obj: A): Attribute = {
    val b = Attribute.newBuilder().setName(name)
    obj match {
      case v: Int => b.setValueSint64(v)
      case v: Long => b.setValueSint64(v)
      case v: Double => b.setValueDouble(v)
      case v: Boolean => b.setValueBool(v)
      case v: String => b.setValueString(v)
    }
    b.build()
  }

  class EventGenerator(out: EventTemplate.Builder => Unit, eventType: String, pointUuid: ModelUUID)
      extends Action.Evaluation {

    def apply(m: Measurement): Measurement = {

      val validity = attribute("validity", m.getQuality.getValidity.toString)

      val valueAttr = m.getType match {
        case Measurement.Type.INT => Some(attribute("value", m.getIntVal))
        case Measurement.Type.DOUBLE => Some(attribute("value", m.getDoubleVal))
        case Measurement.Type.BOOL => Some(attribute("value", m.getBoolVal))
        case Measurement.Type.STRING => Some(attribute("value", m.getStringVal))
        case Measurement.Type.NONE => None
      }

      val attrs = Seq(validity) ++ Seq(valueAttr).flatten

      val builder = EventTemplate.newBuilder
        .setEventType(eventType)
        .setEntityUuid(pointUuid)
        .addAllArgs(attrs)
      if (m.getIsDeviceTime) builder.setDeviceTime(m.getTime)
      out(builder)
      m
    }
  }

  class BoolEnumTransformer(falseString: String, trueString: String) extends Action.Evaluation {
    def apply(m: Measurement): Measurement = {
      if (!m.hasBoolVal) {
        // TODO: handle non boolean measurements in enum transform
        m
      } else {
        m.toBuilder.setType(Measurement.Type.STRING)
          .setStringVal(if (m.getBoolVal) trueString else falseString).build
      }
    }
  }

  // integer as in "not floating point", not integer as in 2^32, values on measurements are actually Longs
  class IntegerEnumTransformer(mapping: Map[Long, String], otherWiseOpt: Option[String]) extends Action.Evaluation {
    def apply(m: Measurement): Measurement = {

      def buildStr(str: String) = m.toBuilder.setStringVal(str).setType(Measurement.Type.STRING).build

      if (!m.hasIntVal) {
        // TODO: handle non int measurements in int transform
        m
      } else {
        mapping.get(m.getIntVal) match {
          case Some(s) => buildStr(s)
          case None => otherWiseOpt.map(buildStr).getOrElse(m)
        }

      }
    }
  }

}