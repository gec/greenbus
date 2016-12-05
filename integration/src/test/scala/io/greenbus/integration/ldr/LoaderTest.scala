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
package io.greenbus.integration.ldr

import java.io.File
import java.util.UUID

import akka.actor.{ ActorRef, ActorSystem }
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.ServiceConnection
import io.greenbus.client.service.ModelService
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.client.service.proto.ModelRequests.{ EndpointQuery, EntityKeySet, EntityEdgeQuery, EntityQuery }
import io.greenbus.ldr.XmlImporter
import io.greenbus.ldr.xml.Configuration
import io.greenbus.loader.set.{ UUIDHelpers, LoadingException }
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.services.{ CoreServices, ResetDatabase, ServiceManager }
import io.greenbus.util.{ XmlHelper, UserSettings }
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.{ BeforeAndAfterAll, FunSuite }

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class LoaderTest extends FunSuite with Matchers with LazyLogging with BeforeAndAfterAll {
  import io.greenbus.integration.tools.PollingUtils._

  val testConfigPath = "io.greenbus.test.cfg"
  val amqpConfig = AmqpSettings.load(testConfigPath)
  val userConfig = UserSettings.load(testConfigPath)
  val system = ActorSystem("integrationTest")
  var services = Option.empty[ActorRef]
  var conn = Option.empty[ServiceConnection]
  var session = Option.empty[Session]
  val parentDir = new File(".")

  override protected def beforeAll(): Unit = {
    ResetDatabase.reset(testConfigPath)

    logger.info("starting services")
    services = Some(system.actorOf(ServiceManager.props(testConfigPath, testConfigPath, CoreServices.runServices)))

    val conn = ServiceConnection.connect(amqpConfig, QpidBroker, 5000)
    this.conn = Some(conn)

    val session = pollForSuccess(500, 5000) {
      Await.result(conn.login(userConfig.user, userConfig.password), 500.milliseconds)
    }

    this.session = Some(session)
  }

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination()
    this.conn.get.disconnect()
  }

  def nameToUuidMap(session: Session): Map[String, ModelUUID] = {

    val modelClient = ModelService.client(session)

    val allEntities = Await.result(modelClient.entityQuery(EntityQuery.newBuilder().build()), 5000.milliseconds)

    allEntities.map(e => (e.getName, e.getUuid)).toMap
  }

  test("initial import") {
    val session = this.session.get

    val xml = XmlHelper.read(TestXml.xmlSimple, classOf[Configuration])

    XmlImporter.importFromXml(amqpConfig, userConfig, None, xml, parentDir, prompt = false)

    val modelClient = ModelService.client(session)

    val allEntities = Await.result(modelClient.entityQuery(EntityQuery.newBuilder().build()), 5000.milliseconds)

    allEntities.size should equal(7)

    val rootA = allEntities.find(_.getName == "RootA").get
    val equipA = allEntities.find(_.getName == "EquipA").get
    val equipB = allEntities.find(_.getName == "EquipB").get
    val pointA = allEntities.find(_.getName == "PointA").get
    val commandA = allEntities.find(_.getName == "CommandA").get
    val endpointA = allEntities.find(_.getName == "EndpointA").get
    val dangling = allEntities.find(_.getName == "DanglingEndpoint").get

    rootA.getTypesList.toSet should equal(Set("Root"))
    equipA.getTypesList.toSet should equal(Set("Equip"))
    equipB.getTypesList.toSet should equal(Set("Equip", "TypeA"))
    pointA.getTypesList.toSet should equal(Set("Point", "PointTypeA"))
    commandA.getTypesList.toSet should equal(Set("Command", "CommandTypeA"))
    endpointA.getTypesList.toSet should equal(Set("Endpoint", "EndpointTypeA"))

    val allUuids = Seq(rootA, equipA, equipB, pointA, commandA, endpointA).map(_.getUuid)

    val allKeys = Await.result(modelClient.getEntityKeys(allUuids), 5000.milliseconds)

    val allKeyPairs = Await.result(modelClient.getEntityKeyValues(allKeys), 5000.milliseconds)

    allKeyPairs.size should equal(1)
    val key = allKeyPairs.find(_.getUuid == equipA.getUuid).get
    key.getKey should equal("keyA")
    key.getValue.hasStringValue should equal(true)
    key.getValue.getStringValue should equal("valueA")

    val edgeQuery = EntityEdgeQuery.newBuilder()
      .addAllParentUuids(allUuids)
      .addAllChildUuids(allUuids)
      .setDepthLimit(1)
      .setPageSize(Int.MaxValue)
      .build()

    val allEdges = Await.result(modelClient.edgeQuery(edgeQuery), 5000.milliseconds)

    val edgeSet = Set(
      (rootA.getUuid, "owns", equipA.getUuid),
      (rootA.getUuid, "owns", equipB.getUuid),
      (equipA.getUuid, "owns", pointA.getUuid),
      (equipA.getUuid, "owns", commandA.getUuid),
      (pointA.getUuid, "feedback", commandA.getUuid),
      (endpointA.getUuid, "source", pointA.getUuid),
      (endpointA.getUuid, "source", commandA.getUuid))

    allEdges.map(e => (e.getParent, e.getRelationship, e.getChild)).toSet should equal(edgeSet)
  }

  test("rename name, with source name unchanged") {
    val session = this.session.get

    val modelClient = ModelService.client(session)

    val keySet = EntityKeySet.newBuilder()
      .addNames("PointA")
      .build()

    val initialResult = Await.result(modelClient.get(keySet), 5000.milliseconds)
    initialResult.size should equal(1)
    val pointAUuid = initialResult.get(0).getUuid

    val xml = XmlHelper.read(TestXml.buildXmlSimple("RenamedPointA", Some(UUIDHelpers.protoUUIDToUuid(pointAUuid))), classOf[Configuration])

    XmlImporter.importFromXml(amqpConfig, userConfig, None, xml, parentDir, prompt = false)

    val laterKeySet = EntityKeySet.newBuilder()
      .addUuids(pointAUuid)
      .build()

    val laterResult = Await.result(modelClient.get(laterKeySet), 5000.milliseconds)
    laterResult.size should equal(1)
    laterResult.get(0).getName should equal("RenamedPointA")

    val edgeQuery = EntityEdgeQuery.newBuilder()
      .addChildUuids(pointAUuid)
      .setDepthLimit(1)
      .setPageSize(Int.MaxValue)
      .build()

    val allEdges = Await.result(modelClient.edgeQuery(edgeQuery), 5000.milliseconds)

    val nameToUuid = nameToUuidMap(session)

    val edgeSet = Set(
      (nameToUuid("EquipA"), "owns", pointAUuid),
      (nameToUuid("EndpointA"), "source", pointAUuid))

    val allEdgeSet = allEdges.map(e => (e.getParent, e.getRelationship, e.getChild)).toSet

    println(allEdgeSet)

    //edgeSet.forall(allEdgeSet.contains) should equal(true)
    edgeSet should equal(allEdgeSet)
  }

  test("dangling endpoint doesn't prevent re-upload") {
    val session = this.session.get

    val xml = XmlHelper.read(TestXml.xmlSimple, classOf[Configuration])

    XmlImporter.importFromXml(amqpConfig, userConfig, None, xml, parentDir, prompt = false)

    val modelClient = ModelService.client(session)

    val endpoints = Await.result(modelClient.endpointQuery(EndpointQuery.newBuilder.build), 5000.milliseconds)

    endpoints.size should equal(2)
  }

  test("double key") {
    val session = this.session.get

    val xml = XmlHelper.read(TestXml.xmlDoubleKey, classOf[Configuration])

    intercept[LoadingException] {
      XmlImporter.importFromXml(amqpConfig, userConfig, None, xml, parentDir, prompt = false)
    }

    val modelClient = ModelService.client(session)

    val allEntities = Await.result(modelClient.get(EntityKeySet.newBuilder().addNames("RootB").build()), 5000.milliseconds)
    allEntities.size should equal(0)
  }

  test("double name in same frag") {
    val session = this.session.get

    val xml = XmlHelper.read(TestXml.xmlRootBWithDuplicate, classOf[Configuration])

    intercept[LoadingException] {
      XmlImporter.importFromXml(amqpConfig, userConfig, None, xml, parentDir, prompt = false)
    }

    val modelClient = ModelService.client(session)

    val allEntities = Await.result(modelClient.get(EntityKeySet.newBuilder().addNames("RootB").build()), 5000.milliseconds)
    allEntities.size should equal(0)
  }

  test("duplicate source edge") {
    val session = this.session.get

    val xml = XmlHelper.read(TestXml.xmlDuplicateSource, classOf[Configuration])

    intercept[LoadingException] {
      XmlImporter.importFromXml(amqpConfig, userConfig, None, xml, parentDir, prompt = false)
    }
  }

  test("rename to name that already exists in another fragment") {
    val session = this.session.get

    val xml = XmlHelper.read(TestXml.xmlRootB, classOf[Configuration])

    XmlImporter.importFromXml(amqpConfig, userConfig, None, xml, parentDir, prompt = false)

    val modelClient = ModelService.client(session)

    val keySet = EntityKeySet.newBuilder()
      .addNames("RootB")
      .addNames("EquipC")
      .build()

    val allEntities = Await.result(modelClient.get(keySet), 5000.milliseconds)
    allEntities.size should equal(2)

    val rootB = allEntities.find(_.getName == "RootB").get
    val equipC = allEntities.find(_.getName == "EquipC").get

    val xml2 = XmlHelper.read(TestXml.xmlRootBRename(rootB.getUuid, equipC.getUuid), classOf[Configuration])

    intercept[LoadingException] {
      XmlImporter.importFromXml(amqpConfig, userConfig, None, xml2, parentDir, prompt = false)
    }

    val xml3 = XmlHelper.read(TestXml.xmlRootBCreateDuplicate, classOf[Configuration])

    intercept[LoadingException] {
      XmlImporter.importFromXml(amqpConfig, userConfig, None, xml3, parentDir, prompt = false)
    }
  }

  test("promoting up the hierarchy does not cause diamond exception") {
    val session = this.session.get

    val xml = XmlHelper.read(TestXml.xmlRootC, classOf[Configuration])

    XmlImporter.importFromXml(amqpConfig, userConfig, None, xml, parentDir, prompt = false)

    val modelClient = ModelService.client(session)

    val keySet = EntityKeySet.newBuilder()
      .addNames("RootC")
      .addNames("EquipE")
      .addNames("PointC")
      .build()

    val allEntities = Await.result(modelClient.get(keySet), 5000.milliseconds)
    allEntities.size should equal(3)

    val rootC = allEntities.find(_.getName == "RootC").get
    val pointC = allEntities.find(_.getName == "PointC").get

    val xml2 = XmlHelper.read(TestXml.xmlRootCPromote(rootC.getUuid, pointC.getUuid), classOf[Configuration])

    XmlImporter.importFromXml(amqpConfig, userConfig, None, xml2, parentDir, prompt = false)

    val allEntitiesAfter = Await.result(modelClient.get(keySet), 5000.milliseconds)
    allEntitiesAfter.size should equal(2)
    allEntitiesAfter.map(_.getName).contains("EquipE") should equal(false)
  }

  test("deleting") {
    val session = this.session.get

    val xml = XmlHelper.read(TestXml.xmlSimpleDeleted, classOf[Configuration])

    XmlImporter.importFromXml(amqpConfig, userConfig, None, xml, parentDir, prompt = false)

    val modelClient = ModelService.client(session)

    val keySet = EntityKeySet.newBuilder()
      .addNames("RootA")
      .addNames("EquipA")
      .addNames("PointA")
      .addNames("CommandA")
      .addNames("EquipB")
      .build()

    val allEntities = Await.result(modelClient.get(keySet), 5000.milliseconds)
    allEntities.size should equal(1)
    allEntities.head.getName should equal("RootA")
  }
}

