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

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.service.proto.Auth.{ Agent, Permission, PermissionSet }
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.services.SqlAuthenticationModule
import io.greenbus.services.data.ServicesSchema._
import io.greenbus.services.data._
import io.greenbus.services.framework._
import io.greenbus.services.model.UUIDHelpers._
import org.squeryl.PrimitiveTypeMode._
import org.squeryl.dsl.ast.{ LogicalBoolean, ExpressionNode }

import scala.collection.JavaConversions._

object AuthModel {
  val permissionSetType = "PermissionSet"
  val agentType = "Agent"

  case class AgentInfo(passwordOpt: Option[String], permissionSets: Seq[String])
  case class PermissionSetInfo(permissions: Seq[Permission])

  case class CoreUntypedTemplate[ID, A](idOpt: Option[ID], name: String, info: A)
  case class IdentifiedUntypedTemplate[ID, A](id: ID, name: String, info: A)
  case class NamedUntypedTemplate[A](name: String, info: A)

  def splitTemplates[ID, A](templates: Seq[CoreUntypedTemplate[ID, A]]): (Seq[IdentifiedUntypedTemplate[ID, A]], Seq[NamedUntypedTemplate[A]]) = {
    val (hasIds, noIds) = templates.partition(_.idOpt.nonEmpty)

    val withIds = hasIds.map(t => IdentifiedUntypedTemplate(t.idOpt.get, t.name, t.info))
    val withNames = noIds.map(t => NamedUntypedTemplate(t.name, t.info))

    (withIds, withNames)
  }
}

trait AuthModel {
  import io.greenbus.services.model.AuthModel._

  def agentIdForName(name: String): Option[UUID]
  def createTokenAndStore(agentId: UUID, expiration: Long, clientVersion: String, location: String): String

  def simpleLogin(name: String, password: String, expiration: Long, clientVersion: String, location: String): Either[String, (String, ModelUUID)]
  def simpleLogout(token: String)
  def authValidate(token: String): Boolean

  def agentKeyQuery(uuids: Seq[UUID], names: Seq[String]): Seq[Agent]
  def agentQuery(self: Option[UUID], setNames: Seq[String], lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true): Seq[Agent]
  def putAgents(notifier: ModelNotifier, templates: Seq[CoreUntypedTemplate[UUID, AgentInfo]], allowCreate: Boolean = true): Seq[Agent]
  def modifyAgentPasswords(updates: Seq[(UUID, String)]): Seq[Agent]
  def deleteAgents(notifier: ModelNotifier, ids: Seq[UUID]): Seq[Agent]

  def permissionSetKeyQuery(ids: Seq[Long], names: Seq[String]): Seq[PermissionSet]
  def permissionSetQuery(last: Option[Long], pageSize: Int): Seq[PermissionSet]
  def permissionSetSelfKeyQuery(self: UUID, ids: Seq[Long], names: Seq[String]): Seq[PermissionSet]
  def permissionSetSelfQuery(self: UUID, last: Option[Long], pageSize: Int): Seq[PermissionSet]
  def putPermissionSets(notifier: ModelNotifier, templates: Seq[CoreUntypedTemplate[Long, PermissionSetInfo]], allowCreate: Boolean = true): Seq[PermissionSet]
  def deletePermissionSets(notifier: ModelNotifier, ids: Seq[Long]): Seq[PermissionSet]
}

class SquerylAuthModule extends SqlAuthenticationModule {
  def authenticate(name: String, password: String /*, expiration: Long, clientVersion: String, location: String*/ ): (Boolean, Option[UUID]) = {
    val agentOpt = from(agents)(agent =>
      where(agent.name === name)
        select (agent)).toSeq.headOption

    agentOpt match {
      case None => (false, None)
      case Some(agent) =>
        agent.checkPassword(password) match {
          case false => (false, Some(agent.id))
          case true => (true, Some(agent.id))
        }
    }

  }
}

object SquerylAuthModel extends AuthModel with LazyLogging {
  import io.greenbus.services.model.AuthModel._

  def agentIdForName(name: String): Option[UUID] = {
    from(agents)(agent =>
      where(agent.name === name)
        select (agent)).toSeq.headOption.map(_.id)
  }

