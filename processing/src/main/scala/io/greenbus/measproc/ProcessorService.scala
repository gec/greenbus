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

import io.greenbus.client.proto.Envelope.{ ServiceResponse, ServiceRequest }
import io.greenbus.client.exception.BadRequestException
import io.greenbus.client.service.proto.MeasurementRequests.PostMeasurementsRequest
import io.greenbus.client.proto.Envelope
import com.google.protobuf.InvalidProtocolBufferException
import com.typesafe.scalalogging.LazyLogging
import scala.collection.JavaConversions._
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.jmx.Metrics

class ProcessorService(endpoint: String, metrics: Metrics) extends LazyLogging {

  private val handleTime = metrics.average("BatchHandleTime")
  private val batchSize = metrics.average("BatchSize")
  private val measHandledCount = metrics.counter("MeasHandledCount")
  private val badRequestCount = metrics.average("BadRequestCount")
  private val serviceErrorCount = metrics.average("ServiceErrorCount")

  def handle(
    bytes: Array[Byte],
    responseHandler: Array[Byte] => Unit,
    process: (Seq[(String, Measurement)], Option[Long]) => Unit,
    pointMap: PointMap) {

    val startTime = System.currentTimeMillis()

    try {
      val requestEnvelope = ServiceRequest.parseFrom(bytes)

      if (!requestEnvelope.hasPayload) {
        throw new BadRequestException("Must include request payload")
      }
      val payload = requestEnvelope.getPayload.toByteArray

      val request = PostMeasurementsRequest.parseFrom(payload)
      if (!request.hasBatch) {
        throw new BadRequestException("Must include measurement batch")
      }

      val batch = request.getBatch
      if (batch.getNamedMeasurementsCount == 0 && batch.getPointMeasurementsCount == 0) {
        logger.debug(s"Empty measurement batch for endpoint $endpoint")
      } else {

        if (batch.getPointMeasurementsList.exists(!_.hasPointUuid)) {
          throw new BadRequestException("Must include point uuids for all id-based measurements")
        }
        if (batch.getNamedMeasurementsList.exists(!_.hasPointName)) {
          throw new BadRequestException("Must include point name for all name-based measurements")
        }

        val wallTime = if (batch.hasWallTime) Some(batch.getWallTime) else None
        val idList = batch.getPointMeasurementsList.map(pm => (pm.getPointUuid.getValue, pm.getValue)).toSeq
        val nameList = batch.getNamedMeasurementsList.flatMap(nm => pointMap.nameToId.get(nm.getPointName).map(uuid => (uuid.getValue, nm.getValue))).toSeq

        val allMeas = idList ++ nameList
        val count = allMeas.size
        batchSize(count)
        measHandledCount(count)

        process(allMeas, wallTime)
      }

      responseHandler(ServiceResponse.newBuilder().setStatus(Envelope.Status.OK).build().toByteArray)

    } catch {
      case ex: BadRequestException =>
        badRequestCount(1)
        logger.warn("Bad client request: " + ex.getMessage)
        val response = buildErrorEnvelope(Envelope.Status.BAD_REQUEST, ex.getMessage)
        responseHandler(response.toByteArray)
      case protoEx: InvalidProtocolBufferException =>
        badRequestCount(1)
        logger.warn("Error parsing client request: " + protoEx.getMessage)
        val response = buildErrorEnvelope(Envelope.Status.BAD_REQUEST, "Couldn't parse service request.")
        responseHandler(response.toByteArray)
      case ex: Throwable =>
        serviceErrorCount(1)
        logger.error("Internal service error: " + ex)
        val response = buildErrorEnvelope(Envelope.Status.INTERNAL_ERROR, "Internal service error.")
        responseHandler(response.toByteArray)
    }

    handleTime((System.currentTimeMillis() - startTime).toInt)

  }

  def buildErrorEnvelope(status: Envelope.Status, message: String): ServiceResponse = {
    ServiceResponse.newBuilder()
      .setStatus(status)
      .setErrorMessage(message)
      .build()
  }
}
