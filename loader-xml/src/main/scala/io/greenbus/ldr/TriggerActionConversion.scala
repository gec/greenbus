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
package io.greenbus.ldr

import io.greenbus.client.service.proto.Processing
import io.greenbus.client.service.proto.Processing.Filter.FilterType
import io.greenbus.client.service.proto.Processing._
import io.greenbus.ldr.xml.IntegerMapping.Mapping
import io.greenbus.ldr.xml.PointType.Triggers
import io.greenbus.ldr.xml._

import scala.collection.JavaConversions._

object TriggerActionConversion {

  def triggersToProto(triggers: Triggers)(implicit context: String): TriggerSet = {
    val protoTriggers = triggers.getTriggerGroup.map(triggerToProto)

    TriggerSet.newBuilder()
      .addAllTriggers(protoTriggers)
      .build()
  }

  def triggerToProto(trig: TriggerBase)(implicit context: String) = {
    val b: Trigger.Builder = trig match {
      case elem: xml.Always =>
        Trigger.newBuilder()

      case elem: xml.Range =>
        val b = AnalogLimit.newBuilder()
        if (elem.isSetLow) b.setLowerLimit(elem.getLow)
        if (elem.isSetHigh) b.setUpperLimit(elem.getHigh)
        if (elem.isSetDeadband) b.setDeadband(elem.getDeadband)

        Trigger.newBuilder().setAnalogLimit(b.build())

      case elem: xml.Filter =>
        val filter = if (elem.isSetDeadband) {
          Processing.Filter.newBuilder()
            .setType(Processing.Filter.FilterType.DEADBAND)
            .setDeadbandValue(elem.getDeadband)
            .build()
        } else {
          Processing.Filter.newBuilder()
            .setType(Processing.Filter.FilterType.DUPLICATES_ONLY)
            .build()
        }

        Trigger.newBuilder().setFilter(filter)

      case elem: xml.MatchValue =>

        val tb = Trigger.newBuilder()

        if (elem.isSetBooleanValue) {
          tb.setBoolValue(elem.getBooleanValue)
        } else if (elem.isSetIntValue) {
          tb.setIntValue(elem.getIntValue)
        } else if (elem.isSetStringValue) {
          tb.setStringValue(elem.getStringValue)
        } else {
          throw new LoadingXmlException(s"Missing value to match in MatchValue element for $context", elem)
        }
        tb

      case x => throw new LoadingXmlException("Unhandled trigger element: " + x.getClass.getSimpleName + s" for $context", trig)
    }

    if (trig.isSetStopProcessingWhen) {
      b.setStopProcessingWhen(activationTypeToProto(trig.getStopProcessingWhen))
    }

    val actions = trig.getActionGroup.map(xmlActionToProto)
    b.addAllActions(actions)

    b.build()
  }

