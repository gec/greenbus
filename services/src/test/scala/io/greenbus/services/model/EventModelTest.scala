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
import io.greenbus.services.framework.{ Deleted, Updated, Created }
import io.greenbus.client.service.proto.Events.{ Alarm, EventConfig, Attribute }
import io.greenbus.services.model.EventAlarmModel.{ EventQueryParams, SysEventTemplate, EventConfigTemp }
import java.util.UUID
import io.greenbus.services.authz.EntityFilter
import org.squeryl.Query
import io.greenbus.services.data.{ EventRow, ServicesSchema, EntityRow }

@RunWith(classOf[JUnitRunner])
class EventModelTest extends ServiceTestBase {
  import ModelTestHelpers._

  test("EventConfig query") {
    val ec01 = createEventConfig("type01", 9, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", false)
    val ec02 = createEventConfig("type02", 8, EventConfig.Designation.ALARM, Alarm.State.ACKNOWLEDGED, "res01", false)
    val ec03 = createEventConfig("type03", 7, EventConfig.Designation.LOG, Alarm.State.UNACK_AUDIBLE, "res01", false)
    val ec04 = createEventConfig("type04", 6, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", false)
    val ec05 = createEventConfig("type05", 5, EventConfig.Designation.ALARM, Alarm.State.ACKNOWLEDGED, "res01", false)
    val ec06 = createEventConfig("type06", 4, EventConfig.Designation.LOG, Alarm.State.UNACK_AUDIBLE, "res01", false)

    val r1 = ("type01", 9, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", false)
    val r2 = ("type02", 8, EventConfig.Designation.ALARM, Alarm.State.ACKNOWLEDGED, "res01", false)
    val r3 = ("type03", 7, EventConfig.Designation.LOG, Alarm.State.UNACK_AUDIBLE, "res01", false)
    val r4 = ("type04", 6, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", false)
    val r5 = ("type05", 5, EventConfig.Designation.ALARM, Alarm.State.ACKNOWLEDGED, "res01", false)
    val r6 = ("type06", 4, EventConfig.Designation.LOG, Alarm.State.UNACK_AUDIBLE, "res01", false)
    val all = Set(r1, r2, r3, r4, r5, r6)

    check(Set(r1, r3, r5)) {
      SquerylEventAlarmModel.eventConfigQuery(List(9, 7, 5), None, Nil, Nil, None, 100)
    }
    check(Set(r4, r5, r6)) {
      SquerylEventAlarmModel.eventConfigQuery(Nil, Some(6), Nil, Nil, None, 100)
    }
    check(Set(r2, r3, r5, r6)) {
      SquerylEventAlarmModel.eventConfigQuery(Nil, None, List(EventConfig.Designation.ALARM, EventConfig.Designation.LOG), Nil, None, 100)
    }
    check(Set(r1, r2, r4, r5)) {
      SquerylEventAlarmModel.eventConfigQuery(Nil, None, Nil, List(Alarm.State.UNACK_SILENT, Alarm.State.ACKNOWLEDGED), None, 100)
    }

    check(Set(r1, r2)) {
      SquerylEventAlarmModel.eventConfigQuery(Nil, None, Nil, Nil, None, 2)
    }
    check(Set(r3, r4)) {
      SquerylEventAlarmModel.eventConfigQuery(Nil, None, Nil, Nil, Some("type02"), 2)
    }
    check(Set(r5, r6)) {
      SquerylEventAlarmModel.eventConfigQuery(Nil, None, Nil, Nil, Some("type04"), 2)
    }
  }

  test("EventConfig get") {
    val ec01 = createEventConfig("type01", 5, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", true)
    val ec02 = createEventConfig("type02", 20, EventConfig.Designation.ALARM, Alarm.State.ACKNOWLEDGED, "res02", false)

    val r1 = ("type01", 5, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", true)
    val r2 = ("type02", 20, EventConfig.Designation.ALARM, Alarm.State.ACKNOWLEDGED, "res02", false)
    val all = Set(r1, r2)

    check(all) {
      SquerylEventAlarmModel.getEventConfigs(Seq("type01", "type02"))
    }

    check(Set(r1)) {
      SquerylEventAlarmModel.getEventConfigs(Seq("type01"))
    }
    check(Set(r2)) {
      SquerylEventAlarmModel.getEventConfigs(Seq("type02"))
    }

  }

  test("EventConfig put") {
    val notifier = new TestModelNotifier
    val ec01 = createEventConfig("type01", 1, EventConfig.Designation.LOG, Alarm.State.UNACK_AUDIBLE, "resA", false)

    val r1 = ("type01", 5, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", false)
    val r2 = ("type02", 20, EventConfig.Designation.ALARM, Alarm.State.ACKNOWLEDGED, "res02", false)
    val all = Set(r1, r2)

    val puts = Seq(
      EventConfigTemp("type01", 5, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01"),
      EventConfigTemp("type02", 20, EventConfig.Designation.ALARM, Alarm.State.ACKNOWLEDGED, "res02"))

    check(all) {
      SquerylEventAlarmModel.putEventConfigs(notifier, puts)
    }

    notifier.checkEventConfigs(Set(
      (Updated, r1),
      (Created, r2)))
  }

  test("EventConfig delete") {
    val notifier = new TestModelNotifier
    val ec01 = createEventConfig("type01", 5, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", true)
    val ec02 = createEventConfig("type02", 20, EventConfig.Designation.ALARM, Alarm.State.ACKNOWLEDGED, "res02", false)

    val r1 = ("type01", 5, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", true)
    val r2 = ("type02", 20, EventConfig.Designation.ALARM, Alarm.State.ACKNOWLEDGED, "res02", false)
    val all = Set(r1, r2)

    check(all) {
      SquerylEventAlarmModel.deleteEventConfigs(notifier, Seq("type01", "type02"))
    }

    notifier.checkEventConfigs(Set(
      (Deleted, r1),
      (Deleted, r2)))
  }

  test("Event post simple") {
    val notifier = new TestModelNotifier
    val ec01 = createEventConfig("type01", 9, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", false)
    val ec02 = createEventConfig("type02", 3, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res02", false)

    val e1: (String, Int, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = ("type01", 9, "user01", false, "sub01", "res01", Option.empty[UUID], None, Some(3L))
    val e2 = ("type02", 3, "user02", false, "sub01", "res02", None, None, Some(5L))

    val puts = Seq(
      SysEventTemplate("user01", "type01", Some("sub01"), Some(3), None, None, Seq()),
      SysEventTemplate("user02", "type02", Some("sub01"), Some(5), None, None, Seq()))

    check(Set(e1, e2)) {
      SquerylEventAlarmModel.postEvents(notifier, puts)
    }

    notifier.checkEvents(Set(
      (Created, e1),
      (Created, e2)))
  }

  test("Event post all fields") {
    val notifier = new TestModelNotifier
    val ec01 = createEventConfig("type01", 9, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", false)
    val ec02 = createEventConfig("type02", 3, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res02", false)

    val uuid1 = UUID.randomUUID()
    val uuid2 = UUID.randomUUID()

    val e1: (String, Int, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = ("type01", 9, "user01", false, "sub01", "res01", Some(uuid1), Some("group01"), Some(3L))
    val e2 = ("type02", 3, "user02", false, "sub01", "res02", Some(uuid2), Some("group02"), Some(5L))

    val puts = Seq(
      SysEventTemplate("user01", "type01", Some("sub01"), Some(3), Some(UUIDHelpers.uuidToProtoUUID(uuid1)), Some("group01"), Seq()),
      SysEventTemplate("user02", "type02", Some("sub01"), Some(5), Some(UUIDHelpers.uuidToProtoUUID(uuid2)), Some("group02"), Seq()))

    check(Set(e1, e2)) {
      SquerylEventAlarmModel.postEvents(notifier, puts)
    }

    notifier.checkEvents(Set(
      (Created, e1),
      (Created, e2)))
  }

  test("Event post multiple types") {
    val notifier = new TestModelNotifier
    val ec01 = createEventConfig("type01", 9, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", false)
    val ec02 = createEventConfig("type02", 20, EventConfig.Designation.ALARM, Alarm.State.UNACK_SILENT, "res02", false)
    val ec03 = createEventConfig("type03", 1, EventConfig.Designation.LOG, Alarm.State.UNACK_SILENT, "res03", false)

    val e1: (String, Int, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = ("type01", 9, "user01", false, "sub01", "res01", None, None, Some(3L))
    val e2 = ("type02", 20, "user02", true, "sub01", "res02", None, None, Some(5L))
    val e3 = ("type03", 1, "user03", false, "sub01", "res03", None, None, Some(8L))

    val a1 = (Alarm.State.UNACK_SILENT, "type02", 20, "user02", true, "sub01", "res02", None, Some(5L))

    val puts = Seq(
      SysEventTemplate("user01", "type01", Some("sub01"), Some(3), None, None, Seq()),
      SysEventTemplate("user02", "type02", Some("sub01"), Some(5), None, None, Seq()),
      SysEventTemplate("user03", "type03", Some("sub01"), Some(8), None, None, Seq()))

    check(Set(e1, e2, e3)) {
      SquerylEventAlarmModel.postEvents(notifier, puts)
    }

    notifier.checkEvents(Set(
      (Created, e1),
      (Created, e2),
      (Created, e3)))

    notifier.checkAlarms(Set(
      (Created, a1)))
  }

  test("Event post alarm initial") {
    val notifier = new TestModelNotifier
    val ec01 = createEventConfig("type01", 9, EventConfig.Designation.ALARM, Alarm.State.UNACK_SILENT, "res01", false)
    val ec02 = createEventConfig("type02", 3, EventConfig.Designation.ALARM, Alarm.State.UNACK_AUDIBLE, "res02", false)
    val ec03 = createEventConfig("type03", 5, EventConfig.Designation.ALARM, Alarm.State.ACKNOWLEDGED, "res03", false)

    val e1: (String, Int, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) =
      ("type01", 9, "user01", true, "sub01", "res01", Option.empty[UUID], None, Some(3L))
    val e2 = ("type02", 3, "user02", true, "sub01", "res02", None, None, Some(5L))
    val e3 = ("type03", 5, "user03", true, "sub01", "res03", None, None, Some(8L))

    val a1: (Alarm.State, String, Int, String, Boolean, String, String, Option[UUID], Option[Long]) =
      (Alarm.State.UNACK_SILENT, "type01", 9, "user01", true, "sub01", "res01", Option.empty[UUID], Some(3L))
    val a2 = (Alarm.State.UNACK_AUDIBLE, "type02", 3, "user02", true, "sub01", "res02", None, Some(5L))
    val a3 = (Alarm.State.ACKNOWLEDGED, "type03", 5, "user03", true, "sub01", "res03", None, Some(8L))

    val allEvents = Set(e1, e2, e3)
    val allAlarms = Set(a1, a2, a3)

    val puts = Seq(
      SysEventTemplate("user01", "type01", Some("sub01"), Some(3), None, None, Seq()),
      SysEventTemplate("user02", "type02", Some("sub01"), Some(5), None, None, Seq()),
      SysEventTemplate("user03", "type03", Some("sub01"), Some(8), None, None, Seq()))

    check(allEvents) {
      SquerylEventAlarmModel.postEvents(notifier, puts)
    }

    notifier.checkEvents(allEvents.map(e => (Created, e)))
    notifier.checkAlarms(allAlarms.map(a => (Created, a)))
  }

  test("Event post without config is alarm") {
    val notifier = new TestModelNotifier
    val ec02 = createEventConfig("type02", 3, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res02", false)

    val e1: (String, Int, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) =
      ("NON_EXISTENT_TYPE", EventAlarmModel.unconfiguredEventSeverity, "user01", true, "sub01", EventAlarmModel.unconfiguredEventMessage, Option.empty[UUID], None, Some(3L))
    val e2 = ("type02", 3, "user02", false, "sub01", "res02", None, None, Some(5L))

    val a1: (Alarm.State, String, Int, String, Boolean, String, String, Option[UUID], Option[Long]) =
      (Alarm.State.UNACK_SILENT, "NON_EXISTENT_TYPE", EventAlarmModel.unconfiguredEventSeverity, "user01", true, "sub01", EventAlarmModel.unconfiguredEventMessage, Option.empty[UUID], Some(3L))

    val puts = Seq(
      SysEventTemplate("user01", "NON_EXISTENT_TYPE", Some("sub01"), Some(3), None, None, Seq()),
      SysEventTemplate("user02", "type02", Some("sub01"), Some(5), None, None, Seq()))

    check(Set(e1, e2)) {
      SquerylEventAlarmModel.postEvents(notifier, puts)
    }

    notifier.checkEvents(Set(
      (Created, e1),
      (Created, e2)))

    notifier.checkAlarms(Set(
      (Created, a1)))
  }

  test("Event post message") {
    val notifier = new TestModelNotifier
    val ec01 = createEventConfig("type01", 9, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "test resource {key01} {key02}", false)

    val alist = Seq(Attribute.newBuilder()
      .setName("key01")
      .setValueString("v01")
      .build(),
      Attribute.newBuilder()
        .setName("key02")
        .setValueString("v02")
        .build())

    val e1: (String, Int, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) =
      ("type01", 9, "user01", false, "sub01", "test resource v01 v02", Option.empty[UUID], None, Some(3L))

    val puts = Seq(
      SysEventTemplate("user01", "type01", Some("sub01"), Some(3), None, None, alist))

    check(Set(e1)) {
      SquerylEventAlarmModel.postEvents(notifier, puts)
    }

    notifier.checkEvents(Set(
      (Created, e1)))
  }

  test("Event post create filter") {
    import UUIDHelpers._
    val notifier = new TestModelNotifier
    val ent01 = createEntity("point01", Seq("Point"))
    val ent02 = createEntity("point02", Seq("Point"))
    val ec01 = createEventConfig("type01", 9, EventConfig.Designation.EVENT, Alarm.State.UNACK_SILENT, "res01", false)

    val filter = new EntityFilter {
      import org.squeryl.PrimitiveTypeMode._
      protected def filterQuery: Query[EntityRow] = {
        ServicesSchema.entities.where(ent => ent.id === ent01.id)
      }
    }

    val e1: (String, Int, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = ("type01", 9, "user01", false, "sub01", "res01", Some(ent01.id), None, Some(3L))

    check(Set(e1)) {
      SquerylEventAlarmModel.postEvents(notifier, Seq(SysEventTemplate("user01", "type01", Some("sub01"), Some(3), Some(uuidToProtoUUID(ent01.id)), None, Seq())), Some(filter))
    }

    intercept[ModelPermissionException] {
      SquerylEventAlarmModel.postEvents(notifier, Seq(SysEventTemplate("user01", "type01", Some("sub01"), Some(3), Some(uuidToProtoUUID(ent02.id)), None, Seq())), Some(filter))
    }
    intercept[ModelPermissionException] {
      SquerylEventAlarmModel.postEvents(notifier, Seq(SysEventTemplate("user01", "type01", Some("sub01"), Some(3), None, None, Seq())), Some(filter))
    }

  }

  class EventQueryFixture {
    val ent01 = createEntity("point01", Seq("Point"))
    val ent02 = createEntity("point02", Seq("Point"))
    val ev01 = createEvent("type01", false, 1, None, 2, "sub01", "agent01", None, None, Array.empty[Byte], "message01")
    val ev02 = createEvent("type02", true, 2, None, 5, "sub02", "agent01", Some(ent01.id), Some("group01"), Array.empty[Byte], "message02")
    val ev03 = createEvent("type03", false, 3, None, 5, "sub01", "agent02", Some(ent02.id), Some("group02"), Array.empty[Byte], "message03")
    val ev04 = createEvent("type01", false, 4, None, 7, "sub01", "agent02", Some(ent01.id), Some("group01"), Array.empty[Byte], "message04")
    val ev05 = createEvent("type02", true, 5, None, 8, "sub03", "agent03", Some(ent02.id), Some("group02"), Array.empty[Byte], "message05")
    val ev06 = createEvent("type03", false, 6, None, 8, "sub01", "agent03", None, None, Array.empty[Byte], "message06")

    val e1: (String, Int, Long, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = ("type01", 2, 1, "agent01", false, "sub01", "message01", None, None, None)
    val e2: (String, Int, Long, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = ("type02", 5, 2, "agent01", true, "sub02", "message02", Some(ent01.id), Some("group01"), None)
    val e3: (String, Int, Long, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = ("type03", 5, 3, "agent02", false, "sub01", "message03", Some(ent02.id), Some("group02"), None)
    val e4: (String, Int, Long, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = ("type01", 7, 4, "agent02", false, "sub01", "message04", Some(ent01.id), Some("group01"), None)
    val e5: (String, Int, Long, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = ("type02", 8, 5, "agent03", true, "sub03", "message05", Some(ent02.id), Some("group02"), None)
    val e6: (String, Int, Long, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = ("type03", 8, 6, "agent03", false, "sub01", "message06", None, None, None)
  }

  test("Event query") {
    val f = new EventQueryFixture
    import f._

    val all = Seq(e1, e2, e3, e4, e5, e6)

    checkOrder(all) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams())
    }

    checkOrder(Seq(e1, e2, e4, e5)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(eventTypes = Seq("type01", "type02")))
    }
    checkOrder(Seq(e1, e5, e6)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(severities = List(2, 8)))
    }
    checkOrder(Seq(e1, e2, e3)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(severityOrHigher = Some(5)))
    }
    checkOrder(Seq(e2, e5)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(subsystems = List("sub02", "sub03")))
    }
    checkOrder(Seq(e1, e2, e5, e6)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(agents = List("agent01", "agent03")))
    }

    checkOrder(Seq(e2, e4)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(entityUuids = List(ent01.id)))
    }
    checkOrder(Seq(e3, e5)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(entityNames = List("point02")))
    }

    checkOrder(Seq(e2, e4)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(modelGroups = Seq("group01")))
    }
    checkOrder(Seq(e2, e3, e4, e5)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(modelGroups = Seq("group01", "group02")))
    }

    checkOrder(Seq(e2, e5)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(isAlarm = Some(true)))
    }
    checkOrder(Seq(e1, e3, e4, e6)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(isAlarm = Some(false)))
    }

    checkOrder(Seq(e4, e5, e6)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(timeFrom = Some(4)))
    }
    checkOrder(Seq(e1, e2, e3)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(timeTo = Some(3)))
    }
    checkOrder(Seq(e3, e4, e5)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(timeFrom = Some(3), timeTo = Some(5)))
    }

    checkOrder(Seq(e5, e6)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(pageSize = 2))
    }
    checkOrder(Seq(e1, e2)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(pageSize = 2, latest = false))
    }
  }

  test("Event read filtering") {
    val f = new EventQueryFixture
    import f._

    val filter = new EntityFilter {
      import org.squeryl.PrimitiveTypeMode._
      protected def filterQuery: Query[EntityRow] = {
        ServicesSchema.entities.where(ent => ent.id === ent01.id)
      }
    }

    checkOrder(Seq(e2, e4)) {
      SquerylEventAlarmModel.getEvents(Seq(ev01.id, ev02.id, ev03.id, ev04.id, ev05.id, ev06.id), filter = Some(filter))
    }

    checkOrder(Seq(e2, e4)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(), filter = Some(filter))
    }

    checkOrder(Seq(e4)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(eventTypes = Seq("type01")), filter = Some(filter))
    }

  }

  test("Event sub-milli paging") {

    def createSimpleEvent(id: Int, time: Long) = createEvent("type" + id, false, time, None, 2, "sub01", "agent01", None, None, Array.empty[Byte], "message01")

    def simpleEventTuple(id: Int, time: Long): (String, Int, Long, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = ("type" + id, 2, time, "agent01", false, "sub01", "message01", None, None, None)

    val ev01 = createSimpleEvent(1, 1)
    val ev02 = createSimpleEvent(2, 2)
    val ev03 = createSimpleEvent(3, 2)
    val ev04 = createSimpleEvent(4, 3)
    val ev05 = createSimpleEvent(5, 3)
    val ev06 = createSimpleEvent(6, 3)

    val e1 = simpleEventTuple(1, 1)
    val e2 = simpleEventTuple(2, 2)
    val e3 = simpleEventTuple(3, 2)
    val e4 = simpleEventTuple(4, 3)
    val e5 = simpleEventTuple(5, 3)
    val e6 = simpleEventTuple(6, 3)

    checkOrder(Seq(e1, e2)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(pageSize = 2, latest = false))
    }
    checkOrder(Seq(e3, e4)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(pageSize = 2, last = Some(ev02.id), latest = false))
    }
    checkOrder(Seq(e5, e6)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(pageSize = 2, last = Some(ev04.id), latest = false))
    }

    checkOrder(Seq(e5, e6)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(pageSize = 2, latest = true))
    }
    checkOrder(Seq(e3, e4)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(pageSize = 2, last = Some(ev05.id), latest = true))
    }
    checkOrder(Seq(e1, e2)) {
      SquerylEventAlarmModel.eventQuery(EventQueryParams(pageSize = 2, last = Some(ev03.id), latest = true))
    }
  }

  class AlarmFixture {
    val ent01 = createEntity("point01", Seq("Point"))
    val ent02 = createEntity("point02", Seq("Point"))
    val ev01 = createEvent("type01", true, 1, None, 2, "sub01", "agent01", None, None, Array.empty[Byte], "message01")
    val ev02 = createEvent("type02", true, 2, None, 5, "sub02", "agent01", Some(ent01.id), None, Array.empty[Byte], "message02")
    val ev03 = createEvent("type03", true, 3, None, 5, "sub01", "agent02", Some(ent02.id), None, Array.empty[Byte], "message03")
    val ev04 = createEvent("type01", true, 4, None, 7, "sub01", "agent02", Some(ent01.id), None, Array.empty[Byte], "message04")
    val ev05 = createEvent("type02", true, 5, None, 8, "sub03", "agent03", Some(ent02.id), None, Array.empty[Byte], "message05")
    val ev06 = createEvent("type03", true, 6, None, 8, "sub01", "agent03", None, None, Array.empty[Byte], "message06")

    val al01 = createAlarm(ev01.id, Alarm.State.UNACK_SILENT)
    val al02 = createAlarm(ev02.id, Alarm.State.UNACK_SILENT)
    val al03 = createAlarm(ev03.id, Alarm.State.UNACK_AUDIBLE)
    val al04 = createAlarm(ev04.id, Alarm.State.UNACK_AUDIBLE)
    val al05 = createAlarm(ev05.id, Alarm.State.ACKNOWLEDGED)
    val al06 = createAlarm(ev06.id, Alarm.State.REMOVED)

    val a1 = (ev01.id, Alarm.State.UNACK_SILENT)
    val a2 = (ev02.id, Alarm.State.UNACK_SILENT)
    val a3 = (ev03.id, Alarm.State.UNACK_AUDIBLE)
    val a4 = (ev04.id, Alarm.State.UNACK_AUDIBLE)
    val a5 = (ev05.id, Alarm.State.ACKNOWLEDGED)
    val a6 = (ev06.id, Alarm.State.REMOVED)

    val allItems = Seq(a1, a2, a3, a4, a5, a6)
  }

  test("Alarm query") {
    val f = new AlarmFixture
    import f._

    checkOrder(allItems) {
      SquerylEventAlarmModel.alarmQuery(Nil, EventQueryParams())
    }

    checkOrder(Seq(a3, a4, a5)) {
      SquerylEventAlarmModel.alarmQuery(List(Alarm.State.UNACK_AUDIBLE, Alarm.State.ACKNOWLEDGED), EventQueryParams())
    }

    checkOrder(Seq(a1, a2)) {
      SquerylEventAlarmModel.alarmQuery(List(Alarm.State.UNACK_SILENT), EventQueryParams(severityOrHigher = Some(5)))
    }

    checkOrder(Seq(a5, a6)) {
      SquerylEventAlarmModel.alarmQuery(Nil, EventQueryParams(pageSize = 2))
    }
    checkOrder(Seq(a1, a2)) {
      SquerylEventAlarmModel.alarmQuery(Nil, EventQueryParams(pageSize = 2, latest = false))
    }

  }

  test("Alarm sub-milli paging") {

    def createSimpleEvent(id: Int, time: Long) = createEvent("type" + id, true, time, None, 2, "sub01", "agent01", None, None, Array.empty[Byte], "message01")

    val ev01 = createSimpleEvent(1, 1)
    val ev02 = createSimpleEvent(2, 2)
    val ev03 = createSimpleEvent(3, 2)
    val ev04 = createSimpleEvent(4, 3)
    val ev05 = createSimpleEvent(5, 3)
    val ev06 = createSimpleEvent(6, 3)

    def createSimpleAlarm(e: EventRow) = {
      createAlarm(e.id, Alarm.State.UNACK_SILENT)
    }

    def simpleAlarmTuple(id: Long): (Long, Alarm.State) = (id, Alarm.State.UNACK_SILENT)

    val al01 = createSimpleAlarm(ev01)
    val al02 = createSimpleAlarm(ev02)
    val al03 = createSimpleAlarm(ev03)
    val al04 = createSimpleAlarm(ev04)
    val al05 = createSimpleAlarm(ev05)
    val al06 = createSimpleAlarm(ev06)

    val e1 = simpleAlarmTuple(ev01.id)
    val e2 = simpleAlarmTuple(ev02.id)
    val e3 = simpleAlarmTuple(ev03.id)
    val e4 = simpleAlarmTuple(ev04.id)
    val e5 = simpleAlarmTuple(ev05.id)
    val e6 = simpleAlarmTuple(ev06.id)

    checkOrder(Seq(e1, e2)) {
      SquerylEventAlarmModel.alarmQuery(Seq(), EventQueryParams(pageSize = 2, latest = false))
    }
    checkOrder(Seq(e3, e4)) {
      SquerylEventAlarmModel.alarmQuery(Seq(), EventQueryParams(pageSize = 2, last = Some(al02.id), latest = false))
    }
    checkOrder(Seq(e5, e6)) {
      SquerylEventAlarmModel.alarmQuery(Seq(), EventQueryParams(pageSize = 2, last = Some(al04.id), latest = false))
    }

    checkOrder(Seq(e5, e6)) {
      SquerylEventAlarmModel.alarmQuery(Seq(), EventQueryParams(pageSize = 2, latest = true))
    }
    checkOrder(Seq(e3, e4)) {
      SquerylEventAlarmModel.alarmQuery(Seq(), EventQueryParams(pageSize = 2, last = Some(al05.id), latest = true))
    }
    checkOrder(Seq(e1, e2)) {
      SquerylEventAlarmModel.alarmQuery(Seq(), EventQueryParams(pageSize = 2, last = Some(al03.id), latest = true))
    }
  }

  test("Alarm read filtering") {
    val f = new AlarmFixture
    import f._

    val filter = new EntityFilter {
      import org.squeryl.PrimitiveTypeMode._
      protected def filterQuery: Query[EntityRow] = {
        ServicesSchema.entities.where(ent => ent.id === ent01.id)
      }
    }

    checkOrder(Seq(a2, a4)) {
      SquerylEventAlarmModel.alarmKeyQuery(Seq(al01.id, al02.id, al03.id, al04.id, al05.id, al06.id), filter = Some(filter))
    }

    checkOrder(Seq(a2, a4)) {
      SquerylEventAlarmModel.alarmQuery(Nil, EventQueryParams(), filter = Some(filter))
    }

    checkOrder(Seq(a4)) {
      SquerylEventAlarmModel.alarmQuery(Nil, EventQueryParams(eventTypes = Seq("type01")), filter = Some(filter))
    }

  }

  test("Alarm update") {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("point01", Seq("Point"))
    val ev01 = createEvent("type01", true, 1, None, 2, "sub01", "agent01", None, None, Array.empty[Byte], "message01")
    val al01 = createAlarm(ev01.id, Alarm.State.UNACK_SILENT)

    intercept[ModelInputException] {
      SquerylEventAlarmModel.putAlarmStates(notifier, Seq((al01.id, Alarm.State.UNACK_AUDIBLE)))
    }

    check(Set((ev01.id, Alarm.State.ACKNOWLEDGED))) {
      SquerylEventAlarmModel.putAlarmStates(notifier, Seq((al01.id, Alarm.State.ACKNOWLEDGED)))
    }

    intercept[ModelInputException] {
      SquerylEventAlarmModel.putAlarmStates(notifier, Seq((al01.id, Alarm.State.UNACK_SILENT)))
    }

    intercept[ModelInputException] {
      SquerylEventAlarmModel.putAlarmStates(notifier, Seq((al01.id, Alarm.State.UNACK_AUDIBLE)))
    }

    check(Set((ev01.id, Alarm.State.REMOVED))) {
      SquerylEventAlarmModel.putAlarmStates(notifier, Seq((al01.id, Alarm.State.REMOVED)))
    }

    intercept[ModelInputException] {
      SquerylEventAlarmModel.putAlarmStates(notifier, Seq((al01.id, Alarm.State.ACKNOWLEDGED)))
    }
  }

  class AlarmMultiFixture {
    val notifier = new TestModelNotifier
    val ent01 = createEntity("point01", Seq("Point"))
    val ent02 = createEntity("point02", Seq("Point"))
    val ev01 = createEvent("type01", true, 1, None, 2, "sub01", "agent01", None, None, Array.empty[Byte], "message01")
    val ev02 = createEvent("type02", true, 2, None, 5, "sub02", "agent01", Some(ent01.id), None, Array.empty[Byte], "message02")
    val ev03 = createEvent("type03", true, 3, None, 5, "sub01", "agent02", Some(ent02.id), None, Array.empty[Byte], "message03")

    val al01 = createAlarm(ev01.id, Alarm.State.UNACK_SILENT)
    val al02 = createAlarm(ev02.id, Alarm.State.UNACK_SILENT)
    val al03 = createAlarm(ev03.id, Alarm.State.UNACK_AUDIBLE)
  }

  test("Alarm update multi") {
    val f = new AlarmMultiFixture
    import f._

    val a1 = (ev01.id, Alarm.State.UNACK_SILENT)
    val a2 = (ev02.id, Alarm.State.UNACK_SILENT)
    val a3 = (ev03.id, Alarm.State.UNACK_AUDIBLE)

    check(Set((ev01.id, Alarm.State.ACKNOWLEDGED), (ev02.id, Alarm.State.UNACK_SILENT), (ev03.id, Alarm.State.ACKNOWLEDGED))) {
      SquerylEventAlarmModel.putAlarmStates(notifier, Seq((al01.id, Alarm.State.ACKNOWLEDGED), (al02.id, Alarm.State.UNACK_SILENT), (al03.id, Alarm.State.ACKNOWLEDGED)))
    }

    notifier.checkAlarmsSimple(Set(
      (Updated, (ev01.id, Alarm.State.ACKNOWLEDGED)),
      (Updated, (ev03.id, Alarm.State.ACKNOWLEDGED))))
  }

  test("Alarm update auth") {
    val f = new AlarmMultiFixture
    import f._

    val filter = new EntityFilter {
      import org.squeryl.PrimitiveTypeMode._
      protected def filterQuery: Query[EntityRow] = {
        ServicesSchema.entities.where(ent => ent.id === ent01.id)
      }
    }

    check(Set((ev02.id, Alarm.State.UNACK_SILENT))) {
      SquerylEventAlarmModel.putAlarmStates(notifier, Seq((al02.id, Alarm.State.UNACK_SILENT)), Some(filter))
    }

    intercept[ModelPermissionException] {
      SquerylEventAlarmModel.putAlarmStates(notifier, Seq((al01.id, Alarm.State.ACKNOWLEDGED)), Some(filter))
    }

    intercept[ModelPermissionException] {
      SquerylEventAlarmModel.putAlarmStates(notifier, Seq((al03.id, Alarm.State.ACKNOWLEDGED)), Some(filter))
    }
  }

}
