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

import io.greenbus.services.data.{ EntityRow, EntityBased }
import org.squeryl.Table
import org.squeryl.dsl.ast.{ ExpressionNode, LogicalBoolean }
import java.util.UUID
import io.greenbus.services.authz.EntityFilter
import org.squeryl.PrimitiveTypeMode._
import io.greenbus.services.data.ServicesSchema._
import io.greenbus.services.framework.{ Deleted, Updated, Created, ModelNotifier }
import io.greenbus.services.model.UUIDHelpers._
import io.greenbus.services.model.EntityModel.TypeParams
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.services.model.FrontEndModel.{ NamedEntityTemplate, IdentifiedEntityTemplate }

object EntityBasedModels {

  def entityBasedQuery[DbType <: EntityBased](
    table: Table[DbType],
    typeParams: TypeParams,
    rowClause: DbType => LogicalBoolean,
    lastUuid: Option[UUID],
    lastName: Option[String],
    pageSize: Int,
    pageByName: Boolean = true,
    filter: Option[EntityFilter] = None): Seq[(UUID, String, Seq[String], DbType)] = {

    val guard = SquerylEntityModel.guardFunc(lastUuid, lastName, pageByName)

    def ordering(ent: EntityRow): ExpressionNode = {
      if (pageByName) {
        ent.name
      } else {
        ent.id
      }
    }

    val query =
      from(entities, table)((ent, row) =>
        where(EntityFilter.optional(filter, ent).inhibitWhen(filter.isEmpty) and
          guard(ent) and
          ent.id === row.entityId and
          SquerylEntityModel.entTypesClause(ent, typeParams) and
          rowClause(row))
          select (ent.id, ent.name, row)
          orderBy (ordering(ent)))
        .page(0, pageSize)

    val results: Seq[(UUID, String, DbType)] = query.toVector

    val entIds = results.map(_._1)

    val typeMap: Map[UUID, Seq[String]] = SquerylEntityModel.entitiesWithTypes(entities.where(t => t.id in entIds)).map { case (row, types) => (row.id, types) }.toMap

    results.map {
      case (uuid, name, row) =>
        val types = typeMap.get(uuid).getOrElse(Nil)
        (uuid, name, types, row)
    }
  }

  def entityBasedKeyQuery[A <: EntityBased](uuids: Seq[UUID], names: Seq[String], table: Table[A], filter: Option[EntityFilter] = None): Seq[(UUID, String, Seq[String], A)] = {

    val entQuery = from(entities)(ent =>
      where(EntityFilter.optional(filter, ent).inhibitWhen(filter.isEmpty) and
        ((ent.id in uuids).inhibitWhen(uuids.isEmpty) or
          (ent.name in names).inhibitWhen(names.isEmpty)))
        select (ent))

    val typeMap: Map[UUID, Seq[String]] = SquerylEntityModel.entitiesWithTypes(entQuery).map { case (row, types) => (row.id, types) }.toMap

    val entAndRow: Seq[(UUID, String, A)] =
      from(entities, table)((ent, mod) =>
        where(EntityFilter.optional(filter, ent).inhibitWhen(filter.isEmpty) and
          ((ent.id in uuids).inhibitWhen(uuids.isEmpty) or
            (ent.name in names).inhibitWhen(names.isEmpty)) and
            (mod.entityId === ent.id))
          select (ent.id, ent.name, mod)).toSeq

    entAndRow.map {
      case (uuid, name, row) =>
        val types = typeMap.get(uuid).getOrElse(Nil)
        (uuid, name, types, row)
    }

  }

  def existingEntityBased[DbType <: EntityBased, TemplateInfo](ids: Seq[UUID], names: Seq[String], table: Table[DbType], coreType: String): Seq[(UUID, String, DbType)] = {

    val currentStateOpt: Seq[(UUID, String, Option[DbType])] =
      join(entities, table.leftOuter)((ent, mod) =>
        where((ent.id in ids) or (ent.name in names))
          select (ent.id, ent.name, mod)
          on (Some(ent.id) === mod.map(_.entityId))).toList

    // It's illegal to have an entity that's not attached to a point/command/etc, and add it on top of it
    val currentState: Seq[(UUID, String, DbType)] = currentStateOpt.map {
      case (id, name, rowOpt) => rowOpt match {
        case None => throw new ModelInputException(s"Entity $name already exists but is not a $coreType")
        case Some(row) => (id, name, row)
      }
    }

    currentState
  }

  def entityBasedCreatesAndUpdates[DbType <: EntityBased, TemplateInfo](
    withIds: Seq[IdentifiedEntityTemplate[TemplateInfo]],
    withNames: Seq[NamedEntityTemplate[TemplateInfo]],
    currentMap: Map[UUID, DbType],
    nameToUuidMap: Map[String, UUID],
    create: (UUID, TemplateInfo) => DbType,
    update: (UUID, DbType, TemplateInfo) => Option[DbType]): (Seq[DbType], Seq[DbType]) = {

    var creates = List.empty[DbType]
    var updates = List.empty[DbType]

    withIds.map { temp =>
      currentMap.get(temp.uuid) match {
        case None =>
          creates ::= create(temp.uuid, temp.info)
        case Some(row) =>
          update(temp.uuid, row, temp.info).foreach { up => updates ::= up }
      }
    }

    withNames.map { temp =>
      nameToUuidMap.get(temp.name) match {
        case None => throw new ModelAssertionException(s"Failed to find or create entity for '${temp.name}'")
        case Some(uuid) =>
          currentMap.get(uuid) match {
            case None =>
              creates ::= create(uuid, temp.info)
            case Some(row) =>
              update(uuid, row, temp.info).foreach { up => updates ::= up }
          }
      }
    }

    (creates, updates)
  }

