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

import java.sql.SQLException

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.RequestDescriptor
import io.greenbus.msg.service.ServiceHandler
import io.greenbus.client.exception.ServiceException
import io.greenbus.client.proto.Envelope
import io.greenbus.jmx.Metrics
import io.greenbus.services.model.{ ModelInputException, ModelPermissionException }

import scala.collection.JavaConversions._

class DecodingServiceHandler[A, B](decoder: RequestDescriptor[A, B], handler: TypedServiceHandler[A, B]) extends ExtractedServiceHandler {
  def handle(request: Array[Byte], headers: Map[String, String], responseHandler: Response[Array[Byte]] => Unit): Unit = {

    def onResponse(result: Response[B]) {
      result match {
        case Failure(status, message) => responseHandler(Failure(status, message))
        case Success(status, obj) =>
          val bytes = decoder.encodeResponse(obj)
          val mapped = Success(status, bytes)
          responseHandler(mapped)
      }
    }

    val requestObj = decoder.decodeRequest(request)
    handler.handle(requestObj, headers, onResponse)
  }
}

class TypedServiceInstrumenter[A, B](requestId: String, handler: TypedServiceHandler[A, B], metrics: Metrics) extends TypedServiceHandler[A, B] with LazyLogging {

  val counter = metrics.counter("Count")
  val handleTime = metrics.average("HandleTime")

  def handle(request: A, headers: Map[String, String], responseHandler: (Response[B]) => Unit): Unit = {
    counter(1)
    val startTime = System.currentTimeMillis()

    def logHandled() {
      val elapsed = System.currentTimeMillis() - startTime
      logger.debug(s"Handled $requestId in $elapsed")
      handleTime(elapsed.toInt)
    }

    def onResponse(response: Response[B]): Unit = {
      logHandled()
      responseHandler(response)
    }

    try {
      handler.handle(request, headers, onResponse)
    } catch {
      case ex: Throwable =>
        logHandled()
        throw ex
    }
  }
}

class ServiceHandlerMetricsInstrumenter(requestId: String, metrics: Metrics, handler: ServiceHandler) extends ServiceHandler with LazyLogging {

  val counter = metrics.counter("Count")
  val handleTime = metrics.average("HandleTime")

  def handleMessage(request: Array[Byte], responseHandler: Array[Byte] => Unit): Unit = {
    counter(1)
    val startTime = System.currentTimeMillis()

    def logHandled() {
      val elapsed = System.currentTimeMillis() - startTime
      logger.debug(s"Handled $requestId in $elapsed")
      handleTime(elapsed.toInt)
    }

    def onResponse(response: Array[Byte]): Unit = {
      logHandled()
      responseHandler(response)
    }

    handler.handleMessage(request, onResponse)
  }
}

class ModelErrorTransformingHandler[A, B](handler: TypedServiceHandler[A, B]) extends TypedServiceHandler[A, B] with LazyLogging {
  def handle(request: A, headers: Map[String, String], responseHandler: (Response[B]) => Unit): Unit = {
    try {
      handler.handle(request, headers, responseHandler)

    } catch {
      case ex: ModelPermissionException =>
        responseHandler(Failure(Envelope.Status.FORBIDDEN, ex.getMessage))
      case ex: ModelInputException =>
        responseHandler(Failure(Envelope.Status.BAD_REQUEST, ex.getMessage))
      case rse: ServiceException =>
        responseHandler(Failure(rse.getStatus, rse.getMessage))
      case sqlEx: SQLException =>
        if (sqlEx.iterator().toList.exists(ex => ex.getClass == classOf[java.net.ConnectException])) {
          logger.error("Database connection error: " + sqlEx.getMessage)
          responseHandler(Failure(Envelope.Status.BUS_UNAVAILABLE, "Data unavailable."))
        } else {
          val next = Option(sqlEx.getNextException)
          next.foreach(ex => logger.error("Sql next exception: " + ex))
          logger.error("Internal service error: " + sqlEx)
          responseHandler(Failure(Envelope.Status.BUS_UNAVAILABLE, "Data unavailable."))
        }
      case ex: IllegalArgumentException =>
        responseHandler(Failure(Envelope.Status.BAD_REQUEST, ex.getMessage))
    }
  }
}
