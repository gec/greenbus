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
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.services.data._
import org.squeryl.PrimitiveTypeMode._
import UUIDHelpers._
import io.greenbus.services.framework._
import io.greenbus.client.service.proto.Model._
import io.greenbus.services.model.FrontEndModel._
import java.util.UUID
import org.scalatest.Matchers
import io.greenbus.services.model.EntityModel.TypeParams
import io.greenbus.client.service.proto.Model.ModelUUID

object EntityBasedTestHelpers extends Matchers {
  import ModelTestHelpers._

  def genericPutTest[A, TupType, InfoType, ProtoType](
    baseName: String,
    typ: String,
    dbCreates: Vector[UUID => A],
    targetTups: Vector[(String, Set[String]) => TupType],
    putInfos: Vector[InfoType],
    notificationCheck: (TestModelNotifier, Set[(ModelEvent, TupType)]) => Unit)(doCheck: (ModelNotifier, Set[TupType], Seq[CoreTypeTemplate[InfoType]]) => Unit) {

    val notifier = new TestModelNotifier

    val ent03 = createEntity(s"${baseName}03", Seq(typ))
    dbCreates(0)(ent03.id)
    val ent04 = createEntity(s"${baseName}04", Seq(typ))
    dbCreates(1)(ent04.id)
    val ent05 = createEntity(s"${baseName}05", Seq(typ))
    dbCreates(2)(ent05.id)
    val ent06 = createEntity(s"${baseName}06", Seq(typ))
    dbCreates(3)(ent06.id)

    val p1 = targetTups(0)(s"${baseName}01", Set(typ))
    val p2 = targetTups(1)(s"${baseName}02", Set(typ, "AnotherType"))
    val p3 = targetTups(2)(s"${baseName}03", Set(typ))
    val p4 = targetTups(3)(s"${baseName}44", Set(typ))
    val p5 = targetTups(4)(s"${baseName}05", Set(typ, "Type05"))
    val p6 = targetTups(5)(s"${baseName}06", Set(typ))
    val all = Set(p1, p2, p3, p4, p5, p6)

    val puts = Seq(
      CoreTypeTemplate(None, s"${baseName}01", Set.empty[String], putInfos(0)),
      CoreTypeTemplate(None, s"${baseName}02", Set("AnotherType"), putInfos(1)),
      CoreTypeTemplate(None, s"${baseName}03", Set.empty[String], putInfos(2)),
      CoreTypeTemplate(Some(ent04.id), s"${baseName}44", Set.empty[String], putInfos(3)),
      CoreTypeTemplate(Some(ent05.id), s"${baseName}05", Set("Type05"), putInfos(4)),
      CoreTypeTemplate(Some(ent06.id), s"${baseName}06", Set.empty[String], putInfos(5)))

    doCheck(notifier, all, puts)

    val correctEnts = Set((s"${baseName}01", Set(typ)),
      (s"${baseName}02", Set(typ, "AnotherType")),
      (s"${baseName}03", Set(typ)),
      (s"${baseName}44", Set(typ)),
      (s"${baseName}05", Set(typ, "Type05")),
      (s"${baseName}06", Set(typ)))
    val allEnts = SquerylEntityModel.allQuery(None, 100).map(simplify)
    val typEnts = allEnts.filter(_._2.contains(typ))
    typEnts.size should equal(correctEnts.size)
    typEnts.toSet should equal(correctEnts)

    notifier.checkEntities(Set(
      (Created, (s"${baseName}01", Set(typ))),
      (Created, (s"${baseName}02", Set(typ, "AnotherType"))),
      (Updated, (s"${baseName}44", Set(typ))),
      (Updated, (s"${baseName}05", Set(typ, "Type05")))))
    notificationCheck(notifier, Set(
      (Created, p1),
      (Created, p2),
      (Updated, p3),
      (Updated, p4),
      (Updated, p5),
      (Updated, p6)))
  }

