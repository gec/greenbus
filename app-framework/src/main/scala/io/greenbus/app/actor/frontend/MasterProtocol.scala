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
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.app.actor.util.TraceMessage
import io.greenbus.client.service.proto.Commands.{ CommandRequest, CommandResult, CommandStatus }
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.client.service.proto.Model.{ Endpoint, EntityKeyValue, ModelUUID }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

trait ProtocolConfigurer[ProtocolConfig] {
  def evaluate(endpoint: Endpoint, keyValues: Seq[EntityKeyValue]): Option[ProtocolConfig]
  def equivalent(latest: ProtocolConfig, previous: ProtocolConfig): Boolean
}

case class MeasurementsPublished(wallTime: Long, idMeasurements: Seq[(ModelUUID, Measurement)], namedMeasurements: Seq[(String, Measurement)]) extends TraceMessage

case class StackStatusUpdated(state: FrontEndConnectionStatus.Status)

trait MasterProtocol[ProtocolConfig] {

  def add(endpoint: Endpoint, protocolConfig: ProtocolConfig, publish: MeasurementsPublished => Unit, statusUpdate: StackStatusUpdated => Unit): ProtocolCommandAcceptor

  def remove(endpointUuid: ModelUUID)

  def shutdown()

}

object ActorProtocolManager {

  case class CommandIssued(commandName: String, request: CommandRequest)

  type ActorProtocolFactory[ProtocolConfig] = (ModelUUID, String, ProtocolConfig, MeasurementsPublished => Unit, StackStatusUpdated => Unit) => Props
}

class ActorProtocolManager[ProtocolConfig](context: ActorContext, factory: ActorProtocolManager.ActorProtocolFactory[ProtocolConfig]) extends MasterProtocol[ProtocolConfig] with LazyLogging {
  import io.greenbus.app.actor.frontend.ActorProtocolManager._

  private var actors = Map.empty[ModelUUID, ActorRef]

  def add(endpoint: Endpoint, protocolConfig: ProtocolConfig, publish: (MeasurementsPublished) => Unit, statusUpdate: (StackStatusUpdated) => Unit): ProtocolCommandAcceptor = {
    actors.get(endpoint.getUuid).foreach(_ ! PoisonPill)
    val ref = context.actorOf(factory(endpoint.getUuid, endpoint.getName, protocolConfig, publish, statusUpdate))
    actors += (endpoint.getUuid -> ref)

    new ProtocolCommandAcceptor {

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
  }

  def remove(endpointUuid: ModelUUID) {
    actors.get(endpointUuid).foreach { ref =>
      ref ! PoisonPill
      actors -= endpointUuid
    }
  }

  def shutdown() {
    actors.foreach { case (_, ref) => ref ! PoisonPill }
  }

}
