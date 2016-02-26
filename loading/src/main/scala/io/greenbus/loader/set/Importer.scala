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

import java.util
import java.util.UUID

import io.greenbus.msg.Session
import io.greenbus.client.service.proto.Calculations.{ CalculationInput, CalculationDescriptor }
import io.greenbus.client.service.proto.Model._
import io.greenbus.loader.set.Actions.ActionsList
import io.greenbus.loader.set.Differences.{ DiffRecord, EntBasedChange, EntBasedCreate, ParamChange }
import io.greenbus.loader.set.Downloader.DownloadSet
import io.greenbus.loader.set.Mdl._

import scala.collection.JavaConversions._

object Importer {

  import io.greenbus.loader.set.UUIDHelpers._

  def runImport(session: Session,
    optCommandRoots: Option[Seq[String]],
    xmlRoots: Seq[EntityId],
    flatModel: FlatModelFragment,
    prompt: Boolean = true) = {

    val fragmentEndpointIds = flatModel.endpoints.map(_.fields.id)

    val downloadSet = optCommandRoots.map { roots =>
      Downloader.downloadByIdsAndNames(session, xmlRoots, roots, fragmentEndpointIds)
    }.getOrElse {
      Downloader.downloadByIds(session, xmlRoots, fragmentEndpointIds)
    }

    val ((actions, diff), idTuples) = Importer.importDiff(session, flatModel, downloadSet)

    Importer.summarize(diff)

    if (!diff.isEmpty) {
      if (prompt) {
        println("Proceed with modifications? (y/N)")
        val answer = readLine()
        if (Set("y", "yes").contains(answer.trim.toLowerCase)) {
          Upload.push(session, actions, idTuples)
        }
      } else {
        Upload.push(session, actions, idTuples)
      }
    }
  }

  def importDiff(session: Session, update: FlatModelFragment, downloadSet: DownloadSet): ((ActionsList, DiffRecord), Seq[(UUID, String)]) = {

    performDiff(update, downloadSet, NameResolver.resolveExternalReferences(session, _, _, _), NameResolver.lookupEntitiesByName(session, _))
  }

  type ResolveNames = (Seq[UUID], Seq[String], DownloadSet) => (Set[UUID], Set[String], Seq[(UUID, String)])

  def performDiff(update: FlatModelFragment, downloadSet: DownloadSet, resolveFunc: ResolveNames, getEntsForNames: Seq[String] => Seq[Entity]): ((ActionsList, DiffRecord), Seq[(UUID, String)]) = {

    val namesOfModelObjects = SetDiff.namesInSet(update)

    SetDiff.checkForNameDuplicatesInFragment(namesOfModelObjects)

    SetDiff.checkForDuplicateKeys(update)

    checkForDuplicateNamesOfExternalObjects(update, downloadSet, getEntsForNames)

    checkForDuplicateEdges(update)

    val (extUuids, extNames, updateTuples) = SetDiff.analyzeIdentities(update)

    val (foundUuids, foundNames, resolvedTuples) = resolveFunc(extUuids, extNames, downloadSet)

    val resolvedFlat = SetDiff.resolveIdentities(update, foundUuids, foundNames)

    val allIdTuples = resolvedTuples ++ updateTuples

    (compare(resolvedFlat, allIdTuples, downloadSet), allIdTuples)
  }

  def checkForDuplicateEdges(model: FlatModelFragment): Unit = {

    val namesOnly: Seq[(String, String, String)] = model.edges.flatMap { e =>
      for {
        parentName <- EntityId.toNameOpt(e.parent)
        childName <- EntityId.toNameOpt(e.child)
      } yield (parentName, e.relationship, childName)
    }

    val duplicates = namesOnly.groupBy(a => a).filter { case (k, v) => v.size > 1 }.values.map(_.head)

    if (duplicates.nonEmpty) {
      throw new LoadingException("The following edges were duplicated: \n\n" + duplicates.map {
        case (parent, rel, child) =>
          "\t" + parent + " --[" + rel + "]--> " + child
      }.mkString("\n"))
    }
  }

