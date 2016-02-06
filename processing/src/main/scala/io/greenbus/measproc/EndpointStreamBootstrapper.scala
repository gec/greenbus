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

import akka.actor._
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.msg.Session
import io.greenbus.app.actor.MessageScheduling
import io.greenbus.client.service.proto.Model.Endpoint
import io.greenbus.measproc.EndpointStreamBootstrapper.ChildFactory

import scala.concurrent.duration._

object EndpointStreamBootstrapper {
  val retryPeriod = Duration(3000, MILLISECONDS)

  type ChildFactory = (Endpoint, EndpointConfiguration) => Props

  private case object DoLookup
  private case class GotConfiguration(config: EndpointConfiguration)
  private case class ConfigurationFailure(ex: Throwable)

  def props(endpoint: Endpoint, session: Session, factory: ChildFactory): Props = {
    Props(classOf[EndpointStreamBootstrapper], endpoint, session, factory)
  }
}
class EndpointStreamBootstrapper(endpoint: Endpoint, session: Session, factory: ChildFactory) extends Actor with MessageScheduling with Logging {
  import context.dispatcher
  import io.greenbus.measproc.EndpointStreamBootstrapper._
  import io.greenbus.measproc.ServiceConfiguration._

  // Restart us if the child restarts, otherwise the restarted child would be with the old data
  override def supervisorStrategy: SupervisorStrategy = {
    import akka.actor.SupervisorStrategy._
    OneForOneStrategy() {
      case _: Throwable => Escalate
    }
  }

  private var child = Option.empty[ActorRef]

  self ! DoLookup

  def receive = {

    case DoLookup => {
      logger.info(s"Requesting configuration for endpoint ${endpoint.getName}")
      val configRequest = configForEndpoint(session, endpoint.getUuid)

      configRequest.onSuccess {
        case config => self ! GotConfiguration(config)
      }
      configRequest.onFailure {
        case ex: Throwable => self ! ConfigurationFailure(ex)
      }
    }

    case GotConfiguration(config) => {
      child = Some(context.actorOf(factory(endpoint, config)))
    }

    case ConfigurationFailure(exception) => {
      logger.error(s"Couldn't get configuration for endpoint ${endpoint.getName}: ${exception.getMessage}")
      scheduleRetry()
    }
  }

  private def scheduleRetry() {
    context.system.scheduler.scheduleOnce(
      retryPeriod,
      self,
      DoLookup)
  }
}
