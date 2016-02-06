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
package io.greenbus.ldr.event

import io.greenbus.msg.Session
import io.greenbus.client.service.EventService
import io.greenbus.client.service.proto.EventRequests.EventConfigTemplate
import io.greenbus.loader.set.PrintTracker
import io.greenbus.util.Timing

object Upload {

  import io.greenbus.loader.set.Upload._

  def upload(session: Session, all: Seq[EventMdl.EventConfig]) = {
    val timer = Timing.Stopwatch.start
    val tracker = new PrintTracker(60, System.out)

    val client = EventService.client(session)

    println("\n== Progress:")

    val eventConfigs = chunkedCalls(allChunkSize, all.map(toTemplate), client.putEventConfigs, tracker)

    println("\n")
    println(s"== Finished in ${timer.elapsed} ms")
  }

  def toTemplate(mdl: EventMdl.EventConfig): EventConfigTemplate = {

    val b = EventConfigTemplate.newBuilder()
      .setEventType(mdl.typ)
      .setSeverity(mdl.severity)
      .setDesignation(mdl.designation)
      .setResource(mdl.resource)

    mdl.initialAlarmState.foreach(b.setAlarmState)

    b.build()
  }
}