  def checkForDuplicateNamesOfExternalObjects(model: FlatModelFragment, downloadSet: DownloadSet, getEntsForNames: Seq[String] => Seq[Entity]): Unit = {

    val nameToUuidInDownloadSet: Seq[(String, UUID)] =
      (downloadSet.modelEntities.map(obj => (obj.getName, obj.getUuid)) ++
        downloadSet.points.map(obj => (obj.getName, obj.getUuid)) ++
        downloadSet.commands.map(obj => (obj.getName, obj.getUuid)) ++
        downloadSet.endpoints.map(obj => (obj.getName, obj.getUuid)))
        .map(tup => (tup._1, UUIDHelpers.protoUUIDToUuid(tup._2)))

    val nameToUuidInSetMap = nameToUuidInDownloadSet.toMap

    val allObjIdsInFrag = model.modelEntities.map(_.fields.id) ++
      model.points.map(_.fields.id) ++
      model.commands.map(_.fields.id) ++
      model.endpoints.map(_.fields.id)

    val (namedOnly, uuidNamePairs) = SetDiff.objectIdBreakdown(allObjIdsInFrag)

    val (namedExisting, namedNotExistingInFrag) = namedOnly.partition(nameToUuidInSetMap.contains)

    val allUuidNamePairs: Seq[(String, UUID)] = uuidNamePairs.map(t => (t._2, t._1)) ++ namedExisting.flatMap(n => nameToUuidInSetMap.get(n).map(u => (n, u)))

    val allObjNames = namedNotExistingInFrag ++ allUuidNamePairs.map(_._1)

    val entsForNames = getEntsForNames(allObjNames)

    val nameToUuidInSystem = entsForNames.map(e => (e.getName, UUIDHelpers.protoUUIDToUuid(e.getUuid))).toMap

    namedNotExistingInFrag.foreach { name =>
      if (nameToUuidInSystem.contains(name)) {
        throw new LoadingException(s"Attempting to create $name but it already exists in the system")
      }
    }

    allUuidNamePairs.foreach {
      case (name, uuid) =>
        nameToUuidInSystem.get(name) match {
          case None =>
          case Some(lookupUuid) =>
            if (uuid != lookupUuid) {
              throw new LoadingException(s"Attempting to use name $name but it already exists in the system")
            }
        }
    }
  }

  def summarize(diff: DiffRecord): Unit = {

    def commonCreate(created: EntBasedCreate): Unit = {
      println("\t" + created.name)
      if (created.withUuid) {
        println("\t\t(* with UUID)")
      }
      println("\t\tTypes: " + created.types.mkString(", "))
      println("\t\tKey values: " + created.keys.mkString(", "))
    }

    def commonChange(changed: EntBasedChange): Unit = {
      println("\t" + changed.currentName)
      changed.nameOpt.foreach {
        case ParamChange(prev, now) => println(s"\t\tName change: $prev -> $now")
      }
      if (changed.addedTypes.nonEmpty) {
        println("\t\tTypes added:   " + changed.addedTypes.mkString(", "))
      }
      if (changed.removedTypes.nonEmpty) {
        println("\t\tTypes removed: " + changed.removedTypes.mkString(", "))
      }
      if (changed.addedKeys.nonEmpty) {
        println("\t\tKey values added:   " + changed.addedKeys.mkString(", "))
      }
      if (changed.modifiedKeys.nonEmpty) {
        println("\t\tKey values modified: " + changed.modifiedKeys.mkString(", "))
      }
      if (changed.removedKeys.nonEmpty) {
        println("\t\tKey values removed: " + changed.removedKeys.mkString(", "))
      }
    }

    def printParam[A](name: String, opt: Option[ParamChange[A]]): Unit = {
      opt.foreach {
        case ParamChange(prev, now) => println(s"\t\t$name change: ${prev.toString} -> ${now.toString}")
      }
    }

    if (diff.isEmpty) {
      println("No changes.")
    } else {

      if (diff.entCreateRecords.nonEmpty) {
        println("Created entities: ")
        diff.entCreateRecords.foreach { created =>
          commonCreate(created)
        }
        println()
      }

      if (diff.entChangedRecords.nonEmpty) {
        println("Modified entities: ")
        diff.entChangedRecords.foreach { changed =>
          commonChange(changed)
        }
        println()
      }

      if (diff.entDeleteRecords.nonEmpty) {
        println("Deleted entities: ")
        diff.entDeleteRecords.foreach { deleted =>
          println("\t" + deleted.name)
        }
        println()
      }

      if (diff.pointCreateRecords.nonEmpty) {
        println("Created points: ")
        diff.pointCreateRecords.foreach { created =>
          commonCreate(created)
          println("\t\tCategory: " + created.category)
          println("\t\tUnit: " + created.unit)
        }
        println()
      }

      if (diff.pointChangedRecords.nonEmpty) {
        println("Modified points: ")
        diff.pointChangedRecords.foreach { changed =>
          commonChange(changed)
          printParam("Category", changed.categoryOpt)
          printParam("Unit", changed.unitOpt)
        }
        println()
      }

      if (diff.pointDeleteRecords.nonEmpty) {
        println("Deleted points: ")
        diff.pointDeleteRecords.foreach { deleted =>
          println("\t" + deleted.name)
        }
        println()
      }

      if (diff.commandCreateRecords.nonEmpty) {
        println("Created commands: ")
        diff.commandCreateRecords.foreach { created =>
          commonCreate(created)
          println("\t\tCategory: " + created.category)
          println("\t\tDisplay name: " + created.displayName)
        }
        println()
      }

      if (diff.commandChangedRecords.nonEmpty) {
        println("Modified commands: ")
        diff.commandChangedRecords.foreach { changed =>
          commonChange(changed)
          printParam("Category", changed.categoryOpt)
          printParam("Display name", changed.displayNameOpt)
        }
        println()
      }

      if (diff.commandDeleteRecords.nonEmpty) {
        println("Deleted commands: ")
        diff.commandDeleteRecords.foreach { deleted =>
          println("\t" + deleted.name)
        }
        println()
      }

      if (diff.endpointCreateRecords.nonEmpty) {
        println("Created endpoints: ")
        diff.endpointCreateRecords.foreach { created =>
          commonCreate(created)
          println("\t\tProtocol: " + created.protocol)
        }
        println()
      }

      if (diff.endpointChangedRecords.nonEmpty) {
        println("Modified endpoints: ")
        diff.endpointChangedRecords.foreach { changed =>
          commonChange(changed)
          printParam("Protocol", changed.protocolOpt)
        }
        println()
      }

      if (diff.endpointDeleteRecords.nonEmpty) {
        println("Deleted endpoints: ")
        diff.endpointDeleteRecords.foreach { deleted =>
          println("\t" + deleted.name)
        }
        println()
      }

      if (diff.edgeCreateRecords.nonEmpty) {
        println("Created edges: ")
        diff.edgeCreateRecords.foreach { edge =>
          println("\t" + edge.parent + " --[" + edge.relation + "]--> " + edge.child)
        }
        println()
      }

      if (diff.edgeDeleteRecords.nonEmpty) {
        println("Deleted edges: ")
        diff.edgeDeleteRecords.foreach { edge =>
          println("\t" + edge.parent + " --[" + edge.relation + "]--> " + edge.child)
        }
        println()
      }

    }

  }

