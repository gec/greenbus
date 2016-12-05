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

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.{ BeforeAndAfterEach, FunSuite }
import io.greenbus.msg.amqp.{ AmqpAddressedMessage, AmqpServiceOperations }
import io.greenbus.msg.service.{ ServiceHandler, ServiceHandlerSubscription }
import io.greenbus.msg.{ Session, SessionUnusableException, Subscription, SubscriptionBinding }
import io.greenbus.app.actor.frontend.CommandProxy.{ CommandSubscriptionRetrieved, CommandsUpdated, ProtocolInitialized, ProtocolUninitialized }
import io.greenbus.app.actor.frontend.EventActor.SubCanceled
import io.greenbus.app.actor.frontend.FrontendProtocolEndpoint.Connected
import io.greenbus.app.actor.frontend.ProcessingProxy.{ LinkUp, PointMapUpdated }
import io.greenbus.client.exception.{ BadRequestException, LockedException, UnauthorizedException }
import io.greenbus.client.service.proto.Commands.{ CommandRequest, CommandResult }
import io.greenbus.client.service.proto.FrontEnd.{ FrontEndConnectionStatus, FrontEndRegistration }
import io.greenbus.client.service.proto.Model._

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise, promise }

case class MockSession(id: String) extends Session {
  def headers: Map[String, String] = ???

  def addHeader(key: String, value: String): Unit = ???

  def removeHeader(key: String, value: String): Unit = ???

  def spawn(): Session = ???

  def clearHeaders(): Unit = ???

  def addHeaders(headers: Seq[(String, String)]): Unit = ???

  def subscribe(requestId: String, headers: Map[String, String], destination: Option[String], payload: Array[Byte]): Future[(Array[Byte], Subscription[Array[Byte]])] = ???

  def request(requestId: String, headers: Map[String, String], destination: Option[String], payload: Array[Byte]): Future[Array[Byte]] = ???
}

case class MockServiceOps(id: String) extends AmqpServiceOperations {
  override def publishEvent(exchange: String, msg: Array[Byte], key: String): Unit = ???

  override def publishBatch(messages: Seq[AmqpAddressedMessage]): Unit = ???

  override def bindRoutedService(handler: ServiceHandler): SubscriptionBinding = ???

  override def competingServiceBinding(exchange: String): ServiceHandlerSubscription = ???

  override def declareExchange(exchange: String): Unit = ???

  override def bindQueue(queue: String, exchange: String, key: String): Unit = ???

  override def bindCompetingService(handler: ServiceHandler, exchange: String): SubscriptionBinding = ???

  override def simpleSubscription(): Subscription[Array[Byte]] = ???

  override def routedServiceBinding(): ServiceHandlerSubscription = ???
}

case class MockServiceHandlerSubscription(id: String, obs: ActorRef) extends ServiceHandlerSubscription {
  override def start(handler: ServiceHandler): Unit = ???

  override def cancel(): Unit = obs ! SubCanceled(id)

  override def getId(): String = ???
}

case class MockSub[A](id: String) extends Subscription[A] {
  override def start(handler: (A) => Unit): Unit = ???

  override def cancel(): Unit = ???

  override def getId(): String = ???
}

case class FakeConfig(id: String)

class EventQueue {
  val queue = new AtomicReference(Seq.empty[Any])
  private val chk = new AtomicReference(Option.empty[(Promise[Boolean], Seq[Any] => Boolean)])

  def received(obj: Any): Unit = {
    var worked = false
    var appended = Seq.empty[Any]
    while (!worked) {
      val prev = queue.get()
      appended = prev ++ Vector(obj)
      worked = queue.compareAndSet(prev, appended)
    }
    chk.get().foreach {
      case (prom, checkFun) => if (checkFun(appended)) prom.success(true)
    }
  }

  def listen(check: Seq[Any] => Boolean): Future[Boolean] = {
    val prom = promise[Boolean]
    chk.set(Some((prom, check)))

    if (check(queue.get())) prom.success(true)

    prom.future
  }
}

