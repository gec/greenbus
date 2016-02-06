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

import io.greenbus.ldr.xml.{ SetpointType, Configuration, Equipment }
import io.greenbus.client.service.proto.Model.{ CommandCategory, PointCategory, StoredValue }
import io.greenbus.loader.set._

import scala.collection.JavaConversions._

object XmlToModel {
  import io.greenbus.loader.set.Mdl._

  def classFilter[A](seq: Seq[AnyRef], klass: Class[A]) = {
    seq.filter(_.getClass == klass).map(_.asInstanceOf[A])
  }

  def convert(root: Configuration): TreeModelFragment = {
    val nodes = if (root.isSetEquipmentModel) {
      val equipModel = root.getEquipmentModel
      equipModel.getEquipment.map(traverse)
    } else {
      Seq()
    }

    val endpoints = if (root.isSetEndpointModel) {
      val endModel = root.getEndpointModel
      endModel.getEndpoint.map(convertEndpoint)
    } else {
      Seq()
    }

    TreeModelFragment(nodes, endpoints)
  }

  def keyValFromXml(kvElem: xml.KeyValue) = {
    val key = if (kvElem.isSetKey) kvElem.getKey else throw new LoadingXmlException("KeyValue element missing key name", kvElem)

    val valueHolder = if (kvElem.isSetBooleanValue) {
      SimpleValue(StoredValue.newBuilder().setBoolValue(kvElem.getBooleanValue).build())
    } else if (kvElem.isSetStringValue) {
      SimpleValue(StoredValue.newBuilder().setStringValue(kvElem.getStringValue).build())
    } else if (kvElem.isSetIntValue) {
      SimpleValue(StoredValue.newBuilder().setInt64Value(kvElem.getIntValue).build())
    } else if (kvElem.isSetDoubleValue) {
      SimpleValue(StoredValue.newBuilder().setDoubleValue(kvElem.getDoubleValue).build())
    } else if (kvElem.isSetBytesSize) {
      ByteArrayReference(kvElem.getBytesSize)
    } else if (kvElem.isSetFileName) {
      FileReference(kvElem.getFileName)
    } else {
      throw new LoadingXmlException("Could not interpret key value", kvElem)
    }

    (key, valueHolder)
  }

  def extractOwnerRefs(elems: java.util.List[AnyRef]): Seq[EntityId] = {
    val externalOwnersOpt = classFilter(elems, classOf[xml.ExternalOwners]).headOption
    externalOwnersOpt.map { extOwners =>
      extOwners.getReference.map { ref =>
        val nameOpt = if (ref.isSetName) Some(ref.getName) else None
        val uuidOpt = if (ref.isSetUuid) Some(ref.getUuid) else None
        if (nameOpt.isEmpty && uuidOpt.isEmpty) throw new LoadingXmlException("Reference must have name or UUID", ref)
        EntityId(uuidOpt.map(UUID.fromString), nameOpt)
      }
    }.getOrElse(Seq())
  }

  def extractCommandRefs(elems: java.util.List[AnyRef]): Seq[EntityId] = {
    val externalOwnersOpt = classFilter(elems, classOf[xml.PointType.Commands]).headOption
    externalOwnersOpt.map { extOwners =>
      extOwners.getReference.map { ref =>
        val nameOpt = if (ref.isSetName) Some(ref.getName) else None
        val uuidOpt = if (ref.isSetUuid) Some(ref.getUuid) else None
        if (nameOpt.isEmpty && uuidOpt.isEmpty) throw new LoadingXmlException("Reference must have name or UUID", ref)
        EntityId(uuidOpt.map(UUID.fromString), nameOpt)
      }
    }.getOrElse(Seq())
  }

  def extractParentRefs(elems: java.util.List[AnyRef]): Seq[(EntityId, String)] = {
    val elemSeq = classFilter(elems, classOf[xml.ParentRelationships]).headOption
    elemSeq.map { elem =>
      elem.getRelationship.map { rel =>
        val nameOpt = if (rel.isSetName) Some(rel.getName) else None
        val uuidOpt = if (rel.isSetUuid) Some(rel.getUuid) else None
        if (nameOpt.isEmpty && uuidOpt.isEmpty) throw new LoadingXmlException("Reference must have name or UUID", rel)
        val relation = if (rel.isSetRelation) rel.getRelation else throw new LoadingXmlException("Edge reference missing relationship", rel)
        (EntityId(uuidOpt.map(UUID.fromString), nameOpt), relation)
      }
    }.getOrElse(Seq())
  }

