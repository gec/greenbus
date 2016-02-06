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

import org.squeryl.PrimitiveTypeMode._
import io.greenbus.services.data.ServicesSchema._
import io.greenbus.services.data._
import UUIDHelpers._
import java.util.UUID
import scala.collection.JavaConversions._
import io.greenbus.services.framework.{ Deleted, Created, ModelNotifier }
import io.greenbus.services.authz.EntityFilter
import io.greenbus.client.service.proto.Commands.CommandLock
import org.squeryl.Query

class CommandLockException(msg: String) extends ModelInputException(msg)

trait CommandModel {

  def lockKeyQuery(lockIds: Seq[Long], agentFilter: Option[EntityFilter] = None): Seq[CommandLock]
  def lockQuery(cmdIds: Seq[UUID], agents: Seq[UUID], accessMode: Option[CommandLock.AccessMode], lastId: Option[Long], pageSize: Int, agentFilter: Option[EntityFilter] = None): Seq[CommandLock]

  def agentHasSelect(cmd: UUID, agent: UUID, filter: Option[EntityFilter] = None): Boolean
  def agentCanIssueCommand(cmd: UUID, agent: UUID, filter: Option[EntityFilter] = None): Boolean

  def selectCommands(notifier: ModelNotifier, cmdIds: Seq[UUID], agent: UUID, expireTime: Long, filter: Option[EntityFilter] = None): CommandLock
  def blockCommands(notifier: ModelNotifier, cmdIds: Seq[UUID], agent: UUID, filter: Option[EntityFilter] = None): CommandLock

  def deleteLocks(notifier: ModelNotifier, lockIds: Seq[Long], agentFilter: Option[UUID] = None): Seq[CommandLock]
}

object SquerylCommandModel extends CommandModel {
  import ModelHelpers._

  private def findApplicableLocks(cmd: UUID, agent: UUID, filter: Option[EntityFilter] = None) = {
    filter.foreach { filt =>
      if (filt.isOutsideSet(cmd)) {
        throw new ModelPermissionException("Tried to lookup restricted commands")
      }
    }

    val now = System.currentTimeMillis

    from(commands, locks, lockJoins)((c, l, j) =>
      where((c.entityId === cmd) and
        l.agent === agent and
        j.commandId === c.id and
        j.lockId === l.id and
        (l.expireTime.isNull or (l.expireTime gt now)))
        select (l)).toVector
  }

  def agentCanIssueCommand(cmd: UUID, agent: UUID, filter: Option[EntityFilter] = None): Boolean = {

    filter.foreach { filt =>
      if (filt.isOutsideSet(cmd)) {
        throw new ModelPermissionException("Tried to lookup restricted commands")
      }
    }

    val now = System.currentTimeMillis

    val locksForCommands = from(commands, locks, lockJoins)((c, l, j) =>
      where((c.entityId === cmd) and
        j.commandId === c.id and
        j.lockId === l.id and
        (l.expireTime.isNull or (l.expireTime gt now)))
        select (l)).toVector

    (locksForCommands.isEmpty ||
      locksForCommands.exists(lock => lock.access == CommandLock.AccessMode.ALLOWED.getNumber && lock.agent == agent)) &&
      !locksForCommands.exists(lock => lock.access == CommandLock.AccessMode.BLOCKED.getNumber)
  }

  def agentHasSelect(cmd: UUID, agent: UUID, filter: Option[EntityFilter] = None): Boolean = {

    val applicableLocks = findApplicableLocks(cmd, agent, filter)

    applicableLocks.exists(lock => lock.access == CommandLock.AccessMode.ALLOWED.getNumber && lock.agent == agent) &&
      !applicableLocks.exists(lock => lock.access == CommandLock.AccessMode.BLOCKED.getNumber)
  }

  def lockKeyQuery(lockIds: Seq[Long], agentFilter: Option[EntityFilter] = None): Seq[CommandLock] = {

    val results: Seq[(CommandLockRow, Option[UUID])] =
      join(locks, commands.leftOuter)((lock, cmd) =>
        where(EntityFilter.optional(agentFilter, lock.agent).inhibitWhen(agentFilter.isEmpty) and
          (lock.id in lockIds))
          select (lock, cmd.map(_.entityId))
          orderBy (lock.id)
          on (cmd.map(_.id) in from(lockJoins)(j => where(j.lockId in lockIds) select (j.commandId)))).toSeq

    val grouped = groupSortedAndPreserveOrder(results)

    grouped.map {
      case (row, optCmdList) =>
        val b = CommandLock.newBuilder()
          .setId(row.id)
          .setAccess(CommandLock.AccessMode.valueOf(row.access))
          .addAllCommandUuids(optCmdList.flatten.map(uuidToProtoUUID))
          .setAgentUuid(row.agent)

        row.expireTime.foreach(b.setExpireTime)

        b.build()
    }

  }

