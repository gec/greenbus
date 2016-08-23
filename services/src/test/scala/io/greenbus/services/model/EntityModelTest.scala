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

import io.greenbus.client.service.proto.Model.{ Entity, EntityEdge, EntityKeyValue, StoredValue }
import io.greenbus.services.authz.EntityFilter
import io.greenbus.services.data.{ EntityEdgeRow, EntityRow, EntityTypeRow, ServicesSchema }
import io.greenbus.services.framework._
import io.greenbus.services.model.EntityModel._
import io.greenbus.services.model.UUIDHelpers._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.squeryl.PrimitiveTypeMode._
import org.squeryl.Query

import scala.collection.JavaConversions._

object EntityModelTest {
  import ModelTestHelpers._

  def bannedTypeTest(banned: String) {

    val ent01 = createEntity("ent02", Seq("typeA"))

    val ent03 = createEntity("ent03", Seq("typeC", banned))

    intercept[ModelInputException] {
      SquerylEntityModel.putEntities(NullModelNotifier, Nil, Seq(("ent01", Set("typeB", banned))))
    }

    intercept[ModelInputException] {
      SquerylEntityModel.putEntities(NullModelNotifier, Nil, Seq(("ent02", Set("typeA", banned))))
    }
    intercept[ModelInputException] {
      SquerylEntityModel.putEntities(NullModelNotifier, Seq((ent01.id, "ent02", Set("typeA", banned))), Nil)
    }

    intercept[ModelInputException] {
      SquerylEntityModel.deleteEntities(NullModelNotifier, Seq(ent03.id))
    }
  }
}

@RunWith(classOf[JUnitRunner])
class EntityModelTest extends ServiceTestBase {
  import ModelTestHelpers._

  def checkEdges(correct: Set[(UUID, UUID, String, Int)])(query: => Seq[EntityEdge]) {
    val simplified = query.map(simplify)

    def debugName(uuid: UUID): String = ServicesSchema.entities.where(t => t.id === uuid).single.name

    if (simplified.toSet != correct) {
      println("Correct: " + correct.map(tup => (debugName(tup._1), debugName(tup._2), tup._3)).mkString("(\n\t", "\n\t", "\n)"))
      println("Result: " + simplified.map(tup => (debugName(tup._1), debugName(tup._2), tup._3)).toSet.mkString("(\n\t", "\n\t", "\n)"))
    }
    simplified.toSet should equal(correct)
    simplified.size should equal(correct.size)
  }

  test("Key queries") {
    val ent01 = createEntity("ent01", Seq("typeA", "typeB"))
    val ent02 = createEntity("ent02", Seq("typeB", "typeC"))
    val ent03 = createEntity("ent03", Seq("typeD"))
    val ent04 = createEntity("ent04", Seq())

    check(Set(("ent01", Set("typeA", "typeB")))) {
      SquerylEntityModel.keyQuery(Nil, Seq("ent01"))
    }
    check(Set(("ent01", Set("typeA", "typeB")), ("ent04", Set()))) {
      SquerylEntityModel.keyQuery(Nil, Seq("ent01", "ent04"))
    }

    check(Set(("ent03", Set("typeD")))) {
      SquerylEntityModel.keyQuery(Seq(ent03.id), Nil)
    }
    check(Set(("ent02", Set("typeB", "typeC")), ("ent03", Set("typeD")))) {
      SquerylEntityModel.keyQuery(Seq(ent02.id, ent03.id), Nil)
    }

    check(Set(("ent02", Set("typeB", "typeC")), ("ent03", Set("typeD")))) {
      SquerylEntityModel.keyQuery(Seq(ent02.id), Seq("ent03"))
    }
  }

