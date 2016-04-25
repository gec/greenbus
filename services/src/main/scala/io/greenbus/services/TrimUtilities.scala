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
package io.greenbus.services

import java.text.SimpleDateFormat
import java.util.Date

import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.mstore.sql.MeasurementStoreSchema
import io.greenbus.services.data.ServicesSchema
import io.greenbus.sql.{ DbConnector, SqlSettings, DbConnection }
import org.apache.commons.cli
import org.apache.commons.cli.{ CommandLine, HelpFormatter, Options }

object TrimUtilities extends Logging {

  val forceLongFlag = "force"
  val forceFlag = "f"

  val hourOffsetFlag = "hour-offset"
  val dayOffsetFlag = "day-offset"

  val boundaryFlag = "boundary"

  val millisecondsInHour = 60 * 60 * 1000
  val millisecondsInDay = millisecondsInHour * 24

  def buildOptions: Options = {
    val opts = new Options
    opts.addOption("h", "help", false, "Display this help text")
    opts.addOption(new cli.Option(forceFlag, forceLongFlag, false, "Skip confirmation prompt"))
    opts.addOption(new cli.Option(null, hourOffsetFlag, true, "Entries earlier than the current time by this offset in hours will be dropped"))
    opts.addOption(new cli.Option(null, dayOffsetFlag, true, "Entries earlier than the current time by this offset in days will be dropped"))
    opts.addOption(new cli.Option(null, boundaryFlag, true, "Entries earlier than this time, specified in format \"yyyy-MM-dd HH:mm\" will be dropped"))
    opts
  }

  def nullableToTimeOpt(v: String): Option[Long] = {
    Option(v).flatMap { s =>
      try {
        Some(s.toLong)
      } catch {
        case ex: Throwable => None
      }
    }
  }

  def run(args: Array[String], helpName: String, trim: (DbConnection, Long) => Unit) {

    val options = buildOptions
    val parser = new cli.BasicParser
    val line = parser.parse(options, args)

    def printHelp() {
      (new HelpFormatter).printHelp(helpName, options)
    }

    if (line.hasOption("h")) {
      printHelp()
    } else {

      val horizon = parseTime(line)

      println("Date selected: " + new Date(horizon).toString)

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

        trim(conn, horizon)

      } catch {
        case ex: Throwable =>
          logger.error("Failure: " + ex)
          println("Error: " + ex.getMessage)
      }
    }
  }

  def parseTime(line: CommandLine): Long = {
    if (line.hasOption(boundaryFlag)) {
      try {
        val date = new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(line.getOptionValue(boundaryFlag))
        date.getTime
      } catch {
        case ex: Throwable =>
          throw new IllegalArgumentException("Could not parse date format for end time.")
      }
    } else if (line.hasOption(hourOffsetFlag)) {

      val hourOffsetOpt = nullableToTimeOpt(line.getOptionValue(hourOffsetFlag))
      val hourOffset = hourOffsetOpt.getOrElse(throw new IllegalArgumentException(""))
      System.currentTimeMillis() - (hourOffset * millisecondsInHour)

    } else if (line.hasOption(dayOffsetFlag)) {

      val daysOffsetOpt = nullableToTimeOpt(line.getOptionValue(dayOffsetFlag))
      val dayOffset = daysOffsetOpt.getOrElse(throw new IllegalArgumentException(""))
      System.currentTimeMillis() - (dayOffset * millisecondsInDay)

    } else {
      throw new IllegalArgumentException("Must specify a time boundary for deletion.")
    }
  }
}

object MeasurementTrim extends Logging {
  import TrimUtilities._

  def main(args: Array[String]): Unit = {
    try {
      run(args, "trim-measurements", trimMeasurements)
    } catch {
      case ex: IllegalArgumentException =>
        println("Error: " + ex.getMessage)
        System.exit(1)
    }
  }

  def trimMeasurements(conn: DbConnection, horizonMs: Long): Unit = {
    import org.squeryl.PrimitiveTypeMode._
    conn.transaction {
      MeasurementStoreSchema.historicalValues.deleteWhere { hrow =>
        hrow.time lt horizonMs
      }
    }
  }
}

object EventTrim extends Logging {
  import TrimUtilities._

  def main(args: Array[String]): Unit = {
    try {
      run(args, "trim-events", trimEvents)
    } catch {
      case ex: IllegalArgumentException =>
        println("Error: " + ex.getMessage)
        System.exit(1)
    }
  }

  def trimEvents(conn: DbConnection, horizonMs: Long): Unit = {
    import org.squeryl.PrimitiveTypeMode._
    conn.transaction {
      ServicesSchema.alarms.deleteWhere { r =>
        r.eventId in from(ServicesSchema.events)(ev =>
          where(ev.time lt horizonMs)
            select (ev.id))
      }
      ServicesSchema.events.deleteWhere { r =>
        r.time lt horizonMs
      }
    }
  }
}
