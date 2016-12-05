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

import akka.actor.{ Actor, ActorRef, Props }
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.app.actor.MessageScheduling
import io.greenbus.app.actor.frontend.ProcessingProxy.DoStackStatusHeartbeat
import io.greenbus.app.actor.util.{ NestedStateMachine, TraceMessage }
import io.greenbus.client.exception.UnauthorizedException
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.client.service.proto.FrontEndRequests.FrontEndStatusUpdate
import io.greenbus.client.service.proto.Measurements.{ NamedMeasurementValue, Measurement, MeasurementBatch, PointMeasurementValue }
import io.greenbus.client.service.proto.Model.{ Endpoint, ModelUUID }
import io.greenbus.client.service.{ FrontEndService, MeasurementService }
import io.greenbus.msg.{ Session, SessionUnusableException }

import scala.collection.JavaConversions._
import scala.collection.immutable.VectorBuilder

object ProcessingProxy extends LazyLogging {

  case class LinkUp(session: Session, inputAddress: String)
  case class PointMapUpdated(pointMap: Map[String, ModelUUID])

  case object LinkLapsed

  object MeasLastValueMap {
    def apply(): MeasLastValueMap = MeasLastValueMap(Map(), Map())
  }
  case class MeasLastValueMap(byUuid: Map[ModelUUID, Measurement], byName: Map[String, Measurement]) {
    def update(moreByUuid: Seq[(ModelUUID, Measurement)], moreByName: Seq[(String, Measurement)]): MeasLastValueMap = {
      MeasLastValueMap(byUuid ++ moreByUuid, byName ++ moreByName)
    }
  }

  object MeasQueue {
    def apply(): MeasQueue = MeasQueue(Seq(), Seq())
  }
  case class MeasQueue(byUuid: Seq[(ModelUUID, Measurement)], byName: Seq[(String, Measurement)]) {
    def enqueue(moreByUuid: Seq[(ModelUUID, Measurement)], moreByName: Seq[(String, Measurement)]): MeasQueue = {
      MeasQueue(byUuid ++ moreByUuid, byName ++ moreByName)
    }
    def enqueue(other: MeasQueue): MeasQueue = {
      MeasQueue(byUuid ++ other.byUuid, byName ++ other.byName)
    }

    def nonEmpty: Boolean = byUuid.nonEmpty || byName.nonEmpty
    def isEmpty: Boolean = !nonEmpty
  }

  protected case class LinkResources(session: Session, inputAddress: String)
  protected case class StatusState(current: FrontEndConnectionStatus.Status, pending: Option[(FrontEndConnectionStatus.Status, Long)], lastStatusSuccess: Option[Long])
  protected case class MeasState(
    pointMap: Map[String, ModelUUID],
    lastValueMap: MeasLastValueMap,
    queued: MeasQueue,
    outstanding: MeasQueue,
    latestPublishTime: Option[Long],
    successPendingSince: Option[Long])

  protected sealed trait State
  protected case class Unlinked(
    currentStatus: FrontEndConnectionStatus.Status,
    lastValueMap: MeasLastValueMap,
    queued: MeasQueue,
    pointMap: Map[String, ModelUUID]) extends State
  protected case class Linked(resources: LinkResources, statusState: StatusState, measState: MeasState) extends State

  private case class StackStatusSuccess(results: Seq[FrontEndConnectionStatus]) extends TraceMessage
  private case class StackStatusFailure(ex: Throwable)
  private case class StackStatusRequestExecutionFailure(ex: Throwable)

  private case class MeasPublishSuccess(success: Boolean) extends TraceMessage
  private case class MeasPublishFailure(ex: Throwable)
  private case class MeasRequestExecutionFailure(ex: Throwable)

  case object DoStackStatusHeartbeat extends TraceMessage
  private case object DoMeasPublishRetry

  def props(subject: ActorRef, endpoint: Endpoint, statusHeartbeatPeriodMs: Long, lapsedTimeMs: Long, statusRetryPeriodMs: Long, measRetryPeriodMs: Int, measQueueLimit: Int): Props = {
    Props(classOf[ProcessingProxy], subject, endpoint, statusHeartbeatPeriodMs, lapsedTimeMs, statusRetryPeriodMs, measRetryPeriodMs, measQueueLimit)
  }

