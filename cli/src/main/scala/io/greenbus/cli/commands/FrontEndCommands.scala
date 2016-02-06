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

import java.util.concurrent.atomic.AtomicReference

import io.greenbus.cli.view._
import io.greenbus.cli.{ CliContext, Command }
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.client.service.{ FrontEndService, ModelService }
import io.greenbus.client.service.proto.Model.{ Endpoint, Point, ModelUUID, Command => ProtoCommand }
import io.greenbus.client.service.proto.ModelRequests.{ EntityEdgeQuery, EntityKeySet, EntityPagingParams, _ }
import io.greenbus.msg.Session

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

object EndpointListCommand {

  def endpointCombined(session: Session, query: EndpointQuery): Future[Seq[(Endpoint, Option[(FrontEndConnectionStatus.Status, Long)])]] = {
    val modelClient = ModelService.client(session)
    val frontEndClient = FrontEndService.client(session)

    val endFut = modelClient.endpointQuery(query)

    endFut.flatMap { endpoints =>
      val uuids = endpoints.map(_.getUuid).distinct

      frontEndClient.getFrontEndConnectionStatuses(EntityKeySet.newBuilder().addAllUuids(uuids).build()).map { statuses =>
        val statusMap = statuses.map(s => (s.getEndpointUuid, (s.getState, s.getUpdateTime))).toMap
        endpoints.map { end =>
          (end, statusMap.get(end.getUuid))
        }
      }
    }
  }

}
class EndpointListCommand extends Command[CliContext] {

  val commandName = "endpoint:list"
  val description = "List all endpoints"

  val protocols = strings.optionRepeated(Some("p"), Some("protocol"), "Endpoint protocols to query for")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)
    val frontEndClient = FrontEndService.client(context.session)

    def allQueryResults(last: Option[ModelUUID], pageSize: Int): Future[Seq[(Endpoint, Option[(FrontEndConnectionStatus.Status, Long)])]] = {

      val pageBuilder = EntityPagingParams.newBuilder().setPageSize(pageSize)
      last.foreach(pageBuilder.setLastUuid)

      val b = EndpointQuery.newBuilder().setPagingParams(pageBuilder)

      if (protocols.value.nonEmpty) {
        b.addAllProtocols(protocols.value)
      }

      EndpointListCommand.endpointCombined(context.session, b.build())
    }

    def pageBoundary(results: Seq[(Endpoint, Option[(FrontEndConnectionStatus.Status, Long)])]) = results.last._1.getUuid

    Paging.page(context.reader, allQueryResults, pageBoundary, EndpointStatusView.printTable, EndpointStatusView.printRows)
  }
}

class EndpointSubscribeCommand extends Command[CliContext] {

  val commandName = "endpoint:subscribe"
  val description = "Subscribe to endpoints"

  val protocols = strings.optionRepeated(Some("p"), Some("protocol"), "Endpoint protocols to query for")
  val allUpdates = optionSwitch(Some("a"), Some("all"), "Shows all subscription updates, even those with no status change")