  def extractChildRefs(elems: java.util.List[AnyRef]): Seq[(EntityId, String)] = {
    val elemSeq = classFilter(elems, classOf[xml.ChildRelationships]).headOption
    elemSeq.map { elem =>
      elem.getRelationship.map { rel =>
        val nameOpt = if (rel.isSetName) Some(rel.getName) else None
        val uuidOpt = if (rel.isSetUuid) Some(rel.getUuid) else None
        if (nameOpt.isEmpty && uuidOpt.isEmpty) throw new LoadingXmlException("Reference must have name or UUID", rel)
        val relation = if (rel.isSetRelation) rel.getRelation else throw new LoadingXmlException("Edge reference missing relationship", rel)
        (EntityId(uuidOpt.map(UUID.fromString), nameOpt), relation)
      }
    }.getOrElse(Seq())
  }

  def extractSourceRefs(elems: java.util.List[AnyRef]): Seq[EntityId] = {
    val sourceRefs = classFilter(elems, classOf[xml.EndpointType.Source])
    sourceRefs.map { ref =>
      val nameOpt = if (ref.isSetName) Some(ref.getName) else None
      val uuidOpt = if (ref.isSetUuid) Some(ref.getUuid) else None
      if (nameOpt.isEmpty && uuidOpt.isEmpty) throw new LoadingXmlException("Reference must have name or UUID", ref)
      EntityId(uuidOpt.map(UUID.fromString), nameOpt)
    }
  }

  def traverse(equip: Equipment): TreeNode = {
    val nameOpt = if (equip.isSetName) Some(equip.getName) else None
    val uuidOpt = if (equip.isSetUuid) Some(equip.getUuid) else None
    if (nameOpt.isEmpty && uuidOpt.isEmpty) throw new LoadingXmlException("Equipment must have name or UUID", equip)

    val types = classFilter(equip.getElements, classOf[xml.Type]).map { elem =>
      if (elem.isSetName) elem.getName else throw new LoadingXmlException(s"Type element missing type name for ${equip.getName}", elem)
    }

    val keysAndHolders = classFilter(equip.getElements, classOf[xml.KeyValue]).map(keyValFromXml)

    val ownerRefs = extractOwnerRefs(equip.getElements)

    val parentRefs = extractParentRefs(equip.getElements)

    val childRefs = extractChildRefs(equip.getElements)

    val childEquips = classFilter(equip.getElements, classOf[xml.Equipment]).map(traverse)

    val points = (classFilter(equip.getElements, classOf[xml.Analog]) ++
      classFilter(equip.getElements, classOf[xml.Status]) ++
      classFilter(equip.getElements, classOf[xml.Counter])).map(traverse)

    val commands = (classFilter(equip.getElements, classOf[xml.Control]) ++
      classFilter(equip.getElements, classOf[xml.Setpoint])).map(traverse)

    val desc = EntityDesc(
      EntityFields(
        EntityId(uuidOpt.map(UUID.fromString), nameOpt),
        types.toSet,
        keysAndHolders))

    TreeNode(desc, childEquips ++ points ++ commands, ownerRefs, parentRefs, childRefs, Seq())
  }

  def traverse(pt: xml.PointType): TreeNode = {
    val nameOpt = if (pt.isSetName) Some(pt.getName) else None
    val uuidOpt = if (pt.isSetUuid) Some(pt.getUuid) else None
    if (nameOpt.isEmpty && uuidOpt.isEmpty) throw new LoadingXmlException("Point must have name or UUID", pt)

    implicit val context = pt.getName

    val types = classFilter(pt.getElements, classOf[xml.Type]).map { elem =>
      if (elem.isSetName) elem.getName else throw new LoadingXmlException(s"Type element missing type name for ${pt.getName}", elem)
    }

    val keysAndHolders = classFilter(pt.getElements, classOf[xml.KeyValue]).map(keyValFromXml)

    val triggerSetKvOpt = {
      val triggerCollOpt = classFilter(pt.getElements, classOf[xml.PointType.Triggers]).headOption

      val triggerSetOpt = triggerCollOpt.map(TriggerActionConversion.triggersToProto)

      triggerSetOpt.map(ts => (TreeToXml.triggerSetKey, ByteArrayValue(ts.toByteArray))).toSeq
    }

    val calcOpt = classFilter(pt.getElements, classOf[xml.Calculation])
      .headOption
      .map(CalculationConversion.toProto)
      .map(tup => UnresolvedCalc(tup._1, tup._2))

    val keysAndTrigger = keysAndHolders ++ Seq(triggerSetKvOpt).flatten

    val pointCategory = pt match {
      case _: xml.Analog => PointCategory.ANALOG
      case _: xml.Status => PointCategory.STATUS
      case _: xml.Counter => PointCategory.COUNTER
    }

    val unit = if (pt.isSetUnit) pt.getUnit else throw new LoadingXmlException(s"Unit missing from point element for ${pt.getName}", pt)

    val ownerRefs = extractOwnerRefs(pt.getElements)

    val parentRefs = extractParentRefs(pt.getElements)

    val childRefs = extractChildRefs(pt.getElements)

    val commandRefs = extractCommandRefs(pt.getElements)

    val desc = PointDesc(
      EntityFields(
        EntityId(uuidOpt.map(UUID.fromString), nameOpt),
        types.toSet,
        keysAndTrigger),
      pointCategory,
      unit,
      calcOpt.getOrElse(NoCalculation))

    TreeNode(desc, Seq(), ownerRefs, parentRefs, childRefs, commandRefs)
  }

