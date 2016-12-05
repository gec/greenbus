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

import akka.actor.{ Actor, ActorRef, Props, _ }
import akka.pattern.ask
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.SubscriptionBinding
import io.greenbus.app.actor.frontend.CommandProxy.CommandSubscriptionRetrieved
import io.greenbus.client.service.proto.Commands.{ CommandRequest, CommandResult, CommandStatus }
import io.greenbus.client.service.proto.Model.Endpoint

import scala.concurrent.Future
import scala.concurrent.duration._

object SimpleCommandProxy {

  case class ProtocolInitialized(subject: ActorRef)
  case object ProtocolUninitialized

  private case class ReceivedCommandIssued(req: CommandRequest, response: CommandResult => Unit)

  def props(endpoint: Endpoint): Props = Props(classOf[SimpleCommandProxy], endpoint)
}

class SimpleCommandProxy(endpoint: Endpoint) extends Actor with LazyLogging {
  import io.greenbus.app.actor.frontend.SimpleCommandProxy._
  import context.dispatcher

  private var commandSub = Option.empty[SubscriptionBinding]
  private var subjectOpt = Option.empty[ActorRef]

  def receive = {
    case ReceivedCommandIssued(req, responseHandler) => {
      subjectOpt match {
        case None =>
          logger.debug(s"Endpoint ${endpoint.getName} saw command request while protocol uninitialized: " + req.getCommandUuid.getValue)
          responseHandler(CommandResult.newBuilder().setStatus(CommandStatus.TIMEOUT).build())
        case Some(subject) =>
          logger.debug(s"Endpoint ${endpoint.getName} handling command request")

          val askFut = subject.ask(req)(5000.milliseconds)

          val resultFut = askFut.flatMap {
            case fut: Future[_] => fut.asInstanceOf[Future[CommandResult]]
            case all =>
              logger.error("Endpoint " + endpoint.getName + " received unknown response from protocol master actor: " + all)
              Future.successful(CommandResult.newBuilder().setStatus(CommandStatus.UNDEFINED).build())
          }

          resultFut.onSuccess {
            case result: CommandResult =>
              logger.debug(s"Endpoint ${endpoint.getName} saw command result: " + result.getStatus)
              responseHandler(result)
          }
          resultFut.onFailure {
            case ex: Throwable =>
              logger.error(s"Command acceptor for endpoint ${endpoint.getName} failed: " + ex)
              responseHandler(CommandResult.newBuilder().setStatus(CommandStatus.UNDEFINED).build())
          }
      }
    }

    case CommandSubscriptionRetrieved(sub) => {
      commandSub.foreach(_.cancel())
      commandSub = Some(sub)
      val cmdDelegate = new DelegatingCommandHandler((req, resultAccept) => self ! ReceivedCommandIssued(req, resultAccept))
      sub.start(cmdDelegate)
    }

    case ProtocolInitialized(subject) => subjectOpt = Some(subject)

    case ProtocolUninitialized => subjectOpt = None
  }
}
