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
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.amqp.AmqpServiceOperations
import io.greenbus.msg.{ Session, SessionUnusableException, Subscription, SubscriptionBinding }
import io.greenbus.client.exception.UnauthorizedException
import io.greenbus.client.service.ModelService
import io.greenbus.client.service.proto.Model._
import io.greenbus.client.service.proto.ModelRequests.EndpointSubscriptionQuery

import scala.concurrent.{ ExecutionContext, Future }

object EndpointMonitor {

  class EndpointConfigException(msg: String) extends Exception(msg)

  def queryEndpoint(session: Session, endpoint: Endpoint)(implicit context: ExecutionContext): Future[(Seq[Endpoint], Subscription[EndpointNotification])] = {
    val modelClient = ModelService.client(session)

    val endSubQuery = EndpointSubscriptionQuery.newBuilder().addUuids(endpoint.getUuid).build()

    modelClient.subscribeToEndpoints(endSubQuery)
  }

  case object MakeRequests
  case class EndpointConfig(endpoints: Seq[Endpoint], endpointSub: Subscription[EndpointNotification])
  case class RequestFailure(ex: Throwable)

  def props(endpoint: Endpoint, membership: CollectionMembership, session: Session, serviceOps: AmqpServiceOperations, factory: (Endpoint, Session, AmqpServiceOperations) => Props): Props =
    Props(classOf[EndpointMonitor], endpoint, membership, session, serviceOps, factory)

}

class EndpointMonitor(endpoint: Endpoint, membership: CollectionMembership, session: Session, serviceOps: AmqpServiceOperations, factory: (Endpoint, Session, AmqpServiceOperations) => Props) extends Actor with LazyLogging {
  import io.greenbus.app.actor.EndpointMonitor._

  private var endpointBinding = Option.empty[SubscriptionBinding]

  private var child = Option.empty[ActorRef]

  self ! MakeRequests

  def receive = {

    case MakeRequests => {
      import context.dispatcher

      val dataFut = queryEndpoint(session, endpoint)
      dataFut.onSuccess { case config => self ! EndpointConfig(config._1, config._2) }
      dataFut.onFailure { case ex => self ! RequestFailure(ex) }
    }

    case config: EndpointConfig => {
      import config._

      endpointBinding = Some(endpointSub)

      endpointSub.start { self ! _ }

      logger.info(s"Endpoint management initialized for ${endpoint.getName}")

      child = Some(context.actorOf(factory(endpoint, session, serviceOps)))
    }

    case endEvent: EndpointNotification => {
      import io.greenbus.client.proto.Envelope.SubscriptionEventType._

      // We need to help the collection manager know if our endpoint has fallen outside our set
      // If the collection manager is subscribing by name/protocol, a change to those parameters may not be
      // reflected in its subscription
      endEvent.getEventType match {
        case ADDED => logger.warn(s"Saw added in endpoint manager for ${endpoint.getName}; endpoint already existed")
        case REMOVED => // not our responsibility (collection manager will handle)
        case MODIFIED =>
          val changed = endEvent.getValue
          membership match {
            case AnyAndAllMembership =>
            case NamedMembership =>
              if (endpoint.getName != changed.getName) {
                throw new OutOfEndpointCollectionException(endpoint, "name changed")
              }
            case ProtocolMembership(valid) =>
              if (!valid.contains(changed.getProtocol)) {
                throw new OutOfEndpointCollectionException(endpoint, "protocol changed")
              }
          }
      }
    }

    case RequestFailure(ex) =>
      logger.warn(s"Error making requests for endpoint manager for ${endpoint.getName}: ${ex.getMessage}")
      throw ex
  }

  override def postStop() {
    endpointBinding.foreach(_.cancel())
  }

  override def supervisorStrategy: SupervisorStrategy = {
    import akka.actor.SupervisorStrategy._
    OneForOneStrategy() {
      case _: UnauthorizedException => Escalate
      case _: SessionUnusableException => Escalate
      case _: Throwable => Escalate
    }
  }

}
