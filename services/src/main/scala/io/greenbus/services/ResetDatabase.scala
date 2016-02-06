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

import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.sql.{ DbConnector, SqlSettings, DbConnection }
import io.greenbus.services.model.{ EventSeeding, ModelAuthSeeder, AuthSeedData }
import io.greenbus.services.data.{ ProcessingLockSchema, ServicesSchema }
import io.greenbus.mstore.sql.MeasurementStoreSchema
import org.apache.commons.cli
import org.apache.commons.cli.{ HelpFormatter, Options }

object ResetDatabase extends Logging {

  val forceLongFlag = "force"
  val forceFlag = "f"

  def buildOptions: Options = {
    val opts = new Options
    opts.addOption("h", "help", false, "Display this help text")
    opts.addOption(new cli.Option(forceFlag, forceLongFlag, false, "Skip confirmation prompt"))
    opts
  }

  def reset(sqlConfigPath: String) {
    val sqlSettings = SqlSettings.load(sqlConfigPath)
    val conn = DbConnector.connect(sqlSettings)
    reset(conn)
  }

  def reset(conn: DbConnection) {
    conn.transaction {
      ServicesSchema.reset()
      MeasurementStoreSchema.drop
      MeasurementStoreSchema.create

      ProcessingLockSchema.reset()

      AuthSeedData.seed(ModelAuthSeeder, "system")
      EventSeeding.seed()
    }
  }

  def main(args: Array[String]) {

    val options = buildOptions
    val parser = new cli.BasicParser
    val line = parser.parse(options, args)

    def printHelp() {
      (new HelpFormatter).printHelp("", options)
    }

    if (line.hasOption("h")) {
      printHelp()
    } else {

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
        reset(sqlConfigPath)
      } catch {
        case ex: Throwable =>
          logger.error("Failure: " + ex)
          println("Error: " + ex.getMessage)
      }
    }
  }
}
