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

import io.greenbus.client.service.proto.FrontEnd._
import io.greenbus.client.service.proto.Model._
import io.greenbus.services.authz.EntityFilter
import io.greenbus.services.data.ServicesSchema._
import io.greenbus.services.data.{ CommandRow, EndpointRow, PointRow, _ }
import io.greenbus.services.framework._
import io.greenbus.services.model.EntityModel.TypeParams
import io.greenbus.services.model.UUIDHelpers._
import org.squeryl.PrimitiveTypeMode._
import org.squeryl.Query
import org.squeryl.dsl.ast.LogicalBoolean

import scala.collection.JavaConversions._

object FrontEndModel {
  val pointType = "Point"
  val commandType = "Command"

  case class CoreTypeTemplate[A](uuidOpt: Option[UUID], name: String, types: Set[String], info: A)

  case class IdentifiedEntityTemplate[A](uuid: UUID, name: String, types: Set[String], info: A)
  case class NamedEntityTemplate[A](name: String, types: Set[String], info: A)

  case class PointInfo(pointCategory: PointCategory, unit: String)
  case class CommandInfo(displayName: String, commandCategory: CommandCategory)
  case class EndpointInfo(protocol: String, disabled: Option[Boolean])
  case class ConfigFileInfo(mimeType: String, file: Array[Byte])

  case class FrontEndConnectionTemplate(endpointId: UUID, inputAddress: String, commandAddress: Option[String])

  case class EndpointDisabledUpdate(id: UUID, disabled: Boolean)

  def splitTemplates[A](templates: Seq[CoreTypeTemplate[A]]): (Seq[IdentifiedEntityTemplate[A]], Seq[NamedEntityTemplate[A]]) = {

    val (hasIds, noIds) = templates.partition(_.uuidOpt.nonEmpty)

    val withIds = hasIds.map(t => IdentifiedEntityTemplate(t.uuidOpt.get, t.name, t.types, t.info))
    val withNames = noIds.map(t => NamedEntityTemplate(t.name, t.types, t.info))

    (withIds, withNames)
  }
}
trait FrontEndModel {
  import io.greenbus.services.model.FrontEndModel._

  def pointExists(id: UUID, filter: Option[EntityFilter] = None): Boolean

