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
package io.greenbus.loader.set

import java.util.UUID

import io.greenbus.msg.Session
import io.greenbus.client.service.ModelService
import io.greenbus.client.service.proto.Calculations.CalculationDescriptor
import io.greenbus.client.service.proto.Model._
import io.greenbus.client.service.proto.ModelRequests.{ EntityEdgeQuery, EntityKeySet }
import io.greenbus.loader.set.Downloader.DownloadSet
import io.greenbus.loader.set.Mdl._
import UUIDHelpers._

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

object Downloader {

  case class DownloadSet(
    modelEntities: Seq[Entity],
    points: Seq[Point],
    commands: Seq[Command],
    endpoints: Seq[Endpoint],
    edges: Seq[EntityEdge],
    keyValues: Seq[EntityKeyValue])

  val pageSizeForGet = 500
  val timeoutMs = 10000

  private def entityIdsToKeySet(ids: Seq[EntityId]): Option[EntityKeySet] = {

    val (uuids, names) = NameResolver.mostSpecificIdElements(ids)

    if (uuids.nonEmpty || names.nonEmpty) {
      Some(EntityKeySet.newBuilder()
        .addAllUuids(uuids.map(UUIDHelpers.uuidToProtoUUID))
        .addAllNames(names)
        .build())
    } else {
      None
    }
  }

  def downloadByIdsAndNames(session: Session, rootIds: Seq[EntityId], rootNames: Seq[String], endpointIds: Seq[EntityId]) = {

    val (uuids, names) = NameResolver.mostSpecificIdElements(rootIds)

    val rootKeySet = EntityKeySet.newBuilder()
      .addAllUuids(uuids.map(UUIDHelpers.uuidToProtoUUID))
      .addAllNames(names ++ rootNames)
      .build()

    download(session, rootKeySet, entityIdsToKeySet(endpointIds))
  }

  def downloadByIds(session: Session, rootIds: Seq[EntityId], endpointIds: Seq[EntityId]): DownloadSet = {

    val rootKeySet = entityIdsToKeySet(rootIds).getOrElse {
      throw new IllegalArgumentException("Must include at least one root id")
    }

    download(session, rootKeySet, entityIdsToKeySet(endpointIds))
  }

  def download(session: Session, rootNames: Seq[String]): DownloadSet = {

    val rootKeySet = EntityKeySet.newBuilder().addAllNames(rootNames).build()

    download(session, rootKeySet, None)
  }

  def download(session: Session, rootKeySet: EntityKeySet, endpointKeySetOpt: Option[EntityKeySet]): DownloadSet = {

    val modelClient = ModelService.client(session)

    val rootResults = Await.result(modelClient.get(rootKeySet), timeoutMs.milliseconds)

    if (rootResults.nonEmpty) {
      downloadSet(session, rootResults, endpointKeySetOpt)
    } else {
      DownloadSet(Seq(), Seq(), Seq(), Seq(), Seq(), Seq())
    }
  }

  private def downloadSet(session: Session, roots: Seq[Entity], endpointKeySetOpt: Option[EntityKeySet]): DownloadSet = {

    val rootUuids = roots.map(_.getUuid)

    val ownsSet = getOwnsSet(session, rootUuids)

    val ownsEntities = getEntities(session, ownsSet)

    val pointEntities = ownsEntities.filter(_.getTypesList.contains("Point"))
    val pointUuids = pointEntities.map(_.getUuid)
    val pointUuidSet = pointUuids.toSet
    val points = getPoints(session, pointUuids)

    val commandEntities = ownsEntities.filter(_.getTypesList.contains("Command"))
    val commandUuids = commandEntities.map(_.getUuid)
    val commandUuidSet = commandUuids.toSet
    val commands = getCommands(session, commandUuids)

    val allEdges = getAllEdges(session, ownsSet)

    val sourceEndpointUuids = endpointUuidsFromEdges(allEdges).distinct

    val endpointsBySource = getEndpoints(session, sourceEndpointUuids)

    val endpointsFromFragment = endpointKeySetOpt.map { keySet =>
      getEndpoints(session, keySet)
    }.getOrElse(Seq())

    val endpoints = distinctBy(endpointsBySource ++ endpointsFromFragment, { e: Endpoint => e.getUuid })
    val endpointUuids = endpoints.map(_.getUuid)

    val keyValues = getKeyValues(session, (ownsSet ++ endpointUuids).distinct)

    val modelEntities = ownsEntities.filterNot(ent => (pointUuidSet union commandUuidSet).contains(ent.getUuid))

    DownloadSet(modelEntities, points, commands, endpoints, allEdges, keyValues)
  }

