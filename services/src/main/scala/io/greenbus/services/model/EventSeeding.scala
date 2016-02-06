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
package io.greenbus.services.model

import io.greenbus.services.data.{ AlarmRow, EventConfigRow }

object EventSeeding {

  import EventConfigRow.{ ALARM, EVENT }

  case class EventTypeConfig(eventType: String, designation: Int, severity: Int, resource: String)

  object System {

    val userLogin = EventTypeConfig("System.UserLogin", EVENT, 5, "User logged in: {user}")
    val userLoginFailure = EventTypeConfig("System.UserLoginFailure", ALARM, 1, "User login failed {reason}")
    val userLogout = EventTypeConfig("System.UserLogout", EVENT, 5, "User logged out")

    val controlExe = EventTypeConfig("System.ControlIssued", EVENT, 3, "Executed control {command}")
    val updatedSetpoint = EventTypeConfig("System.SetpointIssued", EVENT, 3, "Updated setpoint {command} to {value}")
    val setOverride = EventTypeConfig("System.SetOverride", EVENT, 3, "Point overridden: {point}")
    val setNotInService = EventTypeConfig("System.SetNotInService", EVENT, 3, "Point removed from service: {point}")
    val removeOverride = EventTypeConfig("System.RemoveOverride", EVENT, 3, "Removed override on point: {point}")
    val removeNotInService = EventTypeConfig("System.RemoveNotInService", EVENT, 3, "Returned point to service: {point}")

    val endpointDisabled = EventTypeConfig("System.EndpointDisabled", EVENT, 3, "Endpoint {name} disabled")
    val endpointEnabled = EventTypeConfig("System.EndpointEnabled", EVENT, 3, "Endpoint {name} enabled")

    val all = Seq(
      userLogin,
      userLoginFailure,
      controlExe,
      updatedSetpoint,
      setOverride,
      setNotInService,
      removeOverride,
      removeNotInService,
      endpointDisabled,
      endpointEnabled)
  }

  def toRow(cfg: EventTypeConfig): EventConfigRow = {
    import cfg._
    EventConfigRow(0, eventType, severity, designation, AlarmRow.UNACK_SILENT, resource, true)
  }

  def seed() {

    val events = System.all.map(toRow)

    import io.greenbus.services.data.ServicesSchema._
    eventConfigs.insert(events)
  }
}