  def performEntityBasedCreateAndUpdates[DbType <: EntityBased, TemplateInfo](
    notifier: ModelNotifier,
    withIds: Seq[IdentifiedEntityTemplate[TemplateInfo]],
    withNames: Seq[NamedEntityTemplate[TemplateInfo]],
    coreType: String,
    create: (UUID, TemplateInfo) => DbType,
    update: (UUID, DbType, TemplateInfo) => Option[DbType],
    table: Table[DbType],
    allowCreate: Boolean = true,
    updateFilter: Option[EntityFilter] = None): (Set[UUID], Set[UUID], Set[UUID], Map[String, UUID]) = {

    val ids = withIds.map(_.uuid)
    val names = withNames.map(_.name)

    updateFilter.foreach { filter =>
      if (filter.anyOutsideSet(entities.where(t => (t.id in ids) and (t.name in names)))) {
        throw new ModelPermissionException("Tried to put restricted entities")
      }
    }

    val currentState: Seq[(UUID, String, DbType)] = existingEntityBased(ids, names, table, coreType)

    val currentMap: Map[UUID, DbType] = currentState.map(cs => (cs._1, cs._3)).toMap

    val withIdDescs = withIds.map(tup => (tup.uuid, tup.name, tup.types))
    val withNameDescs = withNames.map(tup => (tup.name, tup.types))

    val (entCreateIds, entUpdateIds, foundOrCreatedEntities) = SquerylEntityModel.findOrCreateEntities(notifier, withIdDescs, withNameDescs, allowCreate, updateFilter)

    val nameToUuidMap: Map[String, UUID] = {
      val currentStateNameToUuidMap: Map[String, UUID] = currentState.map(cs => (cs._2, cs._1)).toMap
      val updatedNameToUuidMap: Map[String, UUID] = foundOrCreatedEntities.map(e => (e.getName, protoUUIDToUuid(e.getUuid))).toMap

      currentStateNameToUuidMap ++ updatedNameToUuidMap
    }

    val (creates, updates) = entityBasedCreatesAndUpdates(withIds, withNames, currentMap, nameToUuidMap, create, update)

    if (creates.nonEmpty) {
      table.insert(creates)
    }
    if (updates.nonEmpty) {
      table.update(updates)
    }

    val allCreateIds = (creates.map(_.entityId) ++ entCreateIds).toSet
    val allUpdateIds = (updates.map(_.entityId) ++ entUpdateIds).toSet

    val allUuids = (currentState.map(_._1) ++ entCreateIds).toSet

    (allCreateIds, allUpdateIds, allUuids, nameToUuidMap)
  }

  def putEntityBased[DbType <: EntityBased, TemplateInfo, ProtoType](
    notifier: ModelNotifier,
    withIds: Seq[IdentifiedEntityTemplate[TemplateInfo]],
    withNames: Seq[NamedEntityTemplate[TemplateInfo]],
    coreType: String,
    create: (UUID, TemplateInfo) => DbType,
    update: (UUID, DbType, TemplateInfo) => Option[DbType],
    query: (Seq[UUID], Seq[String]) => Seq[ProtoType],
    uuidFromProto: ProtoType => ModelUUID,
    table: Table[DbType],
    allowCreate: Boolean = true,
    updateFilter: Option[EntityFilter] = None): Seq[ProtoType] = {

    val ids = withIds.map(_.uuid)
    val names = withNames.map(_.name)

    val (allCreateIds, allUpdateIds, _, _) = performEntityBasedCreateAndUpdates(notifier, withIds, withNames, coreType, create, update, table, allowCreate, updateFilter)

    val allResults = query(ids, names)
    lazy val resultMap: Map[UUID, ProtoType] = allResults.map(proto => (protoUUIDToUuid(uuidFromProto(proto)), proto)).toMap

    allCreateIds.flatMap(resultMap.get).foreach(notifier.notify(Created, _))
    allUpdateIds.flatMap(resultMap.get).foreach(notifier.notify(Updated, _))

    allResults
  }

  def deleteEntityBased[A <: EntityBased, C](
    notifier: ModelNotifier,
    ids: Seq[UUID],
    query: Seq[UUID] => Seq[C],
    table: Table[A],
    filter: Option[EntityFilter] = None): Seq[C] = {

    filter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to delete restricted agents")
      }
    }

    val results = query(ids)

    SquerylEntityModel.deleteEntityBased(notifier, ids)

    table.deleteWhere(row => row.entityId in ids)

    results.foreach(notifier.notify(Deleted, _))

    results
  }
}
