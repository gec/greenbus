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
package io.greenbus.ldr

import java.util.UUID

import io.greenbus.ldr.xml.EndpointType.Source
import io.greenbus.ldr.xml.PointType.{ Triggers, Commands }
import io.greenbus.ldr.xml._
import io.greenbus.client.service.proto.Calculations.CalculationDescriptor
import io.greenbus.client.service.proto.Model.{ StoredValue, CommandCategory, PointCategory }
import io.greenbus.client.service.proto.Processing.TriggerSet
import io.greenbus.loader.set._
import io.greenbus.loader.set.Mdl._
import io.greenbus.loader.set

object TreeToXml {

  val triggerSetKey = "triggerSet"
  val calculationKey = "calculation"

  def xml(set: TreeModelFragment): Configuration = {

    val equipModel = new EquipmentModel

    val equipRoots: Seq[Equipment] = set.roots.sortBy(_.node.fields.id).map(equipNode).map {
      case e: Equipment => e
      case _ => throw new LoadingException("Roots must be equipment, not points or commands")
    }

    equipRoots.foreach(equipModel.getEquipment.add)
    val config = new Configuration

    config.setEquipmentModel(equipModel)

    val endpoints = set.endpoints.sortBy(_.node.fields.id).map(endpointNodeToXml)

    if (endpoints.nonEmpty) {
      val endpointModel = new EndpointModel
      endpoints.foreach(endpointModel.getEndpoint.add)
      config.setEndpointModel(endpointModel)
    }

    config
  }

  def xmlType(typ: String) = {
    val t = new Type
    t.setName(typ)
    t
  }

  private def entIdToRefXml(id: set.EntityId): Reference = {
    val (optUuid, optName) = set.EntityId.optional(id)
    val ref = new Reference
    optName.foreach(ref.setName)
    optUuid.map(_.toString).foreach(ref.setUuid)
    ref
  }

  private def entIdAndTypeToRelationXml(id: set.EntityId, relation: String): Relationship = {
    val (optUuid, optName) = set.EntityId.optional(id)
    val rel = new Relationship
    optName.foreach(rel.setName)
    optUuid.map(_.toString).foreach(rel.setUuid)
    rel.setRelation(relation)
    rel
  }

  private def equipNode(node: TreeNode): AnyRef = {

    def commonElements(
      elements: java.util.List[AnyRef],
      fields: EntityFields,
      preExtractedCalc: Option[(CalculationDescriptor, Map[UUID, String])],
      node: TreeNode)(implicit context: String): Unit = {

      fields.types.toSeq.sorted.map(xmlType).foreach(elements.add)

      val (kvs, triggerSetOpt, calcOpt) = mapKeyValues(fields.kvs, preExtractedCalc)

      kvs.sortBy(_.getKey).foreach(elements.add)

      if (node.extParentRefs.nonEmpty) {
        val owners = new ExternalOwners
        node.extParentRefs.sorted.map(entIdToRefXml).foreach(owners.getReference.add)
        elements.add(owners)
      }

      if (node.otherChildRefs.nonEmpty) {
        val refs = new ChildRelationships
        node.otherChildRefs.sorted.map(tup => entIdAndTypeToRelationXml(tup._1, tup._2)).foreach(refs.getRelationship.add)
        elements.add(refs)
      }

      if (node.otherParentRefs.nonEmpty) {
        val parentRefs = new ParentRelationships
        node.otherParentRefs.sorted.map(tup => entIdAndTypeToRelationXml(tup._1, tup._2)).foreach(parentRefs.getRelationship.add)
        elements.add(parentRefs)
      }

      val subs = node.children.sortBy(_.node.fields.id).map(equipNode)
      subs.foreach(elements.add)

      triggerSetOpt.foreach(elements.add)
      calcOpt.foreach(elements.add)
    }

    node.node match {
      case desc: EntityDesc =>
        val equip = new Equipment
        val (optUuid, optName) = set.EntityId.optional(desc.fields.id)
        optName.foreach(equip.setName)
        optUuid.map(_.toString).foreach(equip.setUuid)

        implicit val context = equip.getName

        commonElements(equip.getElements, desc.fields, None, node)

        equip

      case desc: PointDesc =>
        val xml = desc.pointCategory match {
          case PointCategory.ANALOG => new Analog
          case PointCategory.STATUS => new Status
          case PointCategory.COUNTER => new Counter
        }
        val (optUuid, optName) = set.EntityId.optional(desc.fields.id)
        optName.foreach(xml.setName)
        xml.setUnit(desc.unit)
        optUuid.map(_.toString).foreach(xml.setUuid)

        implicit val context = xml.getName

        if (node.commandRefs.nonEmpty) {
          val commands = new Commands
          node.commandRefs.sorted.map(entIdToRefXml).foreach(commands.getReference.add)
          xml.getElements.add(commands)
        }

        val calcOpt = desc.calcHolder match {
          case NoCalculation | CalcNotChecked => None
          case NamesResolvedCalc(calc, uuidMap) => Some((calc, uuidMap))
          case _: UnresolvedCalc => throw new LoadingException("Not expecting UnresolvedCalc in outputter")
        }

        commonElements(xml.getElements, desc.fields, calcOpt, node)

        xml

      case desc: CommandDesc =>
        val xml = desc.commandCategory match {
          case CommandCategory.CONTROL => new Control
          case CommandCategory.SETPOINT_INT =>
            val sp = new Setpoint
            sp.setSetpointType(SetpointType.INTEGER)
            sp
          case CommandCategory.SETPOINT_DOUBLE =>
            val sp = new Setpoint
            sp.setSetpointType(SetpointType.DOUBLE)
            sp
          case CommandCategory.SETPOINT_STRING =>
            val sp = new Setpoint
            sp.setSetpointType(SetpointType.STRING)
            sp
        }

        val (optUuid, optName) = set.EntityId.optional(desc.fields.id)
        optName.foreach(xml.setName)
        xml.setDisplayName(desc.displayName)
        optUuid.map(_.toString).foreach(xml.setUuid)

        implicit val context = xml.getName

        commonElements(xml.getElements, desc.fields, None, node)

        xml
    }
  }

