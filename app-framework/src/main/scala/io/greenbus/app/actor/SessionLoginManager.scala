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
package io.greenbus.app.actor

import akka.actor._
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.ServiceConnection
import io.greenbus.client.exception.ServiceException
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpServiceOperations
import io.greenbus.util.UserSettings

import scala.concurrent.duration._

object SessionLoginManager {

  sealed trait State
  case object Unestablished extends State
  case class Running(child: ActorRef)

  case object AttemptLogin
  case class LoginSuccess(session: Session)
  case class LoginFailure(ex: Throwable)

  def props(processName: String,
    userConfigPath: String,
    connection: ServiceConnection,
    loginRetryMs: Long,
    factory: (Session, AmqpServiceOperations) => Props): Props = {
    Props(classOf[SessionLoginManager], processName, userConfigPath, connection, loginRetryMs, factory)
  }
}

class SessionLoginManager(
  processName: String,
  userConfigPath: String,
  connection: ServiceConnection,
  loginRetryMs: Long,
  factory: (Session, AmqpServiceOperations) => Props)
    extends Actor with MessageScheduling with LazyLogging {
  import SessionLoginManager._
  import context.dispatcher

  private var childOpt = Option.empty[ActorRef]

  self ! AttemptLogin

  def receive = {
    case AttemptLogin => {
      attemptLogin(connection)
    }
    case LoginSuccess(session) => {
      logger.info(s"$processName logged into services")

      val child = context.actorOf(factory(session, connection.serviceOperations))
      childOpt = Some(child)
    }
    case LoginFailure(ex) => {
      ex match {
        case ex: ServiceException =>
          logger.warn(s"$processName login failed: " + ex.getMessage)
          scheduleMsg(loginRetryMs, AttemptLogin)
      }
    }
  }

  private def attemptLogin(conn: ServiceConnection) {
    val userConfig = userSettings()
    val future = conn.login(userConfig.user, userConfig.password)
    future.onSuccess {
      case session: Session => self ! LoginSuccess(session)
    }
    future.onFailure {
      case ex: Throwable => self ! LoginFailure(ex)
    }
  }

  private def userSettings(): UserSettings = {
    UserSettings.load(userConfigPath)
  }

  override def supervisorStrategy: SupervisorStrategy = {
    import SupervisorStrategy._
    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 3.seconds) {
      case ex: Throwable =>
        Escalate
    }
  }
}