object EventActor {

  case class ProtAdded(end: String, config: FakeConfig)
  case class ProtRemoved(end: String)
  case object ProtShutdown

  case class ConfigEvaluate(end: String)

  case object ConfigStarted
  case object ConfigStopped

  case class CmdIssued(cmdName: String, request: String)

  case class RegCalled(end: String, holdingLock: Boolean)
  case class ConfigCalled(end: String)

  case class SubCanceled(id: String)

  def props(queue: EventQueue): Props = Props(classOf[EventActor], queue)
}
class EventActor(queue: EventQueue) extends Actor {

  def receive = {
    case obj => queue.received(obj)
  }
}

import io.greenbus.app.actor.frontend.EventActor._

class ProtocolInterfaces(observer: ActorRef) extends MasterProtocol[FakeConfig] with ProtocolConfigurer[FakeConfig] with ProtocolCommandAcceptor {

  val measPublish = new AtomicReference(Option.empty[(MeasurementsPublished) => Unit])
  val statusUpdate = new AtomicReference(Option.empty[(StackStatusUpdated) => Unit])

  val config = new AtomicReference(Option.empty[FakeConfig])

  val cmdResult = new AtomicReference(Option.empty[CommandResult])

  val regResult = new AtomicReference(Option.empty[(Future[FrontEndRegistration], ServiceHandlerSubscription)])
  val cfgResult = new AtomicReference(Option.empty[Future[FrontendConfiguration]])

  def add(endpoint: Endpoint, protocolConfig: FakeConfig, publish: (MeasurementsPublished) => Unit, status: (StackStatusUpdated) => Unit): ProtocolCommandAcceptor = {
    measPublish.set(Some(publish))
    statusUpdate.set(Some(status))
    observer ! ProtAdded(endpoint.getUuid.getValue, protocolConfig)
    this
  }

  def remove(endpointUuid: ModelUUID): Unit = {
    observer ! ProtRemoved(endpointUuid.getValue)
  }

  def shutdown(): Unit = {
    observer ! ProtShutdown
  }

  def evaluate(endpoint: Endpoint, configFiles: Seq[EntityKeyValue]): Option[FakeConfig] = {
    observer ! ConfigEvaluate(endpoint.getUuid.getValue)
    config.get()
  }

  def equivalent(latest: FakeConfig, previous: FakeConfig): Boolean = {
    false
  }

  def issue(commandName: String, request: CommandRequest): Future[CommandResult] = {
    observer ! CmdIssued(commandName, request.getCommandUuid.getValue)
    Future.successful(cmdResult.get().get)
  }

  def register(session: Session, serviceOps: AmqpServiceOperations, endpoint: Endpoint, holdingLock: Boolean): (Future[FrontEndRegistration], ServiceHandlerSubscription) = {
    observer ! RegCalled(endpoint.getUuid.getValue, holdingLock)
    regResult.get().get
  }

  def configure(session: Session, endpoint: Endpoint): Future[FrontendConfiguration] = {
    observer ! ConfigCalled(endpoint.getUuid.getValue)
    cfgResult.get().get
  }
}

object ConfigUpdater {
  def props(test: ActorRef): Props = Props(classOf[ConfigUpdater], test)
}
class ConfigUpdater(test: ActorRef) extends Actor {

  test ! ConfigStarted

  def receive = {
    case x => test ! x
  }

  override def postStop(): Unit = {
    test ! ConfigStopped
  }
}

object EventForwarder {
  def props(test: ActorRef): Props = Props(classOf[EventForwarder], test)
}
class EventForwarder(test: ActorRef) extends Actor {

  def receive = {
    case x => test ! x
  }
}

class FepTestRig(system: ActorSystem) {

  val events = new EventQueue
  val eventActor = system.actorOf(EventActor.props(events))

  val mock = new ProtocolInterfaces(eventActor)

