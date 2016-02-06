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

import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.services.core.EntityKeyValueWithEndpoint
import io.greenbus.services.data._
import java.util.UUID
import io.greenbus.client.service.proto.Auth.{ Permission, Agent, PermissionSet }
import UUIDHelpers._
import io.greenbus.client.service.proto.Model.{ EntityEdge, Entity }
import scala.collection.JavaConversions._
import org.scalatest.matchers.ShouldMatchers
import io.greenbus.services.framework.{ ModelNotifier, ModelEvent }
import scala.collection.mutable
import io.greenbus.client.service.proto.Model._
import io.greenbus.client.service.proto.Commands.CommandLock
import io.greenbus.client.service.proto.Events.{ Event, Alarm, EventConfig }

object ModelTestHelpers extends ShouldMatchers {

  def createEntity(name: String, types: Seq[String]): EntityRow = {
    val ent = ServicesSchema.entities.insert(EntityRow(UUID.randomUUID(), name))
    types.foreach { typ =>
      ServicesSchema.entityTypes.insert(EntityTypeRow(ent.id, typ))
    }
    ent
  }

  def createEdge(parentId: UUID, childId: UUID, relation: String, depth: Int): EntityEdgeRow = {
    ServicesSchema.edges.insert(EntityEdgeRow(0, parentId, childId, relation, depth))
  }

  def createEntityKeyValue(entityId: UUID, key: String, value: Array[Byte]): EntityKeyStoreRow = {
    ServicesSchema.entityKeyValues.insert(EntityKeyStoreRow(0, entityId, key, value))
  }

  def createPoint(entityId: UUID, typ: PointCategory, unit: String): PointRow = {
    ServicesSchema.points.insert(PointRow(0, entityId, typ.getNumber, unit))
  }

  def createCommand(entityId: UUID, displayName: String, typ: CommandCategory): CommandRow = {
    ServicesSchema.commands.insert(CommandRow(0, entityId, displayName, typ.getNumber, None))
  }

  def createEndpoint(entityId: UUID, protocol: String, disabled: Boolean): EndpointRow = {
    ServicesSchema.endpoints.insert(EndpointRow(0, entityId, protocol, disabled))
  }

  def createAgent(name: String, password: String = "password", perms: Set[Long] = Set.empty[Long]): AgentRow = {
    val a = ServicesSchema.agents.insert(AgentRow(UUID.randomUUID(), name, password))
    perms.foreach(addPermissionToAgent(_, a.id))
    a
  }

  def createEventConfig(typ: String, severity: Int, designation: EventConfig.Designation, startState: Alarm.State, resource: String, builtIn: Boolean = false) = {
    ServicesSchema.eventConfigs.insert(EventConfigRow(0, typ, severity, designation.getNumber, startState.getNumber, resource, builtIn))
  }

  def createEvent(eventType: String,
    alarm: Boolean,
    time: Long,
    deviceTime: Option[Long],
    severity: Int,
    subsystem: String,
    userId: String,
    entityId: Option[UUID],
    modelGroup: Option[String],
    args: Array[Byte],
    rendered: String) = {

    ServicesSchema.events.insert(EventRow(0, eventType, alarm, time, deviceTime, severity, subsystem, userId, entityId, modelGroup, args, rendered))
  }

  def createAlarm(eventId: Long, state: Alarm.State) = {
    ServicesSchema.alarms.insert(AlarmRow(0, state.getNumber, eventId))
  }

  def createConnectionStatus(endpointId: UUID, status: FrontEndConnectionStatus.Status, time: Long) = {
    ServicesSchema.frontEndCommStatuses.insert(FrontEndCommStatusRow(0, endpointId, status.getNumber, time))
  }

  def addPermissionToAgent(setId: Long, agentId: UUID) {
    ServicesSchema.agentSetJoins.insert(AgentPermissionSetJoinRow(setId, agentId))
  }

  def createPermission(resource: String, action: String): Permission = {
    Permission.newBuilder()
      .addResources(resource)
      .addActions(action)
      .build()
  }

  def createPermissionSet(name: String, perms: Seq[Permission] = Seq.empty[Permission]): PermissionSetRow = {
    val b = PermissionSet.newBuilder
      .setName(name)

    perms.foreach(b.addPermissions)

    val set = b.build()

    ServicesSchema.permissionSets.insert(PermissionSetRow(0, name, set.toByteArray))
  }

