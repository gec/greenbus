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

import java.util.UUID

import akka.actor._
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.amqp.{ AmqpMessage, AmqpAddressedMessage, AmqpServiceOperations }
import io.greenbus.msg.{ Session, SessionUnusableException, SubscriptionBinding }
import io.greenbus.app.actor.MessageScheduling
import io.greenbus.client.exception.UnauthorizedException
import io.greenbus.client.service.FrontEndService
import io.greenbus.client.service.proto.EventRequests.EventTemplate
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.client.service.proto.Measurements.Quality.Validity
import io.greenbus.client.service.proto.Measurements._
import io.greenbus.client.service.proto.Model.{ Point, Endpoint, ModelUUID }
import io.greenbus.client.service.proto.Processing.{ TriggerSet, MeasOverride }
import io.greenbus.measproc.EventPublisher.EventTemplateGroup
import io.greenbus.measproc.lock.ProcessingLockService
import io.greenbus.mstore.MeasurementValueStore
import io.greenbus.services.authz.AuthLookup
import io.greenbus.services.framework.ModelNotifier
import io.greenbus.services.model.{ FrontEndModel, UUIDHelpers }
import io.greenbus.sql.DbConnection

import scala.collection.JavaConversions._

import scala.concurrent.duration._

object EndpointStreamManager extends LazyLogging {

  def mergeableQualityElements(meas: Measurement): (Quality.Builder, DetailQual.Builder) = {
    if (meas.hasQuality) {
      val detailBuilder = if (meas.getQuality.hasDetailQual) {
        meas.getQuality.getDetailQual.toBuilder
      } else {
        DetailQual.newBuilder()
      }

      (meas.getQuality.toBuilder, detailBuilder)
    } else {
      (Quality.newBuilder(), DetailQual.newBuilder())
    }
  }

  sealed trait ConfigUpdate
  case class PointAdded(point: (ModelUUID, String), measOverride: Option[MeasOverride], triggerSet: Option[TriggerSet]) extends ConfigUpdate
  case class PointRemoved(point: ModelUUID) extends ConfigUpdate
  case class PossiblePointNotification(uuid: ModelUUID) extends ConfigUpdate
  case class PointMiss(uuid: ModelUUID) extends ConfigUpdate
  case class PointModified(point: Point) extends ConfigUpdate

  case class OverridePut(v: MeasOverride) extends ConfigUpdate
  case class OverrideDeleted(v: MeasOverride) extends ConfigUpdate
  case class TriggerSetPut(uuid: ModelUUID, v: TriggerSet) extends ConfigUpdate
  case class TriggerSetDeleted(uuid: ModelUUID) extends ConfigUpdate

  case object HeartbeatCheck
  case object MarkRetry
  case class RegistrationRequest(msg: Array[Byte], responseHandler: (Array[Byte]) => Unit)
  case class MeasBatchRequest(msg: Array[Byte], responseHandler: (Array[Byte]) => Unit, bindingSequence: Long)
  case class HeartbeatRequest(msg: Array[Byte], responseHandler: (Array[Byte]) => Unit, bindingSequence: Long)

  case class StreamRegistered(endpointId: ModelUUID, endpointName: String, address: String)

  case object DoStartup
  case object DoShutdown
  case object CheckLock

  sealed trait State
  case object Down extends State
  case object Standby extends State
  case object Available extends State
  case object LinkUp extends State
  case object LinkHalfDown extends State
  case object LinkHalfDownMarkFailed extends State

  sealed trait Data
  case object NoData extends Data
  case class StandbyData(processing: ProcessorComponents, config: ConfigComponents) extends Data
  case class AvailableData(regServiceBinding: SubscriptionBinding, processing: ProcessorComponents, config: ConfigComponents) extends Data
  case class LinkUpData(lastHeartbeat: Long, nodeId: Option[String], binding: ServiceBindingComponents, availData: AvailableData) extends Data
  case class LinkHalfDownData(previousValues: Seq[(UUID, Measurement)], nodeId: Option[String], binding: ServiceBindingComponents, availData: AvailableData) extends Data

