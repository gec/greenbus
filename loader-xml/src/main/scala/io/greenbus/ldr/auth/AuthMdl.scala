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

import java.util.UUID

object AuthMdl {

  case class EntitySelector(style: String, args: Seq[String])

  case class Permission(allow: Boolean, resources: Seq[String], actions: Seq[String], selectors: Seq[EntitySelector])

  case class PermissionSet(idOpt: Option[Long], name: String, permissions: Seq[Permission])

  case class Agent(uuidOpt: Option[UUID], name: String, permissions: Seq[String], passwordOpt: Option[String] = None)

  case class AuthSet(permSets: Seq[PermissionSet], agents: Seq[Agent])

  def getIdSetForAgents(agents: Seq[Agent]): (Seq[UUID], Seq[String]) = {
    val uuids = Vector.newBuilder[UUID]
    val names = Vector.newBuilder[String]

    agents.foreach { agent =>
      agent.uuidOpt match {
        case Some(uuid) => uuids += uuid
        case None => names += agent.name
      }
    }
    (uuids.result(), names.result())
  }
}
