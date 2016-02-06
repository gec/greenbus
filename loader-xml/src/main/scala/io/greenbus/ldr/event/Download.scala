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
import io.greenbus.client.service.proto.EventRequests.EventConfigQuery
import io.greenbus.client.service.proto.Events.EventConfig
import io.greenbus.loader.set.Downloader

import scala.concurrent.Future

object Download {

  def download(session: Session): Seq[EventConfig] = {
    val client = EventService.client(session)

    def configQuery(last: Option[String], size: Int): Future[Seq[EventConfig]] = {
      val b = EventConfigQuery.newBuilder().setPageSize(size)
      last.foreach(b.setLastEventType)
      client.eventConfigQuery(b.build())
    }

    def configPageBoundary(results: Seq[EventConfig]): String = {
      results.last.getEventType
    }

    Downloader.allPages(200, configQuery, configPageBoundary)
  }

  def toMdl(cfg: EventConfig): EventMdl.EventConfig = {

    val eventType = cfg.getEventType
    val designation = cfg.getDesignation
    val alarmStateOpt = if (cfg.hasAlarmState) Some(cfg.getAlarmState) else None
    val severity = cfg.getSeverity
    val resource = cfg.getResource

    EventMdl.EventConfig(eventType, severity, designation, alarmStateOpt, resource)
  }
}