  def toPointMeases(tups: Seq[(ModelUUID, Measurement)]): Seq[PointMeasurementValue] = {
    tups.map { case (u, m) => PointMeasurementValue.newBuilder().setPointUuid(u).setValue(m).build() }
  }
  def toNamedMeases(tups: Seq[(String, Measurement)]): Seq[NamedMeasurementValue] = {
    tups.map { case (n, m) => NamedMeasurementValue.newBuilder().setPointName(n).setValue(m).build() }
  }

  def maintainQueueLimit[A](all: Seq[(A, Measurement)], queueLimit: Int): Seq[(A, Measurement)] = {

    val b = new VectorBuilder[(A, Measurement)]
    var numForKeys: Map[A, Int] = all.groupBy(_._1).mapValues(_.size)
    var projectedSize = all.size

    // Drop duplicates until we get to our queue limit
    // We can drop as long as there is at least one (the latest) for every key
    // Relying on an assumption that there is not unbounded # of keys
    all.foreach { v =>
      if (projectedSize > queueLimit) {
        numForKeys.get(v._1) match {
          case None => //illegal state
          case Some(x) if x < 1 => //illegal state
          case Some(1) =>
            b += v
          case Some(num) =>
            numForKeys = numForKeys.updated(v._1, num - 1)
            projectedSize -= 1
        }
      } else {
        b += v
      }
    }

    b.result()
  }
  def maintainQueueLimit(queue: MeasQueue, queueLimit: Int): MeasQueue = {
    MeasQueue(maintainQueueLimit(queue.byUuid, queueLimit), maintainQueueLimit(queue.byName, queueLimit))
  }

  def addWallTime(wallTime: Long, m: Measurement): Measurement = {
    if (m.hasTime) m else m.toBuilder.setTime(wallTime).build()
  }
  def addWallTime[A](wallTime: Long, seq: Seq[(A, Measurement)]): Seq[(A, Measurement)] = {
    seq.map { case (n, m) => (n, addWallTime(wallTime, m)) }
  }
  def addWallTime(wallTime: Long, queue: MeasQueue): MeasQueue = {
    MeasQueue(addWallTime(wallTime, queue.byUuid), addWallTime(wallTime, queue.byName))
  }

  def simplePrintMeas(m: Measurement): String = {
    m.getType match {
      case Measurement.Type.BOOL =>
        m.getBoolVal + ", " + m.getTime
      case Measurement.Type.INT =>
        m.getIntVal + ", " + m.getTime
      case Measurement.Type.DOUBLE =>
        m.getDoubleVal + ", " + m.getTime
      case Measurement.Type.STRING =>
        m.getStringVal + ", " + m.getTime
      case _ => "???, " + m.getTime
    }
  }

  def simpleIdMeas(tup: (ModelUUID, Measurement)) = tup._1.getValue + ", " + simplePrintMeas(tup._2)

  def mergeQueueAndLastValues(queue: MeasQueue, lastValues: MeasLastValueMap): MeasQueue = {
    val uuidsInQueue = queue.byUuid.map(_._1).toSet
    val namesInQueue = queue.byName.map(_._1).toSet
    val missingUuidValues = lastValues.byUuid.filterKeys(k => !uuidsInQueue.contains(k))
    val missingNameValues = lastValues.byName.filterKeys(k => !namesInQueue.contains(k))

    val updatedUuidValues = missingUuidValues.map(tup => (tup._1, tup._2.toBuilder.setTime(System.currentTimeMillis()).build())).toSeq
    val updatedNameValues = missingNameValues.map(tup => (tup._1, tup._2.toBuilder.setTime(System.currentTimeMillis()).build())).toSeq

    queue.enqueue(updatedUuidValues, updatedNameValues)
  }
}

class ProcessingProxy(subject: ActorRef, endpoint: Endpoint, statusHeartbeatPeriodMs: Long, lapsedTimeMs: Long, statusRetryPeriodMs: Long, measRetryPeriodMs: Int, measQueueLimit: Int) extends NestedStateMachine with MessageScheduling with LazyLogging {
  import context.dispatcher
  import io.greenbus.app.actor.frontend.ProcessingProxy._

  protected type StateType = State
  protected def start: StateType = Unlinked(FrontEndConnectionStatus.Status.COMMS_DOWN, MeasLastValueMap(), MeasQueue(), Map())
  override protected def instanceId: Option[String] = Some(endpoint.getName)

  private val heartbeater = context.actorOf(HeartbeatActor.props(self, statusHeartbeatPeriodMs))

