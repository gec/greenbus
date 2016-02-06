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

import io.greenbus.loader.set.Mdl.{ FlatModelFragment, ModelObject }

object SetDiff {

  def checkForDuplicateKeys(flat: FlatModelFragment): Unit = {

    def checkEnt(e: ModelObject): Unit = { check(e.fields) }

    def check(fields: EntityFields): Unit = {
      fields.kvs.groupBy(_._1).foreach {
        case (key, tups) =>
          if (tups.size > 1) {
            throw new LoadingException(s"Duplicate key $key for ${EntityId.prettyPrint(fields.id)}")
          }
      }
    }

    flat.modelEntities.foreach(checkEnt)
    flat.points.foreach(checkEnt)
    flat.commands.foreach(checkEnt)
    flat.endpoints.foreach(checkEnt)
  }

  def analyzeIdentities(flat: FlatModelFragment): (Seq[UUID], Seq[String], Seq[(UUID, String)]) = {

    val (objUuidsSet, allObjNames, idTuples) = fragmentIdBreakdown(flat)

    def filterExternalRefs(refUuids: Seq[UUID], refNames: Seq[String], refFullIds: Seq[(UUID, String)]) = {
      val externalUuidOnlyRefs = refUuids.filterNot(objUuidsSet.contains)
      val externalNameRefs = refNames.filterNot(allObjNames.contains)

      val externalUuidsOfFullRefs = refFullIds.filterNot { case (uuid, name) => refUuids.contains(uuid) || refNames.contains(name) }.map(_._1)

      val allExternalUuidRefs = externalUuidOnlyRefs ++ externalUuidsOfFullRefs

      (allExternalUuidRefs, externalNameRefs)
    }

    val allRefIds = flat.edges.flatMap { edge => Seq(edge.parent, edge.child) }
    val (refUuids, refNames, refFullIds) = idReferenceBreakdown(allRefIds)
    val (extEdgeRefsByUuid, extEdgeRefsByName) = filterExternalRefs(refUuids, refNames, refFullIds)

    val calcRefIds = flat.points.flatMap(p => calcHolderRefs(p.calcHolder))
    val (calcRefUuids, calcRefNames, calcRefFullIds) = idReferenceBreakdown(calcRefIds)
    val (extCalcRefsByUuid, extCalcRefsByName) = filterExternalRefs(calcRefUuids, calcRefNames, calcRefFullIds)

    (extEdgeRefsByUuid ++ extCalcRefsByUuid, extEdgeRefsByName ++ extCalcRefsByName, idTuples)
  }

  def resolveIdentities(flat: FlatModelFragment, foundUuids: Set[UUID], foundNames: Set[String]) = {

    val (objUuidsSet, objNamesSet, _) = fragmentIdBreakdown(flat)

    val extantUuids = foundUuids ++ objUuidsSet
    val extantNames = foundNames ++ objNamesSet

    def resolveId(id: EntityId): Boolean = {
      id match {
        case FullEntId(uuid, name) => extantUuids.contains(uuid) || extantNames.contains(name)
        case UuidEntId(uuid) => extantUuids.contains(uuid)
        case NamedEntId(name) => extantNames.contains(name)
      }
    }

    flat.points.foreach { point =>
      val ids = calcHolderRefs(point.calcHolder)
      ids.foreach { id =>
        if (!resolveId(id)) {
          throw new LoadingException(s"Calculation for point ${EntityId.prettyPrint(point.fields.id)} has unresolved reference to input ${EntityId.prettyPrint(id)}")
        }
      }
    }

    val resolvedEdges = flat.edges.flatMap { edge =>
      if (resolveId(edge.parent) && resolveId(edge.child)) Some(edge) else None
    }

    flat.copy(edges = resolvedEdges)
  }

  private def fragmentIdBreakdown(flat: FlatModelFragment): (Set[UUID], Set[String], Seq[(UUID, String)]) = {
    val allFields = flat.modelEntities.map(_.fields.id) ++
      flat.points.map(_.fields.id) ++
      flat.commands.map(_.fields.id) ++
      flat.endpoints.map(_.fields.id)

    val (objNamesOnly, objFullIds) = objectIdBreakdown(allFields)
    val objUuidsSet = objFullIds.map(_._1).toSet
    val allObjNames = (objNamesOnly ++ objFullIds.map(_._2)).toSet

    (objUuidsSet, allObjNames, objFullIds)
  }