  def createTokenAndStore(agentId: UUID, expiration: Long, clientVersion: String, location: String): String = {
    // Random UUID is cryptographically sound and unguessable
    // http://docs.oracle.com/javase/1.5.0/docs/api/java/util/UUID.html#randomUUID()
    val authToken = UUID.randomUUID().toString
    val issueTime = System.currentTimeMillis()

    val tokenRow = authTokens.insert(AuthTokenRow(0, authToken, agentId, location, clientVersion, false, issueTime, expiration))

    val agentPermissions = from(agentSetJoins)(j => where(j.agentId === agentId) select (j.permissionSetId)).toSeq

    val joinInserts = agentPermissions.map(AuthTokenPermissionSetJoinRow(_, tokenRow.id))
    tokenSetJoins.insert(joinInserts)

    authToken
  }

  def simpleLogin(name: String, password: String, expiration: Long, clientVersion: String, location: String): Either[String, (String, ModelUUID)] = {

    val agentOpt = from(agents)(agent =>
      where(agent.name === name)
        select (agent)).toSeq.headOption

    agentOpt match {
      case None => Left("Invalid username or password")
      case Some(agent) => {
        agent.checkPassword(password) match {
          case false => Left("Invalid username or password")
          case true => {
            // Random UUID is cryptographically sound and unguessable
            // http://docs.oracle.com/javase/1.5.0/docs/api/java/util/UUID.html#randomUUID()
            val authToken = UUID.randomUUID().toString
            val issueTime = System.currentTimeMillis()

            val tokenRow = authTokens.insert(AuthTokenRow(0, authToken, agent.id, location, clientVersion, false, issueTime, expiration))

            val agentPermissions = from(agentSetJoins)(j => where(j.agentId === agent.id) select (j.permissionSetId)).toSeq

            val joinInserts = agentPermissions.map(AuthTokenPermissionSetJoinRow(_, tokenRow.id))
            tokenSetJoins.insert(joinInserts)
            Right((authToken, uuidToProtoUUID(agent.id)))
          }
        }
      }
    }
  }

  def simpleLogout(token: String) {
    val current = authTokens.where(t => t.token === token).headOption

    current match {
      case None =>
      case Some(tokenRow) =>
        if (!tokenRow.revoked) {
          authTokens.update(tokenRow.copy(revoked = true))
        }
    }
  }

  private def lookupToken(token: String): Option[(AuthTokenRow, UUID, String, Seq[Array[Byte]])] = {
    val now = System.currentTimeMillis

    val tokenAndSets = join(authTokens, permissionSets.leftOuter)((tok, set) =>
      where(tok.token === token and
        (tok.expirationTime gt now) and
        tok.revoked === false)
        select (tok, set.map(_.protoBytes))
        on (set.map(_.id) in
          from(tokenSetJoins)(j =>
            where(j.authTokenId === tok.id)
              select (j.permissionSetId)))).toSeq

    val authTokenRow = tokenAndSets.headOption.map(_._1)

    authTokenRow.flatMap { tokenRow =>
      val permBytes = tokenAndSets.flatMap(_._2)
      val resultOpt =
        from(agents)(a =>
          where(a.id === tokenRow.agentId)
            select (a.id, a.name)).headOption

      if (resultOpt.isEmpty) {
        logger.warn("Auth token retrieved without associated agent")
      }

      resultOpt.map {
        case (agentId, agentName) =>
          (tokenRow, agentId, agentName, permBytes)
      }
    }
  }

  def authLookup(token: String): Option[(UUID, String, Seq[Array[Byte]])] = {
    lookupToken(token).map {
      case (_, uuid, name, perms) => (uuid, name, perms)
    }
  }

  def authValidate(token: String): Boolean = {
    lookupToken(token).nonEmpty
  }

