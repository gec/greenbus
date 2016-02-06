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
package io.greenbus.services.core

import io.greenbus.client.exception.{ BadRequestException, ForbiddenException }
import io.greenbus.client.proto.Envelope
import io.greenbus.client.proto.Envelope.SubscriptionEventType
import io.greenbus.client.service.EventService.Descriptors
import io.greenbus.client.service.proto.EventRequests
import io.greenbus.client.service.proto.EventRequests._
import io.greenbus.client.service.proto.Events.{ Alarm, AlarmNotification, Event, EventNotification }
import io.greenbus.services.authz.EntityFilter
import io.greenbus.services.framework.{ ServiceContext, Success, _ }
import io.greenbus.services.model.EventAlarmModel.{ EventQueryParams, SysEventTemplate }
import io.greenbus.services.model.UUIDHelpers._
import io.greenbus.services.model.{ EntityModel, EventAlarmModel }

import scala.collection.JavaConversions._

object EventAlarmServices {

  val eventConfigResource = "event_config"
  val eventResource = "event"
  val alarmResource = "alarm"

  val defaultEventConfigPageSize = 200
  val defaultEventPageSize = 200
}

class EventAlarmServices(services: ServiceRegistry, eventAlarmModel: EventAlarmModel, entityModel: EntityModel, eventBinding: SubscriptionChannelBinder, alarmBinding: SubscriptionChannelBinder) {
  import io.greenbus.services.core.EventAlarmServices._

  services.fullService(Descriptors.EventConfigQuery, eventConfigQuery)
  services.fullService(Descriptors.GetEventConfigs, getEventConfigs)
  services.fullService(Descriptors.PutEventConfigs, putEventConfigs)
  services.fullService(Descriptors.DeleteEventConfigs, deleteEventConfigs)

  services.fullService(Descriptors.EventQuery, eventQuery)
  services.fullService(Descriptors.GetEvents, getEvents)
  services.fullService(Descriptors.PostEvents, postEvents)

  services.fullService(Descriptors.AlarmQuery, alarmQuery)
  services.fullService(Descriptors.PutAlarmState, putAlarmStates)

  services.fullService(Descriptors.SubscribeToEvents, subscribeToEvents)
  services.fullService(Descriptors.SubscribeToAlarms, subscribeToAlarms)

