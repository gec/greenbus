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
package io.greenbus.calc

import java.util.UUID

import akka.actor.{ ActorSystem, Props }
import com.typesafe.config.ConfigFactory
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpServiceOperations
import io.greenbus.app.actor._
import io.greenbus.app.actor.frontend.{ BusDrivenProtocolEndpoint, ProcessingProxy }
import io.greenbus.calc.lib.eval.BasicOperations
import io.greenbus.client.service.proto.Model.Endpoint

object CalculationEngine {

  def main(args: Array[String]) {

    val opSource = BasicOperations.getSource

    val rootConfig = ConfigFactory.load()
    val slf4jConfig = ConfigFactory.parseString("""akka { loggers = ["akka.event.slf4j.Slf4jLogger"] }""")
    val akkaConfig = slf4jConfig.withFallback(rootConfig)
    val system = ActorSystem("calculation", akkaConfig)

    val nodeId = UUID.randomUUID().toString

    val endpointStrategy = new ProtocolsEndpointStrategy(Set("calculator"))

    val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")
    val amqpConfigPath = Option(System.getProperty("io.greenbus.config.amqp")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.msg.amqp.cfg")
    val userConfigPath = Option(System.getProperty("io.greenbus.config.user")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.user.cfg")

    def factoryForEndpointMonitor(endpoint: Endpoint, session: Session, serviceOps: AmqpServiceOperations): Props = {

      BusDrivenProtocolEndpoint.props(
        endpoint,
        session,
        BusDrivenProtocolEndpoint.register(_, _, _, lockingEnabled = false, nodeId)(scala.concurrent.ExecutionContext.Implicits.global),
        ProcessingProxy.props(_, _,
          statusHeartbeatPeriodMs = 5000,
          lapsedTimeMs = 11000,
          statusRetryPeriodMs = 2000,
          measRetryPeriodMs = 2000,
          measQueueLimit = 1000),
        CalculationEndpointManager.props(_, _, _, _, opSource),
        registrationRetryMs = 5000)
    }

    val mgr = system.actorOf(
      ConnectedApplicationManager.props(
        "Calculation Engine",
        amqpConfigPath,
        userConfigPath,
        EndpointCollectionManager.props(endpointStrategy, _, _, None,
          EndpointMonitor.props(_, _, _, _, factoryForEndpointMonitor))))
  }
}
