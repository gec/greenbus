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
package io.greenbus.measproc

import java.util.UUID

import akka.actor._
import com.typesafe.config.ConfigFactory
import io.greenbus.app.actor.{ AllEndpointsStrategy, ConnectedApplicationManager, EndpointCollectionManager, EndpointMonitor }
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.client.service.proto.Model.Endpoint
import io.greenbus.client.service.{ FrontEndService, MeasurementService }
import io.greenbus.measproc.EndpointStreamManager.StreamResources
import io.greenbus.measproc.lock.TransactionPostgresLockModel
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpServiceOperations
import io.greenbus.mstore.sql.SqlCurrentValueAndHistorian
import io.greenbus.services.authz.DefaultAuthLookup
import io.greenbus.services.core.FrontEndConnectionStatusSubscriptionDescriptor
import io.greenbus.services.framework.{ ModelEventMapper, SimpleModelNotifier, SubscriptionChannelManager }
import io.greenbus.services.model.SquerylFrontEndModel
import io.greenbus.sql.{ DbConnection, DbConnector, SqlSettings }

object MeasurementProcessor {

  private def buildModelNotifier(serviceOps: AmqpServiceOperations) = {

    val commsStatus = new SubscriptionChannelManager(FrontEndConnectionStatusSubscriptionDescriptor, serviceOps, classOf[FrontEndConnectionStatus].getSimpleName)

    val eventMapper = new ModelEventMapper
    eventMapper.register(classOf[FrontEndConnectionStatus], commsStatus)

    new SimpleModelNotifier(eventMapper, serviceOps, (_, _) => {})
  }

  def buildProcessor(
    amqpConfigPath: String,
    sqlConfigPath: String,
    userConfigPath: String,
    heartbeatTimeoutMs: Long,
    nodeId: String,
    standbyLockRetryPeriodMs: Long = 5000,
    standbyLockExpiryDurationMs: Long = 12500): Props = {

    val collectionStrategy = new AllEndpointsStrategy
    val markRetryTimeoutMs = 2000

    def connectSql(): DbConnection = {
      val config = SqlSettings.load(sqlConfigPath)
      DbConnector.connect(config)
    }

    def processFactory(session: Session, serviceOps: AmqpServiceOperations, sql: DbConnection): Props = {

      serviceOps.declareExchange(FrontEndService.Descriptors.PutFrontEndRegistration.requestId)
      serviceOps.declareExchange(MeasurementService.Descriptors.PostMeasurements.requestId)
      serviceOps.declareExchange(FrontEndService.Descriptors.PutFrontEndConnectionStatuses.requestId)

      val resources = StreamResources(session, serviceOps, new SqlCurrentValueAndHistorian(sql))

      val notifier = buildModelNotifier(serviceOps)

      val lockService = new TransactionPostgresLockModel(sql)

      def bootstrapperFactory(endpoint: Endpoint, config: EndpointConfiguration): Props = {
        EndpointStreamManager.props(endpoint, resources, config, sql, DefaultAuthLookup, SquerylFrontEndModel, notifier, lockService,
          nodeId, heartbeatTimeoutMs, markRetryTimeoutMs, standbyLockRetryPeriodMs, standbyLockExpiryDurationMs)
      }

      def endMgrFactory(endpoint: Endpoint, session: Session, serviceOps: AmqpServiceOperations): Props = {
        EndpointStreamBootstrapper.props(endpoint, session, bootstrapperFactory)
      }

      EndpointCollectionManager.props(collectionStrategy, session, serviceOps, None,
        EndpointMonitor.props(_, _, _, _, endMgrFactory))
    }

    ConnectedApplicationManager.props("Measurement Processor", amqpConfigPath, userConfigPath, processFactory(_, _, connectSql()))
  }

  def main(args: Array[String]) {

    val heartbeatTimeoutMs = 10000

    if (Option(System.getProperty("akka.logger-startup-timeout")).isEmpty) {
      System.setProperty("akka.logger-startup-timeout", "30s")
    }

    val rootConfig = ConfigFactory.load()
    val slf4jConfig = ConfigFactory.parseString("""akka { loggers = ["akka.event.slf4j.Slf4jLogger"] }""")
    val akkaConfig = slf4jConfig.withFallback(rootConfig)
    val system = ActorSystem("measproc", akkaConfig)

    val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")
    val amqpConfigPath = Option(System.getProperty("io.greenbus.config.amqp")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.msg.amqp.cfg")
    val sqlConfigPath = Option(System.getProperty("io.greenbus.config.sql")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.sql.cfg")
    val userConfigPath = Option(System.getProperty("io.greenbus.config.user")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.user.cfg")

    val mgr = system.actorOf(buildProcessor(amqpConfigPath, sqlConfigPath, userConfigPath, heartbeatTimeoutMs, UUID.randomUUID().toString))
  }
}