  def compare(update: FlatModelFragment, resolvedIds: Seq[(UUID, String)], downloadSet: DownloadSet): (ActionsList, DiffRecord) = {
    import io.greenbus.loader.set.Actions._
    import io.greenbus.loader.set.Differences._

    val resolvedNameMap = resolvedIds.map(tup => (tup._2, tup._1)).toMap
    val resolvedUuidMap = resolvedIds.toMap

    val uuidToEnt = downloadSet.modelEntities.map(obj => (protoUUIDToUuid(obj.getUuid), obj)).toMap
    val nameToEnt = downloadSet.modelEntities.map(obj => (obj.getName, obj)).toMap

    val uuidToPoint = downloadSet.points.map(obj => (protoUUIDToUuid(obj.getUuid), obj)).toMap
    val nameToPoint = downloadSet.points.map(obj => (obj.getName, obj)).toMap

    val uuidToCommand = downloadSet.commands.map(obj => (protoUUIDToUuid(obj.getUuid), obj)).toMap
    val nameToCommand = downloadSet.commands.map(obj => (obj.getName, obj)).toMap

    val uuidToEndpoint = downloadSet.endpoints.map(obj => (protoUUIDToUuid(obj.getUuid), obj)).toMap
    val nameToEndpoint = downloadSet.endpoints.map(obj => (obj.getName, obj)).toMap

    val uuidToKeyValues: Map[UUID, Seq[EntityKeyValue]] = downloadSet.keyValues.groupBy(kv => protoUUIDToUuid(kv.getUuid))

    val cache = new ActionCache
    val records = new RecordBuilder

    def handleNewKeyValues(uuidOpt: Option[UUID], name: String, kvs: Seq[(String, ValueHolder)]): Unit = {
      kvs.foreach {
        case (key, vh) =>
          uuidOpt match {
            case None => cache.keyValuePutByNames += PutKeyValueByName(name, key, vh)
            case Some(uuid) => cache.keyValuePutByUuids += PutKeyValueByUuid(uuid, key, vh)
          }
      }
    }

    def handleKvs(uuid: UUID, name: String, kvs: Seq[(String, ValueHolder)]): (Set[String], Set[String], Set[String]) = {
      val currentKvs = uuidToKeyValues.getOrElse(uuid, Seq()).filterNot(_.getKey == "calculation")
      val (keyPuts, keysAdded, keysModified, keysDeleted) = keySetDiff(currentKvs, kvs)

      keyPuts.foreach(p => cache.keyValuePutByUuids += PutKeyValueByUuid(uuid, p._1, p._2))
      keysDeleted.foreach(k => cache.keyValueDeletes += DeleteKeyValue(uuid, k))

      (keysAdded, keysModified, keysDeleted)
    }

    def typeDiff(existing: Set[String], update: Set[String]): (Set[String], Set[String]) = {
      val typesToAdd = update diff existing
      val typesToRemove = existing diff update
      (typesToAdd, typesToRemove)
    }

    def optParamChange[A](old: A, next: A): Option[ParamChange[A]] = {
      if (old != next) Some(ParamChange(old, next)) else None
    }

    def handleEntityUpdate(uuid: UUID, name: String, ent: EntityDesc, existingEnt: Entity): Unit = {

      val nameChangeOpt = optParamChange(existingEnt.getName, name)

      val (typesToAdd, typesToRemove) = typeDiff(existingEnt.getTypesList.toSet, ent.fields.types)
      val (keysAdded, keysModified, keysDeleted) = handleKvs(uuid, name, ent.fields.kvs)
      val keyChange = keysAdded.nonEmpty || keysModified.nonEmpty || keysDeleted.nonEmpty

      if (name != existingEnt.getName || typesToAdd.nonEmpty || typesToRemove.nonEmpty || keyChange) {
        cache.entityPuts += PutEntity(Some(uuid), name, ent.fields.types)
        records.entChangedRecords += EntityChanged(existingEnt.getName, nameChangeOpt, typesToAdd, typesToRemove, keysAdded, keysModified, keysDeleted)
      }
    }

    def handleNewEntity(uuidOpt: Option[UUID], name: String, ent: EntityDesc) {
      cache.entityPuts += PutEntity(uuidOpt, name, ent.fields.types)
      val keys = ent.fields.kvs.map(_._1).toSet
      records.entCreateRecords += EntityCreated(name, ent.fields.types, keys, withUuid = uuidOpt.nonEmpty)
      handleNewKeyValues(uuidOpt, name, ent.fields.kvs)
    }

    def handlePointUpdate(uuid: UUID, name: String, pt: PointDesc, existing: Point): Unit = {

      val nameChangeOpt = optParamChange(existing.getName, name)

      val (typesToAdd, typesToRemove) = typeDiff(existing.getTypesList.toSet, pt.fields.types)

      val existCalcOpt = uuidToKeyValues.getOrElse(uuid, Seq()).find(_.getKey == "calculation").map(_.getValue)

      val (calcAdd, calcModify, calcRemove) = calcDiff(pt.calcHolder, existCalcOpt, resolvedNameMap) match {
        case CalcSame => (Seq(), Seq(), Seq())
        case CalcCreate =>
          cache.calcPutsByUuid += PutCalcByUuid(uuid, pt.calcHolder)
          (Seq("calculation"), Seq(), Seq())
        case CalcUpdate =>
          cache.calcPutsByUuid += PutCalcByUuid(uuid, pt.calcHolder)
          (Seq(), Seq("calculation"), Seq())
        case CalcDelete =>
          cache.keyValueDeletes += DeleteKeyValue(uuid, "calculation")
          (Seq(), Seq(), Seq("calculation"))
      }

      val pointKeys = pt.fields.kvs.filterNot(_._1 == "calculation")
      val (nonCalcKeysAdded, nonCalcKeysModified, nonCalcKeysDeleted) = handleKvs(uuid, name, pointKeys)
      val keysAdded = nonCalcKeysAdded ++ calcAdd
      val keysModified = nonCalcKeysModified ++ calcModify
      val keysDeleted = nonCalcKeysDeleted ++ calcRemove

      val keyChange = keysAdded.nonEmpty || keysModified.nonEmpty || keysDeleted.nonEmpty

      val pointCategoryOpt = optParamChange(existing.getPointCategory, pt.pointCategory)
      val unitOpt = optParamChange(existing.getUnit, pt.unit)

      if (name != existing.getName || typesToAdd.nonEmpty || typesToRemove.nonEmpty || keyChange || pointCategoryOpt.nonEmpty || unitOpt.nonEmpty) {
        cache.pointPuts += PutPoint(Some(uuid), name, pt.fields.types, pt.pointCategory, pt.unit)
        records.pointChangedRecords += PointChanged(existing.getName, nameChangeOpt, typesToAdd, typesToRemove, keysAdded, keysModified, keysDeleted, pointCategoryOpt, unitOpt)
      }
    }

    def handleNewPoint(uuidOpt: Option[UUID], name: String, pt: PointDesc) {
      cache.pointPuts += PutPoint(uuidOpt, name, pt.fields.types, pt.pointCategory, pt.unit)

      pt.calcHolder match {
        case NoCalculation =>
        case _ =>
          uuidOpt match {
            case None => cache.calcPutsByName += PutCalcByName(name, pt.calcHolder)
            case Some(uuid) => cache.calcPutsByUuid += PutCalcByUuid(uuid, pt.calcHolder)
          }
      }

      val allKvs = pt.fields.kvs //++ calcKvSeq

      val keys = allKvs.map(_._1).toSet
      records.pointCreateRecords += PointCreated(name, pt.fields.types, keys, pt.pointCategory, pt.unit, withUuid = uuidOpt.nonEmpty)
      handleNewKeyValues(uuidOpt, name, allKvs)
    }

    def handleCommandUpdate(uuid: UUID, name: String, cmd: CommandDesc, existing: Command): Unit = {

      val nameChangeOpt = optParamChange(existing.getName, name)

      val (typesToAdd, typesToRemove) = typeDiff(existing.getTypesList.toSet, cmd.fields.types)
      val (keysAdded, keysModified, keysDeleted) = handleKvs(uuid, name, cmd.fields.kvs)
      val keyChange = keysAdded.nonEmpty || keysModified.nonEmpty || keysDeleted.nonEmpty

      val commandCategoryOpt = optParamChange(existing.getCommandCategory, cmd.commandCategory)
      val displayNameOpt = optParamChange(existing.getDisplayName, cmd.displayName)

      if (name != existing.getName || typesToAdd.nonEmpty || typesToRemove.nonEmpty || keyChange || commandCategoryOpt.nonEmpty || displayNameOpt.nonEmpty) {
        cache.commandPuts += PutCommand(Some(uuid), name, cmd.fields.types, cmd.commandCategory, cmd.displayName)
        records.commandChangedRecords += CommandChanged(existing.getName, nameChangeOpt, typesToAdd, typesToRemove, keysAdded, keysModified, keysDeleted, commandCategoryOpt, displayNameOpt)
      }
    }

    def handleNewCommand(uuidOpt: Option[UUID], name: String, cmd: CommandDesc) {
      cache.commandPuts += PutCommand(uuidOpt, name, cmd.fields.types, cmd.commandCategory, cmd.displayName)
      val keys = cmd.fields.kvs.map(_._1).toSet
      records.commandCreateRecords += CommandCreated(name, cmd.fields.types, keys, cmd.commandCategory, cmd.displayName, withUuid = uuidOpt.nonEmpty)
      handleNewKeyValues(uuidOpt, name, cmd.fields.kvs)
    }

    def handleEndpointUpdate(uuid: UUID, name: String, end: EndpointDesc, existing: Endpoint): Unit = {

      val nameChangeOpt = optParamChange(existing.getName, name)

      val (typesToAdd, typesToRemove) = typeDiff(existing.getTypesList.toSet, end.fields.types)
      val (keysAdded, keysModified, keysDeleted) = handleKvs(uuid, name, end.fields.kvs)
      val keyChange = keysAdded.nonEmpty || keysModified.nonEmpty || keysDeleted.nonEmpty

      val protocolOpt = optParamChange(existing.getProtocol, end.protocol)

      if (name != existing.getName || typesToAdd.nonEmpty || typesToRemove.nonEmpty || keyChange || protocolOpt.nonEmpty) {
        cache.endpointPuts += PutEndpoint(Some(uuid), name, end.fields.types, end.protocol)
        records.endpointChangedRecords += EndpointChanged(existing.getName, nameChangeOpt, typesToAdd, typesToRemove, keysAdded, keysModified, keysDeleted, protocolOpt)
      }
    }

    def handleNewEndpoint(uuidOpt: Option[UUID], name: String, end: EndpointDesc) {
      cache.endpointPuts += PutEndpoint(uuidOpt, name, end.fields.types, end.protocol)
      val keys = end.fields.kvs.map(_._1).toSet
      records.endpointCreateRecords += EndpointCreated(name, end.fields.types, keys, end.protocol, withUuid = uuidOpt.nonEmpty)
      handleNewKeyValues(uuidOpt, name, end.fields.kvs)
    }

    def handleObject[Desc, Proto](
      obj: Desc,
      fields: EntityFields,
      uuidMap: Map[UUID, Proto],
      nameMap: Map[String, Proto],
      handleUpdate: (UUID, String, Desc, Proto) => Unit,
      handleNew: (Option[UUID], String, Desc) => Unit,
      protoToUuid: Proto => ModelUUID): Unit = {

      fields.id match {
        case FullEntId(uuid, name) =>
          // could be import of old system to a new DB
          // could be name/param change of existing (including no change at all)
          uuidMap.get(uuid) match {
            case None => handleNew(Some(uuid), name, obj)
            case Some(existing) => handleUpdate(uuid, name, obj, existing)
          }
        case NamedEntId(name) =>
          nameMap.get(name) match {
            case None => handleNew(None, name, obj)
            case Some(existing) => handleUpdate(protoUUIDToUuid(protoToUuid(existing)), name, obj, existing)
          }
        case _ => throw new LoadingException("Entity must have name")
      }
    }

    def splitIdSets(ids: Seq[EntityId]): (Set[UUID], Set[String]) = {
      val (onlyUuids, onlyNames, full) = SetDiff.idReferenceBreakdown(ids)
      ((onlyUuids ++ full.map(_._1)).toSet, onlyNames.toSet)
    }

    // if the uuid is present we MUST match that; ignore name
    val (fragModelUuidSet, fragModelNameSet) = splitIdSets(update.modelEntities.map(_.fields.id))
    val (fragPointUuidSet, fragPointNameSet) = splitIdSets(update.points.map(_.fields.id))
    val (fragCommandUuidSet, fragCommandNameSet) = splitIdSets(update.commands.map(_.fields.id))
    val (fragEndpointUuidSet, fragEndpointNameSet) = splitIdSets(update.endpoints.map(_.fields.id))

    downloadSet.modelEntities.foreach { obj =>
      val uuid = UUIDHelpers.protoUUIDToUuid(obj.getUuid)
      if (!fragModelUuidSet.contains(uuid) && !fragModelNameSet.contains(obj.getName)) {
        cache.entityDeletes += DeleteEntity(uuid)
        records.entDeleteRecords += EntityDeleted(obj.getName)
      }
    }

    downloadSet.points.foreach { obj =>
      val uuid = UUIDHelpers.protoUUIDToUuid(obj.getUuid)
      if (!fragPointUuidSet.contains(uuid) && !fragPointNameSet.contains(obj.getName)) {
        cache.pointDeletes += DeletePoint(uuid)
        records.pointDeleteRecords += PointDeleted(obj.getName)
      }
    }
    downloadSet.commands.foreach { obj =>
      val uuid = UUIDHelpers.protoUUIDToUuid(obj.getUuid)
      if (!fragCommandUuidSet.contains(uuid) && !fragCommandNameSet.contains(obj.getName)) {
        cache.commandDeletes += DeleteCommand(uuid)
        records.commandDeleteRecords += CommandDeleted(obj.getName)
      }
    }
    downloadSet.endpoints.foreach { obj =>
      val uuid = UUIDHelpers.protoUUIDToUuid(obj.getUuid)
      if (!fragEndpointUuidSet.contains(uuid) && !fragEndpointNameSet.contains(obj.getName)) {
        cache.endpointDeletes += DeleteEndpoint(uuid)
        records.endpointDeleteRecords += EndpointDeleted(obj.getName)
      }
    }

    update.modelEntities.foreach { ent =>
      handleObject(ent, ent.fields, uuidToEnt, nameToEnt, handleEntityUpdate, handleNewEntity, { proto: Entity => proto.getUuid })
    }

    update.points.foreach { pt =>
      handleObject(pt, pt.fields, uuidToPoint, nameToPoint, handlePointUpdate, handleNewPoint, { proto: Point => proto.getUuid })
    }

    update.commands.foreach { cmd =>
      handleObject(cmd, cmd.fields, uuidToCommand, nameToCommand, handleCommandUpdate, handleNewCommand, { proto: Command => proto.getUuid })
    }

    update.endpoints.foreach { end =>
      handleObject(end, end.fields, uuidToEndpoint, nameToEndpoint, handleEndpointUpdate, handleNewEndpoint, { proto: Endpoint => proto.getUuid })
    }

    val (edgeAdds, edgeDeletes) = edgeDiffWhileUnresolved(update.edges, downloadSet.edges, resolvedUuidMap, resolvedNameMap)
    edgeAdds.foreach { desc =>
      cache.edgePuts += PutEdge(desc)
      records.edgeCreateRecords += EdgeRecord(EntityId.prettyPrint(desc.parent), desc.relationship, EntityId.prettyPrint(desc.child))
    }

    edgeDeletes.foreach { tup =>
      cache.edgeDeletes += DeleteEdge(tup._1, tup._2, tup._3)
      records.edgeDeleteRecords += EdgeRecord(resolvedUuidMap(tup._1), tup._2, resolvedUuidMap(tup._3))
    }

    (cache.result(), records.result())
  }