  protected def machine = {
    case state: Unlinked => {

      case LinkUp(session, inputAddress) => {
        self ! DoStackStatusHeartbeat
        val updates = mergeQueueAndLastValues(state.queued, state.lastValueMap)
        val pushOpt = if (updates.nonEmpty) {
          logger.debug("Frontend bridge for endpoint " + endpoint.getName + " pushing queued measurements")
          pushMeasurements(session, updates, inputAddress)
          Some(System.currentTimeMillis())
        } else {
          None
        }
        Linked(LinkResources(session, inputAddress),
          StatusState(state.currentStatus, None, None),
          MeasState(state.pointMap, state.lastValueMap, queued = MeasQueue(), outstanding = updates, pushOpt, pushOpt))
      }

      case MeasurementsPublished(wallTime, idMeasurements, namedMeasurements) => {
        val measMap = state.lastValueMap.update(idMeasurements, namedMeasurements)
        val latestBatch = MeasQueue(idMeasurements, namedMeasurements)

        logger.trace("Frontend bridge for endpoint " + endpoint.getName + " queueing measurements for when link returns: " + (idMeasurements.size + namedMeasurements.size))
        val updatedQueue = maintainQueueLimit(state.queued.enqueue(addWallTime(wallTime, latestBatch)), measQueueLimit)
        state.copy(queued = updatedQueue, lastValueMap = measMap)
      }

      case StackStatusUpdated(status) => {
        state.copy(currentStatus = status)
      }

      case PointMapUpdated(map) => {
        state.copy(pointMap = map)
      }
      case DoStackStatusHeartbeat => {
        state
      }
    }

    case state: Linked => {

      case PointMapUpdated(map) => {
        state.copy(measState = state.measState.copy(pointMap = map))
      }

      case MeasurementsPublished(wallTime, idMeasurements, namedMeasurements) => {
        val measMap = state.measState.lastValueMap.update(idMeasurements, namedMeasurements)
        val latestBatch = MeasQueue(idMeasurements, namedMeasurements)

        val queued = state.measState.queued enqueue addWallTime(wallTime, latestBatch)
        if (state.measState.outstanding.isEmpty) {
          logger.trace("Frontend bridge for endpoint " + endpoint.getName + " pushing measurements immediately")
          pushMeasurements(state.resources.session, queued, state.resources.inputAddress)
          state.copy(measState = state.measState.copy(outstanding = queued, queued = MeasQueue(), lastValueMap = measMap, latestPublishTime = Some(System.currentTimeMillis()), successPendingSince = Some(System.currentTimeMillis())))
        } else {
          logger.trace("Frontend bridge for endpoint " + endpoint.getName + " queueing measurements for when outstanding publish finishes")
          state.copy(measState = state.measState.copy(queued = queued, lastValueMap = measMap))
        }
      }

      case MeasPublishSuccess(success) => {
        success match {
          case false =>
            logger.warn("Frontend bridge for endpoint " + endpoint.getName + " had got false on publish response")
            state.measState.latestPublishTime match {
              case None => scheduleMsg(measRetryPeriodMs, DoMeasPublishRetry)
              case Some(prev) => scheduleRelativeToPrevious(prev, measRetryPeriodMs, DoMeasPublishRetry)
            }
            state.copy(measState = state.measState.copy(queued = state.measState.outstanding.enqueue(state.measState.queued), outstanding = MeasQueue()))
          case true =>
            if (state.measState.queued.nonEmpty) {
              logger.trace("Frontend bridge for endpoint " + endpoint.getName + " had publish success, pushing queued measurements")
              pushMeasurements(state.resources.session, state.measState.queued, state.resources.inputAddress)
              state.copy(measState = state.measState.copy(outstanding = state.measState.queued, queued = MeasQueue(), latestPublishTime = Some(System.currentTimeMillis()), successPendingSince = Some(System.currentTimeMillis())))
            } else {
              logger.trace("Frontend bridge for endpoint " + endpoint.getName + " had publish success")
              state.copy(measState = state.measState.copy(queued = MeasQueue(), outstanding = MeasQueue(), successPendingSince = None))
            }
        }
      }

      case MeasPublishFailure(ex) => {
        logger.warn("Frontend bridge for endpoint " + endpoint.getName + " had error publishing measurements")
        val requeued = state.measState.outstanding enqueue state.measState.queued

        ex match {
          case ex: SessionUnusableException =>
            subject ! ServiceSessionDead
            Unlinked(state.statusState.current, state.measState.lastValueMap, requeued, state.measState.pointMap)
          case ex: UnauthorizedException =>
            subject ! ServiceSessionDead
            Unlinked(state.statusState.current, state.measState.lastValueMap, requeued, state.measState.pointMap)
          case ex: Throwable => {
            if (state.measState.successPendingSince.exists(last => System.currentTimeMillis() - last > lapsedTimeMs)) {
              logger.info(s"Link for endpoint ${endpoint.getName} lapsed due to measurement publishing timeouts")
              logger.trace(s"Dropping link for endpoint ${endpoint.getName} with the current measurement state: " + requeued)
              subject ! LinkLapsed
              Unlinked(state.statusState.current, state.measState.lastValueMap, requeued, state.measState.pointMap)
            } else {
              state.measState.latestPublishTime match {
                case None => scheduleMsg(measRetryPeriodMs, DoMeasPublishRetry)
                case Some(prev) => scheduleRelativeToPrevious(prev, measRetryPeriodMs, DoMeasPublishRetry)
              }
              state.copy(measState = state.measState.copy(queued = requeued, outstanding = MeasQueue()))
            }
          }
        }
      }

      case MeasRequestExecutionFailure(ex) => {
        logger.warn("Frontend bridge for endpoint " + endpoint.getName + " had request execution error publishing measurements")
        val requeued = state.measState.outstanding enqueue state.measState.queued
        subject ! ServiceSessionDead
        Unlinked(state.statusState.current, state.measState.lastValueMap, requeued, state.measState.pointMap)
      }

      case DoMeasPublishRetry => {
        if (state.measState.outstanding.isEmpty && state.measState.queued.nonEmpty) {
          pushMeasurements(state.resources.session, state.measState.queued, state.resources.inputAddress)
          state.copy(measState = state.measState.copy(outstanding = state.measState.queued, queued = MeasQueue(), latestPublishTime = Some(System.currentTimeMillis())))
        } else {
          state
        }
      }

      case StackStatusUpdated(status) => {
        logger.debug("Frontend bridge for endpoint " + endpoint.getName + " saw comms status update: " + status)

        state.statusState.pending match {
          case None =>
            pushStatusUpdate(state.resources.session, endpoint, status, state.resources.inputAddress)
            state.copy(statusState = state.statusState.copy(current = status, pending = Some(status, System.currentTimeMillis())))
          case Some(_) =>
            state.copy(statusState = state.statusState.copy(current = status))
        }
      }

      case StackStatusSuccess(results) => {
        logger.trace("Frontend bridge for endpoint " + endpoint.getName + " successfully published stack status heartbeat")

        val now = System.currentTimeMillis()
        state.statusState.pending match {
          case None =>
            logger.warn(s"Got stack status success for endpoint ${endpoint.getName} with no pending status")
            state.copy(statusState = state.statusState.copy(lastStatusSuccess = Some(now)))
          case Some((previousStatus, sentTime)) => {
            if (state.statusState.current != previousStatus) {
              pushStatusUpdate(state.resources.session, endpoint, state.statusState.current, state.resources.inputAddress)
              state.copy(statusState = state.statusState.copy(pending = Some(state.statusState.current, now), lastStatusSuccess = Some(now)))
            } else {
              state.copy(statusState = state.statusState.copy(pending = None, lastStatusSuccess = Some(now)))
            }
          }
        }
      }

      // TODO: handle case where outstanding meas publish succeeds, thus eventually causing duplicate measurements
      case StackStatusFailure(ex) => {
        logger.warn("Frontend bridge for endpoint " + endpoint.getName + " had error publishing stack status heartbeat")
        ex match {
          case ex: SessionUnusableException =>
            subject ! ServiceSessionDead
            Unlinked(state.statusState.current, state.measState.lastValueMap, state.measState.outstanding enqueue state.measState.queued, state.measState.pointMap)
          case ex: UnauthorizedException =>
            subject ! ServiceSessionDead
            Unlinked(state.statusState.current, state.measState.lastValueMap, state.measState.outstanding enqueue state.measState.queued, state.measState.pointMap)
          case ex: Throwable => {
            if (state.statusState.lastStatusSuccess.exists(last => System.currentTimeMillis() - last > lapsedTimeMs)) {
              logger.info(s"Link for endpoint ${endpoint.getName} lapsed due to heartbeat publishing timeouts")
              logger.trace(s"Dropping link for endpoint ${endpoint.getName} with the current measurement state: " + (state.measState.outstanding enqueue state.measState.queued))
              subject ! LinkLapsed
              Unlinked(state.statusState.current, state.measState.lastValueMap, state.measState.outstanding enqueue state.measState.queued, state.measState.pointMap)
            } else {
              state.statusState.pending match {
                case None =>
                  logger.warn(s"Got stack status failure for endpoint ${endpoint.getName} with no pending status")
                  state
                case Some((previousStatus, sentTime)) => {
                  scheduleRelativeToPrevious(sentTime, statusRetryPeriodMs, DoStackStatusHeartbeat)
                  state.copy(statusState = state.statusState.copy(pending = None))
                }
              }
            }
          }
        }
      }

      case StackStatusRequestExecutionFailure(ex) => {
        logger.warn("Frontend bridge for endpoint " + endpoint.getName + " had request execution error publishing heartbeat")
        val requeued = state.measState.outstanding enqueue state.measState.queued
        subject ! ServiceSessionDead
        Unlinked(state.statusState.current, state.measState.lastValueMap, requeued, state.measState.pointMap)
      }

      case DoStackStatusHeartbeat => {
        state.statusState.pending match {
          case None =>
            pushStatusUpdate(state.resources.session, endpoint, state.statusState.current, state.resources.inputAddress)
            state.copy(statusState = state.statusState.copy(pending = Some(state.statusState.current, System.currentTimeMillis())))
          case Some(_) => state
        }
      }
    }
  }

