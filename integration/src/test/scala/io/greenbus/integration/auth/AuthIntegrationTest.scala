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
package io.greenbus.integration.auth

import java.util.UUID

import akka.actor.{ ActorRef, ActorSystem }
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.ServiceConnection
import io.greenbus.client.exception.ForbiddenException
import io.greenbus.client.service.ModelService
import io.greenbus.client.service.ModelService.Descriptors.EntityQuery
import io.greenbus.client.service.proto.Model.{ CommandCategory, PointCategory, ModelUUID }
import io.greenbus.client.service.proto.ModelRequests
import io.greenbus.client.service.proto.ModelRequests.{ EntityKeyPair, EntityEdgeDescriptor, EntityPagingParams }
import io.greenbus.integration.IntegrationConfig
import io.greenbus.integration.IntegrationConfig._
import io.greenbus.integration.tools.PollingUtils._
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.services.data._
import io.greenbus.services.{ CoreServices, ResetDatabase, ServiceManager }
import io.greenbus.sql.{ DbConnector, SqlSettings }
import io.greenbus.util.UserSettings
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, FunSuite }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

@RunWith(classOf[JUnitRunner])
class AuthIntegrationTest extends FunSuite with Matchers with LazyLogging with BeforeAndAfterEach with BeforeAndAfterAll {
  import AuthModelHelpers._
  val testConfigPath = "io.greenbus.test.cfg"
  val amqpConfig = AmqpSettings.load(testConfigPath)
  val userConfig = UserSettings.load(testConfigPath)
  val system = ActorSystem("integrationTest")
  var services = Option.empty[ActorRef]
  var connection = Option.empty[ServiceConnection]
  var agentRig = Option.empty[AgentRig]
  val dbConnection = DbConnector.connect(SqlSettings.load(testConfigPath)) //ConnectionStorage.connect()
  var sessionOpt = Option.empty[Session]

  override protected def beforeAll(): Unit = {
    ResetDatabase.reset(testConfigPath)
    services = Some(system.actorOf(ServiceManager.props(testConfigPath, testConfigPath, CoreServices.runServices)))

    val conn = ServiceConnection.connect(amqpConfig, QpidBroker, 5000)
    connection = Some(conn)

    pollForSuccess(500, 5000) {
      Await.result(conn.login(userConfig.user, userConfig.password), 500.milliseconds)
    }
  }

  override protected def beforeEach(): Unit = {
    ResetDatabase.reset(testConfigPath)
    agentRig = dbConnection.transaction {
      Some(new AgentRig)
    }
    sessionOpt = Some(Await.result(connection.get.login(userConfig.user, userConfig.password), 5000.milliseconds))
  }

  override protected def afterEach(): Unit = {

  }

  class AgentRig {
    val noPermsAgent = createAgent("noperm")

    val denyAllAgent = createAgent("denyAll")
    val denyAllPerm = createPermissionSet("denyAllPerm", Seq(createPermission(false, "*", "*")))
    addPermissionToAgent(denyAllPerm.id, denyAllAgent.id)

    val readOnlyAgent = createAgent("readOnly")
    val readOnlyPerm = createPermissionSet("readOnlyPerm", Seq(createPermission(true, "*", "read")))
    addPermissionToAgent(denyAllPerm.id, denyAllAgent.id)
  }

  def loginAs(agent: String): Session = {
    Await.result(connection.get.login(agent, "password"), 5000.milliseconds)
  }

  def checkForbidden[A](f: => Future[A]): Unit = {
    intercept[ForbiddenException] {
      Await.result(f, 5000.milliseconds)
    }
  }

  class ModelLookups(session: Session) {

    val nameToUuid: Map[String, ModelUUID] = {
      val modelClient = ModelService.client(session)

      val entQuery = modelClient.entityQuery(ModelRequests.EntityQuery.newBuilder().setPagingParams(EntityPagingParams.newBuilder().setPageSize(1000)).build())

      val entities = Await.result(entQuery, 5000.milliseconds)

      entities.map(e => (e.getName, e.getUuid)).toMap
    }
  }

  def edgeDesc(parent: ModelUUID, relation: String, child: ModelUUID) = {
    EntityEdgeDescriptor.newBuilder()
      .setParentUuid(parent)
      .setRelationship(relation)
      .setChildUuid(child)
      .build()
  }