  private def distinctBy[A, B](seq: Seq[A], id: A => B): Seq[A] = {
    val b = Seq.newBuilder[A]
    val seen = scala.collection.mutable.HashSet[B]()
    for (x <- seq) {
      val elemId = id(x)
      if (!seen(elemId)) {
        b += x
        seen += elemId
      }
    }
    b.result()
  }

  private def getOwnsSet(session: Session, roots: Seq[ModelUUID]): Seq[ModelUUID] = {
    val results = getOwnsSetEdges(session, roots)
    roots ++ results.map(_.getChild)
  }

  private def getOwnsSetEdges(session: Session, roots: Seq[ModelUUID]): Seq[EntityEdge] = {

    val modelClient = ModelService.client(session)

    def getEdgeQuery(last: Option[ModelID], size: Int): Future[Seq[EntityEdge]] = {
      val b = EntityEdgeQuery.newBuilder()
        .addAllParentUuids(roots)
        .addRelationships("owns")
        .setPageSize(size)

      last.foreach(b.setLastId)

      val query = b.build()

      modelClient.edgeQuery(query)
    }

    def pageBoundary(results: Seq[EntityEdge]): ModelID = {
      results.last.getId
    }

    allPages(pageSizeForGet, getEdgeQuery, pageBoundary)
  }

  private def endpointUuidsFromEdges(edges: Seq[EntityEdge]): Seq[ModelUUID] = {
    edges.filter(_.getRelationship == "source").map(_.getParent)
  }

  private def getAllEdges(session: Session, set: Seq[ModelUUID]): Seq[EntityEdge] = {

    val modelClient = ModelService.client(session)

    def parentQuery = {
      EntityEdgeQuery.newBuilder()
        .addAllParentUuids(set)
    }
    def childQuery = {
      EntityEdgeQuery.newBuilder()
        .addAllChildUuids(set)
    }

    def edgeQuery(build: => EntityEdgeQuery.Builder)(last: Option[ModelID], size: Int): Future[Seq[EntityEdge]] = {
      val b = build
        .setDepthLimit(1)
        .setPageSize(size)

      last.foreach(b.setLastId)

      val query = b.build()

      modelClient.edgeQuery(query)
    }

    def pageBoundary(results: Seq[EntityEdge]): ModelID = {
      results.last.getId
    }

    (allPages(pageSizeForGet, edgeQuery(parentQuery), pageBoundary) ++
      allPages(pageSizeForGet, edgeQuery(childQuery), pageBoundary)).distinct
  }

  def getObs[A](uuids: Seq[ModelUUID], get: EntityKeySet => Future[Seq[A]]): Seq[A] = {
    uuids.grouped(pageSizeForGet).flatMap { set =>
      if (set.nonEmpty) {
        val entKeySet = EntityKeySet.newBuilder().addAllUuids(set).build()
        Await.result(get(entKeySet), timeoutMs.milliseconds)
      } else {
        Seq()
      }
    }.toVector
  }

  private def getEntities(session: Session, uuids: Seq[ModelUUID]): Seq[Entity] = {
    val modelClient = ModelService.client(session)
    getObs(uuids, modelClient.get)
  }

  private def getPoints(session: Session, uuids: Seq[ModelUUID]): Seq[Point] = {
    val modelClient = ModelService.client(session)
    getObs(uuids, modelClient.getPoints)
  }

  private def getCommands(session: Session, uuids: Seq[ModelUUID]): Seq[Command] = {
    val modelClient = ModelService.client(session)
    getObs(uuids, modelClient.getCommands)
  }

