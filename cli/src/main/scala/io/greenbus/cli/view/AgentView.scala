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
package io.greenbus.cli.view

import io.greenbus.client.service.proto.Auth.{ Permission, EntitySelector, PermissionSet, Agent }
import scala.collection.JavaConversions._

object AgentView extends TableView[Agent] {
  def header: List[String] = "Name" :: "Permission Sets" :: Nil

  def row(obj: Agent): List[String] = {
    obj.getName ::
      obj.getPermissionSetsList.mkString(", ") ::
      Nil
  }

  def viewAgent(agent: Agent, perms: Seq[PermissionSet]) = {
    val agentRows = Seq(
      Seq("UUID: ", agent.getUuid.getValue),
      Seq("Name: ", agent.getName))

    Table.renderRows(agentRows)
    println()

    val permRows = perms.flatMap(p => p.getPermissionsList.map(PermissionSetView.permissionRowWithSet(_, p.getName)))
    Table.printTable(PermissionSetView.permissionHeaderWithSet, permRows)
  }
}

object PermissionSetView extends TableView[PermissionSet] {
  def header: List[String] = "Name" :: "Allows" :: "Denies" :: Nil

  def row(obj: PermissionSet): List[String] = {
    val (allows, denies) = obj.getPermissionsList.toSeq.partition(_.getAllow)

    obj.getName ::
      allows.size.toString ::
      denies.size.toString ::
      Nil
  }

  def viewPermissionSet(a: PermissionSet) = {

    val permissions = a.getPermissionsList.toList

    Table.printTable(permissionHeader, permissions.map(permissionRow))
  }

  def permissionHeader = {
    "Allow" :: "Actions" :: "Resources" :: "Selectors" :: Nil
  }

  def permissionRow(a: Permission): List[String] = {
    a.getAllow.toString ::
      a.getActionsList.toList.mkString(",") ::
      a.getResourcesList.toList.mkString(",") ::
      a.getSelectorsList.toList.map(selectorString).mkString(",") ::
      Nil
  }

  def permissionHeaderWithSet = {
    "Allow" :: "Actions" :: "Resources" :: "Selectors" :: "Permission set" :: Nil
  }

  def permissionRowWithSet(a: Permission, setName: String): List[String] = {
    a.getAllow.toString ::
      a.getActionsList.toList.mkString(",") ::
      a.getResourcesList.toList.mkString(",") ::
      a.getSelectorsList.toList.map(selectorString).mkString(",") ::
      setName ::
      Nil
  }

  def selectorString(a: EntitySelector): String = {
    val args = a.getArgumentsList.toList
    val argString = if (args.isEmpty) ""
    else args.mkString("(", ",", ")")
    a.getStyle + argString
  }

}