  def toXml(proto: TriggerSet)(implicit context: String): PointType.Triggers = {

    val triggersXml = new Triggers

    proto.getTriggersList.foreach { trigger =>
      if (trigger.hasAnalogLimit) {
        val limit = trigger.getAnalogLimit

        val range = new xml.Range()

        if (limit.hasLowerLimit) range.setLow(limit.getLowerLimit)
        if (limit.hasUpperLimit) range.setHigh(limit.getUpperLimit)
        if (limit.hasDeadband) range.setDeadband(limit.getDeadband)

        trigger.getActionsList.foreach(a => fillAction(a, range))
        if (trigger.hasStopProcessingWhen) range.setStopProcessingWhen(activationTypeToXml(trigger.getStopProcessingWhen))
        triggersXml.getTriggerGroup.add(range)

      } else if (trigger.hasBoolValue) {
        val matcher = new MatchValue
        matcher.setBooleanValue(trigger.getBoolValue)
        trigger.getActionsList.foreach(a => fillAction(a, matcher))
        if (trigger.hasStopProcessingWhen) matcher.setStopProcessingWhen(activationTypeToXml(trigger.getStopProcessingWhen))
        triggersXml.getTriggerGroup.add(matcher)

      } else if (trigger.hasStringValue) {

        val matcher = new MatchValue
        matcher.setStringValue(trigger.getStringValue)
        trigger.getActionsList.foreach(a => fillAction(a, matcher))
        if (trigger.hasStopProcessingWhen) matcher.setStopProcessingWhen(activationTypeToXml(trigger.getStopProcessingWhen))
        triggersXml.getTriggerGroup.add(matcher)

      } else if (trigger.hasIntValue) {

        val matcher = new MatchValue
        matcher.setIntValue(trigger.getIntValue)
        trigger.getActionsList.foreach(a => fillAction(a, matcher))
        if (trigger.hasStopProcessingWhen) matcher.setStopProcessingWhen(activationTypeToXml(trigger.getStopProcessingWhen))
        triggersXml.getTriggerGroup.add(matcher)

      } else if (trigger.hasFilter) {

        val filter = trigger.getFilter
        val filterXml = new xml.Filter
        filter.getType match {
          case FilterType.DEADBAND =>
            if (filter.hasDeadbandValue) filterXml.setDeadband(filter.getDeadbandValue)
          case FilterType.DUPLICATES_ONLY =>
        }

        trigger.getActionsList.foreach(a => fillAction(a, filterXml))
        if (trigger.hasStopProcessingWhen) filterXml.setStopProcessingWhen(activationTypeToXml(trigger.getStopProcessingWhen))
        triggersXml.getTriggerGroup.add(filterXml)

      } else {

        val alwaysXml = new Always
        trigger.getActionsList.foreach(a => fillAction(a, alwaysXml))
        if (trigger.hasStopProcessingWhen) alwaysXml.setStopProcessingWhen(activationTypeToXml(trigger.getStopProcessingWhen))
        triggersXml.getTriggerGroup.add(alwaysXml)
      }
    }

    triggersXml
  }

  def xmlActionToProto(xmlAction: ActionType)(implicit context: String): Action = {
    val actType = if (xmlAction.isSetActivation) xmlAction.getActivation else throw new LoadingXmlException(s"Missing activation type for action for $context", xmlAction)

    val b = Action.newBuilder().setType(activationTypeToProto(actType))

    xmlAction match {
      case elem: xml.Suppress =>
        b.setSuppress(true)
      case elem: xml.StripValue =>
        b.setStripValue(true)
      case elem: xml.SetBool =>
        val bValue = if (elem.isSetValue) elem.isValue else throw new LoadingXmlException(s"Missing bool value for SetBool for $context", elem)
        b.setSetBool(bValue)
      case elem: xml.Event =>
        val eventConfig = if (elem.isSetEventType) elem.getEventType else throw new LoadingXmlException(s"Missing event config value for Event action for $context", elem)
        b.setEvent(EventGeneration.newBuilder().setEventType(eventConfig).build())
      case elem: xml.Scale =>
        val scale = if (elem.isSetScale) elem.getScale else throw new LoadingXmlException(s"Missing scale for Scale element for $context", elem)
        val offset = if (elem.isSetOffset) elem.getOffset else throw new LoadingXmlException(s"Missing offset for Scale element for $context", elem)
        val forceToDoubleOpt = if (elem.isSetForceToDouble) Some(elem.getForceToDouble) else None

        val ltb = LinearTransform.newBuilder()
          .setScale(scale)
          .setOffset(offset)

        forceToDoubleOpt.foreach(ltb.setForceToDouble)

        b.setLinearTransform(ltb.build())

      case elem: xml.BoolMapping =>
        val falseString = if (elem.isSetFalseString) elem.getFalseString else throw new LoadingXmlException(s"Missing falseString for BoolMapping element for $context", elem)
        val trueString = if (elem.isSetTrueString) elem.getTrueString else throw new LoadingXmlException(s"Missing trueString for BoolMapping element for $context", elem)
        b.setBoolTransform(BoolEnumTransform.newBuilder().setFalseString(falseString).setTrueString(trueString).build())

      case elem: xml.IntegerMapping =>
        val mappings = elem.getMapping.map { m =>
          val fromInt = if (m.isSetFromInteger) m.getFromInteger else throw new LoadingXmlException(s"Need from integer in IntegerMapping for $context", elem)
          val toString = if (m.isSetToString) m.getToString else throw new LoadingXmlException(s"Need to string in IntegerMapping for $context", elem)
          IntToStringMapping.newBuilder().setValue(fromInt).setString(toString).build()
        }

        b.setIntTransform(IntEnumTransform.newBuilder().addAllMappings(mappings).build())

      case _ => throw new LoadingXmlException(s"Unrecognized action type for $context", xmlAction)
    }

    b.build()
  }

