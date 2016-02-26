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
package io.greenbus.integration

import io.greenbus.client.ServiceConnection
import io.greenbus.client.service.proto.MeasurementRequests.{ MeasurementHistorySampledQuery, MeasurementHistoryQuery }
import io.greenbus.client.service.{ MeasurementService, ModelService }
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.client.service.proto.Model.{ ModelUUID, PointCategory }
import io.greenbus.client.service.proto.ModelRequests.EntityKeySet
import io.greenbus.loader.set.Mdl.{ FlatModelFragment, PointDesc }
import io.greenbus.loader.set._
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.mstore.sql.{ HistoricalValueRow, MeasurementStoreSchema }
import io.greenbus.services.ResetDatabase
import io.greenbus.sql.{ DbConnection, DbConnector, SqlSettings }
import io.greenbus.util.{ Timing, UserSettings }

import scala.concurrent.Await
import scala.concurrent.duration._

object HistoryBenchmarkSetup {

  def connectSql(): DbConnection = {
    val config = SqlSettings.load("io.greenbus.sql.cfg")
    DbConnector.connect(config)
  }

  def meas(v: Double, t: Long) = {
    Measurement.newBuilder()
      .setType(Measurement.Type.DOUBLE)
      .setDoubleVal(v)
      .setTime(t)
      .build()
  }
  def measBytes(v: Double, t: Long) = {
    meas(v, t).toByteArray
  }

  def main(args: Array[String]): Unit = {
    val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")
    val amqpConfigPath = Option(System.getProperty("io.greenbus.config.amqp")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.msg.amqp.cfg")
    val userConfigPath = Option(System.getProperty("io.greenbus.config.user")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.user.cfg")

    val config = AmqpSettings.load(amqpConfigPath)
    val userConfig = UserSettings.load(userConfigPath)

    val sql = connectSql()

    ResetDatabase.reset(sql)

    val conn = ServiceConnection.connect(config, QpidBroker, 60000)
    val session = conn.login(userConfig.user, userConfig.password, Duration(5000, MILLISECONDS))

    val pdesc = PointDesc(EntityFields(NamedEntId("PointA"), Set("Point"), Seq()), PointCategory.ANALOG, "unitA", NoCalculation)
    val frag = FlatModelFragment(Seq(), Seq(pdesc), Seq(), Seq(), Seq())

    Importer.runImport(session, None, Seq(NamedEntId("PointA")), frag, false)

    val modelClient = ModelService.client(session)
    val point = Await.result(modelClient.getPoints(EntityKeySet.newBuilder().addNames("PointA").build()), 5000.milliseconds).head

    val pointUuid = UUIDHelpers.protoUUIDToUuid(point.getUuid)

    println(pointUuid)

    val latestTime = System.currentTimeMillis()
    val count = 3000000

    sql.transaction {

      Range(0, count).grouped(200).foreach { range =>

        val rows = range.map { i =>
          val measTime = latestTime - (count - i)
          HistoricalValueRow(pointUuid, measTime, measBytes(0.0 + (i * 0.01), measTime))
        }

        MeasurementStoreSchema.historicalValues.insert(rows)
        print(".")
      }
    }
    println("")

    println("finished")

  }
}

object HistoryBenchmarks {

  def main(args: Array[String]): Unit = {

    val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")
    val amqpConfigPath = Option(System.getProperty("io.greenbus.config.amqp")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.msg.amqp.cfg")
    val userConfigPath = Option(System.getProperty("io.greenbus.config.user")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.user.cfg")

    val config = AmqpSettings.load(amqpConfigPath)
    val userConfig = UserSettings.load(userConfigPath)

    val conn = ServiceConnection.connect(config, QpidBroker, 60000)
    val session = conn.login(userConfig.user, userConfig.password, Duration(5000, MILLISECONDS))

    val modelClient = ModelService.client(session)
    val point = Await.result(modelClient.getPoints(EntityKeySet.newBuilder().addNames("PointA").build()), 5000.milliseconds).head

    val (timeStart, timeEnd) = findTimeInterval(session, point.getUuid)
    println(s"$timeStart - $timeEnd")

    Range(0, 100).foreach(_ => println(limitQuery(session, point.getUuid, 100)))

    /*Seq(100, 1000, 10000, 100000, 1000000).foreach { count =>
      val values = Range(0, 20).map(_ => limitQuery(session, point.getUuid, count))
      val avg = values.sum.toDouble / values.size
      println(s"$count\t$avg")
    }*/

    Seq(1000, 10000, 100000, 1000000).foreach { count =>
      val values = Range(0, 20).map(_ => sampledQuery(session, point.getUuid, timeStart, timeStart + count))
      val avg = values.sum.toDouble / values.size
      println(s"$count\t$avg")
      //println(s"$avg")
    }

    //val t = limitQuery(session, point.getUuid, 100)

    //println(t)

    conn.disconnect()
  }

  def findTimeInterval(session: Session, uuid: ModelUUID): (Long, Long) = {

    val measClient = MeasurementService.client(session)

    val firstQuery = MeasurementHistoryQuery.newBuilder()
      .setPointUuid(uuid)
      .setLimit(1)
      .setLatest(false)
      .build()

    val first = Await.result(measClient.getHistory(firstQuery), 60000.milliseconds)

    val secondQuery = MeasurementHistoryQuery.newBuilder()
      .setPointUuid(uuid)
      .setLimit(1)
      .setLatest(true)
      .build()

    val second = Await.result(measClient.getHistory(secondQuery), 60000.milliseconds)

    (first.getValue(0).getTime, second.getValue(0).getTime)
  }

  def sampledQuery(session: Session, uuid: ModelUUID, start: Long, end: Long /*, amount: Int*/ ): Long = {

    val measClient = MeasurementService.client(session)

    val query = MeasurementHistorySampledQuery.newBuilder()
      .setPointUuid(uuid)
      .setTimeFrom(start)
      .setTimeTo(end)
      .setTimeWindowLength((end - start) / 250)
      .build()

    Timing.benchmark {
      Await.result(measClient.getHistorySamples(query), 60000.milliseconds)
    }
  }

  def limitQuery(session: Session, uuid: ModelUUID, amount: Int): Long = {

    val measClient = MeasurementService.client(session)

    val query = MeasurementHistoryQuery.newBuilder()
      .setPointUuid(uuid)
      .setLimit(amount)
      .build()

    Timing.benchmark {
      Await.result(measClient.getHistory(query), 60000.milliseconds)
    }
  }
}
