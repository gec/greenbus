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

import java.util.UUID

import io.greenbus.msg.SubscriptionBinding
import io.greenbus.msg.amqp.AmqpServiceOperations
import io.greenbus.msg.service.ServiceHandler
import io.greenbus.client.exception.{ BadRequestException, InternalServiceException }
import io.greenbus.client.proto.Envelope
import io.greenbus.client.proto.Envelope.ServiceResponse
import io.greenbus.client.service.{ FrontEndService, MeasurementService }
import io.greenbus.client.service.proto.CommandRequests.PostCommandRequestRequest
import io.greenbus.client.service.proto.FrontEndRequests.{ PutFrontEndRegistrationRequest, PutFrontEndRegistrationResponse, FrontEndRegistrationTemplate }
import io.greenbus.client.service.proto.Model.Endpoint
import io.greenbus.services.authz.{ EntityFilter, AuthLookup }
import io.greenbus.services.core.FrontEndServices
import io.greenbus.services.framework._
import io.greenbus.services.model.FrontEndModel
import io.greenbus.services.model.FrontEndModel.FrontEndConnectionTemplate
import io.greenbus.services.model.UUIDHelpers._
import io.greenbus.sql.DbConnection

case class RegistrationResult(address: String, measServiceBinding: SubscriptionBinding, heartbeatServiceBinding: SubscriptionBinding, nodeId: Option[String])

class RegistrationResultHolder {
  private var resultOpt = Option.empty[RegistrationResult]
  def set(result: RegistrationResult): Unit = { resultOpt = Some(result) }
  def getOption: Option[RegistrationResult] = resultOpt
}

object RegistrationServiceHandler {

  def wrapped(endpoint: Endpoint,
    sql: DbConnection,
    auth: AuthLookup,
    ops: AmqpServiceOperations,
    frontEndModel: FrontEndModel,
    measServiceHandler: ServiceHandler,
    heartbeatServiceHandler: ServiceHandler,
    resultHolder: RegistrationResultHolder,
    down: Boolean,
    currentNodeId: Option[String]): ServiceHandler = {

    val handler = new RegistrationServiceHandler(
      endpoint,
      sql,
      auth,
      ops,
      frontEndModel,
      measServiceHandler,
      heartbeatServiceHandler,
      resultHolder,
      down,
      currentNodeId)

    new EnvelopeParsingHandler(
      new DecodingServiceHandler(FrontEndService.Descriptors.PutFrontEndRegistration,
        new ModelErrorTransformingHandler(handler)))
  }

  val unavailableResponse = {
    ServiceResponse.newBuilder()
      .setStatus(Envelope.Status.LOCKED)
      .setErrorMessage("FEP already registered")
      .build()
  }

  val unavailableResponseBytes = {
    unavailableResponse.toByteArray
  }

}

class RegistrationServiceHandler(
  endpoint: Endpoint,
  sql: DbConnection,
  auth: AuthLookup,
  ops: AmqpServiceOperations,
  frontEndModel: FrontEndModel,
  measServiceHandler: ServiceHandler,
  heartbeatServiceHandler: ServiceHandler,
  resultHolder: RegistrationResultHolder,
  down: Boolean,
  currentNodeId: Option[String])
    extends TypedServiceHandler[PutFrontEndRegistrationRequest, PutFrontEndRegistrationResponse] {

  def handle(request: PutFrontEndRegistrationRequest, headers: Map[String, String], responseHandler: (Response[PutFrontEndRegistrationResponse]) => Unit): Unit = {

    sql.transaction {
      val authContext = auth.validateAuth(headers)
      val filter = authContext.authorize(FrontEndServices.frontEndRegistrationResource, "update")

      if (!request.hasRequest) {
        throw new BadRequestException("Must include at least one front end connection request")
      }

      val template = request.getRequest

      if (!template.hasEndpointUuid) {
        throw new BadRequestException("Must include endpoint uuid")
      }

      if (template.getEndpointUuid != endpoint.getUuid) {
        throw new BadRequestException("Incorrect endpoint uuid for service address")
      }

      val nodeIdOpt = if (template.hasFepNodeId) Some(template.getFepNodeId) else None

      val holdingLockOpt = if (template.hasHoldingLock) Some(template.getHoldingLock) else None

      if (!down) {
        if (nodeIdOpt == currentNodeId || holdingLockOpt == Some(true)) {
          makeRegistration(template, filter, nodeIdOpt, responseHandler)
        } else {
          responseHandler(Failure(Envelope.Status.LOCKED, "FEP already registered"))
        }
      } else {
        makeRegistration(template, filter, nodeIdOpt, responseHandler)
      }
    }
  }

  private def makeRegistration(template: FrontEndRegistrationTemplate, filter: Option[EntityFilter], requestNodeId: Option[String], responseHandler: (Response[PutFrontEndRegistrationResponse]) => Unit): Unit = {

    val cmdAddr = if (template.hasCommandAddress) Some(template.getCommandAddress) else None

    val streamAddress = UUID.randomUUID().toString

    val modelTemplate = FrontEndConnectionTemplate(endpoint.getUuid, streamAddress, cmdAddr)

    val result = frontEndModel.putFrontEndConnections(NullModelNotifier, Seq(modelTemplate), filter).headOption.getOrElse {
      throw new InternalServiceException("Frontend registration could not be put in database")
    }

    cmdAddr.foreach { addr =>
      ops.bindQueue(addr, classOf[PostCommandRequestRequest].getSimpleName, addr)
    }

    val measStreamBinding = ops.bindRoutedService(measServiceHandler)
    val heartbeatBinding = ops.bindRoutedService(heartbeatServiceHandler)
    try {
      ops.bindQueue(measStreamBinding.getId(), MeasurementService.Descriptors.PostMeasurements.requestId, streamAddress)
      ops.bindQueue(heartbeatBinding.getId(), FrontEndService.Descriptors.PutFrontEndConnectionStatuses.requestId, streamAddress)
    } catch {
      case ex: Throwable =>
        measStreamBinding.cancel()
        heartbeatBinding.cancel()
        throw ex
    }

    resultHolder.set(RegistrationResult(streamAddress, measStreamBinding, heartbeatBinding, requestNodeId))

    val response = PutFrontEndRegistrationResponse.newBuilder().setResults(result).build
    responseHandler(Success(Envelope.Status.OK, response))
  }
}
