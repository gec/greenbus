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

import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.msg.{ Session, Subscription }
import io.greenbus.client.service.proto.Model._
import io.greenbus.client.service.proto.ModelRequests._
import io.greenbus.client.service.proto.Processing.{ MeasOverride, OverrideNotification, TriggerSet }
import io.greenbus.client.service.proto.ProcessingRequests.OverrideSubscriptionQuery
import io.greenbus.client.service.{ ModelService, ProcessingService }

import scala.collection.JavaConversions._
import scala.concurrent.{ ExecutionContext, Future }

case class EndpointConfiguration(points: Seq[(ModelUUID, String)],
  pointSubscription: Subscription[PointNotification],
  overrides: Seq[MeasOverride],
  overrideSubscription: Subscription[OverrideNotification],
  triggerSets: Seq[(ModelUUID, TriggerSet)],
  triggerSetSubscription: Subscription[EntityKeyValueNotification],
  edgeSubscription: Subscription[EntityEdgeNotification])

object ServiceConfiguration extends Logging {

  val triggerSetKey = "triggerSet"

  def endpointEdgesSubscription(session: Session, endpointId: ModelUUID)(implicit context: ExecutionContext): Future[Subscription[EntityEdgeNotification]] = {
    val modelClient = ModelService.client(session)

    val edgeQuery = EntityEdgeSubscriptionQuery.newBuilder()
      .addFilters(EntityEdgeFilter.newBuilder()
        .setParentUuid(endpointId)
        .setRelationship("source")
        .setDistance(1)
        .build())
      .build()

    val edgeSubFut = modelClient.subscribeToEdges(edgeQuery)

    edgeSubFut.map(_._2)
  }

  def keyValueToTriggerSet(kvp: EntityKeyValue): Option[TriggerSet] = {
    if (kvp.hasValue && kvp.getValue.hasByteArrayValue) {
      try {
        Some(TriggerSet.parseFrom(kvp.getValue.getByteArrayValue))
      } catch {
        case ex: Throwable =>
          logger.warn(s"Trigger set for uuid ${kvp.getUuid} couldn't parse")
          None
      }
    } else {
      logger.warn(s"Trigger set for uuid ${kvp.getUuid} had no byte value")
      None
    }
  }
  def keyValueToUuidAndTriggerSet(kvp: EntityKeyValue): Option[(ModelUUID, TriggerSet)] = {
    keyValueToTriggerSet(kvp).map(c => (kvp.getUuid, c))
  }

  def configForPointAdded(session: Session, uuid: ModelUUID)(implicit context: ExecutionContext): Future[Option[(Point, Option[MeasOverride], Option[TriggerSet])]] = {
    val modelClient = ModelService.client(session)
    val procClient = ProcessingService.client(session)

    val keys = EntityKeySet.newBuilder().addUuids(uuid).build()

    val keyPair = EntityKeyPair.newBuilder().setUuid(uuid).setKey(triggerSetKey).build()

    modelClient.getPoints(keys).flatMap { points =>
      points.headOption match {
        case None => Future.successful(None)
        case Some(pt) =>
          (procClient.getOverrides(keys) zip modelClient.getEntityKeyValues(Seq(keyPair))).map {
            case (over, trigger) =>
              Some((pt, over.headOption, trigger.headOption.flatMap(keyValueToTriggerSet)))
          }
      }
    }

  }