  def agentKeyQuery(uuids: Seq[UUID], names: Seq[String]): Seq[Agent] = {

    val agentInfo: Seq[(UUID, String)] =
      from(agents)(agent =>
        where((agent.id in uuids) or (agent.name in names))
          select ((agent.id, agent.name))).toSeq

    val agentIds = agentInfo.map(_._1)

    val agentIdToSetNames: Map[UUID, Seq[String]] =
      from(agentSetJoins, permissionSets)((j, set) =>
        where((j.agentId in agentIds) and
          j.permissionSetId === set.id)
          select (j.agentId, set.name))
        .groupBy(_._1)
        .toMap
        .mapValues(_.map(_._2).toSeq)

    agentInfo.map {
      case (uuid, name) =>
        val sets = agentIdToSetNames.getOrElse(uuid, Seq())
        Agent.newBuilder()
          .setUuid(uuid)
          .setName(name)
          .addAllPermissionSets(sets)
          .build
    }

    /*
     Runtime error ?!?!
    val results: Seq[((UUID, String), Option[String])] =
      from(entityQuery, agents, entities.leftOuter)((ent, agent, setEnt) =>
        where(ent.id === agent.entityId and
          (setEnt.map(_.id) in from(agentSetJoins, permissionSets)((j, set) =>
            where(j.agentId === agent.id and j.permissionSetId === set.id)
              select(set.entityId))))
          select((ent.id, ent.name), setEnt.map(_.name))
          orderBy(ent.id)).toSeq

    val grouped: Seq[((UUID, String), Seq[String])] = groupSortedAndPreserveOrder(results) map { tup => (tup._1, tup._2.flatten) }

    grouped.map {
      case ((uuid, name), sets) =>
        Agent.newBuilder()
          .setUuid(uuid)
          .setName(name)
          .addAllPermissionSets(sets)
          .build
    } */
  }

  def agentQuery(self: Option[UUID], setNames: Seq[String], lastUuid: Option[UUID], lastName: Option[String], pageSize: Int, pageByName: Boolean = true): Seq[Agent] = {

    def ordering(ag: AgentRow): ExpressionNode = {
      if (pageByName) {
        ag.name
      } else {
        ag.id
      }
    }

    def guardFunc(lastUuid: Option[UUID], lastName: Option[String], pageByName: Boolean = true): AgentRow => LogicalBoolean = {
      if (pageByName) {

        val nameOpt = lastName match {
          case Some(name) => Some(name)
          case None =>
            lastUuid match {
              case Some(uuid) => entities.where(t => t.id === uuid).headOption.map(_.name)
              case None => None
            }
        }

        def pageGuard(ag: AgentRow) = ag.name gt nameOpt.?
        pageGuard

      } else {

        val uuidOpt = lastUuid match {
          case Some(uuid) => Some(uuid)
          case None =>
            lastName match {
              case Some(name) => entities.where(t => t.name === name).headOption.map(_.id)
              case None => None
            }
        }

        def pageGuard(ag: AgentRow) = ag.id > uuidOpt.?
        pageGuard
      }
    }

    val guard = guardFunc(lastUuid, lastName, pageByName)

    val agentResults: Seq[(UUID, String)] = if (setNames.nonEmpty) {
      from(agents, agentSetJoins, permissionSets)((agent, j, set) =>
        where(
          guard(agent) and
            agent.id === self.? and
            j.agentId === agent.id and
            j.permissionSetId === set.id and
            (set.name in setNames).inhibitWhen(setNames.isEmpty))
          select (agent.id, agent.name)
          orderBy (ordering(agent)))
        .page(0, pageSize)
        .toVector
        .distinct
    } else {
      from(agents)(agent =>
        where(guard(agent) and
          agent.id === self.?)
          select (agent.id, agent.name)
          orderBy (ordering(agent)))
        .page(0, pageSize)
        .toVector
        .distinct
    }

    val agentIds = agentResults.map(_._1).toSet

    val agentUuidToSetNames: Map[UUID, Seq[String]] =
      from(agents, agentSetJoins, permissionSets)((agent, j, set) =>
        where(
          (agent.id in agentIds) and
            j.agentId === agent.id and
            j.permissionSetId === set.id)
          select (agent.id, set.name))
        .groupBy(_._1)
        .toMap
        .mapValues(_.map(_._2).toSeq)

    agentResults.map {
      case (uuid, name) =>
        val sets = agentUuidToSetNames.getOrElse(uuid, Nil)
        Agent.newBuilder()
          .setUuid(uuid)
          .setName(name)
          .addAllPermissionSets(sets)
          .build
    }

  }

  private def agentsByKey(uuids: Seq[UUID], names: Seq[String]): Seq[(AgentRow, Seq[String])] = {
    val agentInfo: Seq[AgentRow] =
      from(agents)(agent =>
        where((agent.id in uuids) or (agent.name in names))
          select (agent)).toVector

    val agentIds = agentInfo.map(_.id)

    val agentIdToSetNames: Map[UUID, Seq[String]] =
      from(agentSetJoins, permissionSets)((j, set) =>
        where((j.agentId in agentIds) and
          j.permissionSetId === set.id)
          select (j.agentId, set.name))
        .groupBy(_._1)
        .toMap
        .mapValues(_.map(_._2).toVector)

    agentInfo.map { agent =>
      val sets = agentIdToSetNames.getOrElse(agent.id, Seq())
      (agent, sets)
    }
  }

