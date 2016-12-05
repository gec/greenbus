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

import java.util.UUID
import java.util.concurrent.TimeoutException

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.services.{ AsyncAuthenticationModule, SqlAuthenticationModule, AuthenticationModule }
import io.greenbus.services.framework._
import io.greenbus.client.service.proto.LoginRequests._
import io.greenbus.client.exception.{ UnauthorizedException, BadRequestException }
import io.greenbus.client.proto.Envelope
import io.greenbus.services.model.EventAlarmModel.SysEventTemplate
import io.greenbus.sql.DbConnection
import io.greenbus.services.model.{ UUIDHelpers, EventSeeding, EventAlarmModel, AuthModel }
import io.greenbus.client.version.Version

import scala.concurrent.ExecutionContext.Implicits.global

object LoginServices {
  val defaultExpirationLength = 18144000000L // one month

}

class LoginServices(services: ServiceRegistry, sql: DbConnection, authenticator: AuthenticationModule, authModel: AuthModel, eventModel: EventAlarmModel, modelNotifier: ModelNotifier) extends LazyLogging {
  import LoginServices._
  import io.greenbus.client.service.LoginService.Descriptors

  //services.simpleSync(Descriptors.Login, login)
  services.simpleAsync(Descriptors.Login, loginAsync)
  services.simpleSync(Descriptors.Logout, logout)
  services.simpleSync(Descriptors.Validate, validateAuthToken)

  def loginAsync(request: PostLoginRequest, headers: Map[String, String], responseHandler: Response[PostLoginResponse] => Unit): Unit = {

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val login = request.getRequest

    if (!login.hasName) {
      throw new BadRequestException("Must include user name.")
    }
    if (!login.hasPassword) {
      throw new BadRequestException("Must include password.")
    }

    val currentTime = System.currentTimeMillis()
    val expiration = if (login.hasExpirationTime) {
      if (login.getExpirationTime < currentTime) {
        throw new BadRequestException("Expiration time cannot be in the past")
      }
      login.getExpirationTime
    } else {
      currentTime + defaultExpirationLength
    }

    val clientVersion = if (login.hasClientVersion) login.getClientVersion else "unknown"
    val location = if (login.hasLoginLocation) login.getLoginLocation else "unknown"

    val error = "Invalid username or password"

    def enterAuthorization(agentId: UUID): Response[PostLoginResponse] = {
      val token = authModel.createTokenAndStore(agentId, expiration, clientVersion, location)

      val attrs = EventAlarmModel.mapToAttributesList(Seq(("user", login.getName)))
      val event = SysEventTemplate(login.getName, EventSeeding.System.userLogin.eventType, Some("auth"), None, Some(UUIDHelpers.uuidToProtoUUID(agentId)), None, attrs)
      eventModel.postEvents(modelNotifier, Seq(event))

      val resp = LoginResponse.newBuilder
        .setToken(token)
        .setExpirationTime(expiration)
        .setServerVersion(Version.clientVersion)
        .build()

      Success(Envelope.Status.OK, PostLoginResponse.newBuilder().setResponse(resp).build())
    }

    def postFailureEvent(): Unit = {
      val attrs = EventAlarmModel.mapToAttributesList(Seq(("reason", error)))
      val event = SysEventTemplate(login.getName, EventSeeding.System.userLoginFailure.eventType, Some("auth"), None, None, None, attrs)
      eventModel.postEvents(modelNotifier, Seq(event))
    }

    authenticator match {

      case sqlModule: SqlAuthenticationModule => {

        val response = sql.transaction {
          val (authenticated, uuidOpt) = sqlModule.authenticate(login.getName, login.getPassword)
          (authenticated, uuidOpt) match {
            case (true, Some(uuid)) => enterAuthorization(uuid)
            case _ =>
              postFailureEvent()
              Failure(Envelope.Status.UNAUTHORIZED, error)
          }
        }
        responseHandler(response)
      }

      case asyncModule: AsyncAuthenticationModule => {

        val authFut = asyncModule.authenticate(login.getName, login.getPassword)

        authFut.onFailure {
          case ex: TimeoutException =>
            logger.warn("Response timeout from authentication service")
            sql.transaction {
              val attrs = EventAlarmModel.mapToAttributesList(Seq(("reason", "Authentication service timeout")))
              val event = SysEventTemplate(login.getName, EventSeeding.System.userLoginFailure.eventType, Some("auth"), None, None, None, attrs)
              eventModel.postEvents(modelNotifier, Seq(event))
            }
            responseHandler(Failure(Envelope.Status.RESPONSE_TIMEOUT, "Response timeout from front end connection"))
          case ex: Throwable =>
            logger.warn("Authentication service returned unexpected error: " + ex)
            sql.transaction {
              postFailureEvent()
            }
            responseHandler(Failure(Envelope.Status.UNAUTHORIZED, error))
        }

        authFut.onSuccess {
          case true =>
            val response = sql.transaction {
              authModel.agentIdForName(login.getName) match {
                case None =>
                  postFailureEvent()
                  Failure(Envelope.Status.UNAUTHORIZED, error)
                case Some(agentId) =>
                  enterAuthorization(agentId)
              }
            }
            responseHandler(response)

          case false =>
            sql.transaction {
              postFailureEvent()
            }
            responseHandler(Failure(Envelope.Status.UNAUTHORIZED, error))
        }
      }
    }

  }

