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

import akka.actor.{ ActorRef, PoisonPill, Props }
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.msg.amqp.AmqpServiceOperations
import io.greenbus.msg.service.ServiceHandlerSubscription
import io.greenbus.msg.{ Session, SessionUnusableException }
import io.greenbus.app.actor.MessageScheduling
import io.greenbus.app.actor.frontend.CommandProxy.CommandSubscriptionRetrieved
import io.greenbus.app.actor.frontend.ProcessingProxy.LinkLapsed
import io.greenbus.app.actor.frontend.SimpleCommandProxy.ProtocolInitialized
import io.greenbus.app.actor.util.NestedStateMachine
import io.greenbus.client.exception.{ LockedException, UnauthorizedException }
import io.greenbus.client.service.FrontEndService
import io.greenbus.client.service.proto.FrontEnd.FrontEndRegistration
import io.greenbus.client.service.proto.FrontEndRequests.FrontEndRegistrationTemplate
import io.greenbus.client.service.proto.Model.Endpoint

import scala.concurrent.{ ExecutionContext, Future }

object BusDrivenWithCommandsProtocolEndpoint {

  type RegisterFunc = (Session, AmqpServiceOperations, Endpoint, Boolean) => (Future[FrontEndRegistration], ServiceHandlerSubscription)

  type ProxyFactory = (ActorRef, Endpoint) => Props
  type ProtocolFactory = (Endpoint, Session, MeasurementsPublished => Unit, StackStatusUpdated => Unit) => Props

  protected sealed trait State
  protected case object BusUp extends State
  protected case class RegistrationPending(cmdSub: ServiceHandlerSubscription) extends State
  protected case class Running(proxy: ActorRef, commandActor: ActorRef, protocol: ActorRef, inputAddress: String) extends State
  protected case class RunningUnregistered(proxy: ActorRef, commandActor: ActorRef, protocol: ActorRef) extends State
  protected case class RunningRegistrationPending(proxy: ActorRef, commandActor: ActorRef, cmdSub: ServiceHandlerSubscription, protocol: ActorRef) extends State

  private case class RegistrationSuccess(conns: FrontEndRegistration)
  private case class RegistrationFailure(ex: Throwable)

  private case object DoRegistrationRetry

  def register(session: Session, serviceOps: AmqpServiceOperations, endpoint: Endpoint, holdingLock: Boolean, lockingEnabled: Boolean, nodeId: String)(implicit context: ExecutionContext): (Future[FrontEndRegistration], ServiceHandlerSubscription) = {
    val serviceHandlerSub = serviceOps.routedServiceBinding()
    val cmdAddress = serviceHandlerSub.getId()

    try {
      val client = FrontEndService.client(session)

      val registration = FrontEndRegistrationTemplate.newBuilder()
        .setEndpointUuid(endpoint.getUuid)
        .setCommandAddress(cmdAddress)
        .setHoldingLock(holdingLock && lockingEnabled)
        .setFepNodeId(nodeId)
        .build()

      val regFut = client.putFrontEndRegistration(registration, endpoint.getUuid.getValue)

      (regFut, serviceHandlerSub)

    } catch {
      case ex: Throwable =>
        serviceHandlerSub.cancel()
        throw ex
    }
  }

  def props(
    endpoint: Endpoint,
    session: Session,
    serviceOps: AmqpServiceOperations,
    regFunc: RegisterFunc,
    proxyFactory: ProxyFactory,
    commandActorFactory: Endpoint => Props,
    protocolFactory: ProtocolFactory,
    registrationRetryMs: Long): Props = {
    Props(classOf[BusDrivenWithCommandsProtocolEndpoint], endpoint, session, serviceOps, regFunc, proxyFactory, commandActorFactory, protocolFactory, registrationRetryMs)
  }
}