  def lockQuery(cmdIds: Seq[UUID], agents: Seq[UUID], accessMode: Option[CommandLock.AccessMode], lastId: Option[Long], pageSize: Int, agentFilter: Option[EntityFilter] = None): Seq[CommandLock] = {

    val idQuery: Query[Long] = if (cmdIds.isEmpty) {

      from(locks)((lock) =>
        where(EntityFilter.optional(agentFilter, lock.agent).inhibitWhen(agentFilter.isEmpty) and
          (lock.agent in agents).inhibitWhen(agents.isEmpty) and
          (Some(lock.access) === accessMode.map(_.getNumber)).inhibitWhen(accessMode.isEmpty) and
          (lock.id gt lastId.?))
          select (lock.id)).page(0, pageSize)

    } else {

      from(locks, lockJoins, commands)((lock, lj, cmd) =>
        where(EntityFilter.optional(agentFilter, lock.agent).inhibitWhen(agentFilter.isEmpty) and
          (lock.id === lj.lockId and cmd.id === lj.commandId) and
          (lock.agent in agents).inhibitWhen(agents.isEmpty) and
          (Some(lock.access) === accessMode.map(_.getNumber)).inhibitWhen(accessMode.isEmpty) and
          (cmd.entityId in cmdIds) and
          (lock.id gt lastId.?))
          select (lock.id)).page(0, pageSize)
    }

    val lockIdList = idQuery.toList

    val query: Query[(CommandLockRow, UUID)] =
      join(locks, commands)((lock, cmd) =>
        where(lock.id in lockIdList)
          select (lock, cmd.entityId)
          orderBy (lock.id)
          on (cmd.id in from(lockJoins)(j => where(j.lockId === lock.id) select (j.commandId))))

    val results: Seq[(CommandLockRow, UUID)] = query.toSeq

    val locksWithCmds: Seq[(CommandLockRow, Seq[UUID])] = groupSortedAndPreserveOrder(results.toList)

    /*val locksWithCmds: Seq[(CommandLockRow, Seq[UUID])] = if (cmdIds.isEmpty) {

      val idQuery: Query[Long] =
        from(locks)((lock) =>
          where(EntityFilter.optional(agentFilter, lock.agent).inhibitWhen(agentFilter.isEmpty) and
            (lock.agent in agents).inhibitWhen(agents.isEmpty) and
            (Some(lock.access) === accessMode.map(_.getNumber)).inhibitWhen(accessMode.isEmpty) and
            (lock.id gt lastId.?))
            select (lock.id)).page(0, pageSize)

      println(idQuery)

      val lockIdList = idQuery.toList

      val query: Query[(CommandLockRow, UUID)] =
        join(locks, commands)((lock, cmd) =>
          where(lock.id in lockIdList /*lks*/ /*from(lockQuery)(l => select(l.id)).page(0, pageSize)*/)
            select (lock, cmd.entityId)
            orderBy (lock.id)
            on (cmd.id in from(lockJoins)(j => where(j.lockId === lock.id) select (j.commandId))))

      println(query)

      val results: Seq[(CommandLockRow, UUID)] = query.toSeq

      groupSortedAndPreserveOrder(results.toList)

    } else {

      val lockQuery: Query[CommandLockRow] =
        from(locks, lockJoins, commands)((lock, lj, cmd) =>
          where(EntityFilter.optional(agentFilter, lock.agent).inhibitWhen(agentFilter.isEmpty) and
            (lock.id === lj.lockId and cmd.id === lj.commandId) and
            (lock.agent in agents).inhibitWhen(agents.isEmpty) and
            (Some(lock.access) === accessMode.map(_.getNumber)).inhibitWhen(accessMode.isEmpty) and
            (cmd.entityId in cmdIds) and
            (lock.id gt lastId.?))
            select (lock)).page(0, pageSize)

      val results: Seq[(CommandLockRow, UUID)] =
        join(locks, commands)((lock, cmd) =>
          where(lock.id in from(lockQuery)(l => select(l.id)))
            select (lock, cmd.entityId)
            orderBy (lock.id)
            on (cmd.id in from(lockJoins)(j => where(j.lockId === lock.id) select (j.commandId)))).toSeq

      groupSortedAndPreserveOrder(results.toList)
    }*/

    locksWithCmds.map {
      case (row, cmdList) =>
        val b = CommandLock.newBuilder()
          .setId(row.id)
          .setAccess(CommandLock.AccessMode.valueOf(row.access))
          .addAllCommandUuids(cmdList.map(uuidToProtoUUID))
          .setAgentUuid(row.agent)

        row.expireTime.foreach(b.setExpireTime)

        b.build()
    }
  }

  def selectCommands(notifier: ModelNotifier, cmdIds: Seq[UUID], agent: UUID, expireTime: Long, filter: Option[EntityFilter] = None): CommandLock = {
    filter.foreach { filt =>
      if (filt.anyOutsideSet(cmdIds)) {
        throw new ModelPermissionException("Tried to lock restricted commands")
      }
    }

    val lockedCmds = commands.where(c => c.entityId in cmdIds).forUpdate.toSeq
    val now = System.currentTimeMillis

    val lockedIds = lockedCmds.map(_.id)

    val existingLocks =
      from(locks, lockJoins)((l, j) =>
        where((j.commandId in lockedIds) and
          j.lockId === l.id and
          (l.expireTime.isNull or (l.expireTime gt now)))
          select (l)).toSeq

    if (existingLocks.nonEmpty) {
      throw new CommandLockException("Commands already locked")
    }

    val lock = locks.insert(CommandLockRow(0, CommandLock.AccessMode.ALLOWED.getNumber, Some(expireTime), agent))

    val joins = lockedIds.map(cmdId => LockJoinRow(cmdId, lock.id))

    lockJoins.insert(joins)

    update(commands)(c =>
      where(c.id in lockedIds)
        set (c.lastSelectId := Some(lock.id)))

    val created = CommandLock.newBuilder()
      .setId(lock.id)
      .setAccess(CommandLock.AccessMode.ALLOWED)
      .setExpireTime(expireTime)
      .addAllCommandUuids(lockedCmds.map(_.entityId).map(uuidToProtoUUID))
      .setAgentUuid(agent)
      .build()

    notifier.notify(Created, created)

    created
  }

  def blockCommands(notifier: ModelNotifier, cmdIds: Seq[UUID], agent: UUID, filter: Option[EntityFilter] = None): CommandLock = {
    filter.foreach { filt =>
      if (filt.anyOutsideSet(cmdIds)) {
        throw new ModelPermissionException("Tried to lock restricted commands")
      }
    }

    val lockedCmds = commands.where(c => c.entityId in cmdIds).forUpdate.toSeq
    val lockedIds = lockedCmds.map(_.id)

    val lock = locks.insert(CommandLockRow(0, CommandLock.AccessMode.BLOCKED.getNumber, None, agent))

    val joins = lockedIds.map(cmdId => LockJoinRow(cmdId, lock.id))

    lockJoins.insert(joins)

    update(commands)(c =>
      where(c.id in lockedIds)
        set (c.lastSelectId := Some(lock.id)))

    val created = CommandLock.newBuilder()
      .setId(lock.id)
      .setAccess(CommandLock.AccessMode.BLOCKED)
      .addAllCommandUuids(lockedCmds.map(_.entityId).map(uuidToProtoUUID))
      .setAgentUuid(agent)
      .build()

    notifier.notify(Created, created)

    created
  }

  def deleteLocks(notifier: ModelNotifier, lockIds: Seq[Long], agentFilter: Option[UUID] = None): Seq[CommandLock] = {

    agentFilter.foreach { agentUuid =>
      val nonOwnedIdCount =
        from(locks)(lock =>
          where(not(lock.agent === agentUuid) and
            (lock.id in lockIds))
            select (lock)).page(0, 1).nonEmpty

      if (nonOwnedIdCount) {
        throw new ModelPermissionException("Tried to delete restricted command locks")
      }
    }

    val results = lockKeyQuery(lockIds)

    locks.deleteWhere(lock => lock.id in lockIds)
    lockJoins.deleteWhere(j => j.lockId in lockIds)

    results.foreach(notifier.notify(Deleted, _))

    results
  }
}

