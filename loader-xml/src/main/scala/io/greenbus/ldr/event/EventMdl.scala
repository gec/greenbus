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
package io.greenbus.ldr.event

import io.greenbus.ldr.xml.events.{ EventConfigs, Events, EventConfig }
import io.greenbus.client.service.proto.Events.Alarm
import io.greenbus.client.service.proto.Events.EventConfig.Designation
import io.greenbus.loader.set.LoadingException

import scala.collection.JavaConversions._

object EventMdl {

  case class EventConfig(typ: String, severity: Int, designation: Designation, initialAlarmState: Option[Alarm.State], resource: String)

}

object EventConversion {

  def toXml(all: Seq[EventMdl.EventConfig]): Events = {
    val xml = new Events

    val coll = new EventConfigs
    all.map(toXml).foreach(coll.getEventConfig.add)

    xml.setEventConfigs(coll)

    xml
  }

  def toXml(mdl: EventMdl.EventConfig): EventConfig = {
    val xml = new EventConfig
    xml.setEventType(mdl.typ)
    xml.setSeverity(mdl.severity)
    xml.setDesignation(toXml(mdl.designation))
    xml.setValue(mdl.resource)
    mdl.initialAlarmState.map(toXml).foreach(xml.setInitialAlarmState)
    xml
  }

  def toXml(des: Designation): String = {
    des match {
      case Designation.EVENT => "EVENT"
      case Designation.ALARM => "ALARM"
      case Designation.LOG => "LOG"
    }
  }

  def toXml(alarmState: Alarm.State): String = {
    alarmState match {
      case Alarm.State.UNACK_AUDIBLE => "UNACK_AUDIBLE"
      case Alarm.State.UNACK_SILENT => "UNACK_SILENT"
      case Alarm.State.ACKNOWLEDGED => "ACKNOWLEDGED"
      case Alarm.State.REMOVED => "REMOVED"
    }
  }

  def toModel(xml: Events): Seq[EventMdl.EventConfig] = {
    if (xml.isSetEventConfigs) {
      xml.getEventConfigs.getEventConfig.map(toModel)
    } else {
      Seq()
    }
  }

  def toModel(eventConfig: EventConfig): EventMdl.EventConfig = {

    val eventType = if (eventConfig.isSetEventType) eventConfig.getEventType else throw new LoadingException("EventConfig must include event type")
    val severity = if (eventConfig.isSetSeverity) eventConfig.getSeverity else throw new LoadingException(s"EventConfig $eventType must include severity")
    val designationStr = if (eventConfig.isSetDesignation) eventConfig.getDesignation else throw new LoadingException(s"EventConfig $eventType must include designation")
    val initialAlarmStrOpt = if (eventConfig.isSetInitialAlarmState) Some(eventConfig.getInitialAlarmState) else None
    val resource = if (eventConfig.isSetValue) eventConfig.getValue else throw new LoadingException(s"EventConfig $eventType must include resource string")

    EventMdl.EventConfig(eventType, severity, desigToModel(designationStr), initialAlarmStrOpt.map(stateToModel), resource)
  }

  def desigToModel(des: String): Designation = {
    des match {
      case "EVENT" => Designation.EVENT
      case "ALARM" => Designation.ALARM
      case "LOG" => Designation.LOG
    }
  }

  def stateToModel(alarmState: String): Alarm.State = {
    alarmState match {
      case "UNACK_AUDIBLE" => Alarm.State.UNACK_AUDIBLE
      case "UNACK_SILENT" => Alarm.State.UNACK_SILENT
      case "ACKNOWLEDGED" => Alarm.State.ACKNOWLEDGED
      case "REMOVED" => Alarm.State.REMOVED
    }
  }

}

