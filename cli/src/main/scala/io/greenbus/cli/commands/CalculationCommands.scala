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

import io.greenbus.cli.view.CalculationView
import io.greenbus.cli.{ CliContext, Command }
import io.greenbus.client.service.ModelService
import io.greenbus.client.service.proto.Calculations.CalculationDescriptor
import io.greenbus.client.service.proto.Model.{ Entity, ModelUUID }
import io.greenbus.client.service.proto.ModelRequests.{ EntityPagingParams, EndpointQuery, EntityKeyPair, EntityRelationshipFlatQuery }

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class CalculationListCommand extends Command[CliContext] {

  val commandName = "calc:list"
  val description = "List all calculations"

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)

    val calcEndpoints = Await.result(modelClient.endpointQuery(EndpointQuery.newBuilder().addProtocols("calculator").build()), 5000.milliseconds)

    val endpointUuids = calcEndpoints.map(_.getUuid)

    def allQueryResults(last: Option[ModelUUID], pageSize: Int): Future[Seq[(Entity, Option[CalculationDescriptor])]] = {

      val queryB = EntityRelationshipFlatQuery.newBuilder()
        .addAllStartUuids(endpointUuids)
        .setRelationship("source")
        .setDescendantOf(true)
        .addEndTypes("Point")

      val pageParamsB = EntityPagingParams.newBuilder()
        .setPageSize(pageSize)

      last.foreach(pageParamsB.setLastUuid)

      val query = queryB.setPagingParams(pageParamsB).build()

      modelClient.relationshipFlatQuery(query).flatMap {
        case Seq() => Future.successful(Nil)
        case points =>
          val uuidKeys = points.map { p =>
            EntityKeyPair.newBuilder().setUuid(p.getUuid).setKey("calculation").build()
          }

          modelClient.getEntityKeyValues(uuidKeys).map { kvs =>

            val pointToCalcMap: Map[ModelUUID, CalculationDescriptor] = kvs.flatMap { kv =>
              val calcDescOpt = if (kv.hasValue && kv.getValue.hasByteArrayValue) {
                try {
                  Some(CalculationDescriptor.parseFrom(kv.getValue.getByteArrayValue))
                } catch {
                  case ex: Throwable => None
                }
              } else {
                None
              }
              calcDescOpt.map(c => (kv.getUuid, c))
            }.toMap

            points.map(p => (p, pointToCalcMap.get(p.getUuid)))
          }
      }
    }

    def pageBoundary(results: Seq[(Entity, Option[CalculationDescriptor])]) = results.last._1.getUuid

    if (endpointUuids.nonEmpty) {
      Paging.page(context.reader, allQueryResults, pageBoundary, CalculationView.printTable, CalculationView.printRows)
    } else {
      CalculationView.printTable(Nil)
    }
  }
}
