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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.squeryl.PrimitiveTypeMode._
import io.greenbus.services.data.ServicesSchema
import io.greenbus.services.framework._
import io.greenbus.services.model.AuthModel.{ PermissionSetInfo, AgentInfo, CoreUntypedTemplate }

@RunWith(classOf[JUnitRunner])
class AuthModelTest extends ServiceTestBase {
  import io.greenbus.services.model.ModelTestHelpers._

  def checkPermissions(id: UUID, names: Set[String]) {
    import io.greenbus.services.data.ServicesSchema._
    val agentId = from(agents)(a => where(a.id === id) select (a.id)).single
    val setNames = from(agentSetJoins, permissionSets)((j, ps) =>
      where(j.agentId === agentId and ps.id === j.permissionSetId)
        select (ps.name)).toVector

    setNames.size should equal(names.size)
    setNames.toSet should equal(names)
  }
  def checkPermissions(agentName: String, names: Set[String]) {
    import io.greenbus.services.data.ServicesSchema._

    val agentId = from(agents)(a =>
      where(a.name === agentName)
        select (a.id)).single

    val permNames = from(agentSetJoins, permissionSets)((j, ps) =>
      where(j.agentId === agentId and ps.id === j.permissionSetId)
        select (ps.name)).toVector

    permNames.size should equal(names.size)
    permNames.toSet should equal(names)
  }

  class AuthFixture {
    val perm1 = createPermissionSet("perm01")
    val perm2 = createPermissionSet("perm02")
    val perm3 = createPermissionSet("perm03")
    val agent01 = createAgent("agent01", "pass01")
    val agent02 = createAgent("agent02")
    val agent03 = createAgent("agent03")
    addPermissionToAgent(perm1.id, agent01.id)
    addPermissionToAgent(perm2.id, agent01.id)
    addPermissionToAgent(perm1.id, agent02.id)
    addPermissionToAgent(perm3.id, agent02.id)
    addPermissionToAgent(perm2.id, agent03.id)
    addPermissionToAgent(perm3.id, agent03.id)
  }

  test("Simple login") {
    val f = new AuthFixture
    import f._

    val now = System.currentTimeMillis()
    val result = SquerylAuthModel.simpleLogin("agent01", "pass01", now + 5000, "ver01", "loc01")
    result.isRight should equal(true)
    val token = result.right.get._1

    val rows = ServicesSchema.authTokens.where(t => t.token === token).toList
    rows.size should equal(1)
    val row = rows.head

    row.agentId should equal(agent01.id)
    row.clientVersion should equal("ver01")
    row.loginLocation should equal("loc01")
    row.expirationTime should equal(now + 5000)
    row.revoked should equal(false)

    val joins = ServicesSchema.tokenSetJoins.where(t => t.authTokenId === row.id).toList
    joins.size should equal(2)
    joins.map(_.permissionSetId).toSet should equal(Set(perm1.id, perm2.id))
  }

  test("Login validation") {
    val f = new AuthFixture

    val now = System.currentTimeMillis()
    val result = SquerylAuthModel.simpleLogin("agent01", "pass01", now + 5000, "ver01", "loc01")
    result.isRight should equal(true)
    val token = result.right.get._1

    val validateResult = SquerylAuthModel.authValidate(token)
    validateResult should equal(true)
  }

  test("Login validation fail") {
    val f = new AuthFixture

    val now = System.currentTimeMillis()
    val result = SquerylAuthModel.simpleLogin("agent01", "pass01", now + 5000, "ver01", "loc01")
    result.isRight should equal(true)
    val token = result.right.get._1

    val validateResult = SquerylAuthModel.authValidate("crap")
    validateResult should equal(false)
  }

