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

import io.greenbus.client.service.proto.Commands.{ CommandResult, CommandRequest }
import io.greenbus.msg.service.ServiceHandler
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.client.proto.Envelope.{ ServiceResponse, ServiceRequest }
import io.greenbus.client.exception.{ ServiceException, BadRequestException }
import io.greenbus.client.service.proto.CommandRequests.{ PostCommandRequestResponse, PostCommandRequestRequest }
import io.greenbus.client.proto.Envelope
import com.google.protobuf.InvalidProtocolBufferException

class DelegatingCommandHandler(handler: (CommandRequest, CommandResult => Unit) => Unit) extends ServiceHandler with Logging {

  def handleMessage(msg: Array[Byte], responseHandler: (Array[Byte]) => Unit) {

    try {
      val requestEnvelope = ServiceRequest.parseFrom(msg)

      if (!requestEnvelope.hasPayload) {
        throw new BadRequestException("Must include request payload")
      }
      val payload = requestEnvelope.getPayload.toByteArray

      val request = PostCommandRequestRequest.parseFrom(payload)
      if (!request.hasRequest) {
        throw new BadRequestException("Must include command request")
      }

      val cmdReq = request.getRequest

      if (!cmdReq.hasCommandUuid) {
        throw new BadRequestException("Must include command id")
      }

      def handleResult(result: CommandResult) {
        val response = PostCommandRequestResponse.newBuilder().setResult(result).build()

        val respEnvelope = ServiceResponse.newBuilder()
          .setStatus(Envelope.Status.OK)
          .setPayload(response.toByteString)
          .build()

        responseHandler(respEnvelope.toByteArray)
      }

      handler(cmdReq, handleResult)

    } catch {
      case ex: InvalidProtocolBufferException =>
        responseHandler(buildErrorEnvelope(Envelope.Status.BAD_REQUEST, "Could not parse request").toByteArray)
      case rse: ServiceException =>
        responseHandler(buildErrorEnvelope(rse.getStatus, rse.getMessage).toByteArray)
      case ex: Throwable =>
        logger.error("Error handling command request: " + ex)
        responseHandler(buildErrorEnvelope(Envelope.Status.INTERNAL_ERROR, "Error handling command request").toByteArray)
    }
  }

  private def buildErrorEnvelope(status: Envelope.Status, message: String): ServiceResponse = {
    ServiceResponse.newBuilder()
      .setStatus(status)
      .setErrorMessage(message)
      .build()
  }
}
