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

import java.util.UUID
import java.util.concurrent.TimeoutException

import io.greenbus.client.proto.Envelope
import io.greenbus.client.service.proto.Events.Event
import io.greenbus.client.service.proto.LoginRequests.{ LoginRequest, PostLoginRequest, PostLoginResponse }
import io.greenbus.jmx.MetricsManager
import io.greenbus.msg.{ SubscriptionBinding, Subscription }
import io.greenbus.msg.amqp.{ AmqpAddressedMessage, AmqpServiceOperations }
import io.greenbus.msg.service.{ ServiceHandler, ServiceHandlerSubscription }
import io.greenbus.services.authz.{ AuthLookup, DefaultAuthLookup }
import io.greenbus.services.data.ServicesSchema
import io.greenbus.services.framework._
import io.greenbus.services.model.ModelTestHelpers.TestModelNotifier
import io.greenbus.services.model.{ ModelTestHelpers, EventSeeding, SquerylAuthModel, SquerylEventAlarmModel }
import io.greenbus.services.{ AsyncAuthenticationModule, AuthenticationModule, SqlAuthenticationModule }
import io.greenbus.sql.test.DatabaseUsingTestBaseNoTransaction
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.squeryl.PrimitiveTypeMode._

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, promise }

@RunWith(classOf[JUnitRunner])
class LoginTests extends DatabaseUsingTestBaseNoTransaction {
  def schemas = List(ServicesSchema)

  override protected def resetDbAfterTestSuite: Boolean = true

  override def beforeAll(): Unit = super.beforeAll()

  class TestSyncAuthModule extends SqlAuthenticationModule {

    var result: (Boolean, Option[UUID]) = (false, None)
    var attemptOpt = Option.empty[(String, String)]

    def authenticate(name: String, password: String): (Boolean, Option[UUID]) = {
      attemptOpt = Some((name, password))
      result
    }
  }

  class TestAsyncAuthModule extends AsyncAuthenticationModule {
    var attemptOpt = Option.empty[(String, String)]
    var result: Future[Boolean] = Future.failed(new TimeoutException("timed out"))
    def authenticate(name: String, password: String): Future[Boolean] = {
      attemptOpt = Some((name, password))
      result
    }
  }

  def request(name: String = "name01", pass: String = "pass01"): PostLoginRequest = {
    PostLoginRequest.newBuilder()
      .setRequest(
        LoginRequest.newBuilder()
          .setName(name)
          .setPassword(pass)
          .setClientVersion("vers01")
          .setExpirationTime(System.currentTimeMillis() + 30000)
          .setLoginLocation("loc01")
          .build())
      .build()
  }

  val fakeServiceOps = new AmqpServiceOperations {
    override def publishEvent(exchange: String, msg: Array[Byte], key: String): Unit = ???

    override def bindRoutedService(handler: ServiceHandler): SubscriptionBinding = ???

    override def competingServiceBinding(exchange: String): ServiceHandlerSubscription = ???

    override def declareExchange(exchange: String): Unit = ???

    override def bindQueue(queue: String, exchange: String, key: String): Unit = ???

    override def bindCompetingService(handler: ServiceHandler, exchange: String): SubscriptionBinding = ???

    override def simpleSubscription(): Subscription[Array[Byte]] = ???

    override def publishBatch(messages: Seq[AmqpAddressedMessage]): Unit = ???

    override def routedServiceBinding(): ServiceHandlerSubscription = ???
  }

  class TestRig(val testAuthModule: AuthenticationModule) {

    val authLookup: AuthLookup = DefaultAuthLookup
    val eventMapper = new ModelEventMapper
    val metricsMgr = MetricsManager("io.greenbus.services")

    val registry = new FullServiceRegistry(dbConnection, fakeServiceOps, authLookup, eventMapper, metricsMgr)
    val notifier = new TestModelNotifier

    val services = new LoginServices(registry, dbConnection, testAuthModule, SquerylAuthModel, SquerylEventAlarmModel, notifier)

    //var prom = promise[Response[PostLoginResponse]]

    var respOpt = Option.empty[Response[PostLoginResponse]]
    def callback(resp: Response[PostLoginResponse]): Unit = {
      respOpt = Some(resp)
    }
  }

  class AsyncTestRig {
    val testAuthModule = new TestAsyncAuthModule
    val authLookup: AuthLookup = DefaultAuthLookup
    val eventMapper = new ModelEventMapper
    val metricsMgr = MetricsManager("io.greenbus.services")

    val registry = new FullServiceRegistry(dbConnection, fakeServiceOps, authLookup, eventMapper, metricsMgr)
    val notifier = new TestModelNotifier

    val services = new LoginServices(registry, dbConnection, testAuthModule, SquerylAuthModel, SquerylEventAlarmModel, notifier)

    val prom = promise[Response[PostLoginResponse]]

    def callback(resp: Response[PostLoginResponse]): Unit = {
      prom.complete(scala.util.Success(resp))
    }
  }

