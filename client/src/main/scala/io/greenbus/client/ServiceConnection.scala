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
package io.greenbus.client

import io.greenbus.msg.amqp._
import io.greenbus.msg.Session
import scala.concurrent.{ ExecutionContext, Await, Future }
import java.util.concurrent.{ ExecutorService, Executors }
import io.greenbus.msg.util.Scheduler
import scala.concurrent.duration.Duration
import io.greenbus.client.service.proto.LoginRequests.LoginRequest
import io.greenbus.client.service.LoginService

object ServiceHeaders {
  def tokenHeader() = "AUTH_TOKEN"
}

object ServiceConnection {

  def connect(settings: AmqpSettings, broker: AmqpBroker, timeoutMs: Long): ServiceConnection = {
    val exe = Executors.newScheduledThreadPool(5)
    val factory = new AmqpConnectionFactory(settings, broker, timeoutMs, Scheduler(exe))
    new DefaultConnection(factory.connect, exe)
  }

  private class DefaultConnection(conn: AmqpConnection, exe: ExecutorService) extends ServiceConnection {

    def login(user: String, password: String): Future[Session] = {
      import ExecutionContext.Implicits.global
      val sess = session
      val loginClient = LoginService.client(sess)

      val loginRequest = LoginRequest.newBuilder()
        .setName(user)
        .setPassword(password)
        .setLoginLocation("")
        .setClientVersion(version.Version.clientVersion)
        .build()

      loginClient.login(loginRequest).map { result =>
        sess.addHeader(ServiceHeaders.tokenHeader(), result.getToken)
        sess
      }
    }
    def login(user: String, password: String, timeout: Duration): Session = {
      Await.result(login(user, password), timeout)
    }

    def session: Session = conn.createSession(ServiceMessagingCodec.codec)

    def serviceOperations: AmqpServiceOperations = conn.serviceOperations

    def disconnect() {
      conn.disconnect()
      exe.shutdown()
    }

    def addConnectionListener(listener: (Boolean) => Unit) {
      conn.addConnectionListener(listener)
    }

    def removeConnectionListener(listener: (Boolean) => Unit) {
      conn.removeConnectionListener(listener)
    }
  }

}

trait ServiceConnection {

  def addConnectionListener(listener: Boolean => Unit)
  def removeConnectionListener(listener: Boolean => Unit)

  def login(user: String, password: String): Future[Session]
  def login(user: String, password: String, duration: Duration): Session

  def session: Session

  def serviceOperations: AmqpServiceOperations

  def disconnect()
}
