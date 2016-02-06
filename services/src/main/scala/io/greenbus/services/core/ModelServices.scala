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
package io.greenbus.services.core

import java.util.UUID

import io.greenbus.client.exception.{ BadRequestException, ForbiddenException }
import io.greenbus.client.proto.Envelope
import io.greenbus.client.proto.Envelope.SubscriptionEventType
import io.greenbus.client.service.proto.Model._
import io.greenbus.client.service.proto.ModelRequests._
import io.greenbus.services.framework._
import io.greenbus.services.model.EntityModel.TypeParams
import io.greenbus.services.model.EventAlarmModel.SysEventTemplate
import io.greenbus.services.model.FrontEndModel.{ CommandInfo, EndpointInfo, PointInfo }
import io.greenbus.services.model.UUIDHelpers._
import io.greenbus.services.model._

import scala.collection.JavaConversions._

object EntityServices {

  val entityResource = "entity"
  val edgeResource = "entity_edge"

  val keyValueResource = "entity_key_value"

  val pointResource = "point"
  val commandResource = "command"
  val endpointResource = "endpoint"

  val defaultPageSize = 200

  def validateEntityName(name: String) {
    name.foreach {
      case ' ' => throw new BadRequestException("Entity names must not include spaces.")
      case '*' => throw new BadRequestException("Entity names must not include banned character '*'.")
      case _ =>
    }
  }

  def parsePageParams(hasPagingParams: => Boolean, getPagingParams: => EntityPagingParams): (Boolean, Option[UUID], Option[String], Int) = {
    if (hasPagingParams) {
      val pageByName = !getPagingParams.hasPageByName || getPagingParams.getPageByName
      val lastUuid = if (getPagingParams.hasLastUuid) Some(protoUUIDToUuid(getPagingParams.getLastUuid)) else None
      val lastName = if (getPagingParams.hasLastName) Some(getPagingParams.getLastName) else None
      val pageSize = if (getPagingParams.hasPageSize) getPagingParams.getPageSize else defaultPageSize
      (pageByName, lastUuid, lastName, pageSize)
    } else {
      (true, None, None, defaultPageSize)
    }
  }
}