  def keySetDiff(original: Seq[EntityKeyValue], update: Seq[(String, ValueHolder)]): (Seq[(String, ValueHolder)], Set[String], Set[String], Set[String]) = {
    val originalKeyMap = original.map(kv => (kv.getKey, kv)).toMap
    val originalKeySet = originalKeyMap.keySet

    val updateKeyMap = update.map(kv => (kv._1, kv._2)).toMap
    val updateKeySet = updateKeyMap.keySet

    val keysToAdd = updateKeySet diff originalKeySet
    val keysToRemove = originalKeySet diff updateKeySet

    val possibleChangeSet = updateKeyMap -- keysToAdd

    val changed = possibleChangeSet.filter {
      case (key, vh) =>
        val origKv = originalKeyMap(key)
        vh match {
          case SimpleValue(sv) => !origKv.getValue.equals(sv)
          case ByteArrayReference(length) => false
          case ByteArrayValue(bytes) =>
            !(origKv.getValue.hasByteArrayValue && util.Arrays.equals(bytes, origKv.getValue.getByteArrayValue.toByteArray))
          case FileReference(ref) => true
        }
    }

    val changedKeys = changed.keySet

    val allPuts = update.filter(tup => (keysToAdd ++ changedKeys).contains(tup._1))

    (allPuts, keysToAdd, changedKeys, keysToRemove)
  }

