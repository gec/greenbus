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
package io.greenbus.integration

import java.util.concurrent.TimeoutException

import akka.actor.{ ActorRef, ActorSystem, PoisonPill }
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.ServiceConnection
import io.greenbus.client.exception.LockedException
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.client.service.proto.FrontEndRequests.{ FrontEndRegistrationTemplate, FrontEndStatusUpdate }
import io.greenbus.client.service.proto.Measurements.Quality
import io.greenbus.client.service.proto.Model.{ Endpoint, ModelUUID }
import io.greenbus.client.service.proto.ModelRequests.{ EndpointQuery, EntityKeySet }
import io.greenbus.client.service.{ EventService, FrontEndService, MeasurementService, ModelService }
import io.greenbus.integration.IntegrationConfig._
import io.greenbus.integration.tools.PollingUtils._
import io.greenbus.measproc.MeasurementProcessor
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.services.{ ServiceManager, CoreServices, ResetDatabase }
import io.greenbus.util.UserSettings
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.{ BeforeAndAfterAll, FunSuite }

import scala.concurrent.Await
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class FailoverTests extends FunSuite with Matchers with LazyLogging with BeforeAndAfterAll {
  import IntegrationHelpers._

  val testConfigPath = "io.greenbus.test.cfg"
  val system = ActorSystem("failoverTest")
  var services = Option.empty[ActorRef]
  var processor = Option.empty[ActorRef]
  var processor2 = Option.empty[ActorRef]
  var conn = Option.empty[ServiceConnection]
  var session = Option.empty[Session]

  var pointAUuid = Option.empty[ModelUUID]
  var endpoint1 = Option.empty[Endpoint]
  var endpoint1Address1 = Option.empty[String]
  var endpoint1Address2 = Option.empty[String]
  var endpoint1Address3 = Option.empty[String]

  override protected def beforeAll(): Unit = {
    ResetDatabase.reset(testConfigPath)

    logger.info("starting services")
    services = Some(system.actorOf(ServiceManager.props(testConfigPath, testConfigPath, CoreServices.runServices)))

    val amqpConfig = AmqpSettings.load(testConfigPath)
    val conn = ServiceConnection.connect(amqpConfig, QpidBroker, 5000)
    this.conn = Some(conn)

    val userConfig = UserSettings.load(testConfigPath)

    val session = pollForSuccess(500, 5000) {
      Await.result(conn.login(userConfig.user, userConfig.password), 500.milliseconds)
    }

    this.session = Some(session)

    IntegrationConfig.loadFragment(buildConfigModel("Set1"), session)

    logger.info("starting processor")
    processor = Some(system.actorOf(MeasurementProcessor.buildProcessor(testConfigPath, testConfigPath, testConfigPath, testConfigPath, 1000, "testNode",
      standbyLockRetryPeriodMs = 500,
      standbyLockExpiryDurationMs = 1000)))

    val eventClient = EventService.client(session)

    Await.result(eventClient.putEventConfigs(eventConfigs()), 5000.milliseconds)
  }

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination()
    this.conn.get.disconnect()
  }

  test("registration") {

    val session = this.session.get

    val modelClient = ModelService.client(session)
    val frontEndClient = FrontEndService.client(session)

    val points = Await.result(modelClient.getPoints(EntityKeySet.newBuilder().addNames("Set1PointA").addNames("Set1PointB").build()), 5000.milliseconds)
    points.size should equal(2)
    val pointA = points.find(_.getName == "Set1PointA").get
    pointAUuid = Some(pointA.getUuid)
    val pointB = points.find(_.getName == "Set1PointB").get

    val endpoints = Await.result(modelClient.endpointQuery(EndpointQuery.newBuilder().addProtocols("test").build()), 5000.milliseconds)

    endpoints.size should equal(1)
    val endpoint = endpoints.head

    // let the measproc do its config, just a chance to avoid 2s wasted in the next step
    Thread.sleep(500)

    val reg = FrontEndRegistrationTemplate.newBuilder()
      .setEndpointUuid(endpoint.getUuid)
      .build()

    val registration = retryFutureUntilSuccess(3, 2000) {
      frontEndClient.putFrontEndRegistration(reg, endpoint.getUuid.getValue)
    }

    // FIRST MEASPROC IS UP, START SECOND
    processor2 = Some(system.actorOf(MeasurementProcessor.buildProcessor(testConfigPath, testConfigPath, testConfigPath, testConfigPath, 1000, "testNode2",
      standbyLockRetryPeriodMs = 500,
      standbyLockExpiryDurationMs = 1000)))
    // -- CONTINUE

    registration.getEndpointUuid should equal(endpoint.getUuid)

    val address = registration.getInputAddress
    this.endpoint1 = Some(endpoint)
    this.endpoint1Address1 = Some(address)

    val measClient = MeasurementService.client(session)

    val original = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
    original.size should equal(0)

    val postTime = System.currentTimeMillis()
    postAndGet(session, pointA.getUuid, analogBatch(pointA.getUuid, 1.45, postTime), address)
  }

  test("second registration fails") {
    val session = this.session.get
    val endpointUuid = this.endpoint1.get.getUuid

    val frontEndClient = FrontEndService.client(session)

    val reg = FrontEndRegistrationTemplate.newBuilder()
      .setEndpointUuid(endpointUuid)
      .setFepNodeId("node2")
      .build()

    val regFut = frontEndClient.putFrontEndRegistration(reg, endpointUuid.getValue)

    intercept[LockedException] {
      Await.result(regFut, 5000.milliseconds)
    }
  }

  private def checkMarkedOffline(v: Double): Unit = {
    checkMarked(v, Quality.Validity.INVALID)
  }
  private def checkMarkedOnline(v: Double): Unit = {
    checkMarked(v, Quality.Validity.GOOD)
  }

  private def checkMarked(v: Double, q: Quality.Validity): Unit = {
    val session = this.session.get
    val measClient = MeasurementService.client(session)

    val markedOffline = Await.result(measClient.getCurrentValues(List(pointAUuid.get)), 5000.milliseconds)
    markedOffline.size should equal(1)
    checkAnalog(markedOffline.head.getValue, v, None, q)
  }

  test("meas marked offline") {
    val session = this.session.get
    val pointAUuid = this.pointAUuid.get

    val measClient = MeasurementService.client(session)

    retryUntilSuccess(10, 500) {
      checkMarkedOffline(1.45)
    }
  }

  test("meas publish brings endpoint back online") {
    val session = this.session.get
    val pointAUuid = this.pointAUuid.get
    val endpoint1Address = this.endpoint1Address1.get

    val postTime = System.currentTimeMillis()
    postAndGet(session, pointAUuid, analogBatch(pointAUuid, 1.8, postTime), endpoint1Address)
  }

  test("go down again; status publish brings back online") {
    val session = this.session.get
    val endpointUuid = this.endpoint1.get.getUuid
    val endpoint1Address = this.endpoint1Address1.get

    val frontEndClient = FrontEndService.client(session)

    retryUntilSuccess(10, 500) {
      checkMarkedOffline(1.8)
    }

    val status = FrontEndStatusUpdate.newBuilder().setEndpointUuid(endpointUuid).setStatus(FrontEndConnectionStatus.Status.COMMS_UP).build()
    val updateFut = frontEndClient.putFrontEndConnectionStatuses(Seq(status), endpoint1Address)
    Await.result(updateFut, 5000.milliseconds)

    checkMarkedOnline(1.8)
  }

  test("second endpoint logs in") {
    val session = this.session.get
    val endpointUuid = this.endpoint1.get.getUuid

    val measClient = MeasurementService.client(session)
    val frontEndClient = FrontEndService.client(session)

    retryUntilSuccess(10, 500) {
      checkMarkedOffline(1.8)
    }

    val reg = FrontEndRegistrationTemplate.newBuilder()
      .setEndpointUuid(endpointUuid)
      .setFepNodeId("node2")
      .build()

    val regFut = frontEndClient.putFrontEndRegistration(reg, endpointUuid.getValue)

    val registration = Await.result(regFut, 5000.milliseconds)

    registration.getEndpointUuid should equal(endpointUuid)

    val address = registration.getInputAddress
    this.endpoint1Address2 = Some(address)
  }

  test("original address is dead, can't re-register") {
    val session = this.session.get
    val endpointUuid = this.endpoint1.get.getUuid
    val frontEndClient = FrontEndService.client(session)
    val measClient = MeasurementService.client(session)

    val status = FrontEndStatusUpdate.newBuilder().setEndpointUuid(endpointUuid).setStatus(FrontEndConnectionStatus.Status.COMMS_UP).build()
    val updateFut = frontEndClient.putFrontEndConnectionStatuses(Seq(status), endpoint1Address1.get)

    intercept[TimeoutException] {
      Await.result(updateFut, 200.milliseconds)
    }

    val postTime = System.currentTimeMillis()
    val measFut = measClient.postMeasurements(analogBatch(this.pointAUuid.get, 1.85, postTime), endpoint1Address1.get)
    intercept[TimeoutException] {
      Await.result(measFut, 200.milliseconds)
    }

    val reg = FrontEndRegistrationTemplate.newBuilder()
      .setEndpointUuid(endpointUuid)
      .build()

    val regFut = frontEndClient.putFrontEndRegistration(reg, endpointUuid.getValue)

    intercept[LockedException] {
      Await.result(regFut, 5000.milliseconds)
    }
  }

  test("id'ed re-registration succeeds immediately") {
    val session = this.session.get
    val endpointUuid = this.endpoint1.get.getUuid

    // keep alive
    val postTime = System.currentTimeMillis()
    postAndGet(session, this.pointAUuid.get, analogBatch(this.pointAUuid.get, 1.2, postTime), this.endpoint1Address2.get)

    val frontEndClient = FrontEndService.client(session)

    val reg = FrontEndRegistrationTemplate.newBuilder()
      .setEndpointUuid(endpointUuid)
      .setFepNodeId("node2")
      .build()

    val regFut = frontEndClient.putFrontEndRegistration(reg, endpointUuid.getValue)

    val registration = Await.result(regFut, 5000.milliseconds)

    registration.getEndpointUuid should equal(endpointUuid)

    postAndGet(session, this.pointAUuid.get, analogBatch(this.pointAUuid.get, 1.25, postTime), registration.getInputAddress)
    this.endpoint1Address2 = Some(registration.getInputAddress)
  }

  test("second measproc") {
    val session = this.session.get
    val endpointUuid = this.endpoint1.get.getUuid
    val frontEndClient = FrontEndService.client(session)

    this.processor.get ! PoisonPill

    var i = 0
    var timedOut = false
    while (i < 10 && !timedOut) {

      val status = FrontEndStatusUpdate.newBuilder().setEndpointUuid(endpointUuid).setStatus(FrontEndConnectionStatus.Status.COMMS_UP).build()
      val updateFut = frontEndClient.putFrontEndConnectionStatuses(Seq(status), this.endpoint1Address2.get)

      try {
        Await.result(updateFut, 300.milliseconds)
        Thread.sleep(1000)
        i += 1
      } catch {
        case ex: TimeoutException =>
          timedOut = true
      }
    }

    timedOut should equal(true)

    val reg = FrontEndRegistrationTemplate.newBuilder()
      .setEndpointUuid(endpointUuid)
      .setFepNodeId("node2")
      .build()

    val registration = retryFutureUntilSuccess(3, 2000) {
      frontEndClient.putFrontEndRegistration(reg, endpointUuid.getValue)
    }

    this.endpoint1Address3 = Some(registration.getInputAddress)

    retryUntilSuccess(10, 500) {
      checkMarked(1.25, Quality.Validity.INVALID)
    }

    val postTime = System.currentTimeMillis()
    postAndGet(session, this.pointAUuid.get, analogBatch(this.pointAUuid.get, 1.11, postTime), endpoint1Address3.get)

  }
}

