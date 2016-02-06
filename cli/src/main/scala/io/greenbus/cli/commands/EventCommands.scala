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
package io.greenbus.cli.commands

import io.greenbus.cli.{ CliContext, Command }
import io.greenbus.cli.view._
import scala.concurrent.Await
import scala.concurrent.duration._
import io.greenbus.client.service.{ ModelService, EventService }
import io.greenbus.client.service.proto.EventRequests._
import io.greenbus.client.service.proto.Events.{ Alarm, EventConfig, Attribute }
import io.greenbus.client.service.proto.ModelRequests.EntityKeySet
import io.greenbus.client.service.proto.Model.ModelID
import scala.collection.JavaConversions._
import java.util.concurrent.atomic.AtomicReference

class EventConfigListCommmand extends Command[CliContext] {

  val commandName = "event-config:list"
  val description = "List all event configurations"

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    def getResults(last: Option[String], pageSize: Int) = {
      val queryBuilder = EventConfigQuery.newBuilder().setPageSize(pageSize)
      last.foreach(queryBuilder.setLastEventType)
      val query = queryBuilder.build()
      client.eventConfigQuery(query)
    }

    def pageBoundary(results: Seq[EventConfig]) = {
      results.last.getEventType
    }

    Paging.page(context.reader, getResults, pageBoundary, EventConfigView.printTable, EventConfigView.printRows)
  }
}

class EventConfigViewCommmand extends Command[CliContext] {

  val commandName = "event-config:view"
  val description = "View particular event configurations"

  val eventTypes = strings.argRepeated("event type", "Event type")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    if (eventTypes.value.isEmpty) {
      throw new IllegalArgumentException("Must include at least one event type")
    }

    val results = Await.result(client.getEventConfigs(eventTypes.value), 5000.milliseconds)
    EventConfigView.printTable(results.toList)
  }
}

class EventConfigDeleteCommmand extends Command[CliContext] {

  val commandName = "event-config:delete"
  val description = "Delete event configurations"

  val eventTypes = strings.argRepeated("event type", "Event type")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    if (eventTypes.value.isEmpty) {
      throw new IllegalArgumentException("Must include at least one event type")
    }

    val results = Await.result(client.deleteEventConfigs(eventTypes.value), 5000.milliseconds)
    EventConfigView.printTable(results.toList)
  }
}

class EventConfigPutCommmand extends Command[CliContext] {

  val commandName = "event-config:put"
  val description = "Create/modify event configurations"

  val eventType = strings.arg("event type", "Event type")
  val resource = strings.arg("resource", "Resource string to be rendered for events")

  val severity = ints.option(Some("s"), Some("severity"), "Severity of processed events")

  val alarm = optionSwitch(Some("a"), Some("alarm"), "Event generates an alarm")

  val log = optionSwitch(Some("l"), Some("log"), "Event is a log entry")

  val silent = optionSwitch(Some("q"), Some("silent"), "Alarm is silent (used with --alarm)")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    val designation = (alarm.value, log.value) match {
      case (true, true) => throw new IllegalArgumentException("Cannot set both --alarm and --log flags.")
      case (true, false) => EventConfig.Designation.ALARM
      case (false, true) => EventConfig.Designation.LOG
      case (false, false) => EventConfig.Designation.EVENT
    }

    if (!alarm.value && silent.value) {
      throw new IllegalArgumentException("Cannot use --silent when --alarm not set")
    }

    val alarmState = if (silent.value) Alarm.State.UNACK_SILENT else Alarm.State.UNACK_AUDIBLE

    val template = EventConfigTemplate.newBuilder()
      .setEventType(eventType.value)
      .setResource(resource.value)
      .setSeverity(severity.value getOrElse 4)
      .setDesignation(designation)
      .setAlarmState(alarmState)
      .build()

    val results = Await.result(client.putEventConfigs(List(template)), 5000.milliseconds)
    EventConfigView.printTable(results.toList)
  }
}

class EventPostCommmand extends Command[CliContext] {

  val commandName = "event:post"
  val description = "Post an event. Refer to event configs for types and arguments."

  val eventType = strings.arg("event type", "Event type")

  val subsystem = strings.option(None, Some("subsystem"), "Subsystem event applies to")

  val entity = strings.option(None, Some("entity"), "Entity name event applies to")

  val args = strings.optionRepeated(Some("a"), Some("arg"), "Attribute, in form \"key=value\"")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    val attrPairs = args.value.map { s =>
      val parts = s.split("=")
      parts.toList match {
        case List(key, value) => (key, value)
        case other => throw new IllegalArgumentException(s"Couldn't parse argument $s")
      }
    }

    val entUuid = entity.value.map { name =>
      val entClient = ModelService.client(context.session)
      val results = Await.result(entClient.get(EntityKeySet.newBuilder().addNames(name).build()), 5000.milliseconds)
      if (results.size == 1) {
        results.head.getUuid
      } else {
        throw new IllegalArgumentException(s"Couldn't find entity with name $name")
      }
    }