  def objectIdBreakdown(ids: Seq[EntityId]): (Seq[String], Seq[(UUID, String)]) = {

    val namedOnly = Vector.newBuilder[String]
    val fullyIdentified = Vector.newBuilder[(UUID, String)]

    ids.foreach {
      case FullEntId(uuid, name) => fullyIdentified += ((uuid, name))
      case NamedEntId(name) => namedOnly += name
      case _ => throw new LoadingException("IDs for model objects must include a name")
    }

    (namedOnly.result(), fullyIdentified.result())
  }

  def idReferenceBreakdown(ids: Seq[EntityId]): (Seq[UUID], Seq[String], Seq[(UUID, String)]) = {

    val uuidOnly = Vector.newBuilder[UUID]
    val namedOnly = Vector.newBuilder[String]
    val fullyIdentified = Vector.newBuilder[(UUID, String)]

    ids.foreach {
      case FullEntId(uuid, name) => fullyIdentified += ((uuid, name))
      case NamedEntId(name) => namedOnly += name
      case UuidEntId(uuid) => uuidOnly += uuid
    }

    (uuidOnly.result(), namedOnly.result(), fullyIdentified.result())
  }

  def checkForNameDuplicatesInFragment(names: Seq[String]): Unit = {
    val grouped = names.groupBy(s => s)

    val duplicateGroups = grouped.values.filter(_.size > 1).toVector

    if (duplicateGroups.nonEmpty) {
      val duplicates = duplicateGroups.map(_.head)

      throw new LoadingException("The following names were duplicated in the model: " + duplicates.mkString(", "))
    }
  }

  def namesInSet(set: FlatModelFragment): Seq[String] = {
    val allFields = set.modelEntities.map(_.fields) ++ set.points.map(_.fields) ++ set.commands.map(_.fields) ++ set.endpoints.map(_.fields)

    val namesInFragment = allFields.map(_.id).map {
      case FullEntId(uuid, name) => name
      case NamedEntId(name) => name
      case UuidEntId(uuid) => throw new LoadingException("Equipment must have a name specified")
    }

    namesInFragment
  }

  def findUnresolvedNameReferences(set: FlatModelFragment, namesInFragmentSet: Set[String]): Seq[String] = {

    val nameOnlyEdgeReferences = set.edges.flatMap { edge => Seq(idToOnlyNameOpt(edge.parent), idToOnlyNameOpt(edge.child)).flatten }

    val unresolvedEdgeNames = nameOnlyEdgeReferences.filterNot(namesInFragmentSet.contains)

    val nameOnlyCalcReferences = set.points.map(_.calcHolder).flatMap(calcHolderToOnlyNameOpt)

    val unresolvedCalcInputNames = nameOnlyCalcReferences.filterNot(namesInFragmentSet.contains)

    unresolvedEdgeNames ++ unresolvedCalcInputNames
  }

  private def calcHolderRefs(calc: CalculationHolder): Seq[EntityId] = {
    calc match {
      case UnresolvedCalc(_, inputs) => inputs.map(_._1)
      case _ => Seq()
    }
  }

  private def calcHolderToOnlyNameOpt(calc: CalculationHolder): Seq[String] = {
    calc match {
      case UnresolvedCalc(_, inputs) =>
        inputs.flatMap {
          case (id, _) => idToOnlyNameOpt(id)
        }
      case _ => Seq()
    }
  }

  private def idToOnlyNameOpt(id: EntityId): Option[String] = {
    id match {
      case NamedEntId(name) => Some(name)
      case _ => None
    }
  }

  private def idToNameOpt(id: EntityId): Option[String] = {
    id match {
      case NamedEntId(name) => Some(name)
      case FullEntId(_, name) => Some(name)
      case _ => None
    }
  }

  private def idToUuidNamePair(id: EntityId): Option[(UUID, String)] = {
    id match {
      case FullEntId(uuid, name) => Some((uuid, name))
      case _ => None
    }
  }
}
