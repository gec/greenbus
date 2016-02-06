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

import akka.actor._
import akka.pattern.{ AskTimeoutException, ask }
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.app.actor.frontend.ActorProtocolManager.CommandIssued
import io.greenbus.client.service.proto.Commands.{ CommandStatus, CommandResult, CommandRequest }
import io.greenbus.client.service.proto.Model.Endpoint

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ActorRefCommandAcceptor(ref: ActorRef, endpoint: Endpoint) extends ProtocolCommandAcceptor with Logging {

  def issue(commandName: String, request: CommandRequest): Future[CommandResult] = {
    val askFut = ref.ask(CommandIssued(commandName, request))(4000.milliseconds)

    askFut.flatMap {
      case cmdFut: Future[_] => cmdFut.asInstanceOf[Future[CommandResult]]
      case all =>
        logger.error("Endpoint " + endpoint.getName + " received unknown response from protocol master actor: " + all)
        Future.successful(CommandResult.newBuilder().setStatus(CommandStatus.UNDEFINED).build())
    }.recover {
      case at: AskTimeoutException =>
        logger.warn("Endpoint " + endpoint.getName + " could not issue command to protocol master actor")
        CommandResult.newBuilder().setStatus(CommandStatus.TIMEOUT).build()

      case ex =>
        logger.warn("Endpoint " + endpoint.getName + " had error communicating with protocol master actor: " + ex)
        CommandResult.newBuilder().setStatus(CommandStatus.UNDEFINED).build()
    }
  }
}