    val attributes = attrPairs.map {
      case (key, value) =>
        Attribute.newBuilder()
          .setName(key)
          .setValueString(value)
          .build()
    }

    val b = EventTemplate.newBuilder()
      .setEventType(eventType.value)
      .addAllArgs(attributes)

    subsystem.value.foreach(b.setSubsystem)
    entUuid.foreach(b.setEntityUuid)

    val results = Await.result(client.postEvents(List(b.build())), 5000.milliseconds)

    EventView.printTable(results.toList)
  }
}

class EventViewCommmand extends Command[CliContext] {

  val commandName = "event:view"
  val description = "Show specific events by ID."

  val eventIds = strings.argRepeated("event id", "Event IDs to display")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    if (eventIds.value.isEmpty) {
      throw new IllegalArgumentException("Must include at least one ID")
    }

    val ids = eventIds.value.map(v => ModelID.newBuilder.setValue(v).build())

    val results = Await.result(client.getEvents(ids), 5000.milliseconds)

    EventView.printTable(results.toList)
  }
}

class EventListCommmand extends Command[CliContext] {

  val commandName = "event:list"
  val description = "List events."

  val eventType = strings.optionRepeated(Some("t"), Some("type"), "Event types to include")
  val severities = ints.optionRepeated(Some("s"), Some("severity"), "Severities to include")
  val severityOrHigher = ints.option(None, Some("min-severity"), "Filters to event with this severity or higher")
  val agents = strings.optionRepeated(Some("a"), Some("agent"), "Agent names to include")

  val alarm = optionSwitch(None, Some("alarm"), "Only include events that are alarms")
  val notAlarm = optionSwitch(None, Some("not-alarm"), "Only include events that are not alarms")

  val limit = ints.option(None, Some("limit"), "Maximum number of events to return")

  val nonLatest = optionSwitch(Some("b"), Some("beginning"), "Fill page window from the beginning of the result set")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    val b = EventQueryParams.newBuilder()

    eventType.value.foreach(b.addEventType)
    severities.value.foreach(b.addSeverity)
    severityOrHigher.value.foreach(b.setSeverityOrHigher)
    agents.value.foreach(b.addAgent)

    val qb = EventQuery.newBuilder().setQueryParams(b)
    limit.value.foreach(qb.setPageSize)

    val isAlarm = (alarm.value, notAlarm.value) match {
      case (true, true) => throw new IllegalArgumentException("Must use only one of --alarm or --not-alarm")
      case (true, false) => Some(true)
      case (false, true) => Some(false)
      case (false, false) => None
    }

    isAlarm.foreach(b.setIsAlarm)

    b.setLatest(!nonLatest.value)

    val results = Await.result(client.eventQuery(qb.build()), 5000.milliseconds)

    EventView.printTable(results.toList)
  }
}

class EventSubscribeCommmand extends Command[CliContext] {

  val commandName = "event:subscribe"
  val description = "Subscribe to events."

  val eventType = strings.optionRepeated(Some("t"), Some("type"), "Event types to include")
  val severities = ints.optionRepeated(Some("s"), Some("severity"), "Severities to include")
  val agents = strings.optionRepeated(Some("a"), Some("agent"), "Agent names to include")

  val limit = ints.option(None, Some("limit"), "Maximum number of events to return")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    val b = EventSubscriptionQuery.newBuilder()

    eventType.value.foreach(b.addEventType)
    severities.value.foreach(b.addSeverity)
    agents.value.foreach(b.addAgent)

    limit.value.foreach(b.setLimit)

    val (results, sub) = Await.result(client.subscribeToEvents(b.build()), 5000.milliseconds)

    val widths = new AtomicReference[Seq[Int]](Nil)

    val origWidths = EventView.printTable(results.toList)

    widths.set(origWidths)

    sub.start { eventNotification =>
      widths.set(EventView.printRows(List(eventNotification.getValue), widths.get()))
    }

    context.reader.readCharacter()
    sub.cancel()
  }
}

class AlarmListCommand extends Command[CliContext] {

  val commandName = "alarm:list"
  val description = "List alarms."

  val alarmStates = strings.optionRepeated(None, Some("alarm-state"), "Alarm states to include: " + List(Alarm.State.UNACK_SILENT, Alarm.State.UNACK_AUDIBLE, Alarm.State.ACKNOWLEDGED, Alarm.State.REMOVED).mkString(", "))

  val eventType = strings.optionRepeated(Some("t"), Some("type"), "Event types to include")
  val severities = ints.optionRepeated(Some("s"), Some("severity"), "Severities to include")
  val severityOrHigher = ints.option(None, Some("min-severity"), "Filters to event with this severity or higher")
  val agents = strings.optionRepeated(Some("a"), Some("agent"), "Agent names to include")

  val limit = ints.option(None, Some("limit"), "Maximum number of events to return")

