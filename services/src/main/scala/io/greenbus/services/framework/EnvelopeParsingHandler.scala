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

import com.google.protobuf.{ ByteString, InvalidProtocolBufferException }
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.service.ServiceHandler
import io.greenbus.client.proto.Envelope
import io.greenbus.client.proto.Envelope.{ ServiceRequest, ServiceResponse }

import scala.collection.JavaConversions._

object EnvelopeParsingHandler {
  def buildErrorEnvelope(status: Envelope.Status, message: String): ServiceResponse = {
    ServiceResponse.newBuilder()
      .setStatus(status)
      .setErrorMessage(message)
      .build()
  }
}

class EnvelopeParsingHandler(handler: ExtractedServiceHandler) extends ServiceHandler with LazyLogging {
  import io.greenbus.services.framework.EnvelopeParsingHandler._

  def handleMessage(msg: Array[Byte], responseHandler: (Array[Byte]) => Unit) {

    def onResponse(result: Response[Array[Byte]]) {
      result match {
        case Failure(status, message) =>
          val responseBytes = buildErrorEnvelope(status, message).toByteArray
          responseHandler(responseBytes)

        case Success(status, bytes) =>
          val responseBytes = ServiceResponse.newBuilder()
            .setStatus(status)
            .setPayload(ByteString.copyFrom(bytes))
            .build()
            .toByteArray

          responseHandler(responseBytes)
      }
    }

    def internalError(ex: Throwable) {
      logger.error("Internal service error: " + ex)
      val response = buildErrorEnvelope(Envelope.Status.INTERNAL_ERROR, "Internal service error.")
      responseHandler(response.toByteArray)
    }

    try {
      val requestEnvelope = ServiceRequest.parseFrom(msg)
      val headers: Map[String, String] = requestEnvelope.getHeadersList.map(hdr => (hdr.getKey, hdr.getValue)).toMap
      if (requestEnvelope.hasPayload) {
        val payload = requestEnvelope.getPayload.toByteArray
        handler.handle(payload, headers, onResponse)
      }
    } catch {
      case protoEx: InvalidProtocolBufferException =>
        logger.warn("Error parsing client request: " + protoEx)
        val response = buildErrorEnvelope(Envelope.Status.BAD_REQUEST, "Couldn't parse service request.")
        responseHandler(response.toByteArray)
      case ex: Throwable =>
        internalError(ex)
    }
  }

}