  def configFactory(ref: ActorRef, endpoint: Endpoint, session: Session, config: FrontendConfiguration) = {
    ConfigUpdater.props(eventActor)
  }

  def proxyFactory(ref: ActorRef, endpoint: Endpoint) = {
    EventForwarder.props(eventActor)
  }

  def cmdFactory(endpoint: Endpoint) = {
    EventForwarder.props(eventActor)
  }

  val uuid = "uuid01"
  val endpoint = Endpoint.newBuilder()
    .setUuid(ModelUUID.newBuilder().setValue(uuid).build())
    .setName("end01")
    .addTypes("Endpoint")
    .setDisabled(false)
    .setProtocol("protocol01")
    .build()

  val fep = system.actorOf(FrontendProtocolEndpoint.props(endpoint, eventActor, mock, mock, 50, 500, proxyFactory, cmdFactory, configFactory, mock.register, mock.configure))

  import io.greenbus.app.actor.frontend.FrontendProtocolEndpointTest._
  def checkFut(compare: Seq[SubSeq]): Future[Boolean] = events.listen { q => /*println(q);*/ checkSeq(q, compare) }
}

object FrontendProtocolEndpointTest {

  sealed trait SubSeq
  case class Ordered(values: Seq[Any]) extends SubSeq
  case class Unordered(values: Seq[Any]) extends SubSeq

  @tailrec
  def unorderedEq(a: Seq[Any], b: Seq[Any]): Boolean = {
    if (a.size != b.size) {
      false
    } else if (a.isEmpty) {
      true
    } else {
      val (c, d) = b.span(_ != a.head)
      if (d.isEmpty) {
        false
      } else {
        unorderedEq(a.drop(1), c ++ d.drop(1))
      }
    }
  }

  @tailrec
  def checkSeq(results: Seq[Any], compare: Seq[SubSeq]): Boolean = {
    compare match {
      case Seq() => results.isEmpty
      case cmp =>
        cmp.head match {
          case Ordered(values) =>
            results.size >= values.size &&
              results.take(values.size) == values &&
              checkSeq(results.drop(values.size), cmp.drop(1))
          case Unordered(values) =>
            results.size >= values.size &&
              unorderedEq(results.take(values.size), values) &&
              checkSeq(results.drop(values.size), cmp.drop(1))
        }
    }
  }

  def registration(uuid: String, input: String): FrontEndRegistration = FrontEndRegistration.newBuilder().setEndpointUuid(ModelUUID.newBuilder().setValue(uuid).build()).setInputAddress(input).build()
}

@RunWith(classOf[JUnitRunner])
class FrontendProtocolEndpointTest extends FunSuite with Matchers with BeforeAndAfterEach {
  import io.greenbus.app.actor.frontend.FrontendProtocolEndpointTest._

  private var as = Option.empty[ActorSystem]

  override protected def beforeEach(): Unit = {
    as = Some(ActorSystem("test"))
  }

  override protected def afterEach(): Unit = {
    as.get.shutdown()
  }

  def comeUpSequence(r: FepTestRig, handlerId: String, sessId: String, input: String, holdingLock: Boolean = false, unorderedPrefix: Seq[Any] = Seq.empty[Any]): Unit = {
    import r._

    events.queue.set(Seq())
    mock.regResult.set(Some((Future.successful(registration(uuid, input)), MockServiceHandlerSubscription(handlerId, eventActor))))
    mock.cfgResult.set(Some(Future.successful(FrontendConfiguration(Seq(), Seq(), Seq(), MockSub[EntityEdgeNotification]("modelEdgeSub01"), MockSub[EntityKeyValueNotification]("configSub01")))))

    mock.config.set(Some(FakeConfig("config01")))

    val comingUp = Seq(
      Unordered(unorderedPrefix ++ Seq(
        RegCalled(uuid, holdingLock))),
      Unordered(Seq(
        ConfigCalled(uuid),
        ConfigStarted,
        LinkUp(MockSession(sessId), input),
        CommandSubscriptionRetrieved(MockServiceHandlerSubscription(handlerId, eventActor)),
        PointMapUpdated(Map()),
        CommandsUpdated(List()),
        ConfigEvaluate(uuid),
        ProtAdded(uuid, FakeConfig("config01")),
        ProtocolInitialized(mock))))

    fep ! Connected(MockSession(sessId), MockServiceOps("ops01"))

    Await.result(checkFut(comingUp), 5000.milliseconds) should equal(true)
  }