class EntityServices(services: ServiceRegistry,
    entityModel: EntityModel,
    frontEndModel: FrontEndModel,
    eventModel: EventAlarmModel,
    entityBinding: SubscriptionChannelBinder,
    keyValueBinding: SubscriptionChannelBinder,
    edgeBinding: SubscriptionChannelBinder,
    endpointBinding: SubscriptionChannelBinder,
    pointBinding: SubscriptionChannelBinder,
    commandBinding: SubscriptionChannelBinder) {

  import io.greenbus.client.service.ModelService.Descriptors
  import io.greenbus.services.core.EntityServices._

  services.fullService(Descriptors.Get, getEntities)
  services.fullService(Descriptors.EntityQuery, entityQuery)
  services.fullService(Descriptors.Subscribe, subscribeToEntities)
  services.fullService(Descriptors.RelationshipFlatQuery, relationshipFlatQuery)
  services.fullService(Descriptors.Put, putEntities)
  services.fullService(Descriptors.Delete, deleteEntities)

  services.fullService(Descriptors.EdgeQuery, edgeQuery)
  services.fullService(Descriptors.PutEdges, putEntityEdges)
  services.fullService(Descriptors.DeleteEdges, deleteEntityEdges)
  services.fullService(Descriptors.SubscribeToEdges, subscribeToEntityEdges)

  services.fullService(Descriptors.GetEntityKeyValues, getEntityKeyValues)
  services.fullService(Descriptors.GetEntityKeys, getEntityKeys)
  services.fullService(Descriptors.PutEntityKeyValues, putEntityKeyValues)
  services.fullService(Descriptors.DeleteEntityKeyValues, deleteEntityKeyValues)
  services.fullService(Descriptors.SubscribeToEntityKeyValues, subscribeToEntityKeyValues)

  services.fullService(Descriptors.GetPoints, getPoints)
  services.fullService(Descriptors.PointQuery, pointQuery)
  services.fullService(Descriptors.PutPoints, putPoints)
  services.fullService(Descriptors.DeletePoints, deletePoints)
  services.fullService(Descriptors.SubscribeToPoints, subscribeToPoints)
  services.fullService(Descriptors.GetCommands, getCommands)
  services.fullService(Descriptors.CommandQuery, commandQuery)
  services.fullService(Descriptors.PutCommands, putCommands)
  services.fullService(Descriptors.DeleteCommands, deleteCommands)
  services.fullService(Descriptors.SubscribeToCommands, subscribeToCommands)
  services.fullService(Descriptors.GetEndpoints, getEndpoints)
  services.fullService(Descriptors.EndpointQuery, endpointQuery)
  services.fullService(Descriptors.PutEndpoints, putEndpoints)
  services.fullService(Descriptors.PutEndpointDisabled, putEndpointsDisabled)
  services.fullService(Descriptors.DeleteEndpoints, deleteEndpoints)
  services.fullService(Descriptors.SubscribeToEndpoints, subscribeToEndpoints)

  def getEntities(request: GetEntitiesRequest, headers: Map[String, String], context: ServiceContext): Response[GetEntitiesResponse] = {
    val filter = context.auth.authorize(entityResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content.")
    }

    val keySet = request.getRequest

    if (keySet.getUuidsCount == 0 && keySet.getNamesCount == 0) {
      throw new BadRequestException("Must include at least one id or name.")
    }

    val uuids = keySet.getUuidsList.toSeq.map(protoUUIDToUuid)
    val names = keySet.getNamesList.toSeq

    val results = entityModel.keyQuery(uuids, names, filter)

    val response = GetEntitiesResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def entityQuery(request: EntityQueryRequest, headers: Map[String, String], context: ServiceContext): Response[EntityQueryResponse] = {
    val filter = context.auth.authorize(entityResource, "read")

    if (!request.hasQuery) {
      throw new BadRequestException("Must include request content.")
    }

    val query = request.getQuery

    val typeParams = if (query.hasTypeParams) {
      TypeParams(
        query.getTypeParams.getIncludeTypesList.toSeq,
        query.getTypeParams.getMatchTypesList.toSeq,
        query.getTypeParams.getFilterOutTypesList.toSeq)
    } else {
      TypeParams(Nil, Nil, Nil)
    }

    val (pageByName, lastUuid, lastName, pageSize) = parsePageParams(query.hasPagingParams, query.getPagingParams)

    val results = entityModel.fullQuery(typeParams, lastUuid, lastName, pageSize, pageByName, filter)

    val response = EntityQueryResponse.newBuilder().addAllResults(results).build

    Success(Envelope.Status.OK, response)
  }

  def subscribeToEntities(request: SubscribeEntitiesRequest, headers: Map[String, String], context: ServiceContext): Response[SubscribeEntitiesResponse] = {
    val filter = context.auth.authorize(entityResource, "read")

    if (!request.hasQuery) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getQuery

    val queue = headers.get("__subscription").getOrElse {
      throw new BadRequestException("Must include subscription queue in headers")
    }

    val immediateResults = if (query.getUuidsCount == 0 && query.getNamesCount == 0) {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions on entities when subscribing to all entities")
      }

      entityBinding.bindAll(queue)
      Nil

    } else {

      val names = query.getNamesList.toSeq
      val uuids = query.getUuidsList.toSeq.map(protoUUIDToUuid)

      val results = entityModel.keyQuery(uuids, names, filter)

      val filteredUuids = results.map(_.getUuid.getValue)

      if (filteredUuids.nonEmpty) {
        entityBinding.bindEach(queue, Seq(filteredUuids))
      }
      results
    }

    val resp = SubscribeEntitiesResponse.newBuilder().addAllResults(immediateResults).build()

    Success(Envelope.Status.OK, resp)
  }

  def relationshipFlatQuery(request: EntityRelationshipFlatQueryRequest, headers: Map[String, String], context: ServiceContext): Response[EntityRelationshipFlatQueryResponse] = {

    val filter = context.auth.authorize(edgeResource, "read")

    val query = request.getQuery
    val options = Seq(
      query.getStartUuidsCount > 0,
      query.getStartNamesCount > 0,
      query.getStartTypesCount > 0).filter(b => b)

    if (options.size == 0) {
      throw new BadRequestException("Must include one of ids, names, or types to search for.")
    }
    if (options.size > 1) {
      throw new BadRequestException("Must include only one of ids, names, or types to search for.")
    }

    if (!query.hasRelationship) {
      throw new BadRequestException("Must include relationship.")
    }
    if (!query.hasDescendantOf) {
      throw new BadRequestException("Must include direction.")
    }

    val descendantTypes = query.getEndTypesList.toSeq
    val depthLimit = if (query.hasDepthLimit) Some(query.getDepthLimit) else None

    val (pageByName, lastUuid, lastName, pageSize) = parsePageParams(query.hasPagingParams, query.getPagingParams)

    val entities = if (query.getStartUuidsCount > 0) {
      entityModel.idsRelationFlatQuery(
        query.getStartUuidsList.map(protoUUIDToUuid).toSeq,
        query.getRelationship,
        query.getDescendantOf,
        descendantTypes,
        depthLimit,
        lastUuid,
        lastName,
        pageSize,
        pageByName,
        filter)
    } else if (query.getStartNamesCount > 0) {
      entityModel.namesRelationFlatQuery(
        query.getStartNamesList.toSeq,
        query.getRelationship,
        query.getDescendantOf,
        descendantTypes,
        depthLimit,
        lastUuid,
        lastName,
        pageSize,
        pageByName,
        filter)
    } else {
      entityModel.typesRelationFlatQuery(
        query.getStartTypesList.toSeq,
        query.getRelationship,
        query.getDescendantOf,
        descendantTypes,
        depthLimit,
        lastUuid,
        lastName,
        pageSize,
        pageByName,
        filter)
    }

    val response = EntityRelationshipFlatQueryResponse.newBuilder().addAllResults(entities).build

    Success(Envelope.Status.OK, response)

  }

  def putEntities(request: PutEntitiesRequest, headers: Map[String, String], context: ServiceContext): Response[PutEntitiesResponse] = {

    val createFilter = context.auth.authorize(entityResource, "create")
    val updateFilter = context.auth.authorize(entityResource, "update")

    if (request.getEntitiesCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val templates = request.getEntitiesList.toSeq

    if (!templates.forall(_.hasName)) {
      throw new BadRequestException("Must include entity name.")
    }

    templates.filter(_.hasName).map(_.getName).foreach(validateEntityName)

    val (withIds, withNames) = templates.partition(_.hasUuid)

    val withIdsSimple = withIds.map { ent =>
      (protoUUIDToUuid(ent.getUuid), ent.getName, ent.getTypesList.toSet)
    }

    val withNamesSimple = withNames.map { ent =>
      (ent.getName, ent.getTypesList.toSet)
    }

    val allowCreates = createFilter.isEmpty

    val entities = try {
      entityModel.putEntities(context.notifier, withIdsSimple, withNamesSimple, allowCreates, updateFilter)
    } catch {
      case ex: ModelPermissionException =>
        throw new ForbiddenException(ex.getMessage)
      case inEx: ModelInputException =>
        throw new BadRequestException(inEx.getMessage)
    }

    Success(Envelope.Status.OK, PutEntitiesResponse.newBuilder().addAllResults(entities).build())
  }

  def deleteEntities(request: DeleteEntitiesRequest, headers: Map[String, String], context: ServiceContext): Response[DeleteEntitiesResponse] = {

    val filter = context.auth.authorize(entityResource, "delete")

    if (request.getEntityUuidsCount == 0) {
      throw new BadRequestException("Must include at least one id to delete.")
    }

    val ids = request.getEntityUuidsList map protoUUIDToUuid

    val results = try {
      entityModel.deleteEntities(context.notifier, ids, filter)
    } catch {
      case ex: ModelPermissionException => {
        throw new ForbiddenException(ex.getMessage)
      }
    }

    Success(Envelope.Status.OK, DeleteEntitiesResponse.newBuilder().addAllResults(results).build())
  }

  def getEntityKeyValues(request: GetEntityKeyValuesRequest, headers: Map[String, String], context: ServiceContext): Response[GetEntityKeyValuesResponse] = {
    val filter = context.auth.authorize(keyValueResource, "read")

    if (request.getRequestCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    request.getRequestList.foreach { kvp =>
      if (!kvp.hasUuid) {
        throw new BadRequestException("Must include UUID")
      }
      if (!kvp.hasKey) {
        throw new BadRequestException("Must include key")
      }
    }

    val idAndKeys = request.getRequestList.map(kvp => (protoUUIDToUuid(kvp.getUuid), kvp.getKey))

    val results = entityModel.getKeyValues2(idAndKeys, filter)

    Success(Envelope.Status.OK, GetEntityKeyValuesResponse.newBuilder().addAllResults(results).build())
  }

  def getEntityKeys(request: GetKeysForEntitiesRequest, headers: Map[String, String], context: ServiceContext): Response[GetKeysForEntitiesResponse] = {
    val filter = context.auth.authorize(keyValueResource, "read")

    if (request.getRequestCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val uuids = request.getRequestList.map(protoUUIDToUuid)

    val results = entityModel.getKeys(uuids, filter)

    Success(Envelope.Status.OK, GetKeysForEntitiesResponse.newBuilder().addAllResults(results).build())
  }

  def subscribeToEntityKeyValues(request: SubscribeEntityKeyValuesRequest, headers: Map[String, String], context: ServiceContext): Response[SubscribeEntityKeyValuesResponse] = {

    val filter = context.auth.authorize(keyValueResource, "read")

    if (!request.hasQuery) {
      throw new BadRequestException("Must include request content")
    }

    val queue = headers.getOrElse("__subscription", throw new BadRequestException("Must include subscription queue in headers"))

    val query = request.getQuery

    val hasKeys = query.getUuidsCount != 0 || query.getKeyPairsCount != 0
    val hasParams = hasKeys || query.getEndpointUuidsCount != 0

    val results = if (!hasParams) {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions on entities when subscribing to all")
      }

      keyValueBinding.bindAll(queue)

      Seq()

    } else if (query.getEndpointUuidsCount != 0) {

      if (hasKeys) {
        throw new BadRequestException("Must not include key parameters while subscribing by endpoint")
      }
      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions when subscribing to abstract sets")
      }

      val endpointUuids = query.getEndpointUuidsList.toSeq

      keyValueBinding.bindEach(queue, Seq(Nil, Nil, endpointUuids.map(_.getValue)))

      Seq()

    } else {

      val uuidSubKeys = query.getUuidsList.toSeq

      val uuidAndKeys = query.getKeyPairsList.toSeq.map { kv =>
        if (!kv.hasUuid) {
          throw new BadRequestException("Key pairs must include UUID")
        }
        if (!kv.hasKey) {
          throw new BadRequestException("Key pairs must include key")
        }

        (protoUUIDToUuid(kv.getUuid), kv.getKey)
      }

      val uuidAndKeyResults = if (uuidAndKeys.nonEmpty) entityModel.getKeyValues2(uuidAndKeys) else Seq()

      val uuidResults = if (uuidSubKeys.nonEmpty) entityModel.getKeyValuesForUuids(uuidSubKeys.map(protoUUIDToUuid)) else Seq()

      uuidAndKeyResults.foreach { kv =>
        keyValueBinding.bindTogether(queue, Seq(Seq(Some(kv.getUuid.getValue), Some(kv.getKey), None)))
      }

      if (uuidResults.nonEmpty) {
        keyValueBinding.bindEach(queue, Seq(uuidResults.map(uuid => uuid.getUuid.getValue), Seq(), Seq()))
      }

      uuidResults ++ uuidAndKeyResults
    }

    val response = SubscribeEntityKeyValuesResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def putEntityKeyValues(request: PutEntityKeyValuesRequest, headers: Map[String, String], context: ServiceContext): Response[PutEntityKeyValuesResponse] = {

    val createFilter = context.auth.authorize(keyValueResource, "create")
    val updateFilter = context.auth.authorize(keyValueResource, "update")

    if (request.getKeyValuesCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val templates = request.getKeyValuesList.toSeq

    templates.foreach { temp =>
      if (!temp.hasUuid) {
        throw new BadRequestException("Must include UUID")
      }
      if (!temp.hasKey) {
        throw new BadRequestException("Must include key")
      }
      if (!temp.hasValue) {
        throw new BadRequestException("Must include value")
      }
    }

    val set = templates.map(kv => (protoUUIDToUuid(kv.getUuid), kv.getKey, kv.getValue))

    val allowCreates = createFilter.isEmpty

    val results = entityModel.putKeyValues2(context.notifier, set, allowCreates, updateFilter)

    Success(Envelope.Status.OK, PutEntityKeyValuesResponse.newBuilder().addAllResults(results).build())
  }

  def deleteEntityKeyValues(request: DeleteEntityKeyValuesRequest, headers: Map[String, String], context: ServiceContext): Response[DeleteEntityKeyValuesResponse] = {

    val filter = context.auth.authorize(keyValueResource, "delete")

    if (request.getRequestCount == 0) {
      throw new BadRequestException("Must include at least one id/key to delete.")
    }

    request.getRequestList.foreach { kvp =>
      if (!kvp.hasUuid) {
        throw new BadRequestException("Must include UUID")
      }
      if (!kvp.hasKey) {
        throw new BadRequestException("Must include key")
      }
    }

    val idAndKeys = request.getRequestList.map(kvp => (protoUUIDToUuid(kvp.getUuid), kvp.getKey))

    val results = entityModel.deleteKeyValues2(context.notifier, idAndKeys, filter)

    Success(Envelope.Status.OK, DeleteEntityKeyValuesResponse.newBuilder().addAllResults(results).build())
  }

  def edgeQuery(request: EntityEdgeQueryRequest, headers: Map[String, String], context: ServiceContext): Response[EntityEdgeQueryResponse] = {

    val filter = context.auth.authorize(edgeResource, "read")

    if (!request.hasQuery) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getQuery

    val parents = query.getParentUuidsList.map(protoUUIDToUuid)
    val children = query.getChildUuidsList.map(protoUUIDToUuid)
    val relations = query.getRelationshipsList.toSeq
    val depthLimit = if (query.hasDepthLimit) Some(query.getDepthLimit) else None

    val lastId = if (query.hasLastId) Some(idToLong(query.getLastId)) else None
    val pageSize = if (query.hasPageSize) query.getPageSize else defaultPageSize

    val results = entityModel.edgeQuery(parents, relations, children, depthLimit, lastId, pageSize, filter)

    Success(Envelope.Status.OK, EntityEdgeQueryResponse.newBuilder().addAllResults(results).build())
  }

  def subscribeToEntityEdges(request: SubscribeEntityEdgesRequest, headers: Map[String, String], context: ServiceContext): Response[SubscribeEntityEdgesResponse] = {
    val filter = context.auth.authorize(edgeResource, "read")

    if (!request.hasQuery) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getQuery

    val queue = headers.get("__subscription").getOrElse {
      throw new BadRequestException("Must include subscription queue in headers")
    }

    if (query.getFiltersCount == 0) {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions when subscribing to all")
      }

      edgeBinding.bindAll(queue)

    } else {

      val params = query.getFiltersList.toSeq.map { filt =>

        val optParent = if (filt.hasParentUuid) Some(protoUUIDToUuid(filt.getParentUuid)) else None
        val optChild = if (filt.hasChildUuid) Some(protoUUIDToUuid(filt.getChildUuid)) else None

        if (optParent.isEmpty && optChild.isEmpty && filter.nonEmpty) {
          throw new ForbiddenException("Must have blanket read permissions when subscribing without specifying parent or child")
        }

        val optRelation = if (filt.hasRelationship) Some(filt.getRelationship) else None
        val optDistance = if (filt.hasDistance) Some(filt.getDistance) else None

        (optParent, optChild, optRelation, optDistance)
      }

      val filtered = filter match {
        case Some(filt) =>
          val allParents = params.flatMap(_._1)
          val allChildren = params.flatMap(_._2)

          val allEnts = allParents ++ allChildren

          val allowedSet = filt.filter(allEnts).toSet

          val allEntsSet = allEnts.toSet

          val entsFilteredOut = allEntsSet &~ allowedSet

          if (entsFilteredOut.nonEmpty) {
            throw new ForbiddenException("Missing permissions for entities in edge request")
          }

          params

        case None =>
          params
      }

      val keyParams = filtered.map {
        case (optParent, optChild, optRelation, optDistance) => List(
          optParent.map(_.getValue),
          optChild.map(_.getValue),
          optRelation,
          optDistance.map(_.toString))
      }

      edgeBinding.bindTogether(queue, keyParams)
    }

    val resp = SubscribeEntityEdgesResponse.newBuilder().build()

    Success(Envelope.Status.OK, resp)
  }

  private def idToLong(id: ModelID): Long = {
    if (!id.hasValue) {
      throw new BadRequestException("ModelID was missing a value")
    }
    try {
      id.getValue.toLong
    } catch {
      case ex: NumberFormatException =>
        throw new BadRequestException("ModelID was an invalid format")
    }
  }

  def putEntityEdges(request: PutEntityEdgesRequest, headers: Map[String, String], context: ServiceContext): Response[PutEntityEdgesResponse] = {

    // TODO: Find all affected entities in one query and check them
    val edgeFilter = context.auth.authorize(edgeResource, "create")
    if (edgeFilter.nonEmpty) {
      throw new ForbiddenException(s"Must have blanket create permissions for '$edgeResource'")
    }

    if (request.getDescriptorsCount == 0) {
      throw new BadRequestException("Must include at least one descriptor.")
    }

    val templates = request.getDescriptorsList.toSeq

    templates.foreach { temp =>
      if (!temp.hasParentUuid) {
        throw new BadRequestException("Entity edge must have a parent.")
      }
      if (!temp.hasChildUuid) {
        throw new BadRequestException("Entity edge must include a child.")
      }
      if (!temp.hasRelationship) {
        throw new BadRequestException("Entity edge must include relationship.")
      }
    }

    val grouped: Seq[(String, Seq[(UUID, UUID)])] = templates.groupBy(temp => temp.getRelationship).mapValues(_.map(temp => (protoUUIDToUuid(temp.getParentUuid), protoUUIDToUuid(temp.getChildUuid)))).toSeq

    val results = grouped.flatMap {
      case (relation, pairs) => entityModel.putEdges(context.notifier, pairs, relation)
    }

    Success(Envelope.Status.OK, PutEntityEdgesResponse.newBuilder().addAllResults(results).build())
  }

  def deleteEntityEdges(request: DeleteEntityEdgesRequest, headers: Map[String, String], context: ServiceContext): Response[DeleteEntityEdgesResponse] = {

    // TODO: Find all affected entities in one query and check them
    val edgeFilter = context.auth.authorize(edgeResource, "delete")
    if (edgeFilter.nonEmpty) {
      throw new ForbiddenException(s"Must have blanket delete permissions for '$edgeResource'")
    }

    if (request.getDescriptorsCount == 0) {
      throw new BadRequestException("Must include at least one descriptor.")
    }

    val templates = request.getDescriptorsList.toSeq

    templates.foreach { temp =>
      if (!temp.hasParentUuid) {
        throw new BadRequestException("Entity edge must have a parent.")
      }
      if (!temp.hasChildUuid) {
        throw new BadRequestException("Entity edge must include at least one child.")
      }
      if (!temp.hasRelationship) {
        throw new BadRequestException("Entity edge must include relationship.")
      }
    }

    val grouped: Seq[(String, Seq[(UUID, UUID)])] = templates.groupBy(temp => temp.getRelationship).mapValues(_.map(temp => (protoUUIDToUuid(temp.getParentUuid), protoUUIDToUuid(temp.getChildUuid)))).toSeq

    val results = grouped.flatMap {
      case (relation, pairs) => entityModel.deleteEdges(context.notifier, pairs, relation)
    }

    Success(Envelope.Status.OK, DeleteEntityEdgesResponse.newBuilder().addAllResults(results).build())
  }

  def getPoints(request: GetPointsRequest, headers: Map[String, String], context: ServiceContext): Response[GetPointsResponse] = {

    val filter = context.auth.authorize(pointResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val keySet = request.getRequest

    if (keySet.getUuidsCount == 0 && keySet.getNamesCount == 0) {
      throw new BadRequestException("Must include at least one id or name")
    }

    val uuids = keySet.getUuidsList.toSeq.map(protoUUIDToUuid)
    val names = keySet.getNamesList.toSeq

    val results = frontEndModel.pointKeyQuery(uuids, names, filter)

    val response = GetPointsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def pointQuery(request: PointQueryRequest, headers: Map[String, String], context: ServiceContext): Response[PointQueryResponse] = {

    val filter = context.auth.authorize(pointResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getRequest

    val (pageByName, lastUuid, lastName, pageSize) = parsePageParams(query.hasPagingParams, query.getPagingParams)

    val typeParams = if (query.hasTypeParams) {
      TypeParams(
        query.getTypeParams.getIncludeTypesList.toSeq,
        query.getTypeParams.getMatchTypesList.toSeq,
        query.getTypeParams.getFilterOutTypesList.toSeq)
    } else {
      TypeParams(Nil, Nil, Nil)
    }

    val pointCategories = query.getPointCategoriesList.toSeq
    val units = query.getUnitsList.toSeq

    val results = frontEndModel.pointQuery(pointCategories, units, typeParams, lastUuid, lastName, pageSize, pageByName, filter)

    val response = PointQueryResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def putPoints(request: PutPointsRequest, headers: Map[String, String], context: ServiceContext): Response[PutPointsResponse] = {

    val createFilter = context.auth.authorize(pointResource, "create")
    val allowCreate = createFilter.isEmpty
    val updateFilter = context.auth.authorize(pointResource, "update")

    if (request.getTemplatesCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val templates = request.getTemplatesList.toSeq

    val modelTemplates = templates.map { template =>

      if (!template.hasEntityTemplate) {
        throw new BadRequestException("Must include entity template")
      }

      if (!template.getEntityTemplate.hasName) {
        throw new BadRequestException("Must include point name")
      }
      EntityServices.validateEntityName(template.getEntityTemplate.getName)

      if (!template.hasPointCategory) {
        throw new BadRequestException("Must include point type")
      }
      if (!template.hasUnit) {
        throw new BadRequestException("Must include point unit")
      }

      val uuidOpt = if (template.getEntityTemplate.hasUuid) Some(protoUUIDToUuid(template.getEntityTemplate.getUuid)) else None

      FrontEndModel.CoreTypeTemplate(uuidOpt, template.getEntityTemplate.getName, template.getEntityTemplate.getTypesList.toSet, PointInfo(template.getPointCategory, template.getUnit))
    }

    val results = frontEndModel.putPoints(context.notifier, modelTemplates, allowCreate, updateFilter)

    val response = PutPointsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def deletePoints(request: DeletePointsRequest, headers: Map[String, String], context: ServiceContext): Response[DeletePointsResponse] = {

    val filter = context.auth.authorize(pointResource, "delete")

    if (request.getPointUuidsCount == 0) {
      throw new BadRequestException("Must include point ids to delete")
    }

    val uuids = request.getPointUuidsList.map(protoUUIDToUuid)

    val results = frontEndModel.deletePoints(context.notifier, uuids, filter)

    val response = DeletePointsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def subscribeToPoints(request: SubscribePointsRequest, headers: Map[String, String], context: ServiceContext): Response[SubscribePointsResponse] = {

    val filter = context.auth.authorize(pointResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val queue = headers.get("__subscription").getOrElse {
      throw new BadRequestException("Must include subscription queue in headers")
    }

    val query = request.getRequest

    val hasKeys = query.getUuidsCount != 0 || query.getNamesCount != 0
    val hasParams = hasKeys || query.getEndpointUuidsCount != 0

    val results = if (!hasParams) {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions when subscribing to all")
      }

      pointBinding.bindAll(queue)

      Nil

    } else if (query.getEndpointUuidsCount != 0 && !hasKeys) {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions when subscribing to all")
      }

      val endpointUuids = query.getEndpointUuidsList.toSeq

      pointBinding.bindEach(queue, Seq(endpointUuids.map(_.getValue), Nil, Nil))

      Nil

    } else if (hasKeys && query.getEndpointUuidsCount == 0) {

      val uuids = query.getUuidsList.toSeq
      val names = query.getNamesList.toSeq

      val results = frontEndModel.pointKeyQuery(uuids map protoUUIDToUuid, names, filter)

      val filteredUuids = results.map(_.getUuid.getValue)

      if (filteredUuids.nonEmpty) {
        pointBinding.bindEach(queue, Seq(Nil, filteredUuids, Nil))
      }

      results

    } else {

      throw new BadRequestException("Must not include key parameters while subscribing by endpoint")
    }

    val response = SubscribePointsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def getCommands(request: GetCommandsRequest, headers: Map[String, String], context: ServiceContext): Response[GetCommandsResponse] = {

    val filter = context.auth.authorize(commandResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val keySet = request.getRequest

    if (keySet.getUuidsCount == 0 && keySet.getNamesCount == 0) {
      throw new BadRequestException("Must include at least one id or name")
    }

    val uuids = keySet.getUuidsList.toSeq.map(protoUUIDToUuid)
    val names = keySet.getNamesList.toSeq

    val results = frontEndModel.commandKeyQuery(uuids, names, filter)

    val response = GetCommandsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def commandQuery(request: CommandQueryRequest, headers: Map[String, String], context: ServiceContext): Response[CommandQueryResponse] = {

    val filter = context.auth.authorize(commandResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getRequest

    val (pageByName, lastUuid, lastName, pageSize) = parsePageParams(query.hasPagingParams, query.getPagingParams)

    val typeParams = if (query.hasTypeParams) {
      TypeParams(
        query.getTypeParams.getIncludeTypesList.toSeq,
        query.getTypeParams.getMatchTypesList.toSeq,
        query.getTypeParams.getFilterOutTypesList.toSeq)
    } else {
      TypeParams(Nil, Nil, Nil)
    }

    val commandCategories = query.getCommandCategoriesList.toSeq

    val results = frontEndModel.commandQuery(commandCategories, typeParams, lastUuid, lastName, pageSize, pageByName, filter)

    val response = CommandQueryResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def putCommands(request: PutCommandsRequest, headers: Map[String, String], context: ServiceContext): Response[PutCommandsResponse] = {

    val createFilter = context.auth.authorize(commandResource, "create")
    val allowCreate = createFilter.isEmpty
    val updateFilter = context.auth.authorize(commandResource, "update")

    if (request.getTemplatesCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val templates = request.getTemplatesList.toSeq

    val modelTemplates = templates.map { template =>

      if (!template.hasEntityTemplate || !template.getEntityTemplate.hasName) {
        throw new BadRequestException("Must include point name")
      }
      EntityServices.validateEntityName(template.getEntityTemplate.getName)

      if (!template.hasDisplayName) {
        throw new BadRequestException("Must include display name")
      }

      if (!template.hasCategory) {
        throw new BadRequestException("Must include command type")
      }

      val uuidOpt = if (template.getEntityTemplate.hasUuid) Some(protoUUIDToUuid(template.getEntityTemplate.getUuid)) else None

      FrontEndModel.CoreTypeTemplate(uuidOpt, template.getEntityTemplate.getName, template.getEntityTemplate.getTypesList.toSet, CommandInfo(template.getDisplayName, template.getCategory))
    }

    val results = frontEndModel.putCommands(context.notifier, modelTemplates, allowCreate, updateFilter)

    val response = PutCommandsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def deleteCommands(request: DeleteCommandsRequest, headers: Map[String, String], context: ServiceContext): Response[DeleteCommandsResponse] = {

    val filter = context.auth.authorize(commandResource, "delete")

    if (request.getCommandUuidsCount == 0) {
      throw new BadRequestException("Must include point ids to delete")
    }

    val uuids = request.getCommandUuidsList.map(protoUUIDToUuid)

    val results = frontEndModel.deleteCommands(context.notifier, uuids, filter)

    val response = DeleteCommandsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def subscribeToCommands(request: SubscribeCommandsRequest, headers: Map[String, String], context: ServiceContext): Response[SubscribeCommandsResponse] = {

    val filter = context.auth.authorize(commandResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val queue = headers.get("__subscription").getOrElse {
      throw new BadRequestException("Must include subscription queue in headers")
    }

    val query = request.getRequest

    val hasKeys = query.getUuidsCount != 0 || query.getNamesCount != 0
    val hasParams = hasKeys || query.getEndpointUuidsCount != 0

    val results = if (!hasParams) {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions on entities when subscribing to all")
      }

      commandBinding.bindAll(queue)

      Nil

    } else if (query.getEndpointUuidsCount != 0 && !hasKeys) {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions when subscribing to all")
      }

      val endpointUuids = query.getEndpointUuidsList.toSeq

      commandBinding.bindEach(queue, Seq(endpointUuids.map(_.getValue), Nil, Nil))

      Nil

    } else if (hasKeys && query.getEndpointUuidsCount == 0) {

      val uuids = query.getUuidsList.toSeq
      val names = query.getNamesList.toSeq

      val results = frontEndModel.commandKeyQuery(uuids map protoUUIDToUuid, names, filter)

      val filteredUuids = results.map(_.getUuid.getValue)

      if (filteredUuids.nonEmpty) {
        commandBinding.bindEach(queue, Seq(Nil, filteredUuids, Nil))
      }

      results

    } else {

      throw new BadRequestException("Must not include key parameters while subscribing by endpoint")
    }

    val response = SubscribeCommandsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def getEndpoints(request: GetEndpointsRequest, headers: Map[String, String], context: ServiceContext): Response[GetEndpointsResponse] = {

    val filter = context.auth.authorize(endpointResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val keySet = request.getRequest

    if (keySet.getUuidsCount == 0 && keySet.getNamesCount == 0) {
      throw new BadRequestException("Must include at least one id or name")
    }

    val uuids = keySet.getUuidsList.toSeq.map(protoUUIDToUuid)
    val names = keySet.getNamesList.toSeq

    val results = frontEndModel.endpointKeyQuery(uuids, names, filter)

    val response = GetEndpointsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def endpointQuery(request: EndpointQueryRequest, headers: Map[String, String], context: ServiceContext): Response[EndpointQueryResponse] = {

    val filter = context.auth.authorize(endpointResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getRequest

    val typeParams = if (query.hasTypeParams) {
      TypeParams(
        query.getTypeParams.getIncludeTypesList.toSeq,
        query.getTypeParams.getMatchTypesList.toSeq,
        query.getTypeParams.getFilterOutTypesList.toSeq)
    } else {
      TypeParams(Nil, Nil, Nil)
    }

    val (pageByName, lastUuid, lastName, pageSize) = parsePageParams(query.hasPagingParams, query.getPagingParams)

    val protocols = query.getProtocolsList.toSeq
    val disabledOpt = if (query.hasDisabled) Some(query.getDisabled) else None

    val results = frontEndModel.endpointQuery(protocols, disabledOpt, typeParams, lastUuid, lastName, pageSize, pageByName, filter)

    val response = EndpointQueryResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def putEndpoints(request: PutEndpointsRequest, headers: Map[String, String], context: ServiceContext): Response[PutEndpointsResponse] = {

    val createFilter = context.auth.authorize(endpointResource, "create")
    val allowCreate = createFilter.isEmpty
    val updateFilter = context.auth.authorize(endpointResource, "update")

    if (request.getTemplatesCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val templates = request.getTemplatesList.toSeq

    val modelTemplates = templates.map { template =>
      if (!template.hasEntityTemplate || !template.getEntityTemplate.hasName) {
        throw new BadRequestException("Must include endpoint name")
      }
      EntityServices.validateEntityName(template.getEntityTemplate.getName)

      if (!template.hasProtocol) {
        throw new BadRequestException("Must include protocol name")
      }

      val uuidOpt = if (template.getEntityTemplate.hasUuid) Some(protoUUIDToUuid(template.getEntityTemplate.getUuid)) else None

      val disabledOpt = if (template.hasDisabled) Some(template.getDisabled) else None

      FrontEndModel.CoreTypeTemplate(uuidOpt, template.getEntityTemplate.getName, template.getEntityTemplate.getTypesList.toSet, EndpointInfo(template.getProtocol, disabledOpt))
    }

    val results = frontEndModel.putEndpoints(context.notifier, modelTemplates, allowCreate, updateFilter)

    val response = PutEndpointsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def putEndpointsDisabled(request: PutEndpointDisabledRequest, headers: Map[String, String], context: ServiceContext): Response[PutEndpointDisabledResponse] = {

    val filter = context.auth.authorize(endpointResource, "update")

    if (request.getUpdatesCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val updates = request.getUpdatesList.toSeq

    val modelUpdates = updates.map { update =>
      if (!update.hasEndpointUuid) {
        throw new BadRequestException("Must include endpoint uuid")
      }
      if (!update.hasDisabled) {
        throw new BadRequestException("Must include requested disable state")
      }

      FrontEndModel.EndpointDisabledUpdate(protoUUIDToUuid(update.getEndpointUuid), update.getDisabled)
    }

    val results = frontEndModel.putEndpointsDisabled(context.notifier, modelUpdates, filter)

    val eventTemplates = results.map { endpoint =>
      val attrs = EventAlarmModel.mapToAttributesList(Seq(("name", endpoint.getName)))
      val eventType = if (endpoint.getDisabled) {
        EventSeeding.System.endpointDisabled.eventType
      } else {
        EventSeeding.System.endpointEnabled.eventType
      }
      SysEventTemplate(context.auth.agentName, eventType, Some("services"), None, None, None, attrs)
    }
    eventModel.postEvents(context.notifier, eventTemplates)

    val response = PutEndpointDisabledResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def deleteEndpoints(request: DeleteEndpointsRequest, headers: Map[String, String], context: ServiceContext): Response[DeleteEndpointsResponse] = {

    val filter = context.auth.authorize(endpointResource, "delete")

    if (request.getEndpointUuidsCount == 0) {
      throw new BadRequestException("Must include point ids to delete")
    }

    val uuids = request.getEndpointUuidsList.map(protoUUIDToUuid)

    val results = frontEndModel.deleteEndpoints(context.notifier, uuids, filter)

    val response = DeleteEndpointsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def subscribeToEndpoints(request: SubscribeEndpointsRequest, headers: Map[String, String], context: ServiceContext): Response[SubscribeEndpointsResponse] = {

    val filter = context.auth.authorize(endpointResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val queue = headers.get("__subscription").getOrElse {
      throw new BadRequestException("Must include subscription queue in headers")
    }

    val query = request.getRequest

    val hasKeys = query.getUuidsCount != 0 || query.getNamesCount != 0
    val hasParams = hasKeys || query.getProtocolsCount != 0

    val results = if (!hasParams) {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions on entities when subscribing to all")
      }

      endpointBinding.bindAll(queue)

      Nil

    } else if (query.getProtocolsCount != 0 && !hasKeys) {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions on entities when subscribing by protocol")
      }

      val protocols = query.getProtocolsList.toSeq

      endpointBinding.bindEach(queue, Seq(Nil, Nil, protocols))

      Nil

    } else if (hasKeys && query.getProtocolsCount == 0) {

      val uuids = query.getUuidsList.toSeq
      val names = query.getNamesList.toSeq

      val results = frontEndModel.endpointKeyQuery(uuids map protoUUIDToUuid, names, filter)

      val filteredUuids = results.map(_.getUuid.getValue)

      if (filteredUuids.nonEmpty) {
        endpointBinding.bindEach(queue, Seq(filteredUuids, Nil, Nil))
      }

      results

    } else {

      throw new BadRequestException("Must not include key parameters if not subscribing by protocol")
    }

    val response = SubscribeEndpointsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

}

object EntitySubscriptionDescriptor extends SubscriptionDescriptor[Entity] {
  def keyParts(payload: Entity): Seq[String] = {
    Seq(payload.getUuid.getValue, payload.getName)
  }

  def notification(eventType: SubscriptionEventType, payload: Entity): Array[Byte] = {
    EntityNotification.newBuilder
      .setValue(payload)
      .setEventType(eventType)
      .build()
      .toByteArray
  }
}

case class EntityKeyValueWithEndpoint(payload: EntityKeyValue, endpoint: Option[ModelUUID])

object EntityKeyValueSubscriptionDescriptor extends SubscriptionDescriptor[EntityKeyValueWithEndpoint] {
  def keyParts(payload: EntityKeyValueWithEndpoint): Seq[String] = {
    Seq(payload.payload.getUuid.getValue, payload.payload.getKey, payload.endpoint.map(_.getValue).getOrElse(""))
  }

  def notification(eventType: SubscriptionEventType, payload: EntityKeyValueWithEndpoint): Array[Byte] = {
    EntityKeyValueNotification.newBuilder
      .setValue(payload.payload)
      .setEventType(eventType)
      .build()
      .toByteArray
  }
}

object EntityEdgeSubscriptionDescriptor extends SubscriptionDescriptor[EntityEdge] {
  def keyParts(payload: EntityEdge): Seq[String] = {
    Seq(payload.getParent.getValue, payload.getChild.getValue, payload.getRelationship, payload.getDistance.toString)
  }

  def notification(eventType: SubscriptionEventType, payload: EntityEdge): Array[Byte] = {
    EntityEdgeNotification.newBuilder
      .setValue(payload)
      .setEventType(eventType)
      .build()
      .toByteArray
  }
}