  def putAgents(notifier: ModelNotifier, templates: Seq[CoreUntypedTemplate[UUID, AgentInfo]], allowCreate: Boolean = true): Seq[Agent] = {

    // For any given agent, three things can change: the entity name/types, the agent table entry (password) or the permission set joins
    // We do the first two with common code and handle the latter manually

    val allSetNames = templates.flatMap(_.info.permissionSets).toSet

    val existingSets = if (allSetNames.nonEmpty) {
      from(permissionSets)(perm =>
        where(perm.name in allSetNames)
          select (perm.name, perm.id)).toVector
    } else {
      Seq()
    }

    val setMap: Map[String, Long] = existingSets.toMap

    val bogusSets = allSetNames.filterNot(setMap.contains)
    if (bogusSets.nonEmpty) {
      throw new ModelInputException(s"Permission sets requested do not exist: " + bogusSets.mkString(", "))
    }

    val (withIds, withNames) = AuthModel.splitTemplates(templates)

    val templateIds = withIds.map(_.id)
    val templateNames = withNames.map(_.name)

    val existing = agentsByKey(templateIds, templateNames)

    val existingIdSet = existing.map(_._1.id).toSet
    val existingNameSet = existing.map(_._1.name).toSet

    val nonexistentByIds = withIds.filterNot(t => existingIdSet.contains(t.id))
    val nonexistentByNames = withNames.filterNot(t => existingNameSet.contains(t.name))

    if ((nonexistentByIds.nonEmpty || nonexistentByNames.nonEmpty) && !allowCreate) {
      throw new ModelPermissionException("Must have blanket create permissions to create entities")
    }

    val withIdsById: Map[UUID, IdentifiedUntypedTemplate[UUID, AgentInfo]] = withIds.map(temp => (temp.id, temp)).toMap
    val withNamesByName: Map[String, NamedUntypedTemplate[AgentInfo]] = withNames.map(temp => (temp.name, temp)).toMap

    val agentUpdates: Seq[(UUID, Option[AgentRow], Set[String], Set[String])] = existing.flatMap {
      case (row, permSets) =>

        val optTemp: Option[(String, AgentInfo)] = withIdsById.get(row.id).map(temp => (temp.name, temp.info))
          .orElse(withNamesByName.get(row.name).map(temp => (temp.name, temp.info)))

        optTemp.flatMap {
          case (updateName, updateInfo) =>

            val passwordUpdatedOpt = updateInfo.passwordOpt.map(pass => row.withUpdatedPassword(pass))

            val passwordChanged = passwordUpdatedOpt.exists(updateRow => updateRow.digest != row.digest || updateRow.salt != row.salt)

            val existPermsSet = permSets.toSet
            val updatePermsSet = updateInfo.permissionSets.toSet

            val permAdds = updatePermsSet diff existPermsSet
            val permRems = existPermsSet diff updatePermsSet

            if (updateName != row.name || passwordChanged) {
              val startFrom = passwordUpdatedOpt.getOrElse(row)
              Some((row.id, Some(startFrom.copy(name = updateName)), permAdds, permRems))
            } else {
              if (permAdds.isEmpty && permRems.isEmpty) {
                None
              } else {
                Some((row.id, None, permAdds, permRems))
              }
            }
        }
    }

    val toCreateByIds = nonexistentByIds.map { temp =>
      val password = temp.info.passwordOpt.getOrElse { throw new ModelInputException(s"Password must be provided for non-existent agent ${temp.name}") }
      val permAdds = temp.info.permissionSets.map(perm => (temp.id, perm))
      (AgentRow(temp.id, temp.name, password), permAdds)
    }

    val toCreateByNames = nonexistentByNames.map { temp =>
      val uuid = UUID.randomUUID()
      val password = temp.info.passwordOpt.getOrElse { throw new ModelInputException(s"Password must be provided for non-existent agent ${temp.name}") }
      val permAdds = temp.info.permissionSets.map(perm => (uuid, perm))
      (AgentRow(uuid, temp.name, password), permAdds)
    }

    val toCreateTupAll = toCreateByIds ++ toCreateByNames

    val createRowInserts = toCreateTupAll.map(_._1)
    val createPermAdds = toCreateTupAll.flatMap(_._2)

    val rowUpdates = agentUpdates.flatMap(_._2)
    val updatePermAdds = agentUpdates.flatMap(tup => tup._3.map(perm => (tup._1, perm)))
    val updatePermRems = agentUpdates.flatMap(tup => tup._4.map(perm => (tup._1, perm)))

    val allPermAdds = createPermAdds ++ updatePermAdds

    val permAddRows = allPermAdds.map {
      case (uuid, permName) =>
        val permId = setMap.getOrElse(permName, throw new ModelAssertionException(s"Could not find ID for permission $permName"))
        AgentPermissionSetJoinRow(permId, uuid)
    }

    agents.insert(createRowInserts)

    agents.update(rowUpdates)

    agentSetJoins.insert(permAddRows)

    val permsToBeDeleted = updatePermRems.map(_._2)

    val delSetMap: Map[String, Long] =
      from(permissionSets)(set =>
        where(set.name in permsToBeDeleted)
          select ((set.name, set.id))).toVector.toMap

    val groupedPermRems: Map[UUID, Seq[String]] = updatePermRems.groupBy(_._1).mapValues(_.map(_._2))

    groupedPermRems.foreach {
      case (uuid, names) =>
        val permIds = names.flatMap(delSetMap.get)
        agentSetJoins.deleteWhere(j => j.agentId === uuid and (j.permissionSetId in permIds))
    }

    val results = agentKeyQuery(templateIds, templateNames)

    val createIds = toCreateTupAll.map(_._1.id).toSet
    val updateIds = agentUpdates.map(_._1).toSet

    val created = results.filter(r => createIds.contains(protoUUIDToUuid(r.getUuid)))
    val updated = results.filter(r => updateIds.contains(protoUUIDToUuid(r.getUuid)))

    created.foreach(notifier.notify(Created, _))
    updated.foreach(notifier.notify(Updated, _))

    results
  }

