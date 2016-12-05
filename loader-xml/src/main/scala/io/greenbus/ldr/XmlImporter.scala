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
package io.greenbus.ldr

import java.io.File
import javax.xml.bind.UnmarshalException
import javax.xml.stream.{ XMLStreamException, Location }

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.ldr.xml.Configuration
import org.apache.commons.cli
import org.apache.commons.cli.{ HelpFormatter, Options }
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.client.ServiceConnection
import io.greenbus.loader.set.Mdl.FlatModelFragment
import io.greenbus.loader.set._
import io.greenbus.util.{ UserSettings, XmlHelper }

import scala.concurrent.duration._

object XmlImporter extends LazyLogging {

  val rootFlag = "root"

  def buildOptions: Options = {
    val opts = new Options
    opts.addOption("h", "help", false, "Display this help text")
    opts.addOption(new cli.Option(null, rootFlag, true, "Root entity to import"))
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
      case ex: UnmarshalException =>
        logger.error("Unmarshal exception: " + ex)
        Option(ex.getCause) match {
          case Some(se: XMLStreamException) => System.err.println(se.getMessage)
          case _ => System.err.println("Unknown error parsing XML.")
        }
      case ex: Throwable =>
        logger.error("Unhandled exception: " + ex)
        System.err.println("Error: " + ex.getMessage)
    }
  }

  def run(args: Array[String]): Unit = {

    val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")
    val amqpConfigPath = Option(System.getProperty("io.greenbus.config.amqp")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.msg.amqp.cfg")
    val userConfigPath = Option(System.getProperty("io.greenbus.config.user")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.user.cfg")

    val config = AmqpSettings.load(amqpConfigPath)
    val userConfig = UserSettings.load(userConfigPath)

    val options = buildOptions
    val parser = new cli.BasicParser
    val line = parser.parse(options, args)

    def printHelp() {
      (new HelpFormatter).printHelp("loader file", options)
    }

    if (line.hasOption("h")) {
      printHelp()
    } else {
      val roots = Option(line.getOptionValues(rootFlag)).map(_.toSeq).getOrElse(Seq())

      if (line.getArgs.length < 1) {
        System.err.println("Must include file to import.")
        System.exit(1)
      }
      val filename = line.getArgs.head

      val optCommandRoots = if (roots.nonEmpty) Some(roots) else None

      val file = new File(filename)
      val parentDir = file.getParentFile

      val (xmlConfig, locationMap) = XmlHelper.readWithLocation(file, classOf[Configuration])

      try {
        importFromXml(config, userConfig, optCommandRoots, xmlConfig, parentDir, prompt = true)
      } catch {
        case ex: LoadingXmlException =>
          throw new LoadingException(ex.getMessage + " " + locationError(ex.element, locationMap))
      }
    }
  }

  def locationError(obj: Any, locations: Map[Any, Location]): String = {
    locations.get(obj) match {
      case None => "[line: ?, col: ?]"
      case Some(loc) => s"[line: ${loc.getLineNumber}, col: ${loc.getColumnNumber}]"
    }
  }

  def importFromXml(
    amqpConfig: AmqpSettings,
    userConfig: UserSettings,
    optCommandRoots: Option[Seq[String]],
    xmlConfig: Configuration,
    parentDir: File,
    prompt: Boolean = true) = {

    val treeRepresentation = XmlToModel.convert(xmlConfig)

    val xmlRootIds = treeRepresentation.roots.map(_.node.fields.id)

    val flatModel = Mdl.treeToFlat(treeRepresentation)

    val flatFilesResolved = KvFileStorage.resolveFileReferences(flatModel, parentDir)

    importFragment(amqpConfig, userConfig, optCommandRoots, xmlRootIds, flatFilesResolved, prompt)
  }

  def importFragment(
    amqpConfig: AmqpSettings,
    userConfig: UserSettings,
    optCommandRoots: Option[Seq[String]],
    xmlRoots: Seq[EntityId],
    flatModel: FlatModelFragment,
    prompt: Boolean = true) = {

    val conn = try {
      ServiceConnection.connect(amqpConfig, QpidBroker, 10000)
    } catch {
      case ex: java.io.IOException => throw new LoadingException(ex.getMessage)
    }

    try {

      val fragmentEndpointIds = flatModel.endpoints.map(_.fields.id)

      val session = conn.login(userConfig.user, userConfig.password, Duration(5000, MILLISECONDS))

      val downloadSet = optCommandRoots.map { roots =>
        Downloader.downloadByIdsAndNames(session, xmlRoots, roots, fragmentEndpointIds)
      }.getOrElse {
        Downloader.downloadByIds(session, xmlRoots, fragmentEndpointIds)
      }

      val ((actions, diff), idTuples) = Importer.importDiff(session, flatModel, downloadSet)

      Importer.summarize(diff)

      if (!diff.isEmpty) {
        if (prompt) {
          println("Proceed with modifications? (y/N)")
          val answer = readLine()
          if (Set("y", "yes").contains(answer.trim.toLowerCase)) {
            Upload.push(session, actions, idTuples)
          }
        } else {
          Upload.push(session, actions, idTuples)
        }
      }

    } finally {
      conn.disconnect()
    }
  }
}
