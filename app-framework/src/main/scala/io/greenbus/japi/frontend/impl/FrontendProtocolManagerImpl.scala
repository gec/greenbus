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
package io.greenbus.japi.frontend.impl

import java.util.UUID

import akka.actor.{ ActorContext, ActorSystem }
import io.greenbus.app.actor._
import io.greenbus.app.actor.frontend.{ MasterProtocol => ScalaMasterProtocol, FrontendRegistrationConfig, FrontendRegistrationConfig$, FrontendFactory }
import io.greenbus.japi.frontend.{ MasterProtocol => JavaMasterProtocol, ProtocolConfigurer => JavaProtocolConfigurer }
import scala.collection.JavaConversions._

class FrontendProtocolManagerImpl[ProtocolConfig](
    protocol: JavaMasterProtocol[ProtocolConfig],
    protocolConfigurer: JavaProtocolConfigurer[ProtocolConfig],
    configKeys: java.util.List[String],
    endpointStrategy: EndpointCollectionStrategy,
    amqpConfigPath: String,
    userConfigPath: String) {

  private val system = ActorSystem("FrontendProtocolManager")

  def start(): Unit = {

    val configurer = new ProtocolConfigurerShim(protocolConfigurer)

    def protocolMgrFactory(context: ActorContext): ScalaMasterProtocol[ProtocolConfig] = new MasterProtocolShim[ProtocolConfig](protocol)

    system.actorOf(FrontendFactory.create(
      AmqpConnectionConfig.default(amqpConfigPath), userConfigPath, endpointStrategy, protocolMgrFactory, configurer, configKeys.toSeq,
      connectionRepresentsLock = false,
      nodeId = UUID.randomUUID().toString,
      FrontendRegistrationConfig.defaults))
  }

  def shutdown(): Unit = {
    system.shutdown()
  }
}
