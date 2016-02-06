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

import io.greenbus.client.service.proto.Calculations.{ CalculationDescriptor, CalculationInput }
import io.greenbus.client.service.proto.Model
import io.greenbus.client.service.proto.Model.StoredValue

object EntityId {
  def optional(id: EntityId): (Option[UUID], Option[String]) = {
    id match {
      case NamedEntId(name) => (None, Some(name))
      case UuidEntId(uuid) => (Some(uuid), None)
      case FullEntId(uuid, name) => (Some(uuid), Some(name))
    }
  }

  def toUuidOpt(id: EntityId): Option[UUID] = {
    id match {
      case UuidEntId(uuid) => Some(uuid)
      case FullEntId(uuid, name) => Some(uuid)
      case NamedEntId(_) => None
    }
  }
  def toNameOpt(id: EntityId): Option[String] = {
    id match {
      case UuidEntId(_) => None
      case FullEntId(_, name) => Some(name)
      case NamedEntId(name) => Some(name)
    }
  }

  def apply(optUuid: Option[UUID], optName: Option[String]): EntityId = {
    (optUuid, optName) match {
      case (None, None) => throw new LoadingException("Must have either name or UUID")
      case (Some(uuid), None) => UuidEntId(uuid)
      case (None, Some(name)) => NamedEntId(name)
      case (Some(uuid), Some(name)) => FullEntId(uuid, name)
    }
  }

  def prettyPrint(id: EntityId): String = {
    id match {
      case NamedEntId(name) => name
      case UuidEntId(uuid) => uuid.toString
      case FullEntId(uuid, name) => name
    }
  }

  def lessThan(a: EntityId, b: EntityId): Boolean = {
    (optional(a), optional(b)) match {
      case ((_, Some(nameA)), (_, Some(nameB))) => nameA < nameB
      case ((_, Some(_)), (_, None)) => true
      case ((_, None), (_, Some(_))) => false
      case ((Some(uuidA), None), (Some(uuidB), None)) => uuidA.compareTo(uuidB) < 0
      case _ => false
    }
  }

  implicit def ordering[A <: EntityId]: Ordering[EntityId] = {
    Ordering.fromLessThan(lessThan)
  }
}

sealed trait EntityId
case class NamedEntId(name: String) extends EntityId
case class UuidEntId(uuid: UUID) extends EntityId
case class FullEntId(uuid: UUID, name: String) extends EntityId

case class EntityFields(
  id: EntityId,
  types: Set[String],
  kvs: Seq[(String, ValueHolder)])

sealed trait ValueHolder
case class SimpleValue(sv: StoredValue) extends ValueHolder
case class ByteArrayValue(bytes: Array[Byte]) extends ValueHolder
case class ByteArrayReference(size: Long) extends ValueHolder
case class FileReference(name: String) extends ValueHolder

sealed trait CalculationHolder
case object NoCalculation extends CalculationHolder
case object CalcNotChecked extends CalculationHolder
case class UnresolvedCalc(builder: CalculationDescriptor.Builder, inputs: Seq[(EntityId, CalculationInput.Builder)]) extends CalculationHolder
case class NamesResolvedCalc(calc: CalculationDescriptor, uuidToName: Map[UUID, String]) extends CalculationHolder

object Mdl {

  sealed trait ModelObject {
    val fields: EntityFields
  }

  sealed trait EquipNode extends ModelObject

  case class EntityDesc(fields: EntityFields) extends EquipNode

  case class PointDesc(
    fields: EntityFields,
    pointCategory: Model.PointCategory,
    unit: String,
    calcHolder: CalculationHolder) extends EquipNode

  case class CommandDesc(
    fields: EntityFields,
    displayName: String,
    commandCategory: Model.CommandCategory) extends EquipNode

  case class EndpointDesc(
    fields: EntityFields,
    protocol: String) extends ModelObject

  case class EdgeDesc(parent: EntityId, relationship: String, child: EntityId)

  case class FlatModelFragment(
    modelEntities: Seq[EntityDesc],
    points: Seq[PointDesc],
    commands: Seq[CommandDesc],
    endpoints: Seq[EndpointDesc],
    edges: Seq[EdgeDesc])

  case class TreeNode(
    node: EquipNode,
    children: Seq[TreeNode],
    extParentRefs: Seq[EntityId],
    otherParentRefs: Seq[(EntityId, String)],
    otherChildRefs: Seq[(EntityId, String)],
    commandRefs: Seq[EntityId])

  case class EndpointNode(node: EndpointDesc,
    sourceRefs: Seq[EntityId],
    otherParentRefs: Seq[(EntityId, String)],
    otherChildRefs: Seq[(EntityId, String)])

  case class TreeModelFragment(
    roots: Seq[TreeNode],
    endpoints: Seq[EndpointNode])

  def forceEntIdToUuid(id: EntityId): UUID = {
    EntityId.toUuidOpt(id).getOrElse(throw new LoadingException("Must have UUID resolved"))
  }

