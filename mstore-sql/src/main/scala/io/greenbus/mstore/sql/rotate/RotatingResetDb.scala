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
package io.greenbus.mstore.sql.rotate

import java.util.Date
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.mstore.{ MeasurementStoreSettings, RotationalStoreSettings }
import io.greenbus.sql.{ DbConnection, DbConnector, SqlSettings }
import org.apache.commons.cli
import org.apache.commons.cli.{ CommandLine, HelpFormatter, Options }

import scala.concurrent.duration.Duration

object RotatingResetDb extends LazyLogging {

  val infoLongFlag = "info"

  val dropLongFlag = "drop"
  val resetLongFlag = "reset"
  val createTablesLongFlag = "create-tables"
  val createIndexesLongFlag = "create-indexes"

  val resetInactiveLongFlag = "reset-inactive"

  val forceLongFlag = "force"
  val forceFlag = "f"

  def buildOptions: Options = {
    val opts = new Options
    opts.addOption("h", "help", false, "Display this help text")
    opts.addOption(new cli.Option(forceFlag, forceLongFlag, false, "Skip confirmation prompt"))
    opts.addOption(new cli.Option(null, dropLongFlag, false, "Drop all tables"))
    opts.addOption(new cli.Option(null, resetLongFlag, false, "Drop and recreate all tables in rotation database schema"))
    opts.addOption(new cli.Option(null, createTablesLongFlag, false, "Create tables (without indexes)"))
    opts.addOption(new cli.Option(null, createIndexesLongFlag, false, "Create indexes for tables"))
    opts.addOption(new cli.Option(null, resetInactiveLongFlag, false, "Delete data in the inactive (oldest) data slice"))
    opts.addOption(new cli.Option(null, infoLongFlag, false, "Print information about the structure of the time slices of the database"))
    opts
  }

