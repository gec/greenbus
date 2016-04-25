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
import io.greenbus.services.framework._
import io.greenbus.client.service.proto.Events._
import io.greenbus.client.service.proto.Model.{ ModelID, ModelUUID }
import UUIDHelpers._
import io.greenbus.services.core.event.MessageFormatter
import java.util.UUID
import org.squeryl.Query
import io.greenbus.services.authz.EntityFilter

object EventAlarmModel {

  val defaultEventQueryPageSize = 200

  val unconfiguredEventSeverity = 9
  val unconfiguredEventMessage = "Event type not configured"

  def mapToAttributesList(map: Seq[(String, String)]): Seq[Attribute] = {
    map.map {
      case (k, v) =>
        Attribute.newBuilder()
          .setName(k)
          .setValueString(v)
          .build()
    }
  }

  case class SysEventTemplate(user: String, eventType: String, subsystem: Option[String], deviceTime: Option[Long], entityUuid: Option[ModelUUID], modelGroup: Option[String], args: Seq[Attribute])

  case class EventConfigTemp(eventType: String, severity: Int, designation: EventConfig.Designation, alarmState: Alarm.State, resource: String)

  case class EventQueryParams(
    eventTypes: Seq[String] = Nil,
    timeFrom: Option[Long] = None,
    timeTo: Option[Long] = None,
    severities: Seq[Int] = Nil,
    severityOrHigher: Option[Int] = None,
    subsystems: Seq[String] = Nil,
    agents: Seq[String] = Nil,
    entityUuids: Seq[UUID] = Nil,
    entityNames: Seq[String] = Nil,
    modelGroups: Seq[String] = Nil,
    isAlarm: Option[Boolean] = None,
    latest: Boolean = true,
    last: Option[Long] = None,
    pageSize: Int = defaultEventQueryPageSize)
}

trait EventAlarmModel {
  import EventAlarmModel._

  def eventConfigQuery(severities: Seq[Int], severityOrHigher: Option[Int], designations: Seq[EventConfig.Designation], alarmStates: Seq[Alarm.State], lastEventType: Option[String], pageSize: Int): Seq[EventConfig]
  def getEventConfigs(eventTypes: Seq[String]): Seq[EventConfig]
  def putEventConfigs(notifier: ModelNotifier, configs: Seq[EventConfigTemp]): Seq[EventConfig]
  def deleteEventConfigs(notifier: ModelNotifier, eventTypes: Seq[String]): Seq[EventConfig]

  def getEvents(eventIds: Seq[Long], filter: Option[EntityFilter] = None): Seq[Event]
  def postEvents(notifier: ModelNotifier, templates: Seq[SysEventTemplate], filter: Option[EntityFilter] = None): Seq[Event]
  def eventQuery(params: EventQueryParams, filter: Option[EntityFilter] = None): Seq[Event]

  def alarmQuery(alarmStates: Seq[Alarm.State], params: EventQueryParams, filter: Option[EntityFilter] = None): Seq[Alarm]

  def putAlarmStates(notifier: ModelNotifier, updates: Seq[(Long, Alarm.State)], filter: Option[EntityFilter] = None): Seq[Alarm]
}

object SquerylEventAlarmModel extends EventAlarmModel {
  import EventAlarmModel._

  def eventConfigToProto(row: EventConfigRow): EventConfig = {
    EventConfig.newBuilder()
      .setEventType(row.eventType)
      .setSeverity(row.severity)
      .setDesignation(EventConfig.Designation.valueOf(row.designation))
      .setAlarmState(Alarm.State.valueOf(row.alarmState))
      .setResource(row.resource)
      .setBuiltIn(row.builtIn)
      .build()
  }