  def flatToTree(flat: FlatModelFragment): TreeModelFragment = {

    val uuidAndEquips = flat.modelEntities.map(e => (forceEntIdToUuid(e.fields.id), e))
    val uuidAndPoints = flat.points.map(e => (forceEntIdToUuid(e.fields.id), e))
    val uuidAndCommands = flat.commands.map(e => (forceEntIdToUuid(e.fields.id), e))

    val equipUuids = uuidAndEquips.map(_._1) ++ uuidAndPoints.map(_._1) ++ uuidAndCommands.map(_._1)
    val equipUuidSet: Set[UUID] = equipUuids.toSet

    val roots = findRoots(equipUuids, flat.edges)

    val parentToChildMap = flat.edges.groupBy(e => forceEntIdToUuid(e.parent))
    val childToParentMap = flat.edges.groupBy(e => forceEntIdToUuid(e.child))

    val equipNodeMap: Map[UUID, EquipNode] = (uuidAndEquips ++ uuidAndPoints ++ uuidAndCommands).toMap

    def fillTree(uuids: Seq[UUID]): Seq[TreeNode] = {
      val nodes: Seq[(UUID, EquipNode)] = uuids.flatMap(uuid => equipNodeMap.get(uuid).map(n => (uuid, n)))
      nodes.map {
        case (uuid, node) =>
          val allChildEdges = parentToChildMap.getOrElse(uuid, Seq())
          val allParentEdges = childToParentMap.getOrElse(uuid, Seq())

          val extParentOwns = allParentEdges
            .filter(e => e.relationship == "owns" && !equipUuidSet.contains(forceEntIdToUuid(e.parent)))
            .map(e => e.parent)

          val ownsChildren = allChildEdges.filter(_.relationship == "owns").map(_.child).map(forceEntIdToUuid)

          val commandRefs = allChildEdges.filter(_.relationship == "feedback").map(e => e.child)

          val builtInSet = Set("owns", "source", "feedback")

          val otherParents = allParentEdges
            .filterNot(e => builtInSet.contains(e.relationship))
            .map(e => (e.parent, e.relationship))

          val otherChildren = allChildEdges
            .filterNot(e => builtInSet.contains(e.relationship))
            .map(e => (e.child, e.relationship))

          TreeNode(
            node,
            fillTree(ownsChildren),
            extParentOwns,
            otherParents,
            otherChildren,
            commandRefs)
      }
    }

    val rootNodes = fillTree(roots)

    val endpointNodes = flat.endpoints.map { desc =>
      val uuid = forceEntIdToUuid(desc.fields.id)
      val allChildEdges = parentToChildMap.getOrElse(uuid, Seq())
      val allParentEdges = childToParentMap.getOrElse(uuid, Seq())

      val (sourceEdges, otherChildEdges) = allChildEdges.partition(_.relationship == "source")

      val sourceRefs = sourceEdges.map(_.child)

      EndpointNode(desc, sourceRefs, allParentEdges.map(e => (e.parent, e.relationship)), otherChildEdges.map(e => (e.child, e.relationship)))
    }

    TreeModelFragment(rootNodes, endpointNodes)
  }

  private def findRoots(nodes: Seq[UUID], edges: Seq[EdgeDesc]): Seq[UUID] = {
    val nodeSet = nodes.toSet

    val nodesWithParentInSet = edges.filter(e => e.relationship == "owns" && nodeSet.contains(forceEntIdToUuid(e.parent)))
      .map(_.child)
      .map(forceEntIdToUuid)
      .toSet

    nodes.filterNot(nodesWithParentInSet.contains)
  }

  def treeToFlat(tree: TreeModelFragment): FlatModelFragment = {

    val entities = Vector.newBuilder[EntityDesc]
    val points = Vector.newBuilder[PointDesc]
    val commands = Vector.newBuilder[CommandDesc]
    val endpoints = Vector.newBuilder[EndpointDesc]

    val edges = Vector.newBuilder[EdgeDesc]

    def traverse(node: TreeNode): EntityId = {

      val nodeId = node.node match {
        case desc: EntityDesc =>
          entities += desc; desc.fields.id
        case desc: PointDesc =>
          points += desc; desc.fields.id
        case desc: CommandDesc =>
          commands += desc; desc.fields.id
      }

      node.extParentRefs.map(parent => EdgeDesc(parent, "owns", nodeId)).foreach(edges.+=)
      node.otherChildRefs.map { case (child, rel) => EdgeDesc(nodeId, rel, child) }.foreach(edges.+=)
      node.otherParentRefs.map { case (parent, rel) => EdgeDesc(parent, rel, nodeId) }.foreach(edges.+=)
      node.commandRefs.map(cmdId => EdgeDesc(nodeId, "feedback", cmdId)).foreach(edges.+=)

      val childIds = node.children.map(traverse)

      childIds.map(childId => EdgeDesc(nodeId, "owns", childId)).foreach(edges.+=)

      nodeId
    }

    tree.roots.foreach(traverse)

    tree.endpoints.foreach { node =>
      endpoints += node.node
      val nodeId = node.node.fields.id
      node.sourceRefs.foreach(ref => edges += EdgeDesc(nodeId, "source", ref))
      node.otherParentRefs.foreach { case (ref, relation) => edges += EdgeDesc(ref, relation, nodeId) }
      node.otherChildRefs.foreach { case (ref, relation) => edges += EdgeDesc(nodeId, relation, ref) }
    }

    FlatModelFragment(entities.result(), points.result(), commands.result(), endpoints.result(), edges.result())
  }
}
