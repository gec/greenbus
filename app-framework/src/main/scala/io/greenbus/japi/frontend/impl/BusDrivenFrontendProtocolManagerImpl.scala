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

import akka.actor.{ ActorRef, Props, ActorContext, ActorSystem }
import io.greenbus.app.actor._
import io.greenbus.app.actor.frontend._
import io.greenbus.client.ServiceConnection
import io.greenbus.client.service.proto.Model.Endpoint
import io.greenbus.japi.frontend.BusDrivenProtocolFactory
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpServiceOperations

class BusDrivenFrontendProtocolManagerImpl(
    processName: String,
    amqpConfig: AmqpConnectionConfig,
    userConfigPath: String,
    registrationConfig: FrontendRegistrationConfig,
    nodeId: String,
    endpointStrategy: EndpointCollectionStrategy,
    factory: BusDrivenProtocolFactory) {

  private val system = ActorSystem(processName)

  def start(): Unit = {

    def factoryForEndpointMonitor(endpoint: Endpoint, session: Session, serviceOps: AmqpServiceOperations): Props = {

      BusDrivenWithCommandsProtocolEndpoint.props(
        endpoint,
        session,
        serviceOps,
        BusDrivenWithCommandsProtocolEndpoint.register(_, _, _, _, lockingEnabled = false, nodeId)(scala.concurrent.ExecutionContext.Implicits.global),
        ProcessingProxy.props(_, _,
          statusHeartbeatPeriodMs = 5000,
          lapsedTimeMs = 11000,
          statusRetryPeriodMs = 2000,
          measRetryPeriodMs = 2000,
          measQueueLimit = 1000),
        SimpleCommandProxy.props,
        BusDrivenProtocolHost.props(_, _, _, _, factory),
        registrationRetryMs = 5000)
    }

    def connMgr() = {

      def endMon(endpoint: Endpoint, coll: CollectionMembership, session: Session, ops: AmqpServiceOperations) = {
        EndpointMonitor.props(endpoint, coll, session, ops, factoryForEndpointMonitor)
      }

      def sessionMgr(conn: ServiceConnection): Props = {
        SessionLoginManager.props("Endpoint Session Manager", userConfigPath, conn,
          registrationConfig.loginRetryMs,
          factory = EndpointCollectionManager.props(endpointStrategy, _, _, None, endMon))
      }

      FailoverConnectionManager.props("Endpoint Connection Bridge",
        amqpConfig.amqpConfigFileList,
        failureLimit = amqpConfig.failureLimit,
        retryDelayMs = amqpConfig.retryDelayMs,
        connectionTimeoutMs = amqpConfig.connectionTimeoutMs,
        sessionMgr)
    }

    val mgr = system.actorOf(connMgr())
  }

  def shutdown(): Unit = {
    system.shutdown()
  }
}