  private def getEndpoints(session: Session, uuids: Seq[ModelUUID]): Seq[Endpoint] = {
    val modelClient = ModelService.client(session)
    getObs(uuids, modelClient.getEndpoints)
  }

  private def getEndpoints(session: Session, keySet: EntityKeySet): Seq[Endpoint] = {
    val modelClient = ModelService.client(session)
    Await.result(modelClient.getEndpoints(keySet), timeoutMs.milliseconds)
  }

  private def getKeyValues(session: Session, uuids: Seq[ModelUUID]): Seq[EntityKeyValue] = {
    val modelClient = ModelService.client(session)

    val keyPairs = uuids.grouped(pageSizeForGet).flatMap { set =>
      if (set.nonEmpty) {
        Await.result(modelClient.getEntityKeys(set), timeoutMs.milliseconds)
      } else {
        Seq()
      }
    }.toVector

    keyPairs.grouped(pageSizeForGet).flatMap { set =>
      if (set.nonEmpty) {
        Await.result(modelClient.getEntityKeyValues(set), timeoutMs.milliseconds)
      } else {
        Seq()
      }
    }.toVector
  }

  def allPages[A, B](pageSize: Int, getResults: (Option[B], Int) => Future[Seq[A]], pageBoundary: Seq[A] => B): Seq[A] = {

    def rest(accum: Seq[A], boundary: Option[B]): Seq[A] = {
      val results = Await.result(getResults(boundary, pageSize), timeoutMs.milliseconds)
      if (results.isEmpty || results.size < pageSize) {
        accum ++ results
      } else {
        rest(accum ++ results, Some(pageBoundary(results)))
      }
    }

    rest(Seq(), None)
  }
}

object NameResolver {

  val calculationKey = "calculation"

  def resolveFlatModelUuids(session: Session, model: FlatModelFragment): FlatModelFragment = {

    val allFields = model.modelEntities.map(_.fields) ++ model.points.map(_.fields) ++ model.commands.map(_.fields) ++ model.endpoints.map(_.fields)
    val allEntIds = allFields.map(_.id)
    val knownMap = entIdsToMap(allEntIds)

    val fromEdges = unresolvedEdgeUuids(model.edges, knownMap.keySet)

    val pointsWithCalcOpt: Seq[(PointDesc, Option[CalculationDescriptor])] = model.points.map { p =>
      val (calcSeq, otherKvs) = p.fields.kvs.partition(_._1 == calculationKey)
      val calcOpt = calcSeq.headOption.map(_._2).map(valueHolderToCalc)

      (p.copy(fields = p.fields.copy(kvs = otherKvs)), calcOpt)
    }

    val allCalcDescs = pointsWithCalcOpt.flatMap(_._2)

    val fromCalcs = allCalcDescs.flatMap(uuidsFromCalc).filterNot(knownMap.keySet.contains)

    val allUnresolved = fromEdges ++ fromCalcs

    val entities = lookupEntitiesByUuid(session, allUnresolved)

    val resolvedUuidToNames = entities.map(e => (UUID.fromString(e.getUuid.getValue), e.getName)).toMap

    val fullMap: Map[UUID, String] = knownMap ++ resolvedUuidToNames

    def resolveEntId(id: EntityId): EntityId = {
      id match {
        case UuidEntId(uuid) =>
          val name = fullMap.getOrElse(uuid, throw new LoadingException("UUID was not resolved: " + uuid))
          FullEntId(uuid, name)
        case x => x
      }
    }

    val resolvedEdges = model.edges.map(e => e.copy(parent = resolveEntId(e.parent), child = resolveEntId(e.child)))

    val resolvedPointDescs = pointsWithCalcOpt.map {
      case (pointDesc, Some(calcDesc)) => pointDesc.copy(calcHolder = NamesResolvedCalc(calcDesc, fullMap))
      case (pointDesc, None) => pointDesc.copy(calcHolder = NoCalculation)
    }

    model.copy(edges = resolvedEdges, points = resolvedPointDescs)
  }

