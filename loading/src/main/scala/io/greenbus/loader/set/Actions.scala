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

import io.greenbus.client.service.proto.Model.{ PointCategory, CommandCategory }
import io.greenbus.loader.set.Mdl.EdgeDesc

object Actions {

  case class PutEntity(uuidOpt: Option[UUID], name: String, types: Set[String])
  case class DeleteEntity(uuid: UUID)

  case class PutPoint(uuidOpt: Option[UUID], name: String, types: Set[String], category: PointCategory, unit: String)
  case class DeletePoint(uuid: UUID)

  case class PutCommand(uuidOpt: Option[UUID], name: String, types: Set[String], category: CommandCategory, displayName: String)
  case class DeleteCommand(uuid: UUID)

  case class PutEndpoint(uuidOpt: Option[UUID], name: String, types: Set[String], protocol: String)
  case class DeleteEndpoint(uuid: UUID)

  case class PutEdge(desc: EdgeDesc)
  case class DeleteEdge(parent: UUID, relationship: String, child: UUID)

  case class PutKeyValueByUuid(uuid: UUID, key: String, vh: ValueHolder)
  case class PutKeyValueByName(name: String, key: String, vh: ValueHolder)
  case class DeleteKeyValue(uuid: UUID, key: String)

  case class PutCalcByUuid(uuid: UUID, calc: CalculationHolder)
  case class PutCalcByName(name: String, calc: CalculationHolder)

  class ActionCache {

    val entityPuts = Vector.newBuilder[PutEntity]
    val entityDeletes = Vector.newBuilder[DeleteEntity]

    val pointPuts = Vector.newBuilder[PutPoint]
    val pointDeletes = Vector.newBuilder[DeletePoint]

    val commandPuts = Vector.newBuilder[PutCommand]
    val commandDeletes = Vector.newBuilder[DeleteCommand]

    val endpointPuts = Vector.newBuilder[PutEndpoint]
    val endpointDeletes = Vector.newBuilder[DeleteEndpoint]

    val edgePuts = Vector.newBuilder[PutEdge]
    val edgeDeletes = Vector.newBuilder[DeleteEdge]

    val calcPutsByUuid = Vector.newBuilder[PutCalcByUuid]
    val calcPutsByName = Vector.newBuilder[PutCalcByName]

    val keyValuePutByUuids = Vector.newBuilder[PutKeyValueByUuid]
    val keyValuePutByNames = Vector.newBuilder[PutKeyValueByName]
    val keyValueDeletes = Vector.newBuilder[DeleteKeyValue]

    def result(): ActionsList = {
      ActionsList(
        entityPuts.result(),
        entityDeletes.result(),
        pointPuts.result(),
        pointDeletes.result(),
        commandPuts.result(),
        commandDeletes.result(),
        endpointPuts.result(),
        endpointDeletes.result(),
        edgePuts.result(),
        edgeDeletes.result(),
        keyValuePutByUuids.result(),
        keyValuePutByNames.result(),
        keyValueDeletes.result(),
        calcPutsByUuid.result(),
        calcPutsByName.result())
    }
  }

  case class ActionsList(
    entityPuts: Vector[PutEntity],
    entityDeletes: Vector[DeleteEntity],
    pointPuts: Vector[PutPoint],
    pointDeletes: Vector[DeletePoint],
    commandPuts: Vector[PutCommand],
    commandDeletes: Vector[DeleteCommand],
    endpointPuts: Vector[PutEndpoint],
    endpointDeletes: Vector[DeleteEndpoint],
    edgePuts: Vector[PutEdge],
    edgeDeletes: Vector[DeleteEdge],
    keyValuePutByUuids: Vector[PutKeyValueByUuid],
    keyValuePutByNames: Vector[PutKeyValueByName],
    keyValueDeletes: Vector[DeleteKeyValue],
    calcPutsByUuid: Vector[PutCalcByUuid],
    calcPutsByName: Vector[PutCalcByName])

}

object Differences {

  trait EntBasedCreate {
    val name: String
    val types: Set[String]
    val keys: Set[String]
    val withUuid: Boolean
  }

  trait EntBasedChange {
    val currentName: String
    val nameOpt: Option[ParamChange[String]]
    val addedTypes: Set[String]
    val removedTypes: Set[String]
    val addedKeys: Set[String]
    val modifiedKeys: Set[String]
    val removedKeys: Set[String]
  }

  case class ParamChange[A](original: A, updated: A)

  case class EntityCreated(name: String, types: Set[String], keys: Set[String], withUuid: Boolean = false) extends EntBasedCreate

  case class EntityChanged(
    currentName: String,
    nameOpt: Option[ParamChange[String]],
    addedTypes: Set[String],
    removedTypes: Set[String],
    addedKeys: Set[String],
    modifiedKeys: Set[String],
    removedKeys: Set[String]) extends EntBasedChange

  case class EntityDeleted(name: String)

  case class PointCreated(name: String, types: Set[String], keys: Set[String], category: PointCategory, unit: String, withUuid: Boolean = false) extends EntBasedCreate