  def eventConfigQuery(severities: Seq[Int], severityOrHigher: Option[Int], designations: Seq[EventConfig.Designation], alarmStates: Seq[Alarm.State], lastEventType: Option[String], pageSize: Int): Seq[EventConfig] = {

    val desigs = designations.map(_.getNumber)
    val alarmStateNums = alarmStates.map(_.getNumber)

    val results =
      from(eventConfigs)(ec =>
        where((ec.severity in severities).inhibitWhen(severities.isEmpty) and
          (ec.severity lte severityOrHigher.?) and
          (ec.designation in desigs).inhibitWhen(designations.isEmpty) and
          (ec.alarmState in alarmStateNums).inhibitWhen(alarmStates.isEmpty) and
          (ec.eventType gt lastEventType.?))
          select (ec)
          orderBy (ec.eventType))
        .page(0, pageSize)

    results.toSeq map eventConfigToProto
  }

  def getEventConfigs(eventTypes: Seq[String]): Seq[EventConfig] = {
    val results = eventConfigs.where(ec => ec.eventType in eventTypes).toSeq

    results map eventConfigToProto
  }

  def putEventConfigs(notifier: ModelNotifier, configs: Seq[EventConfigTemp]): Seq[EventConfig] = {

    val types = configs.map(_.eventType)
    val existing = eventConfigs.where(ec => ec.eventType in types).toSeq

    val existNameSet = existing.map(_.eventType).toSet

    val (toBeUpdated, toBeCreated) = configs.partition(existNameSet contains _.eventType)

    val updateMap = toBeUpdated.map(u => (u.eventType, u)).toMap

    val creates = toBeCreated.map(c => new EventConfigRow(0, c.eventType, c.severity, c.designation.getNumber, c.alarmState.getNumber, c.resource, false))

    val updates = existing.flatMap { row =>
      updateMap.get(row.eventType).map { up =>
        row.copy(
          eventType = up.eventType,
          severity = up.severity,
          designation = up.designation.getNumber,
          alarmState = up.alarmState.getNumber,
          resource = up.resource)
      }
    }

    eventConfigs.insert(creates)
    eventConfigs.update(updates)

    val results = getEventConfigs(configs.map(_.eventType))

    val (updated, created) = results.partition(r => existNameSet contains r.getEventType)

    updated.foreach(notifier.notify(Updated, _))
    created.foreach(notifier.notify(Created, _))

    results
  }

  def deleteEventConfigs(notifier: ModelNotifier, eventTypes: Seq[String]): Seq[EventConfig] = {
    val results = getEventConfigs(eventTypes)

    eventConfigs.deleteWhere(ec => ec.eventType in eventTypes)

    results.foreach(notifier.notify(Deleted, _))

    results
  }

  /*private def argsToArgList(args: Seq[Attribute]): AttributeList = {
    import scala.collection.JavaConversions._
    AttributeList.newBuilder()
      .addAllAttribute(args)
      .build()
  }*/

  private def makeEvent(isAlarm: Boolean, template: SysEventTemplate, config: EventConfig): EventRow = {

    val now = System.currentTimeMillis()
    val args = template.args
    val argBytes = new Array[Byte](0) // argsToArgList(args).toByteArray
    val rendered = MessageFormatter.format(config.getResource, args)

    EventRow(
      0,
      template.eventType,
      isAlarm,
      now,
      template.deviceTime,
      config.getSeverity,
      template.subsystem.getOrElse(""),
      template.user,
      template.entityUuid.map(protoUUIDToUuid),
      template.modelGroup,
      argBytes,
      rendered)
  }

  private def makeUnconfiguredEvent(template: SysEventTemplate): EventRow = {
    val now = System.currentTimeMillis
    val args = template.args
    val argBytes = new Array[Byte](0) //argsToArgList(args).toByteArray

    EventRow(0,
      template.eventType,
      true,
      now,
      template.deviceTime,
      unconfiguredEventSeverity,
      template.subsystem.getOrElse(""),
      template.user,
      template.entityUuid.map(protoUUIDToUuid),
      None,
      argBytes,
      unconfiguredEventMessage)
  }

