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
package io.greenbus.cli.commands

import io.greenbus.cli.{ CliContext, Command }
import io.greenbus.cli.view._
import scala.concurrent.Await
import io.greenbus.client.service.proto.ModelRequests._
import scala.concurrent.duration._
import io.greenbus.client.service.ModelService
import io.greenbus.client.service.proto.Model.{ Entity, ModelUUID }
import scala.collection.JavaConversions._

class EntityListCommand extends Command[CliContext] {

  val commandName = "entity:list"
  val description = "List all entities"

  protected def execute(context: CliContext) {
    val client = ModelService.client(context.session)

    def getResults(last: Option[ModelUUID], pageSize: Int) = {
      val pageBuilder = EntityPagingParams.newBuilder().setPageSize(pageSize)
      last.foreach(pageBuilder.setLastUuid)
      client.entityQuery(EntityQuery.newBuilder().setPagingParams(pageBuilder).build())
    }

    def pageBoundary(results: Seq[Entity]) = {
      results.last.getUuid
    }

    Paging.page(context.reader, getResults, pageBoundary, EntityView.printTable, EntityView.printRows)
  }
}

class EntityViewCommand extends Command[CliContext] {

  val commandName = "entity:view"
  val description = "View details of an entity specified by name"

  val name = strings.arg("entity name", "Entity name")

  protected def execute(context: CliContext) {
    val client = ModelService.client(context.session)

    val result = Await.result(client.get(EntityKeySet.newBuilder().addNames(name.value).build()), 5000.milliseconds)

    result.headOption.map(EntityView.printInspect).getOrElse(println("No entities found."))
  }
}

class EntityTypeCommand extends Command[CliContext] {

  val commandName = "entity:type"
  val description = "List entities that include any of the given types"

  val types = strings.argRepeated("entity type", "Entity type to search for")

  protected def execute(context: CliContext) {

    if (types.value.isEmpty) {
      throw new IllegalArgumentException("Must include at least one type")
    }

    val client = ModelService.client(context.session)

    def getResults(last: Option[ModelUUID], pageSize: Int) = {
      val pageBuilder = EntityPagingParams.newBuilder().setPageSize(pageSize)
      last.foreach(pageBuilder.setLastUuid)

      val query = EntityQuery.newBuilder
        .setTypeParams(EntityTypeParams.newBuilder()
          .addAllIncludeTypes(types.value))
        .setPagingParams(pageBuilder)
        .build()

      client.entityQuery(query)
    }

    def pageBoundary(results: Seq[Entity]) = {
      results.last.getUuid
    }

    Paging.page(context.reader, getResults, pageBoundary, EntityView.printTable, EntityView.printRows)
  }
}

class EntityChildrenCommand extends Command[CliContext] {

  val commandName = "entity:children"
  val description = "List children of specified entity "

  val name = strings.arg("entity name", "Entity name")

  val relation = strings.option(Some("r"), Some("relation"), "Relationship to children (default: \"owns\")")

  val childTypes = strings.optionRepeated(Some("t"), Some("child-type"), "Child types to include")

  val depthLimit = ints.option(Some("d"), Some("depth"), "Depth limit for children")

  protected def execute(context: CliContext) {

    val relationship = relation.value.getOrElse("owns")

    val client = ModelService.client(context.session)

    def getResults(last: Option[ModelUUID], pageSize: Int) = {
      val queryBuilder = EntityRelationshipFlatQuery.newBuilder
        .addStartNames(name.value)
        .setDescendantOf(true)
        .setRelationship(relationship)

      if (childTypes.value.nonEmpty) {
        queryBuilder.addAllEndTypes(childTypes.value)
      }

      depthLimit.value.foreach(queryBuilder.setDepthLimit)

      val pageParamsB = EntityPagingParams.newBuilder()
        .setPageSize(pageSize)

      last.foreach(pageParamsB.setLastUuid)

      val query = queryBuilder.setPagingParams(pageParamsB).build()
      client.relationshipFlatQuery(query)
    }

    def pageBoundary(results: Seq[Entity]) = {
      results.last.getUuid
    }

    Paging.page(context.reader, getResults, pageBoundary, EntityView.printTable, EntityView.printRows)
  }
}