  test("Logout") {
    val f = new AuthFixture
    import f._

    val now = System.currentTimeMillis()
    val result = SquerylAuthModel.simpleLogin("agent01", "pass01", now + 5000, "ver01", "loc01")
    result.isRight should equal(true)
    val token = result.right.get._1

    SquerylAuthModel.simpleLogout(token)

    val rows = ServicesSchema.authTokens.where(t => t.token === token).toList
    rows.size should equal(1)
    val row = rows.head

    row.agentId should equal(agent01.id)
    row.clientVersion should equal("ver01")
    row.loginLocation should equal("loc01")
    row.expirationTime should equal(now + 5000)
    row.revoked should equal(true)

    val joins = ServicesSchema.tokenSetJoins.where(t => t.authTokenId === row.id).toList
    joins.size should equal(2)
    joins.map(_.permissionSetId).toSet should equal(Set(perm1.id, perm2.id))
  }

  test("Validate logged out") {
    val f = new AuthFixture

    val now = System.currentTimeMillis()
    val result = SquerylAuthModel.simpleLogin("agent01", "pass01", now + 5000, "ver01", "loc01")
    result.isRight should equal(true)
    val token = result.right.get._1

    SquerylAuthModel.simpleLogout(token)

    val validateResult = SquerylAuthModel.authValidate(token)
    validateResult should equal(false)
  }

  def createAgentAndEntity(setNameToId: Map[String, Long], name: String, types: Set[String], permissionSets: Set[String]) = {
    val agent01 = createAgent(name)
    permissionSets.foreach { setName =>
      val permId = setNameToId(setName)
      addPermissionToAgent(permId, agent01.id)
    }
  }

  test("Agent query") {
    val f = new AuthFixture

    val agentF = createAgent("agentF", "pass01")
    val agentE = createAgent("agentE")
    val agentD = createAgent("agentD")
    val perm4 = createPermissionSet("perm04")
    addPermissionToAgent(perm4.id, agentE.id)
    addPermissionToAgent(perm4.id, agentD.id)
    addPermissionToAgent(perm4.id, f.agent02.id)

    val p1 = ("agent01", Set("perm01", "perm02"))
    val p2 = ("agent02", Set("perm01", "perm03", "perm04"))
    val p3 = ("agent03", Set("perm02", "perm03"))
    val p4 = ("agentF", Set.empty[String])
    val p5 = ("agentE", Set("perm04"))
    val p6 = ("agentD", Set("perm04"))

    check(Set(p1, p2)) {
      SquerylAuthModel.agentQuery(None, Seq("perm01"), None, None, 100)
    }

    check(Set(p1, p2, p3)) {
      SquerylAuthModel.agentQuery(None, Seq("perm01", "perm03"), None, None, 100)
    }

    check(Set(p1, p2)) {
      SquerylAuthModel.agentQuery(None, Seq(), None, None, 2, pageByName = true)
    }
    check(Set(p3, p6)) {
      SquerylAuthModel.agentQuery(None, Seq(), None, lastName = Some("agent02"), 2, pageByName = true)
    }
    check(Set(p5, p4)) {
      SquerylAuthModel.agentQuery(None, Seq(), None, lastName = Some("agentD"), 2, pageByName = true)
    }

    check(Set(p2, p6)) {
      SquerylAuthModel.agentQuery(None, Seq("perm04"), None, lastName = None, 2, pageByName = true)
    }
    check(Set(p5)) {
      SquerylAuthModel.agentQuery(None, Seq("perm04"), None, lastName = Some("agentD"), 2, pageByName = true)
    }
  }

  test("Agent put") {
    val notifier = new TestModelNotifier
    createPermissionSet("perm01")
    createPermissionSet("perm02")

    val perms = Set("perm01", "perm02")

    val puts = Seq(CoreUntypedTemplate(Option.empty[UUID], "agent01", AgentInfo(Some("secret"), Seq("perm01", "perm02"))))

    val r1 = ("agent01", Set("perm01", "perm02"))
    val all = Set(r1)

    check(all) {
      SquerylAuthModel.putAgents(notifier, puts)
    }

    checkPermissions("agent01", perms)

    notifier.checkAgents(Set((Created, ("agent01", Set("perm01", "perm02")))))
  }

