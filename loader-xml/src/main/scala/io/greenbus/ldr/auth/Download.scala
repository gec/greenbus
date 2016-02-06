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

import java.util.UUID

import io.greenbus.client.service.proto.ModelRequests.EntityPagingParams
import io.greenbus.msg.Session
import io.greenbus.client.service.AuthService
import io.greenbus.client.service.proto.Auth.{ Permission, Agent, PermissionSet }
import io.greenbus.client.service.proto.AuthRequests.{ AgentKeySet, AgentQuery, PermissionSetQuery }
import io.greenbus.client.service.proto.Model.{ ModelUUID, ModelID }
import io.greenbus.ldr.auth.Download.AuthDownloadSet
import io.greenbus.loader.set.{ UUIDHelpers, Downloader }

import scala.collection.JavaConversions._
import scala.concurrent.duration._

import scala.concurrent.{ Await, Future }

object Download {

  case class AuthDownloadSet(perms: Seq[PermissionSet], agents: Seq[Agent])

  def getAgents(session: Session, uuids: Seq[UUID], names: Seq[String]): Seq[Agent] = {
    val client = AuthService.client(session)

    val allKeys = uuids ++ names

    allKeys.grouped(300).flatMap { keyGroup =>
      val keySet = AgentKeySet.newBuilder()

      keyGroup.foreach {
        case uuid: UUID => keySet.addUuids(UUIDHelpers.uuidToProtoUUID(uuid))
        case name: String => keySet.addNames(name)
      }

      Await.result(client.getAgents(keySet.build()), 10000.milliseconds)
    }.toVector
  }

  def download(session: Session) = {
    val client = AuthService.client(session)

    def permQuery(last: Option[ModelID], size: Int): Future[Seq[PermissionSet]] = {
      val b = PermissionSetQuery.newBuilder().setPageSize(size)
      last.foreach(b.setLastId)
      client.permissionSetQuery(b.build())
    }

    def permPageBoundary(results: Seq[PermissionSet]): ModelID = {
      results.last.getId
    }

    val permSets = Downloader.allPages(200, permQuery, permPageBoundary)

    def agentQuery(last: Option[ModelUUID], size: Int): Future[Seq[Agent]] = {

      val pageB = EntityPagingParams.newBuilder().setPageSize(size)

      last.foreach(pageB.setLastUuid)

      client.agentQuery(AgentQuery.newBuilder().setPagingParams(pageB.build()).build())
    }

    def agentPageBoundary(results: Seq[Agent]): ModelUUID = {
      results.last.getUuid
    }

    val agents = Downloader.allPages(200, agentQuery, agentPageBoundary)

    AuthDownloadSet(permSets, agents)
  }

}

object DownloadConversion {

  def toIntermediate(downloadSet: AuthDownloadSet): AuthMdl.AuthSet = {
    val permSets = downloadSet.perms.map { permSet =>
      AuthMdl.PermissionSet(Some(UUIDHelpers.protoIdToLong(permSet.getId)), permSet.getName, permSet.getPermissionsList.map(permissionToMdl))
    }

    val agents = downloadSet.agents.map { agent =>
      AuthMdl.Agent(Some(UUIDHelpers.protoUUIDToUuid(agent.getUuid)), agent.getName, agent.getPermissionSetsList.toSeq)
    }

    AuthMdl.AuthSet(permSets, agents)
  }

  def permissionToMdl(proto: Permission): AuthMdl.Permission = {
    val allow = proto.getAllow
    val actions = proto.getActionsList.toSeq
    val resources = proto.getResourcesList.toSeq

    val selectors = proto.getSelectorsList.map { selector =>
      AuthMdl.EntitySelector(selector.getStyle, selector.getArgumentsList.toSeq)
    }

    AuthMdl.Permission(allow, resources = resources, actions = actions, selectors)
  }

}
