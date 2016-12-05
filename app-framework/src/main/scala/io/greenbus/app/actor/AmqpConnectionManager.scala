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
import io.greenbus.app.actor.util.NestedStateMachine
import io.greenbus.client.ServiceConnection
import io.greenbus.client.exception.UnauthorizedException
import io.greenbus.msg.SessionUnusableException
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import scala.concurrent.duration._

object FailoverConnectionManager {

  sealed trait State
  case class Down(configOffset: Int, failures: Int, sequence: Int) extends State
  case class Running(configOffset: Int, connection: ServiceConnection, child: ActorRef, sequence: Int) extends State

  case object AttemptConnect
  case class LostBrokerConnection(expected: Boolean, sequence: Int)

  def props(processName: String,
    amqpConfigFileList: IndexedSeq[String],
    failureLimit: Int,
    retryDelayMs: Long,
    connectionTimeoutMs: Long,
    factory: ServiceConnection => Props): Props = {
    Props(classOf[FailoverConnectionManager], processName, amqpConfigFileList, failureLimit, retryDelayMs, connectionTimeoutMs, factory)
  }
}

class FailoverConnectionManager(
    processName: String,
    amqpConfigFileList: IndexedSeq[String],
    failureLimit: Int,
    retryDelayMs: Long,
    connectionTimeoutMs: Long,
    factory: ServiceConnection => Props) extends NestedStateMachine with MessageScheduling with LazyLogging {
  import FailoverConnectionManager._

  protected type StateType = State
  protected def start: StateType = Down(0, 0, 0)

  self ! AttemptConnect

  protected def machine = {

    case state @ Down(offset, failures, sequence) => {

      case AttemptConnect => {

        val configToAttempt = amqpConfigFileList(offset)
        logger.info(s"Process $processName attempting connection using config: $configToAttempt")
        logger.debug(s"Process $processName attempting connection, offset: $offset, failures: $failures, list: $amqpConfigFileList")

        try {
          val connection = connectQpid(configToAttempt)

          connection.addConnectionListener { expected =>
            self ! LostBrokerConnection(expected, sequence)
          }

          val child = context.actorOf(factory(connection))

          Running(offset, connection, child, sequence)

        } catch {
          case ex: Throwable =>

            logger.error(s"Couldn't initialize connection for $processName: " + ex.getMessage)

            scheduleMsg(retryDelayMs, AttemptConnect)
            if (failures >= failureLimit) {
              val nextOffset = if (offset >= (amqpConfigFileList.size - 1)) 0 else offset + 1
              Down(nextOffset, 0, sequence)
            } else {
              Down(offset, failures + 1, sequence)
            }

        }
      }
      case LostBrokerConnection(expected, connSeq) => {
        if (!expected) {
          logger.warn(s"Saw unexpected lost connection event while $processName was already down")
        }
        state
      }
    }

    case state @ Running(configOffset, connection, child, sequence) => {

      case LostBrokerConnection(expected, connSeq) => {
        if (connSeq == sequence) {

          try {
            connection.disconnect()
          } catch {
            case ex: Throwable =>
          }
          child ! PoisonPill

          logger.info(s"Process $processName lost connection")

          scheduleMsg(retryDelayMs, AttemptConnect)

          Down(configOffset, 0, sequence + 1)

        } else {
          state
        }
      }

      case RestartConnection(reason) => {
        logger.warn(s"Restarting $processName due to $reason")

        try {
          connection.disconnect()
        } catch {
          case ex: Throwable =>
        }
        child ! PoisonPill

        scheduleMsg(retryDelayMs, AttemptConnect)

        Down(configOffset, 0, sequence + 1)
      }
    }
  }

  private def connectQpid(path: String): ServiceConnection = {
    val config = AmqpSettings.load(path)
    val connection = ServiceConnection.connect(config, QpidBroker, connectionTimeoutMs)
    logger.info(s"Connected to broker: $config")
    connection
  }

  override protected def onShutdown(state: State): Unit = {
    state match {
      case Running(configOffset, connection, child, sequence) =>
        logger.info(s"Disconnecting on shutdown for $processName")
        connection.disconnect()
      case _ =>
    }
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

}
