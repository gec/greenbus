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

import io.greenbus.client.service.proto.Commands.CommandLock
import java.util.Date

object CommandLockView extends TableView[(CommandLock, Seq[String], String)] {

  def header: List[String] = "Id" :: "Mode" :: "User" :: "Commands" :: "Expire Time" :: Nil

  def row(obj: (CommandLock, Seq[String], String)): List[String] = {
    val (lock, cmds, agent) = obj

    lock.getId.getValue ::
      lock.getAccess.toString ::
      agent ::
      cmds.mkString(", ") ::
      lockTimeString(lock) ::
      Nil
  }

  def printInspect(item: (CommandLock, Seq[String], String)) = {
    val (lock, cmds, agent) = item

    val rows: List[List[String]] = ("ID:" :: lock.getId.getValue :: Nil) ::
      ("Mode:" :: lock.getAccess.toString :: Nil) ::
      ("User:" :: agent :: Nil) ::
      ("Expires:" :: lockTimeString(lock) :: Nil) ::
      ("Commands:" :: cmds.head :: Nil) :: Nil

    val cmdRows = cmds.tail.map(cmd => "" :: cmd :: Nil)

    Table.renderRows(rows ::: cmdRows.toList, " ")
  }

  private def lockTimeString(lock: CommandLock): String = {
    if (lock.hasExpireTime) new Date(lock.getExpireTime).toString else ""
  }
}