  private def resolveMeases(namedMeasurements: Seq[(String, Measurement)], pointMap: Map[String, ModelUUID]): Seq[(ModelUUID, Measurement)] = {
    namedMeasurements.flatMap {
      case (n, m) => pointMap.get(n) match {
        case None =>
          logger.warn("Unrecognized point name for measurement: " + n)
          None
        case Some(uuid) => Some((uuid, m))
      }
    }
  }

  private def scheduleRelativeToPrevious(previousTime: Long, offset: Long, msg: AnyRef): Unit = {
    val shiftedFromSentTime = (previousTime + offset) - System.currentTimeMillis()
    if (shiftedFromSentTime > 0) {
      scheduleMsg(shiftedFromSentTime, msg)
    } else {
      self ! msg
    }
  }

  private def pushMeasurements(session: Session, queued: MeasQueue, address: String): Unit = {

    val pointMeasurements = toPointMeases(queued.byUuid)
    val namedMeasurements = toNamedMeases(queued.byName)

    logger.trace("Frontend bridge for endpoint " + endpoint.getName + " saw measurement updates: " + (pointMeasurements.size + namedMeasurements.size))
    val measClient = MeasurementService.client(session)

    val batch = MeasurementBatch.newBuilder()
      .addAllPointMeasurements(pointMeasurements)
      .addAllNamedMeasurements(namedMeasurements)
      .build()

    try {
      val postFut = measClient.postMeasurements(batch, address)
      postFut.onSuccess { case result => self ! MeasPublishSuccess(result) }
      postFut.onFailure { case ex => self ! MeasPublishFailure(ex) }
    } catch {
      case ex: Throwable =>
        self ! MeasRequestExecutionFailure(ex)
    }
  }