  private def eventToProto(row: EventRow): Event = eventToProto(row, false)
  private def eventToProto(row: EventRow, skipId: Boolean = false): Event = {
    val b = Event.newBuilder()
      .setAgentName(row.userId)
      .setAlarm(row.alarm)
      .setEventType(row.eventType)
      .setRendered(row.rendered)
      .setSeverity(row.severity)
      .setTime(row.time)
      .setSubsystem(row.subsystem)

    if (!skipId) b.setId(ModelID.newBuilder.setValue(row.id.toString))

    row.entityId.map(uuidToProtoUUID).foreach(b.setEntityUuid)
    row.deviceTime.foreach(b.setDeviceTime)
    row.modelGroup.foreach(b.setModelGroup)

    b.build()
  }

  private def alarmToProto(row: AlarmRow, eventProto: Event): Alarm = {
    Alarm.newBuilder()
      .setId(ModelID.newBuilder.setValue(row.id.toString))
      .setEvent(eventProto)
      .setState(Alarm.State.valueOf(row.state))
      .build
  }

  def getEvents(eventIds: Seq[Long], filter: Option[EntityFilter] = None): Seq[Event] = {
    if (eventIds.nonEmpty) {
      events.where(ev => (ev.id in eventIds) and EntityFilter.optional(filter, ev.entityId)).toSeq map eventToProto
    } else {
      Nil
    }
  }

  private def eventQueryClause(ev: EventRow, params: EventQueryParams, filter: Option[EntityFilter] = None) = {
    import params._

    val hasEntitySelector = entityUuids.nonEmpty || entityNames.nonEmpty

    val entitySelector: Query[EntityRow] = if (hasEntitySelector) {
      from(entities)(ent =>
        where((ent.id in entityUuids).inhibitWhen(entityUuids.isEmpty) or
          (ent.name in entityNames).inhibitWhen(entityNames.isEmpty))
          select (ent))
    } else {
      entities.where(t => true === true)
    }

    EntityFilter.optional(filter, ev.entityId).inhibitWhen(filter.isEmpty) and
      (ev.eventType in eventTypes).inhibitWhen(eventTypes.isEmpty) and
      (ev.time gte timeFrom.?) and
      (ev.time lte timeTo.?) and
      (ev.severity in severities).inhibitWhen(severities.isEmpty) and
      (ev.severity lte severityOrHigher.?) and
      (ev.subsystem in subsystems).inhibitWhen(subsystems.isEmpty) and
      (ev.userId in agents).inhibitWhen(agents.isEmpty) and
      (ev.modelGroup in modelGroups).inhibitWhen(modelGroups.isEmpty) and
      (ev.alarm === isAlarm.?) and
      (ev.entityId in from(entitySelector)(ent => select(ent.id))).inhibitWhen(!hasEntitySelector)
  }

  private def queryForEvents(params: EventQueryParams, filter: Option[EntityFilter] = None): Query[EventRow] = {
    import params._

    import org.squeryl.dsl.ast.{ OrderByArg, ExpressionNode }
    def timeOrder(time: ExpressionNode) = {
      if (!latest) {
        new OrderByArg(time).asc
      } else {
        new OrderByArg(time).desc
      }
    }
    def idOrder(id: ExpressionNode) = {
      if (!latest) {
        new OrderByArg(id).asc
      } else {
        new OrderByArg(id).desc
      }
    }

    from(events)(ev =>
      where(
        eventQueryClause(ev, params, filter) and
          (ev.id > params.last.?).inhibitWhen(params.latest) and
          (ev.id < params.last.?).inhibitWhen(!params.latest))
        select (ev)
        orderBy (timeOrder(ev.time), idOrder(ev.id)))
  }

  def eventQuery(params: EventQueryParams, filter: Option[EntityFilter] = None): Seq[Event] = {

    val query = queryForEvents(params, filter)

    val results: Seq[EventRow] = query.page(0, params.pageSize).toSeq

    val protos = results map eventToProto

    if (!params.latest) protos else protos.reverse
  }