  private def fillAction(action: Action, trigger: TriggerBase): Unit = {
    if (action.hasSuppress) {
      val elem = new xml.Suppress
      elem.setActivation(activationTypeToXml(action.getType))
      trigger.getActionGroup.add(elem)

    } else if (action.hasStripValue) {
      val elem = new StripValue
      elem.setActivation(activationTypeToXml(action.getType))
      trigger.getActionGroup.add(elem)

    } else if (action.hasSetBool) {
      val elem = new SetBool
      elem.setActivation(activationTypeToXml(action.getType))
      elem.setValue(action.getSetBool)
      trigger.getActionGroup.add(elem)

    } else if (action.hasEvent) {
      val elem = new Event
      elem.setActivation(activationTypeToXml(action.getType))
      elem.setEventType(action.getEvent.getEventType)
      trigger.getActionGroup.add(elem)

    } else if (action.hasLinearTransform) {
      val elem = new Scale
      elem.setActivation(activationTypeToXml(action.getType))

      val trans = action.getLinearTransform
      if (trans.hasScale) elem.setScale(trans.getScale)
      if (trans.hasOffset) elem.setOffset(trans.getOffset)
      if (trans.hasForceToDouble) elem.setForceToDouble(trans.getForceToDouble)

      trigger.getActionGroup.add(elem)

    } else if (action.hasBoolTransform) {
      val elem = new BoolMapping
      elem.setActivation(activationTypeToXml(action.getType))

      val boolTrans = action.getBoolTransform
      if (boolTrans.hasFalseString) elem.setFalseString(boolTrans.getFalseString)
      if (boolTrans.hasTrueString) elem.setTrueString(boolTrans.getTrueString)

      trigger.getActionGroup.add(elem)

    } else if (action.hasIntTransform) {
      val elem = new IntegerMapping
      elem.setActivation(activationTypeToXml(action.getType))

      val intTransform = action.getIntTransform
      val xmlMapElems = intTransform.getMappingsList.map { map =>
        val xmlMap = new Mapping
        xmlMap.setFromInteger(map.getValue)
        xmlMap.setToString(map.getString)
        xmlMap
      }
      xmlMapElems.foreach(elem.getMapping.add)

      if (intTransform.hasDefaultValue) elem.setDefaultValue(intTransform.getDefaultValue)

      trigger.getActionGroup.add(elem)
    }
  }

  def activationTypeToXml(typ: ActivationType): ActivationConditionType = {
    typ match {
      case ActivationType.HIGH => ActivationConditionType.HIGH
      case ActivationType.LOW => ActivationConditionType.LOW
      case ActivationType.RISING => ActivationConditionType.RISING
      case ActivationType.FALLING => ActivationConditionType.FALLING
      case ActivationType.TRANSITION => ActivationConditionType.TRANSITION
    }
  }
  def activationTypeToProto(typ: ActivationConditionType): ActivationType = {
    typ match {
      case ActivationConditionType.HIGH => ActivationType.HIGH
      case ActivationConditionType.LOW => ActivationType.LOW
      case ActivationConditionType.RISING => ActivationType.RISING
      case ActivationConditionType.FALLING => ActivationType.FALLING
      case ActivationConditionType.TRANSITION => ActivationType.TRANSITION
    }
  }

}
