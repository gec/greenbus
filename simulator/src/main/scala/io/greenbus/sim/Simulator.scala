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

import java.util.UUID

import akka.actor.{ ActorContext, ActorSystem }
import com.typesafe.config.ConfigFactory
import io.greenbus.app.actor.{ AmqpConnectionConfig, ProtocolsEndpointStrategy }
import io.greenbus.app.actor.frontend.{ FrontendRegistrationConfig, FrontendRegistrationConfig$, ActorProtocolManager, FrontendFactory }

object Simulator {

  def main(args: Array[String]) {

    if (Option(System.getProperty("akka.logger-startup-timeout")).isEmpty) {
      System.setProperty("akka.logger-startup-timeout", "30s")
    }

    val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")
    val amqpConfigPath = Option(System.getProperty("io.greenbus.config.amqp")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.msg.amqp.cfg")
    val userConfigPath = Option(System.getProperty("io.greenbus.config.user")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.user.cfg")

    val rootConfig = ConfigFactory.load()
    val slf4jConfig = ConfigFactory.parseString("""akka { loggers = ["akka.event.slf4j.Slf4jLogger"] }""")
    val akkaConfig = slf4jConfig.withFallback(rootConfig)
    val system = ActorSystem("simulator", akkaConfig)

    val endpointStrategy = new ProtocolsEndpointStrategy(Set("simulator", "benchmark"))

    def protocolFactory(context: ActorContext) = new ActorProtocolManager[SimulatorEndpointConfig](context, SimulatedEndpoint.props)

    system.actorOf(FrontendFactory.create(
      AmqpConnectionConfig.default(amqpConfigPath), userConfigPath, endpointStrategy, protocolFactory, SimulatedEndpoint, Seq("protocolConfig"),
      connectionRepresentsLock = false,
      nodeId = UUID.randomUUID().toString,
      FrontendRegistrationConfig.defaults))
  }

}