  def resolveExternalReferences(session: Session, uuidRefs: Seq[UUID], nameRefs: Seq[String], downloadSet: DownloadSet): (Set[UUID], Set[String], Seq[(UUID, String)]) = {

    val downloadSetTuples = downloadTuples(downloadSet)
    val downloadUuidsSet = downloadSetTuples.map(_._1).toSet
    val downloadNamesSet = downloadSetTuples.map(_._2).toSet

    val unresUuidRefs = uuidRefs.filterNot(downloadUuidsSet.contains)
    val unresNameRefs = nameRefs.filterNot(downloadNamesSet.contains)

    val uuidEnts = lookupEntitiesByUuid(session, unresUuidRefs)
    val nameEnts = lookupEntitiesByName(session, unresNameRefs)

    val resolveTups = (uuidEnts.map(obj => (obj.getUuid, obj.getName)) ++
      nameEnts.map(obj => (obj.getUuid, obj.getName)))
      .map { case (uuid, name) => (protoUUIDToUuid(uuid), name) }

    val resolveUuids = resolveTups.map(_._1)
    val resolveNames = resolveTups.map(_._2)

    (downloadUuidsSet ++ resolveUuids, downloadNamesSet ++ resolveNames, downloadSetTuples ++ resolveTups)
  }

  private def downloadTuples(downloadSet: DownloadSet) = {
    (downloadSet.modelEntities.map(obj => (obj.getUuid, obj.getName)) ++
      downloadSet.points.map(obj => (obj.getUuid, obj.getName)) ++
      downloadSet.commands.map(obj => (obj.getUuid, obj.getName)) ++
      downloadSet.endpoints.map(obj => (obj.getUuid, obj.getName)))
      .map { case (uuid, name) => (protoUUIDToUuid(uuid), name) }
  }

  def lookupEntitiesByUuid(session: Session, set: Seq[UUID]): Seq[Entity] = {
    val modelClient = ModelService.client(session)
    set.grouped(Downloader.pageSizeForGet).flatMap { uuids =>
      if (uuids.nonEmpty) {
        val modelUuids = uuids.map(u => ModelUUID.newBuilder().setValue(u.toString).build())
        val keySet = EntityKeySet.newBuilder().addAllUuids(modelUuids).build()
        Await.result(modelClient.get(keySet), Downloader.timeoutMs.milliseconds)
      } else {
        Seq()
      }
    }.toVector
  }

  def lookupEntitiesByName(session: Session, set: Seq[String]): Seq[Entity] = {
    val modelClient = ModelService.client(session)
    set.grouped(Downloader.pageSizeForGet).flatMap { uuids =>
      if (uuids.nonEmpty) {
        val keySet = EntityKeySet.newBuilder().addAllNames(set).build()
        Await.result(modelClient.get(keySet), Downloader.timeoutMs.milliseconds)
      } else {
        Seq()
      }
    }.toVector
  }

  private def uuidsFromCalc(calc: CalculationDescriptor): Seq[UUID] = {
    calc.getCalcInputsList.map(_.getPointUuid).map(uuid => UUID.fromString(uuid.getValue))
  }

  def mostSpecificIdElements(ids: Seq[EntityId]): (Seq[UUID], Seq[String]) = {
    val uuids = Vector.newBuilder[UUID]
    val names = Vector.newBuilder[String]

    ids.foreach {
      case FullEntId(uuid, _) => uuids += uuid
      case UuidEntId(uuid) => uuids += uuid
      case NamedEntId(name) => names += name
    }

    (uuids.result(), names.result())
  }

  def entIdsToMap(ids: Seq[EntityId]): Map[UUID, String] = {
    ids.flatMap {
      case FullEntId(uuid, name) => Some((uuid, name))
      case _ => None
    }.toMap
  }

  def uuidsFromEntIds(ids: Seq[EntityId]): Seq[UUID] = {
    ids.flatMap {
      case UuidEntId(uuid) => Some(uuid)
      case _ => None
    }
  }

