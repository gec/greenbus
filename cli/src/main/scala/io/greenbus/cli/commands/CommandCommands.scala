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

import io.greenbus.msg.Session
import io.greenbus.cli.view.CommandLockView
import io.greenbus.cli.{ CliContext, Command }
import io.greenbus.client.service.proto.AuthRequests.AgentKeySet
import io.greenbus.client.service.proto.CommandRequests.{ CommandBlock, CommandLockQuery, CommandSelect }
import io.greenbus.client.service.proto.Commands.{ CommandLock, CommandRequest }
import io.greenbus.client.service.proto.Model.ModelID
import io.greenbus.client.service.proto.ModelRequests.EntityKeySet
import io.greenbus.client.service.{ AuthService, CommandService, ModelService }

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

object CommandIssueCommand {

  def interpretValue(b: CommandRequest.Builder, s: String) {

    def asOpt[A](f: => A): Option[A] = {
      try { Some(f) } catch { case _: NumberFormatException => None }
    }

    val parsed = asOpt(s.toInt) orElse
      asOpt(s.toDouble) getOrElse (s)

    parsed match {
      case v: Int =>
        b.setIntVal(v); b.setType(CommandRequest.ValType.INT)
      case v: Double =>
        b.setDoubleVal(v); b.setType(CommandRequest.ValType.DOUBLE)
      case v: String => b.setStringVal(v); b.setType(CommandRequest.ValType.STRING)
    }
  }
}

class CommandIssueCommand extends Command[CliContext] {

  val commandName = "command:issue"
  val description = "Select commands for execution"

  val commandToIssue = strings.arg("command name", "Command name")
  val commandValue = strings.argOptional("command value", "Command value")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)
    val commandClient = CommandService.client(context.session)

    if (commandToIssue.value.isEmpty) {
      throw new IllegalArgumentException("Must include command to issue")
    }

    val cmdFut = modelClient.getCommands(EntityKeySet.newBuilder().addNames(commandToIssue.value).build())

    val cmdResults = Await.result(cmdFut, 5000.milliseconds)

    if (cmdResults.isEmpty) {
      throw new IllegalArgumentException("Commands not found")
    }

    val cmdUuid = cmdResults.head.getUuid

    val b = CommandRequest.newBuilder()
      .setCommandUuid(cmdUuid)

    commandValue.value.foreach(CommandIssueCommand.interpretValue(b, _))

    val cmdReq = b.build()

    // Can fail because the front end isn't there, server will send ServiceException with timeout
    val result = Await.result(commandClient.issueCommandRequest(cmdReq), 10000.milliseconds)

    val errorStr = if (result.hasErrorMessage) " - " + result.getErrorMessage else ""

    println(result.getStatus.toString + errorStr)
  }
}

object LocksCommands {

  def lockToRow(session: Session, cmdLocks: Seq[CommandLock]): Future[Seq[(CommandLock, Seq[String], String)]] = {
    val modelClient = ModelService.client(session)

    if (cmdLocks.isEmpty) {
      Future.successful(Nil)
    } else {

      val agentUuids = cmdLocks.map(_.getAgentUuid).distinct
      val cmdUuids = cmdLocks.flatMap(_.getCommandUuidsList).distinct

      val keys = EntityKeySet.newBuilder().addAllUuids(agentUuids ++ cmdUuids).build()

      modelClient.get(keys).map { ents =>
        val uuidToName = ents.map(e => (e.getUuid, e.getName)).toMap

        cmdLocks.map { lock =>
          val cmdNames = lock.getCommandUuidsList.toList.flatMap(uuidToName.get)
          val agentName = uuidToName.get(lock.getAgentUuid).getOrElse("unknown")
          (lock, cmdNames, agentName)
        }
      }
    }
  }
}

class LockListCommand extends Command[CliContext] {

  val commandName = "lock:list"
  val description = "List command locks"

  val commandNames = strings.optionRepeated(Some("c"), Some("command"), "Command names")

  val agentNames = strings.optionRepeated(Some("a"), Some("agent"), "Agent names")

  val isSelect = optionSwitch(Some("s"), Some("select"), "List command selects only")

