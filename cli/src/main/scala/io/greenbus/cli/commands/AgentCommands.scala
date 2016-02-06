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

import io.greenbus.cli.view._
import io.greenbus.cli.{ CliContext, Command }
import io.greenbus.client.service.AuthService
import io.greenbus.client.service.proto.Auth.{ Agent, PermissionSet }
import io.greenbus.client.service.proto.AuthRequests._
import io.greenbus.client.service.proto.Model.ModelID
import io.greenbus.client.service.proto.ModelRequests.EntityPagingParams

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

class AgentListCommand extends Command[CliContext] {

  val commandName = "agent:list"
  val description = "List all agents"

  protected def execute(context: CliContext) {
    val authClient = AuthService.client(context.session)

    def results(last: Option[String], pageSize: Int) = {

      val pageB = EntityPagingParams.newBuilder().setPageSize(pageSize)
      last.foreach(pageB.setLastName)

      authClient.agentQuery(AgentQuery.newBuilder().setPagingParams(pageB.build()).build())
    }

    def pageBoundary(results: Seq[Agent]): String = results.last.getName

    Paging.page(context.reader, results, pageBoundary, AgentView.printTable, AgentView.printRows)
  }
}

object AgentCommon {

  def handleAgentResult(authClient: AuthService, results: Seq[Agent], otherwise: Option[String]) = {

    results.headOption match {
      case Some(agent) =>
        val permNames = agent.getPermissionSetsList.toSeq

        val permissionSets = if (permNames.nonEmpty) {
          val permKeySet = PermissionSetKeySet.newBuilder().addAllNames(permNames).build()
          Await.result(authClient.getPermissionSets(permKeySet), 5000.milliseconds)
        } else {
          Seq()
        }

        AgentView.viewAgent(agent, permissionSets)

      case None =>
        otherwise.foreach(println)
    }
  }
}

class AgentCreateCommand extends Command[CliContext] {

  val commandName = "agent:create"
  val description = "Create an agent"

  val name = strings.arg("user name", "Agent name to create.")

  val passOpt = strings.option(None, Some("password"), "Supply password instead of prompting, useful for scripting. WARNING: Password will be visible in command history.")

  val permissions = strings.optionRepeated(Some("p"), Some("permission-set"), "Attach permission set to agent")

  protected def execute(context: CliContext) {
    val authClient = AuthService.client(context.session)

    val password = passOpt.value.getOrElse {

      def prompt(retries: Int): String = {
        if (retries == 0) {
          throw new IllegalArgumentException("Failed to provide a password")
        } else {

          println("Enter password: ")
          val first = readLine()
          println("Re-enter password: ")
          val second = readLine()
          if (first == second) {
            first
          } else {
            println("Passwords did not match.\n")
            prompt(retries - 1)
          }
        }
      }

      prompt(3)
    }

    println()
    val template = AgentTemplate.newBuilder()
      .setName(name.value)
      .setPassword(password)
      .addAllPermissionSets(permissions.value)
      .build()

    val agentResult = Await.result(authClient.putAgents(Seq(template)), 10000.milliseconds)

    AgentCommon.handleAgentResult(authClient, agentResult, None)
  }
}

class AgentModifyCommand extends Command[CliContext] {

  val commandName = "agent:modify"
  val description = "Modify an agent's permission sets"

  val name = strings.arg("user name", "Agent name to modify.")

  val addPerms = strings.optionRepeated(Some("a"), Some("add"), "Attach permission set to agent")
  val remPerms = strings.optionRepeated(Some("r"), Some("remove"), "Attach permission set to agent")

  protected def execute(context: CliContext) {
    val authClient = AuthService.client(context.session)

    val agentGetResult = Await.result(authClient.getAgents(AgentKeySet.newBuilder().addNames(name.value).build()), 5000.milliseconds)

    agentGetResult.headOption match {
      case None => println("Agent not found.")
      case Some(agent) =>
        val currentPermSet = agent.getPermissionSetsList.toSet
        val withoutRemoves = currentPermSet diff remPerms.value.toSet
        val withAdds = withoutRemoves ++ addPerms.value

        val template = AgentTemplate.newBuilder()
          .setUuid(agent.getUuid)
          .setName(name.value)
          .addAllPermissionSets(withAdds)
          .build()

        val putResult = Await.result(authClient.putAgents(Seq(template)), 10000.milliseconds)
        AgentCommon.handleAgentResult(authClient, putResult, None)
    }
  }
}

class AgentPasswordCommand extends Command[CliContext] {

  val commandName = "agent:password"
  val description = "Modify an agent's password"

  val name = strings.arg("user name", "Agent name to modify.")

  val passOpt = strings.option(None, Some("password"), "Supply password instead of prompting, useful for scripting. WARNING: Password will be visible in command history.")

  protected def execute(context: CliContext) {
    val authClient = AuthService.client(context.session)

    val password = passOpt.value.getOrElse {

      def prompt(retries: Int): String = {
        if (retries == 0) {
          throw new IllegalArgumentException("Failed to provide a password")
        } else {

          println("Enter password: ")
          val first = readLine()
          println("Re-enter password: ")
          val second = readLine()
          if (first == second) {
            first
          } else {
            println("Passwords did not match.\n")
            prompt(retries - 1)
          }
        }
      }

      prompt(3)
    }

    val agentGetResult = Await.result(authClient.getAgents(AgentKeySet.newBuilder().addNames(name.value).build()), 5000.milliseconds)

    agentGetResult.headOption match {
      case None => println("Agent not found.")
      case Some(agent) =>
        val update = AgentPasswordUpdate.newBuilder()
          .setUuid(agent.getUuid)
          .setPassword(password)
          .build()

        val putResult = Await.result(authClient.putAgentPasswords(Seq(update)), 10000.milliseconds)
        AgentCommon.handleAgentResult(authClient, putResult, None)
    }
  }
}

class AgentViewCommand extends Command[CliContext] {

  val commandName = "agent:view"
  val description = "View details of agent"

  val setName = strings.arg("name", "Agent name")

  protected def execute(context: CliContext) {
    val authClient = AuthService.client(context.session)

    val agentResult = Await.result(authClient.getAgents(AgentKeySet.newBuilder().addNames(setName.value).build()), 5000.milliseconds)
    AgentCommon.handleAgentResult(authClient, agentResult, Some("Agent not found."))
  }
}

class AgentPermissionsListCommand extends Command[CliContext] {

  val commandName = "agent-permissions:list"
  val description = "List all permission sets"

  protected def execute(context: CliContext) {
    val authClient = AuthService.client(context.session)

    def results(last: Option[ModelID], pageSize: Int) = {
      val queryBuilder = PermissionSetQuery.newBuilder().setPageSize(pageSize)
      last.foreach(queryBuilder.setLastId)
      val query = queryBuilder.build()

      authClient.permissionSetQuery(query)
    }

    def pageBoundary(results: Seq[PermissionSet]) = results.last.getId

    Paging.page(context.reader, results, pageBoundary, PermissionSetView.printTable, PermissionSetView.printRows)
  }
}

class AgentPermissionsViewCommand extends Command[CliContext] {

  val commandName = "agent-permissions:view"
  val description = "View permissions in a permission set"

  val setName = strings.arg("name", "Permission set name")

  protected def execute(context: CliContext) {
    val authClient = AuthService.client(context.session)

    val result = Await.result(authClient.getPermissionSets(PermissionSetKeySet.newBuilder().addNames(setName.value).build()), 5000.milliseconds)

    result.headOption match {
      case None => println("Permission set not found")
      case Some(set) =>
        PermissionSetView.viewPermissionSet(set)
    }

  }
}