  def serviceComeUpProtocolUp(r: FepTestRig, handlerId: String, sessId: String, input: String, holdingLock: Boolean = false): Unit = {
    import r._

    events.queue.set(Seq())
    mock.regResult.set(Some((Future.successful(registration(uuid, input)), MockServiceHandlerSubscription(handlerId, eventActor))))
    mock.cfgResult.set(Some(Future.successful(FrontendConfiguration(Seq(), Seq(), Seq(), MockSub[EntityEdgeNotification]("modelEdgeSub01"), MockSub[EntityKeyValueNotification]("configSub01")))))

    mock.config.set(Some(FakeConfig("config01")))

    val comingUp = Seq(
      Ordered(Seq(
        RegCalled(uuid, holdingLock))),
      Unordered(Seq(
        ConfigCalled(uuid),
        ConfigStarted,
        LinkUp(MockSession(sessId), input),
        CommandSubscriptionRetrieved(MockServiceHandlerSubscription(handlerId, eventActor)),
        PointMapUpdated(Map()),
        CommandsUpdated(List()))))

    fep ! Connected(MockSession(sessId), MockServiceOps("ops01"))

    Await.result(checkFut(comingUp), 5000.milliseconds) should equal(true)
  }

  test("coming up process") {
    val r = new FepTestRig(as.get)
    comeUpSequence(r, "handler01", "sess01", "input01")
  }

  def regFailureTest(exFun: => Throwable) = {
    val r = new FepTestRig(as.get)
    import r._

    mock.regResult.set(Some((Future.failed(exFun), MockServiceHandlerSubscription("sub01", eventActor))))

    fep ! Connected(MockSession("sess01"), MockServiceOps("ops01"))

    val initialFailure = Seq(
      Ordered(Seq(
        RegCalled(uuid, false),
        SubCanceled("sub01"))))

    Await.result(checkFut(initialFailure), 5000.milliseconds) should equal(true)

    events.queue.set(Seq())
    mock.regResult.set(Some((Future.failed(exFun), MockServiceHandlerSubscription("sub02", eventActor))))

    val secondFailure = Seq(
      Ordered(Seq(
        RegCalled(uuid, false),
        SubCanceled("sub02"))))

    Await.result(checkFut(secondFailure), 5000.milliseconds) should equal(true)

    comeUpSequence(r, "handler01", "sess01", "input01")
  }

  test("pre-reg, locked") {
    regFailureTest(new LockedException("msg01"))
  }

  test("pre-reg, bad request") {
    regFailureTest(new BadRequestException("msg01"))
  }

  test("pre-reg, other") {
    regFailureTest(new RuntimeException("msg01"))
  }

  test("pre-reg, session crap") {
    val r = new FepTestRig(as.get)
    import r._

    mock.regResult.set(Some((Future.failed(new SessionUnusableException("msg01")), MockServiceHandlerSubscription("sub01", eventActor))))

    fep ! Connected(MockSession("sess01"), MockServiceOps("ops01"))

    val initialFailure = Seq(
      Ordered(Seq(
        RegCalled(uuid, false))),
      Unordered(Seq(
        SubCanceled("sub01"),
        ServiceSessionDead)))

    Await.result(checkFut(initialFailure), 5000.milliseconds) should equal(true)

    events.queue.set(Seq())
    mock.regResult.set(Some((Future.failed(new UnauthorizedException("msg01")), MockServiceHandlerSubscription("sub02", eventActor))))

    fep ! Connected(MockSession("sess02"), MockServiceOps("ops02"))

    val secondFailure = Seq(
      Ordered(Seq(
        RegCalled(uuid, false))),
      Unordered(Seq(
        SubCanceled("sub02"),
        ServiceSessionDead)))

    Await.result(checkFut(secondFailure), 5000.milliseconds) should equal(true)

    comeUpSequence(r, "handler01", "sess03", "input01")
  }

