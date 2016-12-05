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

import java.util.concurrent.TimeoutException

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.Session
import io.greenbus.client.exception.{ BadRequestException, LockedException, ServiceException }
import io.greenbus.client.proto.Envelope
import io.greenbus.client.service.CommandService
import io.greenbus.client.service.CommandService.Descriptors
import io.greenbus.client.service.proto.CommandRequests._
import io.greenbus.client.service.proto.Commands.{ CommandRequest, CommandResult, CommandStatus }
import io.greenbus.services.framework._
import io.greenbus.services.model.EventAlarmModel.SysEventTemplate
import io.greenbus.services.model.UUIDHelpers._
import io.greenbus.services.model._

import scala.collection.JavaConversions._
import scala.concurrent.Future

object CommandServices {

  val selectResource = "command_lock_select"
  val blockResource = "command_lock_block"
  val lockResource = "command_lock"

  val commandRequestResource = "user_command_request"
  val commandRequestUneventedResource = "user_command_request_unevented"

  val defaultExpireTime = 30000

  val defaultLockPageSize = 200
}

class CommandServices(services: ServiceRegistry, session: Session, trans: ServiceTransactionSource, commandModel: CommandModel, frontEndModel: FrontEndModel, eventModel: EventAlarmModel) extends LazyLogging {
  import io.greenbus.services.core.CommandServices._

  services.simpleAsync(Descriptors.IssueCommandRequest, issueCommandRequest)

  services.fullService(Descriptors.GetCommandLocks, getCommandLocks)
  services.fullService(Descriptors.CommandLockQuery, commandLockQuery)
  services.fullService(Descriptors.SelectCommands, selectCommands)
  services.fullService(Descriptors.BlockCommands, blockCommands)
  services.fullService(Descriptors.DeleteCommandLocks, deleteCommandLocks)

  def issueCommandRequest(request: PostCommandRequestRequest, headers: Map[String, String], responseHandler: Response[PostCommandRequestResponse] => Unit) {

    trans.transaction(headers) { context =>
      if (!request.hasRequest) {
        throw new BadRequestException("Must include request content")
      }

      val cmdReq = request.getRequest

      val unevented = cmdReq.hasUnevented && cmdReq.getUnevented

      val filter = if (unevented) {
        context.auth.authorize(commandRequestUneventedResource, "create")
      } else {
        context.auth.authorize(commandRequestResource, "create")
      }

      if (!cmdReq.hasCommandUuid) {
        throw new BadRequestException("Must include command")
      }

      if (!commandModel.agentCanIssueCommand(cmdReq.getCommandUuid, context.auth.agentId, filter)) {
        throw new LockedException("Command locked")
      }

      val client = CommandService.client(session)
      val resultOpt: Option[(Option[String], String)] = frontEndModel.addressForCommand(cmdReq.getCommandUuid)

      resultOpt match {
        case None => throw new BadRequestException("Frontend serving command not found")
        case Some((None, cmdName)) => Future.successful(CommandResult.newBuilder().setStatus(CommandStatus.TIMEOUT).build())
        case Some((Some(address), cmdName)) =>

          val issued = client.issueCommandRequest(request.getRequest, address)
          import scala.concurrent.ExecutionContext.Implicits.global

          issued.onSuccess {
            case cmdResult =>
              val response = PostCommandRequestResponse.newBuilder().setResult(cmdResult).build
              responseHandler(Success(Envelope.Status.OK, response))
          }

          issued.onFailure {
            case ex: ServiceException => responseHandler(Failure(ex.getStatus, ex.getMessage))
            case ex: TimeoutException =>
              logger.warn("Response timeout from front end connection")
              responseHandler(Failure(Envelope.Status.RESPONSE_TIMEOUT, "Response timeout from front end connection"))
            case ex: Throwable =>
              logger.warn("Command service returned unexpected error: " + ex)
              responseHandler(Failure(Envelope.Status.INTERNAL_ERROR, "Error issuing command request to front end connection"))
          }

          if (!unevented) {
            val event = (cmdReq.hasType && cmdReq.getType != CommandRequest.ValType.NONE) match {
              case false => {
                val attrs = EventAlarmModel.mapToAttributesList(Seq(("command", cmdName)))
                SysEventTemplate(context.auth.agentName, EventSeeding.System.controlExe.eventType, Some("services"), None, None, None, attrs)
              }
              case true => {
                val cmdValue = cmdReq.getType match {
                  case CommandRequest.ValType.DOUBLE => cmdReq.getDoubleVal.toString
                  case CommandRequest.ValType.INT => cmdReq.getIntVal.toString
                  case CommandRequest.ValType.STRING => cmdReq.getStringVal
                  case CommandRequest.ValType.NONE => "-" // should not happen
                }
                val attrs = EventAlarmModel.mapToAttributesList(Seq(("command", cmdName), ("value", cmdValue)))
                SysEventTemplate(context.auth.agentName, EventSeeding.System.updatedSetpoint.eventType, Some("services"), None, None, None, attrs)

              }
            }

            eventModel.postEvents(context.notifier, Seq(event))
          }
      }
    }

  }