  private def endpointNodeToXml(node: EndpointNode): Endpoint = {

    val xml = new Endpoint

    val fields = node.node.fields

    val (optUuid, optName) = set.EntityId.optional(fields.id)
    optName.foreach(xml.setName)
    optUuid.map(_.toString).foreach(xml.setUuid)

    implicit val context = xml.getName

    fields.types.toSeq.map(xmlType).foreach(xml.getElements.add)

    xml.setProtocol(node.node.protocol)

    val kvXmlElems = fields.kvs.distinct.map { case (key, v) => kvHolderToXml(key, v) }
    kvXmlElems.foreach(xml.getElements.add)

    if (node.otherChildRefs.nonEmpty) {
      val refs = new ChildRelationships
      node.otherChildRefs.sorted.map(tup => entIdAndTypeToRelationXml(tup._1, tup._2)).foreach(refs.getRelationship.add)
      xml.getElements.add(refs)
    }

    if (node.otherParentRefs.nonEmpty) {
      val parentRefs = new ParentRelationships
      node.otherParentRefs.sorted.map(tup => entIdAndTypeToRelationXml(tup._1, tup._2)).foreach(parentRefs.getRelationship.add)
      xml.getElements.add(parentRefs)
    }

    node.sourceRefs.sorted.foreach { id =>
      val elem = new Source
      val (optUuid, optName) = set.EntityId.optional(id)
      optName.foreach(elem.setName)
      optUuid.map(_.toString).foreach(elem.setUuid)
      xml.getElements.add(elem)
    }

    xml
  }

  def storedValueToXml(result: KeyValue, v: StoredValue)(implicit context: String) {
    if (v.hasBoolValue) {
      result.setBooleanValue(v.getBoolValue)
    } else if (v.hasInt32Value) {
      result.setIntValue(v.getInt32Value)
    } else if (v.hasInt64Value) {
      result.setIntValue(v.getInt64Value)
    } else if (v.hasUint32Value) {
      result.setIntValue(v.getUint32Value)
    } else if (v.hasUint64Value) {
      result.setIntValue(v.getUint64Value)
    } else if (v.hasDoubleValue) {
      result.setDoubleValue(v.getDoubleValue)
    } else if (v.hasStringValue) {
      result.setStringValue(v.getStringValue)
    } else {
      throw new LoadingException(s"Could not convert key value to XML format for $context")
    }
  }

  private def kvHolderToXml(key: String, v: ValueHolder)(implicit context: String) = {
    val result = new KeyValue
    result.setKey(key)

    v match {
      case SimpleValue(sv) => storedValueToXml(result, sv)
      case ByteArrayValue(bytes) =>
        val size = bytes.length
        result.setBytesSize(size)
      case FileReference(name) =>
        result.setFileName(name)
      case _ =>
        throw new LoadingException(s"Could not handle key value type for $context")
    }

    result
  }

  def mapKeyValues(
    kvs: Seq[(String, ValueHolder)],
    calcOpt: Option[(CalculationDescriptor, Map[UUID, String])])(implicit context: String): (Seq[KeyValue], Option[Triggers], Option[Calculation]) = {

    val (triggers, nonTriggers) = kvs.partition(_._1 == triggerSetKey)
    val (calcs, nonTriggersOrCalcs) = nonTriggers.partition(_._1 == calculationKey)

    val triggerSetProto = triggers.headOption.map(_._2).map {
      case ByteArrayValue(bytes) => {
        try {
          TriggerSet.parseFrom(bytes)
        } catch {
          case ex: Throwable =>
            throw new LoadingException("Could not parse trigger set stored value: " + ex + s" for $context")
        }
      }
      case _ => throw new LoadingException(s"TriggerSet was not a byte array for $context")
    }

    val triggerXmlElem = triggerSetProto.map(proto => TriggerActionConversion.toXml(proto))

    val calcXmlElem = calcOpt match {
      case None =>
        val calcProto = calcs.headOption.map(_._2).map {
          case ByteArrayValue(bytes) => {
            try {
              CalculationDescriptor.parseFrom(bytes)
            } catch {
              case ex: Throwable =>
                throw new LoadingException("Could not parse calculation stored value: " + ex + s" for $context")
            }
          }
          case _ => throw new LoadingException(s"Calculation was not a byte array for $context")
        }
        calcProto.map(p => CalculationConversion.toXml(p, Map()))

      case Some((desc, uuidToNameMap)) => Some(CalculationConversion.toXml(desc, uuidToNameMap))
    }

    val otherKvs = nonTriggersOrCalcs

    val kvXmlElems = otherKvs.map { case (key, v) => kvHolderToXml(key, v) }

    (kvXmlElems, triggerXmlElem, calcXmlElem)
  }
}
