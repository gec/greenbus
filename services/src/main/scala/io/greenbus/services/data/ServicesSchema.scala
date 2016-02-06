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

import org.squeryl.{ KeyedEntity, Schema }
import org.squeryl.PrimitiveTypeMode._
import java.util.UUID

trait EntityBased extends KeyedEntity[Long] {
  def id: Long
  def entityId: UUID
}

case class EntityRow(id: UUID, name: String) extends KeyedEntity[UUID]

case class EntityTypeRow(entityId: UUID, entType: String)

case class EntityEdgeRow(id: Long,
  parentId: UUID,
  childId: UUID,
  relationship: String,
  distance: Int) extends KeyedEntity[Long]

case class EntityKeyStoreRow(id: Long, uuid: UUID, key: String, data: Array[Byte]) extends KeyedEntity[Long]

object ServicesSchema extends Schema {

  // Auth
  val agents = table[AgentRow]
  val permissionSets = table[PermissionSetRow]
  val authTokens = table[AuthTokenRow]

  on(authTokens)(s => declare(s.token is (indexed), s.expirationTime is (indexed)))
  on(agents)(s => declare(
    s.name is (unique, indexed)))
  on(permissionSets)(s => declare(
    s.name is (unique, indexed)))

  val tokenSetJoins = table[AuthTokenPermissionSetJoinRow]
  val agentSetJoins = table[AgentPermissionSetJoinRow]

  on(tokenSetJoins)(s => declare(s.authTokenId is (indexed)))
  on(agentSetJoins)(s => declare(s.agentId is (indexed)))

  // Model
  val entities = table[EntityRow]
  val edges = table[EntityEdgeRow]
  val entityTypes = table[EntityTypeRow]

  val entityKeyValues = table[EntityKeyStoreRow]

  on(entities)(s => declare(
    s.name is (unique, indexed)))
  on(edges)(s => declare(
    columns(s.parentId, s.childId, s.relationship) are (unique),
    columns(s.childId, s.relationship) are (indexed),
    columns(s.parentId, s.relationship) are (indexed)))
  on(entityTypes)(s => declare(
    s.entType is (indexed),
    s.entityId is (indexed)))

  on(entityKeyValues)(s => declare(
    columns(s.uuid, s.key) are (unique, indexed)))

  val points = table[PointRow]
  val commands = table[CommandRow]
  val endpoints = table[EndpointRow]

  on(points)(s => declare(
    s.entityId is (unique, indexed)))
  on(commands)(s => declare(
    s.entityId is (unique, indexed)))
  on(endpoints)(s => declare(
    s.entityId is (unique, indexed)))

  val locks = table[CommandLockRow]
  val lockJoins = table[LockJoinRow]

  val frontEndConnections = table[FrontEndConnectionRow]
  val frontEndCommStatuses = table[FrontEndCommStatusRow]

  val events = table[EventRow]
  val eventConfigs = table[EventConfigRow]
  val alarms = table[AlarmRow]

  on(events)(s => declare(
    s.time is (indexed)))
  on(alarms)(s => declare(
    s.eventId is (indexed)))

  def reset() {
    drop
    create
  }
}
