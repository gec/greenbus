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

import io.greenbus.msg.service.ServiceHandler
import io.greenbus.client.exception.BadRequestException
import io.greenbus.client.proto.Envelope
import io.greenbus.client.service.FrontEndService
import io.greenbus.client.service.proto.FrontEndRequests.{ PutFrontEndConnectionStatusRequest, PutFrontEndConnectionStatusResponse }
import io.greenbus.client.service.proto.Model.Endpoint
import io.greenbus.services.framework._
import io.greenbus.services.model.FrontEndModel
import io.greenbus.services.model.UUIDHelpers._
import io.greenbus.sql.DbConnection

import scala.collection.JavaConversions._

object HeartbeatServiceHandler {

  def wrapped(
    endpoint: Endpoint,
    sql: DbConnection,
    frontEndModel: FrontEndModel,
    notifier: ModelNotifier): ServiceHandler = {

    val handler = new HeartbeatServiceHandler(endpoint, sql, frontEndModel, notifier)

    new EnvelopeParsingHandler(
      new DecodingServiceHandler(FrontEndService.Descriptors.PutFrontEndConnectionStatuses,
        new ModelErrorTransformingHandler(handler)))
  }

}

class HeartbeatServiceHandler(
    endpoint: Endpoint,
    sql: DbConnection,
    frontEndModel: FrontEndModel,
    notifier: ModelNotifier) extends TypedServiceHandler[PutFrontEndConnectionStatusRequest, PutFrontEndConnectionStatusResponse] {

  def handle(request: PutFrontEndConnectionStatusRequest, headers: Map[String, String], responseHandler: (Response[PutFrontEndConnectionStatusResponse]) => Unit) {

    val results = sql.transaction {

      if (request.getUpdatesCount == 0) {
        throw new BadRequestException("Must include request content")
      }

      val updates = request.getUpdatesList.toSeq

      val modelUpdates = updates.map { update =>
        if (!update.hasEndpointUuid) {
          throw new BadRequestException("Must include endpoint uuid")
        }
        if (!update.hasStatus) {
          throw new BadRequestException("Must include communication status")
        }

        (protoUUIDToUuid(update.getEndpointUuid), update.getStatus)
      }

      frontEndModel.putFrontEndConnectionStatuses(notifier, modelUpdates, None)

    }

    val response = PutFrontEndConnectionStatusResponse.newBuilder().addAllResults(results).build
    responseHandler(Success(Envelope.Status.OK, response))
  }
}