object TestXml {

  def uuidTag(uuid: Option[UUID]): String = {
    uuid.map(u => "uuid=\"" + u + "\"").getOrElse("")
  }

  def buildXmlSimple(pointAEquipName: String, pointAUuid: Option[UUID]): String = {
    s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<configuration xmlns="xml.ldr.greenbus.io">
      |    <equipmentModel>
      |        <equipment name="RootA">
      |            <type name="Root"/>
      |            <equipment name="EquipA">
      |                <type name="Equip"/>
      |                <keyValue key="keyA" stringValue="valueA"/>
      |                <analog name="${pointAEquipName}" unit="UnitA" ${uuidTag(pointAUuid)}>
      |                   <type name="PointTypeA"/>
      |                   <commands>
      |                       <reference name="CommandA"/>
      |                   </commands>
      |                </analog>
      |                <setpoint name="CommandA" displayName="CommandA" setpointType="integer">
      |                   <type name="CommandTypeA"/>
      |                </setpoint>
      |            </equipment>
      |            <equipment name="EquipB">
      |                <type name="Equip"/>
      |                <type name="TypeA"/>
      |            </equipment>
      |        </equipment>
      |    </equipmentModel>
      |    <endpointModel>
      |       <endpoint name="EndpointA" protocol="protocolA">
      |         <type name="Endpoint"/>
      |         <type name="EndpointTypeA"/>
      |         <source name="PointA" ${uuidTag(pointAUuid)}/>
      |         <source name="CommandA"/>
      |       </endpoint>
      |       <endpoint name="DanglingEndpoint" protocol="danglingProtocol">
      |         <type name="Endpoint"/>
      |       </endpoint>
      |    </endpointModel>
      |</configuration>
    """.stripMargin
  }

  val xmlSimple = buildXmlSimple("PointA", None)

  val xmlSimpleDeleted =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<configuration xmlns="xml.ldr.greenbus.io">
      |    <equipmentModel>
      |        <equipment name="RootA">
      |            <type name="Root"/>
      |        </equipment>
      |    </equipmentModel>
      |    <endpointModel>
      |       <endpoint name="EndpointA" protocol="protocolA">
      |         <type name="Endpoint"/>
      |         <type name="EndpointTypeA"/>
      |         <source name="PointA"/>
      |         <source name="CommandA"/>
      |       </endpoint>
      |    </endpointModel>
      |</configuration>
    """.stripMargin

  val xmlDoubleKey =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<configuration xmlns="xml.ldr.greenbus.io">
      |    <equipmentModel>
      |        <equipment name="RootB">
      |            <type name="Root"/>
      |            <keyValue key="keyA" stringValue="valueA"/>
      |            <keyValue key="keyA" stringValue="valueB"/>
      |        </equipment>
      |    </equipmentModel>
      |</configuration>
    """.stripMargin

  val xmlDuplicateSource =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<configuration xmlns="xml.ldr.greenbus.io">
      |    <equipmentModel>
      |        <equipment name="RootA">
      |            <type name="Root"/>
      |            <equipment name="EquipA">
      |                <type name="Equip"/>
      |                <keyValue key="keyA" stringValue="valueA"/>
      |                <analog name="PointA" unit="UnitA">
      |                   <type name="PointTypeA"/>
      |                   <commands>
      |                       <reference name="CommandA"/>
      |                   </commands>
      |                </analog>
      |                <analog name="PointB" unit="UnitB">
      |                   <type name="PointTypeB"/>
      |                </analog>
      |                <setpoint name="CommandA" displayName="CommandA" setpointType="integer">
      |                   <type name="CommandTypeA"/>
      |                </setpoint>
      |            </equipment>
      |            <equipment name="EquipB">
      |                <type name="Equip"/>
      |                <type name="TypeA"/>
      |            </equipment>
      |        </equipment>
      |    </equipmentModel>
      |    <endpointModel>
      |       <endpoint name="EndpointA" protocol="protocolA">
      |         <type name="Endpoint"/>
      |         <type name="EndpointTypeA"/>
      |         <source name="PointA"/>
      |         <source name="PointB"/>
      |         <source name="CommandA"/>
      |         <source name="PointB"/>
      |       </endpoint>
      |    </endpointModel>
      |</configuration>
    """.stripMargin

  val xmlRootBWithDuplicate =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<configuration xmlns="xml.ldr.greenbus.io">
      |    <equipmentModel>
      |        <equipment name="RootB">
      |            <type name="Root"/>
      |            <equipment name="EquipC">
      |                <type name="Equip"/>
      |                <type name="TypeB"/>
      |            </equipment>
      |            <equipment name="EquipC">
      |                <type name="Equip"/>
      |                <type name="TypeC"/>
      |            </equipment>
      |        </equipment>
      |    </equipmentModel>
      |</configuration>
    """.stripMargin

  val xmlRootB =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<configuration xmlns="xml.ldr.greenbus.io">
      |    <equipmentModel>
      |        <equipment name="RootB">
      |            <type name="Root"/>
      |            <equipment name="EquipC">
      |                <type name="Equip"/>
      |                <type name="TypeB"/>
      |            </equipment>
      |        </equipment>
      |    </equipmentModel>
      |</configuration>
    """.stripMargin

  def xmlRootBRename(rootBUuid: ModelUUID, equipCUuid: ModelUUID) =
    s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<configuration xmlns="xml.ldr.greenbus.io">
      |    <equipmentModel>
      |        <equipment uuid="${rootBUuid.getValue}" name="RootB">
      |            <type name="Root"/>
      |            <equipment uuid="${equipCUuid.getValue}" name="EquipB">
      |                <type name="Equip"/>
      |                <type name="TypeB"/>
      |            </equipment>
      |        </equipment>
      |    </equipmentModel>
      |</configuration>
    """.stripMargin

  val xmlRootBCreateDuplicate =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<configuration xmlns="xml.ldr.greenbus.io">
      |    <equipmentModel>
      |        <equipment name="RootB">
      |            <type name="Root"/>
      |            <equipment name="EquipC">
      |                <type name="Equip"/>
      |                <type name="TypeB"/>
      |            </equipment>
      |            <equipment name="EquipB">
      |                <type name="Equip"/>
      |                <type name="TypeD"/>
      |            </equipment>
      |        </equipment>
      |    </equipmentModel>
      |</configuration>
    """.stripMargin

  val xmlRootC =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<configuration xmlns="xml.ldr.greenbus.io">
      |    <equipmentModel>
      |        <equipment name="RootC">
      |            <type name="Root"/>
      |            <equipment name="EquipE">
      |                <type name="Equip"/>
      |                <type name="TypeB"/>
      |                <analog name="PointC" unit="UnitC">
      |                   <type name="PointTypeC"/>
      |                </analog>
      |            </equipment>
      |        </equipment>
      |    </equipmentModel>
      |</configuration>
    """.stripMargin

  def xmlRootCPromote(rootCUuid: ModelUUID, pointUuid: ModelUUID) =
    s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<configuration xmlns="xml.ldr.greenbus.io">
      |    <equipmentModel>
      |        <equipment uuid="${rootCUuid.getValue}" name="RootC">
      |            <type name="Root"/>
      |            <analog uuid="${pointUuid.getValue}" name="PointC" unit="UnitC">
      |               <type name="PointTypeC"/>
      |            </analog>
      |        </equipment>
      |    </equipmentModel>
      |</configuration>
    """.stripMargin
}
