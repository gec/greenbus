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

import io.greenbus.client.ServiceConnection
import io.greenbus.msg.{ SessionUnusableException, Session }
import akka.actor._
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.amqp.{ AmqpServiceOperations, AmqpSettings }
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.client.exception.{ ServiceException, UnauthorizedException }
import scala.concurrent.duration._
import akka.actor.OneForOneStrategy
import io.greenbus.util.UserSettings

case class RestartConnection(reason: String)

object ConnectedApplicationManager {

  sealed trait State
  case object Down extends State
  case object Connected extends State
  case object Running extends State

  sealed trait Data
  case object NoData extends Data
  case class ConnEstablished(conn: ServiceConnection) extends Data
  case class SessionEstablished(child: ActorRef, session: Session, conn: ServiceConnection) extends Data

  case object AttemptConnect
  case object AttemptLogin
  case class LoginSuccess(session: Session)
  case class LoginFailure(ex: Throwable)
  case class LostBrokerConnection(expected: Boolean)
  //case class ConnectionRestart(reason: String)

  def props(processName: String, amqpConfigPath: String, userConfigPath: String, factory: (Session, AmqpServiceOperations) => Props): Props =
    Props(classOf[ConnectedApplicationManager], processName, amqpConfigPath, userConfigPath, factory)
}

import ConnectedApplicationManager._

class ConnectedApplicationManager(processName: String, amqpConfigPath: String, userConfigPath: String, factory: (Session, AmqpServiceOperations) => Props) extends Actor with FSM[State, Data] with LazyLogging {

  import context.dispatcher

  private def connectQpid(): ServiceConnection = {
    val config = AmqpSettings.load(amqpConfigPath)
    val connection = ServiceConnection.connect(config, QpidBroker, 5000)
    logger.info(s"Connected to broker: $config")
    connection
  }

  private def userSettings(): UserSettings = {
    UserSettings.load(userConfigPath)
  }

  override def supervisorStrategy: SupervisorStrategy = {
    import SupervisorStrategy._
    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 3.seconds) {
      case _: SessionUnusableException =>
        self ! RestartConnection("session error")
        Stop
      case _: UnauthorizedException =>
        self ! RestartConnection("auth failure")
        Stop
      case ex: Throwable =>
        self ! RestartConnection("unknown error: " + ex)
        Stop
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

  startWith(Down, NoData)

  when(Down) {

    case Event(AttemptConnect, NoData) => {
      try {
        val conn = connectQpid()

        conn.addConnectionListener { expected =>
          self ! LostBrokerConnection(expected)
        }

        attemptLogin(conn)

        goto(Connected) using ConnEstablished(conn)

      } catch {
        case ex: Throwable => {
          logger.error(s"Couldn't initialize $processName: " + ex.getMessage)
          scheduleMsg(3000, AttemptConnect)
          stay using NoData
        }
      }
    }

    case Event(LostBrokerConnection(expected), NoData) => {
      if (!expected) {
        logger.warn(s"Saw unexpected lost connection event while $processName was already down")
      }
      stay using NoData
    }
  }

  when(Connected) {

    case Event(AttemptLogin, data @ ConnEstablished(conn)) => {
      attemptLogin(conn)
      stay using data
    }

    case Event(LoginSuccess(session), ConnEstablished(conn)) => {
      logger.info(s"$processName logged into services")

      val child = context.actorOf(factory(session, conn.serviceOperations))

      goto(Running) using SessionEstablished(child, session, conn)
    }

    case Event(LoginFailure(ex), data @ ConnEstablished(conn)) => {
      ex match {
        case ex: ServiceException =>
          logger.warn(s"$processName login failed: " + ex.getMessage)
          scheduleMsg(3000, AttemptLogin)
          stay using data
        case ex: Throwable =>
          logger.warn(s"$processName login error: " + ex.getMessage)
          conn.disconnect()
          scheduleMsg(3000, AttemptConnect)
          goto(Down) using NoData
      }
    }

    case Event(LostBrokerConnection(expected), ConnEstablished(conn)) => {
      conn.disconnect()
      onLostConnection
    }
  }

  when(Running) {

    case Event(RestartConnection(reason), SessionEstablished(child, _, conn)) =>
      logger.warn(s"Restarting $processName due to $reason")
      child ! PoisonPill
      onUnrecoverable(conn)

    case Event(LostBrokerConnection(expected), SessionEstablished(child, _, _)) =>
      child ! PoisonPill
      onLostConnection
  }

  def onUnrecoverable(conn: ServiceConnection) = {
    conn.disconnect()
    scheduleMsg(3000, AttemptConnect)
    goto(Down) using NoData
  }

  private def onLostConnection = {
    logger.warn(s"$processName lost connection")
    scheduleMsg(3000, AttemptConnect)
    goto(Down) using NoData
  }

  override def preStart() {
    self ! AttemptConnect
  }

  onTermination {
    case StopEvent(_, _, ConnEstablished(conn)) => conn.disconnect()
    case StopEvent(_, _, SessionEstablished(_, _, conn)) => conn.disconnect()
  }

  private def scheduleMsg(timeMs: Long, msg: AnyRef) {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(
      Duration(timeMs, MILLISECONDS),
      self,
      msg)
  }

  initialize()
}