  test("pre-reg, fail before") {
    val r = new FepTestRig(as.get)
    import r._

    val prom = promise[FrontEndRegistration]
    mock.regResult.set(Some((prom.future, MockServiceHandlerSubscription("sub01", eventActor))))

    fep ! Connected(MockSession("sess01"), MockServiceOps("ops01"))

    val comingUp = Seq(
      Ordered(Seq(
        RegCalled(uuid, holdingLock = false))))

    Await.result(checkFut(comingUp), 5000.milliseconds) should equal(true)

    comeUpSequence(r, "sub02", "sess02", "input01", holdingLock = false, Seq(SubCanceled("sub01")))
  }

  /*test("pre-reg, fail before") {
    val r = new FepTestRig(as.get)
    import r._

    val prom = promise[FrontEndRegistration]
    mock.regResult.set(Some((prom.future, MockServiceHandlerSubscription("sub01", eventActor))))

    fep ! Connected(MockSession("sess01"), MockServiceOps("ops01"))

    val comingUp = Seq(
      Ordered(Seq(
        RegCalled(uuid, holdingLock = false))))

    Await.result(checkFut(comingUp), 5000.milliseconds) should equal(true)

    comeUpSequence(r, "sub02", "sess02", "input01", holdingLock = false, Seq(SubCanceled("sub01")))
  }*/

  def sessionDiesWhileProtocolUp(status: FrontEndConnectionStatus.Status, holdingLock: Boolean): Unit = {
    val r = new FepTestRig(as.get)
    import r._

    comeUpSequence(r, "handler01", "sess01", "input01")

    events.queue.set(Seq())

    fep ! StackStatusUpdated(status)

    val onCommsUp = Seq(
      Unordered(Seq(StackStatusUpdated(status))))
    Await.result(checkFut(onCommsUp), 5000.milliseconds) should equal(true)

    events.queue.set(Seq())

    fep ! ServiceSessionDead

    val onSessionDead = Seq(
      Unordered(Seq(
        ServiceSessionDead,
        SubCanceled("handler01"),
        ConfigStopped)))

    Await.result(checkFut(onSessionDead), 5000.milliseconds) should equal(true)

    serviceComeUpProtocolUp(r, "handler02", "sess02", "input02", holdingLock = holdingLock)
  }

  test("comms up, session lost, back up") {
    sessionDiesWhileProtocolUp(FrontEndConnectionStatus.Status.COMMS_UP, holdingLock = true)
  }
  test("comms down, session lost, back up") {
    sessionDiesWhileProtocolUp(FrontEndConnectionStatus.Status.COMMS_DOWN, holdingLock = false)
  }
  test("comms unknown, session lost, back up") {
    sessionDiesWhileProtocolUp(FrontEndConnectionStatus.Status.UNKNOWN, holdingLock = false)
  }
  test("comms error, session lost, back up") {
    sessionDiesWhileProtocolUp(FrontEndConnectionStatus.Status.ERROR, holdingLock = false)
  }

