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
package io.greenbus.services.authz

import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.client.exception.{ ForbiddenException, UnauthorizedException }
import io.greenbus.services.model.SquerylAuthModel
import io.greenbus.client.service.proto.Auth.{ EntitySelector, Permission, PermissionSet }
import com.google.protobuf.InvalidProtocolBufferException
import scala.collection.JavaConversions._
import java.util.UUID

trait AuthLookup {
  def validateAuth(headers: Map[String, String]): AuthContext
}

object DefaultAuthLookup extends AuthLookup with Logging {

  def validateAuth(headers: Map[String, String]): AuthContext = {

    val token = headers.getOrElse("AUTH_TOKEN", throw new UnauthorizedException("Must include AUTH_TOKEN header"))

    val (agentId, agentName, permBytes) = SquerylAuthModel.authLookup(token).getOrElse {
      throw new UnauthorizedException("Invalid auth token")
    }

    val permissions = try {
      permBytes.map(PermissionSet.parseFrom)
    } catch {
      case protoEx: InvalidProtocolBufferException =>
        logger.error(s"Couldn't parse permission sets for agent '$agentName'")
        throw new ForbiddenException("Invalid permission sets, see system administrator")
    }

    new DefaultAuthContext(agentId, agentName, permissions)
  }
}

trait AuthContext {
  def authorize(resource: String, action: String): Option[EntityFilter]
  def authorizeUnconditionallyOnly(resource: String, action: String): Unit
  def authorizeAndCheckSelfOnly(resource: String, action: String): Boolean
  def agentName: String
  def agentId: UUID
}

class DefaultAuthContext(val agentId: UUID, val agentName: String, sets: Seq[PermissionSet]) extends AuthContext with Logging {

  private lazy val allPermissions: Seq[Permission] = sets.flatMap(_.getPermissionsList.toSeq)

  def authorizeAndCheckSelfOnly(resource: String, action: String): Boolean = {

    val applicable = allPermissions.filter { p =>
      p.getResourcesList.exists(r => r == "*" || r == resource) &&
        p.getActionsList.exists(a => a == "*" || a == action)
    }

    // Deny all by default
    if (applicable.isEmpty) {
      throw new ForbiddenException(s"Insufficient permissions for $resource:$action")
    }
    val (allows, denies) = applicable.partition(_.getAllow)

    // Allow must be unconditional or 'self', deny is impossible because agent sets can't be defined other than self
    if (denies.nonEmpty) {
      throw new ForbiddenException(s"Insufficient permissions for $resource:$action")
    }

    val blanketAllow = allows.exists(a => a.getSelectorsCount == 0 || a.getSelectorsList.forall(_.getStyle == "*"))

    if (blanketAllow) {
      false
    } else {
      val selfSpecified = allows.forall(p => p.getSelectorsList.forall(s => s.getStyle == "self"))

      // If there is some other selector (and not blanket allow) we can't authorize this
      if (selfSpecified) true else throw { new ForbiddenException(s"Insufficient permissions for $resource:$action") }
    }
  }

  def authorizeUnconditionallyOnly(resource: String, action: String): Unit = {

    val applicable = allPermissions.filter { p =>
      p.getResourcesList.exists(r => r == "*" || r == resource) &&
        p.getActionsList.exists(a => a == "*" || a == action)
    }

    // Deny all by default
    if (applicable.isEmpty) {
      throw new ForbiddenException(s"Insufficient permissions for $resource:$action")
    }
    val (allows, denies) = applicable.partition(_.getAllow)

    // Allow must be unconditional or 'self', deny is impossible because agent sets can't be defined other than self
    if (denies.nonEmpty) {
      throw new ForbiddenException(s"Insufficient permissions for $resource:$action")
    }

    val blanketAllow = allows.exists(a => a.getSelectorsCount == 0 || a.getSelectorsList.forall(_.getStyle == "*"))

    if (!blanketAllow) {
      throw new ForbiddenException(s"Insufficient permissions for $resource:$action")
    }
  }

  def authorize(resource: String, action: String): Option[EntityFilter] = {

    val applicable = allPermissions.filter { p =>
      p.getResourcesList.exists(r => r == "*" || r == resource) &&
        p.getActionsList.exists(a => a == "*" || a == action)
    }

    // Deny all by default
    if (applicable.isEmpty) {
      throw new ForbiddenException(s"Insufficient permissions for $resource:$action")
    }
    val (allows, denies) = applicable.partition(_.getAllow)

    // Blanket denial of this resource/action
    if (denies.exists(p => p.getSelectorsCount == 0 || p.getSelectorsList.exists(_.getStyle == "*"))) {
      throw new ForbiddenException(s"Insufficient permissions for $resource:$action")
    }

    val blanketAllow = allows.forall(a => a.getSelectorsCount == 0 || a.getSelectorsList.forall(_.getStyle == "*"))

    // Blanket permission of this resource/action
    if (denies.isEmpty && blanketAllow) {
      None
    } else {
      // Resource-conditional permissions
      val denySelectors = denies.flatMap(_.getSelectorsList).map(interpretSelector)
      val allowSelectors = allows.flatMap(_.getSelectorsList).filterNot(_.getStyle == "*").map(interpretSelector)

      val filter = ResourceSelector.buildFilter(denySelectors, allowSelectors)
      Some(filter)
    }
  }

  private def interpretSelector(selector: EntitySelector): ResourceSelector = {

    // Problems here are a data input problem with the auth system
    def invalid(msg: String): Throwable = {
      logger.error(msg)
      new ForbiddenException("Invalid permissions sets, see system administrator")
    }

    if (!selector.hasStyle) {
      throw invalid(s"Permission set for '$agentName' has entity selector missing style")
    }
    selector.getStyle.trim match {
      case "*" => throw new IllegalStateException("Should have filtered out '*' entity selectors")
      case "type" => {
        if (selector.getArgumentsCount == 0) {
          throw invalid(s"Selector for '$agentName' is 'type' but has no arguments")
        }
        TypeSelector(selector.getArgumentsList.toSeq)
      }
      case "parent" => {
        if (selector.getArgumentsCount == 0) {
          throw invalid(s"Selector for '$agentName' is 'parent' but has no arguments")
        }
        ParentSelector(selector.getArgumentsList.toSeq)
      }
      case other => {
        throw invalid(s"Unknown selector type for '$agentName': $other")
      }
    }
  }
}