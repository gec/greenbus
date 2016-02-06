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
package io.greenbus.app.actor.frontend

import akka.actor._
import akka.pattern.gracefulStop
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.app.actor.RestartConnection
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpServiceOperations
import io.greenbus.app.actor.frontend.FepEndpointCollectionManager._
import io.greenbus.app.actor.frontend.FrontEndSetManager._
import io.greenbus.client.service.proto.Model.{ Endpoint, ModelUUID }

import scala.concurrent.Future
import scala.concurrent.duration._

object FrontEndSetManager {

  case object BusConnectionStop
  case object BusConnectionStart

  type ConnMgrFactory = (ActorRef) => Props
  type ChildFactory[ProtocolConfig] = (Endpoint, ActorRef, MasterProtocol[ProtocolConfig]) => Props
  type ProtocolMgrFactory[ProtocolConfig] = (ActorContext) => MasterProtocol[ProtocolConfig]

  def props[ProtocolConfig](connMgrFactory: ConnMgrFactory, childFactory: ChildFactory[ProtocolConfig], protFactory: ProtocolMgrFactory[ProtocolConfig]): Props = {
    Props(classOf[FrontEndSetManager[ProtocolConfig]], connMgrFactory, childFactory, protFactory)
  }
}
class FrontEndSetManager[ProtocolConfig](connMgrFactory: ConnMgrFactory, childFactory: ChildFactory[ProtocolConfig], protFactory: ProtocolMgrFactory[ProtocolConfig]) extends Actor with Logging {

  private val protocolMgr = protFactory(context)

  private var connMgr: Option[ActorRef] = Some(context.actorOf(connMgrFactory(self)))

  private var endpointMap = Map.empty[ModelUUID, ActorRef]

  private var linkOpt = Option.empty[(Session, AmqpServiceOperations)]

  def receive = {

    case BusConnectionStop => {
      sender ! connMgr.map(ref => gracefulStop(ref, 5000.milliseconds)).getOrElse(Future.successful(true))
      connMgr = None
    }

    case BusConnectionStart => {
      if (connMgr.isEmpty) {
        connMgr = Some(context.actorOf(connMgrFactory(self)))
      }
    }

    case ServiceSessionDead => {
      linkOpt = None
      connMgr.foreach { _ ! RestartConnection("session dead") }
    }

    case EndpointsResolved(session, serviceOps, endpoints) => {

      linkOpt = Some((session, serviceOps))

      // Check if any have been removed or disabled
      val resolvedMap = endpoints.map(e => (e.getUuid, e)).toMap

      val missingUuids = endpointMap.keySet.filterNot(resolvedMap.contains)

      if (missingUuids.nonEmpty) {
        logger.info("When resolved, the following endpoints were missing and are being stopped: " + missingUuids.flatMap(resolvedMap.get).map(_.getName).mkString(", "))
        missingUuids.flatMap(endpointMap.get).foreach(_ ! PoisonPill)
        endpointMap = endpointMap -- missingUuids
      }

      val disabledSet = endpoints.filter(_.getDisabled).map(_.getUuid).toSet
      val nowDisabled = disabledSet.filter(endpointMap.contains)

      if (nowDisabled.nonEmpty) {
        logger.info("When resolved, the following endpoints were missing and are being stopped: " + missingUuids.flatMap(resolvedMap.get).map(_.getName).mkString(", "))
        nowDisabled.flatMap(endpointMap.get).foreach(_ ! PoisonPill)
        endpointMap = endpointMap -- nowDisabled
      }

      val notDisabledEndpoints = endpoints.filterNot(_.getDisabled)
      val notStarted = notDisabledEndpoints.filterNot(e => endpointMap.contains(e.getUuid))

      val created = notStarted.map(end => (end.getUuid, context.actorOf(childFactory(end, self, protocolMgr))))

      endpointMap = endpointMap ++ created

      endpointMap.values.foreach { _ ! FrontendProtocolEndpoint.Connected(session, serviceOps) }
    }

    case EndpointAdded(endpoint) => {
      if (!endpoint.getDisabled && !endpointMap.contains(endpoint.getUuid)) {
        addEndpoint(endpoint)
      }
    }

    case EndpointModified(endpoint) => {
      (endpoint.getDisabled, endpointMap.get(endpoint.getUuid)) match {
        case (true, Some(ref)) =>
          logger.info("Endpoint " + endpoint.getName + " disabled, shutting down protocol master")
          ref ! PoisonPill
          endpointMap -= endpoint.getUuid
        case (false, None) =>
          logger.info("Endpoint " + endpoint.getName + " not disabled, starting protocol master")
          addEndpoint(endpoint)
        case _ =>
      }
    }

    case EndpointRemoved(endpoint) => {
      endpointMap.get(endpoint.getUuid).foreach { ref =>
        logger.info("Endpoint " + endpoint.getName + " removed, shutting down protocol master")
        ref ! PoisonPill
        endpointMap -= endpoint.getUuid
      }
    }

    case EndpointOutOfCollection(endpoint) => {
      endpointMap.get(endpoint.getUuid).foreach { ref =>
        logger.info("Endpoint " + endpoint.getName + " removed from collection, shutting down protocol master")
        ref ! PoisonPill
        endpointMap -= endpoint.getUuid
      }
    }
  }

  override def postStop(): Unit = {
    protocolMgr.shutdown()
  }

  private def addEndpoint(endpoint: Endpoint): Unit = {
    val actor = context.actorOf(childFactory(endpoint, self, protocolMgr))
    endpointMap = endpointMap.updated(endpoint.getUuid, actor)
    linkOpt.foreach { case (sess, ops) => actor ! FrontendProtocolEndpoint.Connected(sess, ops) }
  }

}