  val nonLatest = optionSwitch(Some("b"), Some("beginning"), "Fill page window from the beginning of the result set")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    val states = try {
      alarmStates.value.map { str =>
        Alarm.State.valueOf(str)
      }
    } catch {
      case ex: Throwable =>
        throw new IllegalArgumentException("Couldn't parse alarm state")
    }

    val b = EventQueryParams.newBuilder()
    eventType.value.foreach(b.addEventType)
    severities.value.foreach(b.addSeverity)
    severityOrHigher.value.foreach(b.setSeverityOrHigher)
    agents.value.foreach(b.addAgent)
    b.setLatest(!nonLatest.value)

    val ab = AlarmQuery.newBuilder()
    states.foreach(ab.addAlarmStates)
    limit.value.foreach(ab.setPageSize)
    ab.setEventQueryParams(b.build())

    val query = ab.build()

    val results = Await.result(client.alarmQuery(query), 5000.milliseconds)

    AlarmView.printTable(results.toList)
  }
}

class AlarmSubscribeCommmand extends Command[CliContext] {

  val commandName = "alarm:subscribe"
  val description = "Subscribe to alarms."

  val alarmStates = strings.optionRepeated(None, Some("alarm-state"), "Alarm states to include: " + List(Alarm.State.UNACK_SILENT, Alarm.State.UNACK_AUDIBLE, Alarm.State.ACKNOWLEDGED, Alarm.State.REMOVED).mkString(", "))

  val eventType = strings.optionRepeated(Some("t"), Some("type"), "Event types to include")
  val severities = ints.optionRepeated(Some("s"), Some("severity"), "Severities to include")
  val agents = strings.optionRepeated(Some("a"), Some("agent"), "Agent names to include")

  val limit = ints.option(None, Some("limit"), "Maximum number of events to return")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    val states = try {
      alarmStates.value.map { str =>
        Alarm.State.valueOf(str)
      }
    } catch {
      case ex: Throwable =>
        throw new IllegalArgumentException("Couldn't parse alarm state")
    }

    val b = EventSubscriptionQuery.newBuilder()

    eventType.value.foreach(b.addEventType)
    severities.value.foreach(b.addSeverity)
    agents.value.foreach(b.addAgent)

    limit.value.foreach(b.setLimit)

    val alarmBuilder = AlarmSubscriptionQuery.newBuilder().setEventQuery(b.build())

    states.foreach(alarmBuilder.addAlarmStates)

    val (results, sub) = Await.result(client.subscribeToAlarms(alarmBuilder.build()), 5000.milliseconds)

    val widths = new AtomicReference[Seq[Int]](Nil)

    val origWidths = AlarmView.printTable(results.toList)

    widths.set(origWidths)

    sub.start { alarmNotification =>
      widths.set(AlarmView.printRows(List(alarmNotification.getValue), widths.get()))
    }

    context.reader.readCharacter()
    sub.cancel()
  }
}

class AlarmSilenceCommand extends Command[CliContext] {

  val commandName = "alarm:silence"
  val description = "Silence events specified by ID."

  val alarmIds = strings.argRepeated("alarm id", "Alarm IDs")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    val updates = alarmIds.value.map { id =>
      AlarmStateUpdate.newBuilder()
        .setAlarmId(ModelID.newBuilder().setValue(id).build())
        .setAlarmState(Alarm.State.UNACK_SILENT)
        .build()
    }

    val results = Await.result(client.putAlarmState(updates), 5000.milliseconds)

    AlarmView.printTable(results.toList)
  }
}

class AlarmAckCommand extends Command[CliContext] {

  val commandName = "alarm:ack"
  val description = "Acknowledge events specified by ID."

  val alarmIds = strings.argRepeated("alarm id", "Alarm IDs")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    if (alarmIds.value.isEmpty) {
      throw new IllegalArgumentException("Must include at least one id")
    }

    val updates = alarmIds.value.map { id =>
      AlarmStateUpdate.newBuilder()
        .setAlarmId(ModelID.newBuilder().setValue(id).build())
        .setAlarmState(Alarm.State.ACKNOWLEDGED)
        .build()
    }

    val results = Await.result(client.putAlarmState(updates), 5000.milliseconds)

    AlarmView.printTable(results.toList)
  }
}

class AlarmRemoveCommand extends Command[CliContext] {

  val commandName = "alarm:remove"
  val description = "Remove events specified by ID."

  val alarmIds = strings.argRepeated("alarm id", "Alarm IDs")

  protected def execute(context: CliContext) {
    val client = EventService.client(context.session)

    val updates = alarmIds.value.map { id =>
      AlarmStateUpdate.newBuilder()
        .setAlarmId(ModelID.newBuilder().setValue(id).build())
        .setAlarmState(Alarm.State.REMOVED)
        .build()
    }

    val results = Await.result(client.putAlarmState(updates), 5000.milliseconds)

    AlarmView.printTable(results.toList)
  }
}