  private def pushStatusUpdate(session: Session, endpoint: Endpoint, currentStatus: FrontEndConnectionStatus.Status, address: String): Unit = {
    logger.trace(s"Frontend bridge for endpoint ${endpoint.getName} pushing status: $currentStatus")
    val frontEndClient = FrontEndService.client(session)

    val update = FrontEndStatusUpdate.newBuilder()
      .setEndpointUuid(endpoint.getUuid)
      .setStatus(currentStatus)
      .build()

    try {
      val putFut = frontEndClient.putFrontEndConnectionStatuses(List(update), address)

      putFut.onSuccess { case results => self ! StackStatusSuccess(results) }
      putFut.onFailure { case ex: Throwable => self ! StackStatusFailure(ex) }
    } catch {
      case ex: Throwable =>
        self ! StackStatusRequestExecutionFailure(ex)
    }
  }

}

object HeartbeatActor {
  case object Heartbeat

  def props(subject: ActorRef, intervalMs: Long): Props = {
    Props(classOf[HeartbeatActor], subject, intervalMs)
  }
}
class HeartbeatActor(subject: ActorRef, intervalMs: Long) extends Actor with MessageScheduling with LazyLogging {
  import HeartbeatActor._

  self ! Heartbeat
  def receive = {
    case Heartbeat =>
      subject ! DoStackStatusHeartbeat
      scheduleMsg(intervalMs, Heartbeat)
  }
}