  case class PointChanged(
    currentName: String,
    nameOpt: Option[ParamChange[String]],
    addedTypes: Set[String],
    removedTypes: Set[String],
    addedKeys: Set[String],
    modifiedKeys: Set[String],
    removedKeys: Set[String],
    categoryOpt: Option[ParamChange[PointCategory]],
    unitOpt: Option[ParamChange[String]]) extends EntBasedChange

  case class PointDeleted(name: String)

  case class CommandCreated(name: String, types: Set[String], keys: Set[String], category: CommandCategory, displayName: String, withUuid: Boolean = false) extends EntBasedCreate

  case class CommandChanged(
    currentName: String,
    nameOpt: Option[ParamChange[String]],
    addedTypes: Set[String],
    removedTypes: Set[String],
    addedKeys: Set[String],
    modifiedKeys: Set[String],
    removedKeys: Set[String],
    categoryOpt: Option[ParamChange[CommandCategory]],
    displayNameOpt: Option[ParamChange[String]]) extends EntBasedChange

  case class CommandDeleted(name: String)

  case class EndpointCreated(name: String, types: Set[String], keys: Set[String], protocol: String, withUuid: Boolean = false) extends EntBasedCreate

  case class EndpointChanged(
    currentName: String,
    nameOpt: Option[ParamChange[String]],
    addedTypes: Set[String],
    removedTypes: Set[String],
    addedKeys: Set[String],
    modifiedKeys: Set[String],
    removedKeys: Set[String],
    protocolOpt: Option[ParamChange[String]]) extends EntBasedChange

  case class EndpointDeleted(name: String)

  case class KeyValueRecord(entityName: String, key: String)

  case class EdgeRecord(parent: String, relation: String, child: String)

  class RecordBuilder {
    val entCreateRecords = Vector.newBuilder[EntityCreated]
    val entChangedRecords = Vector.newBuilder[EntityChanged]
    val entDeleteRecords = Vector.newBuilder[EntityDeleted]

    val pointCreateRecords = Vector.newBuilder[PointCreated]
    val pointChangedRecords = Vector.newBuilder[PointChanged]
    val pointDeleteRecords = Vector.newBuilder[PointDeleted]

    val commandCreateRecords = Vector.newBuilder[CommandCreated]
    val commandChangedRecords = Vector.newBuilder[CommandChanged]
    val commandDeleteRecords = Vector.newBuilder[CommandDeleted]

    val endpointCreateRecords = Vector.newBuilder[EndpointCreated]
    val endpointChangedRecords = Vector.newBuilder[EndpointChanged]
    val endpointDeleteRecords = Vector.newBuilder[EndpointDeleted]

    /*val keyValueCreateRecords = Vector.newBuilder[KeyValueRecord]
    val keyValueChangedRecords = Vector.newBuilder[KeyValueRecord]
    val keyValueDeleteRecords = Vector.newBuilder[KeyValueRecord]*/

    val edgeCreateRecords = Vector.newBuilder[EdgeRecord]
    val edgeDeleteRecords = Vector.newBuilder[EdgeRecord]

    def result(): DiffRecord = {
      DiffRecord(
        entCreateRecords.result(),
        entChangedRecords.result(),
        entDeleteRecords.result(),
        pointCreateRecords.result(),
        pointChangedRecords.result(),
        pointDeleteRecords.result(),
        commandCreateRecords.result(),
        commandChangedRecords.result(),
        commandDeleteRecords.result(),
        endpointCreateRecords.result(),
        endpointChangedRecords.result(),
        endpointDeleteRecords.result(),
        edgeCreateRecords.result(),
        edgeDeleteRecords.result())
    }
  }

  case class DiffRecord(
      entCreateRecords: Vector[EntityCreated],
      entChangedRecords: Vector[EntityChanged],
      entDeleteRecords: Vector[EntityDeleted],
      pointCreateRecords: Vector[PointCreated],
      pointChangedRecords: Vector[PointChanged],
      pointDeleteRecords: Vector[PointDeleted],
      commandCreateRecords: Vector[CommandCreated],
      commandChangedRecords: Vector[CommandChanged],
      commandDeleteRecords: Vector[CommandDeleted],
      endpointCreateRecords: Vector[EndpointCreated],
      endpointChangedRecords: Vector[EndpointChanged],
      endpointDeleteRecords: Vector[EndpointDeleted],
      edgeCreateRecords: Vector[EdgeRecord],
      edgeDeleteRecords: Vector[EdgeRecord]) {

    def isEmpty: Boolean = {
      entCreateRecords.isEmpty &&
        entChangedRecords.isEmpty &&
        entDeleteRecords.isEmpty &&
        pointCreateRecords.isEmpty &&
        pointChangedRecords.isEmpty &&
        pointDeleteRecords.isEmpty &&
        commandCreateRecords.isEmpty &&
        commandChangedRecords.isEmpty &&
        commandDeleteRecords.isEmpty &&
        endpointCreateRecords.isEmpty &&
        endpointChangedRecords.isEmpty &&
        endpointDeleteRecords.isEmpty &&
        edgeCreateRecords.isEmpty &&
        edgeDeleteRecords.isEmpty
    }
  }
}