  class EntityBasedQueryFixture[Simplified, Proto](
      namePrefix: String,
      typ: String,
      vec: Vector[(String, Set[String]) => Simplified],
      create: Simplified => Unit,
      entQuery: (TypeParams, Option[UUID], Option[String], Int, Boolean) => Seq[Proto],
      getUuid: Proto => ModelUUID,
      getName: Proto => String,
      simplify: Proto => Simplified) {

    val p1 = vec(0)(namePrefix + "01", Set(typ))
    val p2 = vec(1)(namePrefix + "02", Set(typ, "TypeA", "TypeB"))
    val p3 = vec(2)(namePrefix + "03", Set(typ))
    val p4 = vec(3)(namePrefix + "04", Set(typ, "TypeA"))
    val p5 = vec(4)(namePrefix + "05", Set(typ))
    val p6 = vec(5)(namePrefix + "06", Set(typ, "TypeA"))
    val all = Set(p1, p2, p3, p4, p5, p6)

    all.foreach(create)

    def entityBasedTests() {
      implicit val simp = simplify

      check(all) {
        entQuery(TypeParams(Nil, Nil, Nil), None, None, 100, true)
      }

      check(Set(p2, p4, p6)) {
        entQuery(TypeParams(Seq("TypeA"), Nil, Nil), None, None, 100, true)
      }
      check(Set(p1, p3, p5)) {
        entQuery(TypeParams(Nil, Nil, Seq("TypeA")), None, None, 100, true)
      }
      check(Set(p2, p4, p6)) {
        entQuery(TypeParams(Nil, Seq(typ, "TypeA"), Nil), None, None, 100, true)
      }
      check(Set(p2)) {
        entQuery(TypeParams(Nil, Seq(typ, "TypeA", "TypeB"), Nil), None, None, 100, true)
      }

      {
        val q1 = entQuery(TypeParams(Nil, Nil, Nil), None, None, 2, false)
        val q2 = entQuery(TypeParams(Nil, Nil, Nil), Some(getUuid(q1.last)), None, 2, false)
        val q3 = entQuery(TypeParams(Nil, Nil, Nil), Some(getUuid(q2.last)), None, 100, false)
        val results = q1 ++ q2 ++ q3

        results.size should equal(all.size)
        results.map(simplify).toSet should equal(all)
      }

      {
        val q1 = entQuery(TypeParams(Nil, Nil, Nil), None, None, 2, false)
        val q2 = entQuery(TypeParams(Nil, Nil, Nil), None, Some(getName(q1.last)), 2, false)
        val q3 = entQuery(TypeParams(Nil, Nil, Nil), None, Some(getName(q2.last)), 100, false)
        val results = q1 ++ q2 ++ q3

        results.size should equal(all.size)
        results.map(simplify).toSet should equal(all)
      }

      {
        val n1 = entQuery(TypeParams(Nil, Nil, Nil), None, None, 2, true)
        val n2 = entQuery(TypeParams(Nil, Nil, Nil), None, Some(getName(n1.last)), 2, true)
        val n3 = entQuery(TypeParams(Nil, Nil, Nil), None, Some(getName(n2.last)), 100, true)

        n1.map(simplify) should equal(Seq(p1, p2))
        n2.map(simplify) should equal(Seq(p3, p4))
        n3.map(simplify) should equal(Seq(p5, p6))
      }

      {
        val n1 = entQuery(TypeParams(Nil, Nil, Nil), None, None, 2, true)
        val n2 = entQuery(TypeParams(Nil, Nil, Nil), Some(getUuid(n1.last)), None, 2, true)
        val n3 = entQuery(TypeParams(Nil, Nil, Nil), Some(getUuid(n2.last)), None, 100, true)

        n1.map(simplify) should equal(Seq(p1, p2))
        n2.map(simplify) should equal(Seq(p3, p4))
        n3.map(simplify) should equal(Seq(p5, p6))
      }
    }

  }
}

@RunWith(classOf[JUnitRunner])
class FrontEndModelTest extends ServiceTestBase {
  import ModelTestHelpers._
  import EntityBasedTestHelpers._