import io.greenbus.app.actor.frontend.BusDrivenWithCommandsProtocolEndpoint._
class BusDrivenWithCommandsProtocolEndpoint(
  endpoint: Endpoint,
  session: Session,
  serviceOps: AmqpServiceOperations,
  regFunc: RegisterFunc,
  proxyFactory: ProxyFactory,
  commandActorFactory: Endpoint => Props,
  protocolFactory: ProtocolFactory,
  registrationRetryMs: Long)
    extends NestedStateMachine with MessageScheduling with Logging {

  protected type StateType = State
  protected def start: StateType = RegistrationPending(doRegistration())
  override protected def instanceId: Option[String] = Some(endpoint.getName)

  protected def machine = {
    case BusUp => {
      case DoRegistrationRetry => {
        RegistrationPending(doRegistration())
      }
    }

    case RegistrationPending(cmdSub) => {

      case RegistrationSuccess(result) => {

        val proxy = context.actorOf(proxyFactory(self, endpoint))
        proxy ! ProcessingProxy.LinkUp(session, result.getInputAddress)

        val protocol = context.actorOf(protocolFactory(endpoint, session, proxy ! _, proxy ! _))

        val commandProxy = context.actorOf(commandActorFactory(endpoint))

        commandProxy ! ProtocolInitialized(protocol)
        commandProxy ! CommandSubscriptionRetrieved(cmdSub)

        Running(proxy, commandProxy, protocol, result.getInputAddress)
      }

      case RegistrationFailure(ex) => {
        ex match {
          case ex: SessionUnusableException => throw ex
          case ex: UnauthorizedException => throw ex
          case ex: Throwable =>
            logger.warn(s"Endpoint ${endpoint.getName} registration failed")
            logger.debug(s"Endpoint ${endpoint.getName} registration failed: " + ex.getMessage)
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
        val cmdSub = doRegistration()
        RunningRegistrationPending(state.proxy, state.commandActor, cmdSub, state.protocol)
      }
    }

    case state: RunningUnregistered => {

      case ServiceSessionDead => {
        throw new SessionUnusableException("Proxy indicated session was dead")
      }

      case DoRegistrationRetry => {
        val cmdSub = doRegistration()
        RunningRegistrationPending(state.proxy, state.commandActor, cmdSub, state.protocol)
      }
    }

    case state: RunningRegistrationPending => {

      case ServiceSessionDead => {
        state.cmdSub.cancel()
        throw new SessionUnusableException("Proxy indicated session was dead")
      }

      case RegistrationSuccess(result) => {
        state.proxy ! ProcessingProxy.LinkUp(session, result.getInputAddress)
        state.commandActor ! CommandSubscriptionRetrieved(state.cmdSub)
        Running(state.proxy, state.commandActor, state.protocol, result.getInputAddress)
      }

      case RegistrationFailure(ex) => {
        state.cmdSub.cancel()
        ex match {
          case ex: SessionUnusableException => throw ex
          case ex: UnauthorizedException => throw ex
          case ex: LockedException =>
            logger.warn(s"Endpoint ${endpoint.getName} registration returned locked, shutting down protocol")
            state.protocol ! PoisonPill
            state.proxy ! PoisonPill
            state.commandActor ! PoisonPill
            scheduleMsg(registrationRetryMs, DoRegistrationRetry)
            BusUp
          case ex: Throwable =>
            logger.warn(s"Endpoint ${endpoint.getName} registration failed")
            scheduleMsg(registrationRetryMs, DoRegistrationRetry)
            RunningUnregistered(state.proxy, state.commandActor, state.protocol)
        }
      }
    }
  }

  private def doRegistration(): ServiceHandlerSubscription = {
    import context.dispatcher
    val (fut, cmdSub) = regFunc(session, serviceOps, endpoint, true)

    fut.onSuccess { case result => self ! RegistrationSuccess(result) }
    fut.onFailure { case ex => self ! RegistrationFailure(ex) }

    cmdSub
  }
}