  def main(args: Array[String]) {

    val options = buildOptions
    val parser = new cli.BasicParser
    val line = parser.parse(options, args)

    def printHelp() {
      (new HelpFormatter).printHelp("rotate-resetdb", options)
    }

    if (line.hasOption("h")) {
      printHelp()
    } else {

      val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")
      val measStoreConfigPath = Option(System.getProperty("io.greenbus.config.mstore")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.mstore.cfg")
      val settings = MeasurementStoreSettings.load(measStoreConfigPath)

      (settings.rotation, settings.rotationSettingsOpt) match {
        case (false, _) =>
          println("Rotation store not enabled.")
        case (true, Some(rotationSettings)) => {

          if (line.hasOption(infoLongFlag)) {
            printInfo(rotationSettings)
          } else if (line.hasOption(dropLongFlag)) {
            println("Dropping all database slices...\n")
            runDatabaseModifier(drop, rotationSettings, line)
          } else if (line.hasOption(resetLongFlag)) {
            println("Dropping and creating all database slices...\n")
            runDatabaseModifier(reset, rotationSettings, line)
          } else if (line.hasOption(createTablesLongFlag)) {
            println("Create tables (without indexes) for all database slices...\n")
            runDatabaseModifier(createTablesOnly, rotationSettings, line)
          } else if (line.hasOption(createIndexesLongFlag)) {
            println("Create indexes for all database slices...\n")
            runDatabaseModifier(createIndexessOnly, rotationSettings, line)
          } else if (line.hasOption(resetInactiveLongFlag)) {
            println(s"Resetting the inactive data slice... (index ${inactiveIndex(rotationSettings)})")
            runDatabaseModifier(resetInactive, rotationSettings, line)
          } else {
            println("Must use flag to specify operation")
          }

        }
        case (true, None) =>
          throw new IllegalArgumentException("Measurement history rotation selected but rotation settings not specified")
      }
    }
  }

  def runDatabaseModifier(runner: (DbConnection, RotationalStoreSettings) => Unit, rotationSettings: RotationalStoreSettings, line: CommandLine): Unit = {
    val force = line.hasOption(forceLongFlag) || line.hasOption(forceFlag)
    if (!force) {
      println("Proceed with modifications? (y/N)")
      val answer = readLine()
      if (!Set("y", "yes").contains(answer.trim.toLowerCase)) {
        System.exit(0)
      }
    }

    try {
      val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")
      val sqlConfigPath = Option(System.getProperty("io.greenbus.config.sql")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.sql.cfg")
      val sqlSettings = SqlSettings.load(sqlConfigPath)
      val conn = DbConnector.connect(sqlSettings)
      runner(conn, rotationSettings)
    } catch {
      case ex: Throwable =>
        logger.error("Failure: " + ex)
        println("Error: " + ex.getMessage)
    }
  }

  def reset(conn: DbConnection, storeSettings: RotationalStoreSettings) {
    val schema = new RotatingStoreSchema(storeSettings.sliceCount)
    conn.transaction {
      schema.drop
      schema.create
    }
  }

  def drop(conn: DbConnection, storeSettings: RotationalStoreSettings): Unit = {
    val schema = new RotatingStoreSchema(storeSettings.sliceCount, withIndexes = false)
    conn.transaction {
      schema.drop
    }
  }

  def createTablesOnly(conn: DbConnection, storeSettings: RotationalStoreSettings): Unit = {
    val schema = new RotatingStoreSchema(storeSettings.sliceCount, withIndexes = false)
    conn.transaction {
      schema.create
    }
  }

  def createIndexessOnly(conn: DbConnection, storeSettings: RotationalStoreSettings): Unit = {
    val schema = new RotatingStoreSchema(storeSettings.sliceCount, withIndexes = true)
    conn.transaction {
      schema.createIndexesOnly()
    }
  }

  def inactiveIndex(storeSettings: RotationalStoreSettings): Int = {
    val now = System.currentTimeMillis()
    val currentIndex = RotatingHistorianStore.indexOfSliceForTime(now, storeSettings.sliceCount, storeSettings.sliceDurationMs)
    (currentIndex + storeSettings.sliceCount + 1) % storeSettings.sliceCount
  }

  def resetInactive(conn: DbConnection, storeSettings: RotationalStoreSettings): Unit = {
    resetTable(conn, storeSettings, inactiveIndex(storeSettings))
  }

  def resetTable(conn: DbConnection, storeSettings: RotationalStoreSettings, index: Int): Unit = {
    val schema = new IndividualizedRotatingStoreSchema(index, withIndex = true)
    conn.transaction {
      schema.drop
      schema.create
    }
  }

  def printInfo(settings: RotationalStoreSettings): Unit = {
    val now = System.currentTimeMillis()
    val sliceCount = settings.sliceCount
    val sliceDurationMs = settings.sliceDurationMs

    println("Current time: " + new Date(now) + " (" + now + ")")
    println("")

    println(s"Slice count: $sliceCount")

    val d = Duration(sliceDurationMs, TimeUnit.MILLISECONDS)
    val days = d.toDays
    val daysDuration = Duration(days, TimeUnit.DAYS)
    val hoursRemainder = d - daysDuration
    val hours = hoursRemainder.toHours
    val minutesRemainder = hoursRemainder - Duration(hours, TimeUnit.HOURS)
    val minutes = minutesRemainder.toMinutes
    val secondsRemainder = minutesRemainder - Duration(minutes, TimeUnit.MINUTES)
    val seconds = secondsRemainder.toSeconds
    val millisRemainder = secondsRemainder - Duration(seconds, TimeUnit.SECONDS)
    val millis = millisRemainder.toMillis
    println(s"Slice duration: ${days}d ${hours}h ${minutes}m ${seconds}s ${millis}ms")
    println("")

    val (earliestIndex, leftEdgeOfWindow) = RotatingHistorianStore.earliestValidSliceIndexAndBeginTime(now, sliceCount, sliceDurationMs)
    Range(0, sliceCount - 1).foreach { i =>
      val index = (i + earliestIndex) % sliceCount
      val startTime = leftEdgeOfWindow + sliceDurationMs * i
      println(s" * $index: ${new Date(startTime)} ($startTime)")
    }
    val inactiveIndex = (earliestIndex + sliceCount - 1) % sliceCount
    println(s" * $inactiveIndex: [inactive]")
  }
}
