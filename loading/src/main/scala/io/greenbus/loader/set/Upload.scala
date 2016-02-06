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

import java.io.File
import java.util.UUID

import com.google.protobuf.ByteString
import org.apache.commons.io.FileUtils
import io.greenbus.msg.Session
import io.greenbus.client.service.ModelService
import io.greenbus.client.service.proto.Model.{ EntityKeyValue, StoredValue }
import io.greenbus.client.service.proto.ModelRequests._
import io.greenbus.loader.set.Actions._
import io.greenbus.loader.set.UUIDHelpers._
import io.greenbus.util.Timing

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

object Upload {

  val allChunkSize = 300

  def push(session: Session, actions: ActionsList, idTuples: Seq[(UUID, String)]) = {

    val timer = Timing.Stopwatch.start
    val tracker = new PrintTracker(60, System.out)

    val modelClient = ModelService.client(session)

    val keyValuesByNameLoaded = actions.keyValuePutByNames.map(kvn => (kvn.name, kvn.key, valueHolderToStoredValue(kvn.vh)))
    val keyValuesByUuidLoaded = actions.keyValuePutByUuids.map(kvu => (kvu.uuid, kvu.key, valueHolderToStoredValue(kvu.vh)))

    println("\n== Progress:")

    // DELETES
    chunkedCalls(allChunkSize, actions.entityDeletes.map(_.uuid).map(uuidToProtoUUID), modelClient.delete, tracker)
    chunkedCalls(allChunkSize, actions.pointDeletes.map(_.uuid).map(uuidToProtoUUID), modelClient.deletePoints, tracker)
    chunkedCalls(allChunkSize, actions.commandDeletes.map(_.uuid).map(uuidToProtoUUID), modelClient.deleteCommands, tracker)
    chunkedCalls(allChunkSize, actions.endpointDeletes.map(_.uuid).map(uuidToProtoUUID), modelClient.deleteEndpoints, tracker)

    val delKeyPairs = actions.keyValueDeletes.map(dkv => EntityKeyPair.newBuilder().setUuid(uuidToProtoUUID(dkv.uuid)).setKey(dkv.key).build())
    val deletedKvs = chunkedCalls(allChunkSize, delKeyPairs, modelClient.deleteEntityKeyValues, tracker)

    chunkedCalls(allChunkSize, actions.edgeDeletes.map(toTemplate), modelClient.deleteEdges, tracker)

    // ADDS/ MODIFIES
    val ents = chunkedCalls(allChunkSize, actions.entityPuts.map(toTemplate), modelClient.put, tracker)

    val points = chunkedCalls(allChunkSize, actions.pointPuts.map(toTemplate), modelClient.putPoints, tracker)

    val commands = chunkedCalls(allChunkSize, actions.commandPuts.map(toTemplate), modelClient.putCommands, tracker)

    val endpoints = chunkedCalls(allChunkSize, actions.endpointPuts.map(toTemplate), modelClient.putEndpoints, tracker)

    val prevIdMap = idTuples.map(tup => (tup._2, tup._1)).toMap

    val putNameMap: Map[String, UUID] = (ents.map(obj => (obj.getName, protoUUIDToUuid(obj.getUuid))) ++
      points.map(obj => (obj.getName, protoUUIDToUuid(obj.getUuid))) ++
      commands.map(obj => (obj.getName, protoUUIDToUuid(obj.getUuid))) ++
      endpoints.map(obj => (obj.getName, protoUUIDToUuid(obj.getUuid)))).toMap

    val nameMap = prevIdMap ++ putNameMap

    def nameToUuid(name: String) = nameMap.getOrElse(name, throw new LoadingException(s"Name $name not found from object puts"))

    val kvPuts: Seq[(UUID, String, StoredValue)] =
      keyValuesByNameLoaded.map { case (name, key, sv) => (nameToUuid(name), key, sv) } ++
        keyValuesByUuidLoaded

    val kvs = chunkedCalls(allChunkSize, kvPuts.map(toTemplate), modelClient.putEntityKeyValues, tracker)

    chunkedCalls(allChunkSize, actions.calcPutsByUuid.map(put => buildCalc(put.uuid, put.calc, nameMap)), modelClient.putEntityKeyValues, tracker)
    chunkedCalls(allChunkSize, actions.calcPutsByName.map(put => buildCalc(nameToUuid(put.name), put.calc, nameMap)), modelClient.putEntityKeyValues, tracker)

    val resolvedEdgeAdds = actions.edgePuts.map(put => Importer.resolveEdgeDesc(put.desc, nameMap)).distinct

    chunkedCalls(allChunkSize, resolvedEdgeAdds.map(toTemplate), modelClient.putEdges, tracker)

    println("\n")
    println(s"== Finished in ${timer.elapsed} ms")
  }

