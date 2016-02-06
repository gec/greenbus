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
package io.greenbus.services.authz

import java.util.UUID
import org.squeryl.dsl.ast.{ BinaryOperatorNodeLogicalBoolean, LogicalBoolean }
import io.greenbus.services.data.EntityRow
import org.squeryl.PrimitiveTypeMode._
import io.greenbus.services.data.ServicesSchema._
import org.squeryl.Query

object EntityFilter {
  def optional(filter: Option[EntityFilter], ent: EntityRow): LogicalBoolean = {
    filter match {
      case None => true === true
      case Some(f) => f(ent.id)
    }
  }
  def optional(filter: Option[EntityFilter], entId: UUID): LogicalBoolean = {
    filter match {
      case None => true === true
      case Some(f) => f(entId)
    }
  }
  def optional(filter: Option[EntityFilter], entId: Option[UUID]): LogicalBoolean = {
    filter match {
      case None => true === true
      case Some(f) => f(entId)
    }
  }
}

trait EntityFilter {

  protected def filterQuery: Query[EntityRow]

  def apply(id: UUID): LogicalBoolean = {
    id in from(filterQuery)(ent => select(ent.id))
  }
  def apply(optId: Option[UUID]): LogicalBoolean = {
    optId in from(filterQuery)(ent => select(ent.id))
  }

  def filter(ids: Seq[UUID]): Seq[UUID] = {
    from(entities)(ent =>
      where(ent.id in ids and not(apply(ent.id)))
        select (ent.id)).toSeq
  }

  def isOutsideSet(id: UUID): Boolean = {
    from(entities)(ent =>
      where(ent.id === id and not(apply(ent.id)))
        select (ent.id)).page(0, 1).nonEmpty
  }

  def anyOutsideSet(ids: Seq[UUID]): Boolean = {
    from(entities)(ent =>
      where(ent.id in ids and not(apply(ent.id)))
        select (ent.id)).page(0, 1).nonEmpty
  }

  def anyOutsideSet(ents: Query[EntityRow]): Boolean = {
    from(ents)(ent =>
      where(not(apply(ent.id)))
        select (ent.id)).page(0, 1).nonEmpty
  }

}

sealed trait ResourceSelector {
  def clause: EntityRow => LogicalBoolean
}

object ResourceSelector {

  def buildFilter(denies: Seq[ResourceSelector], allows: Seq[ResourceSelector]): EntityFilter = {
    val denyClauses = denies.map(_.clause)
    val allowClauses = allows.map(_.clause)

    def allDenials(row: EntityRow): LogicalBoolean = {
      denyClauses.map(f => f(row)).reduceLeft((a, b) => new BinaryOperatorNodeLogicalBoolean(a, b, "and"))
    }
    def allAllows(row: EntityRow): LogicalBoolean = {
      allowClauses.map(f => f(row)).reduceLeft((a, b) => new BinaryOperatorNodeLogicalBoolean(a, b, "or"))
    }

    new EntityFilter {

      protected def filterQuery: Query[EntityRow] = {
        (allowClauses.nonEmpty, denyClauses.nonEmpty) match {
          case (false, false) => entities.where(t => true === true)

          case (true, false) =>
            from(entities)(ent =>
              where(allAllows(ent))
                select (ent))

          case (false, true) =>
            from(entities)(ent =>
              where(not(allDenials(ent)))
                select (ent))

          case (true, true) =>
            from(entities)(ent =>
              where(allAllows(ent) and not(allDenials(ent)))
                select (ent))
        }
      }
    }
  }

}

case class TypeSelector(types: Seq[String]) extends ResourceSelector {

  def clause: EntityRow => LogicalBoolean = {
    row: EntityRow =>
      exists(from(entityTypes)(t =>
        where(t.entityId === row.id and
          (t.entType in types))
          select (t.entityId)))

  }
}

case class ParentSelector(parents: Seq[String]) extends ResourceSelector {

  def clause: EntityRow => LogicalBoolean = {
    row: EntityRow =>
      exists(from(entities, edges)((parent, edge) =>
        where(parent.name in parents and
          edge.parentId === parent.id and
          edge.childId === row.id and
          edge.relationship === "owns")
          select (parent.id)))
  }
}