  test("sync failure") {
    val req = request()
    val testAuthModule = new TestSyncAuthModule
    val r = new TestRig(testAuthModule)
    import r._

    services.loginAsync(req, Map(), callback)

    testAuthModule.attemptOpt should equal(Some(("name01", "pass01")))

    respOpt match {
      case Some(Failure(Envelope.Status.UNAUTHORIZED, _)) =>
      case _ => fail()
    }

    val events = notifier.getQueue(classOf[Event])
    events.size should equal(1)
    events.head._2.getEventType should equal(EventSeeding.System.userLoginFailure.eventType)
  }

  test("sync success") {
    val req = request()
    val testAuthModule = new TestSyncAuthModule
    val r = new TestRig(testAuthModule)
    import r._

    val uuid = UUID.randomUUID()
    testAuthModule.result = (true, Some(uuid))

    services.loginAsync(req, Map(), callback)

    testAuthModule.attemptOpt should equal(Some(("name01", "pass01")))

    respOpt match {
      case Some(Success(Envelope.Status.OK, resp)) =>
        resp.getResponse.hasToken should equal(true)
      case _ => fail()
    }

    val all = dbConnection.transaction {
      ServicesSchema.authTokens.where(t => true === true).toVector
    }
    all.size should equal(1)
    all.head.agentId should equal(uuid)
    all.head.loginLocation should equal(req.getRequest.getLoginLocation)

    val events = notifier.getQueue(classOf[Event])
    events.size should equal(1)
    events.head._2.getEventType should equal(EventSeeding.System.userLogin.eventType)
  }

  test("async failure timeout") {
    val req = request()
    val r = new AsyncTestRig
    import r._

    services.loginAsync(req, Map(), callback)

    testAuthModule.attemptOpt should equal(Some(("name01", "pass01")))
    val fut = prom.future

    Await.result(fut, 5000.milliseconds) match {
      case Failure(Envelope.Status.RESPONSE_TIMEOUT, _) =>
      case _ => fail()
    }

    val events = notifier.getQueue(classOf[Event])
    events.size should equal(1)
    events.head._2.getEventType should equal(EventSeeding.System.userLoginFailure.eventType)
  }

  test("async rando error") {
    val req = request()
    val r = new AsyncTestRig
    import r._

    testAuthModule.result = Future.failed(new Exception("some random exception"))

    services.loginAsync(req, Map(), callback)

    testAuthModule.attemptOpt should equal(Some(("name01", "pass01")))
    val fut = prom.future

    Await.result(fut, 5000.milliseconds) match {
      case Failure(Envelope.Status.UNAUTHORIZED, _) =>
      case _ => fail()
    }

    val events = notifier.getQueue(classOf[Event])
    events.size should equal(1)
    events.head._2.getEventType should equal(EventSeeding.System.userLoginFailure.eventType)
  }

  test("async negative response") {
    val req = request()
    val r = new AsyncTestRig
    import r._

    testAuthModule.result = Future.successful(false)

    services.loginAsync(req, Map(), callback)

    testAuthModule.attemptOpt should equal(Some(("name01", "pass01")))
    val fut = prom.future

    Await.result(fut, 5000.milliseconds) match {
      case Failure(Envelope.Status.UNAUTHORIZED, _) =>
      case _ => fail()
    }

    val events = notifier.getQueue(classOf[Event])
    events.size should equal(1)
    events.head._2.getEventType should equal(EventSeeding.System.userLoginFailure.eventType)
  }

  test("async positive response, no agent") {
    val req = request()
    val r = new AsyncTestRig
    import r._

    testAuthModule.result = Future.successful(true)

    services.loginAsync(req, Map(), callback)

    testAuthModule.attemptOpt should equal(Some(("name01", "pass01")))
    val fut = prom.future

    Await.result(fut, 5000.milliseconds) match {
      case Failure(Envelope.Status.UNAUTHORIZED, _) =>
      case _ => fail()
    }

    val events = notifier.getQueue(classOf[Event])
    events.size should equal(1)
    events.head._2.getEventType should equal(EventSeeding.System.userLoginFailure.eventType)
  }

  test("async positive response, agent exists") {
    val req = request(name = "existingAgent", pass = "pass02")
    val r = new AsyncTestRig
    import r._

    dbConnection.transaction {
      ModelTestHelpers.createAgent("existingAgent", "pass02")
    }

    testAuthModule.result = Future.successful(true)

    services.loginAsync(req, Map(), callback)

    testAuthModule.attemptOpt should equal(Some(("existingAgent", "pass02")))
    val fut = prom.future

    Await.result(fut, 5000.milliseconds) match {
      case Success(Envelope.Status.OK, resp) =>
        resp.getResponse.hasToken should equal(true)
      case _ => fail()
    }

    val events = notifier.getQueue(classOf[Event])
    events.size should equal(1)
    events.head._2.getEventType should equal(EventSeeding.System.userLogin.eventType)
  }
}