  test("Full queries") {
    val ent01 = createEntity("ent01", Seq("typeA", "typeB"))
    val ent02 = createEntity("ent02", Seq("typeB", "typeC"))
    val ent03 = createEntity("ent03", Seq("typeD"))
    val ent04 = createEntity("ent04", Seq())

    val e1 = ("ent01", Set("typeA", "typeB"))
    val e2 = ("ent02", Set("typeB", "typeC"))
    val e3 = ("ent03", Set("typeD"))
    val e4 = ("ent04", Set.empty[String])

    val all = Set(e1, e2, e3, e4)

    check(all) {
      SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), None, None, 100, false, None)
    }

    check(Set(e1, e2)) {
      SquerylEntityModel.fullQuery(TypeParams(Nil, Seq("typeB"), Nil), None, None, 100, false, None)
    }
    check(Set(e2)) {
      SquerylEntityModel.fullQuery(TypeParams(Nil, Seq("typeB", "typeC"), Nil), None, None, 100, false, None)
    }
    check(Set()) {
      SquerylEntityModel.fullQuery(TypeParams(Nil, Seq("typeA", "typeC"), Nil), None, None, 100, false, None)
    }

    check(Set(e3, e4)) {
      SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Seq("typeB")), None, None, 100, false, None)
    }
    check(Set(e2, e4)) {
      SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Seq("typeA", "typeD")), None, None, 100, false, None)
    }

    check(Set(e2)) {
      SquerylEntityModel.fullQuery(TypeParams(Nil, Seq("typeB"), Seq("typeA")), None, None, 100, false, None)
    }

    check(Set(e1, e2)) {
      SquerylEntityModel.fullQuery(TypeParams(Seq("typeB"), Nil, Nil), None, None, 100, false, None)
    }
    check(Set(e1, e3)) {
      SquerylEntityModel.fullQuery(TypeParams(Seq("typeA", "typeD"), Nil, Nil), None, None, 100, false, None)
    }
    check(Set(e1)) {
      SquerylEntityModel.fullQuery(TypeParams(Seq("typeB"), Nil, Seq("typeC")), None, None, 100, false, None)
    }
    check(Set(e2)) {
      SquerylEntityModel.fullQuery(TypeParams(Seq("typeB"), Seq("typeC"), Nil), None, None, 100, false, None)
    }

  }

  test("All paging") {
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA", "typeD"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA", "typeD"))
    val ent05 = createEntity("ent05", Seq("typeA"))
    val ent06 = createEntity("ent06", Seq("typeA", "typeD"))
    val ent07 = createEntity("ent07", Seq("typeA"))

    def uuidOf(ent: Entity): UUID = ent.getUuid

    val uuidFull = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), None, None, 100, false).map(uuidOf)
    val nameFull = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), None, None, 100, true).map(uuidOf)

    {
      val page1 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), None, None, 2, false)
      val page2 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), Some(page1.last.getUuid), None, 3, false)
      val page3 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), Some(page2.last.getUuid), None, 2, false)

      (page1 ++ page2 ++ page3).map(uuidOf) should equal(uuidFull)
    }
    {
      val page1 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), None, None, 2, false)
      val page2 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), None, Some(page1.last.getName), 3, false)
      val page3 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), None, Some(page2.last.getName), 2, false)

      (page1 ++ page2 ++ page3).map(uuidOf) should equal(uuidFull)
    }

    {
      val page1 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), None, None, 2, true)
      val page2 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), Some(page1.last.getUuid), None, 3, true)
      val page3 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), Some(page2.last.getUuid), None, 2, true)

      (page1 ++ page2 ++ page3).map(uuidOf) should equal(nameFull)
    }
    {
      val page1 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), None, None, 2, true)
      val page2 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), None, Some(page1.last.getName), 3, true)
      val page3 = SquerylEntityModel.fullQuery(TypeParams(Nil, Nil, Nil), None, Some(page2.last.getName), 2, true)

      (page1 ++ page2 ++ page3).map(uuidOf) should equal(nameFull)
    }

  }

  test("Types paging") {
    val ent01 = createEntity("ent01", Seq("typeA", "typeD"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA", "typeD"))
    val ent05 = createEntity("ent05", Seq("typeA"))
    val ent06 = createEntity("ent06", Seq("typeA"))
    val ent07 = createEntity("ent07", Seq("typeA", "typeD"))
    val other01 = createEntity("other01", Seq("typeB"))
    val other02 = createEntity("other02", Seq("typeB"))

    def uuidOf(ent: Entity): UUID = ent.getUuid

    val full = SquerylEntityModel.fullQuery(TypeParams(Seq("typeA"), Nil, Nil), None, None, 100).map(uuidOf)

    val page1 = SquerylEntityModel.fullQuery(TypeParams(Seq("typeA"), Nil, Nil), None, None, 2).map(uuidOf)
    val page2 = SquerylEntityModel.fullQuery(TypeParams(Seq("typeA"), Nil, Nil), Some(page1.last), None, 3).map(uuidOf)
    val page3 = SquerylEntityModel.fullQuery(TypeParams(Seq("typeA"), Nil, Nil), Some(page2.last), None, 2).map(uuidOf)

    (page1 ++ page2 ++ page3) should equal(full)

  }

  test("Put one") {
    val notifier = new TestModelNotifier

    check(Set(("ent01", Set("typeA")))) {
      SquerylEntityModel.putEntities(notifier, Nil, Seq(("ent01", Set("typeA"))))
    }

    val entRows = ServicesSchema.entities.where(t => true === true).toSeq
    entRows.size should equal(1)
    entRows.map(_.name).toSet should equal(Set("ent01"))
    val uuid = entRows.head.id

    val typeJoins = ServicesSchema.entityTypes.where(t => true === true)
    typeJoins.size should equal(1)
    typeJoins.toSet should equal(Set(EntityTypeRow(uuid, "typeA")))

    val notes = notifier.getQueue(classOf[Entity]).toSeq.map(tup => (tup._1, simplify(tup._2)))
    notes.size should equal(1)
    notes.toSet should equal(Set((Created, ("ent01", Set("typeA")))))
  }

  test("Put one with multiple types") {
    val notifier = new TestModelNotifier

    check(Set(("ent01", Set("typeA", "typeB")))) {
      SquerylEntityModel.putEntities(notifier, Nil, Seq(("ent01", Set("typeA", "typeB"))))
    }

    val entRows = ServicesSchema.entities.where(t => true === true).toSeq
    entRows.size should equal(1)
    entRows.map(_.name).toSet should equal(Set("ent01"))
    val uuid = entRows.head.id

    val typeJoins = ServicesSchema.entityTypes.where(t => true === true)
    typeJoins.size should equal(2)
    typeJoins.toSet should equal(Set(EntityTypeRow(uuid, "typeA"), EntityTypeRow(uuid, "typeB")))

    val notes = notifier.getQueue(classOf[Entity]).toSeq.map(tup => (tup._1, simplify(tup._2)))
    notes.size should equal(1)
    notes.toSet should equal(Set((Created, ("ent01", Set("typeA", "typeB")))))
  }

  test("Put multiple") {
    val notifier = new TestModelNotifier

    check(Set(("ent01", Set("typeA", "typeB")), ("ent02", Set("typeB", "typeC")))) {
      SquerylEntityModel.putEntities(notifier, Nil, Seq(("ent01", Set("typeA", "typeB")), ("ent02", Set("typeB", "typeC"))))
    }

    val entRows = ServicesSchema.entities.where(t => true === true).toSeq
    entRows.size should equal(2)
    entRows.map(_.name).toSet should equal(Set("ent01", "ent02"))
    val entId01 = entRows.find(_.name == "ent01").get.id
    val entId02 = entRows.find(_.name == "ent02").get.id

    val typeJoins = ServicesSchema.entityTypes.where(t => true === true)
    typeJoins.size should equal(4)
    typeJoins.toSet should equal(
      Set(EntityTypeRow(entId01, "typeA"), EntityTypeRow(entId01, "typeB"),
        EntityTypeRow(entId02, "typeB"), EntityTypeRow(entId02, "typeC")))

    val notifications = Set(
      (Created, ("ent01", Set("typeA", "typeB"))),
      (Created, ("ent02", Set("typeB", "typeC"))))

    val notes = notifier.getQueue(classOf[Entity]).toSeq.map(tup => (tup._1, simplify(tup._2)))
    notes.size should equal(2)
    notes.toSet should equal(notifications)
  }

  test("Put multiple, add and delete types") {
    val notifier = new TestModelNotifier

    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA", "typeB"))
    val ent03 = createEntity("ent03", Seq("typeB"))

    val resulting = Set(
      ("ent01", Set("typeA", "typeB")),
      ("ent02", Set("typeB")),
      ("ent03", Set("typeA")),
      ("ent04", Set("typeC")))

    val puts = Seq(
      ("ent01", Set("typeA", "typeB")),
      ("ent02", Set("typeB")),
      ("ent03", Set("typeA")),
      ("ent04", Set("typeC")))

    check(resulting) {
      SquerylEntityModel.putEntities(notifier, Nil, puts)
    }

    val names = Set("ent01", "ent02", "ent03", "ent04")
    val entRows = ServicesSchema.entities.where(t => true === true).toSeq
    entRows.size should equal(4)
    entRows.map(_.name).toSet should equal(names)

    val nameToId = entRows.map(row => (row.name, row.id)).toMap

    nameToId("ent01") should equal(ent01.id)
    nameToId("ent02") should equal(ent02.id)
    nameToId("ent03") should equal(ent03.id)

    val typeRows = Set(
      EntityTypeRow(nameToId("ent01"), "typeA"),
      EntityTypeRow(nameToId("ent01"), "typeB"),
      EntityTypeRow(nameToId("ent02"), "typeB"),
      EntityTypeRow(nameToId("ent03"), "typeA"),
      EntityTypeRow(nameToId("ent04"), "typeC"))

    val typeJoins = ServicesSchema.entityTypes.where(t => true === true).toSeq
    typeJoins.size should equal(typeRows.size)
    typeJoins.toSet should equal(typeRows)

    val notifications = Set(
      (Updated, ("ent01", Set("typeA", "typeB"))),
      (Updated, ("ent02", Set("typeB"))),
      (Updated, ("ent03", Set("typeA"))),
      (Created, ("ent04", Set("typeC"))))

    val notes = notifier.getQueue(classOf[Entity]).toSeq.map(tup => (tup._1, simplify(tup._2)))
    notes.size should equal(4)
    notes.toSet should equal(notifications)
  }

  test("Put one with predefined id") {
    val notifier = new TestModelNotifier

    val id = UUID.randomUUID()

    check(Set(("ent01", Set("typeA")))) {
      SquerylEntityModel.putEntities(notifier, Seq((id, "ent01", Set("typeA"))), Nil)
    }

    val entRows = ServicesSchema.entities.where(t => true === true).toSeq
    entRows.size should equal(1)
    entRows.map(_.name).toSet should equal(Set("ent01"))
    val uuid = entRows.head.id

    uuid should equal(id)

    val typeJoins = ServicesSchema.entityTypes.where(t => true === true)
    typeJoins.size should equal(1)
    typeJoins.toSet should equal(Set(EntityTypeRow(uuid, "typeA")))

    val notes = notifier.getQueue(classOf[Entity]).toSeq.map(tup => (tup._1, simplify(tup._2)))
    notes.size should equal(1)
    notes.toSet should equal(Set((Created, ("ent01", Set("typeA")))))
  }

  test("Put, rename, add and remove types") {
    val notifier = new TestModelNotifier

    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA", "typeB"))
    val ent03 = createEntity("ent03", Seq("typeB"))

    val ent04id = UUID.randomUUID()

    val resulting = Set(
      ("ent07", Set("typeA", "typeB")),
      ("ent02", Set("typeB")),
      ("ent03", Set("typeA")),
      ("ent04", Set("typeC")))

    val puts = Seq(
      (ent01.id, "ent07", Set("typeA", "typeB")),
      (ent02.id, "ent02", Set("typeB")),
      (ent03.id, "ent03", Set("typeA")),
      (ent04id, "ent04", Set("typeC")))

    check(resulting) {
      SquerylEntityModel.putEntities(notifier, puts, Nil)
    }

    val names = Set("ent07", "ent02", "ent03", "ent04")
    val entRows = ServicesSchema.entities.where(t => true === true).toSeq
    entRows.size should equal(4)
    entRows.map(_.name).toSet should equal(names)

    val nameToId = entRows.map(row => (row.name, row.id)).toMap

    nameToId("ent07") should equal(ent01.id)
    nameToId("ent02") should equal(ent02.id)
    nameToId("ent03") should equal(ent03.id)
    nameToId("ent04") should equal(ent04id)

    val typeRows = Set(
      EntityTypeRow(nameToId("ent07"), "typeA"),
      EntityTypeRow(nameToId("ent07"), "typeB"),
      EntityTypeRow(nameToId("ent02"), "typeB"),
      EntityTypeRow(nameToId("ent03"), "typeA"),
      EntityTypeRow(nameToId("ent04"), "typeC"))

    val typeJoins = ServicesSchema.entityTypes.where(t => true === true).toSeq
    typeJoins.size should equal(typeRows.size)
    typeJoins.toSet should equal(typeRows)

    val notifications = Set(
      (Updated, (ent01.id, "ent07", Set("typeA", "typeB"))),
      (Updated, (ent02.id, "ent02", Set("typeB"))),
      (Updated, (ent03.id, "ent03", Set("typeA"))),
      (Created, (ent04id, "ent04", Set("typeC"))))

    val notes = notifier.getQueue(classOf[Entity]).toSeq.map(tup => (tup._1, (protoUUIDToUuid(tup._2.getUuid), tup._2.getName, tup._2.getTypesList.toSet)))
    notes.size should equal(4)
    notes.toSet should equal(notifications)
  }

  test("Put filtering") {
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA", "typeB"))

    val filter = new EntityFilter {
      protected def filterQuery: Query[EntityRow] = {
        from(ServicesSchema.entities)(ent => where(ent.name === "ent01") select (ent))
      }
    }

    intercept[ModelInputException] {
      SquerylEntityModel.putEntities(NullModelNotifier, Nil, Seq(("ent03", Set("typeC"))), false, Some(filter))
    }
    intercept[ModelInputException] {
      SquerylEntityModel.putEntities(NullModelNotifier, Nil, Seq(("ent01", Set("typeC")), ("ent03", Set("typeC"))), false, Some(filter))
    }
    intercept[ModelInputException] {
      SquerylEntityModel.putEntities(NullModelNotifier, Nil, Seq(("ent02", Set("typeC"))), true, Some(filter))
    }
    intercept[ModelInputException] {
      SquerylEntityModel.putEntities(NullModelNotifier, Nil, Seq(("ent01", Set("typeC")), ("ent02", Set("typeC"))), true, Some(filter))
    }

    check(Set(("ent01", Set("typeC")))) {
      SquerylEntityModel.putEntities(NullModelNotifier, Nil, Seq(("ent01", Set("typeC"))), true, Some(filter))
    }
  }

  test("Delete entity") {
    val notifier = new TestModelNotifier

    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA", "typeB"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    val ent05 = createEntity("ent05", Seq("typeA"))
    val edge1To2 = createEdge(ent01.id, ent02.id, "relatesTo", 1)
    createEdge(ent01.id, ent03.id, "relatesTo", 2)
    createEdge(ent01.id, ent04.id, "relatesTo", 3)
    createEdge(ent01.id, ent05.id, "relatesTo", 4)
    createEdge(ent02.id, ent03.id, "relatesTo", 1)
    createEdge(ent02.id, ent04.id, "relatesTo", 2)
    createEdge(ent02.id, ent05.id, "relatesTo", 3)
    createEdge(ent03.id, ent04.id, "relatesTo", 1)
    createEdge(ent03.id, ent05.id, "relatesTo", 2)
    val edge4To5 = createEdge(ent04.id, ent05.id, "relatesTo", 1)
    createEdge(ent01.id, ent03.id, "another", 1)
    createEdge(ent03.id, ent05.id, "extraneous", 1)

    val kv01 = createEntityKeyValue(ent03.id, "keyA", strToStored("value01").toByteArray)
    val kv02 = createEntityKeyValue(ent05.id, "keyA", strToStored("value02").toByteArray)

    val result = Set((ent03.name, Set("typeA", "typeB")))

    check(result) {
      SquerylEntityModel.deleteEntities(notifier, Seq(ent03.id))
    }

    val allEntities = ServicesSchema.entities.where(t => true === true).map(_.name)
    allEntities.size should equal(4)
    allEntities.toSet should equal(Set("ent01", "ent02", "ent04", "ent05"))

    ServicesSchema.entityTypes.where(t => t.entityId === ent03.id).size should equal(0)

    val allEdges = ServicesSchema.edges.where(t => true === true).toSeq
    allEdges.size should equal(2)
    allEdges.map(_.id).toSet should equal(Set(edge1To2.id, edge4To5.id))

    val notifications = Set(
      (Deleted, ("ent03", Set("typeA", "typeB"))))

    val notes = notifier.getQueue(classOf[Entity]).toSeq.map(tup => (tup._1, simplify(tup._2)))
    notes.size should equal(1)
    notes.toSet should equal(notifications)

    notifier.checkEntityEdges(Set(
      (Deleted, (ent01.id, ent03.id, "relatesTo", 2)),
      (Deleted, (ent01.id, ent04.id, "relatesTo", 3)),
      (Deleted, (ent01.id, ent05.id, "relatesTo", 4)),
      (Deleted, (ent02.id, ent03.id, "relatesTo", 1)),
      (Deleted, (ent02.id, ent04.id, "relatesTo", 2)),
      (Deleted, (ent02.id, ent05.id, "relatesTo", 3)),
      (Deleted, (ent03.id, ent04.id, "relatesTo", 1)),
      (Deleted, (ent03.id, ent05.id, "relatesTo", 2)),
      (Deleted, (ent01.id, ent03.id, "another", 1)),
      (Deleted, (ent03.id, ent05.id, "extraneous", 1))))

    val v1 = (ent03.id, "keyA", "value01")

    notifier.checkEntityKeyValues(Set((Deleted, v1)))
  }

  /*
   1   2
     3   4
   */

  test("Delete entity, only correct edges") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    createEdge(ent01.id, ent03.id, "relatesTo", 1)
    createEdge(ent02.id, ent04.id, "relatesTo", 1)
    val edge2To3 = createEdge(ent02.id, ent03.id, "relatesTo", 1)

    val result = Set(("ent01", Set("typeA")), ("ent04", Set("typeA")))

    check(result) {
      SquerylEntityModel.deleteEntities(notifier, Seq(ent01.id, ent04.id))
    }

    val allEntities = ServicesSchema.entities.where(t => true === true).map(_.name)
    allEntities.size should equal(2)
    allEntities.toSet should equal(Set("ent02", "ent03"))

    val allEdges = ServicesSchema.edges.where(t => true === true).toSeq
    allEdges.size should equal(1)
    allEdges.map(_.id).toSet should equal(Set(edge2To3.id))

    notifier.checkEntities(Set(
      (Deleted, ("ent01", Set("typeA"))),
      (Deleted, ("ent04", Set("typeA")))))

    notifier.checkEntityEdges(Set(
      (Deleted, (ent01.id, ent03.id, "relatesTo", 1)),
      (Deleted, (ent02.id, ent04.id, "relatesTo", 1))))
  }

  test("Put edge, simple") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))

    val resultEdges = Set(
      (ent01.id, ent02.id, "relatesTo", 1),
      (ent01.id, ent03.id, "relatesTo", 1))

    checkEdges(resultEdges) {
      SquerylEntityModel.putEdges(notifier, Seq((ent01.id, ent02.id), (ent01.id, ent03.id)), "relatesTo")
    }

    val allEdges = ServicesSchema.edges.where(t => true === true).toSeq

    allEdges.size should equal(2)
    allEdges.map(row => (row.parentId, row.childId, row.relationship, row.distance)).toSet should equal(resultEdges)

    notifier.checkEntityEdges(resultEdges.map(e => (Created, e)))
  }

  test("Put edge, simple, with existing") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val edge01 = createEdge(ent01.id, ent03.id, "relatesTo", 1)

    val resultEdges = Set(
      (ent01.id, ent02.id, "relatesTo", 1),
      (ent01.id, ent03.id, "relatesTo", 1))

    checkEdges(resultEdges) {
      SquerylEntityModel.putEdges(notifier, Seq((ent01.id, ent02.id), (ent01.id, ent03.id)), "relatesTo")
    }

    val allEdges = ServicesSchema.edges.where(t => true === true).toSeq
    allEdges.size should equal(2)
    val toMatch = allEdges.map(row => (row.parentId, row.childId, row.relationship, row.distance)).toSet
    toMatch should equal(resultEdges)

    notifier.checkEntityEdges(Set(
      (Created, (ent01.id, ent02.id, "relatesTo", 1))))
  }

  test("Put edge, simple, not confused by different edge type") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val edge01 = createEdge(ent01.id, ent03.id, "totallyUnrelated", 1)

    val resultEdges = Set(
      (ent01.id, ent02.id, "relatesTo", 1),
      (ent01.id, ent03.id, "relatesTo", 1))

    checkEdges(resultEdges) {
      SquerylEntityModel.putEdges(notifier, Seq((ent01.id, ent02.id), (ent01.id, ent03.id)), "relatesTo")
    }

    val allEdges = ServicesSchema.edges.where(t => t.relationship === "relatesTo").toSeq
    allEdges.size should equal(resultEdges.size)
    val toMatch = allEdges.map(row => (row.parentId, row.childId, row.relationship, row.distance)).toSet
    toMatch should equal(resultEdges)

    notifier.checkEntityEdges(resultEdges.map(e => (Created, e)))
  }

  test("Put edges with derived edges") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    val edge01To02 = createEdge(ent01.id, ent02.id, "relatesTo", 1)
    val edge03To04 = createEdge(ent03.id, ent04.id, "relatesTo", 1)

    val resultEdges = Set(
      (ent02.id, ent03.id, "relatesTo", 1),
      (ent02.id, ent04.id, "relatesTo", 2),
      (ent01.id, ent03.id, "relatesTo", 2),
      (ent01.id, ent04.id, "relatesTo", 3))

    checkEdges(resultEdges) {
      SquerylEntityModel.putEdges(notifier, Seq((ent02.id, ent03.id)), "relatesTo")
    }

    val allEdges = ServicesSchema.edges.where(t => true === true).toSeq
    allEdges.size should equal(6)
    val toMatch = allEdges
      .filterNot(a => Seq(edge01To02, edge03To04).exists(_ == a))
      .map(row => (row.parentId, row.childId, row.relationship, row.distance))
      .toSet

    toMatch should equal(resultEdges)

    notifier.checkEntityEdges(resultEdges.map(e => (Created, e)))
  }

  test("Put edge, batch derived") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))

    val resultEdges = Set(
      (ent01.id, ent02.id, "relatesTo", 1),
      (ent02.id, ent03.id, "relatesTo", 1),
      (ent01.id, ent03.id, "relatesTo", 2))

    checkEdges(resultEdges) {
      SquerylEntityModel.putEdges(notifier, Seq((ent01.id, ent02.id), (ent02.id, ent03.id)), "relatesTo")
    }

    val allEdges = ServicesSchema.edges.where(t => true === true).toSeq

    allEdges.size should equal(resultEdges.size)
    allEdges.map(row => (row.parentId, row.childId, row.relationship, row.distance)).toSet should equal(resultEdges)

    notifier.checkEntityEdges(resultEdges.map(e => (Created, e)))
  }

  test("Put edge, batch derived extended") {
    /* 1 - 2 [] 3 [] 4 - 5 - 6    */
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    val ent05 = createEntity("ent05", Seq("typeA"))
    val ent06 = createEntity("ent06", Seq("typeA"))

    val extant = List(
      (ent01.id, ent02.id, "relatesTo", 1),
      (ent04.id, ent05.id, "relatesTo", 1),
      (ent04.id, ent06.id, "relatesTo", 2),
      (ent05.id, ent06.id, "relatesTo", 1))

    extant.foreach { case (parent, child, relation, int) => createEdge(parent, child, relation, int) }

    val allTups = Set(
      (ent01.id, ent02.id, "relatesTo", 1),
      (ent01.id, ent03.id, "relatesTo", 2),
      (ent01.id, ent04.id, "relatesTo", 3),
      (ent01.id, ent05.id, "relatesTo", 4),
      (ent01.id, ent06.id, "relatesTo", 5),
      (ent02.id, ent03.id, "relatesTo", 1),
      (ent02.id, ent04.id, "relatesTo", 2),
      (ent02.id, ent05.id, "relatesTo", 3),
      (ent02.id, ent06.id, "relatesTo", 4),
      (ent03.id, ent04.id, "relatesTo", 1),
      (ent03.id, ent05.id, "relatesTo", 2),
      (ent03.id, ent06.id, "relatesTo", 3),
      (ent04.id, ent05.id, "relatesTo", 1),
      (ent04.id, ent06.id, "relatesTo", 2),
      (ent05.id, ent06.id, "relatesTo", 1))

    val resultEdges = allTups &~ extant.toSet

    checkEdges(resultEdges) {
      SquerylEntityModel.putEdges(notifier, Seq((ent02.id, ent03.id), (ent03.id, ent04.id)), "relatesTo")
    }

    val allEdges = ServicesSchema.edges.where(t => true === true).toSeq

    allEdges.size should equal(allTups.size)
    allEdges.map(row => (row.parentId, row.childId, row.relationship, row.distance)).toSet should equal(allTups)

    notifier.checkEntityEdges(resultEdges.map(e => (Created, e)))
  }

  test("Put edge, batch derived two holes") {
    /* 1 - 2 [] 3 - 4 [] 5 - 6  */
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    val ent05 = createEntity("ent05", Seq("typeA"))
    val ent06 = createEntity("ent06", Seq("typeA"))

    val extant = List(
      (ent01.id, ent02.id, "relatesTo", 1),
      (ent03.id, ent04.id, "relatesTo", 1),
      (ent05.id, ent06.id, "relatesTo", 1))

    extant.foreach { case (parent, child, relation, int) => createEdge(parent, child, relation, int) }

    val allTups = Set(
      (ent01.id, ent02.id, "relatesTo", 1),
      (ent01.id, ent03.id, "relatesTo", 2),
      (ent01.id, ent04.id, "relatesTo", 3),
      (ent01.id, ent05.id, "relatesTo", 4),
      (ent01.id, ent06.id, "relatesTo", 5),
      (ent02.id, ent03.id, "relatesTo", 1),
      (ent02.id, ent04.id, "relatesTo", 2),
      (ent02.id, ent05.id, "relatesTo", 3),
      (ent02.id, ent06.id, "relatesTo", 4),
      (ent03.id, ent04.id, "relatesTo", 1),
      (ent03.id, ent05.id, "relatesTo", 2),
      (ent03.id, ent06.id, "relatesTo", 3),
      (ent04.id, ent05.id, "relatesTo", 1),
      (ent04.id, ent06.id, "relatesTo", 2),
      (ent05.id, ent06.id, "relatesTo", 1))

    val resultEdges = allTups &~ extant.toSet

    checkEdges(resultEdges) {
      SquerylEntityModel.putEdges(notifier, Seq((ent02.id, ent03.id), (ent04.id, ent05.id)), "relatesTo")
    }

    val allEdges = ServicesSchema.edges.where(t => true === true).toSeq

    allEdges.size should equal(allTups.size)
    allEdges.map(row => (row.parentId, row.childId, row.relationship, row.distance)).toSet should equal(allTups)

    notifier.checkEntityEdges(resultEdges.map(e => (Created, e)))
  }

  /*
      1
    2   3
      4
   */
  test("Diamond, from above") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    createEdge(ent01.id, ent02.id, "relatesTo", 1)
    createEdge(ent01.id, ent03.id, "relatesTo", 1)
    createEdge(ent02.id, ent04.id, "relatesTo", 1)
    createEdge(ent01.id, ent04.id, "relatesTo", 2)

    intercept[DiamondException] {
      SquerylEntityModel.putEdges(notifier, Seq((ent03.id, ent04.id)), "relatesTo")
    }
  }
  test("Diamond, from below") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    createEdge(ent01.id, ent03.id, "relatesTo", 1)
    createEdge(ent02.id, ent04.id, "relatesTo", 1)
    createEdge(ent03.id, ent04.id, "relatesTo", 1)
    createEdge(ent01.id, ent04.id, "relatesTo", 2)

    intercept[DiamondException] {
      SquerylEntityModel.putEdges(notifier, Seq((ent01.id, ent02.id)), "relatesTo")
    }
  }
  test("Diamond, from above, different relation ok") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    createEdge(ent01.id, ent02.id, "relatesTo", 1)
    createEdge(ent01.id, ent03.id, "relatesTo", 1)
    createEdge(ent02.id, ent04.id, "relatesTo", 1)
    createEdge(ent01.id, ent04.id, "relatesTo", 2)

    val results = SquerylEntityModel.putEdges(notifier, Seq((ent03.id, ent04.id)), "totallyUnrelated")
    results.toSeq.size > 0 should equal(true)
  }

  test("Delete edges") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    val edge01To02 = createEdge(ent01.id, ent02.id, "relatesTo", 1)
    val edge01To03 = createEdge(ent01.id, ent03.id, "relatesTo", 1)
    val edge01To04 = createEdge(ent01.id, ent04.id, "relatesTo", 1)

    val resultEdges = Set(
      (ent01.id, ent02.id, "relatesTo", 1),
      (ent01.id, ent03.id, "relatesTo", 1))

    checkEdges(resultEdges) {
      SquerylEntityModel.deleteEdges(notifier, Seq((ent01.id, ent02.id), (ent01.id, ent03.id)), "relatesTo")
    }

    val allEdges = ServicesSchema.edges.where(t => true === true).toSeq
    allEdges.size should equal(1)
    allEdges.head.id should equal(edge01To04.id)

    notifier.checkEntityEdges(resultEdges.map(e => (Deleted, e)))
  }

  /*
   5 1 2
     3 4 6
   */
  test("Relation flat query") {
    val ent01 = createEntity("ent01", Seq("typeA", "top"))
    val ent02 = createEntity("ent02", Seq("typeA", "top"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA", "special"))
    val ent05 = createEntity("ent05", Seq("typeA"))
    val ent06 = createEntity("ent06", Seq("typeA"))
    createEdge(ent01.id, ent03.id, "relatesTo", 1)
    createEdge(ent01.id, ent04.id, "relatesTo", 1)
    createEdge(ent02.id, ent04.id, "relatesTo", 1)
    createEdge(ent02.id, ent06.id, "unrelated", 1)

    val results = Set(
      ("ent03", Set("typeA")),
      ("ent04", Set("typeA", "special")))

    check(results) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id, ent02.id), "relatesTo", descendantOf = true, Seq(), None, None, None, 100, false, filter = None)
    }
    check(results) {
      SquerylEntityModel.namesRelationFlatQuery(Seq("ent01", "ent02"), "relatesTo", descendantOf = true, Seq(), None, None, None, 100, false, filter = None)
    }
    check(results) {
      SquerylEntityModel.typesRelationFlatQuery(Seq("top"), "relatesTo", descendantOf = true, Seq(), None, None, None, 100, false, filter = None)
    }

    check(Set(("ent04", Set("typeA", "special")))) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id, ent02.id), "relatesTo", descendantOf = true, Seq("special"), None, None, None, 100, false, filter = None)
    }

    val reverse = Set(
      ("ent01", Set("typeA", "top")),
      ("ent02", Set("typeA", "top")))

    check(reverse) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent03.id, ent04.id), "relatesTo", descendantOf = false, Seq(), None, None, None, 100, false, filter = None)
    }
  }

  test("Relation flat query, depth limit") {
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA", "typeB"))
    createEdge(ent01.id, ent02.id, "relatesTo", 1)
    createEdge(ent01.id, ent03.id, "relatesTo", 2)
    createEdge(ent02.id, ent03.id, "relatesTo", 1)

    val m1 = ("ent02", Set("typeA"))
    val m2 = ("ent03", Set("typeA", "typeB"))

    val all = Set(m1, m2)

    val results = Set(m1)

    check(all) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), Some(100), None, None, 100, false, filter = None)
    }
    check(all) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, None, None, 100, false, filter = None)
    }
    check(all) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq("typeA"), Some(2), None, None, 100, false, filter = None)
    }
    check(all) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), Some(Int.MaxValue), None, None, 100, false, filter = None)
    }

    check(Set(m2)) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq("typeB"), None, None, None, 100, false, filter = None)
    }
    check(Set(m2)) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq("typeB"), Some(2), None, None, 100, false, filter = None)
    }

    check(results) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), Some(1), None, None, 100, false, filter = None)
    }

    check(Set()) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq("typeB"), Some(1), None, None, 100, false, filter = None)
    }
    check(Set()) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq("typeC"), None, None, None, 100, false, filter = None)
    }
  }

  test("Relation flat query, paging") {
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA", "typeB", "typeC"))
    val ent03 = createEntity("ent03", Seq("typeA", "typeC"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    createEdge(ent01.id, ent02.id, "relatesTo", 1)
    createEdge(ent01.id, ent03.id, "relatesTo", 1)
    createEdge(ent01.id, ent04.id, "relatesTo", 1)

    {
      val first = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, None, None, 1, false, filter = None)
      val second = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, Some(first.head.getUuid), None, 1, false, filter = None)
      val third = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, Some(second.head.getUuid), None, 1, false, filter = None)

      val all = (first ++ second ++ third).map(_.getName)
      all.size should equal(3)
      all.toSet should equal(Set("ent02", "ent03", "ent04"))
    }

    {
      val first = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, None, None, 1, false, filter = None)
      val second = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, None, Some(first.last.getName), 1, false, filter = None)
      val third = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, None, Some(second.last.getName), 1, false, filter = None)

      val all = (first ++ second ++ third).map(_.getName)
      all.size should equal(3)
      all.toSet should equal(Set("ent02", "ent03", "ent04"))
    }

    {
      val first = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, None, None, 1, true, filter = None)
      val second = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, Some(first.head.getUuid), None, 1, true, filter = None)
      val third = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, Some(second.head.getUuid), None, 1, true, filter = None)

      val all = (first ++ second ++ third).map(_.getName)
      all should equal(Seq("ent02", "ent03", "ent04"))
    }

    {
      val first = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, None, None, 1, true, filter = None)
      val second = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, None, Some(first.last.getName), 1, true, filter = None)
      val third = SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id), "relatesTo", descendantOf = true, Seq(), None, None, Some(second.last.getName), 1, true, filter = None)

      val all = (first ++ second ++ third).map(_.getName)
      all should equal(Seq("ent02", "ent03", "ent04"))
    }
  }

  /*
    1x 2
  5 3x 4
   */
  test("Relation flat query filtering") {
    val ent01 = createEntity("ent01", Seq("top", "banned"))
    val ent02 = createEntity("ent02", Seq("top"))
    val ent03 = createEntity("ent03", Seq("banned"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    val ent05 = createEntity("ent05", Seq("typeA"))
    createEdge(ent01.id, ent03.id, "relatesTo", 1)
    createEdge(ent01.id, ent05.id, "relatesTo", 1)
    createEdge(ent02.id, ent03.id, "relatesTo", 1)
    createEdge(ent02.id, ent04.id, "relatesTo", 1)

    val entFilter = new EntityFilter {
      protected def filterQuery: Query[EntityRow] = {
        from(ServicesSchema.entities)(ent =>
          where(notExists(
            from(ServicesSchema.entityTypes)(t =>
              where(t.entType === "banned" and t.entityId === ent.id)
                select (t))))
            select (ent))
      }
    }

    val results = Set(
      ("ent04", Set("typeA")))

    check(results) {
      SquerylEntityModel.idsRelationFlatQuery(Seq(ent01.id, ent02.id), "relatesTo", descendantOf = true, Seq(), None, None, None, 100, false, Some(entFilter))
    }
    check(results) {
      SquerylEntityModel.namesRelationFlatQuery(Seq("ent01", "ent02"), "relatesTo", descendantOf = true, Seq(), None, None, None, 100, false, Some(entFilter))
    }
    check(results) {
      SquerylEntityModel.typesRelationFlatQuery(Seq("top"), "relatesTo", descendantOf = true, Seq(), None, None, None, 100, false, Some(entFilter))
    }
  }

  test("Edge query") {
    val ent01 = createEntity("ent01", Seq("typeA", "top"))
    val ent02 = createEntity("ent02", Seq("typeA", "banned"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA", "banned"))
    val ent05 = createEntity("ent05", Seq("typeA"))
    val ent06 = createEntity("ent06", Seq("typeA"))

    val eg1 = (ent01.id, ent03.id, "relatesTo", 1)
    val eg2 = (ent01.id, ent04.id, "relatesTo", 1)
    val eg3 = (ent02.id, ent04.id, "relatesTo", 1)
    val eg4 = (ent02.id, ent06.id, "unrelated", 1)
    val eg5 = (ent03.id, ent05.id, "rules", 1)
    val eg6 = (ent05.id, ent06.id, "rules", 1)
    val eg7 = (ent03.id, ent06.id, "rules", 2)

    val all = Set(eg1, eg2, eg3, eg4, eg5, eg6, eg7)

    all.foreach(eg => createEdge(eg._1, eg._2, eg._3, eg._4))

    checkEdges(all) {
      SquerylEntityModel.edgeQuery(Nil, Nil, Nil, None, None, 100, None)
    }
    checkEdges(Set(eg3)) {
      SquerylEntityModel.edgeQuery(Seq(ent02.id), Seq("relatesTo"), Nil, None, None, 100, None)
    }
    checkEdges(Set(eg3, eg4)) {
      SquerylEntityModel.edgeQuery(Seq(ent02.id), Seq("relatesTo", "unrelated"), Nil, None, None, 100, None)
    }
    checkEdges(Set(eg1, eg2, eg3)) {
      SquerylEntityModel.edgeQuery(Seq(ent01.id, ent02.id), Seq("relatesTo"), Nil, None, None, 100, None)
    }
    checkEdges(Set(eg1)) {
      SquerylEntityModel.edgeQuery(Seq(ent01.id, ent02.id), Seq("relatesTo"), Seq(ent03.id), None, None, 100, None)
    }
    checkEdges(Set(eg1)) {
      SquerylEntityModel.edgeQuery(Nil, Seq("relatesTo"), Seq(ent03.id), None, None, 100, None)
    }
    checkEdges(Set(eg1)) {
      SquerylEntityModel.edgeQuery(Nil, Nil, Seq(ent03.id), None, None, 100, None)
    }
    checkEdges(Set(eg1, eg5)) {
      SquerylEntityModel.edgeQuery(Nil, Nil, Seq(ent03.id, ent05.id), None, None, 100, None)
    }

    checkEdges(Set(eg5, eg7)) {
      SquerylEntityModel.edgeQuery(Seq(ent03.id), Seq("rules"), Nil, None, None, 100, None)
    }
    checkEdges(Set(eg5)) {
      SquerylEntityModel.edgeQuery(Seq(ent03.id), Seq("rules"), Nil, Some(1), None, 100, None)
    }

    val first = SquerylEntityModel.edgeQuery(Nil, Nil, Nil, None, None, 3, None)
    val second = SquerylEntityModel.edgeQuery(Nil, Nil, Nil, None, Some(first.last.getId.getValue.toLong), 2, None)
    val third = SquerylEntityModel.edgeQuery(Nil, Nil, Nil, None, Some(second.last.getId.getValue.toLong), 2, None)

    val paged = first ++ second ++ third
    paged.size should equal(all.size)
    val pageSimple = paged.map(proto => (protoUUIDToUuid(proto.getParent), protoUUIDToUuid(proto.getChild), proto.getRelationship, proto.getDistance))
    pageSimple.toSet should equal(all)

    val entFilter = new EntityFilter {
      protected def filterQuery: Query[EntityRow] = {
        from(ServicesSchema.entities)(ent =>
          where(notExists(
            from(ServicesSchema.entityTypes)(t =>
              where(t.entType === "banned" and t.entityId === ent.id)
                select (t))))
            select (ent))
      }
    }

    checkEdges(Set(eg1, eg5, eg6, eg7)) {
      SquerylEntityModel.edgeQuery(Nil, Nil, Nil, None, None, 100, Some(entFilter))
    }
    checkEdges(Set(eg1)) {
      SquerylEntityModel.edgeQuery(Seq(ent01.id), Seq("relatesTo"), Nil, None, None, 100, Some(entFilter))
    }
    checkEdges(Set(eg6, eg7)) {
      SquerylEntityModel.edgeQuery(Nil, Nil, Seq(ent06.id), None, None, 100, Some(entFilter))
    }

  }

  test("Edge query paging") {
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))
    val ent05 = createEntity("ent05", Seq("typeA"))
    val ent06 = createEntity("ent06", Seq("typeA"))
    val ent07 = createEntity("ent07", Seq("typeA"))
    val ent08 = createEntity("ent08", Seq("typeA"))

    val relation = "relatesTo"
    val eg1 = (ent01.id, ent02.id, relation, 1)
    val eg2 = (ent01.id, ent03.id, relation, 1)
    val eg3 = (ent01.id, ent04.id, relation, 1)
    val eg4 = (ent01.id, ent05.id, relation, 1)
    val eg5 = (ent01.id, ent06.id, relation, 1)
    val eg6 = (ent01.id, ent07.id, relation, 1)
    val eg7 = (ent01.id, ent08.id, relation, 1)

    val all = Set(eg1, eg2, eg3, eg4, eg5, eg6, eg7)

    ServicesSchema.edges.insert(EntityEdgeRow(3, ent01.id, ent02.id, relation, 1))
    ServicesSchema.edges.insert(EntityEdgeRow(4, ent01.id, ent03.id, relation, 1))
    ServicesSchema.edges.insert(EntityEdgeRow(6, ent01.id, ent04.id, relation, 1))
    ServicesSchema.edges.insert(EntityEdgeRow(2, ent01.id, ent05.id, relation, 1))
    ServicesSchema.edges.insert(EntityEdgeRow(7, ent01.id, ent06.id, relation, 1))
    ServicesSchema.edges.insert(EntityEdgeRow(5, ent01.id, ent07.id, relation, 1))
    ServicesSchema.edges.insert(EntityEdgeRow(1, ent01.id, ent08.id, relation, 1))

    checkEdges(all) {
      SquerylEntityModel.edgeQuery(Nil, Nil, Nil, None, None, 100, None)
    }

    val r1 = SquerylEntityModel.edgeQuery(Seq(ent01.id), Nil, Nil, None, None, 2, None).toVector
    r1.size should equal(2)
    val r2 = SquerylEntityModel.edgeQuery(Seq(ent01.id), Nil, Nil, None, Some(r1.last.getId), 2, None).toVector
    r2.size should equal(2)
    val r3 = SquerylEntityModel.edgeQuery(Seq(ent01.id), Nil, Nil, None, Some(r2.last.getId), 2, None).toVector
    r3.size should equal(2)
    val r4 = SquerylEntityModel.edgeQuery(Seq(ent01.id), Nil, Nil, None, Some(r3.last.getId), 2, None).toVector
    r4.size should equal(1)

    val concatResults = r1 ++ r2 ++ r3 ++ r4
    concatResults.size should equal(all.size)

    checkEdges(all) { r1 ++ r2 ++ r3 ++ r4 }
  }

  def checkKeyValues(correct: Set[(UUID, String)])(query: => Seq[(UUID, Array[Byte])]) {
    val results = query
    results.size should equal(correct.size)
    results.map(tup => (tup._1, new String(tup._2, "UTF-8"))).toSet should equal(correct)
  }

  test("Entity key store get") {
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))

    val kv01 = createEntityKeyValue(ent01.id, "keyA", "value01".getBytes("UTF-8"))
    val kv02 = createEntityKeyValue(ent02.id, "keyA", "value02".getBytes("UTF-8"))
    val kv03 = createEntityKeyValue(ent03.id, "keyB", "value03".getBytes("UTF-8"))
    val kv04 = createEntityKeyValue(ent02.id, "keyB", "value04".getBytes("UTF-8"))

    val v1 = (ent01.id, "value01")
    val v2 = (ent02.id, "value02")
    val v3 = (ent03.id, "value03")
    val v4 = (ent02.id, "value04")

    checkKeyValues(Set(v1)) {
      SquerylEntityModel.getKeyValues(Seq(ent01.id), "keyA")
    }

    checkKeyValues(Set(v1, v2)) {
      SquerylEntityModel.getKeyValues(Seq(ent01.id, ent02.id), "keyA")
    }

    checkKeyValues(Set(v1, v2)) {
      SquerylEntityModel.getKeyValues(Seq(ent01.id, ent02.id, ent03.id, ent04.id), "keyA")
    }

    checkKeyValues(Set(v3, v4)) {
      SquerylEntityModel.getKeyValues(Seq(ent01.id, ent02.id, ent03.id, ent04.id), "keyB")
    }
  }

  def checkPutKeyValues(correct: Set[(UUID, String)], correctCreates: Set[UUID], correctUpdates: Set[UUID])(query: => (Seq[(UUID, Array[Byte])], Seq[UUID], Seq[UUID])) {
    val (results, created, updated) = query
    results.size should equal(correct.size)
    results.map(tup => (tup._1, new String(tup._2, "UTF-8"))).toSet should equal(correct)

    created.size should equal(correctCreates.size)
    created.toSet should equal(correctCreates)

    updated.size should equal(correctUpdates.size)
    updated.toSet should equal(correctUpdates)
  }

  test("Entity key store put") {
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))

    val kv02 = createEntityKeyValue(ent02.id, "keyA", "value55".getBytes("UTF-8"))
    val kv03 = createEntityKeyValue(ent03.id, "keyB", "value03".getBytes("UTF-8"))

    val v1 = (ent01.id, "value01")
    val v2 = (ent02.id, "value02")
    val v3 = (ent03.id, "value03")

    val putsKeyA = Seq(
      (ent01.id, "value01".getBytes),
      (ent02.id, "value02".getBytes))

    checkPutKeyValues(Set(v1, v2), Set(ent01.id), Set(ent02.id)) {
      SquerylEntityModel.putKeyValues(putsKeyA, "keyA")
    }

    checkPutKeyValues(Set(v3), Set.empty[UUID], Set.empty[UUID]) {
      SquerylEntityModel.putKeyValues(Seq((ent03.id, "value03".getBytes)), "keyB")
    }
  }

  test("Entity key store delete") {
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))

    val kv01 = createEntityKeyValue(ent01.id, "keyA", "value01".getBytes("UTF-8"))
    val kv02 = createEntityKeyValue(ent02.id, "keyA", "value02".getBytes("UTF-8"))
    val kv03 = createEntityKeyValue(ent03.id, "keyB", "value03".getBytes("UTF-8"))
    val kv04 = createEntityKeyValue(ent02.id, "keyB", "value04".getBytes("UTF-8"))

    val v1 = (ent01.id, "value01")
    val v2 = (ent02.id, "value02")
    val v3 = (ent03.id, "value03")
    val v4 = (ent02.id, "value04")

    checkKeyValues(Set(v1)) {
      SquerylEntityModel.deleteKeyValues(Seq(ent01.id), "keyA")
    }
    checkKeyValues(Set(v2)) {
      SquerylEntityModel.deleteKeyValues(Seq(ent02.id, ent03.id), "keyA")
    }
    checkKeyValues(Set(v3, v4)) {
      SquerylEntityModel.deleteKeyValues(Seq(ent02.id, ent03.id), "keyB")
    }

    checkKeyValues(Set()) {
      SquerylEntityModel.deleteKeyValues(Seq(ent04.id), "keyB")
    }
    checkKeyValues(Set()) {
      SquerylEntityModel.deleteKeyValues(Seq(UUID.randomUUID()), "keyB")
    }
  }

  def checkKeyValues2(correct: Set[(UUID, String, String)])(query: => Seq[EntityKeyValue]) {
    val results = query
    results.size should equal(correct.size)
    results.map(row => (protoUUIDToUuid(row.getUuid), row.getKey, row.getValue.getStringValue)).toSet should equal(correct)
  }

  def strToStored(str: String): StoredValue = {
    StoredValue.newBuilder()
      .setStringValue(str)
      .build()
  }

  test("Entity key store get 2") {
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))

    val kv01 = createEntityKeyValue(ent01.id, "keyA", strToStored("value01").toByteArray)
    val kv02 = createEntityKeyValue(ent02.id, "keyA", strToStored("value02").toByteArray)
    val kv03 = createEntityKeyValue(ent03.id, "keyB", strToStored("value03").toByteArray)
    val kv04 = createEntityKeyValue(ent02.id, "keyB", strToStored("value04").toByteArray)

    val v1 = (ent01.id, "keyA", "value01")
    val v2 = (ent02.id, "keyA", "value02")
    val v3 = (ent03.id, "keyB", "value03")
    val v4 = (ent02.id, "keyB", "value04")

    check(Set(v1)) {
      SquerylEntityModel.getKeyValues2(Seq((ent01.id, "keyA")))
    }

    check(Set(v1, v2)) {
      SquerylEntityModel.getKeyValues2(Seq((ent01.id, "keyA"), (ent02.id, "keyA")))
    }

    check(Set(v1, v2)) {
      SquerylEntityModel.getKeyValues2(Seq((ent01.id, "keyA"), (ent02.id, "keyA"), (ent03.id, "keyA"), (ent04.id, "keyA")))
    }

    check(Set(v3, v4)) {
      SquerylEntityModel.getKeyValues2(Seq((ent01.id, "keyB"), (ent02.id, "keyB"), (ent03.id, "keyB"), (ent04.id, "keyB")))
    }
  }

  test("Entity key store put 2") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))

    val kv02 = createEntityKeyValue(ent02.id, "keyA", strToStored("value55").toByteArray)
    val kv03 = createEntityKeyValue(ent03.id, "keyB", strToStored("value03").toByteArray)

    val v1 = (ent01.id, "keyA", "value01")
    val v2 = (ent02.id, "keyA", "value02")
    val v3 = (ent03.id, "keyB", "value03")

    val putsKeyA = Seq(
      (ent01.id, "keyA", strToStored("value01")),
      (ent02.id, "keyA", strToStored("value02")))

    check(Set(v1, v2)) {
      SquerylEntityModel.putKeyValues2(notifier, putsKeyA)
    }

    check(Set(v3)) {
      SquerylEntityModel.putKeyValues2(notifier, Seq((ent03.id, "keyB", strToStored("value03"))))
    }

    notifier.checkEntityKeyValues(Set(
      (Created, v1),
      (Updated, v2)))
  }

  test("Entity key store delete 2") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeA"))
    val ent03 = createEntity("ent03", Seq("typeA"))
    val ent04 = createEntity("ent04", Seq("typeA"))

    val kv01 = createEntityKeyValue(ent01.id, "keyA", strToStored("value01").toByteArray)
    val kv02 = createEntityKeyValue(ent02.id, "keyA", strToStored("value02").toByteArray)
    val kv03 = createEntityKeyValue(ent03.id, "keyB", strToStored("value03").toByteArray)
    val kv04 = createEntityKeyValue(ent02.id, "keyB", strToStored("value04").toByteArray)

    val v1 = (ent01.id, "keyA", "value01")
    val v2 = (ent02.id, "keyA", "value02")
    val v3 = (ent03.id, "keyB", "value03")
    val v4 = (ent02.id, "keyB", "value04")

    check(Set(v1)) {
      SquerylEntityModel.deleteKeyValues2(notifier, Seq((ent01.id, "keyA")))
    }
    check(Set(v2)) {
      SquerylEntityModel.deleteKeyValues2(notifier, Seq((ent02.id, "keyA"), (ent03.id, "keyA")))
    }
    check(Set(v3, v4)) {
      SquerylEntityModel.deleteKeyValues2(notifier, Seq((ent02.id, "keyB"), (ent03.id, "keyB")))
    }

    check(Set()) {
      SquerylEntityModel.deleteKeyValues2(notifier, Seq((ent04.id, "keyB")))
    }
    check(Set()) {
      SquerylEntityModel.deleteKeyValues2(notifier, Seq((UUID.randomUUID(), "keyB")))
    }

    notifier.checkEntityKeyValues(Set(
      (Deleted, v1),
      (Deleted, v2),
      (Deleted, v3),
      (Deleted, v4)))
  }

}
