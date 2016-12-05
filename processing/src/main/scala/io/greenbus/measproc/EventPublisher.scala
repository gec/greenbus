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
package io.greenbus.measproc

import io.greenbus.msg.Session
import akka.actor.{ Actor, Props }
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.exception.{ ServiceException, UnauthorizedException }
import java.util.concurrent.TimeoutException
import io.greenbus.client.service.EventService
import scala.concurrent.duration._
import io.greenbus.client.service.proto.EventRequests.EventTemplate

object EventPublisher {

  val pubRetryMs = 1000

  case class EventTemplateGroup(events: Seq[EventTemplate.Builder])

  def props(session: Session): Props = {
    Props(classOf[EventPublisher], session)
  }
}

class EventPublisher(session: Session) extends Actor with LazyLogging {
  import EventPublisher._

  private case object PostSuccess
  private case class PostFailure(ex: Throwable)
  private case object PublishRetry

  private var queued = List.empty[EventTemplate]
  private var outstanding = List.empty[EventTemplate]

  def receive = {
    case EventTemplateGroup(events) =>
      logger.trace(s"Received events to publish")
      events.foreach(queued ::= _.build())
      if (outstanding.isEmpty) {
        publish()
      }

    case PostSuccess =>
      logger.trace(s"Published events")
      outstanding = Nil
      if (queued.nonEmpty) {
        publish()
      }

    case PostFailure(error) => {
      error match {
        case ex: UnauthorizedException => throw ex
        case ex: ServiceException =>
          logger.warn(s"Publishing failure: " + ex.getMessage)
          scheduleRetry()
        case ex: TimeoutException =>
          logger.warn(s"Publishing timeout")
          scheduleRetry()
        case ex => throw ex
      }
    }

    case PublishRetry =>
      if (queued.nonEmpty || outstanding.nonEmpty) {
        publish()
      }
  }

  private def scheduleRetry() {
    logger.warn(s"Publishing failure, retrying...")
    scheduleMsg(pubRetryMs, PublishRetry)
  }

  private def publish() {
    val client = EventService.client(session)

    outstanding :::= queued.reverse
    queued = Nil

    logger.trace("Publishing the following events: " + outstanding)

    val postFut = client.postEvents(outstanding)

    import context.dispatcher
    postFut.onSuccess { case result => self ! PostSuccess }
    postFut.onFailure { case ex => self ! PostFailure(ex) }
  }

  private def scheduleMsg(timeMs: Long, msg: AnyRef) {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(
      Duration(timeMs, MILLISECONDS),
      self,
      msg)
  }
}