  def modifyAgentPasswords(updates: Seq[(UUID, String)]): Seq[Agent] = {
    val uuids = updates.map(_._1).distinct

    val all = agents.where(a => a.id in uuids).toVector

    val agentByUuid: Map[UUID, AgentRow] = all.map(a => (a.id, a)).toMap

    val updatedRows = updates.map {
      case (uuid, pass) =>
        val row = agentByUuid.getOrElse(uuid, throw new ModelInputException("Agent did not exist"))
        row.withUpdatedPassword(pass)
    }

    agents.update(updatedRows)

    agentKeyQuery(uuids, Seq())
  }

  def deleteAgents(notifier: ModelNotifier, ids: Seq[UUID]): Seq[Agent] = {

    val results = agentKeyQuery(ids, Nil)

    authTokens.deleteWhere(tok => tok.agentId in ids)
    agentSetJoins.deleteWhere(j => j.agentId in ids)
    agents.deleteWhere(agent => agent.id in ids)

    results.foreach { notifier.notify(Deleted, _) }

    results
  }

  def permissionSetSelfKeyQuery(self: UUID, ids: Seq[Long], names: Seq[String]): Seq[PermissionSet] = {

    val results: Seq[(Long, String, Array[Byte])] =
      from(permissionSets, agentSetJoins, agents)((set, j, agent) =>
        where(
          agent.id === self and
            j.agentId === agent.id and j.permissionSetId === set.id and
            ((set.id in ids) or (set.name in names)))
          select (set.id, set.name, set.protoBytes)).toVector

    results.map {
      case (id, name, bytes) =>
        PermissionSet.newBuilder(PermissionSet.parseFrom(bytes))
          .setId(id)
          .setName(name)
          .build()
    }
  }

  def permissionSetKeyQuery(ids: Seq[Long], names: Seq[String]): Seq[PermissionSet] = {

    val results: Seq[(Long, String, Array[Byte])] =
      from(permissionSets)(set =>
        where((set.id in ids) or (set.name in names))
          select (set.id, set.name, set.protoBytes)).toVector

    results.map {
      case (id, name, bytes) =>
        PermissionSet.newBuilder(PermissionSet.parseFrom(bytes))
          .setId(id)
          .setName(name)
          .build()
    }
  }