  case class ServiceBindingComponents(measBinding: SubscriptionBinding, heartbeatBinding: SubscriptionBinding, bindingSeq: Long)
  case class ConfigComponents(subscriptions: Seq[SubscriptionBinding], pointSet: Seq[(ModelUUID, String)], outstandingPoints: Set[ModelUUID])
  case class ProcessorComponents(processor: EndpointProcessor, eventPublisher: ActorRef)

  case class StreamResources(session: Session, serviceOps: AmqpServiceOperations, store: MeasurementValueStore)

  def props(endpoint: Endpoint,
    resources: StreamResources,
    config: EndpointConfiguration,
    sql: DbConnection,
    auth: AuthLookup,
    frontEndModel: FrontEndModel,
    statusNotifier: ModelNotifier,
    standbyLock: ProcessingLockService,
    nodeId: String,
    heartbeatTimeout: Long,
    markRetryPeriodMs: Long,
    standbyLockRetryPeriodMs: Long,
    standbyLockExpiryDurationMs: Long): Props = {

    Props(
      classOf[EndpointStreamManager],
      endpoint,
      resources,
      config,
      sql,
      auth,
      frontEndModel,
      statusNotifier,
      standbyLock,
      nodeId: String,
      heartbeatTimeout,
      markRetryPeriodMs,
      standbyLockRetryPeriodMs,
      standbyLockExpiryDurationMs)
  }

  def measNotificationBatch(serviceOps: AmqpServiceOperations, endpoint: Endpoint, meases: Seq[(ModelUUID, String, Measurement)]) {
    if (meases.nonEmpty) {
      val exchange = classOf[MeasurementNotification].getSimpleName

      val measNotifications = meases.map {
        case (uuid, name, m) =>
          val mn = MeasurementNotification.newBuilder()
            .setPointUuid(uuid)
            .setPointName(name)
            .setValue(m)
            .build()
          (uuid, name, mn)
      }

      val messages = measNotifications.map {
        case (uuid, name, mn) =>
          AmqpAddressedMessage(exchange, uuid.getValue + "." + name.replace('.', '_'), AmqpMessage(mn.toByteArray, None, None))
      }

      serviceOps.publishBatch(messages)

      val batchNotification = MeasurementBatchNotification.newBuilder()
        .setEndpointUuid(endpoint.getUuid)
        .addAllValues(measNotifications.map(_._3))
        .build()

      serviceOps.publishEvent(classOf[MeasurementBatchNotification].getSimpleName, batchNotification.toByteArray, endpoint.getUuid.getValue)
    }
  }
}