  sealed trait CalcAction
  case object CalcSame extends CalcAction
  case object CalcCreate extends CalcAction
  case object CalcUpdate extends CalcAction
  case object CalcDelete extends CalcAction

  def calcDiff(calcHolder: CalculationHolder, existing: Option[StoredValue], nameMap: Map[String, UUID]): CalcAction = {

    val unresolvedOpt = calcHolder match {
      case NoCalculation => None
      case unres: UnresolvedCalc => Some(unres)
      case _ => throw new LoadingException("Calculation must be nonexistent or unresolved")
    }

    (existing, unresolvedOpt) match {
      case (None, None) => CalcSame
      case (None, Some(unres)) => CalcCreate
      case (Some(sv), None) => CalcDelete
      case (Some(sv), Some(unres)) =>
        if (sv.hasByteArrayValue) {
          try {
            val desc = CalculationDescriptor.parseFrom(sv.getByteArrayValue)
            val descWithoutInputs = desc.toBuilder.clearCalcInputs()

            if (descWithoutInputs.build() == unres.builder.build()) {
              val existingInputs = desc.getCalcInputsList.map(in => (in.getVariableName, in.getPointUuid, in))
              val existingVars = existingInputs.map(_._1)
              val updateVars = unres.inputs.map(_._2.getVariableName)

              if (existingVars.sorted == updateVars.sorted) {
                val existVarMap = existingInputs.map(tup => (tup._1, (tup._2, tup._3))).toMap

                def inputEquals(a: CalculationInput.Builder, b: CalculationInput.Builder): Boolean = {
                  a.clearPointUuid().clearVariableName().build() == b.clearPointUuid().clearVariableName().build()
                }

                val changed = !unres.inputs.forall {
                  case (id, builder) =>
                    val keyName = builder.getVariableName
                    val existingForKey = existVarMap(keyName)
                    val existingUuid = protoUUIDToUuid(existingForKey._1)

                    id match {
                      case FullEntId(uuid, name) =>
                        (uuid == existingUuid || nameMap.get(name) == Some(existingUuid)) &&
                          inputEquals(builder, existingForKey._2.toBuilder)
                      case UuidEntId(uuid) =>
                        uuid == existingUuid && inputEquals(builder, existingForKey._2.toBuilder)
                      case NamedEntId(name) =>
                        nameMap.get(name) == Some(existingUuid) && inputEquals(builder, existingForKey._2.toBuilder)
                    }
                }

                if (changed) CalcUpdate else CalcSame

              } else {
                CalcUpdate
              }
            } else {
              CalcUpdate
            }
          } catch {
            case ex: Throwable => CalcUpdate
          }
        } else {
          CalcUpdate
        }
    }
  }

