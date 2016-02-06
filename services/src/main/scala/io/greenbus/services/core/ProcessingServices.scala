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

import io.greenbus.client.exception.{ BadRequestException, ForbiddenException }
import io.greenbus.client.proto.Envelope
import io.greenbus.client.proto.Envelope.SubscriptionEventType
import io.greenbus.client.service.ProcessingService.Descriptors
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.client.service.proto.Processing.{ MeasOverride, OverrideNotification }
import io.greenbus.client.service.proto.ProcessingRequests._
import io.greenbus.services.framework.{ ServiceContext, Success, _ }
import io.greenbus.services.model.EventAlarmModel.SysEventTemplate
import io.greenbus.services.model.{ FrontEndModel, EventAlarmModel, EventSeeding, ProcessingModel }
import io.greenbus.services.model.UUIDHelpers._

import scala.collection.JavaConversions._

object ProcessingServices {

  val overrideResource = "meas_override"
}

class ProcessingServices(services: ServiceRegistry, processingModel: ProcessingModel, eventModel: EventAlarmModel, frontEndModel: FrontEndModel, overSubMgr: SubscriptionChannelBinder) {
  import io.greenbus.services.core.ProcessingServices._

  services.fullService(Descriptors.GetOverrides, getOverrides)
  services.fullService(Descriptors.SubscribeToOverrides, subscribeToOverrides)
  services.fullService(Descriptors.PutOverrides, putOverrides)
  services.fullService(Descriptors.DeleteOverrides, deleteOverrides)

  def getOverrides(request: GetOverridesRequest, headers: Map[String, String], context: ServiceContext): Response[GetOverridesResponse] = {

    val filter = context.auth.authorize(overrideResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val keySet = request.getRequest

    if (keySet.getUuidsCount == 0 && keySet.getNamesCount == 0) {
      throw new BadRequestException("Must include at least one id or name")
    }

    val uuids = keySet.getUuidsList.toSeq.map(protoUUIDToUuid)
    val names = keySet.getNamesList.toSeq

    val results = processingModel.overrideKeyQuery(uuids, names, filter)

    val response = GetOverridesResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def subscribeToOverrides(request: SubscribeOverridesRequest, headers: Map[String, String], context: ServiceContext): Response[SubscribeOverridesResponse] = {

    val filter = context.auth.authorize(overrideResource, "read")

    if (!request.hasQuery) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getQuery

    val queue = headers.get("__subscription").getOrElse {
      throw new BadRequestException("Must include subscription queue in headers")
    }

    val hasKeys = query.getPointUuidsCount != 0 || query.getPointNamesCount != 0
    val hasParams = hasKeys || query.getEndpointUuidsCount != 0

    val immediateResults = if (!hasParams) {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions on entities when subscribing to all entities")
      }

      overSubMgr.bindAll(queue)
      Seq()

    } else if (query.getEndpointUuidsCount != 0 && !hasKeys) {

      if (query.getPointUuidsCount != 0 || query.getPointNamesCount != 0) {
        throw new BadRequestException("Must not include key parameters when subscribing by endpoints")
      }
      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions on entities when subscribing by endpoints")
      }

      val endpoints = query.getEndpointUuidsList.toSeq

      overSubMgr.bindEach(queue, Seq(Nil, endpoints.map(_.getValue)))

      Nil

    } else if (hasKeys && query.getEndpointUuidsCount == 0) {

      val names = query.getPointNamesList.toSeq
      val uuids = query.getPointUuidsList.toSeq.map(protoUUIDToUuid)

      val results = processingModel.overrideKeyQuery(uuids, names, filter)

      val filteredUuids = results.map(_.getPointUuid.getValue)

      if (filteredUuids.nonEmpty) {
        overSubMgr.bindEach(queue, Seq(filteredUuids, Nil))
      }
      results

    } else {

      throw new BadRequestException("Must not include key parameters when subscribing by endpoints")
    }

    val response = SubscribeOverridesResponse.newBuilder().addAllResults(immediateResults).build
    Success(Envelope.Status.OK, response)
  }

  def putOverrides(request: PutOverridesRequest, headers: Map[String, String], context: ServiceContext): Response[PutOverridesResponse] = {

    val filter = context.auth.authorize(overrideResource, "update")

    if (request.getOverridesCount == 0) {
      throw new BadRequestException("Must include at least one measurement override")
    }

    request.getOverridesList.toList.foreach { measOver =>
      if (!measOver.hasPointUuid) {
        throw new BadRequestException("Measurement overrides must include point uuid")
      }
    }

    val measOverrides = request.getOverridesList.toList.map(o => (protoUUIDToUuid(o.getPointUuid), o))

    val results = processingModel.putOverrides(context.notifier, measOverrides, filter)

    publishEventsForOverrides(context, results, EventSeeding.System.setNotInService.eventType, EventSeeding.System.setOverride.eventType)

    val response = PutOverridesResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def deleteOverrides(request: DeleteOverridesRequest, headers: Map[String, String], context: ServiceContext): Response[DeleteOverridesResponse] = {

    val filter = context.auth.authorize(overrideResource, "delete")

    if (request.getPointUuidsCount == 0) {
      throw new BadRequestException("Must include at least one item to delete")
    }

    val uuids = request.getPointUuidsList.map(protoUUIDToUuid)

    val results = processingModel.deleteOverrides(context.notifier, uuids, filter)

    publishEventsForOverrides(context, results, EventSeeding.System.removeNotInService.eventType, EventSeeding.System.removeOverride.eventType)

    val response = DeleteOverridesResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  private def publishEventsForOverrides(context: ServiceContext, results: Seq[MeasOverride], nisType: String, replaceType: String): Unit = {
    val points = frontEndModel.pointKeyQuery(results.map(_.getPointUuid).map(protoUUIDToUuid), Seq())
    val uuidToNameMap = points.map(p => (p.getUuid, p.getName)).toMap

    val eventTemplates = results.map { over =>
      val pointName = uuidToNameMap.getOrElse(over.getPointUuid, "unknown")
      val attrs = EventAlarmModel.mapToAttributesList(Seq(("point", pointName)))
      val eventType = if (over.hasMeasurement) replaceType else nisType
      SysEventTemplate(context.auth.agentName, eventType, Some("services"), None, None, None, attrs)
    }

    eventModel.postEvents(context.notifier, eventTemplates)
  }
}

case class OverrideWithEndpoint(payload: MeasOverride, endpoint: Option[ModelUUID])

object OverrideSubscriptionDescriptor extends SubscriptionDescriptor[OverrideWithEndpoint] {

  def keyParts(obj: OverrideWithEndpoint): Seq[String] = {
    Seq(obj.payload.getPointUuid.getValue, obj.endpoint.map(_.getValue).getOrElse(""))
  }

  def notification(eventType: SubscriptionEventType, obj: OverrideWithEndpoint): Array[Byte] = {
    OverrideNotification.newBuilder
      .setValue(obj.payload)
      .setEventType(eventType)
      .build()
      .toByteArray
  }
}

