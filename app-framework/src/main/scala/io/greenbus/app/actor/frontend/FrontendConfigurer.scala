/**
 * Copyright 2011-2016 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.greenbus.app.actor.frontend

import akka.actor.{ Props, ActorRef, Actor }
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.msg.{ SessionUnusableException, Subscription, Session }
import io.greenbus.app.actor.MessageScheduling
import io.greenbus.client.exception.UnauthorizedException
import io.greenbus.client.proto.Envelope.SubscriptionEventType._
import io.greenbus.client.service.ModelService
import io.greenbus.client.service.proto.Model._
import io.greenbus.client.service.proto.ModelRequests._

import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.JavaConversions._

case class FrontendConfiguration(
    points: Seq[Point],
    commands: Seq[Command],
    configs: Seq[EntityKeyValue],
    modelEdgeSub: Subscription[EntityEdgeNotification],
    configSub: Subscription[EntityKeyValueNotification]) {

  def cancelAll(): Unit = {
    modelEdgeSub.cancel()
    configSub.cancel()
  }
}

object FrontendConfigurer {

  private def fetchPoints(session: Session, endpoint: Endpoint)(implicit context: ExecutionContext): Future[Seq[Point]] = {
    val modelClient = ModelService.client(session)
    fetchFrontEndType(session, endpoint, "Point", modelClient.getPoints)
  }

  private def fetchCommands(session: Session, endpoint: Endpoint)(implicit context: ExecutionContext): Future[Seq[Command]] = {
    val modelClient = ModelService.client(session)
    fetchFrontEndType(session, endpoint, "Command", modelClient.getCommands)
  }

  private def frontEndTypePage[A](session: Session, endpoint: Endpoint, results: Seq[A], lastUuid: Option[ModelUUID], typ: String, get: EntityKeySet => Future[Seq[A]])(implicit context: ExecutionContext): Future[Seq[A]] = {
    val modelClient = ModelService.client(session)

    val pageSize = 300

    val objRelationBuilder = EntityRelationshipFlatQuery.newBuilder()
      .addStartUuids(endpoint.getUuid)
      .setDescendantOf(true)
      .setRelationship("source")
      .addEndTypes(typ)

    val pageParamsB = EntityPagingParams.newBuilder()
      .setPageSize(pageSize)

    lastUuid.foreach(pageParamsB.setLastUuid)

    val entFut = modelClient.relationshipFlatQuery(objRelationBuilder.setPagingParams(pageParamsB).build())

    entFut.flatMap { ents =>
      if (ents.isEmpty) {
        Future.successful(results)
      } else {
        val entityKeySet = EntityKeySet.newBuilder().addAllUuids(ents.map(_.getUuid)).build()

        get(entityKeySet).flatMap { objs =>
          if (ents.size < pageSize) {
            Future.successful(results ++ objs)
          } else {
            frontEndTypePage(session, endpoint, results ++ objs, Some(ents.last.getUuid), typ, get)
          }
        }
      }
    }

  }

  private def fetchFrontEndType[A](session: Session, endpoint: Endpoint, typ: String, get: EntityKeySet => Future[Seq[A]])(implicit context: ExecutionContext): Future[Seq[A]] = {
    frontEndTypePage(session, endpoint, Seq(), None, typ, get)
  }

  private def fetchConfig(session: Session, endpoint: Endpoint, keys: Seq[String])(implicit context: ExecutionContext): Future[Seq[EntityKeyValue]] = {
    val modelClient = ModelService.client(session)

    val keyPairs = keys.map { key => EntityKeyPair.newBuilder().setUuid(endpoint.getUuid).setKey(key).build() }

    if (keyPairs.nonEmpty) {
      modelClient.getEntityKeyValues(keyPairs)
    } else {
      Future.successful(Seq())
    }
  }

  private def fetchConfigSub(session: Session, endpoint: Endpoint, keys: Seq[String])(implicit context: ExecutionContext): Future[Subscription[EntityKeyValueNotification]] = {
    val modelClient = ModelService.client(session)

    val keyPairs = keys.map { k => EntityKeyPair.newBuilder().setUuid(endpoint.getUuid).setKey(k).build() }

    val subQuery = EntityKeyValueSubscriptionQuery.newBuilder()
      .addAllKeyPairs(keyPairs)
      .build()

    modelClient.subscribeToEntityKeyValues(subQuery).map(_._2)
  }

  private def fetchEndpointEdgesSub(session: Session, endpoint: Endpoint)(implicit context: ExecutionContext): Future[Subscription[EntityEdgeNotification]] = {
    val modelClient = ModelService.client(session)

    val edgeQuery = EntityEdgeSubscriptionQuery.newBuilder()
      .addFilters(EntityEdgeFilter.newBuilder()
        .setParentUuid(endpoint.getUuid)
        .setRelationship("source")
        .setDistance(1)
        .build())
      .build()

    modelClient.subscribeToEdges(edgeQuery).map(_._2)
  }

  private def fetchSubs(session: Session, endpoint: Endpoint, keys: Seq[String])(implicit context: ExecutionContext): Future[(Subscription[EntityEdgeNotification], Subscription[EntityKeyValueNotification])] = {

    val entEdgeSubFut = fetchEndpointEdgesSub(session, endpoint)

    entEdgeSubFut.flatMap { entEdgeSub =>
      val configSubFut = fetchConfigSub(session, endpoint, keys)
      configSubFut.onFailure { case _ => entEdgeSub.cancel() }

      configSubFut.map(configSub => (entEdgeSub, configSub))
    }
  }

  def frontendConfiguration(session: Session, endpoint: Endpoint, keys: Seq[String])(implicit context: ExecutionContext): Future[FrontendConfiguration] = {
    val subsFut = fetchSubs(session, endpoint, keys)

    val staticFut = fetchPoints(session, endpoint) zip fetchCommands(session, endpoint) zip fetchConfig(session, endpoint, keys)

    staticFut.onFailure { case _ => subsFut.foreach { case (modelSub, configSub) => modelSub.cancel(); configSub.cancel() } }

    (staticFut zip subsFut).map {
      case (((points, commands), configs), (modelSub, configSub)) =>
        FrontendConfiguration(points, commands, configs, modelSub, configSub)
    }
  }

}

object FrontendConfigUpdater {

  case class ConfigFilesUpdated(configKeyValues: Seq[EntityKeyValue])
  case class PointsUpdated(points: Seq[Point])
  case class CommandsUpdated(commands: Seq[Command])

  private case class ObjectAdded(uuid: ModelUUID)
  private case class ObjectRemoved(uuid: ModelUUID)

  private case class ConfigAdded(config: EntityKeyValue)
  private case class ConfigModified(config: EntityKeyValue)
  private case class ConfigRemoved(uuid: ModelUUID, key: String)

  private case class PointRetrieved(point: Point)
  private case class CommandRetrieved(command: Command)

  private case class ConfigRetrieveError(uuid: ModelUUID, ex: Throwable)
  private case class PointCommandRetrieveError(uuid: ModelUUID, ex: Throwable)

  private case class ObjectAddedRetry(uuid: ModelUUID)
  private case class ConfigEdgeAddedRetry(uuid: ModelUUID)

  def props(
    subject: ActorRef,
    endpoint: Endpoint,
    session: Session,
    config: FrontendConfiguration,
    requestRetryMs: Long): Props = {

    Props(classOf[FrontendConfigUpdater], subject, endpoint, session, config, requestRetryMs)
  }
}

class FrontendConfigUpdater(
    subject: ActorRef,
    endpoint: Endpoint,
    session: Session,
    config: FrontendConfiguration,
    requestRetryMs: Long) extends Actor with MessageScheduling with LazyLogging {
  import FrontendConfigUpdater._
  import context.dispatcher

  private var pointMap = config.points.map(p => (p.getUuid, p)).toMap
  private var commandMap = config.commands.map(c => (c.getUuid, c)).toMap
  private var configMap: Map[(ModelUUID, String), EntityKeyValue] = config.configs.map(cf => ((cf.getUuid, cf.getKey), cf)).toMap

  private var outstandingSourceEnts = Set.empty[ModelUUID]

  config.modelEdgeSub.start { edgeNotification =>
    val objUuid = edgeNotification.getValue.getChild
    edgeNotification.getEventType match {
      case MODIFIED =>
      case ADDED => self ! ObjectAdded(objUuid)
      case REMOVED => self ! ObjectRemoved(objUuid)
    }
  }

  config.configSub.start { configNotification =>
    configNotification.getEventType match {
      case ADDED => self ! ConfigAdded(configNotification.getValue)
      case MODIFIED => self ! ConfigModified(configNotification.getValue)
      case REMOVED => self ! ConfigRemoved(configNotification.getValue.getUuid, configNotification.getValue.getKey)
    }
  }

  override def postStop(): Unit = {
    config.modelEdgeSub.cancel()
    config.configSub.cancel()
  }

  def receive = {
    case ConfigAdded(kvp) =>
      logger.debug("Saw config add for " + kvp.getKey + " on endpoint " + endpoint.getName)
      configMap += ((kvp.getUuid, kvp.getKey) -> kvp)
      subject ! ConfigFilesUpdated(configMap.values.toSeq)

    case ConfigModified(kvp) =>
      logger.debug("Saw config modified for " + kvp.getKey + " on endpoint " + endpoint.getName)
      configMap += ((kvp.getUuid, kvp.getKey) -> kvp)
      subject ! ConfigFilesUpdated(configMap.values.toSeq)

    case ConfigRemoved(uuid, key) =>
      logger.debug("Saw config remove for " + uuid.getValue + " on endpoint " + endpoint.getName)
      configMap -= ((uuid, key))
      subject ! ConfigFilesUpdated(configMap.values.toSeq)

    case ObjectAdded(objUuid: ModelUUID) =>
      logger.debug("Saw object add for " + objUuid.getValue + " on endpoint " + endpoint.getName)
      outstandingSourceEnts += objUuid
      retrieveEndpointAssociatedObject(objUuid)

    case ObjectRemoved(objUuid: ModelUUID) =>
      logger.debug("Saw object remove for " + objUuid.getValue + " on endpoint " + endpoint.getName)
      outstandingSourceEnts -= objUuid
      if (pointMap.contains(objUuid)) {
        pointMap -= objUuid
        subject ! PointsUpdated(pointMap.values.toSeq)
      } else if (commandMap.contains(objUuid)) {
        commandMap -= objUuid
        subject ! CommandsUpdated(commandMap.values.toSeq)
      } else {
        logger.warn("Removed event for " + objUuid.getValue + " did not correspond to obj owned by endpoint " + endpoint.getName)
      }

    case PointRetrieved(point: Point) =>
      logger.debug("Retrieved point for " + point.getName + " on endpoint " + endpoint.getName)
      if (outstandingSourceEnts.contains(point.getUuid)) {
        pointMap += (point.getUuid -> point)
        subject ! PointsUpdated(pointMap.values.toSeq)
      }

    case CommandRetrieved(cmd: Command) =>
      logger.debug("Retrieved command for " + cmd.getName + " on endpoint " + endpoint.getName)
      if (outstandingSourceEnts.contains(cmd.getUuid)) {
        commandMap += (cmd.getUuid -> cmd)
        subject ! CommandsUpdated(commandMap.values.toSeq)
      }

    case PointCommandRetrieveError(uuid, ex) =>
      logger.warn("Point/command lookup error in endpoint " + endpoint.getName)
      ex match {
        case ex: SessionUnusableException => throw ex
        case ex: UnauthorizedException => throw ex
        case ex: Throwable => scheduleMsg(requestRetryMs, ObjectAddedRetry(uuid))
      }

    case ObjectAddedRetry(uuid) =>
      if (outstandingSourceEnts.contains(uuid)) {
        retrieveEndpointAssociatedObject(uuid)
      }
  }

  private def retrieveEndpointAssociatedObject(uuid: ModelUUID) {
    val lookupFut = lookupFrontEndEntity(uuid).flatMap {
      case None =>
        logger.warn("Saw endpoint owned object, no entity found")
        Future.successful(None)
      case Some(ent) =>
        if (ent.getTypesList.exists(_ == "Point")) {
          lookupPoint(uuid)
        } else if (ent.getTypesList.exists(_ == "Command")) {
          lookupCommand(uuid)
        } else {
          logger.warn("Saw endpoint owned object that was not a point or command")
          Future.successful(None)
        }
    }

    lookupFut.onSuccess {
      case None =>
      case Some(p: Point) => self ! PointRetrieved(p)
      case Some(c: Command) => self ! CommandRetrieved(c)
    }

    lookupFut.onFailure {
      case ex: Throwable => self ! PointCommandRetrieveError(uuid, ex)
    }
  }

  private def lookupFrontEndEntity(uuid: ModelUUID): Future[Option[Entity]] = {
    val modelClient = ModelService.client(session)
    val keySet = EntityKeySet.newBuilder().addUuids(uuid).build()
    modelClient.get(keySet).map(_.headOption)
  }

  private def lookupPoint(uuid: ModelUUID): Future[Option[Point]] = {
    val modelClient = ModelService.client(session)
    val keySet = EntityKeySet.newBuilder().addUuids(uuid).build()
    modelClient.getPoints(keySet).map(_.headOption)
  }
  private def lookupCommand(uuid: ModelUUID): Future[Option[Command]] = {
    val modelClient = ModelService.client(session)
    val keySet = EntityKeySet.newBuilder().addUuids(uuid).build()
    modelClient.getCommands(keySet).map(_.headOption)
  }
}
