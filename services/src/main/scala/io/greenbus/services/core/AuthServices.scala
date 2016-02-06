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
package io.greenbus.services.core

import java.util.UUID

import io.greenbus.client.exception.{ ForbiddenException, BadRequestException }
import io.greenbus.client.proto.Envelope
import io.greenbus.client.service.AuthService.Descriptors
import io.greenbus.client.service.proto.Auth.Permission
import io.greenbus.client.service.proto.AuthRequests._
import io.greenbus.services.framework.{ ServiceContext, Success, _ }
import io.greenbus.services.model.AuthModel
import io.greenbus.services.model.AuthModel.{ AgentInfo, CoreUntypedTemplate, PermissionSetInfo }
import io.greenbus.services.model.UUIDHelpers._

import scala.collection.JavaConversions._

object AuthServices {

  val defaultPageSize = 200

  val agentResource = "agent"
  val agentPasswordResource = "agent_password"
  val permissionSetResource = "agent_permissions"

  def validatePermissionSet(template: PermissionSetTemplate) {
    if (!template.hasName) {
      throw new BadRequestException("Permission set must include name")
    }
    EntityServices.validateEntityName(template.getName)

    if (template.getPermissionsCount == 0) {
      throw new BadRequestException("Permission set must include at least one permission")
    }

    template.getPermissionsList.foreach(validatePermission)
  }

  def validatePermission(perm: Permission) {
    if (!perm.hasAllow) {
      throw new BadRequestException("Permission must specify allow/deny")
    }
    if (perm.getResourcesCount == 0) {
      throw new BadRequestException("Permission must include a resource; add '*' for all")
    }
    if (perm.getActionsCount == 0) {
      throw new BadRequestException("Permission must include an action; add '*' for all")
    }

    perm.getSelectorsList.foreach { selector =>
      if (!selector.hasStyle) {
        throw new BadRequestException("Permission's entity selector must include style")
      }
      if ((selector.getStyle == "type" || selector.getStyle == "parent") && selector.getArgumentsCount == 0) {
        throw new BadRequestException(s"Entity selectors of style ${selector.getStyle} must include arguments")
      }
    }
  }
}

class AuthServices(services: ServiceRegistry, authModel: AuthModel) {
  import io.greenbus.services.core.AuthServices._

  services.fullService(Descriptors.GetAgents, getAgents)
  services.fullService(Descriptors.AgentQuery, agentQuery)
  services.fullService(Descriptors.PutAgents, putAgents)
  services.fullService(Descriptors.PutAgentPasswords, putAgentPasswords)
  services.fullService(Descriptors.DeleteAgents, deleteAgents)
  services.fullService(Descriptors.GetPermissionSets, getPermissionSets)
  services.fullService(Descriptors.PermissionSetQuery, permissionSetQuery)
  services.fullService(Descriptors.PutPermissionSets, putPermissionSets)
  services.fullService(Descriptors.DeletePermissionSets, deletePermissionSets)

