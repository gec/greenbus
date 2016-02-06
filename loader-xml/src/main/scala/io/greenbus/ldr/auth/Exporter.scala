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
package io.greenbus.ldr.auth

import java.io.File

import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.ldr.xml.auth.Authorization
import org.apache.commons.cli
import org.apache.commons.cli.{ HelpFormatter, Options }
import org.apache.commons.io.FileUtils
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.client.ServiceConnection
import io.greenbus.loader.set.LoadingException
import io.greenbus.util.{ UserSettings, XmlHelper }

import scala.concurrent.duration._

object Exporter extends Logging {

  val outputFlag = "output"
  val directoryFlag = "directory"

  def buildOptions: Options = {
    val opts = new Options
    opts.addOption("h", "help", false, "Display this help text")
    opts.addOption(new cli.Option("d", directoryFlag, true, "Directory to output files to"))
    opts.addOption(new cli.Option("o", outputFlag, true, "Output filename"))
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

      val dirOpt = Option(line.getOptionValue(directoryFlag))

      val outOpt = Option(line.getOptionValue(outputFlag))

      export(outOpt, dirOpt)
    }
  }

  def export(outputOpt: Option[String], dirOpt: Option[String]) = {

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

      val downloadSet = Download.download(session)

      val model = DownloadConversion.toIntermediate(downloadSet)

      val xml = AuthConversion.toXml(model)

      val text = XmlHelper.writeToString(xml, classOf[Authorization], formatted = true)

      dirOpt match {
        case Some(dirname) =>
          writeInDir(text, outputOpt, dirname)
        case None =>
          outputOpt match {
            case Some(filename) =>
              FileUtils.write(new File(filename), text)
            case None =>
              println(text)
          }
      }

    } finally {
      conn.disconnect()
    }
  }

  def writeInDir(main: String, outNameOpt: Option[String], dirname: String): Unit = {
    val dirFile = new File(dirname)
    if (!dirFile.exists()) {
      dirFile.mkdir()
    }

    val mainFilename = outNameOpt.getOrElse("authorization.xml")

    val mainFile = new File(dirFile, mainFilename)
    FileUtils.write(mainFile, main)
  }
}
