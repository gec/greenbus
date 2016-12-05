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
package io.greenbus.services.core

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.amqp.AmqpServiceOperations
import io.greenbus.client.exception.{ BadRequestException, ForbiddenException }
import io.greenbus.client.proto.Envelope
import io.greenbus.client.proto.Envelope.SubscriptionEventType
import io.greenbus.client.service.FrontEndService
import io.greenbus.client.service.proto.FrontEnd._
import io.greenbus.client.service.proto.FrontEndRequests._
import io.greenbus.client.service.proto.Model._
import io.greenbus.services.framework.{ ServiceContext, Success, _ }
import io.greenbus.services.model.FrontEndModel
import io.greenbus.services.model.UUIDHelpers._

import scala.collection.JavaConversions._

object FrontEndServices {

  val frontEndRegistrationResource = "frontEndRegistration"
  val frontEndConnectionStatusResource = "frontEndConnectionStatus"

  val defaultPageSize = 200
}

class FrontEndServices(
    services: ServiceRegistry,
    ops: AmqpServiceOperations,
    frontEndModel: FrontEndModel,
    frontEndConnectionStatusBinding: SubscriptionChannelBinder) extends LazyLogging {
  import io.greenbus.services.core.FrontEndServices._

  services.fullService(FrontEndService.Descriptors.GetFrontEndConnectionStatuses, getFrontEndConnectionStatuses)
  services.fullService(FrontEndService.Descriptors.SubscribeToFrontEndConnectionStatuses, subscribeFrontEndConnectionStatuses)

  def getFrontEndConnectionStatuses(request: GetFrontEndConnectionStatusRequest, headers: Map[String, String], context: ServiceContext): Response[GetFrontEndConnectionStatusResponse] = {

    val filter = context.auth.authorize(frontEndConnectionStatusResource, "read")

    if (!request.hasEndpoints) {
      throw new BadRequestException("Must include request content")
    }

    val keySet = request.getEndpoints

    if (keySet.getUuidsCount == 0 && keySet.getNamesCount == 0) {
      throw new BadRequestException("Must include at least one id or name")
    }

    val uuids = keySet.getUuidsList.toSeq.map(protoUUIDToUuid)
    val names = keySet.getNamesList.toSeq

    val results = frontEndModel.getFrontEndConnectionStatuses(uuids, names, filter)

    val response = GetFrontEndConnectionStatusResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def subscribeFrontEndConnectionStatuses(request: SubscribeFrontEndConnectionStatusRequest, headers: Map[String, String], context: ServiceContext): Response[SubscribeFrontEndConnectionStatusResponse] = {

    val filter = context.auth.authorize(frontEndConnectionStatusResource, "read")

    if (!request.hasEndpoints) {
      throw new BadRequestException("Must include request content")
    }

    val queue = headers.get("__subscription").getOrElse {
      throw new BadRequestException("Must include subscription queue in headers")
    }

    val keySet = request.getEndpoints

    val results = if (keySet.getUuidsCount != 0 || keySet.getNamesCount != 0) {

      val uuids = keySet.getUuidsList.toSeq.map(protoUUIDToUuid)
      val names = keySet.getNamesList.toSeq

      val endpoints = frontEndModel.endpointKeyQuery(uuids, names, filter)

      val getResults = frontEndModel.getFrontEndConnectionStatuses(uuids, names, filter)

      val filteredUuids = endpoints.map(_.getUuid)

      frontEndConnectionStatusBinding.bindEach(queue, Seq(filteredUuids.map(_.getValue)))

      getResults

    } else {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions on entities when subscribing to all")
      }

      frontEndConnectionStatusBinding.bindAll(queue)

      Nil
    }

    val response = SubscribeFrontEndConnectionStatusResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }
}

object EndpointSubscriptionDescriptor extends SubscriptionDescriptor[Endpoint] {
  def keyParts(payload: Endpoint): Seq[String] = {
    Seq(payload.getUuid.getValue, payload.getName, payload.getProtocol)
  }

  def notification(eventType: SubscriptionEventType, payload: Endpoint): Array[Byte] = {
    EndpointNotification.newBuilder
      .setValue(payload)
      .setEventType(eventType)
      .build()
      .toByteArray
  }
}
object PointSubscriptionDescriptor extends SubscriptionDescriptor[Point] {
  def keyParts(payload: Point): Seq[String] = {
    val endpointUuid = if (payload.hasEndpointUuid) payload.getEndpointUuid.getValue else ""
    Seq(endpointUuid, payload.getUuid.getValue, payload.getName)
  }

  def notification(eventType: SubscriptionEventType, payload: Point): Array[Byte] = {
    PointNotification.newBuilder
      .setValue(payload)
      .setEventType(eventType)
      .build()
      .toByteArray
  }
}
object CommandSubscriptionDescriptor extends SubscriptionDescriptor[Command] {
  def keyParts(payload: Command): Seq[String] = {
    val endpointUuid = if (payload.hasEndpointUuid) payload.getEndpointUuid.getValue else ""
    Seq(endpointUuid, payload.getUuid.getValue, payload.getName)
  }

  def notification(eventType: SubscriptionEventType, payload: Command): Array[Byte] = {
    CommandNotification.newBuilder
      .setValue(payload)
      .setEventType(eventType)
      .build()
      .toByteArray
  }
}

object FrontEndConnectionStatusSubscriptionDescriptor extends SubscriptionDescriptor[FrontEndConnectionStatus] {
  def keyParts(payload: FrontEndConnectionStatus): Seq[String] = {
    Seq(payload.getEndpointUuid.getValue)
  }

  def notification(eventType: SubscriptionEventType, payload: FrontEndConnectionStatus): Array[Byte] = {
    FrontEndConnectionStatusNotification.newBuilder
      .setValue(payload)
      .setEventType(eventType)
      .build()
      .toByteArray
  }
}
