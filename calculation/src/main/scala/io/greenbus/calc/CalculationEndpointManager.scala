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
package io.greenbus.calc

import akka.actor._
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.msg.{ Session, SessionUnusableException, Subscription, SubscriptionBinding }
import io.greenbus.app.actor.frontend.{ MeasurementsPublished, StackStatusUpdated }
import io.greenbus.calc.lib.eval.OperationSource
import io.greenbus.client.exception.{ BusUnavailableException, ServiceException, UnauthorizedException }
import io.greenbus.client.proto.Envelope.SubscriptionEventType
import io.greenbus.client.service.ModelService
import io.greenbus.client.service.proto.Calculations.CalculationDescriptor
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.client.service.proto.Model._
import io.greenbus.client.service.proto.ModelRequests._

import scala.concurrent.Future
import scala.concurrent.duration._

object CalculationEndpointManager {

  val retrieveRetryMs = 5000

  case class ObjectAdded(uuid: ModelUUID)
  case class ObjectRemoved(uuid: ModelUUID)

  case class CalculationRetrieved(uuid: ModelUUID, calcs: Seq[CalculationDescriptor])
  case class CalculationRetrieveError(uuid: ModelUUID, ex: Throwable)

  case class CalcEvent(eventType: SubscriptionEventType, uuid: ModelUUID, calc: CalculationDescriptor)

  case class SubResult(current: Seq[(ModelUUID, CalculationDescriptor)], subscription: Subscription[EntityKeyValueNotification], edgeCurrent: Seq[EntityEdge], edgeSub: Subscription[EntityEdgeNotification])
  case class SubError(ex: Throwable)

  def props(endpoint: Endpoint, session: Session, publishMeasurements: MeasurementsPublished => Unit, updateStatus: StackStatusUpdated => Unit, opSource: OperationSource): Props = {
    Props(classOf[CalculationEndpointManager], endpoint, session, publishMeasurements, updateStatus, opSource)
  }

}

class CalculationEndpointManager(endpoint: Endpoint, session: Session, publishMeasurements: MeasurementsPublished => Unit, updateStatus: StackStatusUpdated => Unit, opSource: OperationSource) extends Actor with Logging {
  import io.greenbus.calc.CalculationEndpointManager._

  private var calcSubscription = Option.empty[SubscriptionBinding]
  private var edgeSubscription = Option.empty[SubscriptionBinding]

  private var calcMap = Map.empty[ModelUUID, ActorRef]

  // a check for if removed between the time point is added and calc comes in
  private var outstandingPoints = Set.empty[ModelUUID]

  private def publish(uuid: ModelUUID, m: Measurement): Unit = publishMeasurements(MeasurementsPublished(System.currentTimeMillis(), Seq((uuid, m)), Seq()))

  setupConfig()

  def receive = {
    case SubResult(current, sub, currentEdges, edgeSub) =>
      calcSubscription = Some(sub)
      edgeSubscription = Some(edgeSub)

      sub.start { calcEvent =>
        keyValueToUuidAndCalc(calcEvent.getValue).foreach {
          case (uuid, calc) =>
            self ! CalcEvent(calcEvent.getEventType, uuid, calc)
        }

      }

      edgeSub.start { ev =>
        ev.getEventType match {
          case SubscriptionEventType.ADDED => self ! ObjectAdded(ev.getValue.getChild)
          case SubscriptionEventType.REMOVED => self ! ObjectRemoved(ev.getValue.getChild)
          case SubscriptionEventType.MODIFIED =>
        }
      }

      logger.info(s"Calculations initialized for ${endpoint.getName}")
      logger.debug(s"Calculations (${current.size}) configured for ${endpoint.getName}: " + current.map(_._1.getValue))
      updateStatus(StackStatusUpdated(FrontEndConnectionStatus.Status.COMMS_UP))

      updateCalcs(current)

    case SubError(ex: Throwable) =>
      logger.warn(s"Error setting up subscription for endpoint ${endpoint.getUuid}")
      throw ex

    case CalcEvent(eventType, uuid, calc) =>
      import io.greenbus.client.proto.Envelope.SubscriptionEventType._
      eventType match {
        case ADDED =>
          logger.debug(s"Calculation ${uuid.getValue} added on calculation endpoint ${endpoint.getName}")
          updateCalcs(Seq((uuid, calc)))
        case MODIFIED =>
          logger.debug(s"Calculation ${uuid.getValue} modified on calculation endpoint ${endpoint.getName}")
          updateCalcs(Seq((uuid, calc)))
        case REMOVED =>
          logger.debug(s"Calculation ${uuid.getValue} removed on calculation endpoint ${endpoint.getName}")
          removeCalcs(Seq(uuid))
      }

    case ObjectAdded(uuid) =>
      logger.debug(s"Point ${uuid.getValue} added on calculation endpoint ${endpoint.getName}")
      outstandingPoints += uuid
      retrieveCalc(uuid)

    case ObjectRemoved(uuid) =>
      logger.debug(s"Point ${uuid.getValue} removed on calculation endpoint ${endpoint.getName}")
      outstandingPoints -= uuid
      removeCalcs(Seq(uuid))

    case CalculationRetrieved(uuid, calcs) =>
      calcs.headOption match {
        case None =>
          logger.debug(s"Calculation config empty for ${uuid.getValue} on calculation endpoint ${endpoint.getName}")
          outstandingPoints -= uuid
        case Some(calc) =>
          logger.debug(s"Calculation retrieved for ${uuid.getValue} on calculation endpoint ${endpoint.getName}")
          updateCalcs(Seq((uuid, calc)))
          outstandingPoints -= uuid
      }

    case CalculationRetrieveError(uuid, error) =>
      error match {
        case ex: BusUnavailableException => scheduleMsg(retrieveRetryMs, ObjectAdded(uuid))
        case ex: UnauthorizedException => throw ex
        case ex: ServiceException =>
          // We assume this means the configuration is bad, treat it like a point that failed to start
          logger.warn(s"Failed to get calculation for ${uuid.getValue} for endpoint ${endpoint.getName}")
        case ex => throw ex
      }

  }

