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
package io.greenbus.services

import java.util.concurrent.ExecutorService

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.client.ServiceConnection
import io.greenbus.client.proto.Envelope.SubscriptionEventType
import io.greenbus.client.service.proto.Events.{ Alarm, AlarmNotification, Event, EventNotification }
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.client.service.proto.Measurements.{ MeasurementBatchNotification, MeasurementNotification }
import io.greenbus.client.service.proto.Model._
import io.greenbus.client.service.proto.Processing.OverrideNotification
import io.greenbus.jmx.MetricsManager
import io.greenbus.mstore.sql.{ SimpleInTransactionCurrentValueStore, SimpleInTransactionHistoryStore }
import io.greenbus.services.authz.{ AuthLookup, DefaultAuthLookup }
import io.greenbus.services.core._
import io.greenbus.services.framework._
import io.greenbus.services.model._
import io.greenbus.sql.DbConnection

object CoreServices extends Logging {

  def main(args: Array[String]) {

    if (Option(System.getProperty("akka.logger-startup-timeout")).isEmpty) {
      System.setProperty("akka.logger-startup-timeout", "30s")
    }

    val rootConfig = ConfigFactory.load()
    val slf4jConfig = ConfigFactory.parseString("""akka { loggers = ["akka.event.slf4j.Slf4jLogger"] }""")
    val akkaConfig = slf4jConfig.withFallback(rootConfig)
    val system = ActorSystem("services", akkaConfig)

    val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")
    val amqpConfigPath = Option(System.getProperty("io.greenbus.config.amqp")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.msg.amqp.cfg")
    val sqlConfigPath = Option(System.getProperty("io.greenbus.config.sql")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.sql.cfg")

    val mgr = system.actorOf(ServiceManager.props(amqpConfigPath, sqlConfigPath, runServices))
  }

  def runServices(sql: DbConnection, conn: ServiceConnection, exe: ExecutorService) {

    val marshaller = new ServiceMarshaller {
      def run[U](runner: => U) {
        exe.submit(new Runnable {
          def run() {
            runner
          }
        })
      }
    }

    val authModule: AuthenticationModule = new SquerylAuthModule
    val authLookup: AuthLookup = DefaultAuthLookup

    val metricsMgr = MetricsManager("io.greenbus.services")

    val measChannelMgr = new SubscriptionChannelBinderOnly(conn.serviceOperations, classOf[MeasurementNotification].getSimpleName)
    val measBatchChannelMgr = new SubscriptionChannelBinderOnly(conn.serviceOperations, classOf[MeasurementBatchNotification].getSimpleName)

    val entitySubMgr = new SubscriptionChannelManager(EntitySubscriptionDescriptor, conn.serviceOperations, classOf[EntityNotification].getSimpleName)
    val entityEdgeSubMgr = new SubscriptionChannelManager(EntityEdgeSubscriptionDescriptor, conn.serviceOperations, classOf[EntityEdgeNotification].getSimpleName)
    val keyValueSubMgr = new SubscriptionChannelManager(EntityKeyValueSubscriptionDescriptor, conn.serviceOperations, classOf[EntityKeyValueNotification].getSimpleName)

    val overSubMgr = new SubscriptionChannelManager(OverrideSubscriptionDescriptor, conn.serviceOperations, classOf[OverrideNotification].getSimpleName)

    val endpointMgr = new SubscriptionChannelManager(EndpointSubscriptionDescriptor, conn.serviceOperations, classOf[EndpointNotification].getSimpleName)
    val pointMgr = new SubscriptionChannelManager(PointSubscriptionDescriptor, conn.serviceOperations, classOf[PointNotification].getSimpleName)
    val commandMgr = new SubscriptionChannelManager(CommandSubscriptionDescriptor, conn.serviceOperations, classOf[CommandNotification].getSimpleName)
    val commStatusMgr = new SubscriptionChannelManager(FrontEndConnectionStatusSubscriptionDescriptor, conn.serviceOperations, classOf[FrontEndConnectionStatus].getSimpleName)

    val eventMgr = new SubscriptionChannelManager(EventSubscriptionDescriptor, conn.serviceOperations, classOf[EventNotification].getSimpleName)
    val alarmMgr = new SubscriptionChannelManager(AlarmSubscriptionDescriptor, conn.serviceOperations, classOf[AlarmNotification].getSimpleName)

    val eventMapper = new ModelEventMapper
    eventMapper.register(classOf[Entity], entitySubMgr)
    eventMapper.register(classOf[EntityEdge], entityEdgeSubMgr)
    eventMapper.register(classOf[EntityKeyValueWithEndpoint], keyValueSubMgr)
    eventMapper.register(classOf[OverrideWithEndpoint], overSubMgr)
    eventMapper.register(classOf[Endpoint], endpointMgr)
    eventMapper.register(classOf[Point], pointMgr)
    eventMapper.register(classOf[Command], commandMgr)
    eventMapper.register(classOf[Event], eventMgr)
    eventMapper.register(classOf[Alarm], alarmMgr)
    eventMapper.register(classOf[FrontEndConnectionStatus], commStatusMgr)

    val transSource = ServiceTransactionSource(sql, conn.serviceOperations, authLookup, eventMapper)
    val registry = new FullServiceRegistry(sql, conn.serviceOperations, authLookup, eventMapper, metricsMgr)

    val simpleModelNotifier = new SimpleModelNotifier(eventMapper, conn.serviceOperations, ServiceTransactionSource.notificationObserver)

    val loginServices = new LoginServices(registry, sql, authModule, SquerylAuthModel, SquerylEventAlarmModel, simpleModelNotifier)

    val entityServices = new EntityServices(registry, SquerylEntityModel, SquerylFrontEndModel, SquerylEventAlarmModel, entitySubMgr, keyValueSubMgr, entityEdgeSubMgr, endpointMgr, pointMgr, commandMgr)

    val authServices = new AuthServices(registry, SquerylAuthModel)

    val frontEndServices = new FrontEndServices(registry, conn.serviceOperations, SquerylFrontEndModel, commStatusMgr)

    val processingServices = new ProcessingServices(registry, SquerylProcessingModel, SquerylEventAlarmModel, SquerylFrontEndModel, overSubMgr)

    val measurementServices = new MeasurementServices(registry, SimpleInTransactionCurrentValueStore, SimpleInTransactionHistoryStore, measChannelMgr, measBatchChannelMgr, SquerylFrontEndModel)

    val commandServices = new CommandServices(registry, conn.session, transSource, SquerylCommandModel, SquerylFrontEndModel, SquerylEventAlarmModel)

    val eventAlarmServices = new EventAlarmServices(registry, SquerylEventAlarmModel, SquerylEntityModel, eventMgr, alarmMgr)

    logger.info("Binding services")
    registry.getRegistered.foreach {
      case (requestId, handler) =>
        conn.serviceOperations.bindCompetingService(new MarshallingHandler(marshaller, handler), requestId)
    }

    metricsMgr.register()

    logger.info("Services bound")
  }
}

object NotificationConversions {
  def protoType: ModelEvent => SubscriptionEventType = {
    case Created => SubscriptionEventType.ADDED
    case Updated => SubscriptionEventType.MODIFIED
    case Deleted => SubscriptionEventType.REMOVED
  }
}
