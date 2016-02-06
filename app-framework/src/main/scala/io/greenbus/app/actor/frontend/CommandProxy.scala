/**
 * Copyright 2011-2016 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.greenbus.app.actor.frontend

import akka.actor.{ Props, Actor }
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.msg.SubscriptionBinding
import io.greenbus.msg.service.ServiceHandlerSubscription
import io.greenbus.client.service.proto.Commands.{ CommandStatus, CommandResult, CommandRequest }
import io.greenbus.client.service.proto.Model.{ Endpoint, ModelUUID, Command }

import scala.concurrent.Future

object CommandProxy {

  case class CommandsUpdated(commands: Seq[Command])

  case class CommandSubscriptionRetrieved(commandSub: ServiceHandlerSubscription)

  case class ProtocolInitialized(acceptor: ProtocolCommandAcceptor)
  case object ProtocolUninitialized

  private case class CommandIssued(req: CommandRequest, response: CommandResult => Unit)

  def props(endpoint: Endpoint): Props = Props(classOf[CommandProxy], endpoint)
}

class CommandProxy(endpoint: Endpoint) extends Actor with Logging {
  import CommandProxy._
  import context.dispatcher

  private var commandSub = Option.empty[SubscriptionBinding]
  private var commandAcceptor = Option.empty[ProtocolCommandAcceptor]
  private var commandMap = Map.empty[ModelUUID, String]

  def receive = {
    case CommandIssued(req, responseHandler) => {
      commandAcceptor match {
        case None =>
          logger.debug(s"Endpoint ${endpoint.getName} saw command request while protocol uninitialized: " + req.getCommandUuid.getValue)
          responseHandler(CommandResult.newBuilder().setStatus(CommandStatus.TIMEOUT).build())
        case Some(acceptor) =>
          commandMap.get(req.getCommandUuid) match {
            case None => logger.warn(s"Saw command request with missing command for ${endpoint.getName}: " + req.getCommandUuid.getValue)
            case Some(name) =>
              logger.debug(s"Endpoint ${endpoint.getName} handling command request: " + name)
              val fut = try {
                acceptor.issue(name, req)
              } catch {
                case ex: Throwable =>
                  logger.warn(s"Immediate command failure for endpoint ${endpoint.getName}: " + ex)
                  Future.failed(ex)
              }
              fut.onSuccess { case result => responseHandler(result) }
              fut.onFailure {
                case ex: Throwable =>
                  logger.error(s"Command acceptor for endpoint ${endpoint.getName} failed: " + ex)
                  responseHandler(CommandResult.newBuilder().setStatus(CommandStatus.UNDEFINED).build())
              }
          }
      }
    }

    case CommandsUpdated(updated) => {
      commandMap = updated.map(c => (c.getUuid, c.getName)).toMap
    }

    case CommandSubscriptionRetrieved(sub) => {
      commandSub.foreach(_.cancel())
      commandSub = Some(sub)
      val cmdDelegate = new DelegatingCommandHandler((req, resultAccept) => self ! CommandIssued(req, resultAccept))
      sub.start(cmdDelegate)
    }

    case ProtocolInitialized(acceptor) => commandAcceptor = Some(acceptor)

    case ProtocolUninitialized => commandAcceptor = None
  }
}
