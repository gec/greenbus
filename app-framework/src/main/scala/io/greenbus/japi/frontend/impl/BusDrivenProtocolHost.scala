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
package io.greenbus.japi.frontend.impl

import akka.actor.{ Props, Actor }
import com.google.common.util.concurrent.{ FutureCallback, Futures, SettableFuture }
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.app.actor.frontend.{ StackStatusUpdated, MeasurementsPublished }
import io.greenbus.client.service.proto.Commands.{ CommandStatus, CommandResult, CommandRequest }
import io.greenbus.client.service.proto.Model.Endpoint
import io.greenbus.japi.frontend.BusDrivenProtocolFactory
import io.greenbus.msg.Session
import io.greenbus.msg.japi.impl.SessionShim

import scala.concurrent.Future
import scala.concurrent.promise

object BusDrivenProtocolHost {

  def props(endpoint: Endpoint,
    session: Session,
    publishMeasurements: MeasurementsPublished => Unit,
    updateStatus: StackStatusUpdated => Unit,
    factory: BusDrivenProtocolFactory): Props = {
    Props(classOf[BusDrivenProtocolHost], endpoint, session, publishMeasurements, updateStatus, factory)
  }
}

class BusDrivenProtocolHost(
    endpoint: Endpoint,
    session: Session,
    publishMeasurements: MeasurementsPublished => Unit,
    updateStatus: StackStatusUpdated => Unit,
    factory: BusDrivenProtocolFactory) extends Actor with Logging {

  private val protocol = factory.initialize(endpoint, new SessionShim(session), new ProtocolUpdaterImpl(publishMeasurements, updateStatus))

  def receive = {
    case cmdReq: CommandRequest => {

      val scalaPromise = promise[CommandResult]
      val javaPromise: SettableFuture[CommandResult] = SettableFuture.create[CommandResult]()

      protocol.handleCommandRequest(cmdReq, javaPromise)

      Futures.addCallback(javaPromise, new FutureCallback[CommandResult] {

        override def onSuccess(v: CommandResult): Unit = {
          scalaPromise.success(v)
        }

        override def onFailure(throwable: Throwable): Unit = {
          scalaPromise.failure(throwable)
        }
      })

      sender ! scalaPromise.future
    }
  }

  override def postStop(): Unit = {
    try {
      protocol.onClose()
    } catch {
      case ex: Throwable =>
        logger.warn(s"Exception thrown for onClose for endpoint ${endpoint.getName}")
    }
  }
}