  def postEvents(notifier: ModelNotifier, templates: Seq[SysEventTemplate], filter: Option[EntityFilter] = None): Seq[Event] = {

    filter.foreach { filt =>
      if (templates.exists(_.entityUuid.isEmpty)) {
        throw new ModelPermissionException("Tried to create events with no entities with non-blanket create permissions")
      }

      val relatedEntIds = templates.flatMap(t => t.entityUuid)
        .distinct
        .map(protoUUIDToUuid)

      if (filt.anyOutsideSet(relatedEntIds)) {
        throw new ModelPermissionException("Tried to create events for restricted entities")
      }
    }

    val configTypes = templates.map(_.eventType).distinct

    val configs = getEventConfigs(configTypes)

    val configMap = configs.map(c => (c.getEventType, c)).toMap

    val mapped: Seq[(Boolean, EventRow, Option[Alarm.State])] = templates.map { temp =>

      configMap.get(temp.eventType) match {
        case None => (true, makeUnconfiguredEvent(temp), Some(Alarm.State.UNACK_SILENT))
        case Some(config) => config.getDesignation match {
          case EventConfig.Designation.EVENT =>
            (true, makeEvent(false, temp, config), None)
          case EventConfig.Designation.ALARM =>
            val initialState = config.getAlarmState
            (true, makeEvent(true, temp, config), Some(initialState))
          case EventConfig.Designation.LOG =>
            (false, makeEvent(false, temp, config), None)
        }
      }
    }

    val (real, logged) = mapped.partition(m => m._1)

    val insertResults: Seq[(EventRow, Option[AlarmRow])] = real.map {
      case (_, eventRow, None) =>
        (events.insert(eventRow), None)
      case (_, eventRow, Some(alarmState)) =>
        val event = events.insert(eventRow)
        val alarm = alarms.insert(AlarmRow(0, alarmState.getNumber, event.id))
        (event, Some(alarm))
    }

    val realResults: Seq[(Event, Option[Alarm])] = insertResults.map {
      case (eventRow, Some(alarmRow)) =>
        val eventProto = eventToProto(eventRow)
        val alarmProto = alarmToProto(alarmRow, eventProto)
        (eventProto, Some(alarmProto))
      case (eventRow, None) =>
        (eventToProto(eventRow), None)
    }

    val loggedRows = logged.map(_._2)

    val logResults: Seq[Event] = loggedRows.map(eventToProto(_, skipId = true))

    realResults.foreach {
      case (eventProto, optAlarmProto) =>
        notifier.notify(Created, eventProto)
        optAlarmProto.foreach(notifier.notify(Created, _))
    }

    logResults.foreach(notifier.notify(Created, _))

    realResults.map(_._1) ++ logResults
  }

  def alarmQuery(alarmStates: Seq[Alarm.State], params: EventQueryParams, filter: Option[EntityFilter] = None): Seq[Alarm] = {

    val stateNums = alarmStates.map(_.getNumber)

    val latest = params.latest

    import org.squeryl.dsl.ast.{ OrderByArg, ExpressionNode }
    def timeOrder(time: ExpressionNode) = {
      if (!params.latest) {
        new OrderByArg(time).asc
      } else {
        new OrderByArg(time).desc
      }
    }
    def idOrder(id: ExpressionNode) = {
      if (!latest) {
        new OrderByArg(id).asc
      } else {
        new OrderByArg(id).desc
      }
    }

    val lastEventOpt: Option[EventRow] = params.last.flatMap { lastAlarmId =>
      from(events, alarms)((e, a) =>
        where(e.id === a.eventId and a.id === lastAlarmId)
          select (e)).headOption
    }

    val lastEventIdOpt = lastEventOpt.map(_.id)

    // NOTE: we must provide time in a backwards (forward in time) paging because otherwise
    // it tries to use the (time, id) index... poorly
    val lastEventTimeOpt = lastEventOpt.map(_.time)

    val results: Seq[(EventRow, AlarmRow)] =
      join(events, alarms)((ev, al) =>
        where(
          eventQueryClause(ev, params, filter) and
            (ev.alarm === true) and
            (al.state in stateNums).inhibitWhen(stateNums.isEmpty) and
            ((ev.id > lastEventIdOpt.?) and (ev.time >= lastEventTimeOpt.?)).inhibitWhen(params.latest) and
            ((ev.id < lastEventIdOpt.?) and (ev.time <= lastEventTimeOpt.?)).inhibitWhen(!params.latest))
          select ((ev, al))
          orderBy (timeOrder(ev.time), idOrder(ev.id))
          on (ev.id === al.eventId))
        .page(0, params.pageSize)
        .toVector

    val protos = results.map {
      case (ev, al) => alarmToProto(al, eventToProto(ev))
    }

    if (!params.latest) protos else protos.reverse
  }

