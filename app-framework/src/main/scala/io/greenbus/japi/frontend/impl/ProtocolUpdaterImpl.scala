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

import io.greenbus.app.actor.frontend.{ StackStatusUpdated, MeasurementsPublished }
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.japi.frontend.{ NamedMeasurement, ProtocolUpdater }
import scala.collection.JavaConversions._

class ProtocolUpdaterImpl(scalaPublish: (MeasurementsPublished) => Unit, statusUpdate: (StackStatusUpdated) => Unit) extends ProtocolUpdater {
  def updateStatus(status: FrontEndConnectionStatus.Status): Unit = {
    statusUpdate(StackStatusUpdated(status))
  }

  def publish(wallTime: Long, updates: java.util.List[NamedMeasurement]): Unit = {
    scalaPublish(MeasurementsPublished(wallTime, Seq(), updates.map(nm => (nm.getName, nm.getValue))))
  }
}