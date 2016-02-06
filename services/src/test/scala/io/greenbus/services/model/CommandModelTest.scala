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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import io.greenbus.client.service.proto.Model.CommandCategory
import java.util.UUID
import io.greenbus.services.framework.{ Deleted, Created }
import io.greenbus.client.service.proto.Commands.CommandLock

@RunWith(classOf[JUnitRunner])
class CommandModelTest extends ServiceTestBase {
  import ModelTestHelpers._

  class Fixture {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("command01", Seq("Command"))
    val ent02 = createEntity("command02", Seq("Command"))
    val ent03 = createEntity("command03", Seq("Command"))
    val cmd01 = createCommand(ent01.id, "displayCommand01", CommandCategory.CONTROL)
    val cmd02 = createCommand(ent02.id, "displayCommand02", CommandCategory.CONTROL)
    val cmd03 = createCommand(ent03.id, "displayCommand03", CommandCategory.CONTROL)
    val agent01 = createAgent("agent01")
    val agent02 = createAgent("agent02")

    val allAgentIds = Seq(agent01, agent02).map(_.id)
    val allCmdIds = Seq(cmd01, cmd02, cmd03).map(_.entityId)

    def hasSelect(cmd: UUID, agent: UUID, shouldBe: Boolean) {
      SquerylCommandModel.agentHasSelect(cmd, agent) should equal(shouldBe)
    }
    def canIssue(cmd: UUID, agent: UUID, shouldBe: Boolean): Unit = {
      SquerylCommandModel.agentCanIssueCommand(cmd, agent) should equal(shouldBe)
    }

    def permutations[A](f: (UUID, UUID) => A) = {
      allAgentIds.foreach(a => allCmdIds.foreach(c => f(a, c)))
    }
  }

  test("Select") {
    val f = new Fixture
    import f._

    def allUnselected() {
      permutations((a, c) => hasSelect(c, a, false))
      permutations((a, c) => canIssue(c, a, true))
    }

    allUnselected()

    val select = SquerylCommandModel.selectCommands(notifier, Seq(cmd01.entityId, cmd02.entityId), agent01.id, Long.MaxValue)

    hasSelect(cmd01.entityId, agent01.id, true)
    hasSelect(cmd02.entityId, agent01.id, true)
    hasSelect(cmd03.entityId, agent01.id, false)
    hasSelect(cmd01.entityId, agent02.id, false)
    hasSelect(cmd02.entityId, agent02.id, false)
    hasSelect(cmd03.entityId, agent02.id, false)

    allCmdIds.foreach(c => canIssue(c, agent01.id, true))
    Seq(cmd01, cmd02).map(_.entityId).foreach(c => canIssue(c, agent02.id, false))
    canIssue(cmd03.entityId, agent02.id, true)

    val deleted = SquerylCommandModel.deleteLocks(notifier, Seq(select.getId.getValue.toLong))

    allUnselected()

    notifier.checkCommandLocks(Set(
      (Created, (CommandLock.AccessMode.ALLOWED, Set(cmd01.entityId, cmd02.entityId), agent01.id, Some(Long.MaxValue))),
      (Deleted, (CommandLock.AccessMode.ALLOWED, Set(cmd01.entityId, cmd02.entityId), agent01.id, Some(Long.MaxValue)))))
  }

  test("Select bars later selects") {
    val f = new Fixture
    import f._

    SquerylCommandModel.selectCommands(notifier, Seq(cmd01.entityId, cmd02.entityId), agent01.id, Long.MaxValue)

    intercept[CommandLockException] {
      SquerylCommandModel.selectCommands(notifier, Seq(cmd02.entityId, cmd03.entityId), agent02.id, Long.MaxValue)
    }

  }

