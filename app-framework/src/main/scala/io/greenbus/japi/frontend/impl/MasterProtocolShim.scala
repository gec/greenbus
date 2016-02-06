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

import com.google.common.util.concurrent.{ ListenableFuture, FutureCallback, Futures }
import io.greenbus.app.actor.frontend.{ ProtocolCommandAcceptor => ScalaCommandAcceptor, StackStatusUpdated, MeasurementsPublished }
import io.greenbus.client.service.proto.Commands.{ CommandResult, CommandRequest }
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.client.service.proto.Model.Endpoint
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.japi.frontend.{ NamedMeasurement, ProtocolUpdater }
import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.promise

class MasterProtocolShim[ProtocolConfig](javaProtocol: io.greenbus.japi.frontend.MasterProtocol[ProtocolConfig])
    extends io.greenbus.app.actor.frontend.MasterProtocol[ProtocolConfig] {

  def add(endpoint: Endpoint, protocolConfig: ProtocolConfig, scalaPublish: (MeasurementsPublished) => Unit, statusUpdate: (StackStatusUpdated) => Unit): ScalaCommandAcceptor = {

    val updater = new ProtocolUpdater {
      def updateStatus(status: FrontEndConnectionStatus.Status): Unit = {
        statusUpdate(StackStatusUpdated(status))
      }

      def publish(wallTime: Long, updates: java.util.List[NamedMeasurement]): Unit = {
        scalaPublish(MeasurementsPublished(wallTime, Seq(), updates.map(nm => (nm.getName, nm.getValue))))
      }
    }

    val javaAcceptor = javaProtocol.add(endpoint, protocolConfig, updater)

    new ScalaCommandAcceptor {
      def issue(commandName: String, request: CommandRequest): Future[CommandResult] = {

        val scalaPromise = promise[CommandResult]()

        val javaFuture: ListenableFuture[CommandResult] = javaAcceptor.issue(commandName, request)

        Futures.addCallback(javaFuture, new FutureCallback[CommandResult] {
          def onSuccess(v: CommandResult): Unit = {
            scalaPromise.success(v)
          }

          def onFailure(ex: Throwable): Unit = {
            scalaPromise.failure(ex)
          }
        })

        scalaPromise.future
      }
    }

  }

  def remove(endpointUuid: ModelUUID): Unit = {
    javaProtocol.remove(endpointUuid)
  }

  def shutdown(): Unit = {
    javaProtocol.shutdown()
  }
}