  def getCommandLocks(request: GetCommandLockRequest, headers: Map[String, String], context: ServiceContext): Response[GetCommandLockResponse] = {

    val filter = context.auth.authorize(lockResource, "read")

    if (request.getLockIdsCount == 0) {
      throw new BadRequestException("Must include one or more ids")
    }

    val lockIds = request.getLockIdsList.toSeq.map(protoIdToLong)

    val results = commandModel.lockKeyQuery(lockIds, filter)

    val response = GetCommandLockResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def commandLockQuery(request: CommandLockQueryRequest, headers: Map[String, String], context: ServiceContext): Response[CommandLockQueryResponse] = {

    val filter = context.auth.authorize(lockResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getRequest

    val cmds = query.getCommandUuidsList.toSeq map protoUUIDToUuid

    val agents = query.getAgentUuidsList.toSeq map protoUUIDToUuid

    val access = if (query.hasAccess) Some(query.getAccess) else None

    val lastId = if (query.hasLastId) Some(protoIdToLong(query.getLastId)) else None

    val results = commandModel.lockQuery(cmds, agents, access, lastId, defaultLockPageSize, filter)

    val response = CommandLockQueryResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def selectCommands(request: PostCommandSelectRequest, headers: Map[String, String], context: ServiceContext): Response[PostCommandSelectResponse] = {

    val filter = context.auth.authorize(selectResource, "create")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    if (request.getRequest.getCommandUuidsCount == 0) {
      throw new BadRequestException("Must include one or more commands")
    }

    val commands = request.getRequest.getCommandUuidsList.toSeq.map(protoUUIDToUuid)
    val expiryDuration = if (request.getRequest.hasExpireDuration) request.getRequest.getExpireDuration else defaultExpireTime
    val expireTime = System.currentTimeMillis() + expiryDuration
    val agentId = context.auth.agentId

    val result = try {
      commandModel.selectCommands(context.notifier, commands, agentId, expireTime, filter)
    } catch {
      case ex: CommandLockException => throw new LockedException(ex.getMessage)
    }

    val response = PostCommandSelectResponse.newBuilder().setResult(result).build
    Success(Envelope.Status.OK, response)
  }

  def blockCommands(request: PostCommandBlockRequest, headers: Map[String, String], context: ServiceContext): Response[PostCommandBlockResponse] = {

    val filter = context.auth.authorize(blockResource, "create")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    if (request.getRequest.getCommandUuidsCount == 0) {
      throw new BadRequestException("Must include one or more commands")
    }

    val commands = request.getRequest.getCommandUuidsList.toSeq.map(protoUUIDToUuid)
    val agentId = context.auth.agentId

    val result = try {
      commandModel.blockCommands(context.notifier, commands, agentId, filter)
    } catch {
      case ex: CommandLockException => throw new LockedException(ex.getMessage)
    }

    val response = PostCommandBlockResponse.newBuilder().setResult(result).build
    Success(Envelope.Status.OK, response)
  }

  def deleteCommandLocks(request: DeleteCommandLockRequest, headers: Map[String, String], context: ServiceContext): Response[DeleteCommandLockResponse] = {

    val selfOnly = context.auth.authorizeAndCheckSelfOnly(lockResource, "delete")

    if (request.getLockIdsCount == 0) {
      throw new BadRequestException("Must include one or more ids")
    }

    val lockIds = request.getLockIdsList.toSeq.map(protoIdToLong)

    val filter = if (selfOnly) Some(context.auth.agentId) else None

    val results = commandModel.deleteLocks(context.notifier, lockIds, filter)

    val response = DeleteCommandLockResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }
}