  test("Block bars later select") {
    val f = new Fixture
    import f._

    val block = SquerylCommandModel.blockCommands(notifier, Seq(cmd01.entityId, cmd02.entityId), agent01.id)

    intercept[CommandLockException] {
      SquerylCommandModel.selectCommands(notifier, Seq(cmd02.entityId, cmd03.entityId), agent01.id, Long.MaxValue)
    }

    SquerylCommandModel.deleteLocks(notifier, Seq(block.getId.getValue.toLong))

    SquerylCommandModel.selectCommands(notifier, Seq(cmd02.entityId, cmd03.entityId), agent01.id, Long.MaxValue)

    hasSelect(cmd01.entityId, agent01.id, false)
    hasSelect(cmd02.entityId, agent01.id, true)
    hasSelect(cmd03.entityId, agent01.id, true)

    canIssue(cmd01.entityId, agent01.id, true)
    canIssue(cmd02.entityId, agent01.id, true)
    canIssue(cmd03.entityId, agent01.id, true)

    canIssue(cmd01.entityId, agent02.id, true)
    canIssue(cmd02.entityId, agent02.id, false)
    canIssue(cmd03.entityId, agent02.id, false)
  }

  test("Block occludes earlier select") {
    val f = new Fixture
    import f._

    SquerylCommandModel.selectCommands(notifier, Seq(cmd01.entityId, cmd02.entityId), agent01.id, Long.MaxValue)

    hasSelect(cmd01.entityId, agent01.id, true)
    hasSelect(cmd02.entityId, agent01.id, true)
    hasSelect(cmd03.entityId, agent01.id, false)

    canIssue(cmd01.entityId, agent01.id, true)
    canIssue(cmd02.entityId, agent01.id, true)
    canIssue(cmd03.entityId, agent01.id, true)

    canIssue(cmd01.entityId, agent02.id, false)
    canIssue(cmd02.entityId, agent02.id, false)
    canIssue(cmd03.entityId, agent02.id, true)

    SquerylCommandModel.blockCommands(notifier, Seq(cmd02.entityId, cmd03.entityId), agent01.id)

    hasSelect(cmd01.entityId, agent01.id, true)
    hasSelect(cmd02.entityId, agent01.id, false)
    hasSelect(cmd03.entityId, agent01.id, false)

    canIssue(cmd01.entityId, agent01.id, true)
    canIssue(cmd02.entityId, agent01.id, false)
    canIssue(cmd03.entityId, agent01.id, false)

    canIssue(cmd01.entityId, agent02.id, false)
    canIssue(cmd02.entityId, agent02.id, false)
    canIssue(cmd03.entityId, agent02.id, false)

    notifier.checkCommandLocks(Set(
      (Created, (CommandLock.AccessMode.ALLOWED, Set(cmd01.entityId, cmd02.entityId), agent01.id, Some(Long.MaxValue))),
      (Created, (CommandLock.AccessMode.BLOCKED, Set(cmd02.entityId, cmd03.entityId), agent01.id, None))))
  }