  def chunkedCalls[A, B](chunkSize: Int, all: Seq[A], put: Seq[A] => Future[Seq[B]], tracker: Tracker): Seq[B] = {
    all.grouped(chunkSize).flatMap { chunk =>
      val results = Await.result(put(chunk), Duration(60000, MILLISECONDS))
      tracker.added()
      results
    }.toVector
  }

  def toTemplate(act: PutEntity): EntityTemplate = {
    val b = EntityTemplate.newBuilder()
      .setName(act.name)
      .addAllTypes(act.types)

    act.uuidOpt.map(uuidToProtoUUID).foreach(b.setUuid)

    b.build()
  }

  def toTemplate(act: PutPoint): PointTemplate = {
    val entB = EntityTemplate.newBuilder()
      .setName(act.name)
      .addAllTypes(act.types)

    act.uuidOpt.map(uuidToProtoUUID).foreach(entB.setUuid)

    PointTemplate.newBuilder()
      .setEntityTemplate(entB.build())
      .setPointCategory(act.category)
      .setUnit(act.unit)
      .build()
  }

  def toTemplate(act: PutCommand): CommandTemplate = {
    val entB = EntityTemplate.newBuilder()
      .setName(act.name)
      .addAllTypes(act.types)

    act.uuidOpt.map(uuidToProtoUUID).foreach(entB.setUuid)

    CommandTemplate.newBuilder()
      .setEntityTemplate(entB.build())
      .setCategory(act.category)
      .setDisplayName(act.displayName)
      .build()
  }

  def toTemplate(act: PutEndpoint): EndpointTemplate = {
    val entB = EntityTemplate.newBuilder()
      .setName(act.name)
      .addAllTypes(act.types)

    act.uuidOpt.map(uuidToProtoUUID).foreach(entB.setUuid)

    EndpointTemplate.newBuilder()
      .setEntityTemplate(entB.build())
      .setProtocol(act.protocol)
      .build()
  }

  def toTemplate(tup: (UUID, String, UUID)): EntityEdgeDescriptor = {
    EntityEdgeDescriptor.newBuilder()
      .setParentUuid(uuidToProtoUUID(tup._1))
      .setChildUuid(uuidToProtoUUID(tup._3))
      .setRelationship(tup._2)
      .build()
  }
  def toTemplate(act: DeleteEdge): EntityEdgeDescriptor = {
    EntityEdgeDescriptor.newBuilder()
      .setParentUuid(uuidToProtoUUID(act.parent))
      .setChildUuid(uuidToProtoUUID(act.child))
      .setRelationship(act.relationship)
      .build()
  }

  def toTemplate(kv: (UUID, String, StoredValue)): EntityKeyValue = {
    val (uuid, key, sv) = kv
    EntityKeyValue.newBuilder()
      .setUuid(uuidToProtoUUID(uuid))
      .setKey(key)
      .setValue(sv)
      .build()
  }

  def buildCalc(uuid: UUID, calculationHolder: CalculationHolder, nameMap: Map[String, UUID]): EntityKeyValue = {
    val calcDesc = Importer.resolveCalc(calculationHolder, nameMap)
    EntityKeyValue.newBuilder()
      .setUuid(uuidToProtoUUID(uuid))
      .setKey("calculation")
      .setValue(StoredValue.newBuilder().setByteArrayValue(ByteString.copyFrom(calcDesc.toByteArray)).build())
      .build()
  }

  def valueHolderToStoredValue(vh: ValueHolder): StoredValue = {
    vh match {
      case SimpleValue(sv) => sv
      case ByteArrayValue(bytes) => StoredValue.newBuilder().setByteArrayValue(ByteString.copyFrom(bytes)).build()
      case ByteArrayReference(size) => throw new LoadingException("Cannot upload a byte array referenced by size")
      case FileReference(filename) =>
        val bytes = try {
          FileUtils.readFileToByteArray(new File(filename))
        } catch {
          case ex: Throwable => throw new LoadingException(s"Could not open file $filename: " + ex.getMessage)
        }
        StoredValue.newBuilder().setByteArrayValue(ByteString.copyFrom(bytes)).build()
    }
  }

}
