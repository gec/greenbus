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
import io.greenbus.app.actor.frontend.CommandProxy.ProtocolInitialized
import io.greenbus.app.actor.frontend.FrontendConfigUpdater.{ CommandsUpdated, ConfigFilesUpdated, PointsUpdated }
import io.greenbus.app.actor.frontend.FrontendProtocolEndpoint._
import io.greenbus.app.actor.frontend.ProcessingProxy.LinkLapsed
import io.greenbus.app.actor.util.NestedStateMachine
import io.greenbus.client.exception.{ LockedException, UnauthorizedException }
import io.greenbus.client.service.FrontEndService
import io.greenbus.client.service.proto.Commands.{ CommandRequest, CommandResult }
import io.greenbus.client.service.proto.FrontEnd.{ FrontEndConnectionStatus, FrontEndRegistration }
import io.greenbus.client.service.proto.FrontEndRequests.FrontEndRegistrationTemplate
import io.greenbus.client.service.proto.Model.{ Endpoint, EntityKeyValue }

import scala.concurrent.{ ExecutionContext, Future }

case object ServiceSessionDead

object FrontendProtocolEndpoint {

  type ConfigUpdaterFactory = (ActorRef, Endpoint, Session, FrontendConfiguration) => Props
  type ProxyFactory = (ActorRef, Endpoint) => Props
  type CommandActorFactory = Endpoint => Props

  type RegisterFunc = (Session, AmqpServiceOperations, Endpoint, Boolean) => (Future[FrontEndRegistration], ServiceHandlerSubscription)
  type ConfigureFunc = (Session, Endpoint) => Future[FrontendConfiguration]

  protected sealed trait BusState
  protected trait BindingHoldingBusState extends BusState {
    val commandSub: ServiceHandlerSubscription
    def cleanup() { commandSub.cancel() }
  }
  protected case class ServicesDown(linkPendingSince: Option[Long]) extends BusState
  protected case class ServicesUp(session: Session, serviceOps: AmqpServiceOperations, linkPendingSince: Option[Long]) extends BusState
  protected case class RegistrationPending(session: Session, serviceOps: AmqpServiceOperations, commandSub: ServiceHandlerSubscription, linkPendingSince: Option[Long]) extends BindingHoldingBusState
  protected case class Registered(session: Session, serviceOps: AmqpServiceOperations, commandSub: ServiceHandlerSubscription, inputAddress: String) extends BindingHoldingBusState
  protected case class ConfigPending(session: Session, serviceOps: AmqpServiceOperations, commandSub: ServiceHandlerSubscription, inputAddress: String, configFut: Future[FrontendConfiguration]) extends BindingHoldingBusState
  protected case class Configured(session: Session, serviceOps: AmqpServiceOperations, commandSub: ServiceHandlerSubscription, inputAddress: String, updater: ActorRef) extends BindingHoldingBusState

  protected sealed trait ProtocolState
  protected case object ProtocolUninit extends ProtocolState

  protected sealed trait ProtocolActiveState extends ProtocolState {
    val configs: Seq[EntityKeyValue]
    val holdingLock: Boolean = false
  }
  protected case class ProtocolPending(configs: Seq[EntityKeyValue]) extends ProtocolActiveState
  protected case class ProtocolError(configs: Seq[EntityKeyValue]) extends ProtocolActiveState
  protected case class ProtocolUp(configs: Seq[EntityKeyValue]) extends ProtocolActiveState {
    override val holdingLock = true
  }
  protected case class ProtocolDown(configs: Seq[EntityKeyValue]) extends ProtocolActiveState

  case class Connected(session: Session, serviceOps: AmqpServiceOperations)
  private case class RegistrationSuccess(conns: FrontEndRegistration)
  private case class RegistrationFailure(ex: Throwable)
  private case class ConfigurationSuccess(config: FrontendConfiguration)
  private case class ConfigurationFailure(ex: Throwable)

  private sealed trait Retry
  private case object DoRegistrationRetry extends Retry
  private case object DoConfigRetry extends Retry
  private case class DoReleaseTimeoutCheck(lossTime: Long) extends Retry

  private case class CommandIssued(req: CommandRequest, response: CommandResult => Unit)

