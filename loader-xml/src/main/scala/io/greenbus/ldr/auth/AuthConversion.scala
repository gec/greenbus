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

import io.greenbus.ldr.xml.auth._
import io.greenbus.ldr.XmlToModel
import io.greenbus.loader.set.LoadingException

import scala.collection.JavaConversions._

object AuthConversion {

  def toXml(authMdl: AuthMdl.AuthSet): Authorization = {

    val xmlSets = authMdl.permSets.map(toXml)

    val xmlAgents = authMdl.agents.map(toXml)

    val xml = new Authorization

    val agentColl = new Agents
    xmlAgents.foreach(agentColl.getAgent.add)

    val permsColl = new PermissionSets
    xmlSets.foreach(permsColl.getPermissionSet.add)

    xml.setAgents(agentColl)
    xml.setPermissionSets(permsColl)

    xml
  }

  def toXml(agent: AuthMdl.Agent): Agent = {
    val xml = new Agent

    agent.uuidOpt.foreach(u => xml.setUuid(u.toString))
    xml.setName(agent.name)

    val xmlPermRefs = agent.permissions.map { p =>
      val ps = new Agent.PermissionSet
      ps.setName(p)
      ps
    }

    xmlPermRefs.foreach(xml.getPermissionSet.add)

    xml
  }

  def toXml(permSet: AuthMdl.PermissionSet): PermissionSet = {

    val xml = new PermissionSet

    permSet.idOpt.foreach(id => xml.setId(id.toString))
    xml.setName(permSet.name)

    permSet.permissions.map(toXml).foreach(xml.getPermissions.add)

    xml
  }

  def toXml(perm: AuthMdl.Permission): PermissionType = {

    val xml = if (perm.allow) new Allow else new Deny

    val actions = perm.actions.map { a =>
      val obj = new Action
      obj.setName(a)
      obj
    }

    val resources = perm.resources.map { r =>
      val obj = new Resource
      obj.setName(r)
      obj
    }

    val selectors = perm.selectors.map { sel =>
      val xmlSelector = new Selector
      xmlSelector.setStyle(sel.style)

      val xmlArgs = sel.args.map { arg =>
        val a = new Argument
        a.setValue(arg)
        a
      }
      xmlArgs.foreach(xmlSelector.getArgument.add)

      xmlSelector
    }

    actions.foreach(xml.getElements.add)
    resources.foreach(xml.getElements.add)
    selectors.foreach(xml.getElements.add)

    xml
  }

  def toModel(set: Authorization): AuthMdl.AuthSet = {

    val agents = if (set.isSetAgents) {
      set.getAgents.getAgent.map(toModel)
    } else {
      Seq()
    }

    val permissionSets = if (set.isSetPermissionSets) {
      set.getPermissionSets.getPermissionSet.map(toModel)
    } else {
      Seq()
    }

    AuthMdl.AuthSet(permissionSets, agents)
  }

  def toModel(agent: Agent): AuthMdl.Agent = {

    val name = if (agent.isSetName) agent.getName else throw new LoadingException("PermissionSet must include a name")

    val idOpt = if (agent.isSetUuid) {
      try {
        Some(UUID.fromString(agent.getUuid))
      } catch {
        case ex: Throwable =>
          throw new LoadingException(s"UUID for Agent $name must be numeric")
      }
    } else {
      None
    }

    val permNames = agent.getPermissionSet.map { obj =>
      if (obj.isSetName) obj.getName else throw new LoadingException(s"Agent permissions for $name must include a name")
    }

    AuthMdl.Agent(idOpt, name, permNames)
  }

  def toModel(permSet: PermissionSet): AuthMdl.PermissionSet = {

    val name = if (permSet.isSetName) permSet.getName else throw new LoadingException("PermissionSet must include a name")

    val idOpt = if (permSet.isSetId) {
      val idStr = permSet.getId
      val idLong = try { idStr.toLong } catch { case ex: Throwable => throw new LoadingException(s"ID for PermissionSet $name must be numeric") }
      Some(idLong)
    } else {
      None
    }

    val perms = permSet.getPermissions.map(toModel)

    AuthMdl.PermissionSet(idOpt, name, perms)
  }

  def toModel(permissionType: PermissionType): AuthMdl.Permission = {

    val isAllow = permissionType match {
      case _: Allow => true
      case _: Deny => false
    }

    val resources = XmlToModel.classFilter(permissionType.getElements.toSeq, classOf[Resource]).map { obj =>
      if (obj.isSetName) obj.getName else throw new LoadingException("Resources must include a name")
    }

    val actions = XmlToModel.classFilter(permissionType.getElements.toSeq, classOf[Action]).map { obj =>
      if (obj.isSetName) obj.getName else throw new LoadingException("Actions must include a name")
    }

    val selectors = XmlToModel.classFilter(permissionType.getElements.toSeq, classOf[Selector]).map { obj =>
      val style = if (obj.isSetStyle) obj.getStyle else throw new LoadingException("Selectors must include a style")
      val args = obj.getArgument.map { arg =>
        if (arg.isSetValue) arg.getValue else throw new LoadingException("Arguments must include a value")
      }
      AuthMdl.EntitySelector(style, args)
    }

    AuthMdl.Permission(isAllow, resources, actions, selectors)
  }

}
