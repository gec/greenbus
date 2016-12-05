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
package io.greenbus.cli

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.amqp.util.LoadingException
import jline.console.ConsoleReader
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.client.{ ServiceHeaders, ServiceConnection }
import scala.concurrent.duration._
import scala.concurrent.Await
import io.greenbus.cli.commands._
import jline.console.completer.{ NullCompleter, StringsCompleter, ArgumentCompleter }
import scala.collection.JavaConversions._
import io.greenbus.util.{ ConfigurationException, UserSettings }
import java.util.Properties
import org.fusesource.jansi.AnsiConsole
import io.greenbus.client.exception.ServiceException
import io.greenbus.client.version.Version
import java.util.concurrent.TimeoutException
import java.io.IOException
import io.greenbus.msg.SessionUnusableException

object CliMain extends LazyLogging {

  val commandList = List(
    () => new LoginCommand,
    () => new LogoutCommand,
    () => new EntityViewCommand,
    () => new EntityListCommand,
    () => new EntityTypeCommand,
    () => new EntityChildrenCommand,
    () => new KvListCommand,
    () => new KvViewCommand,
    () => new KvSetCommand,
    () => new KvDeleteCommand,
    () => new PointListCommand,
    () => new CommandListCommand,
    () => new CommandIssueCommand,
    () => new LockListCommand,
    () => new LockSelectCommand,
    () => new LockBlockCommand,
    () => new LockDeleteCommand,
    () => new EndpointListCommand,
    () => new EndpointSubscribeCommand,
    () => new EndpointEnableCommand,
    () => new EndpointDisableCommand,
    () => new AgentListCommand,
    () => new AgentViewCommand,
    () => new AgentCreateCommand,
    () => new AgentModifyCommand,
    () => new AgentPasswordCommand,
    () => new AgentPermissionsListCommand,
    () => new AgentPermissionsViewCommand,
    () => new MeasListCommand,
    () => new MeasHistoryCommand,
    () => new MeasDownloadCommand,
    () => new MeasSubscribeCommand,
    () => new MeasBlockCommand,
    () => new MeasUnblockCommand,
    () => new MeasReplaceCommand,
    () => new EventConfigListCommmand,
    () => new EventConfigViewCommmand,
    () => new EventConfigPutCommmand,
    () => new EventConfigDeleteCommmand,
    () => new EventPostCommmand,
    () => new EventListCommmand,
    () => new EventSubscribeCommmand,
    () => new EventViewCommmand,
    () => new AlarmListCommand,
    () => new AlarmSubscribeCommmand,
    () => new AlarmSilenceCommand,
    () => new AlarmAckCommand,
    () => new AlarmRemoveCommand,
    () => new CalculationListCommand)

  val map: Map[String, () => Command[_ >: ManagementCliContext <: CliContext]] = commandList.map { fac =>
    (fac().commandName, fac)
  }.toMap

  def main(args: Array[String]): Unit = {
    try {
      if (args.length > 0) {
        run(execute(args, _, _, _))
      } else {
        run(runCli)
      }
    } catch {
      case ex: LoadingException =>
        System.err.println(ex.getMessage)
      case ex: IllegalStateException =>
        System.err.println(ex.getMessage)
      case ex: Throwable =>
        logger.error("Unhandled exception: " + ex)
        System.err.println("Error: " + ex.getMessage)
    }
  }