  def traverse(elem: xml.Command): TreeNode = {
    val nameOpt = if (elem.isSetName) Some(elem.getName) else None
    val uuidOpt = if (elem.isSetUuid) Some(elem.getUuid) else None
    if (nameOpt.isEmpty && uuidOpt.isEmpty) throw new LoadingXmlException("Command must have name or UUID", elem)

    val types = classFilter(elem.getElements, classOf[xml.Type]).map { elem =>
      if (elem.isSetName) elem.getName else throw new LoadingXmlException(s"Type element missing type name for ${elem.getName}", elem)
    }

    val keysAndHolders = classFilter(elem.getElements, classOf[xml.KeyValue]).map(keyValFromXml)

    val commandCategory = elem match {
      case _: xml.Control => CommandCategory.CONTROL
      case sp: xml.Setpoint =>
        if (sp.isSetSetpointType) {
          sp.getSetpointType match {
            case SetpointType.DOUBLE => CommandCategory.SETPOINT_DOUBLE
            case SetpointType.INTEGER => CommandCategory.SETPOINT_INT
            case SetpointType.STRING => CommandCategory.SETPOINT_STRING
          }
        } else {
          CommandCategory.SETPOINT_DOUBLE
        }
    }

    val displayName = if (elem.isSetDisplayName) elem.getDisplayName else throw new LoadingXmlException(s"Display name missing from command element for ${elem.getName}", elem)

    val ownerRefs = extractOwnerRefs(elem.getElements)

    val parentRefs = extractParentRefs(elem.getElements)

    val childRefs = extractChildRefs(elem.getElements)

    val desc = CommandDesc(
      EntityFields(
        EntityId(uuidOpt.map(UUID.fromString), nameOpt),
        types.toSet,
        keysAndHolders),
      displayName,
      commandCategory)

    TreeNode(desc, Seq(), ownerRefs, parentRefs, childRefs, Seq())
  }

  def convertEndpoint(elem: xml.Endpoint): EndpointNode = {
    val nameOpt = if (elem.isSetName) Some(elem.getName) else None
    val uuidOpt = if (elem.isSetUuid) Some(elem.getUuid) else None
    if (nameOpt.isEmpty && uuidOpt.isEmpty) throw new LoadingXmlException("Endpoint must have name or UUID", elem)

    val protocol = if (elem.isSetProtocol) elem.getProtocol else throw new LoadingException(s"Protocol name must be set for endpoint for ${elem.getName}")

    val types = classFilter(elem.getElements, classOf[xml.Type]).map { elem =>
      if (elem.isSetName) elem.getName else throw new LoadingXmlException(s"Type element missing type name for ${elem.getName}", elem)
    }

    val keysAndHolders = classFilter(elem.getElements, classOf[xml.KeyValue]).map(keyValFromXml)

    val sourceRefs = extractSourceRefs(elem.getElements)

    val parentRefs = extractParentRefs(elem.getElements)

    val childRefs = extractChildRefs(elem.getElements)

    EndpointNode(
      EndpointDesc(
        EntityFields(
          EntityId(uuidOpt.map(UUID.fromString), nameOpt),
          types.toSet,
          keysAndHolders),
        protocol),
      sourceRefs,
      parentRefs,
      childRefs)
  }
}
