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
package io.greenbus.client

import com.google.protobuf.ByteString
import scala.util.{ Success, Failure, Try }
import io.greenbus.msg.RequestMessagingCodec
import io.greenbus.client.proto.Envelope.{ ServiceResponse, RequestHeader, ServiceRequest }
import io.greenbus.client.exception.{ RequestException, MalformedResponseException, StatusCodes }

object ServiceMessagingCodec {

  def codec: RequestMessagingCodec = FullCodec

  private object FullCodec extends RequestMessagingCodec {

    private def requestBuilder(requestId: String, headers: Map[String, String], payload: Array[Byte]): ServiceRequest.Builder = {
      val builder = ServiceRequest.newBuilder
        .setPayload(ByteString.copyFrom(payload))

      headers.foreach { case (key, v) => builder.addHeaders(RequestHeader.newBuilder.setKey(key).setValue(v)) }

      builder
    }

    def encode(requestId: String, headers: Map[String, String], payload: Array[Byte]): Array[Byte] = {
      val requestEnv = requestBuilder(requestId, headers, payload)
      requestEnv.build().toByteArray
    }

    def encodeSubscription(requestId: String, headers: Map[String, String], payload: Array[Byte], subscriptionId: String): Array[Byte] = {
      val requestEnv = requestBuilder(requestId, headers, payload)
      requestEnv.addHeaders(
        RequestHeader.newBuilder
          .setKey("__subscription")
          .setValue(subscriptionId))

      requestEnv.build().toByteArray
    }

    def decodeAndProcess(bytes: Array[Byte]): Try[Array[Byte]] = {
      try {
        val response = ServiceResponse.parseFrom(bytes)

        if (!response.hasStatus) {
          Failure(new MalformedResponseException("Service response did not include status"))
        } else {
          if (StatusCodes.isSuccess(response.getStatus)) {
            Success(response.getPayload.toByteArray)
          } else {
            Failure(StatusCodes.toException(response.getStatus, response.getErrorMessage))
          }
        }
      } catch {
        case ex: Throwable =>
          Failure(new RequestException("Couldn't parse response envelope", ex))
      }
    }

  }
}