  test("Point get") {
    val ent01 = createEntity("point01", Seq("Point"))
    val ent02 = createEntity("point02", Seq("Point"))
    val ent03 = createEntity("point03", Seq("Point"))
    val pt01 = createPoint(ent01.id, PointCategory.ANALOG, "unit1")
    val pt02 = createPoint(ent02.id, PointCategory.COUNTER, "unit2")
    val pt03 = createPoint(ent03.id, PointCategory.STATUS, "unit3")

    val p1 = ("point01", Set("Point"), PointCategory.ANALOG, "unit1")
    val p2 = ("point02", Set("Point"), PointCategory.COUNTER, "unit2")
    val p3 = ("point03", Set("Point"), PointCategory.STATUS, "unit3")
    val all = Set(p1, p2, p3)

    check(all) {
      SquerylFrontEndModel.pointKeyQuery(Seq(ent01.id, ent02.id, ent03.id), Nil)
    }

    check(all) {
      SquerylFrontEndModel.pointKeyQuery(Seq(ent01.id, ent02.id), Seq("point03"))
    }

    check(Set(p1)) {
      SquerylFrontEndModel.pointKeyQuery(Seq(ent01.id), Nil)
    }
  }

  def createPointAndEntity(name: String, types: Set[String], pointCategory: PointCategory, unit: String) = {
    val ent01 = createEntity(name, types.toSeq)
    val pt01 = createPoint(ent01.id, pointCategory, unit)
  }

  test("Point query") {

    type TupleType = (String, Set[String], PointCategory, String)

    def toUuid(proto: Point): ModelUUID = proto.getUuid
    def toName(proto: Point): String = proto.getName

    val data = Vector[(String, Set[String]) => TupleType](
      (_, _, PointCategory.ANALOG, "unit1"),
      (_, _, PointCategory.COUNTER, "unit2"),
      (_, _, PointCategory.STATUS, "unit3"),
      (_, _, PointCategory.ANALOG, "unit2"),
      (_, _, PointCategory.COUNTER, "unit3"),
      (_, _, PointCategory.STATUS, "unit1"))

    val f = new EntityBasedQueryFixture[TupleType, Point](
      "point",
      "Point",
      data,
      Function.tupled(createPointAndEntity _),
      SquerylFrontEndModel.pointQuery(Nil, Nil, _, _, _, _, _),
      toUuid,
      toName,
      simplify)

    import f._

    entityBasedTests()

    check(Set(p1, p4)) {
      SquerylFrontEndModel.pointQuery(Seq(PointCategory.ANALOG), Nil, TypeParams(Nil, Nil, Nil), None, None, 100)
    }
    check(Set(p1, p2, p4, p5)) {
      SquerylFrontEndModel.pointQuery(Seq(PointCategory.ANALOG, PointCategory.COUNTER), Nil, TypeParams(Nil, Nil, Nil), None, None, 100)
    }

    check(Set(p1, p6)) {
      SquerylFrontEndModel.pointQuery(Nil, Seq("unit1"), TypeParams(Nil, Nil, Nil), None, None, 100)
    }
    check(Set(p1, p2, p4, p6)) {
      SquerylFrontEndModel.pointQuery(Nil, Seq("unit1", "unit2"), TypeParams(Nil, Nil, Nil), None, None, 100)
    }
  }

  test("Point put") {

    val creates = Vector[UUID => PointRow](
      createPoint(_, PointCategory.ANALOG, "unit100"),
      createPoint(_, PointCategory.ANALOG, "unit14"),
      createPoint(_, PointCategory.ANALOG, "unit15"),
      createPoint(_, PointCategory.ANALOG, "unit16"))

    val targetTups = Vector[(String, Set[String]) => (String, Set[String], PointCategory, String)](
      (_, _, PointCategory.ANALOG, "unit1"),
      (_, _, PointCategory.COUNTER, "unit2"),
      (_, _, PointCategory.STATUS, "unit3"),
      (_, _, PointCategory.ANALOG, "unit14"),
      (_, _, PointCategory.ANALOG, "unit15"),
      (_, _, PointCategory.COUNTER, "unit66"))

    val putInfos = Vector(
      PointInfo(PointCategory.ANALOG, "unit1"),
      PointInfo(PointCategory.COUNTER, "unit2"),
      PointInfo(PointCategory.STATUS, "unit3"),
      PointInfo(PointCategory.ANALOG, "unit14"),
      PointInfo(PointCategory.ANALOG, "unit15"),
      PointInfo(PointCategory.COUNTER, "unit66"))

    def notifyCheck(notifier: TestModelNotifier, set: Set[(ModelEvent, (String, Set[String], PointCategory, String))]) {
      notifier.checkPoints(set)
    }

    genericPutTest(
      "point",
      "Point",
      creates,
      targetTups,
      putInfos,
      notifyCheck) { (notifier, all, puts) =>

        check(all) {
          SquerylFrontEndModel.putPoints(notifier, puts)
        }
      }
  }