  def login(request: PostLoginRequest, headers: Map[String, String]): Response[PostLoginResponse] = {

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val login = request.getRequest

    if (!login.hasName) {
      throw new BadRequestException("Must include user name.")
    }
    if (!login.hasPassword) {
      throw new BadRequestException("Must include password.")
    }

    val currentTime = System.currentTimeMillis()
    val expiration = if (login.hasExpirationTime) {
      if (login.getExpirationTime < currentTime) {
        throw new BadRequestException("Expiration time cannot be in the past")
      }
      login.getExpirationTime
    } else {
      currentTime + defaultExpirationLength
    }

    val clientVersion = if (login.hasClientVersion) login.getClientVersion else "unknown"
    val location = if (login.hasLoginLocation) login.getLoginLocation else "unknown"

    sql.transaction {
      val result = authModel.simpleLogin(login.getName, login.getPassword, expiration, clientVersion, location)
      result match {
        case Left(error) =>

          val attrs = EventAlarmModel.mapToAttributesList(Seq(("reason", error)))
          val event = SysEventTemplate(login.getName, EventSeeding.System.userLoginFailure.eventType, Some("auth"), None, None, None, attrs)
          eventModel.postEvents(modelNotifier, Seq(event))

          throw new UnauthorizedException(error)
        case Right((token, uuid)) => {

          val attrs = EventAlarmModel.mapToAttributesList(Seq(("user", login.getName)))
          val event = SysEventTemplate(login.getName, EventSeeding.System.userLogin.eventType, Some("auth"), None, Some(uuid), None, attrs)
          eventModel.postEvents(modelNotifier, Seq(event))

          val resp = LoginResponse.newBuilder
            .setToken(token)
            .setExpirationTime(expiration)
            .setServerVersion(Version.clientVersion)
            .build()

          Success(Envelope.Status.OK, PostLoginResponse.newBuilder().setResponse(resp).build())
        }
      }
    }
  }

  def logout(request: PostLogoutRequest, headers: Map[String, String]): Response[PostLogoutResponse] = {
    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val tokenRequest = request.getRequest
    if (!tokenRequest.hasToken) {
      throw new BadRequestException("Must include auth token to be logged out")
    }

    sql.transaction {
      authModel.simpleLogout(tokenRequest.getToken)
      val event = SysEventTemplate("-", EventSeeding.System.userLogout.eventType, Some("auth"), None, None, None, Seq())
      eventModel.postEvents(modelNotifier, Seq(event))
    }

    Success(Envelope.Status.OK, PostLogoutResponse.newBuilder().build())
  }

  def validateAuthToken(request: ValidateAuthTokenRequest, headers: Map[String, String]): Response[ValidateAuthTokenResponse] = {
    if (!request.hasToken) {
      throw new BadRequestException("Must include request content")
    }

    val result = sql.transaction {
      authModel.authValidate(request.getToken)
    }

    result match {
      case false => Failure(Envelope.Status.UNAUTHORIZED, "Invalid auth token")
      case true => Success(Envelope.Status.OK, ValidateAuthTokenResponse.newBuilder().build())
    }
  }
}