  test("Lock query") {
    val f = new Fixture
    import f._

    val select = SquerylCommandModel.selectCommands(notifier, Seq(cmd01.entityId, cmd02.entityId), agent01.id, Long.MaxValue)
    val block = SquerylCommandModel.blockCommands(notifier, Seq(cmd03.entityId), agent01.id)

    val lk1: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.ALLOWED, Set(cmd01.entityId, cmd02.entityId), agent01.id, Some(Long.MaxValue))
    val lk2: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.BLOCKED, Set(cmd03.entityId), agent01.id, None)

    check(Set(lk1)) {
      SquerylCommandModel.lockQuery(Seq(cmd01.entityId), Nil, None, None, 300)
    }
    check(Set(lk2)) {
      SquerylCommandModel.lockQuery(Seq(cmd03.entityId), Nil, None, None, 300)
    }
  }

  test("Lock query overlap") {
    val f = new Fixture
    import f._

    val select = SquerylCommandModel.selectCommands(notifier, Seq(cmd01.entityId, cmd02.entityId), agent01.id, Long.MaxValue)
    val block = SquerylCommandModel.blockCommands(notifier, Seq(cmd02.entityId, cmd03.entityId), agent01.id)

    val lk1: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.ALLOWED, Set(cmd01.entityId, cmd02.entityId), agent01.id, Some(Long.MaxValue))
    val lk2: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.BLOCKED, Set(cmd02.entityId, cmd03.entityId), agent01.id, None)

    check(Set(lk1, lk2)) {
      SquerylCommandModel.lockQuery(Seq(cmd02.entityId), Nil, None, None, 300)
    }
    check(Set(lk2)) {
      SquerylCommandModel.lockQuery(Seq(cmd02.entityId), Nil, Some(CommandLock.AccessMode.BLOCKED), None, 300)
    }
  }

  test("Lock query agents") {
    val f = new Fixture
    import f._

    val select = SquerylCommandModel.selectCommands(notifier, Seq(cmd01.entityId, cmd02.entityId), agent01.id, Long.MaxValue)
    val block = SquerylCommandModel.blockCommands(notifier, Seq(cmd02.entityId, cmd03.entityId), agent02.id)

    val lk1: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.ALLOWED, Set(cmd01.entityId, cmd02.entityId), agent01.id, Some(Long.MaxValue))
    val lk2: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.BLOCKED, Set(cmd02.entityId, cmd03.entityId), agent02.id, None)

    check(Set(lk1, lk2)) {
      SquerylCommandModel.lockQuery(Nil, Seq(agent01.id, agent02.id), None, None, 300)
    }
    check(Set(lk2)) {
      SquerylCommandModel.lockQuery(Nil, Seq(agent01.id, agent02.id), Some(CommandLock.AccessMode.BLOCKED), None, 300)
    }
    check(Set(lk1)) {
      SquerylCommandModel.lockQuery(Nil, Seq(agent01.id), None, None, 300)
    }
  }

  import UUIDHelpers._

  test("Lock query paging") {
    val f = new Fixture
    import f._

    val select1 = SquerylCommandModel.selectCommands(notifier, Seq(cmd01.entityId), agent01.id, Long.MaxValue)
    val select2 = SquerylCommandModel.selectCommands(notifier, Seq(cmd02.entityId), agent01.id, Long.MaxValue)
    val select3 = SquerylCommandModel.selectCommands(notifier, Seq(cmd03.entityId), agent01.id, Long.MaxValue)
    val block1 = SquerylCommandModel.blockCommands(notifier, Seq(cmd01.entityId), agent02.id)
    val block2 = SquerylCommandModel.blockCommands(notifier, Seq(cmd02.entityId), agent02.id)
    val block3 = SquerylCommandModel.blockCommands(notifier, Seq(cmd03.entityId), agent02.id)

    val lk1: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.ALLOWED, Set(cmd01.entityId), agent01.id, Some(Long.MaxValue))
    val lk2: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.ALLOWED, Set(cmd02.entityId), agent01.id, Some(Long.MaxValue))
    val lk3: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.ALLOWED, Set(cmd03.entityId), agent01.id, Some(Long.MaxValue))
    val lk4: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.BLOCKED, Set(cmd01.entityId), agent02.id, None)
    val lk5: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.BLOCKED, Set(cmd02.entityId), agent02.id, None)
    val lk6: (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = (CommandLock.AccessMode.BLOCKED, Set(cmd03.entityId), agent02.id, None)

    checkOrder(Seq(lk1, lk2)) {
      SquerylCommandModel.lockQuery(Nil, Nil, None, None, 2)
    }
    checkOrder(Seq(lk3, lk4)) {
      SquerylCommandModel.lockQuery(Nil, Nil, None, Some(protoIdToLong(select2.getId)), 2)
    }
    checkOrder(Seq(lk5, lk6)) {
      SquerylCommandModel.lockQuery(Nil, Nil, None, Some(protoIdToLong(block1.getId)), 2)
    }
  }

}