  def permissionSetSelfQuery(self: UUID, last: Option[Long], pageSize: Int): Seq[PermissionSet] = {

    val results = from(permissionSets, agentSetJoins, agents)((set, j, agent) =>
      where(set.id > last.? and
        agent.id === self and
        j.agentId === agent.id and
        j.permissionSetId === set.id)
        select (set)
        orderBy (set.id))
      .page(0, pageSize)
      .toVector

    results.map {
      case (row) =>
        PermissionSet.newBuilder(PermissionSet.parseFrom(row.protoBytes))
          .setId(row.id)
          .setName(row.name)
          .build()
    }
  }

  def permissionSetQuery(last: Option[Long], pageSize: Int): Seq[PermissionSet] = {

    val results = from(permissionSets)(set =>
      where(set.id > last.?)
        select (set)
        orderBy (set.id))
      .page(0, pageSize)
      .toVector

    results.map {
      case (row) =>
        PermissionSet.newBuilder(PermissionSet.parseFrom(row.protoBytes))
          .setId(row.id)
          .setName(row.name)
          .build()
    }
  }

  def putPermissionSets(notifier: ModelNotifier, templates: Seq[CoreUntypedTemplate[Long, PermissionSetInfo]], allowCreate: Boolean = true): Seq[PermissionSet] = {

    val (withIds, withNames) = AuthModel.splitTemplates(templates)

    val templateIds = withIds.map(_.id)
    val templateNames = withNames.map(_.name)

    val existing = permissionSets.where(set => (set.id in templateIds) or (set.name in templateNames)).toVector

    val existingIdSet = existing.map(_.id).toSet
    val existingNameSet = existing.map(_.name).toSet

    val nonexistentByIds = withIds.filterNot(t => existingIdSet.contains(t.id))
    val nonexistentByNames = withNames.filterNot(t => existingNameSet.contains(t.name))

    if (nonexistentByIds.nonEmpty) {
      throw new ModelInputException("Cannot create new permission sets with an ID")
    }

    if (nonexistentByNames.nonEmpty && !allowCreate) {
      throw new ModelPermissionException("Must have blanket create permissions to create entities")
    }

    val withIdsById: Map[Long, IdentifiedUntypedTemplate[Long, PermissionSetInfo]] = withIds.map(temp => (temp.id, temp)).toMap
    val withNamesByName: Map[String, NamedUntypedTemplate[PermissionSetInfo]] = withNames.map(temp => (temp.name, temp)).toMap

    val updates = existing.flatMap { row =>
      val optTemp: Option[(String, PermissionSetInfo)] = withIdsById.get(row.id).map(temp => (temp.name, temp.info))
        .orElse(withNamesByName.get(row.name).map(temp => (temp.name, temp.info)))

      optTemp.flatMap {
        case (updateName, info) =>
          val updateBytes = PermissionSet.newBuilder().addAllPermissions(info.permissions).build().toByteArray

          if (updateName != row.name || !java.util.Arrays.equals(row.protoBytes, updateBytes)) {
            Some(row.copy(name = updateName, protoBytes = updateBytes))
          } else {
            None
          }
      }
    }

    val createRows = nonexistentByNames.map { temp =>
      val bytes = PermissionSet.newBuilder().addAllPermissions(temp.info.permissions).build().toByteArray
      PermissionSetRow(0, temp.name, bytes)
    }

    permissionSets.update(updates)
    permissionSets.insert(createRows)

    val createNames = createRows.map(_.name).toSet
    val updateIds = updates.map(_.id).toSet

    val results = permissionSetKeyQuery(templateIds, templateNames)

    results.filter(set => createNames.contains(set.getName)).foreach(notifier.notify(Created, _))
    results.filter(set => updateIds.contains(protoIdToLong(set.getId))).foreach(notifier.notify(Updated, _))

    results
  }

  def deletePermissionSets(notifier: ModelNotifier, ids: Seq[Long]): Seq[PermissionSet] = {

    val results = permissionSetKeyQuery(ids, Seq())

    permissionSets.deleteWhere(ps => ps.id in ids)
    agentSetJoins.deleteWhere(j => j.permissionSetId in ids)
    tokenSetJoins.deleteWhere(j => j.permissionSetId in ids)

    results.foreach(notifier.notify(Deleted, _))

    results
  }

}
