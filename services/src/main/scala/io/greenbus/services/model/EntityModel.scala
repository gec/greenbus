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

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.Query
import org.squeryl.dsl.ast.{ ExpressionNode, LogicalBoolean }
import io.greenbus.client.service.proto.Model._
import io.greenbus.client.service.proto.ModelRequests.EntityKeyPair
import io.greenbus.services.authz.EntityFilter
import io.greenbus.services.core.EntityKeyValueWithEndpoint
import io.greenbus.services.data.ServicesSchema._
import io.greenbus.services.data.{ EntityEdgeRow, EntityKeyStoreRow, EntityRow, EntityTypeRow }
import io.greenbus.services.framework.{ Created, Deleted, ModelNotifier, Updated }

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.immutable.VectorBuilder

object EntityModel {

  class DiamondException(msg: String) extends ModelInputException(msg)

  case class TypeParams(includeTypes: Seq[String], matchTypes: Seq[String], filterNotTypes: Seq[String])

  val reservedTypes = Set("Point", "Command", "Endpoint", "ConfigFile")
}

trait EntityModel {
  import io.greenbus.services.model.EntityModel._

  def fullQuery(typeParams: TypeParams, lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Entity]
  def keyQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[Entity]

  def keyQueryFilter(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[UUID]

  def idsRelationFlatQuery(ids: Seq[UUID], relation: String, descendantOf: Boolean, endTypes: Seq[String], depthLimit: Option[Int], lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Entity]
  def namesRelationFlatQuery(names: Seq[String], relation: String, descendantOf: Boolean, endTypes: Seq[String], depthLimit: Option[Int], lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Entity]
  def typesRelationFlatQuery(types: Seq[String], relation: String, descendantOf: Boolean, endTypes: Seq[String], depthLimit: Option[Int], lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Entity]

  def putEntities(notifier: ModelNotifier, withIds: Seq[(UUID, String, Set[String])], withNames: Seq[(String, Set[String])], allowCreate: Boolean = true, updateFilter: Option[EntityFilter] = None): Seq[Entity]
  def deleteEntities(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[Entity]

  def edgeQuery(parents: Seq[UUID], relations: Seq[String], children: Seq[UUID], depthLimit: Option[Int], lastId: Option[Long], pageSize: Int, filter: Option[EntityFilter] = None): Seq[EntityEdge]

  //def putEdgesOnParent(parent: UUID, children: Seq[UUID], relation: String): Seq[EntityEdge]
  def putEdges(notifier: ModelNotifier, edgeList: Seq[(UUID, UUID)], relation: String): Seq[EntityEdge]
  def deleteEdgesFromParent(notifier: ModelNotifier, parent: UUID, children: Seq[UUID], relation: String): Seq[EntityEdge]
  def deleteEdges(notifier: ModelNotifier, edgeList: Seq[(UUID, UUID)], relation: String): Seq[EntityEdge]

  //def getKeyValues(ids: Seq[UUID], key: String, filter: Option[EntityFilter] = None): Seq[(UUID, Array[Byte])]
  //def putKeyValues(values: Seq[(UUID, Array[Byte])], key: String, filter: Option[EntityFilter] = None): (Seq[(UUID, Array[Byte])], Seq[UUID], Seq[UUID])
  //def deleteKeyValues(ids: Seq[UUID], key: String, filter: Option[EntityFilter] = None): Seq[(UUID, Array[Byte])]

  def getKeyValues2(set: Seq[(UUID, String)], filter: Option[EntityFilter] = None): Seq[EntityKeyValue]
  def getKeyValuesForUuids(set: Seq[UUID], filter: Option[EntityFilter] = None): Seq[EntityKeyValue]
  def getKeys(ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[EntityKeyPair]
  def putKeyValues2(notifier: ModelNotifier, set: Seq[(UUID, String, StoredValue)], allowCreate: Boolean = true, updateFilter: Option[EntityFilter] = None): Seq[EntityKeyValue]
  def deleteKeyValues2(notifier: ModelNotifier, set: Seq[(UUID, String)], filter: Option[EntityFilter] = None): Seq[EntityKeyValue]
}

object SquerylEntityModel extends EntityModel {
  import io.greenbus.services.model.EntityModel._
  import io.greenbus.services.model.ModelHelpers._
  import io.greenbus.services.model.UUIDHelpers._

  private def entitiesFromJoin(results: Seq[(EntityRow, Option[String])]): Seq[Entity] = {
    groupSortedAndPreserveOrder(results).map {
      case (row, types) =>
        val flatTypes = types.flatten
        Entity.newBuilder
          .setUuid(row.id)
          .setName(row.name)
          .addAllTypes(flatTypes)
          .build()
    }
  }

  private def simpleEntity(id: UUID, name: String, types: Seq[String]): Entity = {
    Entity.newBuilder
      .setUuid(id)
      .setName(name)
      .addAllTypes(types)
      .build()
  }

  private def filterEntities(ent: EntityRow, filter: Option[EntityFilter]): LogicalBoolean = {
    filter match {
      case None => true === true
      case Some(f) => f(ent.id)
    }
  }

  def allQuery(last: Option[UUID], pageSize: Int): Seq[Entity] = {

    val sql = last match {
      case None =>
        join(entities, entityTypes.leftOuter)((ent, typ) =>
          select(ent, typ.map(_.entType))
            orderBy (ent.id)
            on (Some(ent.id) === typ.map(_.entityId))).page(0, pageSize)
      case Some(lastUuid) =>
        join(entities, entityTypes.leftOuter)((ent, typ) =>
          where(ent.id > lastUuid)
            select (ent, typ.map(_.entType))
            orderBy (ent.id)
            on (Some(ent.id) === typ.map(_.entityId))).page(0, pageSize)
    }

    entitiesFromJoin(sql.toSeq)
  }

  def typeMatchClause(ent: EntityRow, matchTypes: Seq[String]): LogicalBoolean = {
    if (matchTypes.nonEmpty) {
      def typeExists(typ: String): LogicalBoolean = exists(from(entityTypes)(t =>
        where(t.entityId === ent.id and t.entType === typ)
          select (t.entityId)))

      matchTypes.map(typeExists).reduceLeft(logicalAnd)
      //matchTypes.map(typeExists).reduceLeft((a, b) => new BinaryOperatorNodeLogicalBoolean(a, b, "and"))
    } else {
      true === true
    }
  }

  def entTypesClause(ent: EntityRow, params: TypeParams): LogicalBoolean = {
    (ent.id in from(entityTypes)(t =>
      where(t.entType in params.includeTypes)
        select (t.entityId))).inhibitWhen(params.includeTypes.isEmpty) and
      (ent.id notIn from(entityTypes)(t =>
        where(t.entType in params.filterNotTypes)
          select (t.entityId))).inhibitWhen(params.filterNotTypes.isEmpty) and
      SquerylEntityModel.typeMatchClause(ent, params.matchTypes).inhibitWhen(params.matchTypes.isEmpty)
  }

  def guardFunc(lastUuid: Option[UUID], lastName: Option[String], pageByName: Boolean = true): EntityRow => LogicalBoolean = {
    if (pageByName) {

      val nameOpt = lastName match {
        case Some(name) => Some(name)
        case None =>
          lastUuid match {
            case Some(uuid) => entities.where(t => t.id === uuid).headOption.map(_.name)
            case None => None
          }
      }

      def pageGuard(ent: EntityRow) = ent.name gt nameOpt.?
      pageGuard

    } else {

      val uuidOpt = lastUuid match {
        case Some(uuid) => Some(uuid)
        case None =>
          lastName match {
            case Some(name) => entities.where(t => t.name === name).headOption.map(_.id)
            case None => None
          }
      }

      def pageGuard(ent: EntityRow) = ent.id > uuidOpt.?
      pageGuard
    }
  }

  def fullQuery(typeParams: TypeParams, lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Entity] = {

    val guard = SquerylEntityModel.guardFunc(lastUuid, lastName, pageByName)

    def ordering(ent: EntityRow): ExpressionNode = {
      if (pageByName) {
        ent.name
      } else {
        ent.id
      }
    }

    val entSet = from(entities)(ent =>
      where(filterEntities(ent, filter) and
        guard(ent) and
        (ent.id in from(entityTypes)(t =>
          where(t.entType in typeParams.includeTypes)
            select (t.entityId))).inhibitWhen(typeParams.includeTypes.isEmpty) and
        (ent.id notIn from(entityTypes)(t =>
          where(t.entType in typeParams.filterNotTypes)
            select (t.entityId))).inhibitWhen(typeParams.filterNotTypes.isEmpty) and
        typeMatchClause(ent, typeParams.matchTypes).inhibitWhen(typeParams.matchTypes.isEmpty))
        select (ent.id)
        orderBy (ordering(ent))).page(0, pageSize)

    val entList = entSet.toList

    val sql = join(entities, entityTypes.leftOuter)((ent, typ) =>
      where(ent.id in entList)
        select (ent, typ.map(_.entType))
        orderBy (ordering(ent))
        on (Some(ent.id) === typ.map(_.entityId)))

    entitiesFromJoin(sql.toVector)
  }

  def keyQueryFilter(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[UUID] = {
    from(entities)(ent =>
      where(filterEntities(ent, filter).inhibitWhen(filter.isEmpty) and
        ((ent.id in uuids).inhibitWhen(uuids.isEmpty) or
          (ent.name in names).inhibitWhen(names.isEmpty)))
        select (ent.id)).toList
  }

  def keyQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[Entity] = {
    if (uuids.isEmpty && names.isEmpty) {
      throw new ModelInputException("Must include uuids or names for querying")
    }

    val sql = join(entities, entityTypes.leftOuter)((ent, typ) =>
      where(filterEntities(ent, filter).inhibitWhen(filter.isEmpty) and
        ((ent.id in uuids).inhibitWhen(uuids.isEmpty) or
          (ent.name in names).inhibitWhen(names.isEmpty)))
        select (ent, typ.map(_.entType))
        orderBy (ent.id)
        on (Some(ent.id) === typ.map(_.entityId)))

    entitiesFromJoin(sql.toSeq)
  }

  def keyQueryAbstract(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Query[EntityRow] = {
    if (uuids.isEmpty && names.isEmpty) {
      throw new ModelInputException("Must include uuids or names for querying")
    }

    from(entities)(ent =>
      where(filterEntities(ent, filter).inhibitWhen(filter.isEmpty) and
        ((ent.id in uuids).inhibitWhen(uuids.isEmpty) or
          (ent.name in names).inhibitWhen(names.isEmpty)))
        select (ent))
  }

  def idQuery(uuids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[Entity] = {
    keyQuery(uuids, Nil, filter)
  }

  def nameQuery(names: Seq[String], filter: Option[EntityFilter] = None): Seq[Entity] = {
    keyQuery(Nil, names, filter)
  }

  def idsRelationFlatQuery(ids: Seq[UUID], relation: String, descendantOf: Boolean, endTypes: Seq[String], depthLimit: Option[Int], lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Entity] = {
    val start = from(entities)(e => where(filterEntities(e, filter) and (e.id in ids)) select (e.id))

    relationFlatQuery(start, relation, descendantOf, endTypes, depthLimit, lastUuid, lastName, pageSize, pageByName, filter)
  }

  def namesRelationFlatQuery(names: Seq[String], relation: String, descendantOf: Boolean, endTypes: Seq[String], depthLimit: Option[Int], lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Entity] = {
    val start = from(entities)(e => where(filterEntities(e, filter) and (e.name in names)) select (e.id))

    relationFlatQuery(start, relation, descendantOf, endTypes, depthLimit, lastUuid, lastName, pageSize, pageByName, filter)
  }

  def typesRelationFlatQuery(types: Seq[String], relation: String, descendantOf: Boolean, endTypes: Seq[String], depthLimit: Option[Int], lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Entity] = {
    val start = from(entities, entityTypes)((ent, typ) =>
      where(filterEntities(ent, filter) and ent.id === typ.entityId and (typ.entType in types))
        select (ent.id))

    relationFlatQuery(start, relation, descendantOf, endTypes, depthLimit, lastUuid, lastName, pageSize, pageByName, filter)
  }

  private def relationFlatQuery(startQuery: Query[UUID], relation: String, descendantOf: Boolean, endTypes: Seq[String], depthLimit: Option[Int], lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Entity] = {

    val guard = SquerylEntityModel.guardFunc(lastUuid, lastName, pageByName)

    def ordering(ent: EntityRow): ExpressionNode = {
      if (pageByName) {
        ent.name
      } else {
        ent.id
      }
    }

    // Note: inhibitWhen appears not to work on exists()
    val entSet = endTypes.isEmpty match {
      case true =>
        from(entities, edges)((ent, edge) =>
          where(filterEntities(ent, filter) and
            edge.relationship === relation and
            (edge.distance lte depthLimit.?) and
            ((edge.parentId in startQuery) and
              (edge.childId === ent.id)).inhibitWhen(!descendantOf) and
              ((edge.childId in startQuery) and
                (edge.parentId === ent.id)).inhibitWhen(descendantOf))
            select (ent.id))
      case false =>
        from(entities, edges)((ent, edge) =>
          where(filterEntities(ent, filter) and
            edge.relationship === relation and
            (edge.distance lte depthLimit.?) and
            ((edge.parentId in startQuery) and
              (edge.childId === ent.id)).inhibitWhen(!descendantOf) and
              ((edge.childId in startQuery) and
                (edge.parentId === ent.id)).inhibitWhen(descendantOf) and
                exists(
                  from(entityTypes)(typ =>
                    where(typ.entityId === ent.id and
                      (typ.entType in endTypes))
                      select (typ.entityId))))
            select (ent.id))
    }

    val results = join(entities, entityTypes.leftOuter)((ent, typ) =>
      where(guard(ent) and (ent.id in entSet))
        select (ent, typ.map(_.entType))
        orderBy (ordering(ent))
        on (Some(ent.id) === typ.map(_.entityId))).page(0, pageSize)

    entitiesFromJoin(results.toSeq)
  }

  private def entityIdFilter(id: UUID, filter: Option[EntityFilter]): LogicalBoolean = {
    filter match {
      case None => true === true
      case Some(filt) => filt(id)
    }
  }

  def edgeQuery(parents: Seq[UUID], relations: Seq[String], children: Seq[UUID], depthLimit: Option[Int], lastId: Option[Long], pageSize: Int, filter: Option[EntityFilter] = None): Seq[EntityEdge] = {

    val edgeResults =
      from(edges)(edge =>
        where((edge.id > lastId.?) and
          (entityIdFilter(edge.parentId, filter) and
            entityIdFilter(edge.childId, filter)).inhibitWhen(filter.isEmpty) and
            (edge.parentId in parents).inhibitWhen(parents.isEmpty) and
            (edge.childId in children).inhibitWhen(children.isEmpty) and
            (edge.relationship in relations).inhibitWhen(relations.isEmpty) and
            (edge.distance lte depthLimit.?))
          select (edge)
          orderBy (edge.id)).page(0, pageSize).toVector

    edgeResults map edgeRowToProto
  }

  def putEntity(notifier: ModelNotifier, name: String, types: Seq[String], preserveTypes: Boolean, mustHaveAllTypes: Boolean, mustOnlyHaveTypes: Boolean, allowCreate: Boolean = true, filter: Option[EntityFilter] = None): EntityRow = {

    val entOption = entities.where(e => e.name === name).toSeq.headOption

    entOption match {
      case Some(existing) => {

        filter.foreach { filt =>
          if (filt.isOutsideSet(existing.id)) {
            throw new ModelPermissionException("Don't have permissions for entity")
          }
        }

        val existingTypes =
          from(entityTypes)(t =>
            where(t.entityId === existing.id)
              select (t.entType)).toSeq

        val existingTypesSet = existingTypes.toSet
        val wantedTypesSet = types.toSet

        val onlyInExisting = existingTypesSet &~ wantedTypesSet
        if (mustOnlyHaveTypes && onlyInExisting.nonEmpty) {
          throw new ModelInputException("Existing entity had extra types, refusing to overwrite")
        }

        val onlyInWanted = wantedTypesSet &~ existingTypesSet
        if (mustHaveAllTypes && onlyInWanted.nonEmpty) {
          throw new ModelInputException("Existing entity did not include types, refusing to overwrite")
        }

        if (!preserveTypes && onlyInExisting.nonEmpty) {
          entityTypes.deleteWhere(t => t.entityId === existing.id and (t.entType in onlyInExisting))
        }

        if (onlyInWanted.nonEmpty) {
          val typeInserts = onlyInWanted.map(EntityTypeRow(existing.id, _))
          entityTypes.insert(typeInserts)
        }

        val allTypes = if (!preserveTypes) wantedTypesSet else wantedTypesSet ++ onlyInExisting

        if ((!preserveTypes && onlyInExisting.nonEmpty) || onlyInWanted.nonEmpty) {
          notifier.notify(Updated, simpleEntity(existing.id, name, allTypes.toSeq))
        }

        existing
      }
      case None => {
        if (!allowCreate) {
          throw new ModelPermissionException("Entity create not allowed")
        }

        val ent = entities.insert(EntityRow(UUID.randomUUID(), name))
        val typeInserts = types.map(EntityTypeRow(ent.id, _))
        entityTypes.insert(typeInserts)

        notifier.notify(Created, simpleEntity(ent.id, name, types))

        ent
      }
    }
  }

  def entitiesWithTypes(ents: Query[EntityRow]): Seq[(EntityRow, Seq[String])] = {

    val joins = join(ents, entityTypes.leftOuter)((ent, typ) =>
      select(ent, typ.map(_.entType))
        orderBy (ent.id)
        on (Some(ent.id) === typ.map(_.entityId)))

    groupSortedAndPreserveOrder(joins.toSeq)
      .map { case (row, optTypes) => (row, optTypes.flatten) }
  }

  private def typeDiff(existingTypes: Set[String], wantedTypes: Set[String]): (Set[String], Set[String]) = {

    lazy val existingTypesSet = existingTypes.toSet
    lazy val wantedTypesSet = wantedTypes.toSet
    lazy val onlyInWanted = wantedTypesSet &~ existingTypesSet

    val toBeRemoved = existingTypesSet &~ wantedTypesSet
    val toBeAdded = onlyInWanted

    (toBeAdded, toBeRemoved)
  }

  private case class EntityPutResult(created: Seq[EntityRow],
      updated: Seq[UUID],
      updatedRows: Seq[EntityRow],
      typeInserts: Seq[EntityTypeRow],
      typeDeletes: Seq[EntityTypeRow]) {

    def merge(rhs: EntityPutResult): EntityPutResult = {
      EntityPutResult(
        created ++ rhs.created,
        updated ++ rhs.updated,
        updatedRows ++ rhs.updatedRows,
        typeInserts ++ rhs.typeInserts,
        typeDeletes ++ rhs.typeDeletes)
    }
  }

  private def computePutEntities(
    withIds: Seq[(UUID, String, Set[String])],
    withNames: Seq[(String, Set[String])],
    enforceReserved: Boolean,
    allowCreate: Boolean = true,
    updateFilter: Option[EntityFilter] = None): EntityPutResult = {

    val specifiedIds = withIds.map(_._1)
    val specifiedNames = withNames.map(_._1)

    val existingQuery = entities.where(ent => (ent.id in specifiedIds) or (ent.name in specifiedNames))

    val existing: Seq[(EntityRow, Seq[String])] = entitiesWithTypes(existingQuery)

    if (enforceReserved) {
      val foundReservedExisting = existing.flatMap(_._2).filter(reservedTypes.contains)
      if (foundReservedExisting.nonEmpty) {
        throw new ModelInputException("Cannot modify entities with reserved types: " + foundReservedExisting.mkString("(", ", ", ")") + ", use specific service")
      }

      val foundReservedUpdates = (withIds.flatMap(_._3) ++ withNames.flatMap(_._2)).filter(reservedTypes.contains)
      if (foundReservedUpdates.nonEmpty) {
        throw new ModelInputException("Cannot add reserved types: " + foundReservedUpdates.mkString("(", ", ", ")") + ", use specific service")
      }
    }

    val existingIdSet = existing.map(_._1.id).toSet
    val existingNameSet = existing.map(_._1.name).toSet

    val notExistingWithIds = withIds.filterNot(entry => existingIdSet.contains(entry._1))
    val notExistingWithNames = withNames.filterNot(entry => existingNameSet.contains(entry._1))

    if ((notExistingWithIds.nonEmpty || notExistingWithNames.nonEmpty) && !allowCreate) {
      throw new ModelPermissionException("Must have blanket create permissions to create entities")
    }

    val withIdsById: Map[UUID, (UUID, String, Set[String])] = withIds.map(tup => (tup._1, tup)).toMap
    val withNamesByName: Map[String, (String, Set[String])] = withNames.map(tup => (tup._1, tup)).toMap

    val updates: Seq[(UUID, Option[EntityRow], Set[String], Set[String])] = existing.flatMap {
      case (currentRow, currentTypes) =>
        val id = currentRow.id
        val currentName = currentRow.name

        val update: Option[(String, Set[String])] = withIdsById.get(id).map(tup => (tup._2, tup._3)).orElse(withNamesByName.get(currentName))

        update.flatMap {
          case (updateName, updateTypes) =>

            val rowUpdate = if (currentName != updateName) Some(currentRow.copy(name = updateName)) else None

            val (adds, deletes) = typeDiff(currentTypes.toSet, updateTypes)

            if (rowUpdate.nonEmpty || adds.nonEmpty || deletes.nonEmpty) {
              Some((id, rowUpdate, adds, deletes))
            } else {
              None
            }
        }
    }

    val idRowAndTypeInserts: Seq[(EntityRow, Seq[EntityTypeRow])] = notExistingWithIds.map {
      case (id, name, typeSet) => (EntityRow(id, name), typeSet.toSeq.map(EntityTypeRow(id, _)))
    }

    val nameRowAndTypeInserts: Seq[(EntityRow, Seq[EntityTypeRow])] = notExistingWithNames.map {
      case (name, typeSet) =>
        val uuid = UUID.randomUUID()
        val typeInserts = typeSet.toSeq.map(EntityTypeRow(uuid, _))
        (EntityRow(uuid, name), typeInserts)
    }

    val rowInserts = idRowAndTypeInserts.map(_._1) ++ nameRowAndTypeInserts.map(_._1)

    val updatedIds = updates.map(_._1)

    val rowUpdates = updates.flatMap(_._2)

    val typeInserts = updates.flatMap(tup => tup._3.toSeq.map(EntityTypeRow(tup._1, _))) ++ idRowAndTypeInserts.flatMap(_._2) ++ nameRowAndTypeInserts.flatMap(_._2)

    val typeDeletes = updates.flatMap(tup => tup._4.map(EntityTypeRow(tup._1, _)))

    EntityPutResult(rowInserts, updatedIds, rowUpdates, typeInserts, typeDeletes)
  }

  private def performPutEntities(notifier: ModelNotifier, result: EntityPutResult): Seq[Entity] = {
    if (result.typeDeletes.nonEmpty) {
      result.typeDeletes.foreach { delRow =>
        entityTypes.deleteWhere(t => t.entityId === delRow.entityId and t.entType === delRow.entType)
      }
    }

    if (result.created.nonEmpty) {
      entities.insert(result.created)
    }
    if (result.updatedRows.nonEmpty) {
      entities.update(result.updatedRows)
    }
    if (result.typeInserts.nonEmpty) {
      entityTypes.insert(result.typeInserts)
    }

    val createdIds = result.created.map(_.id)
    val updatedIds = result.updated

    val allIds = createdIds ++ updatedIds

    val results = if (allIds.nonEmpty) {
      keyQuery(createdIds ++ updatedIds, Nil)
    } else {
      Nil
    }

    val resultMap: Map[UUID, Entity] = results.map(r => (protoUUIDToUuid(r.getUuid), r)).toMap

    createdIds.flatMap(resultMap.get).foreach(notifier.notify(Created, _))
    updatedIds.flatMap(resultMap.get).foreach(notifier.notify(Updated, _))

    results
  }

  def findOrCreateEntities(notifier: ModelNotifier,
    withIds: Seq[(UUID, String, Set[String])],
    withNames: Seq[(String, Set[String])],
    allowCreate: Boolean = true,
    updateFilter: Option[EntityFilter] = None): (Seq[UUID], Seq[UUID], Seq[Entity]) = {

    val computeResult = computePutEntities(withIds, withNames, false, allowCreate, updateFilter)

    val entities = performPutEntities(notifier, computeResult)

    (computeResult.created.map(_.id), computeResult.updated, entities)
  }

  def putEntities(notifier: ModelNotifier,
    withIds: Seq[(UUID, String, Set[String])],
    withNames: Seq[(String, Set[String])],
    allowCreate: Boolean = true,
    updateFilter: Option[EntityFilter] = None): Seq[Entity] = {

    val specifiedIds = withIds.map(_._1)
    val specifiedNames = withNames.map(_._1)

    // Reject them even if they're NOT changed, otherwise it's an information leak
    // about the current name/types (rather than just existence, which is unavoidable)
    updateFilter.foreach { filter =>

      val existingQuery = from(entities)(ent =>
        where((ent.id in specifiedIds).inhibitWhen(specifiedIds.isEmpty) or
          (ent.name in specifiedNames).inhibitWhen(specifiedNames.isEmpty))
          select (ent))

      if (filter.anyOutsideSet(existingQuery)) {
        throw new ModelPermissionException("Tried to update restricted entities")
      }
    }

    if (withIds.isEmpty && withNames.isEmpty) {
      throw new ModelInputException("Must specify at least one entity to query for")
    }

    val result = computePutEntities(withIds, withNames, true, allowCreate, updateFilter)

    performPutEntities(notifier, result)

    keyQuery(specifiedIds, specifiedNames)
  }

  def deleteEntities(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[Entity] = {
    deleteByIds(notifier, ids, true, filter)
  }

  def deleteEntityBased(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[Entity] = {
    deleteByIds(notifier, ids, false, filter)
  }

  private def deleteByIds(notifier: ModelNotifier, ids: Seq[UUID], enforceReserved: Boolean, filter: Option[EntityFilter] = None): Seq[Entity] = {
    filter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to delete restricted entities")
      }
    }

    val results = idQuery(ids)

    if (enforceReserved) {
      val foundReserved = results.flatMap(_.getTypesList.toSeq).filter(reservedTypes.contains)
      if (foundReserved.nonEmpty) {
        throw new ModelInputException("Cannot delete entities with reserved types: " + foundReserved.mkString("(", ", ", ")") + ", use specific service")
      }
    }

    entities.deleteWhere(t => t.id in ids)
    entityTypes.deleteWhere(t => t.entityId in ids)

    ids.foreach(deleteAllEdgesFromNode(notifier, _))

    deleteAllKeyValues2(notifier, ids)

    results.foreach(notifier.notify(Deleted, _))

    results
  }

  private def checkForDiamonds(parent: UUID, children: Seq[UUID], relation: String) {
    val childrenOfChildren = from(edges)(e =>
      where((e.parentId in children) and e.relationship === relation)
        select (e.childId))

    val ourParents = from(edges)(e =>
      where(e.childId === parent and e.relationship === relation)
        select (e.parentId))

    val existingPathToChildren = from(edges)(e =>
      where((e.parentId === parent or (e.parentId in ourParents)) and
        ((e.childId in children) or (e.childId in childrenOfChildren)) and
        e.relationship === relation)
        select ((e.parentId, e.childId, e.relationship))).page(0, 3).toSeq

    if (existingPathToChildren.nonEmpty) {
      val summary = existingPathToChildren.map {
        case (parent, child, relation) => s"$parent -($relation)-> $child"
      }.mkString("", ", ", " ...")
      throw new DiamondException("Edge insertion would create diamond: " + summary)
    }
  }

  private def edgeRowToProto(edgeRow: EntityEdgeRow): EntityEdge = {
    EntityEdge.newBuilder
      .setId(ModelID.newBuilder.setValue(edgeRow.id.toString))
      .setParent(edgeRow.parentId)
      .setChild(edgeRow.childId)
      .setRelationship(edgeRow.relationship)
      .setDistance(edgeRow.distance)
      .build
  }

  def deleteAllEdgesFromNode(notifier: ModelNotifier, node: UUID): Seq[EntityEdge] = {

    val relationships =
      from(edges)(e =>
        where(e.parentId === node or e.childId === node)
          select (e.relationship)).distinct.toList

    val relationEdgeSets: Seq[Query[EntityEdgeRow]] = relationships.map { relation =>
      // Node and its parents
      val upperSet =
        from(edges)(e =>
          where(e.childId === node and
            e.relationship === relation)
            select (e.parentId))

      // Node and its children
      val lowerSet =
        from(edges)(e =>
          where(e.parentId === node and
            e.relationship === relation)
            select (e.childId))

      // All paths that go through node (simple paths between all nodes)
      from(edges)(e =>
        where(((e.parentId in upperSet) or e.parentId === node) and
          ((e.childId in lowerSet) or (e.childId === node)) and
          e.relationship === relation)
          select (e))
    }

    val results = relationEdgeSets.flatMap(_.toList map edgeRowToProto)

    relationEdgeSets.foreach(edges.delete)

    results.foreach(notifier.notify(Deleted, _))

    results
  }

  def deleteEdgesFromParent(notifier: ModelNotifier, parent: UUID, children: Seq[UUID], relation: String): Seq[EntityEdge] = {

    // Parent and parents of parent
    val upper = from(edges)(e =>
      where((e.childId === parent or
        (e.parentId === parent and (e.childId in children))) and
        e.relationship === relation)
        select (e.parentId))

    // Children and children of children
    val lower = from(edges)(e =>
      where((e.parentId in children or
        (e.parentId === parent and (e.childId in children))) and
        e.relationship === relation)
        select (e.childId))

    // Because we have simple paths between all nodes, deleted edges completely separate
    // the component the parent is attached to from the child components
    val allEdges = from(edges)(e =>
      where(e.relationship === relation and
        (e.childId in lower) and
        (e.parentId in upper))
        select (e))

    val results = allEdges.toSeq map edgeRowToProto

    edges.delete(allEdges)

    results.foreach(notifier.notify(Deleted, _))

    results
  }

  def deleteEdges(notifier: ModelNotifier, edgeList: Seq[(UUID, UUID)], relation: String): Seq[EntityEdge] = {

    val parentGrouped: Map[UUID, Seq[UUID]] = edgeList.groupBy(_._1).mapValues(_.map(_._2))

    parentGrouped.toSeq.flatMap {
      case (parent, children) =>
        deleteEdgesFromParent(notifier, parent, children, relation)
    }
  }

  def existingEdges(edgeList: Seq[(UUID, UUID)], relation: String): Seq[EntityEdgeRow] = {

    val parentGrouped: Map[UUID, Seq[UUID]] = edgeList.groupBy(_._1).mapValues(_.map(_._2))

    parentGrouped.flatMap { case (parent, children) => existingEdges(parent, children, relation).toSeq }.toSeq
  }

  def existingEdges(parent: UUID, children: Seq[UUID], relation: String): Seq[EntityEdgeRow] = {
    from(edges)(e =>
      where(e.parentId === parent and
        (e.childId in children) and
        e.relationship === relation and
        e.distance === 1)
        select (e)).toSeq
  }

  def calculateEdgeAdditions(parent: UUID, child: UUID, parentsOfParent: Seq[(UUID, Int)], childrenOfChild: Seq[(UUID, Int)]): Seq[(UUID, UUID, Int)] = {

    val upper: Seq[(UUID, Int)] = parentsOfParent :+ (parent, 0)
    val lower: Seq[(UUID, Int)] = childrenOfChild :+ (child, 0)

    upper.flatMap {
      case (parentId, height) =>
        lower.map {
          case (childId, depth) =>
            (parentId, childId, height + depth + 1)
        }
    }
  }

  def groupEdgeList(list: Seq[(UUID, UUID, Int)]): (Map[UUID, Seq[(UUID, Int)]], Map[UUID, Seq[(UUID, Int)]]) = {
    val childToParents = list.groupBy(_._2).mapValues(_.map(tup => (tup._1, tup._3)))
    val parentToChildren = list.groupBy(_._1).mapValues(_.map(tup => (tup._2, tup._3)))

    (childToParents, parentToChildren)
  }

  private def mergeMaps[A, B](a: Map[A, Seq[B]], b: Map[A, Seq[B]]): Map[A, Seq[B]] = {
    (a.keySet ++ b.keySet).map { key =>
      (key, a.get(key).getOrElse(Nil) ++ b.get(key).getOrElse(Nil))
    }.toMap
  }

  @tailrec
  private def findEdgeAdditions(
    results: List[(UUID, UUID, Int)],
    toAdd: List[(UUID, UUID)],
    childToParents: Map[UUID, Seq[(UUID, Int)]],
    parentsToChildren: Map[UUID, Seq[(UUID, Int)]]): List[(UUID, UUID, Int)] = {

    toAdd match {
      case Nil => results
      case (parent, child) :: tail =>

        val parentSet = childToParents.get(parent).getOrElse(Nil)
        val childSet = parentsToChildren.get(child).getOrElse(Nil)

        val added = calculateEdgeAdditions(parent, child, parentSet, childSet)

        val (childMapAdditions, parentMapAdditions) = groupEdgeList(added)

        findEdgeAdditions(results ++ added, tail, mergeMaps(childToParents, childMapAdditions), mergeMaps(parentsToChildren, parentMapAdditions))
    }
  }

  def putEdges(notifier: ModelNotifier, edgeList: Seq[(UUID, UUID)], relation: String): Seq[EntityEdge] = {

    if (edgeList.exists(tup => tup._1 == tup._2)) {
      throw new ModelInputException("Edges must have different parent and child UUIDs")
    }

    val edgeListDistinct = edgeList.distinct

    val existing = existingEdges(edgeListDistinct, relation)
    val existingSet: Set[(UUID, UUID)] = existing.map(r => (r.parentId, r.childId)).toSet
    val nonExistent = edgeListDistinct.filterNot(existingSet.contains)

    val (parents, children) = nonExistent.unzip

    val parentsAndTheirParents: Seq[(UUID, UUID, Int)] =
      from(edges)(e =>
        where((e.childId in parents) and
          e.relationship === relation)
          select (e.childId, e.parentId, e.distance)).toVector

    val parentsOfParents: Map[UUID, Seq[(UUID, Int)]] = parentsAndTheirParents.groupBy(_._1).mapValues(_.map(tup => (tup._2, tup._3)))

    val childrenAndTheirChildren: Seq[(UUID, UUID, Int)] =
      from(edges)(e =>
        where((e.parentId in children) and
          e.relationship === relation)
          select (e.parentId, e.childId, e.distance)).toVector

    val childrenOfChildren: Map[UUID, Seq[(UUID, Int)]] = childrenAndTheirChildren.groupBy(_._1).mapValues(_.map(tup => (tup._2, tup._3)))

    val additions = findEdgeAdditions(Nil, nonExistent.toList, parentsOfParents, childrenOfChildren)

    val rowAdditions = additions.map {
      case (parent, child, dist) => EntityEdgeRow(0, parent, child, relation, dist)
    }

    val insertedRows = try {
      //edges.insert(rowAdditions)
      rowAdditions.map(edges.insert)
    } catch {
      case ex: RuntimeException =>
        // TODO: HACK!! this might have been a disconnect, but we're going to assume it was a 'unique_violation' in postgres (23505), otherwise user gets unhelpful internal service error
        throw new DiamondException("Edge insertion would create diamond")
    }

    val insertedProtos = insertedRows map edgeRowToProto
    val existingProtos = existing map edgeRowToProto

    insertedProtos.foreach(notifier.notify(Created, _))

    insertedProtos ++ existingProtos
  }

  def getKeyValues(ids: Seq[UUID], key: String, filter: Option[EntityFilter] = None): Seq[(UUID, Array[Byte])] = {

    from(entities, entityKeyValues)((ent, kv) =>
      where(EntityFilter.optional(filter, ent).inhibitWhen(filter.isEmpty) and
        (ent.id in ids) and
        ent.id === kv.uuid and
        kv.key === key)
        select (ent.id, kv.data)).toSeq
  }

  def getAllKeyValues(ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[(UUID, String, Array[Byte])] = {

    from(entities, entityKeyValues)((ent, kv) =>
      where(EntityFilter.optional(filter, ent).inhibitWhen(filter.isEmpty) and
        (ent.id in ids) and
        ent.id === kv.uuid)
        select (ent.id, kv.key, kv.data)).toSeq
  }

  def putKeyValues(values: Seq[(UUID, Array[Byte])], key: String, filter: Option[EntityFilter] = None): (Seq[(UUID, Array[Byte])], Seq[UUID], Seq[UUID]) = {

    val ids = values.map(_._1)
    filter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to update restricted entities")
      }
    }

    val current: Map[UUID, EntityKeyStoreRow] =
      from(entities, entityKeyValues)((ent, kv) =>
        where((ent.id in ids) and
          kv.key === key and
          ent.id === kv.uuid)
          select (ent.id, kv)).toList.toMap

    val isCreatedAndRow: Seq[(Boolean, (UUID, EntityKeyStoreRow))] = values.flatMap {
      case (id, bytes) =>
        current.get(id) match {
          case None =>
            Some(true, (id, EntityKeyStoreRow(0, id, key, bytes)))
          case Some(ks) =>
            if (!java.util.Arrays.equals(bytes, ks.data)) {
              Some(false, (id, ks.copy(data = bytes)))
            } else {
              None
            }
        }
    }

    val creates: Seq[(UUID, EntityKeyStoreRow)] = isCreatedAndRow.filter(_._1).map(_._2)
    val updates: Seq[(UUID, EntityKeyStoreRow)] = isCreatedAndRow.filter(!_._1).map(_._2)

    val createRows = creates.map(_._2)
    val updateRows = updates.map(_._2)

    entityKeyValues.insert(createRows)
    entityKeyValues.update(updateRows)

    val results = getKeyValues(ids, key)

    (results, creates.map(_._1), updates.map(_._1))
  }

  def deleteKeyValues(ids: Seq[UUID], key: String, filter: Option[EntityFilter] = None): Seq[(UUID, Array[Byte])] = {
    filter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to delete restricted entity key values")
      }
    }

    val results = getKeyValues(ids, key)

    entityKeyValues.deleteWhere(t => (t.uuid in ids) and t.key === key)

    results
  }

  def deleteAllKeyValues(ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[(UUID, String, Array[Byte])] = {
    filter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to delete restricted entity key values")
      }
    }

    val results = getAllKeyValues(ids)

    entityKeyValues.deleteWhere(t => t.uuid in ids)

    results
  }

  def getCurrentKeyValues(set: Seq[(UUID, String)], filter: Option[EntityFilter] = None): Seq[EntityKeyStoreRow] = {
    from(entityKeyValues)(kv =>
      where(
        EntityFilter.optional(filter, kv.uuid).inhibitWhen(filter.isEmpty) and
          set.map(tup => tup._1 === kv.uuid and tup._2 === kv.key).reduce(ModelHelpers.logicalOr))
        select (kv)).toVector
  }

  def getCurrentKeyValuesByUuids(ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[EntityKeyStoreRow] = {
    from(entityKeyValues)(kv =>
      where(
        EntityFilter.optional(filter, kv.uuid).inhibitWhen(filter.isEmpty) and
          (kv.uuid in ids))
        select (kv)).toVector
  }

  def kvToProto(row: EntityKeyStoreRow): EntityKeyValue = {
    EntityKeyValue.newBuilder()
      .setUuid(uuidToProtoUUID(row.uuid))
      .setKey(row.key)
      .setValue(StoredValue.parseFrom(row.data))
      .build()
  }

  def getKeys(ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[EntityKeyPair] = {
    val uuidAndKeys = from(entityKeyValues)(kv =>
      where(
        EntityFilter.optional(filter, kv.uuid).inhibitWhen(filter.isEmpty) and
          (kv.uuid in ids))
        select (kv.uuid, kv.key))
      .toVector
      .filterNot(_._2 == ProcessingModel.overrideKey)

    uuidAndKeys.map {
      case (uuid, key) =>
        EntityKeyPair.newBuilder()
          .setUuid(uuid)
          .setKey(key)
          .build
    }
  }

  def getKeyValues2(set: Seq[(UUID, String)], filter: Option[EntityFilter] = None): Seq[EntityKeyValue] = {
    getCurrentKeyValues(set, filter)
      .filterNot(_.key == ProcessingModel.overrideKey)
      .map(kvToProto)
  }

  def getKeyValuesForUuids(set: Seq[UUID], filter: Option[EntityFilter] = None): Seq[EntityKeyValue] = {
    getCurrentKeyValuesByUuids(set, filter)
      .filterNot(_.key == ProcessingModel.overrideKey)
      .map(kvToProto)
  }

  def putKeyValues2(notifier: ModelNotifier, set: Seq[(UUID, String, StoredValue)], allowCreate: Boolean = true, updateFilter: Option[EntityFilter] = None): Seq[EntityKeyValue] = {
    val ids = set.map(_._1)
    updateFilter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to update restricted entities")
      }
    }

    if (set.map(_._2).contains(ProcessingModel.overrideKey)) {
      throw new ModelInputException(s"Cannot put reserved key ${ProcessingModel.overrideKey}")
    }

    val uuidsAndKeys = set.map { case (id, key, _) => (id, key) }

    val current = getCurrentKeyValues(uuidsAndKeys)

    val currentMap = current.map(r => ((r.uuid, r.key), r)).toMap

    val (creates, updates, same) = {
      val crd = new VectorBuilder[EntityKeyStoreRow]
      val upd = new VectorBuilder[EntityKeyStoreRow]
      val unch = new VectorBuilder[EntityKeyStoreRow]

      set.foreach {
        case (uuid, key, protoValue) =>
          currentMap.get((uuid, key)) match {
            case None => crd += EntityKeyStoreRow(0, uuid, key, protoValue.toByteArray)
            case Some(existing) =>
              val bytes = protoValue.toByteArray
              if (!java.util.Arrays.equals(bytes, existing.data)) {
                upd += existing.copy(data = bytes)
              } else {
                unch += existing
              }
          }
      }

      (crd.result(), upd.result(), unch.result())
    }

    val endpointMap = keyEndpointMap(ids)

    val createProtosAndEndpoint = creates.map(row => (kvToProto(row), endpointMap.get(row.uuid).map(uuidToProtoUUID)))
    val updateProtosAndEndpoint = updates.map(row => (kvToProto(row), endpointMap.get(row.uuid).map(uuidToProtoUUID)))

    entityKeyValues.insert(creates)
    createProtosAndEndpoint.map(t => EntityKeyValueWithEndpoint(t._1, t._2)).foreach(notifier.notify(Created, _))

    entityKeyValues.update(updates)
    updateProtosAndEndpoint.map(t => EntityKeyValueWithEndpoint(t._1, t._2)).foreach(notifier.notify(Updated, _))

    createProtosAndEndpoint.map(_._1) ++ updateProtosAndEndpoint.map(_._1) ++ same.map(kvToProto)
  }

  def deleteKeyValues2(notifier: ModelNotifier, set: Seq[(UUID, String)], filter: Option[EntityFilter] = None): Seq[EntityKeyValue] = {

    val ids = set.map(_._1)
    filter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to delete restricted entity key value pairs")
      }
    }
    if (set.map(_._2).contains(ProcessingModel.overrideKey)) {
      throw new ModelInputException(s"Cannot delete reserved key ${ProcessingModel.overrideKey}")
    }

    val originalRows = getCurrentKeyValues(set, filter)
    val endpointMap = keyEndpointMap(ids)

    val origProtosAndEndpoint = originalRows.map(row => (kvToProto(row), endpointMap.get(row.uuid).map(uuidToProtoUUID)))

    entityKeyValues.deleteWhere(kv => set.map(tup => tup._1 === kv.uuid and tup._2 === kv.key).reduce(ModelHelpers.logicalOr))

    origProtosAndEndpoint.map(t => EntityKeyValueWithEndpoint(t._1, t._2)).foreach(notifier.notify(Deleted, _))

    origProtosAndEndpoint.map(_._1)
  }

  def deleteAllKeyValues2(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[EntityKeyValue] = {
    filter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to delete restricted entity key values")
      }
    }

    val results = getKeyValuesForUuids(ids).filterNot(_.getKey == ProcessingModel.overrideKey)
    val endpointMap = keyEndpointMap(ids).map { case (u1, u2) => (uuidToProtoUUID(u1), uuidToProtoUUID(u2)) }

    entityKeyValues.deleteWhere(t => t.uuid in ids)

    val resultsWithEndpoint = results.map(r => EntityKeyValueWithEndpoint(r, endpointMap.get(r.getUuid)))

    resultsWithEndpoint.foreach(notifier.notify(Deleted, _))

    results
  }

  def keyEndpointMap(ids: Seq[UUID]): Map[UUID, UUID] = {
    from(edges)(e =>
      where(e.relationship === "source" and
        (e.childId in ids))
        select (e.childId, e.parentId)).toVector.toMap
  }

}