  def run(fun: (ServiceConnection, String, Option[UserSettings]) => Unit) {
    if (System.getProperty("jline.terminal") == null) {
      System.setProperty("jline.terminal", "jline.UnsupportedTerminal")
    }

    val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")
    val amqpConfigPath = Option(System.getProperty("io.greenbus.config.amqp")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.msg.amqp.cfg")
    val userConfigPath = Option(System.getProperty("io.greenbus.config.user")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.user.cfg")

    val settings = AmqpSettings.load(amqpConfigPath)

    val userSettingsOpt = try {
      Some(UserSettings.load(userConfigPath))
    } catch {
      case ex: ConfigurationException => None
    }

    val conn = try {
      ServiceConnection.connect(settings, QpidBroker, 10000)
    } catch {
      case ex: Throwable =>
        logger.error("Broker connection error: " + ex)
        throw new IllegalStateException(ex.getMessage)
    }

    try {
      fun(conn, settings.host, userSettingsOpt)

    } finally {
      conn.disconnect()
    }

  }

  def execute(args: Array[String], conn: ServiceConnection, host: String, userOpt: Option[UserSettings]): Unit = {
    val reader = new ConsoleReader(null, System.in, AnsiConsole.out(), null, "UTF-8")
    var context = CliContext(reader, conn)

    val userSettings = userOpt.getOrElse(throw new IllegalStateException("Must provide user settings to run single commands."))

    try {
      val session = Await.result(conn.login(userSettings.user, userSettings.password), 5000.milliseconds)
      conn.session
      context.setSession(Some(session))
      context.setAgent(Some(userSettings.user))
      context.setToken(session.headers.get(ServiceHeaders.tokenHeader()))
    } catch {
      case ex: Throwable =>
        throw new IllegalStateException("Login failed.")
    }

    try {
      val commandName = args(0)

      commandName match {
        case "version" =>
          println(Version.clientVersion)
        case "help" =>
          if (args.length == 1) {
            println("Usage: help <command>")
          } else {
            val helpCmdName = args(1)

            helpCmdName match {
              case "version" => println("Prints build version of client library.")
              case _ =>
                map.get(helpCmdName) match {
                  case None => println("Unknown command")
                  case Some(cmdFun) =>
                    val cmd = cmdFun()
                    cmd.printHelpString()
                }
            }
          }
        case _ =>
          map.get(commandName) match {
            case None => println("Unknown command")
            case Some(cmdFun) =>
              val cmd = cmdFun()
              println("")
              cmd.run(args.drop(1), context)
          }
      }
      println("")
    } catch {
      case sue: SessionUnusableException =>
        println("Session failure: " + sue.getMessage)
        println("")

        // The broker killing sessions is a detail we hide from the user
        context.setSession(None)
        val session = conn.session
        context.tokenOption.foreach(session.addHeader(ServiceHeaders.tokenHeader(), _))
        context.setSession(Some(session))

      case ioe: IOException =>
        println("Communication failure: " + ioe.getMessage)
        println("")
      case rse: ServiceException =>
        println("Request failure: " + rse.getStatus + " - " + rse.getMessage)
        println("")
      case te: TimeoutException =>
        println("Request timeout")
        println("")
      case ex: IllegalArgumentException =>
        println(ex.getMessage)
        println("")
    }
  }

  def runCli(conn: ServiceConnection, host: String, userOpt: Option[UserSettings]) {

    val allCmds = (map.keys.toSeq ++ Seq("login", "logout", "version")).sorted

    val reader = new ConsoleReader(null, System.in, AnsiConsole.out(), null, "UTF-8")

    reader.addCompleter(new ArgumentCompleter(
      new StringsCompleter(allCmds ++ Seq("help")),
      new NullCompleter))

    reader.addCompleter(new ArgumentCompleter(
      new StringsCompleter(Seq("help")),
      new StringsCompleter(allCmds),
      new NullCompleter))

    loadTitle(reader)

    var keepGoing = true
    var context = CliContext(reader, conn)

    userOpt.foreach { userSettings =>
      reader.println(s"Attempting auto-login for user ${userSettings.user}.")

      try {
        val session = Await.result(conn.login(userSettings.user, userSettings.password), 5000.milliseconds)
        conn.session
        context.setSession(Some(session))
        context.setAgent(Some(userSettings.user))
        context.setToken(session.headers.get(ServiceHeaders.tokenHeader()))
      } catch {
        case ex: Throwable =>
          reader.println("Login failed.")
      }
      reader.println("")
    }

    while (keepGoing) {
      val agentPrompt = context.agentOption.getOrElse("")
      val prompt = s"\u001B[1m${agentPrompt}\u001B[0m@$host> "

      val lineRead = Option(reader.readLine(prompt)).map(_.trim)

      lineRead match {
        case None => keepGoing = false
        case Some("") =>
        case Some(line) =>
          try {

            val (cmdArr, rest) = SearchingTokenizer.tokenize(line).toArray.splitAt(1)
            val commandName = cmdArr(0)

            commandName match {
              case "version" =>
                println(Version.clientVersion)
              case "help" =>
                if (rest.length == 0) {
                  println("Usage: help <command>")
                } else {
                  val helpCmdName = rest(0)

                  helpCmdName match {
                    case "version" => println("Prints build version of client library.")
                    case _ =>
                      map.get(helpCmdName) match {
                        case None => println("Unknown command")
                        case Some(cmdFun) =>
                          val cmd = cmdFun()
                          cmd.printHelpString()
                      }
                  }
                }
              case _ =>
                map.get(commandName) match {
                  case None => println("Unknown command")
                  case Some(cmdFun) =>
                    val cmd = cmdFun()
                    println("")
                    cmd.run(rest, context)
                }
            }
            println("")
          } catch {
            case sue: SessionUnusableException =>
              println("Session failure: " + sue.getMessage)
              println("")

              // The broker killing sessions is a detail we hide from the user
              context.setSession(None)
              val session = conn.session
              context.tokenOption.foreach(session.addHeader(ServiceHeaders.tokenHeader(), _))
              context.setSession(Some(session))

            case ioe: IOException =>
              println("Communication failure: " + ioe.getMessage)
              println("")
            case rse: ServiceException =>
              println("Request failure: " + rse.getStatus + " - " + rse.getMessage)
              println("")
            case te: TimeoutException =>
              println("Request timeout")
              println("")
            case ex: IllegalArgumentException =>
              println(ex.getMessage)
              println("")
          }
      }
    }
  }

  def loadTitle(reader: ConsoleReader) {
    val stream = this.getClass.getClassLoader.getResourceAsStream("io.greenbus.cli.branding/branding.properties")
    val props = new Properties
    props.load(stream)
    Option(props.getProperty("welcome")) foreach { s => reader.println(s) }
  }

}
