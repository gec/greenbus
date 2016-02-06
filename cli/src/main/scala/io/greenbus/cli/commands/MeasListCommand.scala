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

import java.io.{ PrintWriter, FileOutputStream }
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

import io.greenbus.cli.view._
import io.greenbus.cli.{ CliContext, Command }
import io.greenbus.client.service.proto.MeasurementRequests.MeasurementHistoryQuery
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.client.service.proto.Model.{ Point, ModelUUID }
import io.greenbus.client.service.proto.ModelRequests.{ EntityKeySet, EntityPagingParams, PointQuery }
import io.greenbus.client.service.{ MeasurementService, ModelService }

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class MeasListCommand extends Command[CliContext] {

  val commandName = "meas:list"
  val description = "List current measurement values"

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)
    val measClient = MeasurementService.client(context.session)

    def results(last: Option[ModelUUID], pageSize: Int) = {

      val pageBuilder = EntityPagingParams.newBuilder().setPageSize(pageSize)
      last.foreach(pageBuilder.setLastUuid)

      val query = PointQuery.newBuilder().setPagingParams(pageBuilder).build()

      modelClient.pointQuery(query).flatMap {
        case Seq() => Future.successful(Nil)
        case points =>
          val uuids = points.map(_.getUuid)
          measClient.getCurrentValues(uuids).map { pointMeas =>
            val pointMap = pointMeas.map(p => (p.getPointUuid, p)).toMap
            points.map { point =>
              val measOpt = pointMap.get(point.getUuid)
              (point, measOpt.map(_.getValue))
            }
          }
      }

    }

    def pageBoundary(results: Seq[(Point, Option[Measurement])]) = results.last._1.getUuid

    Paging.page(context.reader, results, pageBoundary, MeasWithEmptiesView.printTable, MeasWithEmptiesView.printRows)
  }
}

class MeasHistoryCommand extends Command[CliContext] {

  val commandName = "meas:history"
  val description = "Get history for measurement"

  val measName = strings.arg("name", "Name of point to retrieve history for")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)
    val measClient = MeasurementService.client(context.session)

    val pointResults = Await.result(modelClient.get(EntityKeySet.newBuilder().addNames(measName.value).build()), 5000.milliseconds)

    val point = pointResults.headOption.getOrElse {
      throw new IllegalArgumentException("Point does not exist")
    }

    def results(last: Option[Long], pageSize: Int) = {
      val queryBuilder = MeasurementHistoryQuery.newBuilder()
        .setPointUuid(point.getUuid)
        .setLatest(true)
        .setLimit(pageSize)

      last.foreach(queryBuilder.setTimeTo)

      val query = queryBuilder.build()

      measClient.getHistory(query).map(_.getValueList.toSeq.reverse)
    }

    def pageBoundary(results: Seq[Measurement]) = results.last.getTime

    Paging.page(context.reader, results, pageBoundary, MeasurementHistoryView.printTable, MeasurementHistoryView.printRows)
  }
}

class MeasSubscribeCommand extends Command[CliContext] {

  val commandName = "meas:subscribe"
  val description = "Subscribe to measurement update values"

  val allFlag = optionSwitch(Some("a"), Some("all"), "Subscribe to all updates")

  val measNames = strings.argRepeated("name", "Name of point to retrieve history for")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)
    val measClient = MeasurementService.client(context.session)

    val sub = if (allFlag.value) {

      if (measNames.value.nonEmpty) {
        throw new IllegalArgumentException("Must not include measurement names when subscribing to all")
      }

      val (_, sub) = Await.result(measClient.getCurrentValuesAndSubscribe(Seq()), 5000.milliseconds)

      val widths = new AtomicReference(Seq.empty[Int])

      sub.start { measNotification =>
        widths.set(SimpleMeasurementView.printRows(List((measNotification.getPointName, measNotification.getValue, "--")), widths.get()))
      }

      sub

    } else {

      if (measNames.value.isEmpty) {
        throw new IllegalArgumentException("Must include at least one measurement name")
      }

      val pointResults = Await.result(modelClient.getPoints(EntityKeySet.newBuilder().addAllNames(measNames.value).build()), 5000.milliseconds)

      if (pointResults.isEmpty) {
        throw new IllegalArgumentException("Points did not exist")
      }

      val uuidToPointMap = pointResults.map(p => p.getUuid -> p).toMap

      val (results, sub) = Await.result(measClient.getCurrentValuesAndSubscribe(pointResults.map(_.getUuid)), 5000.milliseconds)

      val origWidths = SimpleMeasurementView.printTable(results.flatMap(r => uuidToPointMap.get(r.getPointUuid).map(p => (p.getName, r.getValue, p.getUnit))).toList)

      val widths = new AtomicReference(origWidths)

      sub.start { measNotification =>
        val pointOpt = uuidToPointMap.get(measNotification.getPointUuid)
        pointOpt.foreach { point =>
          widths.set(SimpleMeasurementView.printRows(List((measNotification.getPointName, measNotification.getValue, point.getUnit)), widths.get()))
        }
      }

      sub
    }

    context.reader.readCharacter()
    sub.cancel()
  }
}

