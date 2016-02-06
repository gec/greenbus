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
package io.greenbus.services.data

import java.util.UUID
import io.greenbus.client.service.proto.Events.EventConfig.Designation
import io.greenbus.client.service.proto.Events.Alarm.State
import org.squeryl.KeyedEntity

case class EventRow(id: Long,
  eventType: String,
  alarm: Boolean,
  time: Long,
  deviceTime: Option[Long],
  severity: Int,
  subsystem: String,
  userId: String,
  entityId: Option[UUID],
  modelGroup: Option[String],
  args: Array[Byte],
  rendered: String) extends KeyedEntity[Long]

object EventConfigRow {
  val ALARM = Designation.ALARM.getNumber
  val EVENT = Designation.EVENT.getNumber
  val LOG = Designation.LOG.getNumber
}

case class EventConfigRow(
  id: Long,
  eventType: String,
  severity: Int,
  designation: Int, // Alarm, Event, or Log
  alarmState: Int, // Initial alarm start state: UNACK_AUDIBLE, UNACK_SILENT, or ACKNOWLEDGED
  resource: String,
  builtIn: Boolean) extends KeyedEntity[Long]

/**
 * The Model for the Alarm. It's part DB map and part Model.
 */
object AlarmRow {

  // Get the enum values from the proto.
  //
  val UNACK_AUDIBLE = State.UNACK_AUDIBLE.getNumber
  val UNACK_SILENT = State.UNACK_SILENT.getNumber
  val ACKNOWLEDGED = State.ACKNOWLEDGED.getNumber
  val REMOVED = State.REMOVED.getNumber
}

/**
 * The Model for the Alarm. It's part DB map and part Model.
 */
case class AlarmRow(
  id: Long,
  state: Int,
  eventId: Long) extends KeyedEntity[Long]