import io.greenbus.measproc.EndpointStreamManager._
class EndpointStreamManager(
  endpoint: Endpoint,
  resources: StreamResources,
  config: EndpointConfiguration,
  sql: DbConnection,
  auth: AuthLookup,
  frontEndModel: FrontEndModel,
  statusNotifier: ModelNotifier,
  standbyLock: ProcessingLockService,
  nodeId: String,
  heartbeatTimeout: Long,
  markRetryPeriodMs: Long,
  standbyLockRetryPeriodMs: Long,
  standbyLockExpiryDurationMs: Long)
    extends Actor with FSM[State, Data] with MessageScheduling with LazyLogging {

  import context.dispatcher

  override def supervisorStrategy: SupervisorStrategy = {
    import akka.actor.SupervisorStrategy._
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 30.seconds) {
      case _: UnauthorizedException => Escalate
      case _: SessionUnusableException => Escalate
      case _: Throwable => Escalate
    }
  }

  startWith(Down, NoData)
  self ! DoStartup

  when(Down) {
    case Event(DoStartup, NoData) => {
      logger.debug(s"Starting processor for ${endpoint.getName}")
      val (processor, eventPublisher) = setupProcessor(config)

      self ! CheckLock
      goto(Standby) using StandbyData(
        processing = ProcessorComponents(processor, eventPublisher),
        config = ConfigComponents(Seq(config.overrideSubscription, config.triggerSetSubscription), config.points, Set.empty[ModelUUID]))
    }
  }

  when(Standby) {
    case Event(CheckLock, data: StandbyData) => {
      try {
        val haveLock = standbyLock.acquireOrHold(UUIDHelpers.protoUUIDToUuid(endpoint.getUuid), nodeId, standbyLockExpiryDurationMs)

        if (haveLock) {
          val registrationBinding = setupRegistrationService()

          markEndpointOffline(pointSetUuidConversion(data.config.pointSet), data.processing.processor)

          scheduleMsg(standbyLockRetryPeriodMs, CheckLock)
          goto(Available) using AvailableData(
            regServiceBinding = registrationBinding,
            processing = data.processing,
            config = data.config)
        } else {
          scheduleMsg(standbyLockRetryPeriodMs, CheckLock)
          stay using data
        }
      } catch {
        case ex: Throwable =>
          logger.error(s"Endpoint ${endpoint.getName} had error acquiring lock: " + ex)
          scheduleMsg(standbyLockRetryPeriodMs, CheckLock)
          stay using data
      }
    }
    case Event(configUpdate: ConfigUpdate, data: AvailableData) => {
      handleConfigUpdate(configUpdate, data.config, data.processing.processor)
      stay using data
    }
  }

  when(Available) {
    case Event(configUpdate: ConfigUpdate, data: AvailableData) => {
      handleConfigUpdate(configUpdate, data.config, data.processing.processor)
      stay using data
    }
    case Event(RegistrationRequest(msgBytes, respFunc), data: AvailableData) => {

      val nextBindingSeq = 0
      handleRegistrationAttemptWhileDown(msgBytes, respFunc, nextBindingSeq) match {
        case None =>
          logger.debug(s"Registration failed on endpoint ${endpoint.getName}")
          stay using data
        case Some(result) =>
          logger.debug(s"Registration succeeded on endpoint ${endpoint.getName}")

          markEndpointOnline() // TODO: handle failure here
          scheduleHeartbeatCheck()
          goto(LinkUp) using LinkUpData(
            System.currentTimeMillis(),
            nodeId = result.nodeId,
            binding = ServiceBindingComponents(result.measServiceBinding, result.heartbeatServiceBinding, nextBindingSeq),
            availData = data)
      }

    }
    case Event(HeartbeatCheck, data) => {
      logger.warn("Got heartbeat check in available state")
      stay using data
    }
    case Event(CheckLock, data: AvailableData) => {
      val haveLock = standbyLock.acquireOrHold(UUIDHelpers.protoUUIDToUuid(endpoint.getUuid), nodeId, standbyLockExpiryDurationMs)
      if (haveLock) {
        scheduleMsg(standbyLockRetryPeriodMs, CheckLock)
        stay using data
      } else {
        logger.info(s"Endpoint ${endpoint.getName} lost lock while available; going to standby")
        scheduleMsg(standbyLockRetryPeriodMs, CheckLock)
        data.regServiceBinding.cancel()
        goto(Standby) using StandbyData(data.processing, data.config)
      }
    }
  }

  when(LinkUp) {
    case Event(configUpdate: ConfigUpdate, data: LinkUpData) => {
      handleConfigUpdate(configUpdate, data.availData.config, data.availData.processing.processor)
      stay using data
    }
    case Event(RegistrationRequest(msgBytes, respFunc), data: LinkUpData) => {
      val nextSeq = data.binding.bindingSeq + 1
      handleRegistrationAttemptWhileUp(msgBytes, respFunc, nextSeq, data.nodeId) match {
        case None =>
          logger.debug(s"Registration failed on endpoint ${endpoint.getName}")
          stay using data
        case Some(result) =>
          logger.debug(s"Registration succeeded on endpoint ${endpoint.getName}")

          data.binding.measBinding.cancel()
          data.binding.heartbeatBinding.cancel()

          val bind = ServiceBindingComponents(result.measServiceBinding, result.heartbeatServiceBinding, nextSeq)

          scheduleHeartbeatCheck()
          stay using LinkUpData(System.currentTimeMillis(), nodeId = result.nodeId, binding = bind, availData = data.availData)
      }
    }
    case Event(MeasBatchRequest(msgBytes, respFunc, bindSeq), data: LinkUpData) => {
      if (bindSeq == data.binding.bindingSeq) {
        data.availData.processing.processor.handle(msgBytes, respFunc)

        scheduleHeartbeatCheck()
        stay using data.copy(lastHeartbeat = System.currentTimeMillis())
      } else {
        stay using data
      }
    }
    case Event(HeartbeatRequest(msgBytes, respFunc, bindSeq), data: LinkUpData) => {
      if (bindSeq == data.binding.bindingSeq) {
        handleHeartbeat(msgBytes, respFunc)
        scheduleHeartbeatCheck()
        stay using data.copy(lastHeartbeat = System.currentTimeMillis())
      } else {
        stay using data
      }
    }
    case Event(HeartbeatCheck, data: LinkUpData) => {
      logger.trace(s"Endpoint ${endpoint.getName} checking heartbeat in LinkUp state")
      val now = System.currentTimeMillis()
      val elapsed = now - data.lastHeartbeat // TODO: sanity checks on time warping?

      if (elapsed >= heartbeatTimeout) {
        try {
          val prevValues: Seq[(UUID, Measurement)] = markEndpointOffline(pointSetUuidConversion(data.availData.config.pointSet), data.availData.processing.processor)

          logger.info(s"Endpoint ${endpoint.getName} accepting failover registrations")
          goto(LinkHalfDown) using LinkHalfDownData(previousValues = prevValues, nodeId = data.nodeId, data.binding, data.availData)

        } catch {
          case ex: Throwable =>
            logger.error(s"Could not mark endpoint ${endpoint.getName} as down: " + ex)
            scheduleMsg(markRetryPeriodMs, MarkRetry)

            logger.info(s"Endpoint ${endpoint.getName} accepting failover registrations, but marking down failed")
            goto(LinkHalfDownMarkFailed) using data
        }
      } else {
        // this is likely an old check; an update came in since
        stay using data
      }
    }
    case Event(CheckLock, data: LinkUpData) => {
      val haveLock = standbyLock.acquireOrHold(UUIDHelpers.protoUUIDToUuid(endpoint.getUuid), nodeId, standbyLockExpiryDurationMs)
      if (haveLock) {
        scheduleMsg(standbyLockRetryPeriodMs, CheckLock)
        stay using data
      } else {
        logger.info(s"Endpoint ${endpoint.getName} lost lock while link up; going to standby")
        data.binding.measBinding.cancel()
        data.binding.heartbeatBinding.cancel()
        data.availData.regServiceBinding.cancel()

        scheduleMsg(standbyLockRetryPeriodMs, CheckLock)
        goto(Standby) using StandbyData(data.availData.processing, data.availData.config)
      }
    }
  }

  when(LinkHalfDownMarkFailed) {
    case Event(configUpdate: ConfigUpdate, data: LinkUpData) => {
      handleConfigUpdate(configUpdate, data.availData.config, data.availData.processing.processor)
      stay using data
    }
    case Event(MarkRetry, data: LinkUpData) => {
      try {
        val prevValues: Seq[(UUID, Measurement)] = markEndpointOffline(pointSetUuidConversion(data.availData.config.pointSet), data.availData.processing.processor)

        goto(LinkHalfDown) using LinkHalfDownData(previousValues = prevValues, nodeId = data.nodeId, data.binding, data.availData)

      } catch {
        case ex: Throwable =>
          logger.error(s"Could not mark endpoint ${endpoint.getName} as down: " + ex)
          scheduleMsg(markRetryPeriodMs, MarkRetry)
          stay using data
      }
    }
    case Event(RegistrationRequest(msgBytes, respFunc), data: LinkUpData) => {

      val nextSeq = data.binding.bindingSeq + 1
      handleRegistrationAttemptWhileDown(msgBytes, respFunc, nextSeq) match {
        case None =>
          logger.debug(s"Registration failed on endpoint ${endpoint.getName}")
          stay using data
        case Some(result) =>
          logger.debug(s"Registration succeeded on endpoint ${endpoint.getName}")

          data.binding.measBinding.cancel()
          data.binding.heartbeatBinding.cancel()

          val bind = ServiceBindingComponents(result.measServiceBinding, result.heartbeatServiceBinding, nextSeq)

          scheduleHeartbeatCheck()
          goto(LinkUp) using LinkUpData(System.currentTimeMillis(), nodeId = data.nodeId, binding = bind, availData = data.availData)
      }
    }
    case Event(HeartbeatRequest(msgBytes, respFunc, bindSeq), data: LinkUpData) => {
      if (bindSeq == data.binding.bindingSeq) {
        markEndpointOnline()
        handleHeartbeat(msgBytes, respFunc)

        scheduleHeartbeatCheck()
        stay using data.copy(lastHeartbeat = System.currentTimeMillis())
      } else {
        stay using data
      }
    }
    case Event(MeasBatchRequest(msgBytes, respFunc, bindSeq), data: LinkUpData) => {
      if (bindSeq == data.binding.bindingSeq) {
        logger.debug(s"Endpoint ${endpoint.getName} was half down and came back up")

        markEndpointOnline()
        data.availData.processing.processor.handle(msgBytes, respFunc)

        scheduleHeartbeatCheck()
        goto(LinkUp) using data.copy(lastHeartbeat = System.currentTimeMillis())
      } else {
        stay using data
      }
    }
    case Event(HeartbeatCheck, data: LinkUpData) => {
      stay using data
    }
    case Event(CheckLock, data: LinkUpData) => {
      val haveLock = standbyLock.acquireOrHold(UUIDHelpers.protoUUIDToUuid(endpoint.getUuid), nodeId, standbyLockExpiryDurationMs)
      if (haveLock) {
        scheduleMsg(standbyLockRetryPeriodMs, CheckLock)
        stay using data
      } else {
        logger.info(s"Endpoint ${endpoint.getName} lost lock while link down; going to standby")
        data.binding.measBinding.cancel()
        data.binding.heartbeatBinding.cancel()
        data.availData.regServiceBinding.cancel()

        scheduleMsg(standbyLockRetryPeriodMs, CheckLock)
        goto(Standby) using StandbyData(data.availData.processing, data.availData.config)
      }
    }
  }

  when(LinkHalfDown) {
    case Event(configUpdate: ConfigUpdate, data: LinkHalfDownData) => {
      handleConfigUpdate(configUpdate, data.availData.config, data.availData.processing.processor)
      stay using data
    }
    case Event(RegistrationRequest(msgBytes, respFunc), data: LinkHalfDownData) => {

      val nextSeq = data.binding.bindingSeq + 1
      handleRegistrationAttemptWhileDown(msgBytes, respFunc, nextSeq) match {
        case None =>
          logger.debug(s"Registration failed on endpoint ${endpoint.getName}")
          stay using data
        case Some(result) =>
          logger.debug(s"Registration succeeded on endpoint ${endpoint.getName}")

          data.binding.measBinding.cancel()
          data.binding.heartbeatBinding.cancel()

          val bind = ServiceBindingComponents(result.measServiceBinding, result.heartbeatServiceBinding, nextSeq)

          scheduleHeartbeatCheck()
          goto(LinkUp) using LinkUpData(System.currentTimeMillis(), nodeId = result.nodeId, binding = bind, availData = data.availData)
      }
    }
    case Event(HeartbeatRequest(msgBytes, respFunc, bindSeq), data: LinkHalfDownData) => {
      if (bindSeq == data.binding.bindingSeq) {
        logger.debug(s"Endpoint ${endpoint.getName} was half down and came back up")
        val uuidSet = data.availData.config.pointSet.map(_._1).map(UUIDHelpers.protoUUIDToUuid).toSet
        val stillRelevantSet = data.previousValues.filter(kv => uuidSet.contains(kv._1))

        markEndpointOnlineAndRestoreMeasurements(stillRelevantSet, pointSetUuidConversion(data.availData.config.pointSet), data.availData.processing.processor)

        handleHeartbeat(msgBytes, respFunc)

        scheduleHeartbeatCheck()
        goto(LinkUp) using LinkUpData(lastHeartbeat = System.currentTimeMillis(), nodeId = data.nodeId, binding = data.binding, availData = data.availData)
      } else {
        stay using data
      }
    }
    case Event(MeasBatchRequest(msgBytes, respFunc, bindSeq), data: LinkHalfDownData) => {
      if (bindSeq == data.binding.bindingSeq) {

        logger.debug(s"Endpoint ${endpoint.getName} was half down and came back up")
        val uuidSet = data.availData.config.pointSet.map(_._1).map(UUIDHelpers.protoUUIDToUuid).toSet
        val stillRelevantSet = data.previousValues.filter(kv => uuidSet.contains(kv._1))

        markEndpointOnlineAndRestoreMeasurements(stillRelevantSet, pointSetUuidConversion(data.availData.config.pointSet), data.availData.processing.processor)
        data.availData.processing.processor.handle(msgBytes, respFunc)

        scheduleHeartbeatCheck()
        goto(LinkUp) using LinkUpData(lastHeartbeat = System.currentTimeMillis(), nodeId = data.nodeId, binding = data.binding, availData = data.availData)
      } else {
        stay using data
      }
    }
    case Event(HeartbeatCheck, data: LinkHalfDownData) => {
      stay using data
    }
    case Event(MarkRetry, data: LinkHalfDownData) => {
      stay using data
    }
    case Event(CheckLock, data: LinkHalfDownData) => {
      val haveLock = standbyLock.acquireOrHold(UUIDHelpers.protoUUIDToUuid(endpoint.getUuid), nodeId, standbyLockExpiryDurationMs)
      if (haveLock) {
        scheduleMsg(standbyLockRetryPeriodMs, CheckLock)
        stay using data
      } else {
        logger.info(s"Endpoint ${endpoint.getName} lost lock while link half down; going to standby")
        data.binding.measBinding.cancel()
        data.binding.heartbeatBinding.cancel()
        data.availData.regServiceBinding.cancel()

        scheduleMsg(standbyLockRetryPeriodMs, CheckLock)
        goto(Standby) using StandbyData(data.availData.processing, data.availData.config)
      }
    }
  }

  private def commonCleanup(data: AvailableData): Unit = {
    data.processing.processor.unregister()
    data.regServiceBinding.cancel()
    config.edgeSubscription.cancel()
    config.pointSubscription.cancel()
    config.overrideSubscription.cancel()
    config.triggerSetSubscription.cancel()
  }

  // TODO: dry this up further
  onTermination {
    case StopEvent(_, Available, data: AvailableData) =>
      logger.info(s"Processor for endpoint '${endpoint.getName}' terminating, canceling subscriptions")
      commonCleanup(data)
    case StopEvent(_, LinkUp, data: LinkUpData) =>
      logger.info(s"Processor for endpoint '${endpoint.getName}' terminating, canceling subscriptions")
      data.binding.measBinding.cancel()
      data.binding.heartbeatBinding.cancel()
      commonCleanup(data.availData)
    case StopEvent(_, LinkHalfDown, data: LinkHalfDownData) =>
      logger.info(s"Processor for endpoint '${endpoint.getName}' terminating, canceling subscriptions")
      data.binding.measBinding.cancel()
      data.binding.heartbeatBinding.cancel()
      commonCleanup(data.availData)
    case StopEvent(_, LinkHalfDownMarkFailed, data: LinkUpData) =>
      logger.info(s"Processor for endpoint '${endpoint.getName}' terminating, canceling subscriptions")
      data.binding.measBinding.cancel()
      data.binding.heartbeatBinding.cancel()
      commonCleanup(data.availData)
  }

  initialize()

  private def handleConfigUpdate(update: ConfigUpdate, current: ConfigComponents, processor: EndpointProcessor): ConfigComponents = {
    logger.trace(s"Endpoint ${endpoint.getName} got config update: " + update)
    update match {
      case PossiblePointNotification(pointUuid) => {
        ServiceConfiguration.configForPointAdded(resources.session, pointUuid).map {
          case None =>
            self ! PointMiss(pointUuid)
          case Some((pt, overOpt, triggerOpt)) =>
            val tup = (pt.getUuid, pt.getName)
            self ! PointAdded(tup, overOpt, triggerOpt)
        }
        current.copy(outstandingPoints = current.outstandingPoints + pointUuid)
      }
      case PointMiss(pointUuid) => {
        current.copy(outstandingPoints = current.outstandingPoints - pointUuid)
      }
      case PointAdded(point, over, trigger) =>
        processor.pointAdded(point, over, trigger)
        current.copy(outstandingPoints = current.outstandingPoints - point._1, pointSet = current.pointSet :+ point)
      case PointRemoved(pointUuid) =>
        processor.pointRemoved(pointUuid)
        current.copy(outstandingPoints = current.outstandingPoints - pointUuid)
      case PointModified(point) =>
        processor.pointModified(point)
        current
      case OverridePut(v) =>
        processor.overridePut(v)
        current
      case OverrideDeleted(v) =>
        processor.overrideDeleted(v)
        current
      case TriggerSetPut(uuid, v) =>
        processor.triggerSetPut(uuid, v)
        current
      case TriggerSetDeleted(uuid) =>
        processor.triggerSetDeleted(uuid)
        current
    }
  }

  private def scheduleHeartbeatCheck(): Unit = {
    scheduleMsg(heartbeatTimeout, HeartbeatCheck)
  }

  private def pointSetUuidConversion(points: Seq[(ModelUUID, String)]): Seq[(UUID, String)] = {
    points.map { case (uuid, name) => (UUIDHelpers.protoUUIDToUuid(uuid), name) }
  }

  private def setupProcessor(config: EndpointConfiguration): (EndpointProcessor, ActorRef) = {
    val publisher = context.actorOf(EventPublisher.props(resources.session))

    def publishEvents(events: Seq[EventTemplate.Builder]) { if (events.nonEmpty) publisher ! EventTemplateGroup(events) }

    val processor = new EndpointProcessor(
      endpoint.getName,
      config.points,
      config.overrides,
      config.triggerSets,
      resources.store,
      measNotificationBatch(resources.serviceOps, endpoint, _),
      publishEvents)

    processor.register()

    import io.greenbus.client.proto.Envelope.SubscriptionEventType._

    config.pointSubscription.start { note =>
      note.getEventType match {
        case MODIFIED => self ! PointModified(note.getValue)
        case _ => // let add/removes be picked up by edge sub
      }
    }

    config.overrideSubscription.start { note =>
      note.getEventType match {
        case ADDED | MODIFIED => self ! OverridePut(note.getValue)
        case REMOVED => self ! OverrideDeleted(note.getValue)
      }
    }

    config.triggerSetSubscription.start { note =>
      val triggerSetOpt = ServiceConfiguration.keyValueToTriggerSet(note.getValue)
      triggerSetOpt.foreach { triggerSet =>
        note.getEventType match {
          case ADDED | MODIFIED => self ! TriggerSetPut(note.getValue.getUuid, triggerSet)
          case REMOVED => self ! TriggerSetDeleted(note.getValue.getUuid)
        }
      }
    }

    config.edgeSubscription.start { note =>
      note.getEventType match {
        case ADDED | MODIFIED => self ! PossiblePointNotification(note.getValue.getChild)
        case REMOVED => self ! PointRemoved(note.getValue.getChild)
      }
    }

    (processor, publisher)
  }

  private def setupRegistrationService(): SubscriptionBinding = {

    logger.debug(s"Subscribing to service queue for ${endpoint.getName}")
    val binding = resources.serviceOps.bindRoutedService(new ActorForwardingServiceHandler(self, RegistrationRequest))

    logger.debug(s"Binding service queue to exchange for ${endpoint.getName}")
    resources.serviceOps.bindQueue(binding.getId(), FrontEndService.Descriptors.PutFrontEndRegistration.requestId, endpoint.getUuid.getValue)

    binding
  }

  private def handleRegistrationAttemptWhileDown(msg: Array[Byte], responseHandler: (Array[Byte]) => Unit, sequence: Long): Option[RegistrationResult] = {

    val measService = new ActorForwardingServiceHandler(self, MeasBatchRequest(_, _, sequence))
    val heartbeatService = new ActorForwardingServiceHandler(self, HeartbeatRequest(_, _, sequence))
    val resultHolder = new RegistrationResultHolder

    val handler = RegistrationServiceHandler.wrapped(
      endpoint,
      sql,
      auth,
      resources.serviceOps,
      frontEndModel,
      measService,
      heartbeatService,
      resultHolder,
      down = true,
      currentNodeId = None)

    handler.handleMessage(msg, responseHandler)
    resultHolder.getOption
  }

  private def handleRegistrationAttemptWhileUp(msg: Array[Byte], responseHandler: (Array[Byte]) => Unit, sequence: Long, nodeId: Option[String]): Option[RegistrationResult] = {

    val measService = new ActorForwardingServiceHandler(self, MeasBatchRequest(_, _, sequence))
    val heartbeatService = new ActorForwardingServiceHandler(self, HeartbeatRequest(_, _, sequence))
    val resultHolder = new RegistrationResultHolder

    val handler = RegistrationServiceHandler.wrapped(
      endpoint,
      sql,
      auth,
      resources.serviceOps,
      frontEndModel,
      measService,
      heartbeatService,
      resultHolder,
      down = false,
      currentNodeId = nodeId)

    handler.handleMessage(msg, responseHandler)
    resultHolder.getOption
  }

  private def handleHeartbeat(msg: Array[Byte], responseHandler: (Array[Byte]) => Unit) = {
    logger.trace(s"Endpoint ${endpoint.getName} handling heartbeat")
    val handler = HeartbeatServiceHandler.wrapped(endpoint, sql, frontEndModel, statusNotifier)

    handler.handleMessage(msg, responseHandler)
  }

  private def markEndpointOffline(points: Seq[(UUID, String)], processor: EndpointProcessor): Seq[(UUID, Measurement)] = {
    logger.debug(s"Marking Endpoint ${endpoint.getName} offline")
    sql.transaction {

      val entry = Seq((UUIDHelpers.protoUUIDToUuid(endpoint.getUuid), FrontEndConnectionStatus.Status.COMMS_DOWN))
      frontEndModel.putFrontEndConnectionStatuses(statusNotifier, entry)

      val uuids = points.map(_._1)

      val currentValues = resources.store.get(uuids)
      val now = System.currentTimeMillis()

      val markedOld = currentValues.map {
        case (uuid, meas) =>

          val (qualBuilder, detailQualBuilder) = mergeableQualityElements(meas)

          val marked = meas.toBuilder
            .setQuality(
              qualBuilder.setValidity(Validity.INVALID)
                .setDetailQual(detailQualBuilder.setOldData(true)))
            .setTime(now)
            .build()

          (uuid, marked)
      }

      resources.store.put(markedOld)
      processor.modifyLastValues(markedOld.map(tup => (tup._1.toString, tup._2)))

      notifyFromStore(markedOld, points)

      currentValues
    }
  }

  private def markEndpointOnline(): Unit = {
    logger.debug(s"Marking Endpoint ${endpoint.getName} online")
    sql.transaction {
      val entry = Seq((UUIDHelpers.protoUUIDToUuid(endpoint.getUuid), FrontEndConnectionStatus.Status.COMMS_UP))
      frontEndModel.putFrontEndConnectionStatuses(statusNotifier, entry)
    }
  }

  private def markEndpointOnlineAndRestoreMeasurements(previousValues: Seq[(UUID, Measurement)], points: Seq[(UUID, String)], processor: EndpointProcessor): Unit = {
    logger.debug(s"Marking Endpoint ${endpoint.getName} online and restoring measurement quality")

    val now = System.currentTimeMillis()
    sql.transaction {

      val entry = Seq((UUIDHelpers.protoUUIDToUuid(endpoint.getUuid), FrontEndConnectionStatus.Status.COMMS_UP))
      frontEndModel.putFrontEndConnectionStatuses(statusNotifier, entry)

      val restoredMeases = previousValues.map {
        case (uuid, meas) =>
          val updatedTimeMeas = meas.toBuilder.setTime(now).build()
          (uuid, updatedTimeMeas)
      }

      resources.store.put(restoredMeases)
      processor.modifyLastValues(restoredMeases.map(tup => (tup._1.toString, tup._2)))

      notifyFromStore(restoredMeases, points)
    }
  }

  private def notifyFromStore(values: Seq[(UUID, Measurement)], pointList: Seq[(UUID, String)]): Unit = {
    val uuidToName = pointList.map(tup => (tup._1, tup._2)).toMap

    val notifications = values.flatMap { case (uuid, m) => uuidToName.get(uuid).map { name => (UUIDHelpers.uuidToProtoUUID(uuid), name, m) } }

    measNotificationBatch(resources.serviceOps, endpoint, notifications)
  }
}