  def subsForEndpoint(session: Session, endpointId: ModelUUID)(implicit executor: ExecutionContext): Future[(Subscription[PointNotification], Subscription[OverrideNotification], Subscription[EntityKeyValueNotification])] = {
    val modelClient = ModelService.client(session)
    val procClient = ProcessingService.client(session)

    val pointSubQuery = PointSubscriptionQuery.newBuilder().addEndpointUuids(endpointId).build()
    val overSubQuery = OverrideSubscriptionQuery.newBuilder().addEndpointUuids(endpointId).build()
    val triggerSubQuery = EntityKeyValueSubscriptionQuery.newBuilder().addEndpointUuids(endpointId).build()

    val pointSubFut = modelClient.subscribeToPoints(pointSubQuery)

    val overSubFut = procClient.subscribeToOverrides(overSubQuery)

    val triggerSubFut = modelClient.subscribeToEntityKeyValues(triggerSubQuery)

    def cancelSub[A, B](result: (A, Subscription[B])) { result._2.cancel() }

    pointSubFut.onFailure { case _ => triggerSubFut.foreach(cancelSub); overSubFut.foreach(cancelSub) }
    overSubFut.onFailure { case _ => triggerSubFut.foreach(cancelSub); pointSubFut.foreach(cancelSub) }
    triggerSubFut.onFailure { case _ => overSubFut.foreach(cancelSub); pointSubFut.foreach(cancelSub) }

    (pointSubFut zip (overSubFut zip triggerSubFut)).map {
      case ((_, pointSub), ((_, overSub), (_, triggerSub))) => (pointSub, overSub, triggerSub)
    }
  }

  def currentSetsForEndpoint(session: Session, endpointId: ModelUUID)(implicit executor: ExecutionContext): Future[(Seq[Entity], Seq[MeasOverride], Seq[(ModelUUID, TriggerSet)])] = {

    val modelClient = ModelService.client(session)
    val procClient = ProcessingService.client(session)

    val pageSize = Int.MaxValue // TODO: Breaks on large endpoints?

    val pointQueryTemplate = EntityRelationshipFlatQuery.newBuilder()
      .addStartUuids(endpointId)
      .setRelationship("source")
      .setDescendantOf(true)
      .addEndTypes("Point")
      .setPagingParams(
        EntityPagingParams.newBuilder()
          .setPageSize(pageSize))
      .build()

    val entFuture = modelClient.relationshipFlatQuery(pointQueryTemplate)

    val overFut = entFuture.flatMap { pointEntities =>
      if (pointEntities.isEmpty) {
        Future.successful(Nil)
      } else {
        val pointSet = EntityKeySet.newBuilder().addAllUuids(pointEntities.map(_.getUuid)).build()
        procClient.getOverrides(pointSet)
      }
    }

    val triggerFut: Future[Seq[(ModelUUID, TriggerSet)]] = entFuture.flatMap { pointEntities =>
      if (pointEntities.isEmpty) {
        Future.successful(Nil)
      } else {
        val keyValues = pointEntities.map(e => EntityKeyPair.newBuilder().setUuid(e.getUuid).setKey(triggerSetKey).build())

        modelClient.getEntityKeyValues(keyValues).map(_.flatMap(keyValueToUuidAndTriggerSet))
      }
    }

    (entFuture zip (overFut zip triggerFut)).map {
      case (ents, (overs, triggers)) => (ents, overs, triggers)
    }
  }

  def configForEndpoint(session: Session, endpointId: ModelUUID)(implicit executor: ExecutionContext): Future[EndpointConfiguration] = {
    val configSubFut = subsForEndpoint(session, endpointId)

    val edgeSubFut = endpointEdgesSubscription(session, endpointId)

    configSubFut.onFailure { case _ => edgeSubFut.foreach(_.cancel()) }
    edgeSubFut.onFailure { case _ => configSubFut.foreach { case (pointSub, overSub, triggerSub) => pointSub.cancel(); overSub.cancel(); triggerSub.cancel() } }

    (configSubFut zip edgeSubFut).flatMap {
      case ((pointSub, overSub, triggerSub), edgeSub) =>

        val currentFut = currentSetsForEndpoint(session, endpointId)

        currentFut.onFailure { case _ => overSub.cancel(); triggerSub.cancel(); edgeSub.cancel() }

        currentFut.map {
          case (ents, overs, triggers) =>
            EndpointConfiguration(ents.map(p => (p.getUuid, p.getName)), pointSub, overs, overSub, triggers, triggerSub, edgeSub)
        }
    }
  }
}