  def resolveCalcOpt(calc: CalculationHolder, nameMap: Map[String, UUID]): Option[CalculationDescriptor] = {
    calc match {
      case NoCalculation => None
      case UnresolvedCalc(builder, inputs) =>
        val resolvedInputs = inputs.map {
          case (id, inputBuilder) =>
            val resolvedUuid = entIdToUuid(id, nameMap)
            inputBuilder.setPointUuid(uuidToProtoUUID(resolvedUuid)).build()
        }

        resolvedInputs.foreach(builder.addCalcInputs)
        Some(builder.build())

      case CalcNotChecked | _: NamesResolvedCalc => throw new IllegalArgumentException("Can only resolve calculations from XML import")
    }
  }

  def resolveCalc(calc: CalculationHolder, nameMap: Map[String, UUID]): CalculationDescriptor = {
    calc match {
      case UnresolvedCalc(builder, inputs) =>

        val resolvedInputs = inputs.map {
          case (id, inputBuilder) =>
            val resolvedUuid = entIdToUuid(id, nameMap)
            inputBuilder.setPointUuid(uuidToProtoUUID(resolvedUuid)).build()
        }

        resolvedInputs.foreach(builder.addCalcInputs)
        builder.build()

      case _ => throw new IllegalArgumentException("Can only resolve calculations from XML import")
    }
  }