  private case class Cache(widths: Seq[Int], endpoints: Map[ModelUUID, Endpoint], statuses: Map[ModelUUID, (FrontEndConnectionStatus.Status, Long)])

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)
    val frontEndClient = FrontEndService.client(context.session)

    val b = EndpointSubscriptionQuery.newBuilder()

    if (protocols.value.nonEmpty) {
      b.addAllProtocols(protocols.value)
    }

    val query = b.build()

    val immediateEndpoints = {
      val regQuery = EndpointQuery.newBuilder()
      if (protocols.value.nonEmpty) b.addAllProtocols(protocols.value)
      Await.result(EndpointListCommand.endpointCombined(context.session, regQuery.build()), 5000.milliseconds)
    }

    val (_, sub) = Await.result(modelClient.subscribeToEndpoints(query), 5000.milliseconds)
    val (_, statusSub) = Await.result(frontEndClient.subscribeToFrontEndConnectionStatuses(query), 5000.milliseconds)

    val endpointMap = immediateEndpoints.map(tup => (tup._1.getUuid, tup._1)).toMap
    val statusMap = immediateEndpoints.flatMap(tup => tup._2.map(stat => (tup._1.getUuid, stat))).toMap

    val originals = immediateEndpoints.map(tup => (tup._1, tup._2))
    val origWidths = EndpointStatusView.printTable(originals.toList)

    val cacheRef = new AtomicReference[Cache](Cache(origWidths, endpointMap, statusMap))

    sub.start { endpointEvent =>
      val cache = cacheRef.get()
      val update = (endpointEvent.getValue, cache.statuses.get(endpointEvent.getValue.getUuid))
      val nextWidths = EndpointStatusView.printRows(List(update), cache.widths)
      cacheRef.set(
        cache.copy(
          widths = nextWidths,
          endpoints = cache.endpoints.updated(endpointEvent.getValue.getUuid, endpointEvent.getValue)))
    }

    statusSub.start { statusEvent =>
      val stateTup = (statusEvent.getValue.getState, statusEvent.getValue.getUpdateTime)
      val cache = cacheRef.get()
      endpointMap.get(statusEvent.getValue.getEndpointUuid) match {
        case Some(endpoint) =>
          val currentStatusOpt: Option[(FrontEndConnectionStatus.Status, Long)] = cache.statuses.get(endpoint.getUuid)
          if (!currentStatusOpt.exists(tup => tup._1 == statusEvent.getValue.getState) || allUpdates.value) {
            val update = (endpoint, Some(stateTup))
            val nextWidths = EndpointStatusView.printRows(List(update), cache.widths)
            cacheRef.set(
              cache.copy(
                widths = nextWidths,
                statuses = cache.statuses.updated(statusEvent.getValue.getEndpointUuid, stateTup)))
          } else {
            cacheRef.set(cache.copy(statuses = cache.statuses.updated(statusEvent.getValue.getEndpointUuid, stateTup)))
          }

        case None =>
          cacheRef.set(cache.copy(statuses = cache.statuses.updated(statusEvent.getValue.getEndpointUuid, stateTup)))
      }
    }

    try {
      context.reader.readCharacter()
    } finally {
      sub.cancel()
      statusSub.cancel()
    }
  }
}

class EndpointEnableCommand extends Command[CliContext] {

  val commandName = "endpoint:enable"
  val description = "Enable endpoint"

  val names = strings.argRepeated("endpoint name", "Endpoint names")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)

    if (names.value.isEmpty) {
      throw new IllegalArgumentException("Must include at least one argument")
    }

    val keyQuery = EntityKeySet.newBuilder().addAllNames(names.value).build()

    val endpoints = Await.result(modelClient.getEndpoints(keyQuery), 5000.milliseconds)

    val nameToUuidMap = endpoints.map(end => (end.getName, end.getUuid)).toMap

    val updates = names.value.flatMap { name =>
      nameToUuidMap.get(name).map { uuid =>
        EndpointDisabledUpdate.newBuilder()
          .setEndpointUuid(uuid)
          .setDisabled(false)
          .build()
      }
    }

    val results = Await.result(modelClient.putEndpointDisabled(updates), 5000.milliseconds)

    EndpointView.printTable(results.toList)
  }
}

class EndpointDisableCommand extends Command[CliContext] {

  val commandName = "endpoint:disable"
  val description = "Disable endpoint"

  val names = strings.argRepeated("endpoint name", "Endpoint names")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)

    if (names.value.isEmpty) {
      throw new IllegalArgumentException("Must include at least one argument")
    }

    val keyQuery = EntityKeySet.newBuilder().addAllNames(names.value).build()

    val endpoints = Await.result(modelClient.getEndpoints(keyQuery), 5000.milliseconds)

    val nameToUuidMap = endpoints.map(end => (end.getName, end.getUuid)).toMap

    val updates = names.value.flatMap { name =>
      nameToUuidMap.get(name).map { uuid =>
        EndpointDisabledUpdate.newBuilder()
          .setEndpointUuid(uuid)
          .setDisabled(true)
          .build()
      }
    }

    val results = Await.result(modelClient.putEndpointDisabled(updates), 5000.milliseconds)

    EndpointView.printTable(results.toList)
  }
}

class PointListCommand extends Command[CliContext] {

  val commandName = "point:list"
  val description = "List all points"