  test("Point put to non-point entity") {
    val ent01 = createEntity("point01", Seq("AnotherType"))

    intercept[ModelInputException] {
      SquerylFrontEndModel.putPoints(NullModelNotifier, Seq(CoreTypeTemplate(None, "point01", Set.empty[String], PointInfo(PointCategory.ANALOG, "unit1"))))
    }
  }

  test("Point type can't be added through entity service") {
    EntityModelTest.bannedTypeTest("Point")
  }

  test("Point put cannot remove point type") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("point01", Seq("Point", "AnotherType"))
    val pt01 = createPoint(ent01.id, PointCategory.ANALOG, "unit100")

    val p1 = ("point01", Set("Point"), PointCategory.ANALOG, "unit1")

    val puts = Seq(
      CoreTypeTemplate(None, "point01", Set.empty[String], PointInfo(PointCategory.ANALOG, "unit1")))

    check(Set(p1)) {
      SquerylFrontEndModel.putPoints(notifier, puts)
    }
    notifier.checkEntities(Set(
      (Updated, ("point01", Set("Point")))))
    notifier.checkPoints(Set(
      (Updated, p1)))
  }

  test("Point delete") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("point01", Seq("Point"))
    val ent02 = createEntity("point02", Seq("Point"))
    val ent03 = createEntity("point03", Seq("Point"))
    val pt01 = createPoint(ent01.id, PointCategory.ANALOG, "unit1")
    val pt02 = createPoint(ent02.id, PointCategory.COUNTER, "unit2")
    val pt03 = createPoint(ent03.id, PointCategory.STATUS, "unit3")

    val p1 = ("point01", Set("Point"), PointCategory.ANALOG, "unit1")
    val p2 = ("point02", Set("Point"), PointCategory.COUNTER, "unit2")
    val p3 = ("point03", Set("Point"), PointCategory.STATUS, "unit3")
    val all = Set(p1, p2, p3)

    check(Set(p2, p3)) {
      SquerylFrontEndModel.deletePoints(notifier, Seq(ent02.id, ent03.id))
    }

    val correctEnts = Set(("point01", Set("Point")))
    val allEnts = SquerylEntityModel.allQuery(None, 100).map(simplify)
    allEnts.size should equal(correctEnts.size)
    allEnts.toSet should equal(correctEnts)

    val allPoints = ServicesSchema.points.where(t => true === true).toSeq
    allPoints.size should equal(1)
    allPoints.head.id should equal(pt01.id)

    notifier.checkEntities(Set(
      (Deleted, ("point02", Set("Point"))),
      (Deleted, ("point03", Set("Point")))))
    notifier.checkPoints(Set(
      (Deleted, p2),
      (Deleted, p3)))
  }

  test("Command get") {
    val ent01 = createEntity("command01", Seq("Command"))
    val ent02 = createEntity("command02", Seq("Command"))
    val ent03 = createEntity("command03", Seq("Command"))
    val cmd01 = createCommand(ent01.id, "displayCommand01", CommandCategory.SETPOINT_INT)
    val cmd02 = createCommand(ent02.id, "displayCommand02", CommandCategory.SETPOINT_DOUBLE)
    val cmd03 = createCommand(ent03.id, "displayCommand03", CommandCategory.CONTROL)

    val c1 = ("command01", Set("Command"), "displayCommand01", CommandCategory.SETPOINT_INT)
    val c2 = ("command02", Set("Command"), "displayCommand02", CommandCategory.SETPOINT_DOUBLE)
    val c3 = ("command03", Set("Command"), "displayCommand03", CommandCategory.CONTROL)
    val all = Set(c1, c2, c3)

    check(all) {
      SquerylFrontEndModel.commandKeyQuery(Seq(ent01.id, ent02.id, ent03.id), Nil)
    }

    check(all) {
      SquerylFrontEndModel.commandKeyQuery(Nil, Seq("command01", "command02", "command03"))
    }

    check(all) {
      SquerylFrontEndModel.commandKeyQuery(Seq(ent01.id, ent02.id), Seq("command03"))
    }

    check(Set(c1, c3)) {
      SquerylFrontEndModel.commandKeyQuery(Seq(ent01.id), Seq("command03"))
    }
  }

  def createCommandAndEntity(name: String, types: Set[String], displayName: String, commandCategory: CommandCategory) = {
    val ent01 = createEntity(name, types.toSeq)
    val pt01 = createCommand(ent01.id, displayName, commandCategory)
  }

  test("Command query") {

    type TupleType = (String, Set[String], String, CommandCategory)

    def toUuid(proto: Command): ModelUUID = proto.getUuid
    def toName(proto: Command): String = proto.getName

    val data = Vector[(String, Set[String]) => TupleType](
      (_, _, "diplay01", CommandCategory.CONTROL),
      (_, _, "diplay02", CommandCategory.SETPOINT_DOUBLE),
      (_, _, "diplay03", CommandCategory.SETPOINT_INT),
      (_, _, "diplay04", CommandCategory.CONTROL),
      (_, _, "diplay05", CommandCategory.CONTROL),
      (_, _, "diplay06", CommandCategory.SETPOINT_INT))

    val f = new EntityBasedQueryFixture[TupleType, Command](
      "command",
      "Command",
      data,
      Function.tupled(createCommandAndEntity _),
      SquerylFrontEndModel.commandQuery(Nil, _, _, _, _, _),
      toUuid,
      toName,
      simplify)

    import f._

    entityBasedTests()

    check(Set(p1, p4, p5)) {
      SquerylFrontEndModel.commandQuery(Seq(CommandCategory.CONTROL), TypeParams(Nil, Nil, Nil), None, None, 100)
    }

    check(Set(p2, p3, p6)) {
      SquerylFrontEndModel.commandQuery(Seq(CommandCategory.SETPOINT_DOUBLE, CommandCategory.SETPOINT_INT), TypeParams(Nil, Nil, Nil), None, None, 100)
    }
  }

  test("Command put") {

    val creates = Vector[UUID => CommandRow](
      createCommand(_, "display03", CommandCategory.CONTROL),
      createCommand(_, "display04", CommandCategory.CONTROL),
      createCommand(_, "display05", CommandCategory.CONTROL),
      createCommand(_, "display06", CommandCategory.CONTROL))

    val targetTups = Vector[(String, Set[String]) => (String, Set[String], String, CommandCategory)](
      (_, _, "display01", CommandCategory.SETPOINT_INT),
      (_, _, "display02", CommandCategory.SETPOINT_DOUBLE),
      (_, _, "display03", CommandCategory.SETPOINT_STRING),
      (_, _, "display04", CommandCategory.CONTROL),
      (_, _, "display05", CommandCategory.CONTROL),
      (_, _, "display66", CommandCategory.SETPOINT_DOUBLE))

    val putInfos = Vector(
      CommandInfo("display01", CommandCategory.SETPOINT_INT),
      CommandInfo("display02", CommandCategory.SETPOINT_DOUBLE),
      CommandInfo("display03", CommandCategory.SETPOINT_STRING),
      CommandInfo("display04", CommandCategory.CONTROL),
      CommandInfo("display05", CommandCategory.CONTROL),
      CommandInfo("display66", CommandCategory.SETPOINT_DOUBLE))

    def notifyCheck(notifier: TestModelNotifier, set: Set[(ModelEvent, (String, Set[String], String, CommandCategory))]) {
      notifier.checkCommands(set)
    }

    genericPutTest(
      "command",
      "Command",
      creates,
      targetTups,
      putInfos,
      notifyCheck) { (notifier, all, puts) =>

        check(all) {
          SquerylFrontEndModel.putCommands(notifier, puts)
        }
      }
  }

  test("Command type can't be added through entity service") {
    EntityModelTest.bannedTypeTest("Command")
  }

  test("Command delete") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("command01", Seq("Command"))
    val ent02 = createEntity("command02", Seq("Command"))
    val ent03 = createEntity("command03", Seq("Command"))
    val cmd01 = createCommand(ent01.id, "displayCommand01", CommandCategory.SETPOINT_INT)
    val cmd02 = createCommand(ent02.id, "displayCommand02", CommandCategory.SETPOINT_DOUBLE)
    val cmd03 = createCommand(ent03.id, "displayCommand03", CommandCategory.CONTROL)

    val c1 = ("command01", Set("Command"), "displayCommand01", CommandCategory.SETPOINT_INT)
    val c2 = ("command02", Set("Command"), "displayCommand02", CommandCategory.SETPOINT_DOUBLE)
    val c3 = ("command03", Set("Command"), "displayCommand03", CommandCategory.CONTROL)
    val all = Set(c1, c2, c3)

    check(Set(c2, c3)) {
      SquerylFrontEndModel.deleteCommands(notifier, Seq(ent02.id, ent03.id))
    }

    val correctEnts = Set(("command01", Set("Command")))
    val allEnts = SquerylEntityModel.allQuery(None, 100).map(simplify)
    allEnts.size should equal(correctEnts.size)
    allEnts.toSet should equal(correctEnts)

    val allPoints = ServicesSchema.commands.where(t => true === true).toSeq
    allPoints.size should equal(1)
    allPoints.head.id should equal(cmd01.id)

    notifier.checkEntities(Set(
      (Deleted, ("command02", Set("Command"))),
      (Deleted, ("command03", Set("Command")))))
    notifier.checkCommands(Set(
      (Deleted, c2),
      (Deleted, c3)))
  }

  test("Endpoint get") {
    val ent01 = createEntity("endpoint01", Seq("Endpoint"))
    val ent02 = createEntity("endpoint02", Seq("Endpoint"))
    val ent03 = createEntity("endpoint03", Seq("Endpoint"))
    val end01 = createEndpoint(ent01.id, "protocol01", false)
    val end02 = createEndpoint(ent02.id, "protocol02", false)
    val end03 = createEndpoint(ent03.id, "protocol03", false)

    val c1 = ("endpoint01", Set("Endpoint"), "protocol01", false)
    val c2 = ("endpoint02", Set("Endpoint"), "protocol02", false)
    val c3 = ("endpoint03", Set("Endpoint"), "protocol03", false)
    val all = Set(c1, c2, c3)

    check(all) {
      SquerylFrontEndModel.endpointKeyQuery(Seq(ent01.id, ent02.id, ent03.id), Nil)
    }

    check(all) {
      SquerylFrontEndModel.endpointKeyQuery(Nil, Seq("endpoint01", "endpoint02", "endpoint03"))
    }

    check(all) {
      SquerylFrontEndModel.endpointKeyQuery(Seq(ent01.id, ent02.id), Seq("endpoint03"))
    }

    check(Set(c1, c3)) {
      SquerylFrontEndModel.endpointKeyQuery(Seq(ent01.id), Seq("endpoint03"))
    }
  }

  private def createEntAndEndpoint(name: String, protocol: String): EntityRow = {
    val ent = createEntity(name, Seq("Endpoint"))
    createEndpoint(ent.id, protocol, false)
    ent
  }

  def createEndpointAndEntity(name: String, types: Set[String], protocol: String, disabled: Boolean) = {
    val ent01 = createEntity(name, types.toSeq)
    val pt01 = createEndpoint(ent01.id, protocol, disabled)
  }

  test("Endpoint query") {

    type TupleType = (String, Set[String], String, Boolean)

    def toUuid(proto: Endpoint): ModelUUID = proto.getUuid
    def toName(proto: Endpoint): String = proto.getName

    val data = Vector[(String, Set[String]) => TupleType](
      (_, _, "protocolA", false),
      (_, _, "protocolB", true),
      (_, _, "protocolC", false),
      (_, _, "protocolC", false),
      (_, _, "protocolA", true),
      (_, _, "protocolB", true))

    val f = new EntityBasedQueryFixture[TupleType, Endpoint](
      "endpoint",
      "Endpoint",
      data,
      Function.tupled(createEndpointAndEntity _),
      SquerylFrontEndModel.endpointQuery(Nil, None, _, _, _, _, _),
      toUuid,
      toName,
      simplify)

    import f._

    entityBasedTests()

    check(Set(p1, p5)) {
      SquerylFrontEndModel.endpointQuery(Seq("protocolA"), None, TypeParams(Nil, Nil, Nil), None, None, 100)
    }
    check(Set(p1, p2, p5, p6)) {
      SquerylFrontEndModel.endpointQuery(Seq("protocolA", "protocolB"), None, TypeParams(Nil, Nil, Nil), None, None, 100)
    }

    check(Set(p1, p3, p4)) {
      SquerylFrontEndModel.endpointQuery(Nil, Some(false), TypeParams(Nil, Nil, Nil), None, None, 100)
    }
    check(Set(p5)) {
      SquerylFrontEndModel.endpointQuery(Seq("protocolA"), Some(true), TypeParams(Nil, Nil, Nil), None, None, 100)
    }
  }

  test("Endpoint put") {

    val creates = Vector[UUID => EndpointRow](
      createEndpoint(_, "protocol100", false),
      createEndpoint(_, "protocol04", false),
      createEndpoint(_, "protocol05", false),
      createEndpoint(_, "protocol06", false))

    val targetTups = Vector[(String, Set[String]) => (String, Set[String], String, Boolean)](
      (_, _, "protocol01", false),
      (_, _, "protocol02", false),
      (_, _, "protocol03", false),
      (_, _, "protocol04", false),
      (_, _, "protocol55", false),
      (_, _, "protocol66", false))

    val putInfos = Vector(
      EndpointInfo("protocol01", None),
      EndpointInfo("protocol02", None),
      EndpointInfo("protocol03", None),
      EndpointInfo("protocol04", None),
      EndpointInfo("protocol55", None),
      EndpointInfo("protocol66", None))

    def notifyCheck(notifier: TestModelNotifier, set: Set[(ModelEvent, (String, Set[String], String, Boolean))]) {
      notifier.checkEndpoints(set)
    }

    genericPutTest(
      "endpoint",
      "Endpoint",
      creates,
      targetTups,
      putInfos,
      notifyCheck) { (notifier, all, puts) =>

        check(all) {
          SquerylFrontEndModel.putEndpoints(notifier, puts)
        }
      }
  }

  test("Endpoint type can't be added through entity service") {
    EntityModelTest.bannedTypeTest("Endpoint")
  }

  test("Endpoint delete") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("endpoint01", Seq("Endpoint"))
    val ent02 = createEntity("endpoint02", Seq("Endpoint"))
    val ent03 = createEntity("endpoint03", Seq("Endpoint"))
    val cmd01 = createEndpoint(ent01.id, "protocol01", false)
    val cmd02 = createEndpoint(ent02.id, "protocol02", false)
    val cmd03 = createEndpoint(ent03.id, "protocol03", false)

    val c1 = ("endpoint01", Set("Endpoint"), "protocol01", false)
    val c2 = ("endpoint02", Set("Endpoint"), "protocol02", false)
    val c3 = ("endpoint03", Set("Endpoint"), "protocol03", false)
    val all = Set(c1, c2, c3)

    check(Set(c2, c3)) {
      SquerylFrontEndModel.deleteEndpoints(notifier, Seq(ent02.id, ent03.id))
    }

    val correctEnts = Set(("endpoint01", Set("Endpoint")))
    val allEnts = SquerylEntityModel.allQuery(None, 100).map(simplify)
    allEnts.size should equal(correctEnts.size)
    allEnts.toSet should equal(correctEnts)

    val allPoints = ServicesSchema.endpoints.where(t => true === true).toSeq
    allPoints.size should equal(1)
    allPoints.head.id should equal(cmd01.id)

    notifier.checkEntities(Set(
      (Deleted, ("endpoint02", Set("Endpoint"))),
      (Deleted, ("endpoint03", Set("Endpoint")))))
    notifier.checkEndpoints(Set(
      (Deleted, c2),
      (Deleted, c3)))
  }

  test("Endpoint put disabled") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("endpoint01", Seq("Endpoint"))
    val ent02 = createEntity("endpoint02", Seq("Endpoint"))
    val ent03 = createEntity("endpoint03", Seq("Endpoint"))
    val cmd01 = createEndpoint(ent01.id, "protocol01", false)
    val cmd02 = createEndpoint(ent02.id, "protocol02", true)
    val cmd03 = createEndpoint(ent03.id, "protocol03", false)

    val c1 = ("endpoint01", Set("Endpoint"), "protocol01", true)
    val c2 = ("endpoint02", Set("Endpoint"), "protocol02", false)
    val c3 = ("endpoint03", Set("Endpoint"), "protocol03", false)
    val all = Set(c1, c2, c3)

    val puts = Seq(EndpointDisabledUpdate(ent01.id, true), EndpointDisabledUpdate(ent02.id, false), EndpointDisabledUpdate(ent03.id, false))

    check(all) {
      SquerylFrontEndModel.putEndpointsDisabled(notifier, puts)
    }

    notifier.checkEndpoints(Set(
      (Updated, c1),
      (Updated, c2)))
  }

  test("Comm status get (empty)") {
    val ent01 = createEntAndEndpoint("endpoint01", "protocol01")
    val ent02 = createEntAndEndpoint("endpoint02", "protocol01")

    check(Set.empty[(String, FrontEndConnectionStatus.Status)]) {
      SquerylFrontEndModel.getFrontEndConnectionStatuses(List(ent01.id, ent02.id), Nil)
    }
  }

  test("Comm status get") {
    val ent01 = createEntAndEndpoint("endpoint01", "protocol01")
    val ent02 = createEntAndEndpoint("endpoint02", "protocol01")
    createConnectionStatus(ent01.id, FrontEndConnectionStatus.Status.COMMS_DOWN, 1)
    createConnectionStatus(ent02.id, FrontEndConnectionStatus.Status.COMMS_UP, 2)

    val m1 = ("endpoint01", FrontEndConnectionStatus.Status.COMMS_DOWN)
    val m2 = ("endpoint02", FrontEndConnectionStatus.Status.COMMS_UP)
    val all = Set(m1, m2)

    check(all) {
      SquerylFrontEndModel.getFrontEndConnectionStatuses(List(ent01.id, ent02.id), Nil)
    }
  }

  test("Comm status put (empty)") {
    val notifier = new TestModelNotifier
    val ent01 = createEntAndEndpoint("endpoint01", "protocol01")
    val ent02 = createEntAndEndpoint("endpoint02", "protocol01")

    val puts = List(
      (ent01.id, FrontEndConnectionStatus.Status.COMMS_DOWN),
      (ent02.id, FrontEndConnectionStatus.Status.COMMS_UP))

    val m1 = ("endpoint01", FrontEndConnectionStatus.Status.COMMS_DOWN)
    val m2 = ("endpoint02", FrontEndConnectionStatus.Status.COMMS_UP)
    val all = Set(m1, m2)

    check(all) {
      SquerylFrontEndModel.putFrontEndConnectionStatuses(notifier, puts)
    }

    notifier.checkConnectionStatus(
      Set((Updated, m1),
        (Updated, m2)))
  }

  test("Comm status put") {
    val notifier = new TestModelNotifier
    val ent01 = createEntAndEndpoint("endpoint01", "protocol01")
    val ent02 = createEntAndEndpoint("endpoint02", "protocol01")
    createConnectionStatus(ent01.id, FrontEndConnectionStatus.Status.COMMS_DOWN, 1)
    createConnectionStatus(ent02.id, FrontEndConnectionStatus.Status.COMMS_UP, 2)

    val puts = List(
      (ent01.id, FrontEndConnectionStatus.Status.COMMS_UP),
      (ent02.id, FrontEndConnectionStatus.Status.ERROR))

    val m1 = ("endpoint01", FrontEndConnectionStatus.Status.COMMS_UP)
    val m2 = ("endpoint02", FrontEndConnectionStatus.Status.ERROR)
    val all = Set(m1, m2)

    check(all) {
      SquerylFrontEndModel.putFrontEndConnectionStatuses(notifier, puts)
    }

    notifier.checkConnectionStatus(
      Set((Updated, m1),
        (Updated, m2)))
  }
}