class MeasDownloadCommand extends Command[CliContext] {

  val commandName = "meas:download"
  val description = "Download measurement history to a file"

  val measName = strings.arg("name", "Name of point to retrieve history for")

  val fileName = strings.option(Some("o"), Some("output"), "Filename to write output to")

  val startTimeOption = strings.option(Some("s"), Some("start"), "Start time, UTC, in format \"yyyy-MM-dd HH:mm\"")
  val endTimeOption = strings.option(Some("e"), Some("end"), "End time, UTC, in format \"yyyy-MM-dd HH:mm\"")

  val offsetHours = ints.option(None, Some("hours-offset"), "Duration time, in hours, offset from the end time")
  val offsetMinutes = ints.option(None, Some("minutes-offset"), "Duration time, in minutes, offset from the end time")

  val delimiterOption = strings.option(Some("d"), None, "Delimiter character (e.g. tab-separated, comma-separated). Tab-separated by default.")

  val rawTimeOption = optionSwitch(None, Some("raw-time"), "Write time in milliseconds since 1970 UTC")
  val dateFormatOption = strings.option(None, Some("date-format"), "Specifies format for time. See documentation for the SimpleDataFormat Java class for the syntax.")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)
    val measClient = MeasurementService.client(context.session)

    val pointResults = Await.result(modelClient.get(EntityKeySet.newBuilder().addNames(measName.value).build()), 5000.milliseconds)

    val point = pointResults.headOption.getOrElse {
      throw new IllegalArgumentException(s"Point ${measName.value} not found.")
    }

    val endTimeMillis = endTimeOption.value match {
      case None => System.currentTimeMillis()
      case Some(endTime) =>
        try {
          val date = new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(endTimeOption.value.get)
          date.getTime
        } catch {
          case ex: Throwable =>
            throw new IllegalArgumentException("Could not parse date format for end time.")
        }
    }

    val startTimeMillis = if (startTimeOption.value.nonEmpty) {
      try {
        val date = new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(startTimeOption.value.get)
        date.getTime
      } catch {
        case ex: Throwable =>
          throw new IllegalArgumentException("Could not parse date format for start time.")
      }
    } else if (offsetHours.value.nonEmpty || offsetMinutes.value.nonEmpty) {
      endTimeMillis -
        offsetHours.value.map(h => h * 60 * 60 * 1000).getOrElse(0) -
        offsetMinutes.value.map(min => min * 60 * 1000).getOrElse(0)
    } else {
      throw new IllegalArgumentException("Must set a start time or a time offset.")
    }

    val delimiter = delimiterOption.value.getOrElse("\t")

    val dateFormatter = dateFormatOption.value.map(s => new SimpleDateFormat(s))

    def timeFormat(m: Measurement): String = {
      if (rawTimeOption.value) {
        m.getTime.toString
      } else if (dateFormatOption.value.nonEmpty) {
        dateFormatter.map(f => f.format(m.getTime)).getOrElse(throw new IllegalArgumentException("Couldn't build date formatter"))
      } else {
        MeasViewCommon.timeString(m)
      }
    }

    def toMeasLine(m: Measurement): String = {
      val time = timeFormat(m)
      Seq(time, MeasViewCommon.value(m), MeasViewCommon.shortQuality(m), MeasViewCommon.longQuality(m)).mkString(delimiter)
    }

    val pageSize = 1000

    def historyBuilder(startTime: Long): MeasurementHistoryQuery = {
      MeasurementHistoryQuery.newBuilder()
        .setPointUuid(point.getUuid)
        .setLatest(false)
        .setTimeFrom(startTime)
        .setTimeTo(endTimeMillis)
        .setLimit(pageSize)
        .build()
    }

    val filepath = fileName.value.getOrElse(throw new IllegalArgumentException("Must include output filename."))
    val file = new FileOutputStream(filepath)
    val pw = new PrintWriter(file)

    try {

      var latestStartTime = startTimeMillis
      var done = false
      while (!done) {
        val query = historyBuilder(latestStartTime)
        val results = Await.result(measClient.getHistory(query), 10000.milliseconds)

        results.getValueList.foreach { m => pw.println(toMeasLine(m)) }

        if (results.getValueList.size() < pageSize) {
          done = true
        } else {
          latestStartTime = results.getValueList.last.getTime
        }
      }
    } finally {
      pw.flush()
      pw.close()
    }
  }
}

