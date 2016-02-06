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
package io.greenbus.services

import akka.actor._
import com.typesafe.scalalogging.slf4j.Logging
import scala.concurrent.duration._
import java.util.concurrent.{ ExecutorService, Executors }
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.sql.{ DbConnector, SqlSettings, DbConnection }
import scala.concurrent.ExecutionContext.Implicits.global
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.client.ServiceConnection

object ServiceManager {

  sealed trait State
  case object Down extends State
  case object Running extends State

  sealed trait Data
  case object NoData extends Data
  case class Resources(sql: DbConnection, conn: ServiceConnection, exe: ExecutorService) extends Data

  case object AttemptConnect
  case class LostBrokerConnection(expected: Boolean)

  type ServiceRunner = (DbConnection, ServiceConnection, ExecutorService) => Unit

  def props(amqpConfigPath: String, sqlConfigPath: String, runner: ServiceRunner): Props =
    Props(classOf[ServiceManager], amqpConfigPath, sqlConfigPath, runner)
}

import ServiceManager._
class ServiceManager(amqpConfigPath: String, sqlConfigPath: String, runner: ServiceRunner) extends Actor with FSM[State, Data] with Logging {

  private def connectSql(): DbConnection = {
    val config = SqlSettings.load(sqlConfigPath)
    DbConnector.connect(config)
  }

  startWith(Down, NoData)

  when(Down) {
    case Event(AttemptConnect, NoData) => {
      try {
        val conn = ServiceConnection.connect(AmqpSettings.load(amqpConfigPath), QpidBroker, 5000)
        val exe = Executors.newCachedThreadPool()
        val sql = connectSql()

        conn.addConnectionListener { expected =>
          self ! LostBrokerConnection(expected)
        }

        runner(sql, conn, exe)

        goto(Running) using Resources(sql, conn, exe)

      } catch {
        case ex: Throwable => {
          logger.error("Couldn't initialize services: " + ex.getMessage)
          scheduleRetry()
          stay using NoData
        }
      }
    }
  }

  when(Running) {
    case Event(LostBrokerConnection(expected), res: Resources) =>
      res.exe.shutdown()
      scheduleRetry()
      goto(Down) using NoData
  }

  override def preStart() {
    self ! AttemptConnect
  }

  onTermination {
    case StopEvent(_, _, res: Resources) =>
      res.conn.disconnect()
      res.exe.shutdown()
  }

  private def scheduleRetry() {
    context.system.scheduler.scheduleOnce(
      Duration(3000, MILLISECONDS),
      self,
      AttemptConnect)
  }

  initialize()
}

