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

import akka.actor.{ ActorRef, ActorSystem }
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.client.ServiceConnection
import io.greenbus.client.service._
import io.greenbus.client.service.proto.EventRequests.{ EventQuery, EventQueryParams }
import io.greenbus.client.service.proto.FrontEndRequests.FrontEndRegistrationTemplate
import io.greenbus.client.service.proto.MeasurementRequests.MeasurementBatchSubscriptionQuery
import io.greenbus.client.service.proto.Measurements._
import io.greenbus.client.service.proto.Model
import io.greenbus.client.service.proto.Model._
import io.greenbus.client.service.proto.ModelRequests.{ EntityEdgeDescriptor, EntityKeySet, EntityTemplate, _ }
import io.greenbus.client.service.proto.Processing.MeasOverride
import io.greenbus.measproc.MeasurementProcessor
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.services.{ CoreServices, ResetDatabase, ServiceManager }
import io.greenbus.util.UserSettings
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{ BeforeAndAfterAll, FunSuite }

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class ServiceAndProcessorTest extends FunSuite with ShouldMatchers with Logging with BeforeAndAfterAll {
  import IntegrationHelpers._
  import io.greenbus.integration.IntegrationConfig._
  import io.greenbus.integration.tools.PollingUtils._

  val testConfigPath = "io.greenbus.test.cfg"
  val system = ActorSystem("integrationTest")
  var services = Option.empty[ActorRef]
  var processor = Option.empty[ActorRef]
  var conn = Option.empty[ServiceConnection]
  var session = Option.empty[Session]

  var set1PointA = Option.empty[Point]
  var set1PointB = Option.empty[Point]
  var endpoint1 = Option.empty[Endpoint]
  var endpoint1Address = Option.empty[String]
  var endpoint2 = Option.empty[Endpoint]
  var endpoint2Address = Option.empty[String]
  var dynamicPoint = Option.empty[Point]

  val measQueue = new TypedEventQueue[MeasurementNotification]
  val batchQueue = new TypedEventQueue[MeasurementBatchNotification]

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
    processor = Some(system.actorOf(MeasurementProcessor.buildProcessor(testConfigPath, testConfigPath, testConfigPath, 10000, "testNode")))

    val eventClient = EventService.client(session)

    Await.result(eventClient.putEventConfigs(eventConfigs()), 5000.milliseconds)

    val modelClient = ModelService.client(session)

    val points = Await.result(modelClient.getPoints(EntityKeySet.newBuilder().addNames("Set1PointA").addNames("Set1PointB").build()), 5000.milliseconds)
    points.size should equal(2)
    val pointA = points.find(_.getName == "Set1PointA").get
    val pointB = points.find(_.getName == "Set1PointB").get
    this.set1PointA = Some(pointA)
    this.set1PointB = Some(pointB)

    val endpoints = Await.result(modelClient.endpointQuery(EndpointQuery.newBuilder().addProtocols("test").build()), 5000.milliseconds)

    endpoints.size should equal(1)
    val endpoint = endpoints.head

    this.endpoint1 = Some(endpoint)

    val measClient = MeasurementService.client(session)

    val measSubFut = measClient.getCurrentValuesAndSubscribe(Seq(pointA.getUuid, pointB.getUuid))
    val (_, measSub) = Await.result(measSubFut, 5000.milliseconds)
    measSub.start { not => measQueue.received(not) }

    val batchSubFut = measClient.subscribeToBatches(MeasurementBatchSubscriptionQuery.newBuilder().build())
    val (_, batchSub) = Await.result(batchSubFut, 5000.milliseconds)
    batchSub.start { not => /*println("BATCH: " + not);*/ batchQueue.received(not) }
  }

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination()
    this.conn.get.disconnect()
  }

  test("Simple meas publish") {
    val session = this.session.get

    val modelClient = ModelService.client(session)
    val frontEndClient = FrontEndService.client(session)

    val endpoint = endpoint1.get

    // let the measproc do its config, just a chance to avoid 2s wasted in the next step
    Thread.sleep(500)

    val reg = FrontEndRegistrationTemplate.newBuilder()
      .setEndpointUuid(endpoint.getUuid)
      .build()

    val registration = retryFutureUntilSuccess(3, 2000) {
      frontEndClient.putFrontEndRegistration(reg, endpoint.getUuid.getValue)
    }

    registration.getEndpointUuid should equal(endpoint.getUuid)

    val address = registration.getInputAddress
    this.endpoint1Address = Some(address)

    val measClient = MeasurementService.client(session)

    val original = Await.result(measClient.getCurrentValues(List(set1PointA.get.getUuid)), 5000.milliseconds)
    original.size should equal(0)

    val measEventFut = measQueue.listen(_.nonEmpty)
    val measBatchFut = batchQueue.listen(_.nonEmpty)

    val postTime = 5
    val posted = pollForSuccess(500, 5000) {
      Await.result(measClient.postMeasurements(analogBatch(set1PointA.get.getUuid, 1.45, postTime), address), 500.milliseconds)
    }

    posted should equal(true)

    val after = Await.result(measClient.getCurrentValues(List(set1PointA.get.getUuid)), 5000.milliseconds)
    after.size should equal(1)
    checkAnalog(after.head.getValue, 1.45, Some(postTime))

    val measEvents = Await.result(measEventFut, 5000.milliseconds)
    measEvents.size should equal(1)
    checkAnalog(after.head.getValue, 1.45, Some(postTime))

    val batchEvents = Await.result(measBatchFut, 5000.milliseconds)
    batchEvents.size should equal(1)
    batchEvents.head.getEndpointUuid should equal(endpoint.getUuid)
    batchEvents.head.getValuesList.size should equal(1)
    batchEvents.head.getValuesList.head.getPointUuid should equal(set1PointA.get.getUuid)
    batchEvents.head.getValuesList.head.getPointName should equal(set1PointA.get.getName)
    checkAnalog(batchEvents.head.getValuesList.head.getValue, 1.45, Some(postTime))
  }

  test("Post with nonexistent point") {
    val session = this.session.get
    val address = this.endpoint1Address.get
    val measClient = MeasurementService.client(session)

    def partialBatchTest(v: Double, time: Long, batch: MeasurementBatch): Unit = {
      val posted = pollForSuccess(500, 5000) {
        Await.result(measClient.postMeasurements(batch, address), 500.milliseconds)
      }

      posted should equal(true)

      val after = Await.result(measClient.getCurrentValues(List(set1PointA.get.getUuid)), 5000.milliseconds)
      after.size should equal(1)
      checkAnalog(after.head.getValue, v, Some(time))
    }

    val nameBatch = MeasurementBatch.newBuilder()
      .addNamedMeasurements(
        NamedMeasurementValue.newBuilder()
          .setPointName(set1PointA.get.getName)
          .setValue(analogMeas(1.59, 6))
          .build())
      .addNamedMeasurements(
        NamedMeasurementValue.newBuilder()
          .setPointName("erroneousPoint")
          .setValue(analogMeas(3.33, 6))
          .build())
      .build()

    partialBatchTest(1.59, 6, nameBatch)

    val postTime2 = 6
    val batch2 = MeasurementBatch.newBuilder()
      .addPointMeasurements(
        PointMeasurementValue.newBuilder()
          .setPointUuid(set1PointA.get.getUuid)
          .setValue(analogMeas(1.63, postTime2))
          .build())
      .addPointMeasurements(
        PointMeasurementValue.newBuilder()
          .setPointUuid(ModelUUID.newBuilder().setValue("erroneousUuid").build())
          .setValue(analogMeas(3.33, postTime2))
          .build())
      .build()

    partialBatchTest(1.63, postTime2, batch2)

  }

  test("Event triggered") {
    val session = this.session.get
    val address = this.endpoint1Address.get

    val modelClient = ModelService.client(session)
    val points = Await.result(modelClient.getPoints(EntityKeySet.newBuilder().addNames("Set1PointA").addNames("Set1PointB").build()), 5000.milliseconds)
    points.size should equal(2)
    val pointA = points.find(_.getName == "Set1PointA").get

    val postTime = 10
    val measClient = MeasurementService.client(session)
    val posted = Await.result(measClient.postMeasurements(analogBatch(pointA.getUuid, 2.5, postTime), address), 5000.milliseconds)

    posted should equal(true)

    val after = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
    after.size should equal(1)

    checkAnalog(after.head.getValue, 2.5, Some(postTime), Quality.Validity.QUESTIONABLE)

    val eventClient = EventService.client(session)

    val events = pollForSuccess(500, 5000) {
      val evs = Await.result(eventClient.eventQuery(EventQuery.newBuilder().setQueryParams(EventQueryParams.newBuilder.addEventType("OutOfRangeEvent")).setPageSize(100).build()), 5000.milliseconds)
      if (evs.nonEmpty) evs else throw new Exception("poll miss")
    }
    events.size should equal(1)
  }

  test("Endpoint added") {
    val session = this.session.get

    val modelClient = ModelService.client(session)
    val frontEndClient = FrontEndService.client(session)

    val endpointTemplate = EndpointTemplate.newBuilder()
      .setEntityTemplate(
        EntityTemplate.newBuilder()
          .setName("Set2Endpoint")
          .build())
      .setProtocol("test2")
      .build()

    val ends = Await.result(modelClient.putEndpoints(List(endpointTemplate)), 5000.milliseconds)
    ends.size should equal(1)
    val endpoint = ends.head

    // let the measproc do its config, just a chance to avoid 2s wasted in the next step
    Thread.sleep(500)

    val regRequest = FrontEndRegistrationTemplate.newBuilder().setEndpointUuid(endpoint.getUuid).build()

    val regResult = retryFutureUntilSuccess(3, 2000) {
      frontEndClient.putFrontEndRegistration(regRequest, endpoint.getUuid.getValue)
    }

    val set2Address = regResult.getInputAddress
    this.endpoint2 = Some(endpoint)
    this.endpoint2Address = Some(set2Address)

    val pointATemplate = PointTemplate.newBuilder()
      .setEntityTemplate(
        EntityTemplate.newBuilder()
          .setName("Set2PointA")
          .addTypes("Point"))
      .setPointCategory(Model.PointCategory.ANALOG)
      .setUnit("unit3")
      .build()

    val pointAResult = Await.result(modelClient.putPoints(List(pointATemplate)), 5000.milliseconds)

    pointAResult.size should equal(1)
    val pointA = pointAResult.head
    this.dynamicPoint = Some(pointA)

    val measClient = MeasurementService.client(session)
    val pointABatch = analogBatch(pointA.getUuid, 1.2, 3)

    logger.info("post meas")

    val postResult = pollForSuccess(500, 5000) {
      Await.result(measClient.postMeasurements(pointABatch, set2Address), 500.milliseconds)
    }
    postResult should equal(true)

    logger.info("get meas")
    val emptyMeasResult = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
    emptyMeasResult.size should equal(0)

    val edgeDesc = EntityEdgeDescriptor.newBuilder()
      .setParentUuid(endpoint.getUuid)
      .setChildUuid(pointA.getUuid)
      .setRelationship("source")
      .build()

    logger.info("put edges")
    val edgeResult = Await.result(modelClient.putEdges(List(edgeDesc)), 5000.milliseconds)
    edgeResult.size should equal(1)

    val measInDb = pollForSuccess(500, 5000) {
      val postResult2 = Await.result(measClient.postMeasurements(pointABatch, set2Address), 5000.milliseconds)
      postResult2 should equal(true)

      val shouldExistResult = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
      if (shouldExistResult.isEmpty) {
        throw new Exception("Still empty")
      } else {
        shouldExistResult
      }
    }

    checkAnalog(measInDb.head.getValue, 1.2, Some(3))

  }

  test("Trigger added") {
    val session = this.session.get
    val pointA = this.dynamicPoint.get
    val set2Address = this.endpoint2Address.get

    val auxTrigger = buildOffsetTrigger(0.33d)

    val keyValue = EntityKeyValue.newBuilder()
      .setUuid(pointA.getUuid)
      .setKey("triggerSet")
      .setValue(StoredValue.newBuilder()
        .setByteArrayValue(auxTrigger.toByteString))
      .build()

    val modelClient = ModelService.client(session)

    val setPutResult = Await.result(modelClient.putEntityKeyValues(Seq(keyValue)), 5000.milliseconds)
    setPutResult.size should equal(1)

    val unitWasSet = pollForSuccessWithIter(500, 5000) { i =>
      val m = postAndGet(session, pointA.getUuid, analogBatch(pointA.getUuid, 1.1, 9 + i), set2Address)
      if (m.getDoubleVal != 1.1) m else throw new Exception("poll miss")
    }

    checkAnalog(unitWasSet, 1.1 + 0.33, None)
  }

  test("Endpoint disable/enable") {
    val session = this.session.get
    val pointA = this.dynamicPoint.get
    val endpoint2 = this.endpoint2.get
    val set2Address = this.endpoint2Address.get

    val modelClient = ModelService.client(session)
    val frontEndClient = FrontEndService.client(session)
    val measClient = MeasurementService.client(session)

    val disabledResult = Await.result(modelClient.putEndpointDisabled(List(endUpdate(endpoint2.getUuid, true))), 5000.milliseconds)
    disabledResult.size should equal(1)

    pollForSuccessWithIter(500, 5000) { i =>
      val time = 15 + i
      try {
        Await.ready(measClient.postMeasurements(analogBatch(pointA.getUuid, 1.7, time), set2Address), 100.milliseconds)
        throw new Exception("poll miss")
      } catch {
        case ex: TimeoutException =>
      }
    }

    Await.result(modelClient.putEndpointDisabled(List(endUpdate(endpoint2.getUuid, false))), 5000.milliseconds).size should equal(1)

    // let the measproc do its config, just a chance to avoid 2s wasted in the next step
    Thread.sleep(500)

    val regRequest = FrontEndRegistrationTemplate.newBuilder().setEndpointUuid(endpoint2.getUuid).build()

    val regResult = retryFutureUntilSuccess(3, 2000) {
      frontEndClient.putFrontEndRegistration(regRequest, endpoint2.getUuid.getValue)
    }

    val nextEndpoint2Address = regResult.getInputAddress
    this.endpoint2Address = Some(nextEndpoint2Address)

    pollForSuccessWithIter(500, 5000) { i =>
      val time = 18 + i
      val m = postAndGet(session, pointA.getUuid, analogBatch(pointA.getUuid, 1.236, time), nextEndpoint2Address, 500)
      if (m.getTime == time) m else throw new Exception("poll miss")
    }
  }

  private var measTime = 20
  def nextMeasTime(): Int = {
    val r = measTime
    measTime += 1
    r
  }

  test("Override Added") {
    val session = this.session.get
    val pointA = this.dynamicPoint.get
    val set2Address = this.endpoint2Address.get

    val modelClient = ModelService.client(session)
    val processingClient = ProcessingService.client(session)
    val measClient = MeasurementService.client(session)

    // Set up new value suppression
    val auxTrigger = buildFilterTrigger()

    val keyValue = EntityKeyValue.newBuilder()
      .setUuid(pointA.getUuid)
      .setKey("triggerSet")
      .setValue(StoredValue.newBuilder()
        .setByteArrayValue(auxTrigger.toByteString))
      .build()

    val setPutResult = Await.result(modelClient.putEntityKeyValues(Seq(keyValue)), 5000.milliseconds)
    setPutResult.size should equal(1)

    val offsetUsed = pollForSuccessWithIter(500, 5000) { i =>
      val t = nextMeasTime()
      val m = postAndGet(session, pointA.getUuid, analogBatch(pointA.getUuid, 20.56, t), set2Address)
      if (m.getTime != t) m else throw new Exception("poll miss")
    }

    val startMeas = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
    val startValue = startMeas.head.getValue.getDoubleVal

    val measOver = MeasOverride.newBuilder()
      .setPointUuid(pointA.getUuid)
      .build()

    val overResult = Await.result(processingClient.putOverrides(List(measOver)), 5000.milliseconds)
    overResult.size should equal(1)

    val nisMeas = pollForSuccess(500, 5000) {
      val result = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
      result.size should equal(1)
      val m = result.head.getValue
      val isBlocked = m.getQuality.hasOperatorBlocked && m.getQuality.getOperatorBlocked
      if (isBlocked) m else throw new Exception("poll miss")
    }

    nisMeas.getType should equal(Measurement.Type.DOUBLE)
    nisMeas.getDoubleVal should equal(startValue)
    (nisMeas.getQuality.hasOperatorBlocked && nisMeas.getQuality.getOperatorBlocked) should equal(true)
    (nisMeas.getQuality.getDetailQual.hasOldData && nisMeas.getQuality.getDetailQual.getOldData) should equal(true)

    val postNisResult = Await.result(measClient.postMeasurements(analogBatch(pointA.getUuid, 1.9, nextMeasTime()), set2Address), 5000.milliseconds)
    postNisResult should equal(true)

    val stillNisResult = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
    stillNisResult.size should equal(1)

    val stillNisMeas = stillNisResult.head.getValue
    stillNisMeas.getType should equal(Measurement.Type.DOUBLE)
    stillNisMeas.getDoubleVal should equal(startValue)
    (stillNisMeas.getQuality.hasOperatorBlocked && stillNisMeas.getQuality.getOperatorBlocked) should equal(true)
    (stillNisMeas.getQuality.getDetailQual.hasOldData && stillNisMeas.getQuality.getDetailQual.getOldData) should equal(true)

    val delResult = Await.result(processingClient.deleteOverrides(List(pointA.getUuid)), 5000.milliseconds)
    delResult.size should equal(1)

    val afterNisMeas = pollForSuccess(500, 5000) {
      val result = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
      result.size should equal(1)
      val m = result.head.getValue
      println(m)
      val notBlocked = !(m.getQuality.hasOperatorBlocked && m.getQuality.getOperatorBlocked)
      if (notBlocked) m else throw new Exception("poll miss")
    }

    afterNisMeas.getType should equal(Measurement.Type.DOUBLE)
    afterNisMeas.getDoubleVal should equal(1.9)
  }

  test("Override add/remove with identical update inbetween") {
    val session = this.session.get
    val pointA = this.dynamicPoint.get
    val set2Address = this.endpoint2Address.get

    val processingClient = ProcessingService.client(session)
    val measClient = MeasurementService.client(session)

    val initialValue = Await.result(measClient.postMeasurements(analogBatch(pointA.getUuid, 2.35, nextMeasTime()), set2Address), 5000.milliseconds)
    initialValue should equal(true)

    val startMeas = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
    println("startMeas: " + startMeas)
    val startValue = startMeas.head.getValue.getDoubleVal

    val overVal = 1.11

    val measOver = MeasOverride.newBuilder()
      .setPointUuid(pointA.getUuid)
      .setMeasurement(Measurement.newBuilder()
        .setDoubleVal(overVal)
        .setType(Measurement.Type.DOUBLE)
        .setTime(nextMeasTime()))
      .build()

    val overResult = Await.result(processingClient.putOverrides(List(measOver)), 5000.milliseconds)
    overResult.size should equal(1)

    val overMeas = pollForSuccess(500, 5000) {
      val result = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
      result.size should equal(1)
      val m = result.head.getValue
      val isBlocked = m.getQuality.hasOperatorBlocked && m.getQuality.getOperatorBlocked
      if (isBlocked) m else throw new Exception("poll miss")
    }

    overMeas.getType should equal(Measurement.Type.DOUBLE)
    overMeas.getDoubleVal should equal(overVal)
    (overMeas.getQuality.hasOperatorBlocked && overMeas.getQuality.getOperatorBlocked) should equal(true)
    (overMeas.getQuality.hasSource && overMeas.getQuality.getSource == Quality.Source.SUBSTITUTED) should equal(true)

    val postNisResult = Await.result(measClient.postMeasurements(analogBatch(pointA.getUuid, 2.35, nextMeasTime()), set2Address), 5000.milliseconds)
    postNisResult should equal(true)

    val stillOverResult = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
    stillOverResult.size should equal(1)

    val stillOverMeas = stillOverResult.head.getValue
    stillOverMeas.getType should equal(Measurement.Type.DOUBLE)
    stillOverMeas.getDoubleVal should equal(overVal)
    (stillOverMeas.getQuality.hasOperatorBlocked && stillOverMeas.getQuality.getOperatorBlocked) should equal(true)
    (stillOverMeas.getQuality.hasSource && stillOverMeas.getQuality.getSource == Quality.Source.SUBSTITUTED) should equal(true)

    val delResult = Await.result(processingClient.deleteOverrides(List(pointA.getUuid)), 5000.milliseconds)
    delResult.size should equal(1)

    val afterRemoveMeas = pollForSuccess(500, 5000) {
      val result = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
      result.size should equal(1)
      val m = result.head.getValue
      val notBlocked = !(m.getQuality.hasOperatorBlocked && m.getQuality.getOperatorBlocked)
      if (notBlocked) m else throw new Exception("poll miss")
    }

    afterRemoveMeas.getType should equal(Measurement.Type.DOUBLE)
    afterRemoveMeas.getDoubleVal should equal(2.35)
  }

  test("un-nissing same measurement doesn't run transform twice") {
    val session = this.session.get
    val pointA = this.dynamicPoint.get
    val set2Address = this.endpoint2Address.get

    val modelClient = ModelService.client(session)
    val measClient = MeasurementService.client(session)
    val processingClient = ProcessingService.client(session)

    val auxTrigger = buildUnconditionalOffsetTrigger(0.01d)

    val keyValue = EntityKeyValue.newBuilder()
      .setUuid(pointA.getUuid)
      .setKey("triggerSet")
      .setValue(StoredValue.newBuilder()
        .setByteArrayValue(auxTrigger.toByteString))
      .build()

    val setPutResult = Await.result(modelClient.putEntityKeyValues(Seq(keyValue)), 5000.milliseconds)
    setPutResult.size should equal(1)

    val offsetUsed = pollForSuccessWithIter(500, 5000) { i =>
      val m = postAndGet(session, pointA.getUuid, analogBatch(pointA.getUuid, 1.55, nextMeasTime()), set2Address)
      if (m.getDoubleVal == 1.56) m else throw new Exception("poll miss")
    }

    val measOver = MeasOverride.newBuilder()
      .setPointUuid(pointA.getUuid)
      .build()

    val overResult = Await.result(processingClient.putOverrides(List(measOver)), 5000.milliseconds)
    overResult.size should equal(1)

    val nisMeas = pollForSuccess(500, 5000) {
      val result = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
      result.size should equal(1)
      val m = result.head.getValue
      val isBlocked = m.getQuality.hasOperatorBlocked && m.getQuality.getOperatorBlocked
      if (isBlocked) m else throw new Exception("poll miss")
    }

    nisMeas.getType should equal(Measurement.Type.DOUBLE)
    nisMeas.getDoubleVal should equal(1.56)
    (nisMeas.getQuality.hasOperatorBlocked && nisMeas.getQuality.getOperatorBlocked) should equal(true)
    (nisMeas.getQuality.getDetailQual.hasOldData && nisMeas.getQuality.getDetailQual.getOldData) should equal(true)

    val delResult = Await.result(processingClient.deleteOverrides(List(pointA.getUuid)), 5000.milliseconds)
    delResult.size should equal(1)

    val afterNisMeas = pollForSuccess(500, 5000) {
      val result = Await.result(measClient.getCurrentValues(List(pointA.getUuid)), 5000.milliseconds)
      result.size should equal(1)
      val m = result.head.getValue
      val notBlocked = !(m.getQuality.hasOperatorBlocked && m.getQuality.getOperatorBlocked)
      if (notBlocked) m else throw new Exception("poll miss")
    }

    afterNisMeas.getType should equal(Measurement.Type.DOUBLE)
    afterNisMeas.getDoubleVal should equal(1.56)
  }

  test("Swap point between endpoints") {
    val session = this.session.get
    val pointA = this.dynamicPoint.get
    val endpoint1 = this.endpoint1.get
    val end1address = this.endpoint1Address.get
    val endpoint2 = this.endpoint2.get
    val end2address = this.endpoint2Address.get

    val modelClient = ModelService.client(session)
    val end2Edge = EntityEdgeDescriptor.newBuilder()
      .setParentUuid(endpoint2.getUuid)
      .setChildUuid(pointA.getUuid)
      .setRelationship("source")
      .build()

    logger.info("put edges")
    val edgeResult = Await.result(modelClient.deleteEdges(List(end2Edge)), 5000.milliseconds)
    edgeResult.size should equal(1)

    pollForSuccessWithIter(500, 5000) { i =>
      val t = nextMeasTime()
      val m = postAndGet(session, pointA.getUuid, analogBatch(pointA.getUuid, 1.7, t), end2address)
      if (m.getTime != t) m else throw new Exception("poll miss")
    }

    val end1Edge = EntityEdgeDescriptor.newBuilder()
      .setParentUuid(endpoint1.getUuid)
      .setChildUuid(pointA.getUuid)
      .setRelationship("source")
      .build()

    val end1Result = Await.result(modelClient.putEdges(List(end1Edge)), 5000.milliseconds)
    end1Result.size should equal(1)

    pollForSuccessWithIter(500, 5000) { i =>
      val t = nextMeasTime()
      val m = postAndGet(session, pointA.getUuid, analogBatch(pointA.getUuid, 1.74, t), end1address)
      if (m.getTime == t) m else throw new Exception("poll miss")
    }

    val unitTrigger = buildOffsetTrigger(0.66d)
    val keyValue = EntityKeyValue.newBuilder()
      .setUuid(pointA.getUuid)
      .setKey("triggerSet")
      .setValue(StoredValue.newBuilder()
        .setByteArrayValue(unitTrigger.toByteString))
      .build()
    val triggerResult = Await.result(modelClient.putEntityKeyValues(List(keyValue)), 5000.milliseconds)
    triggerResult.size should equal(1)

    pollForSuccessWithIter(500, 5000) { i =>
      val t = nextMeasTime()
      val m = postAndGet(session, pointA.getUuid, analogBatch(pointA.getUuid, 1.05, t), end1address)
      if (m.getDoubleVal == (1.05 + 0.66)) m else throw new Exception("poll miss")
    }
  }
}