  test("Agent put swaps permissions") {
    val notifier = new TestModelNotifier
    createPermissionSet("perm01")
    createPermissionSet("perm02")

    {
      val puts = Seq(CoreUntypedTemplate(Option.empty[UUID], "agent01", AgentInfo(Some("secret"), Seq("perm01"))))

      val r1 = ("agent01", Set("perm01"))
      val all = Set(r1)

      check(all) {
        SquerylAuthModel.putAgents(notifier, puts)
      }
    }

    {
      val puts = Seq(CoreUntypedTemplate(Option.empty[UUID], "agent01", AgentInfo(Some("secret"), Seq("perm02"))))

      val r1 = ("agent01", Set("perm02"))
      val all = Set(r1)

      check(all) {
        SquerylAuthModel.putAgents(notifier, puts)
      }
    }

    notifier.checkAgents(Set(
      (Created, ("agent01", Set("perm01"))),
      (Updated, ("agent01", Set("perm02")))))
  }

  test("Agent put with non-existent permissions") {
    val notifier = new TestModelNotifier
    createPermissionSet("perm01")

    intercept[ModelInputException] {
      SquerylAuthModel.putAgents(notifier, Seq(CoreUntypedTemplate(Option.empty[UUID], "agent01", AgentInfo(Some("secret"), Seq("perm01", "nope")))))
    }
  }

  test("Agent put battery") {

    val notifier = new TestModelNotifier

    val perm1 = createPermissionSet("perm01")
    val perm2 = createPermissionSet("perm02")
    val perm3 = createPermissionSet("perm03")
    val agent01 = createAgent("agent01", "pass01")
    val agent02 = createAgent("agent02")
    addPermissionToAgent(perm1.id, agent01.id)
    addPermissionToAgent(perm2.id, agent01.id)
    addPermissionToAgent(perm1.id, agent02.id)
    addPermissionToAgent(perm3.id, agent02.id)

    val baseName = "agent"
    val uuid04 = UUID.randomUUID()

    val puts = Seq(
      CoreUntypedTemplate[UUID, AgentInfo](Some(agent01.id), s"${baseName}11", AgentInfo(Some("pass01"), Seq("perm01"))),
      CoreUntypedTemplate[UUID, AgentInfo](None, s"${baseName}02", AgentInfo(None, Seq("perm02", "perm03"))),
      CoreUntypedTemplate[UUID, AgentInfo](None, s"${baseName}03", AgentInfo(Some("pass02"), Seq("perm01", "perm03"))),
      CoreUntypedTemplate[UUID, AgentInfo](Some(uuid04), s"${baseName}04", AgentInfo(Some("pass44"), Seq("perm02"))))

    val p1 = (s"${baseName}11", Set("perm01"))
    val p2 = (s"${baseName}02", Set("perm02", "perm03"))
    val p3 = (s"${baseName}03", Set("perm01", "perm03"))
    val p4 = (s"${baseName}04", Set("perm02"))
    val all = Set(p1, p2, p3, p4)

    check(all) {
      SquerylAuthModel.putAgents(notifier, puts)
    }

    notifier.checkAgents(
      Set(
        (Updated, (s"${baseName}11", Set("perm01"))),
        (Updated, (s"${baseName}02", Set("perm02", "perm03"))),
        (Created, (s"${baseName}03", Set("perm01", "perm03"))),
        (Created, (s"${baseName}04", Set("perm02")))))
  }

  test("Agent delete") {
    val notifier = new TestModelNotifier
    val f = new AuthFixture
    import f._

    val toBeDeleted = Set(
      ("agent01", Set("perm01", "perm02")),
      ("agent02", Set("perm01", "perm03")))

    check(toBeDeleted) {
      SquerylAuthModel.deleteAgents(notifier, Seq(agent01.id, agent02.id))
    }

    val agents = ServicesSchema.agents.where(t => true === true).toSeq
    agents.size should equal(1)
    agents.head.id should equal(agent03.id)

    val setsFor03 = ServicesSchema.agentSetJoins.where(j => true === true).map(j => (j.agentId, j.permissionSetId))
    setsFor03.size should equal(2)
    setsFor03.toSet should equal(Set((agent03.id, perm2.id), (agent03.id, perm3.id)))

    notifier.checkAgents(Set(
      (Deleted, ("agent01", Set("perm01", "perm02"))),
      (Deleted, ("agent02", Set("perm01", "perm03")))))
  }

