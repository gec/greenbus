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
package io.greenbus.services.model

import java.util.UUID

import io.greenbus.client.service.proto.Auth.{ Agent, EntitySelector, Permission, PermissionSet }
import io.greenbus.services.framework.NullModelNotifier
import io.greenbus.services.model.AuthModel.{ AgentInfo, CoreUntypedTemplate, PermissionSetInfo }

trait AuthSeeder {

  def addPermissionSets(descs: Seq[(String, Seq[Permission])]): Seq[PermissionSet]

  def addUsers(descs: Seq[(String, String, Seq[String])]): Seq[Agent]
}

object ModelAuthSeeder extends AuthSeeder {

  def addPermissionSets(descs: Seq[(String, Seq[Permission])]): Seq[PermissionSet] = {
    val templates = descs.map {
      case (name, perms) =>
        CoreUntypedTemplate(Option.empty[Long], name, PermissionSetInfo(perms))
    }

    SquerylAuthModel.putPermissionSets(NullModelNotifier, templates)
  }

  def addUsers(descs: Seq[(String, String, Seq[String])]): Seq[Agent] = {
    val templates = descs.map {
      case (name, pass, perms) => CoreUntypedTemplate(Option.empty[UUID], name, AgentInfo(Some(pass), perms))
    }

    SquerylAuthModel.putAgents(NullModelNotifier, templates)
  }
}

object AuthSeedData {

  private def makeSelector(style: String, arguments: List[String] = Nil) = {
    val b = EntitySelector.newBuilder.setStyle(style)
    arguments.foreach(b.addArguments)
    b.build
  }

  private def makePermission(allow: Boolean, verbs: List[String] = List("*"), resources: List[String] = List("*"), selectors: List[EntitySelector] = Nil) = {
    val b = Permission.newBuilder.setAllow(allow)
    verbs.foreach(b.addActions)
    resources.foreach(b.addResources)
    if (selectors.isEmpty) b.addSelectors(makeSelector("*"))
    else selectors.foreach(b.addSelectors)
    b.build
  }

  def seed(seeder: AuthSeeder, systemPassword: String) {
    val all = makePermission(true)
    val readOnly = makePermission(true, List("read"))

    val selfSelector = makeSelector("self")

    val readAndDeleteOwnTokens = makePermission(true, List("read", "delete"), List("auth_token"), List(selfSelector))
    val denyAuthTokens = makePermission(false, List("read", "delete"), List("auth_token"))

    var permSetDescs = List.empty[(String, Seq[Permission])]

    permSetDescs ::= ("read_only", List(readAndDeleteOwnTokens, denyAuthTokens, readOnly))
    permSetDescs ::= ("all", List(all))
    seeder.addPermissionSets(permSetDescs)

    val standardRoles = List("user_role", "system_viewer")

    val agentDescs = List(
      ("system", systemPassword, List("all")),
      ("guest", systemPassword, List("read_only")))

    seeder.addUsers(agentDescs)

  }
}
