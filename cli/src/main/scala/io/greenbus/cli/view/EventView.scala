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
package io.greenbus.cli.view

import io.greenbus.client.service.proto.Events.{ Event, Alarm, EventConfig }

object EventConfigView extends TableView[EventConfig] {
  def header: List[String] = "EventType" :: "Dest" :: "Sev" :: "Audible" :: "Resources" :: "BuiltIn" :: Nil

  def row(obj: EventConfig): List[String] = {
    obj.getEventType ::
      obj.getDesignation.toString ::
      obj.getSeverity.toString ::
      (obj.getAlarmState == Alarm.State.UNACK_AUDIBLE).toString ::
      obj.getResource ::
      obj.getBuiltIn.toString :: Nil
  }
}

object EventView extends TableView[Event] {
  def header: List[String] = "ID" :: "EventType" :: "IsAlarm" :: "Time" :: "Sev" :: "Agent" :: "DeviceTime" :: "Subsystem" :: "Message" :: Nil

  def row(obj: Event): List[String] = {
    obj.getId.getValue ::
      obj.getEventType ::
      obj.getAlarm.toString ::
      new java.util.Date(obj.getTime).toString ::
      obj.getSeverity.toString ::
      obj.getAgentName ::
      (if (obj.hasDeviceTime) new java.util.Date(obj.getDeviceTime).toString else "") ::
      obj.getSubsystem ::
      obj.getRendered :: Nil
  }
}

object AlarmView extends TableView[Alarm] {
  def header: List[String] = "ID" :: "State" :: "EventType" :: "Time" :: "Sev" :: "Agent" :: "DeviceTime" :: "Subsystem" :: "Message" :: Nil

  def row(obj: Alarm): List[String] = {
    obj.getId.getValue ::
      obj.getState.toString ::
      obj.getEvent.getEventType ::
      new java.util.Date(obj.getEvent.getTime).toString ::
      obj.getEvent.getSeverity.toString ::
      obj.getEvent.getAgentName ::
      (if (obj.getEvent.hasDeviceTime) new java.util.Date(obj.getEvent.getDeviceTime).toString else "") ::
      obj.getEvent.getSubsystem ::
      obj.getEvent.getRendered :: Nil
  }
}