  def check[A, B](correct: Set[B])(query: => Seq[A])(implicit mapping: A => B) {
    val simplified = query.map(mapping)
    if (simplified.toSet != correct) {
      println("Correct: " + correct.mkString("(\n\t", "\n\t", "\n)"))
      println("Result: " + simplified.toSet.mkString("(\n\t", "\n\t", "\n)"))
    }
    simplified.toSet should equal(correct)
    simplified.size should equal(correct.size)
  }

  def checkOrder[A, B](correct: Seq[B])(query: => Seq[A])(implicit mapping: A => B) {
    val simplified = query.map(mapping).toList
    simplified.toList should equal(correct)
    simplified.size should equal(correct.size)
  }

  implicit def simplify(proto: Entity): (String, Set[String]) = {
    (proto.getName, proto.getTypesList.toSet)
  }

  implicit def simplify(proto: EntityKeyValue): (UUID, String, String) = {
    (proto.getUuid, proto.getKey, proto.getValue.getStringValue)
  }

  implicit def simplify(v: EntityKeyValueWithEndpoint): (UUID, String, String) = {
    (v.payload.getUuid, v.payload.getKey, v.payload.getValue.getStringValue)
  }

  implicit def simplify(proto: EntityEdge): (UUID, UUID, String, Int) = {
    (proto.getParent, proto.getChild, proto.getRelationship, proto.getDistance)
  }

  implicit def simplify(proto: Point): (String, Set[String], PointCategory, String) = {
    (proto.getName, proto.getTypesList.toSet, proto.getPointCategory, proto.getUnit)
  }

  implicit def simplify(proto: Command): (String, Set[String], String, CommandCategory) = {
    (proto.getName, proto.getTypesList.toSet, proto.getDisplayName, proto.getCommandCategory)
  }

  implicit def simplify(proto: Endpoint): (String, Set[String], String, Boolean) = {
    (proto.getName, proto.getTypesList.toSet, proto.getProtocol, proto.getDisabled)
  }

  implicit def simplify(proto: Agent): (String, Set[String]) = {
    (proto.getName, proto.getPermissionSetsList.toSet)
  }

  implicit def simplify(proto: PermissionSet): (String, Set[(Set[String], Set[String])]) = {
    (proto.getName, proto.getPermissionsList.map(p => (p.getResourcesList.toSet, p.getActionsList.toSet)).toSet)
  }

