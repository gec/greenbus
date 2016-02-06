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
package io.greenbus.ldr.auth

import io.greenbus.msg.Session
import io.greenbus.client.service.AuthService
import io.greenbus.client.service.proto.Auth.{ EntitySelector, Permission }
import io.greenbus.client.service.proto.AuthRequests.{ PermissionSetTemplate, AgentTemplate }
import io.greenbus.ldr.auth.AuthMdl.{ PermissionSet, Agent, AuthSet }
import io.greenbus.loader.set.{ PrintTracker, UUIDHelpers }
import io.greenbus.util.Timing

import scala.collection.JavaConversions._

object Upload {

  import io.greenbus.loader.set.Upload._

  def upload(session: Session, mdl: AuthSet) = {
    val timer = Timing.Stopwatch.start
    val tracker = new PrintTracker(60, System.out)

    val authClient = AuthService.client(session)

    println("\n== Progress:")

    val permSets = chunkedCalls(allChunkSize, mdl.permSets.map(toTemplate), authClient.putPermissionSets, tracker)

    val agents = chunkedCalls(allChunkSize, mdl.agents.map(toTemplate), authClient.putAgents, tracker)

    println("\n")
    println(s"== Finished in ${timer.elapsed} ms")
  }

  def toTemplate(agent: Agent): AgentTemplate = {
    val b = AgentTemplate.newBuilder()
      .setName(agent.name)
      .addAllPermissionSets(agent.permissions)

    agent.uuidOpt.map(UUIDHelpers.uuidToProtoUUID).foreach(b.setUuid)
    agent.passwordOpt.foreach(b.setPassword)

    b.build()
  }

  def toTemplate(permSet: PermissionSet): PermissionSetTemplate = {

    val b = PermissionSetTemplate.newBuilder()
      .setName(permSet.name)
      .addAllPermissions(permSet.permissions.map(toProto))

    permSet.idOpt.map(UUIDHelpers.longToProtoId).foreach(b.setId)

    b.build()
  }

  def toProto(mdl: AuthMdl.Permission): Permission = {
    Permission.newBuilder()
      .setAllow(mdl.allow)
      .addAllActions(mdl.actions)
      .addAllResources(mdl.resources)
      .addAllSelectors(mdl.selectors.map(toProto))
      .build()
  }

  def toProto(mdl: AuthMdl.EntitySelector): EntitySelector = {
    EntitySelector.newBuilder()
      .setStyle(mdl.style)
      .addAllArguments(mdl.args)
      .build()
  }
}