  override def postStop(): Unit = {
    calcSubscription.foreach(_.cancel())
    edgeSubscription.foreach(_.cancel())
  }

  private def removeCalcs(uuids: Seq[ModelUUID]) {
    uuids.flatMap(calcMap.get).foreach(_ ! PoisonPill)
    calcMap --= uuids
  }

  private def retrieveCalc(uuid: ModelUUID) {
    import context.dispatcher
    val modelClient = ModelService.client(session)

    val keyPair = EntityKeyPair.newBuilder()
      .setUuid(uuid)
      .setKey("calculation")
      .build()

    val calcFut = modelClient.getEntityKeyValues(Seq(keyPair)).map { results =>
      results.flatMap(keyValueToCalc)
    }

    import context.dispatcher
    calcFut.onSuccess { case calcs => self ! CalculationRetrieved(uuid, calcs) }
    calcFut.onFailure { case ex => self ! CalculationRetrieveError(uuid, ex) }
  }

  private def updateCalcs(current: Seq[(ModelUUID, CalculationDescriptor)]) {

    removeCalcs(current.map(_._1))

    val created = current.flatMap {
      case (uuid, calc) =>
        try {
          val actor = context.actorOf(CalculationPointManager.props(uuid, calc, session, opSource, publish))
          Some(uuid, actor)
        } catch {
          case ex: Throwable =>
            logger.warn(s"Couldn't initialize point in endpoint ${endpoint.getName}: " + ex.getMessage)
            None
        }
    }

    calcMap = calcMap ++ created
  }

  private def keyValueToCalc(kvp: EntityKeyValue): Option[CalculationDescriptor] = {
    if (kvp.hasValue && kvp.getValue.hasByteArrayValue) {
      try {
        Some(CalculationDescriptor.parseFrom(kvp.getValue.getByteArrayValue))
      } catch {
        case ex: Throwable =>
          logger.warn(s"Calculation set for uuid ${kvp.getUuid} couldn't parse")
          None
      }
    } else {
      None
    }
  }

  private def keyValueToUuidAndCalc(kvp: EntityKeyValue): Option[(ModelUUID, CalculationDescriptor)] = {
    keyValueToCalc(kvp).map(c => (kvp.getUuid, c))
  }

  private def getCalcFuture(): Future[Seq[(ModelUUID, CalculationDescriptor)]] = {
    import context.dispatcher
    val modelClient = ModelService.client(session)

    val pageSize = Int.MaxValue // TODO: Breaks on large endpoints?

    val pointQueryTemplate = EntityRelationshipFlatQuery.newBuilder()
      .addStartUuids(endpoint.getUuid)
      .setRelationship("source")
      .setDescendantOf(true)
      .addEndTypes("Point")
      .setPagingParams(
        EntityPagingParams.newBuilder()
          .setPageSize(pageSize))

    val entFuture = modelClient.relationshipFlatQuery(pointQueryTemplate.build())

    entFuture.flatMap {
      case Seq() => Future.successful(Nil)
      case ents: Seq[Entity] =>
        val keys = ents.map { e =>
          EntityKeyPair.newBuilder()
            .setUuid(e.getUuid)
            .setKey("calculation")
            .build()
        }

        modelClient.getEntityKeyValues(keys).map { results =>
          results.flatMap(keyValueToUuidAndCalc)
        }
    }
  }

  private def setupConfig() {
    import context.dispatcher

    val modelClient = ModelService.client(session)

    val edgeQuery = EntityEdgeSubscriptionQuery.newBuilder()
      .addFilters(EntityEdgeFilter.newBuilder()
        .setParentUuid(endpoint.getUuid)
        .setRelationship("source")
        .setDistance(1)
        .build())
      .build()

    val edgeFut = modelClient.subscribeToEdges(edgeQuery)

    val allFut: Future[SubResult] = edgeFut.flatMap { edgeResult =>

      val subFut = modelClient.subscribeToEntityKeyValues(EntityKeyValueSubscriptionQuery.newBuilder().addEndpointUuids(endpoint.getUuid).build())

      subFut.onFailure { case _ => edgeResult._2.cancel() }

      subFut.flatMap {
        case (emptyCurrent, sub) =>
          val currentFut = getCalcFuture()

          currentFut.onFailure { case ex => sub.cancel() }

          currentFut.map { calcs =>
            (calcs, sub)
          }
      }.map {
        case (calcCurrent, sub) => SubResult(calcCurrent, sub, edgeResult._1, edgeResult._2)
      }
    }

    allFut.onSuccess { case result => self ! result }
    allFut.onFailure { case ex => self ! SubError(ex) }
  }

  override def supervisorStrategy: SupervisorStrategy = {
    import akka.actor.SupervisorStrategy._
    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 3.seconds) {
      case _: SessionUnusableException => Escalate
      case _: UnauthorizedException => Escalate
      case _: Throwable => Restart
    }
  }

  private def scheduleMsg(timeMs: Long, msg: AnyRef) {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(
      Duration(timeMs, MILLISECONDS),
      self,
      msg)
  }
}
