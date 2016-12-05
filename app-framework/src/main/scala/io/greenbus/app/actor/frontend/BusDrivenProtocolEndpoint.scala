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

import akka.actor.{ PoisonPill, ActorRef, Props }
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.{ Session, SessionUnusableException }
import io.greenbus.app.actor.MessageScheduling
import io.greenbus.app.actor.frontend.BusDrivenProtocolEndpoint.{ ProtocolFactory, ProxyFactory, RegisterFunc }
import io.greenbus.app.actor.frontend.ProcessingProxy.LinkLapsed
import io.greenbus.app.actor.util.NestedStateMachine
import io.greenbus.client.exception.{ LockedException, UnauthorizedException }
import io.greenbus.client.service.FrontEndService
import io.greenbus.client.service.proto.FrontEnd.FrontEndRegistration
import io.greenbus.client.service.proto.FrontEndRequests.FrontEndRegistrationTemplate
import io.greenbus.client.service.proto.Model.Endpoint

import scala.concurrent.{ ExecutionContext, Future }

object BusDrivenProtocolEndpoint {

  type RegisterFunc = (Session, Endpoint, Boolean) => Future[FrontEndRegistration]

  type ProxyFactory = (ActorRef, Endpoint) => Props
  type ProtocolFactory = (Endpoint, Session, MeasurementsPublished => Unit, StackStatusUpdated => Unit) => Props

  protected sealed trait State
  protected case object BusUp extends State
  protected case object RegistrationPending extends State
  protected case class Running(proxy: ActorRef, protocol: ActorRef, inputAddress: String) extends State
  protected case class RunningUnregistered(proxy: ActorRef, protocol: ActorRef) extends State
  protected case class RunningRegistrationPending(proxy: ActorRef, protocol: ActorRef) extends State

  private case class RegistrationSuccess(conns: FrontEndRegistration)
  private case class RegistrationFailure(ex: Throwable)

  private case object DoRegistrationRetry

  def register(session: Session, endpoint: Endpoint, holdingLock: Boolean, lockingEnabled: Boolean, nodeId: String)(implicit context: ExecutionContext): Future[FrontEndRegistration] = {

    val client = FrontEndService.client(session)

    val registration = FrontEndRegistrationTemplate.newBuilder()
      .setEndpointUuid(endpoint.getUuid)
      .setHoldingLock(holdingLock && lockingEnabled)
      .setFepNodeId(nodeId)
      .build()

    client.putFrontEndRegistration(registration, endpoint.getUuid.getValue)
  }

  def props(
    endpoint: Endpoint,
    session: Session,
    regFunc: RegisterFunc,
    proxyFactory: ProxyFactory,
    protocolFactory: ProtocolFactory,
    registrationRetryMs: Long): Props = {
    Props(classOf[BusDrivenProtocolEndpoint], endpoint, session, regFunc, proxyFactory, protocolFactory, registrationRetryMs)
  }
}

class BusDrivenProtocolEndpoint(
  endpoint: Endpoint,
  session: Session,
  regFunc: RegisterFunc,
  proxyFactory: ProxyFactory,
  protocolFactory: ProtocolFactory,
  registrationRetryMs: Long)
    extends NestedStateMachine with MessageScheduling with LazyLogging {

  import io.greenbus.app.actor.frontend.BusDrivenProtocolEndpoint._

  protected type StateType = State
  protected def start: StateType = RegistrationPending
  override protected def instanceId: Option[String] = Some(endpoint.getName)

  doRegistration()

  protected def machine = {
    case BusUp => {
      case DoRegistrationRetry => {
        doRegistration()
        RegistrationPending
      }
    }

    case RegistrationPending => {

      case RegistrationSuccess(result) => {

        val proxy = context.actorOf(proxyFactory(self, endpoint))
        proxy ! ProcessingProxy.LinkUp(session, result.getInputAddress)

        val protocol = context.actorOf(protocolFactory(endpoint, session, proxy ! _, proxy ! _))

        Running(proxy, protocol, result.getInputAddress)
      }

      case RegistrationFailure(ex) => {
        ex match {
          case ex: SessionUnusableException => throw ex
          case ex: UnauthorizedException => throw ex
          /*case ex: LockedException =>
            logger.warn(s"Endpoint ${endpoint.getName} registration failed")
            scheduleMsg(registrationRetryMs, DoRegistrationRetry)
            BusUp*/
          case ex: Throwable =>
            logger.warn(s"Endpoint ${endpoint.getName} registration failed")
            scheduleMsg(registrationRetryMs, DoRegistrationRetry)
            BusUp
        }
      }
    }

    case state: Running => {

      case ServiceSessionDead => {
        throw new SessionUnusableException("Proxy indicated session was dead")
      }

      case LinkLapsed => {
        doRegistration()
        RunningRegistrationPending(state.proxy, state.protocol)
      }
    }

    case state: RunningUnregistered => {

      case ServiceSessionDead => {
        throw new SessionUnusableException("Proxy indicated session was dead")
      }

      case DoRegistrationRetry => {
        doRegistration()
        RunningRegistrationPending(state.proxy, state.protocol)
      }
    }

    case state: RunningRegistrationPending => {

      case ServiceSessionDead => {
        throw new SessionUnusableException("Proxy indicated session was dead")
      }

      case RegistrationSuccess(result) => {
        state.proxy ! ProcessingProxy.LinkUp(session, result.getInputAddress)
        Running(state.proxy, state.protocol, result.getInputAddress)
      }

      case RegistrationFailure(ex) => {
        ex match {
          case ex: SessionUnusableException => throw ex
          case ex: UnauthorizedException => throw ex
          case ex: LockedException =>
            logger.warn(s"Endpoint ${endpoint.getName} registration returned locked, shutting down protocol")
            state.protocol ! PoisonPill
            state.proxy ! PoisonPill
            scheduleMsg(registrationRetryMs, DoRegistrationRetry)
            BusUp
          case ex: Throwable =>
            logger.warn(s"Endpoint ${endpoint.getName} registration failed")
            scheduleMsg(registrationRetryMs, DoRegistrationRetry)
            RunningUnregistered(state.proxy, state.protocol)
        }
      }
    }
  }

  private def doRegistration(): Unit = {
    import context.dispatcher
    val fut = regFunc(session, endpoint, true)

    fut.onSuccess { case result => self ! RegistrationSuccess(result) }
    fut.onFailure { case ex => self ! RegistrationFailure(ex) }
  }
}
