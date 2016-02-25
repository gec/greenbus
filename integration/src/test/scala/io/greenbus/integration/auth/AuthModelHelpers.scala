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
package io.greenbus.integration.auth

import java.util.UUID

import io.greenbus.client.service.proto.Auth.{ PermissionSet, Permission, EntitySelector }
import io.greenbus.client.service.proto.Model._
import io.greenbus.client.service.proto.ModelRequests._
import io.greenbus.services.data.{ AgentRow, PermissionSetRow, AgentPermissionSetJoinRow, ServicesSchema }

object AuthModelHelpers {
  def addPermissionToAgent(setId: Long, agentId: UUID) {
    ServicesSchema.agentSetJoins.insert(AgentPermissionSetJoinRow(setId, agentId))
  }

  def buildSelector(style: String, args: Seq[String] = Seq.empty[String]): Unit = {
    val b = EntitySelector.newBuilder()
      .setStyle(style)

    args.foreach(b.addArguments)

    b.build()
  }

  def createPermission(allow: Boolean, resource: String, action: String, selectors: Seq[EntitySelector] = Seq.empty[EntitySelector]): Permission = {
    val b = Permission.newBuilder()
      .setAllow(allow)
      .addResources(resource)
      .addActions(action)

    selectors.foreach(b.addSelectors)

    b.build()
  }

  def createPermissionSet(name: String, perms: Seq[Permission] = Seq.empty[Permission]): PermissionSetRow = {
    val b = PermissionSet.newBuilder
      .setName(name)

    perms.foreach(b.addPermissions)

    val set = b.build()

    ServicesSchema.permissionSets.insert(PermissionSetRow(0, name, set.toByteArray))
  }

  def createAgent(name: String, password: String = "password", perms: Set[Long] = Set.empty[Long]): AgentRow = {
    val a = ServicesSchema.agents.insert(AgentRow(UUID.randomUUID(), name, password))
    perms.foreach(addPermissionToAgent(_, a.id))
    a
  }

  def entTemplate(name: String, types: Seq[String] = Seq("type01")): EntityTemplate = {
    val b = EntityTemplate.newBuilder().setName(name)
    types.foreach(b.addTypes)
    b.build()
  }
  def pointTemplate(name: String, category: PointCategory = PointCategory.ANALOG): PointTemplate = {
    PointTemplate.newBuilder().setEntityTemplate(entTemplate(name)).setPointCategory(category).setUnit("unit01").build()
  }
  def commandTemplate(name: String, category: CommandCategory = CommandCategory.CONTROL): CommandTemplate = {
    CommandTemplate.newBuilder().setEntityTemplate(entTemplate(name)).setCategory(category).setDisplayName("display01").build()
  }
  def endpointTemplate(name: String, protocol: String = "protocol01"): EndpointTemplate = {
    EndpointTemplate.newBuilder().setEntityTemplate(entTemplate(name)).setDisabled(false).setProtocol(protocol).build()
  }
  def edgeTemplate(entA: ModelUUID, entB: ModelUUID): EntityEdgeDescriptor = {
    EntityEdgeDescriptor.newBuilder().setParentUuid(entA).setRelationship("rel01").setChildUuid(entB).build()
  }
  def kvTemplate(entA: ModelUUID, key: String = "key01", value: String = "value01"): EntityKeyValue = {
    EntityKeyValue.newBuilder().setUuid(entA).setKey(key).setValue(StoredValue.newBuilder().setStringValue(value).build()).build()
  }

}
