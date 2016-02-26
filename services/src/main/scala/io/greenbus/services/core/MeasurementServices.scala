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

import io.greenbus.services.framework._
import io.greenbus.client.service.proto.MeasurementRequests._
import io.greenbus.client.service.MeasurementService.Descriptors
import io.greenbus.client.exception.{ ForbiddenException, BadRequestException }
import io.greenbus.services.model.UUIDHelpers._
import scala.collection.JavaConversions._
import io.greenbus.client.proto.Envelope
import io.greenbus.mstore.{ MeasurementHistorySource, MeasurementValueSource }
import io.greenbus.client.service.proto.Measurements._
import io.greenbus.services.framework.ServiceContext
import io.greenbus.services.framework.Success
import java.util.UUID
import io.greenbus.services.model.FrontEndModel
import io.greenbus.services.model.MeasurementSampling

object MeasurementServices {

  val measurementResource = "measurement"

  val defaultHistoryLimit = 200
}

class MeasurementServices(services: ServiceRegistry, store: MeasurementValueSource, history: MeasurementHistorySource, measChannel: SubscriptionChannelBinder, measBatchChannel: SubscriptionChannelBinder, frontEndModel: FrontEndModel) {
  import MeasurementServices._

  services.fullService(Descriptors.GetCurrentValues, getCurrentValue)
  services.fullService(Descriptors.GetCurrentValuesAndSubscribe, subscribeWithCurrentValue)
  services.fullService(Descriptors.GetHistory, getHistory)
  services.fullService(Descriptors.GetHistorySamples, getHistorySampled)
  services.fullService(Descriptors.SubscribeToBatches, subscribeToBatches)

  def getCurrentValue(request: GetCurrentValuesRequest, headers: Map[String, String], context: ServiceContext): Response[GetCurrentValuesResponse] = {

    val optFilter = context.auth.authorize(measurementResource, "read")

    if (request.getPointUuidsCount == 0) {
      throw new BadRequestException("Must include at least one point uuid")
    }

    val uuids = request.getPointUuidsList.toSeq.map(protoUUIDToUuid)

    val filteredUuids = optFilter match {
      case None => uuids
      case Some(filter) => filter.filter(uuids)
    }

    val results = store.get(filteredUuids)

    val protoResults = results.map {
      case (id, meas) =>
        PointMeasurementValue.newBuilder
          .setPointUuid(uuidToProtoUUID(id))
          .setValue(meas)
          .build
    }

    val response = GetCurrentValuesResponse.newBuilder().addAllResults(protoResults).build
    Success(Envelope.Status.OK, response)
  }

  def subscribeWithCurrentValue(request: GetCurrentValuesAndSubscribeRequest, headers: Map[String, String], context: ServiceContext): Response[GetCurrentValuesAndSubscribeResponse] = {

    val optFilter = context.auth.authorize(measurementResource, "read")

    val queue = headers.get("__subscription").getOrElse {
      throw new BadRequestException("Must include subscription queue in headers")
    }

    val protoResults = if (request.getPointUuidsCount == 0) {

      if (optFilter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions when subscribing to all")
      }

      measChannel.bindAll(queue)

      Seq()

    } else {

      val uuids = request.getPointUuidsList.toSeq.map(protoUUIDToUuid)

      val filteredUuids = optFilter match {
        case None => uuids
        case Some(filter) => filter.filter(uuids)
      }

      val results = store.get(filteredUuids)

      val protoResults = results.map {
        case (id, meas) =>
          PointMeasurementValue.newBuilder
            .setPointUuid(uuidToProtoUUID(id))
            .setValue(meas)
            .build
      }

      if (filteredUuids.nonEmpty) {
        measChannel.bindEach(queue, Seq(filteredUuids.map(_.toString), Nil))
      }

      protoResults
    }

    val response = GetCurrentValuesAndSubscribeResponse.newBuilder().addAllResults(protoResults).build
    Success(Envelope.Status.OK, response)
  }

  def getHistory(request: GetMeasurementHistoryRequest, headers: Map[String, String], context: ServiceContext): Response[GetMeasurementHistoryResponse] = {

    val optFilter = context.auth.authorize(measurementResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request query")
    }

    val query = request.getRequest

    if (!query.hasPointUuid) {
      throw new BadRequestException("Must include point uuid")
    }

    val pointId: UUID = query.getPointUuid

    if (!frontEndModel.pointExists(pointId, optFilter)) {
      throw new BadRequestException("No point exists for uuid")
    }

    val windowStart = if (query.hasTimeFrom) Some(query.getTimeFrom) else None
    val windowEnd = if (query.hasTimeTo) Some(query.getTimeTo) else None
    val limit = if (query.hasLimit) query.getLimit else defaultHistoryLimit
    val latest = if (query.hasLatest) query.getLatest else false

    val results = history.getHistory(pointId, windowStart, windowEnd, limit, latest)

    val result = PointMeasurementValues.newBuilder()
      .setPointUuid(query.getPointUuid)
      .addAllValue(results)
      .build()

    val response = GetMeasurementHistoryResponse.newBuilder().setResult(result).build
    Success(Envelope.Status.OK, response)
  }

  def getHistorySampled(request: GetMeasurementHistorySampledRequest, headers: Map[String, String], context: ServiceContext): Response[GetMeasurementHistorySampledResponse] = {

    val optFilter = context.auth.authorize(measurementResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request query")
    }

    val query = request.getRequest

    if (!query.hasPointUuid) {
      throw new BadRequestException("Must include point uuid")
    }

    val pointId: UUID = query.getPointUuid

    if (!frontEndModel.pointExists(pointId, optFilter)) {
      throw new BadRequestException("No point exists for uuid")
    }

    if (!query.hasTimeFrom) {
      throw new BadRequestException("Must set beginning of time window")
    }

    if (!query.hasTimeWindowLength) {
      throw new BadRequestException("Must set length of time windows")
    }

    val windowStart = query.getTimeFrom

    val windowEnd = if (query.hasTimeTo) query.getTimeTo else System.currentTimeMillis()

    val timeWindowLength = query.getTimeWindowLength

    val samples = MeasurementSampling.sampleRecur2(history, pointId, windowStart, windowEnd, timeWindowLength, MeasurementSampling.historyWindowMax)

    val result = PointMeasurementSampleValues.newBuilder()
      .setPointUuid(query.getPointUuid)
      .setValue(samples)
      .build()

    val response = GetMeasurementHistorySampledResponse.newBuilder().setResult(result).build
    Success(Envelope.Status.OK, response)
  }

  def subscribeToBatches(request: SubscribeToBatchesRequest, headers: Map[String, String], context: ServiceContext): Response[SubscribeToBatchesResponse] = {

    val optFilter = context.auth.authorize(measurementResource, "read")

    if (optFilter.nonEmpty) {
      throw new ForbiddenException(s"Must have blanked read permissions for $measurementResource to subscribe to batches")
    }

    val queue = headers.get("__subscription").getOrElse {
      throw new BadRequestException("Must include subscription queue in headers")
    }

    if (!request.hasQuery) {
      throw new BadRequestException("Must include request query")
    }

    val endpointUuids = request.getQuery.getEndpointUuidsList.toSeq

    if (endpointUuids.nonEmpty) {
      measBatchChannel.bindEach(queue, Seq(endpointUuids.map(_.getValue)))
    } else {
      measBatchChannel.bindAll(queue)
    }

    val response = SubscribeToBatchesResponse.newBuilder().build
    Success(Envelope.Status.OK, response)
  }

}