  def sessionRefresh(status: FrontEndConnectionStatus.Status, holdingLock: Boolean): Unit = {
    val r = new FepTestRig(as.get)
    import r._

    comeUpSequence(r, "handler01", "sess01", "input01")

    events.queue.set(Seq())

    fep ! StackStatusUpdated(status)

    val onCommsUp = Seq(
      Unordered(Seq(StackStatusUpdated(status))))
    Await.result(checkFut(onCommsUp), 5000.milliseconds) should equal(true)

    events.queue.set(Seq())
    mock.regResult.set(Some((Future.successful(registration(uuid, "input02")), MockServiceHandlerSubscription("handler02", eventActor))))
    mock.cfgResult.set(Some(Future.successful(FrontendConfiguration(Seq(), Seq(), Seq(), MockSub[EntityEdgeNotification]("modelEdgeSub01"), MockSub[EntityKeyValueNotification]("configSub01")))))

    mock.config.set(Some(FakeConfig("config01")))

    val comingUp = Seq(
      Unordered(Seq(
        SubCanceled("handler01"),
        ConfigStopped,
        RegCalled(uuid, holdingLock),
        ConfigCalled(uuid),
        ConfigStarted,
        LinkUp(MockSession("sess02"), "input02"),
        CommandSubscriptionRetrieved(MockServiceHandlerSubscription("handler02", eventActor)),
        PointMapUpdated(Map()),
        CommandsUpdated(List()))))

    fep ! Connected(MockSession("sess02"), MockServiceOps("ops01"))

    Await.result(checkFut(comingUp), 5000.milliseconds) should equal(true)
  }

  test("comms up, session refresh, back up") {
    sessionRefresh(FrontEndConnectionStatus.Status.COMMS_UP, holdingLock = true)
  }
  test("comms down, session refresh, back up") {
    sessionRefresh(FrontEndConnectionStatus.Status.COMMS_DOWN, holdingLock = false)
  }
  test("comms unknown, session refresh, back up") {
    sessionRefresh(FrontEndConnectionStatus.Status.UNKNOWN, holdingLock = false)
  }
  test("comms error, session refresh, back up") {
    sessionRefresh(FrontEndConnectionStatus.Status.ERROR, holdingLock = false)
  }

  test("session dead, eventual protocol remove") {
    val r = new FepTestRig(as.get)
    import r._

    comeUpSequence(r, "handler01", "sess01", "input01")

    events.queue.set(Seq())
    fep ! StackStatusUpdated(FrontEndConnectionStatus.Status.COMMS_UP)

    val onCommsUp = Seq(
      Unordered(Seq(StackStatusUpdated(FrontEndConnectionStatus.Status.COMMS_UP))))
    Await.result(checkFut(onCommsUp), 5000.milliseconds) should equal(true)

    events.queue.set(Seq())
    fep ! ServiceSessionDead

    val onSessionDead = Seq(
      Unordered(Seq(
        ServiceSessionDead,
        SubCanceled("handler01"),
        ConfigStopped)),
      Unordered(Seq(
        ProtocolUninitialized,
        ProtRemoved(uuid))))

    Await.result(checkFut(onSessionDead), 5000.milliseconds) should equal(true)
  }

  test("session dead, locked, protocol remove") {
    val r = new FepTestRig(as.get)
    import r._

    comeUpSequence(r, "handler01", "sess01", "input01")

    events.queue.set(Seq())
    fep ! StackStatusUpdated(FrontEndConnectionStatus.Status.COMMS_UP)

    val onCommsUp = Seq(
      Unordered(Seq(StackStatusUpdated(FrontEndConnectionStatus.Status.COMMS_UP))))
    Await.result(checkFut(onCommsUp), 5000.milliseconds) should equal(true)

    events.queue.set(Seq())
    fep ! ServiceSessionDead

    val onSessionDead = Seq(
      Unordered(Seq(
        ServiceSessionDead,
        SubCanceled("handler01"),
        ConfigStopped)))

    Await.result(checkFut(onSessionDead), 5000.milliseconds) should equal(true)

    events.queue.set(Seq())
    mock.regResult.set(Some((Future.failed(new LockedException("msg01")), MockServiceHandlerSubscription("handler02", eventActor))))

    fep ! Connected(MockSession("sess02"), MockServiceOps("ops01"))

    val onBackUp = Seq(
      Unordered(Seq(
        RegCalled(uuid, holdingLock = true),
        SubCanceled("handler02"),
        ProtocolUninitialized,
        ProtRemoved(uuid))))

    Await.result(checkFut(onBackUp), 50.milliseconds) should equal(true)
  }
}