  implicit def simplify(proto: CommandLock): (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]) = {
    (proto.getAccess, proto.getCommandUuidsList.map(protoUUIDToUuid).toSet, proto.getAgentUuid, if (proto.hasExpireTime) Some(proto.getExpireTime) else None)
  }

  implicit def simplify(proto: EventConfig): (String, Int, EventConfig.Designation, Alarm.State, String, Boolean) = {
    (proto.getEventType, proto.getSeverity, proto.getDesignation, proto.getAlarmState, proto.getResource, proto.getBuiltIn)
  }

  implicit def simplify(proto: Event): (String, Int, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = {
    (proto.getEventType,
      proto.getSeverity,
      proto.getAgentName,
      proto.getAlarm,
      proto.getSubsystem,
      proto.getRendered,
      if (proto.hasEntityUuid) Some(proto.getEntityUuid) else None,
      if (proto.hasModelGroup) Some(proto.getModelGroup) else None,
      if (proto.hasDeviceTime) Some(proto.getDeviceTime) else None)
  }

  implicit def simplifyWithTime(proto: Event): (String, Int, Long, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]) = {
    (proto.getEventType,
      proto.getSeverity,
      proto.getTime,
      proto.getAgentName,
      proto.getAlarm,
      proto.getSubsystem,
      proto.getRendered,
      if (proto.hasEntityUuid) Some(proto.getEntityUuid) else None,
      if (proto.hasModelGroup) Some(proto.getModelGroup) else None,
      if (proto.hasDeviceTime) Some(proto.getDeviceTime) else None)
  }

  implicit def simplify(proto: Alarm): (Long, Alarm.State) = {
    (proto.getEvent.getId.getValue.toLong, proto.getState)
  }

  implicit def simplifyAlarmFull(proto: Alarm): (Alarm.State, String, Int, String, Boolean, String, String, Option[UUID], Option[Long]) = {
    val eventProto = proto.getEvent
    (proto.getState,
      eventProto.getEventType,
      eventProto.getSeverity,
      eventProto.getAgentName,
      eventProto.getAlarm,
      eventProto.getSubsystem,
      eventProto.getRendered,
      if (eventProto.hasEntityUuid) Some(eventProto.getEntityUuid) else None,
      if (eventProto.hasDeviceTime) Some(eventProto.getDeviceTime) else None)
  }

  implicit def simplify(proto: FrontEndConnectionStatus): (String, FrontEndConnectionStatus.Status) = {
    (proto.getEndpointName, proto.getState)
  }

  class TestModelNotifier extends ModelNotifier with ShouldMatchers {
    private val map = mutable.Map.empty[Class[_], mutable.Queue[(ModelEvent, _)]]

    def notify[A](typ: ModelEvent, event: A) {
      val klass = event.getClass
      map.get(klass) match {
        case None =>
          val q = mutable.Queue.empty[(ModelEvent, _)]
          q += ((typ, event))
          map.update(klass, q)
        case Some(queue) =>
          queue += ((typ, event))
      }
    }

    def getQueue[A](klass: Class[A]): mutable.Queue[(ModelEvent, A)] = {
      map.get(klass).map(_.asInstanceOf[mutable.Queue[(ModelEvent, A)]]).getOrElse(mutable.Queue.empty[(ModelEvent, A)])
    }

    /*def check[A, B](klass: Class[A], correct: Set[(ModelEvent, B)])(implicit mapping: A => B) {
      val results = getQueue(klass).toSeq.map(tup => (tup._1, mapping(tup._2)))
      setCheck(results, correct)
    }*/

    def checkEntities(correct: Set[(ModelEvent, (String, Set[String]))]) {
      val results = getQueue(classOf[Entity]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkEntityEdges(correct: Set[(ModelEvent, (UUID, UUID, String, Int))]) {
      val results = getQueue(classOf[EntityEdge]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkEntityKeyValues(correct: Set[(ModelEvent, (UUID, String, String))]): Unit = {
      val results = getQueue(classOf[EntityKeyValueWithEndpoint]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkPoints(correct: Set[(ModelEvent, (String, Set[String], PointCategory, String))]) {
      val results = getQueue(classOf[Point]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkCommands(correct: Set[(ModelEvent, (String, Set[String], String, CommandCategory))]) {
      val results = getQueue(classOf[Command]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkCommandLocks(correct: Set[(ModelEvent, (CommandLock.AccessMode, Set[UUID], UUID, Option[Long]))]) {
      val results = getQueue(classOf[CommandLock]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkEndpoints(correct: Set[(ModelEvent, (String, Set[String], String, Boolean))]) {
      val results = getQueue(classOf[Endpoint]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkAgents(correct: Set[(ModelEvent, (String, Set[String]))]) {
      val results = getQueue(classOf[Agent]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkPermissionSetNames(correct: Set[(ModelEvent, String)]) {
      val results = getQueue(classOf[PermissionSet]).toSeq.map(tup => (tup._1, tup._2.getName))
      setCheck(results, correct)
    }
    def checkPermissionSets(correct: Set[(ModelEvent, (String, Set[(Set[String], Set[String])]))]) {
      val results = getQueue(classOf[PermissionSet]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkEventConfigs(correct: Set[(ModelEvent, (String, Int, EventConfig.Designation, Alarm.State, String, Boolean))]) {
      val results = getQueue(classOf[EventConfig]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkEvents(correct: Set[(ModelEvent, (String, Int, String, Boolean, String, String, Option[UUID], Option[String], Option[Long]))]) {
      val results = getQueue(classOf[Event]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkAlarms(correct: Set[(ModelEvent, (Alarm.State, String, Int, String, Boolean, String, String, Option[UUID], Option[Long]))]) {
      val results = getQueue(classOf[Alarm]).toSeq.map(tup => (tup._1, simplifyAlarmFull(tup._2)))
      setCheck(results, correct)
    }

    def checkAlarmsSimple(correct: Set[(ModelEvent, (Long, Alarm.State))]) {
      val results = getQueue(classOf[Alarm]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    def checkConnectionStatus(correct: Set[(ModelEvent, (String, FrontEndConnectionStatus.Status))]) {
      val results = getQueue(classOf[FrontEndConnectionStatus]).toSeq.map(tup => (tup._1, simplify(tup._2)))
      setCheck(results, correct)
    }

    private def setCheck[A](result: Seq[A], correct: Set[A]) {
      result.size should equal(correct.size)
      result.toSet should equal(correct)
    }
  }

}
