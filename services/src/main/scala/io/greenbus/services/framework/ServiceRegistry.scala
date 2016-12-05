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
package io.greenbus.services.framework

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.jmx.{ Metrics, MetricsManager, MetricsSource, Tag }
import io.greenbus.msg.RequestDescriptor
import io.greenbus.msg.amqp.AmqpServiceOperations
import io.greenbus.msg.service.ServiceHandler
import io.greenbus.services.authz.{ AuthContext, AuthLookup }
import io.greenbus.sql.{ DbConnection, TransactionMetrics, TransactionMetricsListener }
import org.squeryl.logging.StatisticsListener

object ServiceHandlerRegistry {

  def fullHandlerChain[A, B](desc: RequestDescriptor[A, B], handler: TypedServiceHandler[A, B], metrics: Metrics): ServiceHandler = {
    new ServiceHandlerMetricsInstrumenter(desc.requestId, metrics,
      new EnvelopeParsingHandler(
        new DecodingServiceHandler(desc,
          new ModelErrorTransformingHandler(handler))))
  }
}

trait ServiceHandlerRegistry {

  protected val metricsSource: MetricsSource

  private var registered = List.empty[(String, ServiceHandler)]

  def getRegistered: Seq[(String, ServiceHandler)] = registered

  protected def register(requestId: String, handler: ServiceHandler) {
    registered ::= (requestId, handler)
  }

}

trait ServiceRegistry {
  def simpleAsync[A, B](desc: RequestDescriptor[A, B], handler: (A, Map[String, String], (Response[B]) => Unit) => Unit)
  def simpleSync[A, B](desc: RequestDescriptor[A, B], handler: (A, Map[String, String]) => Response[B])

  def fullService[A, B](desc: RequestDescriptor[A, B], handler: (A, Map[String, String], ServiceContext) => Response[B])
}

trait SimpleServiceRegistry extends ServiceHandlerRegistry {

  def simpleAsync[A, B](desc: RequestDescriptor[A, B], handler: (A, Map[String, String], (Response[B]) => Unit) => Unit) {

    val metrics = metricsSource.metrics(desc.requestId, Tag("SubHead", "Handlers"))

    val methodHandler = new TypedServiceHandler[A, B] {
      def handle(request: A, headers: Map[String, String], responseHandler: (Response[B]) => Unit) {
        handler(request, headers, responseHandler)
      }
    }

    val fullHandler = ServiceHandlerRegistry.fullHandlerChain(desc, methodHandler, metrics)
    register(desc.requestId, fullHandler)
  }

  def simpleSync[A, B](desc: RequestDescriptor[A, B], handler: (A, Map[String, String]) => Response[B]) {

    val metrics = metricsSource.metrics(desc.requestId, Tag("SubHead", "Handlers"))

    val methodHandler = new SyncTypedServiceHandler[A, B] {
      def handle(request: A, headers: Map[String, String]): Response[B] = handler(request, headers)
    }

    val fullHandler = ServiceHandlerRegistry.fullHandlerChain(desc, methodHandler, metrics)
    register(desc.requestId, fullHandler)
  }
}

case class ServiceContext(auth: AuthContext, notifier: ModelNotifier)

class FullServiceRegistry(sql: DbConnection, ops: AmqpServiceOperations, auth: AuthLookup, mapper: ModelEventMapper, protected val metricsSource: MetricsSource) extends ServiceRegistry with SimpleServiceRegistry {

  def fullService[A, B](desc: RequestDescriptor[A, B], handler: (A, Map[String, String], ServiceContext) => Response[B]) {

    val metrics = metricsSource.metrics(desc.requestId, Tag("SubHead", "Handlers"))
    val sqlMetrics = new TransactionMetrics(metrics)

    val wrappingHandler = new SyncTypedServiceHandler[A, B] {
      def handle(request: A, headers: Map[String, String]): Response[B] = {
        val listener = new TransactionMetricsListener(request.getClass.getSimpleName)

        val result = ServiceTransactionSource.transaction(sql, ops, auth, mapper, headers, Some(listener)) { context =>
          handler(request, headers, context)
        }

        listener.report(sqlMetrics)

        result
      }
    }

    val fullHandler = ServiceHandlerRegistry.fullHandlerChain(desc, wrappingHandler, metrics)
    register(desc.requestId, fullHandler)
  }
}

trait ServiceTransactionSource {
  def transaction[A](headers: Map[String, String])(handler: ServiceContext => A): A
}

object ServiceTransactionSource extends LazyLogging {

  private object GlobalServiceMetrics {
    val metricsMgr = MetricsManager("io.greenbus.services")
    val metrics = metricsMgr.metrics("Auth")

    val authTime = metrics.timer("AuthTime")

    val notifierMetrics = metricsMgr.metrics("Notifier")
    val notificationAverage = notifierMetrics.average("Average")
    val notificationCount = notifierMetrics.counter("Counter")
    val notificationTime = notifierMetrics.average("FlushTime")
    val notificationTimePerNotification = notifierMetrics.average("FlushTimePerMessage")

    metricsMgr.register()
  }

  import GlobalServiceMetrics._

  def notificationObserver(width: Int, time: Int) = {
    notificationAverage(width)
    notificationCount(width)
    notificationTime(time)
    notificationTimePerNotification(if (width != 0) (time.toDouble / width.toDouble).toInt else 0)
  }

  def apply(sql: DbConnection, ops: AmqpServiceOperations, auth: AuthLookup, mapper: ModelEventMapper): ServiceTransactionSource = {
    new DefaultTransactionSource(sql, ops, auth, mapper)
  }

  def transaction[A](sql: DbConnection, ops: AmqpServiceOperations, auth: AuthLookup, mapper: ModelEventMapper, headers: Map[String, String], listener: Option[StatisticsListener])(handler: ServiceContext => A): A = {

    var transStartEnd = 0L
    // We flush the notifications outside of the transaction so that when observers receive a notification
    // the database is consistent.
    val notificationBuffer = new BufferingModelNotifier(mapper, ops, notificationObserver)
    val result = sql.transaction(listener) {
      val authContext = authTime { auth.validateAuth(headers) }
      val context = ServiceContext(authContext, notificationBuffer)
      val result = handler(context)
      transStartEnd = System.currentTimeMillis()
      result
    }
    logger.trace("Transaction end time: " + (System.currentTimeMillis() - transStartEnd))
    notificationBuffer.flush()
    result
  }

  private class DefaultTransactionSource(sql: DbConnection, ops: AmqpServiceOperations, auth: AuthLookup, mapper: ModelEventMapper) extends ServiceTransactionSource {
    def transaction[A](headers: Map[String, String])(handler: (ServiceContext) => A): A = {
      ServiceTransactionSource.transaction(sql, ops, auth, mapper, headers, None)(handler)
    }
  }

}