  def pointKeyQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[Point]
  def pointQuery(pointCategories: Seq[PointCategory], units: Seq[String], typeParams: TypeParams, lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Point]
  def putPoints(notifier: ModelNotifier, templates: Seq[CoreTypeTemplate[PointInfo]], allowCreate: Boolean = true, updateFilter: Option[EntityFilter] = None): Seq[Point]
  def deletePoints(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[Point]

  def commandKeyQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[Command]
  def commandQuery(commandCategories: Seq[CommandCategory], typeParams: TypeParams, lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Command]
  def putCommands(notifier: ModelNotifier, points: Seq[CoreTypeTemplate[CommandInfo]], allowCreate: Boolean = true, filter: Option[EntityFilter] = None): Seq[Command]
  def deleteCommands(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[Command]

  def endpointKeyQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[Endpoint]
  def endpointQuery(protocols: Seq[String], disabledOpt: Option[Boolean], typeParams: TypeParams, lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Endpoint]
  def putEndpoints(notifier: ModelNotifier, points: Seq[CoreTypeTemplate[EndpointInfo]], allowCreate: Boolean = true, filter: Option[EntityFilter] = None): Seq[Endpoint]
  def putEndpointsDisabled(notifier: ModelNotifier, updates: Seq[EndpointDisabledUpdate], filter: Option[EntityFilter] = None): Seq[Endpoint]
  def deleteEndpoints(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[Endpoint]

  def putFrontEndConnections(notifier: ModelNotifier, templates: Seq[FrontEndConnectionTemplate], filter: Option[EntityFilter] = None): Seq[FrontEndRegistration]
  def addressForCommand(command: UUID): Option[(Option[String], String)]

  def getFrontEndConnectionStatuses(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[FrontEndConnectionStatus]
  def putFrontEndConnectionStatuses(notifier: ModelNotifier, updates: Seq[(UUID, FrontEndConnectionStatus.Status)], filter: Option[EntityFilter] = None): Seq[FrontEndConnectionStatus]
}

object SquerylFrontEndModel extends FrontEndModel {
  import io.greenbus.services.model.EntityBasedModels._
  import io.greenbus.services.model.FrontEndModel._

  def pointExists(id: UUID, filter: Option[EntityFilter] = None): Boolean = {
    from(points)((pt) =>
      where(EntityFilter.optional(filter, id) and
        pt.entityId === id)
        select (pt.id)).nonEmpty
  }

  def sourceParentMap(children: Seq[UUID]): Map[UUID, UUID] = {
    from(entities, edges, entities)((childEnt, edge, endEnt) =>
      where(childEnt.id in children and
        edge.childId === childEnt.id and
        edge.relationship === "source" and
        edge.parentId === endEnt.id)
        select (childEnt.id, endEnt.id)).toList.toMap
  }

  private def buildPointProto(tup: (UUID, String, Seq[String], PointRow)): Point = {
    val (id, name, types, row) = tup
    Point.newBuilder()
      .setUuid(id)
      .setName(name)
      .addAllTypes(types)
      .setPointCategory(PointCategory.valueOf(row.pointCategory))
      .setUnit(row.unit)
      .build()
  }

  def pointKeyQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[Point] = {

    val rows: Seq[(UUID, String, Seq[String], PointRow)] = entityBasedKeyQuery(uuids, names, points, filter)

    val pointToEndpointMap: Map[UUID, UUID] = sourceParentMap(rows.map(_._1))

    rows.map {
      case (id, name, types, row) =>
        val b = Point.newBuilder()
          .setUuid(id)
          .setName(name)
          .addAllTypes(types)
          .setPointCategory(PointCategory.valueOf(row.pointCategory))
          .setUnit(row.unit)

        pointToEndpointMap.get(id).foreach(uuid => b.setEndpointUuid(uuidToProtoUUID(uuid)))

        b.build
    }
  }

  def pointQuery(pointCategories: Seq[PointCategory], units: Seq[String], typeParams: TypeParams, lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Point] = {

    def rowClause(row: PointRow): LogicalBoolean = {
      val pointTypeIds = pointCategories.map(_.getNumber)

      (row.pointCategory in pointTypeIds).inhibitWhen(pointTypeIds.isEmpty) and
        (row.unit in units).inhibitWhen(units.isEmpty)
    }

    val results = entityBasedQuery(points, typeParams, rowClause, lastUuid, lastName, pageSize, pageByName, filter)

    val pointToEndpointMap: Map[UUID, UUID] = sourceParentMap(results.map(_._1))

    results.map {
      case (uuid, name, types, row) =>
        val endpointUuid = pointToEndpointMap.get(uuid)
        val b = Point.newBuilder()
          .setUuid(uuid)
          .setName(name)
          .addAllTypes(types)
          .setPointCategory(PointCategory.valueOf(row.pointCategory))
          .setUnit(row.unit)

        endpointUuid.map(uuidToProtoUUID).foreach(b.setEndpointUuid)

        b.build()
    }
  }

  def putPoints(notifier: ModelNotifier, templates: Seq[CoreTypeTemplate[PointInfo]], allowCreate: Boolean = true, updateFilter: Option[EntityFilter] = None): Seq[Point] = {

    val (withIds, withNames) = splitTemplates(templates.map(t => t.copy(types = t.types + "Point")))

    def create(id: UUID, temp: PointInfo) = PointRow(0, id, temp.pointCategory.getNumber, temp.unit)

    def update(id: UUID, row: PointRow, temp: PointInfo) = {
      if (row.pointCategory != temp.pointCategory.getNumber || row.unit != temp.unit) {
        Some(row.copy(pointCategory = temp.pointCategory.getNumber, unit = temp.unit))
      } else {
        None
      }
    }

    def toUuid(point: Point) = point.getUuid

    putEntityBased(notifier, withIds, withNames, "Point", create, update, pointKeyQuery(_, _, None), toUuid, points, allowCreate, updateFilter)
  }

  def deletePoints(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[Point] = {
    deleteEntityBased(notifier, ids, pointKeyQuery(_, Nil), points, filter)
  }

  private def buildCommandProto(tup: (UUID, String, Seq[String], CommandRow)): Command = {
    val (id, name, types, row) = tup
    Command.newBuilder()
      .setUuid(id)
      .setName(name)
      .addAllTypes(types)
      .setDisplayName(row.displayName)
      .setCommandCategory(CommandCategory.valueOf(row.commandCategory))
      .build()
  }

  def commandKeyQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[Command] = {

    val rows: Seq[(UUID, String, Seq[String], CommandRow)] = entityBasedKeyQuery(uuids, names, commands, filter)

    val commandToEndpointMap: Map[UUID, UUID] = sourceParentMap(rows.map(_._1))

    rows.map {
      case (id, name, types, row) =>
        val b = Command.newBuilder()
          .setUuid(id)
          .setName(name)
          .addAllTypes(types)
          .setDisplayName(row.displayName)
          .setCommandCategory(CommandCategory.valueOf(row.commandCategory))

        commandToEndpointMap.get(id).foreach(uuid => b.setEndpointUuid(uuidToProtoUUID(uuid)))
        b.build
    }
  }

  def commandQuery(commandCategories: Seq[CommandCategory], typeParams: TypeParams, lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Command] = {

    def rowClause(row: CommandRow): LogicalBoolean = {
      val commandTypeIds = commandCategories.map(_.getNumber)

      (row.commandCategory in commandTypeIds).inhibitWhen(commandTypeIds.isEmpty)
    }

    val results = entityBasedQuery(commands, typeParams, rowClause, lastUuid, lastName, pageSize, pageByName, filter)

    val pointToEndpointMap: Map[UUID, UUID] = sourceParentMap(results.map(_._1))

    results.map {
      case (uuid, name, types, row) =>
        val endpointUuid = pointToEndpointMap.get(uuid)
        val b = Command.newBuilder()
          .setUuid(uuid)
          .setName(name)
          .addAllTypes(types)
          .setDisplayName(row.displayName)
          .setCommandCategory(CommandCategory.valueOf(row.commandCategory))

        endpointUuid.map(uuidToProtoUUID).foreach(b.setEndpointUuid)

        b.build()
    }
  }

  def putCommands(notifier: ModelNotifier, templates: Seq[CoreTypeTemplate[CommandInfo]], allowCreate: Boolean = true, updateFilter: Option[EntityFilter] = None): Seq[Command] = {

    val (withIds, withNames) = splitTemplates(templates.map(t => t.copy(types = t.types + "Command")))

    def create(id: UUID, temp: CommandInfo) = CommandRow(0, id, temp.displayName, temp.commandCategory.getNumber, None)

    def update(id: UUID, row: CommandRow, temp: CommandInfo) = {
      if (row.commandCategory != temp.commandCategory.getNumber || row.displayName != temp.displayName) {
        Some(row.copy(commandCategory = temp.commandCategory.getNumber, displayName = temp.displayName))
      } else {
        None
      }
    }

    def toUuid(command: Command) = command.getUuid

    putEntityBased(notifier, withIds, withNames, "Command", create, update, commandKeyQuery(_, _, None), toUuid, commands, allowCreate, updateFilter)
  }

  def deleteCommands(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[Command] = {
    deleteEntityBased(notifier, ids, commandKeyQuery(_, Nil), commands, filter)
  }

  private def buildEndpoint(tup: (UUID, String, Seq[String], EndpointRow)): Endpoint = tup match {
    case (id, name, types, row) =>
      Endpoint.newBuilder()
        .setUuid(id)
        .setName(name)
        .addAllTypes(types)
        .setProtocol(row.protocol)
        .setDisabled(row.disabled)
        .build
  }

  def endpointKeyQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[Endpoint] = {
    entityBasedKeyQuery(uuids, names, endpoints, filter).map(buildEndpoint)
  }

  def endpointQuery(protocols: Seq[String], disabledOpt: Option[Boolean], typeParams: TypeParams, lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true, filter: Option[EntityFilter] = None): Seq[Endpoint] = {

    def rowClause(row: EndpointRow): LogicalBoolean = {

      (row.protocol in protocols).inhibitWhen(protocols.isEmpty) and
        (row.disabled === disabledOpt.?)
    }

    val results = entityBasedQuery(endpoints, typeParams, rowClause, lastUuid, lastName, pageSize, pageByName, filter)

    results.map(buildEndpoint)
  }

  def putEndpoints(notifier: ModelNotifier, templates: Seq[CoreTypeTemplate[EndpointInfo]], allowCreate: Boolean = true, updateFilter: Option[EntityFilter] = None): Seq[Endpoint] = {

    val (withIds, withNames) = splitTemplates(templates.map(t => t.copy(types = t.types + "Endpoint")))

    def create(id: UUID, temp: EndpointInfo) = EndpointRow(0, id, temp.protocol, temp.disabled.getOrElse(false))

    def update(id: UUID, row: EndpointRow, temp: EndpointInfo) = {
      val disabledChanged = temp.disabled.nonEmpty && temp.disabled != Some(row.disabled)
      if (row.protocol != temp.protocol || disabledChanged) {
        val withDisabled = temp.disabled.map(d => row.copy(disabled = d)).getOrElse(row)
        Some(withDisabled.copy(protocol = temp.protocol))
      } else {
        None
      }
    }

    def toUuid(proto: Endpoint) = proto.getUuid

    putEntityBased(notifier, withIds, withNames, "Endpoint", create, update, endpointKeyQuery(_, _, None), toUuid, endpoints, allowCreate, updateFilter)
  }

  def deleteEndpoints(notifier: ModelNotifier, ids: Seq[UUID], filter: Option[EntityFilter] = None): Seq[Endpoint] = {
    deleteEntityBased(notifier, ids, endpointKeyQuery(_, Nil), endpoints, filter)
  }

  def putEndpointsDisabled(notifier: ModelNotifier, updates: Seq[EndpointDisabledUpdate], filter: Option[EntityFilter] = None): Seq[Endpoint] = {

    val ids = updates.map(_.id)

    filter.foreach { filter =>
      if (filter.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to modify restricted objects")
      }
    }

    val current: Map[UUID, EndpointRow] = endpoints.where(end => end.entityId in ids).toList.map(row => (row.entityId, row)).toMap

    val updateRows = updates.flatMap { up =>
      current.get(up.id) match {
        case None => throw new ModelInputException(s"Endpoint with id ${up.id} not found")
        case Some(row) =>
          if (row.disabled != up.disabled) {
            Some(row.copy(disabled = up.disabled))
          } else {
            None
          }
      }
    }

    if (updateRows.nonEmpty) {
      endpoints.update(updateRows)
    }

    val updateIds = updateRows.map(_.entityId)

    val results = endpointKeyQuery(ids, Nil)

    val updatedResults = results.filter(end => updateIds.contains(protoUUIDToUuid(end.getUuid)))

    updatedResults.foreach(notifier.notify(Updated, _))

    results
  }

  def frontEndConnectionKeyQuery(endpointUuids: Seq[UUID], endpointNames: Seq[String], filter: Option[EntityFilter] = None): Seq[FrontEndRegistration] = {

    val rows: Seq[(UUID, String, Option[String])] =
      from(entities, endpoints, frontEndConnections)((ent, end, fep) =>
        where(EntityFilter.optional(filter, ent).inhibitWhen(filter.isEmpty) and
          ((ent.id in endpointUuids).inhibitWhen(endpointUuids.isEmpty) or
            (ent.name in endpointNames).inhibitWhen(endpointNames.isEmpty)) and
            (end.entityId === ent.id) and
            (fep.endpointId === end.entityId))
          select (ent.id, fep.inputAddress, fep.commandAddress)).toSeq

    rows.map {
      case (uuid, inputAddr, optCmdAddr) =>
        val b = FrontEndRegistration.newBuilder()
          .setEndpointUuid(uuid)
          .setInputAddress(inputAddr)

        optCmdAddr.foreach(b.setCommandAddress)
        b.build()
    }
  }

  def putFrontEndConnections(notifier: ModelNotifier, templates: Seq[FrontEndConnectionTemplate], filter: Option[EntityFilter] = None): Seq[FrontEndRegistration] = {
    val ids = templates.map(_.endpointId)
    filter.foreach { filt =>
      if (filt.anyOutsideSet(ids)) {
        throw new ModelPermissionException("Tried to register for restricted endpoint")
      }
    }

    val current: Map[UUID, Option[FrontEndConnectionRow]] =
      join(endpoints, frontEndConnections.leftOuter)((end, fep) =>
        where(end.entityId in ids)
          select (end.entityId, fep)
          on (Some(end.entityId) === fep.map(_.endpointId))).toSeq.toMap

    if (ids.exists(id => !current.contains(id))) {
      throw new ModelInputException("Endpoint for front end connection does not exist")
    }

    val existing: Seq[(UUID, FrontEndConnectionRow)] = ids.flatMap(id => current(id).map(row => (id, row)))

    val createIds = ids.filter(current(_).isEmpty)

    val templateMap: Map[UUID, FrontEndConnectionTemplate] = templates.map(temp => (temp.endpointId, temp)).toMap

    val updateRows = existing.map {
      case (id, oldRow) =>
        val template = templateMap(id)
        oldRow.copy(inputAddress = template.inputAddress,
          commandAddress = template.commandAddress)
    }

    val insertRows = createIds.map { id =>
      val template = templateMap(id)
      FrontEndConnectionRow(0, id, template.inputAddress, template.commandAddress)
    }

    frontEndConnections.update(updateRows)
    frontEndConnections.insert(insertRows)

    val results = frontEndConnectionKeyQuery(ids, Nil)

    val updateSet = existing.map(_._1).toSet
    val insertSet = createIds.toSet

    results.filter(r => insertSet.contains(r.getEndpointUuid)).foreach(notifier.notify(Created, _))
    results.filter(r => updateSet.contains(r.getEndpointUuid)).foreach(notifier.notify(Updated, _))

    results
  }

  def addressForCommand(command: UUID): Option[(Option[String], String)] = {

    val edgeForCommand =
      from(edges)(e =>
        where(e.childId === command and
          e.relationship === "source" and
          e.distance === 1)
          select (e))

    from(commands, endpoints, frontEndConnections, entities)((cmd, end, fep, ent) =>
      where(cmd.entityId === command and
        (ent.id === cmd.entityId) and
        (end.entityId in from(edgeForCommand)(e => select(e.parentId))) and
        fep.endpointId === end.entityId)
        select ((fep.commandAddress, ent.name))).headOption
  }

  private def connectionStatusQuery(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Query[(UUID, String, FrontEndCommStatusRow)] = {
    from(endpoints, entities, frontEndCommStatuses)((end, ent, stat) =>
      where(
        EntityFilter.optional(filter, ent).inhibitWhen(filter.isEmpty) and
          (ent.id in uuids).inhibitWhen(uuids.isEmpty) and
          (ent.name in names).inhibitWhen(names.isEmpty) and
          end.entityId === ent.id and
          stat.endpointId === end.entityId)
        select (ent.id, ent.name, stat))
  }

  def getFrontEndConnectionStatuses(uuids: Seq[UUID], names: Seq[String], filter: Option[EntityFilter] = None): Seq[FrontEndConnectionStatus] = {

    val results = connectionStatusQuery(uuids, names, filter).toSeq

    results.map {
      case (uuid, name, statusRow) =>
        FrontEndConnectionStatus.newBuilder()
          .setEndpointUuid(uuid)
          .setEndpointName(name)
          .setState(FrontEndConnectionStatus.Status.valueOf(statusRow.status))
          .setUpdateTime(statusRow.updateTime)
          .build()
    }.toList
  }

  def putFrontEndConnectionStatuses(notifier: ModelNotifier, updates: Seq[(UUID, FrontEndConnectionStatus.Status)], filter: Option[EntityFilter] = None): Seq[FrontEndConnectionStatus] = {
    val uuids = updates.map(_._1)
    filter.foreach { filt =>
      if (filt.anyOutsideSet(uuids)) {
        throw new ModelPermissionException("Tried to register for restricted endpoint")
      }
    }

    val existing: List[(UUID, String, FrontEndCommStatusRow)] = connectionStatusQuery(uuids, Nil).toList
    val existingUuids = existing.map(_._1).toSet

    val missingUuids = uuids.filterNot(existingUuids.contains)

    val endpointSet: Set[UUID] =
      from(endpoints, entities)((end, ent) =>
        where((ent.id in missingUuids) and end.entityId === ent.id)
          select (ent.id)).toList.toSet

    if (missingUuids.filterNot(endpointSet.contains).nonEmpty) {
      throw new ModelInputException("Tried to update connection statuses for non-existent endpoints")
    }

    val uuidToStatusMap = updates.toMap
    val now = System.currentTimeMillis()

    val missingRecords = missingUuids.flatMap { uuid => uuidToStatusMap.get(uuid).map(status => FrontEndCommStatusRow(0, uuid, status.getNumber, now)) }

    val updateRecords = existing.flatMap {
      case (uuid, name, row) => uuidToStatusMap.get(uuid).map(status => row.copy(status = status.getNumber, updateTime = now))
    }

    frontEndCommStatuses.insert(missingRecords)
    frontEndCommStatuses.update(updateRecords)

    val results = getFrontEndConnectionStatuses(uuids, Nil)

    results.foreach(notifier.notify(Updated, _))

    results
  }
}
