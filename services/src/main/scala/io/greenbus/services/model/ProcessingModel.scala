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
package io.greenbus.services.model

import java.util.UUID

import com.google.protobuf.Message
import org.squeryl.PrimitiveTypeMode._
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.client.service.proto.Processing.MeasOverride
import io.greenbus.services.authz.EntityFilter
import io.greenbus.services.core.OverrideWithEndpoint
import io.greenbus.services.data.ServicesSchema._
import io.greenbus.services.framework._
import io.greenbus.services.model.UUIDHelpers._

object ProcessingModel {
  val overrideKey = "meas_override"
  val triggerSetKey = "trigger_set"

}
trait ProcessingModel {

  def overrideKeyQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[MeasOverride]
  def putOverrides(notifier: ModelNotifier, overrides: Seq[(UUID, MeasOverride)], filter: Option[EntityFilter]): Seq[MeasOverride]
  def deleteOverrides(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[MeasOverride]

}

object SquerylProcessingModel extends ProcessingModel {
  import io.greenbus.services.model.ProcessingModel._

  def overrideKeyQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter]): Seq[MeasOverride] = {

    val allIds = allPointIdsForKeyQuery(uuids, names, filter)

    val results = SquerylEntityModel.getKeyValues(allIds, overrideKey)

    results.map { case (_, bytes) => MeasOverride.parseFrom(bytes) }
  }

  def putOverrides(notifier: ModelNotifier, overrides: Seq[(UUID, MeasOverride)], filter: Option[EntityFilter]): Seq[MeasOverride] = {

    def toNote(obj: MeasOverride, end: Option[ModelUUID]) = OverrideWithEndpoint(obj, end)

    putPointBasedKeyValues(notifier, overrides, overrideKey, MeasOverride.parseFrom, toNote, filter)
  }

  def deleteOverrides(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter]): Seq[MeasOverride] = {
    def keyValueToResult(pointUuid: UUID, obj: Array[Byte], end: Option[UUID]) = MeasOverride.parseFrom(obj)
    def keyValueToNotification(pointUuid: UUID, obj: MeasOverride, end: Option[UUID]) = OverrideWithEndpoint(obj, end.map(uuidToProtoUUID))

    SquerylProcessingModel.deleteKeyValuesWithEndpoint(notifier, ids, overrideKey, keyValueToResult, keyValueToNotification, filter)
  }

  def allPointIdsForKeyQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter]): Seq[UUID] = {

    val idsForNames: Seq[UUID] =
      from(entities, points)((ent, pt) =>
        where(EntityFilter.optional(filter, ent).inhibitWhen(filter.isEmpty) and
          ent.id === pt.entityId and
          (ent.name in names))
          select (ent.id)).toSeq

    uuids ++ idsForNames
  }

  def putPointBasedKeyValuesWithEndpoint[PayloadType <: Message, ResultType, NoteType](notifier: ModelNotifier,
    values: Seq[(UUID, PayloadType)],
    key: String,
    toResult: (UUID, Array[Byte], Option[UUID]) => ResultType,
    toNotification: (UUID, ResultType, Option[UUID]) => NoteType,
    filter: Option[EntityFilter]): Seq[ResultType] = {

    val ids = values.map(_._1)
    filter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to touch restricted entities")
      }
    }

    val existingPoints = from(entities, points)((ent, pt) =>
      where((ent.id in ids) and pt.entityId === ent.id)
        select (ent.id)).toSeq

    val existingPointsSet = existingPoints.toSet
    if (ids.exists(id => !existingPointsSet.contains(id))) {
      throw new ModelInputException("Tried to put value to non-existent point")
    }

    val kvs = values.map { case (id, proto) => (id, proto.toByteArray) }

    val (results, creates, updates) = SquerylEntityModel.putKeyValues(kvs, key)

    val endpointMap = SquerylFrontEndModel.sourceParentMap(ids)

    val parsed = results.map { case (id, bytes) => (id, toResult(id, bytes, endpointMap.get(id))) }

    val parsedMap = parsed.toMap

    creates.foreach { uuid =>
      parsedMap.get(uuid).foreach(obj => notifier.notify(Created, toNotification(uuid, obj, endpointMap.get(uuid))))
    }
    updates.foreach { uuid =>
      parsedMap.get(uuid).foreach(obj => notifier.notify(Updated, toNotification(uuid, obj, endpointMap.get(uuid))))
    }

    parsed.map(_._2)
  }

  def putPointBasedKeyValues[A <: Message, NoteType](notifier: ModelNotifier,
    values: Seq[(UUID, A)],
    key: String,
    parse: Array[Byte] => A,
    noteFunc: (A, Option[ModelUUID]) => NoteType,
    filter: Option[EntityFilter]): Seq[A] = {

    val ids = values.map(_._1)
    filter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to touch restricted entities")
      }
    }

    val existingPoints = from(entities, points)((ent, pt) =>
      where((ent.id in ids) and pt.entityId === ent.id)
        select (ent.id)).toSeq

    val existingPointsSet = existingPoints.toSet
    if (ids.exists(id => !existingPointsSet.contains(id))) {
      throw new ModelInputException("Tried to put value to non-existent point")
    }

    val kvs = values.map { case (id, proto) => (id, proto.toByteArray) }

    val (results, creates, updates) = SquerylEntityModel.putKeyValues(kvs, key)

    val parsed = results.map { case (id, bytes) => (id, parse(bytes)) }

    val parsedMap = parsed.toMap

    val endpointMap = SquerylFrontEndModel.sourceParentMap(ids)

    creates.foreach { uuid =>
      parsedMap.get(uuid).foreach(obj => notifier.notify(Created, noteFunc(obj, endpointMap.get(uuid).map(uuidToProtoUUID))))
    }
    updates.foreach { uuid =>
      parsedMap.get(uuid).foreach(obj => notifier.notify(Updated, noteFunc(obj, endpointMap.get(uuid).map(uuidToProtoUUID))))
    }

    parsed.map(_._2)
  }

  def deleteKeyValues[A](notifier: ModelNotifier, ids: Seq[UUID], key: String, parse: Array[Byte] => A, filter: Option[EntityFilter]): Seq[A] = {
    val results = SquerylEntityModel.deleteKeyValues(ids, key, filter)
    val parsed = results.map { case (id, bytes) => (id, parse(bytes)) }

    val values = parsed.map(_._2)
    values.foreach(notifier.notify(Deleted, _))
    values
  }

  def deleteKeyValuesWithEndpoint[PayloadType <: Message, ResultType, NoteType](
    notifier: ModelNotifier,
    ids: Seq[UUID],
    key: String,
    toResult: (UUID, Array[Byte], Option[UUID]) => ResultType,
    toNotification: (UUID, ResultType, Option[UUID]) => NoteType,
    filter: Option[EntityFilter]): Seq[ResultType] = {

    filter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to delete restricted entity key values")
      }
    }

    val endpointMap = SquerylFrontEndModel.sourceParentMap(ids)

    val results = SquerylEntityModel.deleteKeyValues(ids, key, None)
    val resultParts: Seq[(UUID, ResultType, Option[UUID])] = results.map {
      case (id, bytes) =>
        val endpointUuid = endpointMap.get(id)
        (id, toResult(id, bytes, endpointUuid), endpointUuid)
    }

    resultParts.foreach {
      case (id, result, endOpt) => notifier.notify(Deleted, toNotification(id, result, endOpt))
    }

    resultParts.map(_._2)
  }
}