  private def validAlarmTransition(prev: Alarm.State, next: Alarm.State): Boolean = {
    import Alarm.State._
    (prev, next) match {
      case (UNACK_AUDIBLE, UNACK_SILENT) => true
      case (UNACK_AUDIBLE, ACKNOWLEDGED) => true
      case (UNACK_SILENT, ACKNOWLEDGED) => true
      case (ACKNOWLEDGED, REMOVED) => true
      case (p, n) if p == n => true
      case _ => false
    }
  }

  def alarmKeyQuery(ids: Seq[Long], filter: Option[EntityFilter] = None): Seq[Alarm] = {

    val results: Seq[(EventRow, AlarmRow)] =
      from(events, alarms)((ev, al) =>
        where(EntityFilter.optional(filter, ev.entityId).inhibitWhen(filter.isEmpty) and
          (al.id in ids) and
          ev.id === al.eventId)
          select (ev, al)).toSeq

    results.map {
      case (ev, al) => alarmToProto(al, eventToProto(ev))
    }
  }

  def putAlarmStates(notifier: ModelNotifier, updates: Seq[(Long, Alarm.State)], filter: Option[EntityFilter] = None): Seq[Alarm] = {

    val alarmIds = updates.map(_._1)

    filter.foreach { filt =>

      val anyWithoutEnt =
        from(events, alarms)((ev, al) =>
          where(al.id in alarmIds and
            al.eventId === ev.id and
            ev.entityId.isNull)
            select (al.id)).page(0, 1).nonEmpty

      if (anyWithoutEnt) {
        throw new ModelPermissionException("Tried to modify alarms with no associated entities without blanked update permissions")
      }

      val relatedEntQuery =
        from(events, alarms, entities)((ev, al, ent) =>
          where(al.id in alarmIds and
            al.eventId === ev.id and
            ev.entityId === Some(ent.id))
            select (ent))

      if (filt.anyOutsideSet(relatedEntQuery)) {
        throw new ModelPermissionException("Tried to modify alarms for restricted entities")
      }
    }

    val currentValues: Seq[AlarmRow] = alarms.where(al => al.id in alarmIds).toSeq

    val idMap = currentValues.map(a => (a.id, a)).toMap

    val toBeUpdated = updates.flatMap {
      case (id, nextState) =>
        idMap.get(id) match {
          case Some(current) =>
            val currentState = Alarm.State.valueOf(current.state)
            if (currentState != nextState) {
              if (validAlarmTransition(currentState, nextState)) {
                Some(current.copy(state = nextState.getNumber))
              } else {
                throw new ModelInputException(s"Invalid transition between alarm states $currentState -> $nextState")
              }
            } else {
              None
            }
          case None =>
            throw new ModelInputException(s"No alarm exists for id $id")
        }
    }

    alarms.update(toBeUpdated)

    val results = alarmKeyQuery(alarmIds)

    val actuallyUpdatedIds = toBeUpdated.map(_.id).toSet
    val actuallyUpdated = results.filter(a => actuallyUpdatedIds.contains(a.getId.getValue.toLong))
    actuallyUpdated.foreach(up => notifier.notify(Updated, up))

    results
  }
}