  def putsRejectionBattery(session: Session, entA: ModelUUID, entB: ModelUUID): Unit = {
    val modelClient = ModelService.client(session)

    checkForbidden {
      modelClient.put(Seq(entTemplate("name01")))
    }
    checkForbidden {
      modelClient.putPoints(Seq(pointTemplate("name01")))
    }
    checkForbidden {
      modelClient.putCommands(Seq(commandTemplate("name01")))
    }
    checkForbidden {
      modelClient.putEndpoints(Seq(endpointTemplate("name01")))
    }
    checkForbidden {
      modelClient.putEdges(Seq(edgeTemplate(entA, entB)))
    }
    checkForbidden {
      modelClient.putEntityKeyValues(Seq(kvTemplate(entA)))
    }
  }

  def deletesRejectionBattery(session: Session, uuidFor: String => ModelUUID): Unit = {
    val modelClient = ModelService.client(session)

    checkForbidden {
      modelClient.delete(Seq(uuidFor("RootA")))
    }
    checkForbidden {
      modelClient.deletePoints(Seq(uuidFor("PointAAA")))
    }
    checkForbidden {
      modelClient.deleteCommands(Seq(uuidFor("CommandAAA")))
    }
    checkForbidden {
      modelClient.deleteEndpoints(Seq(uuidFor("EndpointA")))
    }
    checkForbidden {
      modelClient.deleteEdges(Seq(edgeDesc(uuidFor("RootA"), "owns", uuidFor("EquipAA"))))
    }
    checkForbidden {
      modelClient.deleteEntityKeyValues(Seq(EntityKeyPair.newBuilder().setUuid(uuidFor("RootA")).setKey("keyA").build()))
    }
  }

  def putUpdatesRejectionBattery(session: Session, uuidFor: String => ModelUUID): Unit = {
    val modelClient = ModelService.client(session)

    checkForbidden {
      modelClient.put(Seq(entTemplate("RootA", Seq("DifferentType"))))
    }
    checkForbidden {
      modelClient.putPoints(Seq(pointTemplate("PointAAA", PointCategory.STATUS)))
    }
    checkForbidden {
      modelClient.putCommands(Seq(commandTemplate("name01", CommandCategory.SETPOINT_DOUBLE)))
    }
    checkForbidden {
      modelClient.putEndpoints(Seq(endpointTemplate("name01", "DifferentProtocol")))
    }
    checkForbidden {
      modelClient.putEntityKeyValues(Seq(kvTemplate(uuidFor("RootA"), "keyA", "differentValue")))
    }
  }

  import io.greenbus.services.model.UUIDHelpers._

  test("put rejections on near-blank model") {
    val ar = agentRig.get

    val (entA, entB) = dbConnection.transaction {
      (ServicesSchema.entities.insert(EntityRow(UUID.randomUUID(), "entA")),
        ServicesSchema.entities.insert(EntityRow(UUID.randomUUID(), "entB")))
    }

    val noPermsSession = loginAs(ar.noPermsAgent.name)
    putsRejectionBattery(noPermsSession, entA.id, entB.id)

    val denyAllSession = loginAs(ar.denyAllAgent.name)
    putsRejectionBattery(denyAllSession, entA.id, entB.id)

    val readOnlySession = loginAs(ar.readOnlyAgent.name)
    putsRejectionBattery(readOnlySession, entA.id, entB.id)
  }

  test("delete rejections") {
    val ar = agentRig.get

    IntegrationConfig.loadFragment(AuthModelBuilder.buildConfigModel(), sessionOpt.get)
    val ml = new ModelLookups(sessionOpt.get)

    val noPermsSession = loginAs(ar.noPermsAgent.name)
    deletesRejectionBattery(noPermsSession, ml.nameToUuid.apply)

    val denyAllSession = loginAs(ar.denyAllAgent.name)
    deletesRejectionBattery(denyAllSession, ml.nameToUuid.apply)

    val readOnlySession = loginAs(ar.readOnlyAgent.name)
    deletesRejectionBattery(readOnlySession, ml.nameToUuid.apply)
  }

  test("update rejections") {
    val ar = agentRig.get

    IntegrationConfig.loadFragment(AuthModelBuilder.buildConfigModel(), sessionOpt.get)
    val ml = new ModelLookups(sessionOpt.get)

    val noPermsSession = loginAs(ar.noPermsAgent.name)
    putUpdatesRejectionBattery(noPermsSession, ml.nameToUuid.apply)

    val denyAllSession = loginAs(ar.denyAllAgent.name)
    putUpdatesRejectionBattery(denyAllSession, ml.nameToUuid.apply)

    val readOnlySession = loginAs(ar.readOnlyAgent.name)
    putUpdatesRejectionBattery(readOnlySession, ml.nameToUuid.apply)
  }
}