  val showCommands = optionSwitch(Some("c"), Some("show-commands"), "Show commands associated with points")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)

    def vanillaResults(last: Option[ModelUUID], pageSize: Int) = {

      val pageBuilder = EntityPagingParams.newBuilder().setPageSize(pageSize)
      last.foreach(pageBuilder.setLastUuid)

      val query = PointQuery.newBuilder().setPagingParams(pageBuilder).build()
      modelClient.pointQuery(query)
    }

    def resultsWithCommands(last: Option[ModelUUID], pageSize: Int): Future[Seq[(Point, Seq[String])]] = {

      val pageBuilder = EntityPagingParams.newBuilder().setPageSize(pageSize)
      last.foreach(pageBuilder.setLastUuid)

      val pointQuery = PointQuery.newBuilder().setPagingParams(pageBuilder).build()
      val pointsFut = modelClient.pointQuery(pointQuery)

      pointsFut.flatMap {
        case Seq() => Future.successful(Nil)
        case points =>

          val query = EntityEdgeQuery.newBuilder()
            .addAllParentUuids(points.map(_.getUuid))
            .addRelationships("feedback")
            .build()

          modelClient.edgeQuery(query).flatMap { edges =>
            val grouped = edges.map(e => (e.getParent, e.getChild)).groupBy(_._1).mapValues(_.map(_._2))
            val commandIds = edges.map(_.getChild).distinct

            modelClient.getCommands(EntityKeySet.newBuilder().addAllUuids(commandIds).build()).map { commands =>
              val commandMap = commands.map(c => (c.getUuid, c.getName)).toMap
              points.map { p =>
                val cmdIds: Seq[ModelUUID] = grouped.get(p.getUuid).getOrElse(Nil)
                val cmdNames: Seq[String] = cmdIds.flatMap(id => commandMap.get(id))
                (p, cmdNames)
              }
            }
          }
      }
    }

    def pageBoundary(results: Seq[Point]) = results.last.getUuid
    def pageBoundaryWithCommands(results: Seq[(Point, Seq[String])]) = results.last._1.getUuid

    if (showCommands.value) {
      Paging.page(context.reader, resultsWithCommands, pageBoundaryWithCommands, PointWithCommandsView.printTable, PointWithCommandsView.printRows)
    } else {
      Paging.page(context.reader, vanillaResults, pageBoundary, PointView.printTable, PointView.printRows)
    }
  }
}

class CommandListCommand extends Command[CliContext] {

  val commandName = "command:list"
  val description = "List all commands"

  val showCommands = optionSwitch(Some("p"), Some("show-points"), "Show points associated with commands")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)

    def vanillaResults(last: Option[ModelUUID], pageSize: Int) = {

      val pageBuilder = EntityPagingParams.newBuilder().setPageSize(pageSize)
      last.foreach(pageBuilder.setLastUuid)

      val query = CommandQuery.newBuilder().setPagingParams(pageBuilder).build()
      modelClient.commandQuery(query)
    }

    def resultsWithPoints(last: Option[ModelUUID], pageSize: Int): Future[Seq[(ProtoCommand, Seq[String])]] = {

      val pageBuilder = EntityPagingParams.newBuilder().setPageSize(pageSize)
      last.foreach(pageBuilder.setLastUuid)

      val query = CommandQuery.newBuilder().setPagingParams(pageBuilder).build()

      modelClient.commandQuery(query).flatMap {
        case Seq() => Future.successful(Nil)
        case commands =>

          val query = EntityEdgeQuery.newBuilder()
            .addAllChildUuids(commands.map(_.getUuid))
            .addRelationships("feedback")
            .build()

          modelClient.edgeQuery(query).flatMap {
            case Seq() => Future.successful(Nil)
            case edges =>
              val grouped = edges.map(e => (e.getChild, e.getParent)).groupBy(_._1).mapValues(_.map(_._2))
              val pointIds = edges.map(_.getParent).distinct

              modelClient.getPoints(EntityKeySet.newBuilder().addAllUuids(pointIds).build()).map { points =>
                val pointMap = points.map(c => (c.getUuid, c.getName)).toMap
                commands.map { c =>
                  val pointIds: Seq[ModelUUID] = grouped.get(c.getUuid).getOrElse(Nil)
                  val pointNames: Seq[String] = pointIds.flatMap(id => pointMap.get(id))
                  (c, pointNames)
                }
              }
          }
      }
    }

    def pageBoundary(results: Seq[ProtoCommand]) = results.last.getUuid
    def pageBoundaryWithCommands(results: Seq[(ProtoCommand, Seq[String])]) = results.last._1.getUuid

    if (showCommands.value) {
      Paging.page(context.reader, resultsWithPoints, pageBoundaryWithCommands, CommandWithPointsView.printTable, CommandWithPointsView.printRows)
    } else {
      Paging.page(context.reader, vanillaResults, pageBoundary, CommandView.printTable, CommandView.printRows)
    }
  }
}

