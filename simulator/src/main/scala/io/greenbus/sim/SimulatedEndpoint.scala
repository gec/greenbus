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
package io.greenbus.sim

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import akka.actor.{ Actor, Props }
import play.api.libs.json.Json
import scala.concurrent.duration._
import scala.collection.JavaConversions._
import io.greenbus.client.service.proto.Commands.{ CommandStatus, CommandResult }
import io.greenbus.client.service.proto.Model.{ EntityKeyValue, ModelUUID, Endpoint }
import io.greenbus.app.actor.frontend.{ StackStatusUpdated, MeasurementsPublished, ProtocolConfigurer }
import scala.concurrent.Future

object SimulatedEndpoint extends ProtocolConfigurer[SimulatorEndpointConfig] with LazyLogging {

  def extractConfig(config: EntityKeyValue): Option[SimulatorEndpointConfig] = {
    if (config.getValue.hasByteArrayValue) {
      try {
        val json = Json.parse(config.getValue.getByteArrayValue.toByteArray).as[SimulatorEndpointConfig]
        Some(json)
      } catch {
        case ex: Throwable =>
          logger.warn("Couldn't unmarshal configuration: " + ex)
          None
      }
    } else {
      None
    }
  }

  def evaluate(endpoint: Endpoint, configFiles: Seq[EntityKeyValue]): Option[SimulatorEndpointConfig] = {
    configFiles.find(kv => kv.getKey == "protocolConfig").flatMap(extractConfig)
  }

  def equivalent(latest: SimulatorEndpointConfig, previous: SimulatorEndpointConfig): Boolean = {
    latest == previous
  }

  case object Tick

  def props(endpointUuid: ModelUUID, endpointName: String, protocolConfig: SimulatorEndpointConfig, publish: (MeasurementsPublished) => Unit, statusUpdate: (StackStatusUpdated) => Unit): Props = {
    Props(classOf[SimulatedEndpoint], endpointUuid, endpointName, protocolConfig, publish, statusUpdate)
  }

}

class SimulatedEndpoint(endpointUuid: ModelUUID, endpointName: String, config: SimulatorEndpointConfig, publish: (MeasurementsPublished) => Unit, statusUpdate: (StackStatusUpdated) => Unit) extends Actor with LazyLogging {
  import SimulatedEndpoint._
  import io.greenbus.app.actor.frontend.ActorProtocolManager.CommandIssued

  private val delay = config.delay.map(_.milliseconds).getOrElse(2000.milliseconds)
  private val simulation = new EndpointSimulation(config.measurements)

  logger.info("Initializing simulated endpoint " + endpointName + ", point count: " + config.measurements.size + ", with period " + delay)

  import context.dispatcher

  statusUpdate(StackStatusUpdated(FrontEndConnectionStatus.Status.COMMS_UP))
  self ! Tick

  def receive = {

    case Tick => {
      val measurements = simulation.tick()
      if (measurements.nonEmpty) {
        publish(MeasurementsPublished(System.currentTimeMillis(), Seq(), measurements))
      }
      scheduleNext()
    }

    case CommandIssued(cmdName, cmdReq) => {
      val cmdId = cmdReq.getCommandUuid

      logger.info("Command request for " + cmdId.getValue)

      sender ! Future.successful(CommandResult.newBuilder().setStatus(CommandStatus.NOT_SUPPORTED).build())
    }
  }

  private def scheduleNext() {
    context.system.scheduler.scheduleOnce(
      delay,
      self,
      Tick)
  }
}