  def getAgents(request: GetAgentsRequest, headers: Map[String, String], context: ServiceContext): Response[GetAgentsResponse] = {

    val selfOnly = context.auth.authorizeAndCheckSelfOnly(agentResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val keySet = request.getRequest

    if (keySet.getUuidsCount == 0 && keySet.getNamesCount == 0) {
      throw new BadRequestException("Must include at least one id or name")
    }

    val uuids = keySet.getUuidsList.toSeq.map(protoUUIDToUuid)
    val names = keySet.getNamesList.toSeq

    val results = if (selfOnly) {
      if (uuids.contains(context.auth.agentId) || names.contains(context.auth.agentName)) {
        authModel.agentKeyQuery(Seq(context.auth.agentId), Seq())
      } else {
        Seq()
      }
    } else {
      authModel.agentKeyQuery(uuids, names)
    }

    val response = GetAgentsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def agentQuery(request: AgentQueryRequest, headers: Map[String, String], context: ServiceContext): Response[AgentQueryResponse] = {

    val selfOnly = context.auth.authorizeAndCheckSelfOnly(agentResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getRequest

    val (pageByName, lastUuid, lastName, pageSize) = EntityServices.parsePageParams(query.hasPagingParams, query.getPagingParams)

    val permSetNames = query.getPermissionSetsList.toSeq

    val selfOpt = if (selfOnly) Some(context.auth.agentId) else None

    val results = authModel.agentQuery(selfOpt, permSetNames, lastUuid, lastName, pageSize, pageByName)

    val response = AgentQueryResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def putAgents(request: PutAgentsRequest, headers: Map[String, String], context: ServiceContext): Response[PutAgentsResponse] = {

    context.auth.authorizeUnconditionallyOnly(agentResource, "create")
    context.auth.authorizeUnconditionallyOnly(agentResource, "update")

    if (request.getTemplatesCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val templates = request.getTemplatesList.toSeq

    val modelTemplates = templates.map { template =>
      if (!template.hasName) {
        throw new BadRequestException("Must include agent name")
      }

      EntityServices.validateEntityName(template.getName)

      val passwordOpt = if (template.hasPassword) Some(template.getPassword) else None

      val uuidOpt = if (template.hasUuid) Some(protoUUIDToUuid(template.getUuid)) else None

      CoreUntypedTemplate(uuidOpt, template.getName, AgentInfo(passwordOpt, template.getPermissionSetsList.toSeq))
    }

    val results = authModel.putAgents(context.notifier, modelTemplates, true)

    val response = PutAgentsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def putAgentPasswords(request: PutAgentPasswordsRequest, headers: Map[String, String], context: ServiceContext): Response[PutAgentPasswordsResponse] = {

    val selfOnly = context.auth.authorizeAndCheckSelfOnly(agentPasswordResource, "update")

    if (request.getUpdatesCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val updates = request.getUpdatesList.toSeq.map { update =>
      val uuid: UUID = if (update.hasUuid) update.getUuid else throw new BadRequestException("Must include UUID of Agent")
      val password = if (update.hasPassword) update.getPassword else throw new BadRequestException("Must include new password")

      (uuid, password)
    }

    if (selfOnly) {
      updates match {
        case Seq(update) =>
          if (update._1 != context.auth.agentId) {
            throw new ForbiddenException("Insufficient permissions")
          }
        case _ => throw new ForbiddenException("Insufficient permissions")
      }
    }

    val results = authModel.modifyAgentPasswords(updates)

    val response = PutAgentPasswordsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def deleteAgents(request: DeleteAgentsRequest, headers: Map[String, String], context: ServiceContext): Response[DeleteAgentsResponse] = {

    context.auth.authorizeUnconditionallyOnly(agentResource, "delete")

    if (request.getAgentUuidsCount == 0) {
      throw new BadRequestException("Must include ids to delete")
    }

    val results = authModel.deleteAgents(context.notifier, request.getAgentUuidsList.map(protoUUIDToUuid))

    val response = DeleteAgentsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def getPermissionSets(request: GetPermissionSetsRequest, headers: Map[String, String], context: ServiceContext): Response[GetPermissionSetsResponse] = {

    val selfOnly = context.auth.authorizeAndCheckSelfOnly(permissionSetResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val keySet = request.getRequest

    if (keySet.getIdsCount == 0 && keySet.getNamesCount == 0) {
      throw new BadRequestException("Must include at least one id or name.")
    }

    val uuids = keySet.getIdsList.toSeq.map(protoIdToLong)
    val names = keySet.getNamesList.toSeq

    val results = if (selfOnly) {
      authModel.permissionSetSelfKeyQuery(context.auth.agentId, uuids, names)
    } else {
      authModel.permissionSetKeyQuery(uuids, names)
    }

    val response = GetPermissionSetsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def permissionSetQuery(request: PermissionSetQueryRequest, headers: Map[String, String], context: ServiceContext): Response[PermissionSetQueryResponse] = {

    val selfOnly = context.auth.authorizeAndCheckSelfOnly(permissionSetResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getRequest

    val lastId = if (query.hasLastId) Some(protoIdToLong(query.getLastId)) else None
    val pageSize = if (query.hasPageSize) query.getPageSize else defaultPageSize

    val results = if (selfOnly) {
      authModel.permissionSetSelfQuery(context.auth.agentId, lastId, pageSize)
    } else {
      authModel.permissionSetQuery(lastId, pageSize)
    }

    val response = PermissionSetQueryResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def putPermissionSets(request: PutPermissionSetsRequest, headers: Map[String, String], context: ServiceContext): Response[PutPermissionSetsResponse] = {

    context.auth.authorizeUnconditionallyOnly(permissionSetResource, "create")
    context.auth.authorizeUnconditionallyOnly(permissionSetResource, "update")

    if (request.getTemplatesCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val templates = request.getTemplatesList.toSeq

    templates.foreach(validatePermissionSet)

    val modelTemplates = templates.map { template =>

      val uuidOpt = if (template.hasId) Some(protoIdToLong(template.getId)) else None

      CoreUntypedTemplate(uuidOpt, template.getName, PermissionSetInfo(template.getPermissionsList.toSeq))
    }

    val results = authModel.putPermissionSets(context.notifier, modelTemplates, true)

    val response = PutPermissionSetsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def deletePermissionSets(request: DeletePermissionSetsRequest, headers: Map[String, String], context: ServiceContext): Response[DeletePermissionSetsResponse] = {

    context.auth.authorizeUnconditionallyOnly(permissionSetResource, "delete")

    if (request.getPermissionSetIdsCount == 0) {
      throw new BadRequestException("Must include ids to delete")
    }

    val uuids = request.getPermissionSetIdsList.map(protoIdToLong)

    val results = authModel.deletePermissionSets(context.notifier, uuids)

    val response = DeletePermissionSetsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

}