  def eventConfigQuery(request: EventConfigQueryRequest, headers: Map[String, String], context: ServiceContext): Response[EventConfigQueryResponse] = {

    val filter = context.auth.authorize(eventConfigResource, "read")
    if (filter.nonEmpty) {
      throw new ForbiddenException("Must have blanket read permissions on " + eventConfigResource)
    }

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getRequest

    val last = if (query.hasLastEventType) Some(query.getLastEventType) else None
    val pageSize = if (query.hasPageSize) query.getPageSize else defaultEventConfigPageSize

    val alarmStates = query.getAlarmStateList.toSeq
    val designations = query.getDesignationList.toSeq
    val severities = query.getSeverityList.map(_.toInt).toSeq
    val severityOrHigher = if (query.hasSeverityOrHigher) Some(query.getSeverityOrHigher) else None

    val results = eventAlarmModel.eventConfigQuery(severities, severityOrHigher, designations, alarmStates, last, pageSize)

    val response = EventConfigQueryResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def getEventConfigs(request: GetEventConfigRequest, headers: Map[String, String], context: ServiceContext): Response[GetEventConfigResponse] = {

    val filter = context.auth.authorize(eventConfigResource, "read")
    if (filter.nonEmpty) {
      throw new ForbiddenException("Must have blanket read permissions on " + eventConfigResource)
    }

    if (request.getEventTypeCount == 0) {
      throw new BadRequestException("Must include at least one event type")
    }

    val eventTypeList = request.getEventTypeList.toSeq

    val results = eventAlarmModel.getEventConfigs(eventTypeList)

    val response = GetEventConfigResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def putEventConfigs(request: PutEventConfigRequest, headers: Map[String, String], context: ServiceContext): Response[PutEventConfigResponse] = {

    val createFilter = context.auth.authorize(eventConfigResource, "create")
    if (createFilter.nonEmpty) {
      throw new ForbiddenException("Must have blanket create permissions on " + eventConfigResource)
    }
    val updateFilter = context.auth.authorize(eventConfigResource, "update")
    if (updateFilter.nonEmpty) {
      throw new ForbiddenException("Must have blanket update permissions on " + eventConfigResource)
    }

    if (request.getRequestCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val configs = request.getRequestList.toSeq.map { proto =>
      if (!proto.hasEventType) {
        throw new BadRequestException("All event configs must include event type")
      }
      if (!proto.hasSeverity) {
        throw new BadRequestException("All event configs must include severity")
      }
      if (!proto.hasDesignation) {
        throw new BadRequestException("All event configs must include designation")
      }
      if (!proto.hasResource) {
        throw new BadRequestException("All event configs must include resource")
      }

      val alarmState = if (proto.hasAlarmState) proto.getAlarmState else Alarm.State.UNACK_SILENT

      EventAlarmModel.EventConfigTemp(proto.getEventType, proto.getSeverity, proto.getDesignation, alarmState, proto.getResource)
    }

    val results = eventAlarmModel.putEventConfigs(context.notifier, configs)

    val response = PutEventConfigResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def deleteEventConfigs(request: DeleteEventConfigRequest, headers: Map[String, String], context: ServiceContext): Response[DeleteEventConfigResponse] = {

    val filter = context.auth.authorize(eventConfigResource, "delete")
    if (filter.nonEmpty) {
      throw new ForbiddenException("Must have blanket delete permissions on " + eventConfigResource)
    }

    if (request.getEventTypeCount == 0) {
      throw new BadRequestException("Must include at least one event type")
    }

    val eventTypeList = request.getEventTypeList.toSeq

    val results = eventAlarmModel.deleteEventConfigs(context.notifier, eventTypeList)

    val response = DeleteEventConfigResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def getEvents(request: GetEventsRequest, headers: Map[String, String], context: ServiceContext): Response[GetEventsResponse] = {

    val filter = context.auth.authorize(eventResource, "read")

    if (request.getEventIdCount == 0) {
      throw new BadRequestException("Must include at least one event id")
    }

    val eventIds = request.getEventIdList.toSeq.map(protoIdToLong)

    val results = eventAlarmModel.getEvents(eventIds, filter)

    val response = GetEventsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def postEvents(request: PostEventsRequest, headers: Map[String, String], context: ServiceContext): Response[PostEventsResponse] = {

    val createFilter = context.auth.authorize(eventResource, "create")
    if (createFilter.nonEmpty) {
      throw new ForbiddenException("Must have blanket create permissions on " + eventResource)
    }

    if (request.getRequestCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val protoTemplates = request.getRequestList.toSeq

    val templates = protoTemplates.map { et =>
      val eventType = if (et.hasEventType) {
        et.getEventType
      } else {
        throw new BadRequestException("Must include event type")
      }

      val subsystem = if (et.hasSubsystem) Some(et.getSubsystem) else None
      val deviceTime = if (et.hasDeviceTime) Some(et.getDeviceTime) else None

      val entityUuid = if (et.hasEntityUuid) Some(et.getEntityUuid) else None

      val modelGroup = if (et.hasModelGroup) Some(et.getModelGroup) else None

      val args = et.getArgsList.toSeq

      SysEventTemplate(context.auth.agentName, eventType, subsystem, deviceTime, entityUuid, modelGroup, args)
    }

    val results = eventAlarmModel.postEvents(context.notifier, templates)

    val response = PostEventsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  private def parseEventQuery(query: EventRequests.EventQueryParams, lastId: Option[Long], pageSize: Int): EventQueryParams = {
    val eventTypes = query.getEventTypeList.toSeq
    val timeFrom = if (query.hasTimeFrom) Some(query.getTimeFrom) else None
    val timeTo = if (query.hasTimeTo) Some(query.getTimeTo) else None
    val severities = query.getSeverityList.toSeq.map(_.toInt)
    val severityOrHigher = if (query.hasSeverityOrHigher) Some(query.getSeverityOrHigher) else None
    val subsystems = query.getSubsystemList.toSeq
    val agents = query.getAgentList.toSeq

    val modelGroups = query.getModelGroupList.toSeq

    val isAlarm = if (query.hasIsAlarm) Some(query.getIsAlarm) else None

    val (entityUuids, entityNames) = if (query.hasEntities) {
      (query.getEntities.getUuidsList.toSeq.map(protoUUIDToUuid), query.getEntities.getNamesList.toSeq)
    } else {
      (Nil, Nil)
    }

    val latest = if (query.hasLatest) query.getLatest else true

    EventQueryParams(eventTypes, timeFrom, timeTo, severities, severityOrHigher, subsystems, agents, entityUuids, entityNames, modelGroups, isAlarm, latest, lastId, pageSize)
  }

  def eventQuery(request: EventQueryRequest, headers: Map[String, String], context: ServiceContext): Response[EventQueryResponse] = {

    val filter = context.auth.authorize(eventResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getRequest

    val lastId = if (query.hasLastId) Some(query.getLastId.getValue.toLong) else None

    val limit = if (query.hasPageSize) query.getPageSize else defaultEventPageSize

    val params = if (query.hasQueryParams) parseEventQuery(query.getQueryParams, lastId, limit) else EventQueryParams(last = lastId, pageSize = limit)

    val results = eventAlarmModel.eventQuery(params, filter)

    val response = EventQueryResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  private def parseEventSubscriptionQuery(query: EventSubscriptionQuery, filter: Option[EntityFilter]): EventQueryParams = {

    val timeFrom = if (query.hasTimeFrom) Some(query.getTimeFrom) else None
    val limit = if (query.hasLimit) query.getLimit else defaultEventPageSize

    val eventTypes = query.getEventTypeList.toSeq
    val severities = query.getSeverityList.toSeq.map(_.toInt)
    val subsystem = query.getSubsystemList.toSeq
    val agents = query.getAgentList.toSeq

    val (entityUuids, entityNames) = if (query.hasEntities) {
      (query.getEntities.getUuidsList.toSeq.map(protoUUIDToUuid), query.getEntities.getNamesList.toSeq)
    } else {
      (Nil, Nil)
    }

    val lookingForEntities = entityUuids.nonEmpty || entityNames.nonEmpty

    if (!lookingForEntities && filter.nonEmpty) {
      throw new ForbiddenException("Must have blanket read permissions to subscribe without specifying entities")
    }

    val entUuids = if (lookingForEntities) {
      entityModel.keyQueryFilter(entityUuids, entityNames, filter)
    } else {
      Nil
    }

    if (lookingForEntities && entUuids.isEmpty) {
      throw new BadRequestException("Entities to subscribe to not found")
    }

    EventQueryParams(eventTypes = eventTypes, timeFrom = timeFrom, severities = severities, subsystems = subsystem, agents = agents, entityUuids = entUuids, pageSize = limit, latest = true)
  }

  def subscribeToEvents(request: SubscribeEventsRequest, headers: Map[String, String], context: ServiceContext): Response[SubscribeEventsResponse] = {

    val filter = context.auth.authorize(eventResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val queue = headers.get("__subscription").getOrElse {
      throw new BadRequestException("Must include subscription queue in headers")
    }

    val query = request.getRequest

    val timeFrom = if (query.hasTimeFrom) Some(query.getTimeFrom) else None
    val limit = if (query.hasLimit) query.getLimit else defaultEventPageSize

    val hasParams = query.getEventTypeCount != 0 ||
      query.getSeverityCount != 0 ||
      query.getSubsystemCount != 0 ||
      query.getAgentCount != 0 ||
      (query.hasEntities && (query.getEntities.getUuidsCount != 0 || query.getEntities.getNamesCount != 0))

    val results = if (!hasParams) {

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions to subscribe without specifying entities")
      }

      eventBinding.bindAll(queue)

      eventAlarmModel.eventQuery(EventQueryParams(timeFrom = timeFrom, pageSize = limit, latest = true))

    } else {

      val params = parseEventSubscriptionQuery(query, filter)

      eventBinding.bindEach(queue, Seq(params.eventTypes, params.severities.map(_.toString), params.subsystems, params.agents, params.entityUuids.map(_.toString)))

      eventAlarmModel.eventQuery(params)
    }

    val response = SubscribeEventsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def alarmQuery(request: AlarmQueryRequest, headers: Map[String, String], context: ServiceContext): Response[AlarmQueryResponse] = {

    val filter = context.auth.authorize(alarmResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val query = request.getRequest

    val lastId = if (query.hasLastId) Some(query.getLastId.getValue.toLong) else None

    val limit = if (query.hasPageSize) query.getPageSize else defaultEventPageSize

    val params = if (query.hasEventQueryParams) parseEventQuery(query.getEventQueryParams, lastId, limit) else EventQueryParams(last = lastId, pageSize = limit)

    val alarmStates = query.getAlarmStatesList.toSeq

    val results = eventAlarmModel.alarmQuery(alarmStates, params, filter)

    val response = AlarmQueryResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def subscribeToAlarms(request: SubscribeAlarmsRequest, headers: Map[String, String], context: ServiceContext): Response[SubscribeAlarmsResponse] = {

    val filter = context.auth.authorize(alarmResource, "read")

    if (!request.hasRequest) {
      throw new BadRequestException("Must include request content")
    }

    val queue = headers.get("__subscription").getOrElse {
      throw new BadRequestException("Must include subscription queue in headers")
    }

    val query = request.getRequest

    val states = query.getAlarmStatesList.toSeq

    val results = if (query.hasEventQuery) {
      val eventQuery = query.getEventQuery

      val hasEventParams =
        eventQuery.getEventTypeCount != 0 ||
          eventQuery.getSeverityCount != 0 ||
          eventQuery.getSubsystemCount != 0 ||
          eventQuery.getAgentCount != 0 ||
          (eventQuery.hasEntities && (eventQuery.getEntities.getUuidsCount != 0 || eventQuery.getEntities.getNamesCount != 0))

      val hasParams = states.nonEmpty || hasEventParams

      if (!hasParams) {

        if (filter.nonEmpty) {
          throw new ForbiddenException("Must have blanket read permissions to subscribe without specifying entities")
        }

        alarmBinding.bindAll(queue)

        eventAlarmModel.alarmQuery(states, parseEventSubscriptionQuery(eventQuery, None))

      } else {

        if (!hasEventParams) {
          throw new BadRequestException("Must include request parameters if not subscribing to all")
        }

        val params = parseEventSubscriptionQuery(eventQuery, filter)

        alarmBinding.bindEach(queue, Seq(states.map(_.toString), params.eventTypes, params.severities.map(_.toString), params.subsystems, params.agents, params.entityUuids.map(_.toString)))

        eventAlarmModel.alarmQuery(states, params)
      }

    } else {

      if (states.isEmpty) {
        throw new BadRequestException("Must include request parameters when not subscribing to all")
      }

      if (filter.nonEmpty) {
        throw new ForbiddenException("Must have blanket read permissions to subscribe without specifying entities")
      }

      alarmBinding.bindEach(queue, Seq(states.map(_.toString), Nil, Nil, Nil, Nil, Nil))

      eventAlarmModel.alarmQuery(states, EventQueryParams(pageSize = defaultEventPageSize))
    }

    val response = SubscribeAlarmsResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

  def putAlarmStates(request: PutAlarmStateRequest, headers: Map[String, String], context: ServiceContext): Response[PutAlarmStateResponse] = {

    val updateFilter = context.auth.authorize(alarmResource, "update")
    if (updateFilter.nonEmpty) {
      throw new ForbiddenException("Must have blanket create permissions on " + eventResource)
    }

    if (request.getRequestCount == 0) {
      throw new BadRequestException("Must include request content")
    }

    val updates = request.getRequestList.toSeq.map { update =>
      if (!update.hasAlarmId) {
        throw new BadRequestException("Must include alarm ID")
      }
      if (!update.hasAlarmState) {
        throw new BadRequestException("Must include alarm state")
      }
      (update.getAlarmId.getValue.toLong, update.getAlarmState)
    }

    val results = eventAlarmModel.putAlarmStates(context.notifier, updates)

    val response = PutAlarmStateResponse.newBuilder().addAllResults(results).build
    Success(Envelope.Status.OK, response)
  }

}

object EventSubscriptionDescriptor extends SubscriptionDescriptor[Event] {
  def keyParts(payload: Event): Seq[String] = {
    Seq(payload.getEventType, payload.getSeverity.toString, payload.getSubsystem, payload.getAgentName, payload.getEntityUuid.getValue)
  }

  def notification(eventType: SubscriptionEventType, payload: Event): Array[Byte] = {
    EventNotification.newBuilder
      .setValue(payload)
      .build()
      .toByteArray
  }
}

object AlarmSubscriptionDescriptor extends SubscriptionDescriptor[Alarm] {
  def keyParts(payload: Alarm): Seq[String] = {
    Seq(payload.getState.toString, payload.getEvent.getEventType, payload.getEvent.getSeverity.toString, payload.getEvent.getSubsystem, payload.getEvent.getAgentName, payload.getEvent.getEntityUuid.getValue)
  }

  def notification(eventType: SubscriptionEventType, payload: Alarm): Array[Byte] = {
    AlarmNotification.newBuilder
      .setValue(payload)
      .setEventType(eventType)
      .build()
      .toByteArray
  }
}