  val isBlock = optionSwitch(Some("b"), Some("block"), "List command blocks only")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)
    val commandClient = CommandService.client(context.session)

    val agentUuids = if (agentNames.value.nonEmpty) {
      val authClient = AuthService.client(context.session)

      val keys = AgentKeySet.newBuilder().addAllNames(agentNames.value).build()
      val agents = Await.result(authClient.getAgents(keys), 5000.milliseconds)

      if (agents.isEmpty) {
        throw new IllegalArgumentException("Agents specified not found")
      }

      agents.map(_.getUuid)
    } else {
      Nil
    }

    val commandUuids = if (commandNames.value.nonEmpty) {
      val modelClient = ModelService.client(context.session)

      val keys = EntityKeySet.newBuilder().addAllNames(commandNames.value).build()
      val commands = Await.result(modelClient.getCommands(keys), 5000.milliseconds)

      if (commands.isEmpty) {
        throw new IllegalArgumentException("Commands specified not found")
      }

      commands.map(_.getUuid)
    } else {
      Nil
    }

    if (isSelect.value && isBlock.value) {
      throw new IllegalArgumentException("Can choose only one of select or block")
    }

    def getResults(last: Option[ModelID], pageSize: Int) = {
      val b = CommandLockQuery.newBuilder()

      if (isSelect.value) b.setAccess(CommandLock.AccessMode.ALLOWED)
      if (isBlock.value) b.setAccess(CommandLock.AccessMode.BLOCKED)

      if (agentUuids.nonEmpty) b.addAllAgentUuids(agentUuids)
      if (commandUuids.nonEmpty) b.addAllCommandUuids(commandUuids)

      last.foreach(b.setLastId)
      b.setPageSize(pageSize)

      val lockFut = commandClient.commandLockQuery(b.build())

      lockFut.flatMap { cmdLocks => LocksCommands.lockToRow(context.session, cmdLocks) }
    }

    def pageBoundary(results: Seq[(CommandLock, Seq[String], String)]) = {
      results.last._1.getId
    }

    Paging.page(context.reader, getResults, pageBoundary, CommandLockView.printTable, CommandLockView.printRows)
  }
}

class LockSelectCommand extends Command[CliContext] {

  val commandName = "lock:select"
  val description = "Select commands for execution"

  val duration = ints.option(Some("t"), Some("expiry"), "Timeout duration in milliseconds. Default 30000.")

  val commandNames = strings.argRepeated("command name", "Command names")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)
    val commandClient = CommandService.client(context.session)

    val durationMs = duration.value.getOrElse(30000)

    if (commandNames.value.isEmpty) {
      throw new IllegalArgumentException("Must include at least one command")
    }

    val cmdNames = commandNames.value
    val cmdFut = modelClient.getCommands(EntityKeySet.newBuilder().addAllNames(cmdNames).build())

    val cmdResults = Await.result(cmdFut, 5000.milliseconds)

    if (cmdResults.isEmpty) {
      throw new IllegalArgumentException("Commands not found")
    }

    val select = CommandSelect.newBuilder()
      .addAllCommandUuids(cmdResults.map(_.getUuid))
      .setExpireDuration(durationMs)
      .build()

    val lock = Await.result(commandClient.selectCommands(select), 5000.milliseconds)

    CommandLockView.printInspect(lock, cmdResults.map(_.getName), context.agent)

  }
}

class LockBlockCommand extends Command[CliContext] {

  val commandName = "lock:block"
  val description = "Block commands from being execution"

  val commandNames = strings.argRepeated("command name", "Command names")

  protected def execute(context: CliContext) {
    val modelClient = ModelService.client(context.session)
    val commandClient = CommandService.client(context.session)

    if (commandNames.value.isEmpty) {
      throw new IllegalArgumentException("Must include at least one command")
    }

    val cmdNames = commandNames.value
    val cmdFut = modelClient.getCommands(EntityKeySet.newBuilder().addAllNames(cmdNames).build())

    val cmdResults = Await.result(cmdFut, 5000.milliseconds)

    if (cmdResults.isEmpty) {
      throw new IllegalArgumentException("Commands not found")
    }

    val block = CommandBlock.newBuilder()
      .addAllCommandUuids(cmdResults.map(_.getUuid))
      .build()

    val lock = Await.result(commandClient.blockCommands(block), 5000.milliseconds)

    CommandLockView.printInspect(lock, cmdResults.map(_.getName), context.agent)
  }
}

class LockDeleteCommand extends Command[CliContext] {

  val commandName = "lock:delete"
  val description = "Delete command locks"

  val commandIds = strings.argRepeated("command name", "Command names")

  protected def execute(context: CliContext) {
    val commandClient = CommandService.client(context.session)

    if (commandIds.value.isEmpty) {
      throw new IllegalArgumentException("Must include command IDs to delete")
    }

    val ids = commandIds.value.map(id => ModelID.newBuilder().setValue(id).build())

    val rowFut = commandClient.deleteCommandLocks(ids).flatMap(LocksCommands.lockToRow(context.session, _))

    val rows = Await.result(rowFut, 5000.milliseconds)

    CommandLockView.printTable(rows.toList)
  }
}