  test("Permission set query") {

    val perm1 = createPermissionSet("perm01")
    val perm2 = createPermissionSet("perm02")
    val perm3 = createPermissionSet("perm03")
    val perm4 = createPermissionSet("perm04")
    val perm5 = createPermissionSet("perm05")

    val emptyPerms = Set.empty[(Set[String], Set[String])]

    val p1 = ("perm01", emptyPerms)
    val p2 = ("perm02", emptyPerms)
    val p3 = ("perm03", emptyPerms)
    val p4 = ("perm04", emptyPerms)
    val p5 = ("perm05", emptyPerms)
    val all = Seq(p1, p2, p3, p4, p5)

    checkOrder(all) {
      SquerylAuthModel.permissionSetQuery(None, 100)
    }

    checkOrder(Seq(p1, p2)) {
      SquerylAuthModel.permissionSetQuery(None, 2)
    }
    checkOrder(Seq(p3, p4)) {
      SquerylAuthModel.permissionSetQuery(Some(perm2.id), 2)
    }
    checkOrder(Seq(p5)) {
      SquerylAuthModel.permissionSetQuery(Some(perm4.id), 2)
    }

  }

  test("Permission set put battery") {
    val notifier = new TestModelNotifier
    val baseName = "permissionSet"

    val perm3 = createPermissionSet(s"${baseName}03", Seq(createPermission("res03", "get")))
    val perm4 = createPermissionSet(s"${baseName}04", Seq(createPermission("res04", "get")))

    val puts = Seq(
      CoreUntypedTemplate[Long, PermissionSetInfo](None, s"${baseName}01", PermissionSetInfo(Seq(createPermission("res01", "get")))),
      CoreUntypedTemplate[Long, PermissionSetInfo](None, s"${baseName}02", PermissionSetInfo(Seq(createPermission("res02", "get")))),
      CoreUntypedTemplate[Long, PermissionSetInfo](None, s"${baseName}03", PermissionSetInfo(Seq(createPermission("res33", "get")))),
      CoreUntypedTemplate[Long, PermissionSetInfo](Some(perm4.id), s"${baseName}44", PermissionSetInfo(Seq(createPermission("res04", "delete")))))

    def simp(name: String, res: String, act: String) = (name, Set((Set(res), Set(act))))

    val p1 = simp(s"${baseName}01", "res01", "get")
    val p2 = simp(s"${baseName}02", "res02", "get")
    val p3 = simp(s"${baseName}03", "res33", "get")
    val p4 = simp(s"${baseName}44", "res04", "delete")
    val all = Set(p1, p2, p3, p4)

    check(all) {
      SquerylAuthModel.putPermissionSets(notifier, puts)
    }

    notifier.checkPermissionSets(
      Set(
        (Created, p1),
        (Created, p2),
        (Updated, p3),
        (Updated, p4)))
  }

  test("Permission set delete") {
    val notifier = new TestModelNotifier
    val f = new AuthFixture
    import f._

    val results = SquerylAuthModel.deletePermissionSets(notifier, Seq(perm1.id, perm2.id))
    results.size should equal(2)
    results.map(_.getName).toSet should equal(Set("perm01", "perm02"))

    val setsFor03 = ServicesSchema.agentSetJoins.where(j => true === true).map(j => (j.agentId, j.permissionSetId))
    setsFor03.size should equal(2)
    setsFor03.toSet should equal(Set((agent02.id, perm3.id), (agent03.id, perm3.id)))

    notifier.checkPermissionSetNames(Set(
      (Deleted, "perm01"),
      (Deleted, "perm02")))
  }

}