  def unresolvedEdgeUuids(descs: Seq[EdgeDesc], known: Set[UUID]): Seq[UUID] = {
    val allEntIds = descs.flatMap(e => Seq(e.child, e.parent))
    val uuids = uuidsFromEntIds(allEntIds)
    uuids.filterNot(known.contains)
  }

  private def valueHolderToCalc(v: ValueHolder): CalculationDescriptor = {
    val byteArray = v match {
      case ByteArrayValue(bytes) => bytes
      case SimpleValue(sv) => if (sv.hasByteArrayValue) sv.getByteArrayValue.toByteArray else throw new LoadingException("Calculation key value was not a byte array")
      case _ => throw new LoadingException("Calculation key value was not a byte array")
    }

    try {
      CalculationDescriptor.parseFrom(byteArray)
    } catch {
      case ex: Throwable =>
        throw new LoadingException("Could not parse calculation stored value: " + ex)
    }
  }
}

object DownloadConversion {

  implicit def protoUUIDToUuid(uuid: ModelUUID): UUID = UUID.fromString(uuid.getValue)

  private def toEntityDesc(ent: Entity, kvs: Seq[(String, StoredValue)]): EntityDesc = {
    EntityDesc(
      EntityFields(
        FullEntId(ent.getUuid, ent.getName),
        ent.getTypesList.toSet,
        kvs.map(tup => (tup._1, storedValueToRepr(tup._2)))))
  }

  private def toPointDesc(p: Point, kvs: Seq[(String, StoredValue)]): PointDesc = {
    PointDesc(
      EntityFields(
        FullEntId(p.getUuid, p.getName),
        p.getTypesList.toSet,
        kvs.map(tup => (tup._1, storedValueToRepr(tup._2)))),
      p.getPointCategory,
      p.getUnit,
      CalcNotChecked)
  }

  private def toCommandDesc(c: Command, kvs: Seq[(String, StoredValue)]): CommandDesc = {
    CommandDesc(
      EntityFields(
        FullEntId(c.getUuid, c.getName),
        c.getTypesList.toSet,
        kvs.map(tup => (tup._1, storedValueToRepr(tup._2)))),
      c.getDisplayName,
      c.getCommandCategory)
  }

  private def toEndpointDesc(e: Endpoint, kvs: Seq[(String, StoredValue)]): EndpointDesc = {
    EndpointDesc(
      EntityFields(
        FullEntId(e.getUuid, e.getName),
        e.getTypesList.toSet,
        kvs.map(tup => (tup._1, storedValueToRepr(tup._2)))),
      e.getProtocol)
  }

  def storedValueToRepr(v: StoredValue): ValueHolder = {
    if (v.hasByteArrayValue) {
      ByteArrayValue(v.getByteArrayValue.toByteArray)
    } else {
      SimpleValue(v)
    }
  }

  def downloadToIntermediate(set: DownloadSet): Mdl.FlatModelFragment = {
    import Mdl._

    val kvMap: Map[ModelUUID, Seq[(String, StoredValue)]] = set.keyValues.groupBy(_.getUuid).map {
      case (uuid, ekvs) => (uuid, ekvs.map(ekv => (ekv.getKey, ekv.getValue)))
    }

    def kvsForUuid(uuid: ModelUUID) = kvMap.getOrElse(uuid, Seq())

    val modelDescs = set.modelEntities.map(ent => toEntityDesc(ent, kvsForUuid(ent.getUuid)))

    val pointDescs = set.points.map(pt => toPointDesc(pt, kvsForUuid(pt.getUuid)))

    val commandDescs = set.commands.map(cmd => toCommandDesc(cmd, kvsForUuid(cmd.getUuid)))

    val endpointDescs = set.endpoints.map(end => toEndpointDesc(end, kvsForUuid(end.getUuid)))

    val edgeDescs = set.edges.map(e => EdgeDesc(UuidEntId(e.getParent), e.getRelationship, UuidEntId(e.getChild)))

    FlatModelFragment(modelDescs, pointDescs, commandDescs, endpointDescs, edgeDescs)
  }
}
