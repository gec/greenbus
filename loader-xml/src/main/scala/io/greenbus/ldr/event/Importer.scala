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
package io.greenbus.ldr.event

import java.io.File

import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.ldr.xml.events.Events
import org.apache.commons.cli
import org.apache.commons.cli.{ HelpFormatter, Options }
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.client.ServiceConnection
import io.greenbus.loader.set.LoadingException
import io.greenbus.util.{ UserSettings, XmlHelper }

import scala.concurrent.duration._

object Importer extends Logging {

  def buildOptions: Options = {
    val opts = new Options
    opts.addOption("h", "help", false, "Display this help text")
    opts
  }

  def main(args: Array[String]): Unit = {
    try {
      run(args)
    } catch {
      case ex: LoadingException =>
        System.err.println(ex.getMessage)
      case ex: io.greenbus.msg.amqp.util.LoadingException =>
        System.err.println(ex.getMessage)
      case ex: java.util.concurrent.TimeoutException =>
        System.err.println("Service request timed out")
      case ex: Throwable =>
        logger.error("Unhandled exception: " + ex)
        System.err.println("Error: " + ex.getMessage)
    }
  }

  def run(args: Array[String]): Unit = {

    val options = buildOptions
    val parser = new cli.BasicParser
    val line = parser.parse(options, args)

    def printHelp() {
      (new HelpFormatter).printHelp("loader file", options)
    }

    if (line.hasOption("h")) {
      printHelp()
    } else {

      if (line.getArgs.length < 1) {
        System.err.println("Must include file to import.")
        System.exit(1)
      }
      val filename = line.getArgs.head

      val file = new File(filename)
      val parentDir = file.getParentFile

      val back = XmlHelper.read(file, classOf[Events])

      val model = EventConversion.toModel(back)

      importEventConfig(model)
    }
  }

  def importEventConfig(all: Seq[EventMdl.EventConfig]) = {

    val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")
    val amqpConfigPath = Option(System.getProperty("io.greenbus.config.amqp")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.msg.amqp.cfg")
    val userConfigPath = Option(System.getProperty("io.greenbus.config.user")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.user.cfg")

    val config = AmqpSettings.load(amqpConfigPath)
    val userConfig = UserSettings.load(userConfigPath)

    val conn = try {
      ServiceConnection.connect(config, QpidBroker, 10000)
    } catch {
      case ex: java.io.IOException => throw new LoadingException(ex.getMessage)
    }

    try {
      val session = conn.login(userConfig.user, userConfig.password, Duration(5000, MILLISECONDS))

      Upload.upload(session, all)

    } finally {
      conn.disconnect()
    }
  }
}
