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

import akka.actor.{ ActorContext, ActorRef, Props }
import io.greenbus.app.actor._
import io.greenbus.client.ServiceConnection
import io.greenbus.client.service.proto.Model.Endpoint
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpServiceOperations

import scala.concurrent.ExecutionContext.Implicits.global

object FrontendFactory {

  def create[ProtocolConfig](
    amqpConfig: AmqpConnectionConfig,
    userConfigPath: String,
    endpointStrategy: EndpointCollectionStrategy,
    protocolMgrFactory: ActorContext => MasterProtocol[ProtocolConfig],
    configurer: ProtocolConfigurer[ProtocolConfig],
    configKeys: Seq[String],
    connectionRepresentsLock: Boolean,
    nodeId: String,
    registrationConfig: FrontendRegistrationConfig) = {

    def connMgr(observer: ActorRef) = {

      def endMon(endpoint: Endpoint, coll: CollectionMembership, session: Session, ops: AmqpServiceOperations) = {
        FepEndpointMonitor.props(endpoint, coll, session)
      }

      def sessionMgr(conn: ServiceConnection): Props = {
        SessionLoginManager.props("Endpoint Session Manager", userConfigPath, conn,
          registrationConfig.loginRetryMs,
          factory = FepEndpointCollectionManager.props(endpointStrategy, _, _, Some(observer), endMon))
      }

      FailoverConnectionManager.props("Endpoint Connection Bridge",
        amqpConfig.amqpConfigFileList,
        failureLimit = amqpConfig.failureLimit,
        retryDelayMs = amqpConfig.retryDelayMs,
        connectionTimeoutMs = amqpConfig.connectionTimeoutMs,
        sessionMgr)
    }

    def child(endpoint: Endpoint, manager: ActorRef, masterProtocol: MasterProtocol[ProtocolConfig]) = {

      def proxyFactory(subject: ActorRef, endpoint: Endpoint) = {
        ProcessingProxy.props(
          subject,
          endpoint,
          registrationConfig.statusHeartbeatPeriodMs,
          registrationConfig.lapsedTimeMs,
          registrationConfig.statusRetryPeriodMs,
          registrationConfig.measRetryPeriodMs,
          registrationConfig.measQueueLimit)
      }

      def updaterFactory(
        subject: ActorRef,
        endpoint: Endpoint,
        session: Session,
        config: FrontendConfiguration) = {
        FrontendConfigUpdater.props(subject, endpoint, session, config, registrationConfig.configRequestRetryMs)
      }

      FrontendProtocolEndpoint.props(
        endpoint,
        manager,
        masterProtocol,
        configurer,
        registrationConfig.registrationRetryMs,
        registrationConfig.releaseTimeoutMs,
        proxyFactory,
        CommandProxy.props,
        updaterFactory,
        FrontendProtocolEndpoint.register(_, _, _, _, connectionRepresentsLock, nodeId),
        FrontendConfigurer.frontendConfiguration(_, _, configKeys))
    }

    FrontEndSetManager.props(connMgr, child, protocolMgrFactory)

  }
}