  def edgeDiffWhileUnresolved(
    updateDescs: Seq[EdgeDesc],
    existingEdges: Seq[EntityEdge],
    uuidMap: Map[UUID, String],
    nameMap: Map[String, UUID]): (Seq[EdgeDesc], Set[(UUID, String, UUID)]) = {

    val existingSet = existingEdges.map(e => (protoUUIDToUuid(e.getParent), e.getRelationship, protoUUIDToUuid(e.getChild))).toSet

    val adds = updateDescs.filter { desc =>
      val parentUuidOpt = entIdToUuidOpt(desc.parent, nameMap)
      val childUuidOpt = entIdToUuidOpt(desc.child, nameMap)
      (parentUuidOpt, childUuidOpt) match {
        case (Some(parentUuid), Some(childUuid)) => !existingSet.contains((parentUuid, desc.relationship, childUuid))
        case _ => true
      }
    }

    val updateSet = updateDescs.toSet

    val removes = existingSet.filter {
      case (parentUuid, relationship, childUuid) =>
        val parentName = uuidMap.getOrElse(parentUuid, throw new LoadingException(s"Existing edge referred to UUID $parentUuid that was unresolved"))
        val childName = uuidMap.getOrElse(childUuid, throw new LoadingException(s"Existing edge referred to UUID $childUuid that was unresolved"))

        def matchWithPriority(leftUuid: UUID, leftName: String, right: EntityId): Boolean = {
          EntityId.optional(right) match {
            case (Some(uuid), _) => uuid == leftUuid
            case (None, Some(name)) => name == leftName
            case _ => false
          }
        }

        !updateDescs.exists { upEdge =>
          relationship == upEdge.relationship &&
            matchWithPriority(parentUuid, parentName, upEdge.parent) &&
            matchWithPriority(childUuid, childName, upEdge.child)
        }
    }

    (adds, removes)
  }

  def resolveEdgeDesc(desc: EdgeDesc, nameMap: Map[String, UUID]): (UUID, String, UUID) = {
    (entIdToUuid(desc.parent, nameMap), desc.relationship, entIdToUuid(desc.child, nameMap))
  }

  def entIdToUuid(id: EntityId, nameMap: Map[String, UUID]): UUID = {
    id match {
      case FullEntId(uuid, _) => uuid
      case UuidEntId(uuid) => uuid
      case NamedEntId(name) => nameMap.getOrElse(name, throw new IllegalArgumentException(s"Referenced unresolved name: $name"))
    }
  }
  def entIdToUuidOpt(id: EntityId, nameMap: Map[String, UUID]): Option[UUID] = {
    id match {
      case FullEntId(uuid, _) => Some(uuid)
      case UuidEntId(uuid) => Some(uuid)
      case NamedEntId(name) => nameMap.get(name)
    }
  }
}
