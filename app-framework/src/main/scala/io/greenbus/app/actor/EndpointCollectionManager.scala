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
import akka.actor.Actor
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.{ SessionUnusableException, SubscriptionBinding, Subscription, Session }
import io.greenbus.msg.amqp.AmqpServiceOperations
import io.greenbus.client.service.proto.Model.{ EndpointNotification, Endpoint }
import io.greenbus.client.exception.UnauthorizedException
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.client.proto.Envelope.SubscriptionEventType
import java.io.IOException

class OutOfEndpointCollectionException(end: Endpoint, msg: String) extends Exception(msg) {
  def endpoint: Endpoint = end
}

object EndpointCollectionManager {

  case class EndpointsResolved(endpoints: Seq[Endpoint]) // Unlike added it just means it was there when we came up
  case class EndpointAdded(endpoint: Endpoint)
  case class EndpointModified(endpoint: Endpoint)
  case class EndpointRemoved(endpoint: Endpoint)
  case class EndpointOutOfCollection(endpoint: Endpoint) // Unlike removed, just means we're not responsible for it anymore

  private case object LookupEndpoints
  private case class EndpointsResult(poll: Seq[Endpoint], subscription: Subscription[EndpointNotification])
  private case class RequestFailure(ex: Throwable)

  def props(strategy: EndpointCollectionStrategy, session: Session, serviceOps: AmqpServiceOperations, endpointObserver: Option[ActorRef], factory: (Endpoint, CollectionMembership, Session, AmqpServiceOperations) => Props): Props =
    Props(classOf[EndpointCollectionManager], strategy, session, serviceOps, endpointObserver, factory)

}

class EndpointCollectionManager(strategy: EndpointCollectionStrategy, session: Session, serviceOps: AmqpServiceOperations, endpointObserver: Option[ActorRef], factory: (Endpoint, CollectionMembership, Session, AmqpServiceOperations) => Props) extends Actor with LazyLogging {
  import EndpointCollectionManager._

  private var binding = Option.empty[SubscriptionBinding]
  private var streams = Map.empty[ModelUUID, ActorRef]

  self ! LookupEndpoints

  def receive = {

    case LookupEndpoints => {

      import context.dispatcher
      val configFut = strategy.configuration(session)

      configFut.onSuccess { case (poll, subscription) => self ! EndpointsResult(poll, subscription) }
      configFut.onFailure { case ex => self ! RequestFailure(ex) }
    }

    case EndpointsResult(results, subscription) => {

      binding = Some(subscription)
      val whitelistOpt = strategy.nameWhitelist

      whitelistOpt match {
        case None => subscription.start { event => self ! event }
        case Some(whitelist) =>
          subscription.start { event =>
            if (whitelist.contains(event.getValue.getName)) {
              self ! event
            }
          }
      }

      endpointObserver.foreach(ref => ref ! EndpointsResolved(results))

      val initial = results.filter(_.getDisabled == false).map { endpoint =>
        (endpoint.getUuid, launchStream(endpoint))
      }

      streams = initial.toMap

      logger.info("Endpoint collection management initialized")
    }

    case RequestFailure(ex) => throw ex

    case event: EndpointNotification => {
      val endpoint = event.getValue
      val uuid = endpoint.getUuid
      val name = endpoint.getName

      endpointObserver.foreach { obsRef =>
        val msg = event.getEventType match {
          case SubscriptionEventType.ADDED => EndpointAdded(endpoint)
          case SubscriptionEventType.MODIFIED => EndpointModified(endpoint)
          case SubscriptionEventType.REMOVED => EndpointRemoved(endpoint)
        }

        obsRef ! msg
      }

      (event.getEventType, endpoint.getDisabled) match {
        case (SubscriptionEventType.ADDED, false) =>
          if (!streams.contains(uuid)) {
            addStream(endpoint)
          } else {
            logger.warn(s"Saw add event on existing endpoint stream: $name (${uuid.getValue})")
          }
        case (SubscriptionEventType.ADDED, true) =>
        case (SubscriptionEventType.MODIFIED, false) =>
          if (!streams.contains(uuid)) {
            addStream(endpoint)
          }
        case (SubscriptionEventType.MODIFIED, true) => removeStream(uuid)
        case (SubscriptionEventType.REMOVED, _) => removeStream(uuid)
      }
    }

  }

  private def addStream(endpoint: Endpoint) {
    streams = streams + ((endpoint.getUuid, launchStream(endpoint)))
  }
  private def removeStream(uuid: ModelUUID) {
    logger.debug("Removing endpoint " + uuid.getValue)
    streams.get(uuid).foreach(ref => ref ! PoisonPill)
    streams -= uuid
  }

  private def launchStream(endpoint: Endpoint): ActorRef = {
    logger.debug("Launching endpoint " + endpoint.getName)
    context.actorOf(factory(endpoint, strategy.membership, session.spawn(), serviceOps))
  }

  override def supervisorStrategy: SupervisorStrategy = {
    import SupervisorStrategy._
    OneForOneStrategy() {
      case out: OutOfEndpointCollectionException => {
        logger.info("Endpoint marked itself as out of collection: " + out.endpoint.getName)
        endpointObserver.foreach(_ ! EndpointOutOfCollection(out.endpoint))
        removeStream(out.endpoint.getUuid)
        Resume
      }
      case _: UnauthorizedException => Escalate
      case _: SessionUnusableException => Escalate
      case _: IOException => Escalate
      case _: Throwable => Escalate
    }
  }
}