  def props[ProtocolConfig](
    endpoint: Endpoint,
    manager: ActorRef,
    protocolMgr: MasterProtocol[ProtocolConfig],
    configurer: ProtocolConfigurer[ProtocolConfig],
    registrationRetryMs: Long,
    releaseTimeoutMs: Long,
    proxyFactory: ProxyFactory,
    commandActorFactory: CommandActorFactory,
    configUpdaterFactory: ConfigUpdaterFactory,
    registerFunc: RegisterFunc,
    configureFunc: ConfigureFunc): Props = {

    Props(classOf[FrontendProtocolEndpoint[ProtocolConfig]], endpoint, manager, protocolMgr, configurer, registrationRetryMs, releaseTimeoutMs, proxyFactory, commandActorFactory, configUpdaterFactory, registerFunc, configureFunc)
  }

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

  def configsEqual(l: Seq[EntityKeyValue], r: Seq[EntityKeyValue]): Boolean = {
    l.map(_.hashCode()).sorted == r.map(_.hashCode()).sorted
  }
}

class FrontendProtocolEndpoint[ProtocolConfig](
    endpoint: Endpoint,
    manager: ActorRef,
    protocolMgr: MasterProtocol[ProtocolConfig],
    configurer: ProtocolConfigurer[ProtocolConfig],
    registrationRetryMs: Long,
    releaseTimeoutMs: Long,
    proxyFactory: ProxyFactory,
    commandActorFactory: CommandActorFactory,
    configUpdaterFactory: ConfigUpdaterFactory,
    registerFunc: RegisterFunc,
    configureFunc: ConfigureFunc) extends NestedStateMachine with MessageScheduling with Logging {
  import context.dispatcher
  import io.greenbus.app.actor.frontend.FrontendProtocolEndpoint._

  private val proxy = context.actorOf(proxyFactory(self, endpoint))
  private val commandActor = context.actorOf(commandActorFactory(endpoint))

  protected type StateType = (BusState, ProtocolState)
  protected def start: StateType = (ServicesDown(None), ProtocolUninit)
  override protected def instanceId: Option[String] = Some(endpoint.getName)

  protected def machine = {

    case state @ (busState: ServicesDown, ProtocolUninit) => {

      case Connected(session, serviceOps) => {
        (doRegistration(session, serviceOps, holdingLock = false, busState.linkPendingSince), ProtocolUninit)
      }
    }

    case state @ (busState: ServicesUp, ProtocolUninit) => {

      case ServiceSessionDead => {
        manager ! ServiceSessionDead
        (ServicesDown(busState.linkPendingSince), ProtocolUninit)
      }
      case Connected(session, serviceOps) => {
        (doRegistration(session, serviceOps, holdingLock = false, busState.linkPendingSince), ProtocolUninit)
      }
      case DoRegistrationRetry => {
        (doRegistration(busState.session, busState.serviceOps, holdingLock = false, busState.linkPendingSince), ProtocolUninit)
      }
    }

    case state @ (busState: RegistrationPending, ProtocolUninit) => {

      case ServiceSessionDead => {
        manager ! ServiceSessionDead
        busState.commandSub.cancel()
        (ServicesDown(busState.linkPendingSince), ProtocolUninit)
      }
      case Connected(session, serviceOps) => {
        busState.commandSub.cancel()
        (doRegistration(session, serviceOps, holdingLock = false, busState.linkPendingSince), ProtocolUninit)
      }
      case RegistrationFailure(ex) => {

        def sessionDead(): StateType = {
          manager ! ServiceSessionDead
          busState.commandSub.cancel()
          (ServicesDown(busState.linkPendingSince), ProtocolUninit)
        }

        def retry(): StateType = {
          scheduleMsg(registrationRetryMs, DoRegistrationRetry)
          busState.commandSub.cancel()
          (ServicesUp(busState.session, busState.serviceOps, busState.linkPendingSince), ProtocolUninit)
        }

        ex match {
          case ex: SessionUnusableException => sessionDead()
          case ex: UnauthorizedException => sessionDead()
          case ex: LockedException => retry()
          case ex: Throwable => retry()
        }
      }
      case RegistrationSuccess(registration) => {

        proxy ! ProcessingProxy.LinkUp(busState.session, registration.getInputAddress)

        commandActor ! CommandProxy.CommandSubscriptionRetrieved(busState.commandSub)

        (doConfig(busState.session, busState.serviceOps, busState.commandSub, registration.getInputAddress), ProtocolUninit)
      }
    }

    case state @ (busState: Registered, ProtocolUninit) => {

      case ServiceSessionDead => {
        busState.commandSub.cancel()
        sessionDeadGoDown(ProtocolUninit)
      }
      case Connected(session, serviceOps) => {
        busState.commandSub.cancel()
        doRegistrationAndSwitch(session, serviceOps, holdingLock = false, ProtocolUninit)
      }
      case LinkLapsed => {
        busState.commandSub.cancel()
        doRegistrationAndSwitch(busState.session, busState.serviceOps, holdingLock = false, ProtocolUninit)
      }
      case DoConfigRetry => {
        (doConfig(busState.session, busState.serviceOps, busState.commandSub, busState.inputAddress), ProtocolUninit)
      }
    }

    case state @ (busState: ConfigPending, ProtocolUninit) => {

      case ServiceSessionDead => {
        busState.commandSub.cancel()
        sessionDeadGoDown(ProtocolUninit)
      }
      case Connected(session, serviceOps) => {
        busState.commandSub.cancel()
        doRegistrationAndSwitch(session, serviceOps, holdingLock = false, ProtocolUninit)
      }
      case LinkLapsed => {
        busState.commandSub.cancel()
        doRegistrationAndSwitch(busState.session, busState.serviceOps, holdingLock = false, ProtocolUninit)
      }
      case ConfigurationFailure(ex) => {
        logger.debug(s"Endpoint ${endpoint.getName} saw configuration failure: $ex")

        def sessionDead(): StateType = {
          busState.commandSub.cancel()
          sessionDeadGoDown(ProtocolUninit)
        }

        def retry(): StateType = {
          scheduleMsg(registrationRetryMs, DoConfigRetry)
          (Registered(busState.session, busState.serviceOps, busState.commandSub, busState.inputAddress), ProtocolUninit)
        }

        ex match {
          case ex: SessionUnusableException => sessionDead()
          case ex: UnauthorizedException => sessionDead()
          case ex: Throwable => retry()
        }
      }
      case ConfigurationSuccess(config) => {

        proxy ! ProcessingProxy.PointMapUpdated(config.points.map(p => (p.getName, p.getUuid)).toMap)
        commandActor ! CommandProxy.CommandsUpdated(config.commands)

        val configUpdaterChild = context.actorOf(configUpdaterFactory(self, endpoint, busState.session, config))

        val resultBusState = Configured(busState.session, busState.serviceOps, busState.commandSub, busState.inputAddress, configUpdaterChild)

        configurer.evaluate(endpoint, config.configs) match {
          case None =>
            logger.warn("Configuration couldn't be read for endpoint " + endpoint.getName)
            (resultBusState, ProtocolUninit)
          case Some(protocolConfig) => {

            try {
              val cmdAcceptor = protocolMgr.add(endpoint, protocolConfig, proxy ! _, proxy ! _)
              commandActor ! ProtocolInitialized(cmdAcceptor)
              (resultBusState, ProtocolPending(config.configs))
            } catch {
              case ex: Throwable =>
                logger.error(s"Error adding protocol master for endpoint ${endpoint.getName}: " + ex)
                (resultBusState, ProtocolUninit)
            }
          }
        }
      }
    }

    case state @ (busState: Configured, ProtocolUninit) => {

      case ServiceSessionDead => {
        busState.commandSub.cancel()
        busState.updater ! PoisonPill
        sessionDeadGoDown(ProtocolUninit)
      }
      case Connected(session, serviceOps) => {
        busState.updater ! PoisonPill
        busState.commandSub.cancel()
        doRegistrationAndSwitch(session, serviceOps, holdingLock = false, ProtocolUninit)
      }
      case LinkLapsed => {
        busState.updater ! PoisonPill
        busState.commandSub.cancel()
        doRegistrationAndSwitch(busState.session, busState.serviceOps, holdingLock = false, ProtocolUninit)
      }
      case ConfigFilesUpdated(configFiles) => {

        configurer.evaluate(endpoint, configFiles) match {
          case None =>
            logger.warn("Configuration couldn't be read for endpoint " + endpoint.getName)
            state
          case Some(protocolConfig) => {
            try {
              val cmdAcceptor = protocolMgr.add(endpoint, protocolConfig, proxy ! _, proxy ! _)
              commandActor ! ProtocolInitialized(cmdAcceptor)
              (busState, ProtocolPending(configFiles))
            } catch {
              case ex: Throwable =>
                logger.error(s"Error adding protocol master for endpoint ${endpoint.getName}: " + ex)
                state
            }
          }
        }
      }
      case PointsUpdated(points) => {
        proxy ! ProcessingProxy.PointMapUpdated(points.map(p => (p.getName, p.getUuid)).toMap)
        state
      }
      case CommandsUpdated(commands) => {
        commandActor ! CommandProxy.CommandsUpdated(commands)
        state
      }
    }

    case state @ (busState: Configured, protocolState: ProtocolActiveState) => {

      case ServiceSessionDead => {
        busState.commandSub.cancel()
        busState.updater ! PoisonPill
        sessionDeadGoDown(protocolState)
      }
      case Connected(session, serviceOps) => {
        busState.updater ! PoisonPill
        busState.commandSub.cancel()
        doRegistrationAndSwitch(session, serviceOps, holdingLock = protocolState.holdingLock, protocolState)
      }
      case LinkLapsed => {
        busState.updater ! PoisonPill
        busState.commandSub.cancel()
        doRegistrationAndSwitch(busState.session, busState.serviceOps, holdingLock = protocolState.holdingLock, protocolState)
      }
      case update @ StackStatusUpdated(status) => {
        handleProtocolStateUpdate(update, protocolState.configs, busState)
      }
      case ConfigFilesUpdated(configFiles) => {
        val optStateChange = handleProtocolUpConfigUpdate(protocolState.configs, configFiles)
        (busState, optStateChange.getOrElse(protocolState))
      }
      case PointsUpdated(points) => {
        proxy ! ProcessingProxy.PointMapUpdated(points.map(p => (p.getName, p.getUuid)).toMap)
        state
      }
      case CommandsUpdated(commands) => {
        commandActor ! CommandProxy.CommandsUpdated(commands)
        state
      }
    }

    case state @ (busState: ServicesDown, protocolState: ProtocolActiveState) => {

      case Connected(session, serviceOps) => {
        (doRegistration(session, serviceOps, holdingLock = protocolState.holdingLock, busState.linkPendingSince), protocolState)
      }
      case update @ StackStatusUpdated(status) => {
        handleProtocolStateUpdate(update, protocolState.configs, busState)
      }
      case DoReleaseTimeoutCheck(lossTime) => {
        if (busState.linkPendingSince == Some(lossTime)) {
          logger.info(s"Endpoint ${endpoint.getName} releasing protocol because release timeout expired")
          commandActor ! CommandProxy.ProtocolUninitialized
          protocolMgr.remove(endpoint.getUuid)
          (busState, ProtocolUninit)
        } else {
          state
        }
      }
    }

    case state @ (busState: ServicesUp, protocolState: ProtocolActiveState) => {

      case ServiceSessionDead => {
        manager ! ServiceSessionDead
        (ServicesDown(busState.linkPendingSince), protocolState)
      }
      case Connected(session, serviceOps) => {
        (doRegistration(session, serviceOps, holdingLock = protocolState.holdingLock, busState.linkPendingSince), protocolState)
      }
      case DoRegistrationRetry => {
        (doRegistration(busState.session, busState.serviceOps, holdingLock = protocolState.holdingLock, busState.linkPendingSince), protocolState)
      }
      case update @ StackStatusUpdated(status) => {
        handleProtocolStateUpdate(update, protocolState.configs, busState)
      }
      case DoReleaseTimeoutCheck(lossTime) => {
        if (busState.linkPendingSince == Some(lossTime)) {
          logger.info(s"Endpoint ${endpoint.getName} releasing protocol because release timeout expired")
          commandActor ! CommandProxy.ProtocolUninitialized
          protocolMgr.remove(endpoint.getUuid)
          (busState, ProtocolUninit)
        } else {
          state
        }
      }
    }

    case state @ (busState: RegistrationPending, protocolState: ProtocolActiveState) => {

      case ServiceSessionDead => {
        manager ! ServiceSessionDead
        (ServicesDown(busState.linkPendingSince), protocolState)
      }
      case Connected(session, serviceOps) => {
        (doRegistration(session, serviceOps, holdingLock = protocolState.holdingLock, busState.linkPendingSince), protocolState)
      }
      case update @ StackStatusUpdated(status) => {
        handleProtocolStateUpdate(update, protocolState.configs, busState)
      }
      case RegistrationFailure(ex) => {

        def sessionDead(): StateType = {
          manager ! ServiceSessionDead
          busState.commandSub.cancel()
          (ServicesDown(busState.linkPendingSince), protocolState)
        }

        def retry(): StateType = {
          scheduleMsg(registrationRetryMs, DoRegistrationRetry)
          busState.commandSub.cancel()
          (ServicesUp(busState.session, busState.serviceOps, busState.linkPendingSince), protocolState)
        }

        ex match {
          case ex: SessionUnusableException => sessionDead()
          case ex: UnauthorizedException => sessionDead()
          case ex: LockedException =>
            logger.info(s"Endpoint ${endpoint.getName} releasing protocol because registration returned locked")
            busState.commandSub.cancel()
            commandActor ! CommandProxy.ProtocolUninitialized
            protocolMgr.remove(endpoint.getUuid)
            scheduleMsg(registrationRetryMs, DoRegistrationRetry)
            (ServicesUp(busState.session, busState.serviceOps, busState.linkPendingSince), ProtocolUninit)
          case ex: Throwable => retry()
        }
      }
      case RegistrationSuccess(registration) => {

        proxy ! ProcessingProxy.LinkUp(busState.session, registration.getInputAddress)

        commandActor ! CommandProxy.CommandSubscriptionRetrieved(busState.commandSub)

        (doConfig(busState.session, busState.serviceOps, busState.commandSub, registration.getInputAddress), protocolState)
      }
      case DoReleaseTimeoutCheck(lossTime) => {
        if (busState.linkPendingSince == Some(lossTime)) {
          logger.info(s"Endpoint ${endpoint.getName} releasing protocol because release timeout expired")
          commandActor ! CommandProxy.ProtocolUninitialized
          protocolMgr.remove(endpoint.getUuid)
          (busState, ProtocolUninit)
        } else {
          state
        }
      }
    }

    case state @ (busState: Registered, protocolState: ProtocolActiveState) => {

      case ServiceSessionDead => {
        busState.commandSub.cancel()
        sessionDeadGoDown(protocolState)
      }
      case Connected(session, serviceOps) => {
        busState.commandSub.cancel()
        doRegistrationAndSwitch(session, serviceOps, holdingLock = protocolState.holdingLock, protocolState)
      }
      case LinkLapsed => {
        busState.commandSub.cancel()
        doRegistrationAndSwitch(busState.session, busState.serviceOps, holdingLock = protocolState.holdingLock, protocolState)
      }
      case update @ StackStatusUpdated(status) => {
        handleProtocolStateUpdate(update, protocolState.configs, busState)
      }
      case DoConfigRetry => {
        (doConfig(busState.session, busState.serviceOps, busState.commandSub, busState.inputAddress), protocolState)
      }
    }

    case state @ (busState: ConfigPending, protocolState: ProtocolActiveState) => {

      case ServiceSessionDead => {
        busState.commandSub.cancel()
        sessionDeadGoDown(protocolState)
      }
      case Connected(session, serviceOps) => {
        busState.commandSub.cancel()
        doRegistrationAndSwitch(session, serviceOps, holdingLock = protocolState.holdingLock, protocolState)
      }
      case LinkLapsed => {
        busState.commandSub.cancel()
        doRegistrationAndSwitch(busState.session, busState.serviceOps, holdingLock = protocolState.holdingLock, protocolState)
      }
      case update @ StackStatusUpdated(status) => {
        handleProtocolStateUpdate(update, protocolState.configs, busState)
      }
      case DoConfigRetry => {
        (doConfig(busState.session, busState.serviceOps, busState.commandSub, busState.inputAddress), protocolState)
      }
      case ConfigurationFailure(ex) => {
        logger.debug(s"Endpoint ${endpoint.getName} saw configuration failure: $ex")

        def sessionDead(): StateType = {
          busState.commandSub.cancel()
          sessionDeadGoDown(protocolState)
        }

        def retry(): StateType = {
          scheduleMsg(registrationRetryMs, DoConfigRetry)
          (Registered(busState.session, busState.serviceOps, busState.commandSub, busState.inputAddress), protocolState)
        }

        ex match {
          case ex: SessionUnusableException => sessionDead()
          case ex: UnauthorizedException => sessionDead()
          case ex: Throwable => retry()
        }
      }
      case ConfigurationSuccess(config) => {
        logger.debug(s"Endpoint ${endpoint.getName} reconfigured while protocol running")
        val configUpdaterChild = context.actorOf(configUpdaterFactory(self, endpoint, busState.session, config))
        proxy ! ProcessingProxy.PointMapUpdated(config.points.map(p => (p.getName, p.getUuid)).toMap)
        commandActor ! CommandProxy.CommandsUpdated(config.commands)

        val resultBusState = Configured(busState.session, busState.serviceOps, busState.commandSub, busState.inputAddress, configUpdaterChild)
        val optStateChange = handleProtocolUpConfigUpdate(protocolState.configs, config.configs)
        (resultBusState, optStateChange.getOrElse(protocolState))
      }
    }
  }

  override protected def onShutdown(state: (BusState, ProtocolState)): Unit = {
    logger.debug("Endpoint " + endpoint.getName + " saw shutdown in state " + NestedStateMachine.stateToString(state))
    val (busState, protocolState) = state
    protocolState match {
      case _: ProtocolActiveState =>
        logger.info("Endpoint " + endpoint.getName + " is removing protocol due to shutdown")
        protocolMgr.remove(endpoint.getUuid)
      case _ =>
    }
    busState match {
      case s: BindingHoldingBusState => s.cleanup()
      case _ =>
    }
  }

  private def sessionDeadGoDown(protocolState: ProtocolState) = {
    val now = System.currentTimeMillis()
    scheduleMsg(releaseTimeoutMs, DoReleaseTimeoutCheck(now))
    manager ! ServiceSessionDead
    (ServicesDown(Some(now)), protocolState)
  }

  private def doRegistrationAndSwitch(session: Session, serviceOps: AmqpServiceOperations, holdingLock: Boolean, protocolState: ProtocolState) = {
    val now = System.currentTimeMillis()
    scheduleMsg(releaseTimeoutMs, DoReleaseTimeoutCheck(now))
    (doRegistration(session, serviceOps, holdingLock = holdingLock, Some(System.currentTimeMillis())), protocolState)
  }

  override protected def unhandled(obj: Any, state: (BusState, ProtocolState)): Unit = {
    obj match {
      case r: Retry =>
      case x => logger.warn("Saw unhandled event " + obj.getClass.getSimpleName + " in state " + NestedStateMachine.stateToString(state))
    }
  }

  private def handleProtocolStateUpdate(update: StackStatusUpdated, configs: Seq[EntityKeyValue], busState: BusState) = {
    proxy ! update
    import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus.Status._
    update.state match {
      case COMMS_UP => (busState, ProtocolUp(configs))
      case COMMS_DOWN => (busState, ProtocolDown(configs))
      case UNKNOWN => (busState, ProtocolDown(configs))
      case ERROR => (busState, ProtocolError(configs))
    }
  }

  private def handleProtocolUpConfigUpdate(oldConfigs: Seq[EntityKeyValue], latestConfigs: Seq[EntityKeyValue]): Option[ProtocolState] = {
    if (!configsEqual(oldConfigs, latestConfigs)) {
      configurer.evaluate(endpoint, latestConfigs) match {
        case None =>
          logger.info("Updated configuration couldn't be used " + endpoint.getName + " is removing protocol")
          protocolMgr.remove(endpoint.getUuid)
          commandActor ! CommandProxy.ProtocolUninitialized
          proxy ! StackStatusUpdated(FrontEndConnectionStatus.Status.COMMS_DOWN)
          Some(ProtocolUninit)
        case Some(protocolConfig) => {
          logger.info("Updated configuration, " + endpoint.getName + " is re-initializing protocol")
          try {
            commandActor ! CommandProxy.ProtocolUninitialized
            protocolMgr.remove(endpoint.getUuid)
            val cmdAcceptor = protocolMgr.add(endpoint, protocolConfig, proxy ! _, proxy ! _)
            commandActor ! CommandProxy.ProtocolInitialized(cmdAcceptor)
            Some(ProtocolPending(latestConfigs))
          } catch {
            case ex: Throwable =>
              logger.error(s"Error adding protocol master for endpoint ${endpoint.getName}: " + ex)
              Some(ProtocolUninit)
          }
        }
      }
    } else {
      logger.debug(s"Endpoint ${endpoint.getName} saw byte-equivalent config update")
      None
    }
  }

  private def doConfig(session: Session, serviceOps: AmqpServiceOperations, commandSub: ServiceHandlerSubscription, inputAddress: String): ConfigPending = {
    val configFut = configureFunc(session, endpoint)
    configFut.onSuccess { case resp => self ! ConfigurationSuccess(resp) }
    configFut.onFailure { case ex => self ! ConfigurationFailure(ex) }
    ConfigPending(session, serviceOps, commandSub, inputAddress, configFut)
  }

  private def doRegistration(session: Session, serviceOps: AmqpServiceOperations, holdingLock: Boolean, linkPendingSince: Option[Long]): RegistrationPending = {
    val (fut, sub) = registerFunc(session, serviceOps, endpoint, holdingLock)

    fut.onSuccess { case conn => self ! RegistrationSuccess(conn) }
    fut.onFailure { case ex => self ! RegistrationFailure(ex) }

    RegistrationPending(session, serviceOps, sub, linkPendingSince)
  }
}
