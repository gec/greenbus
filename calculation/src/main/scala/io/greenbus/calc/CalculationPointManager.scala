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
package io.greenbus.calc

import akka.actor.{ Actor, Props }
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.msg.{ Session, Subscription, SubscriptionBinding }
import io.greenbus.calc.lib.eval.OperationSource
import io.greenbus.calc.lib.{ CalculationEvaluator, ErrorMeasurement, InputBucket }
import io.greenbus.client.service.MeasurementService
import io.greenbus.client.service.proto.Calculations.{ CalculationDescriptor, CalculationInput }
import io.greenbus.client.service.proto.MeasurementRequests.MeasurementHistoryQuery
import io.greenbus.client.service.proto.Measurements.{ Measurement, MeasurementNotification, PointMeasurementValue, PointMeasurementValues }
import io.greenbus.client.service.proto.Model.ModelUUID

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._

class CalcConfigException(msg: String) extends Exception(msg)

object CalculationPointManager extends Logging {

  case object OnPeriodUpdate

  case class RequestsCompleted(current: Seq[PointMeasurementValue], sub: Subscription[MeasurementNotification], histories: Seq[PointMeasurementValues])
  case class RequestsFailure(ex: Throwable)

  case object ConstantValue

  sealed trait TriggerStrategy
  case object OnAnyUpdate extends TriggerStrategy
  case class PeriodUpdate(periodMs: Long) extends TriggerStrategy

  def props(pointUuid: ModelUUID, config: CalculationDescriptor, session: Session, opSource: OperationSource, measSink: (ModelUUID, Measurement) => Unit): Props = {
    Props(classOf[CalculationPointManager], pointUuid, config, session, opSource, measSink)
  }

  def buildBuckets(config: Seq[CalculationInput]): Map[ModelUUID, InputBucket] = {
    config.map { calc =>
      val point = calc.getPointUuid
      val bucket = InputBucket.build(calc)
      (point, bucket)
    }.toMap
  }

  def getTriggerStrategy(config: CalculationDescriptor): TriggerStrategy = {

    def error = new CalcConfigException("Calculation must have a valid trigger strategy")

    if (config.hasTriggering) {
      val triggering = config.getTriggering
      if (triggering.hasUpdateAny && triggering.getUpdateAny) {
        OnAnyUpdate
      } else if (triggering.hasPeriodMs) {
        PeriodUpdate(triggering.getPeriodMs)
      } else {
        throw error
      }
    } else {
      throw error
    }
  }

  def requestForInput(calc: CalculationInput): Option[MeasurementHistoryQuery] = {
    import io.greenbus.util.Optional._

    if (calc.hasSingle || calc.hasRange && calc.getRange.hasSinceLast && calc.getRange.getSinceLast) {
      None
    } else {

      val range = optGet(calc.hasRange, calc.getRange) getOrElse {
        throw new CalcConfigException("Non-singular calc input must include range")
      }

      val pointUuid = optGet(calc.hasPointUuid, calc.getPointUuid).getOrElse {
        throw new CalcConfigException("Must include point uuid for calc input")
      }

      val limit = optGet(range.hasLimit, range.getLimit).getOrElse(100)

      val timeFromMs = if (range.hasFromMs) Some(range.getFromMs) else None

      val b = MeasurementHistoryQuery.newBuilder()
        .setPointUuid(pointUuid)
        .setLatest(true)
        .setLimit(limit)

      timeFromMs.foreach(offset => b.setTimeFrom(System.currentTimeMillis() + offset))

      Some(b.build())
    }
  }

}

class CalculationPointManager(pointUuid: ModelUUID, config: CalculationDescriptor, session: Session, opSource: OperationSource, measSink: (ModelUUID, Measurement) => Unit) extends Actor with Logging {
  import io.greenbus.calc.CalculationPointManager._

  private var inputPoints: Map[ModelUUID, InputBucket] = buildBuckets(config.getCalcInputsList.toSeq) //Map.empty[ModelUUID, InputBucket]

  private val triggerStrategy: TriggerStrategy = getTriggerStrategy(config)

  private val evaluator: CalculationEvaluator = CalculationEvaluator.build(config, opSource)

  private var binding = Option.empty[SubscriptionBinding]

  setupData()

  def receive = {
    case ConstantValue =>
      evaluate()

    case RequestsCompleted(current, sub, histories) =>
      binding = Some(sub)

      initBuckets(current, histories)

      sub.start { measEvent =>
        self ! measEvent
      }

      logger.info(s"Calculation initialized for point ${pointUuid.getValue}")

      triggerStrategy match {
        case PeriodUpdate(periodMs) => scheduleMsg(periodMs, OnPeriodUpdate)
        case _ =>
      }

    case RequestsFailure(ex) =>
      logger.warn(s"Failed to get data for calculation point ${pointUuid.getValue}")
      throw ex

    case m: MeasurementNotification =>
      val point = m.getPointUuid
      val meas = m.getValue

      logger.trace(s"Got measurement update for point ${point.getValue} on ${pointUuid.getValue}")

      inputPoints.get(point) match {
        case None => logger.warn("Got measurement event for point without bucket: " + m.getPointName)
        case Some(current) =>
          inputPoints = inputPoints.updated(point, current.added(meas))
      }

      triggerStrategy match {
        case PeriodUpdate(_) =>
        case OnAnyUpdate => evaluate()
      }

    case OnPeriodUpdate =>
      evaluate()

      triggerStrategy match {
        case PeriodUpdate(periodMs) => scheduleMsg(periodMs, OnPeriodUpdate)
        case other => throw new IllegalStateException("Got period update but strategy was on any update")
      }
  }

  private def evaluate() {

    logger.debug(s"Evaluating - ${pointUuid.getValue}")

    val snapshot = inputPoints.mapValues(_.snapshot())

    val containerMap = snapshot.mapValues(_._1).flatMap { case (k, vOpt) => vOpt.map(v => (k, v)) }
    val updatedInputs = snapshot.mapValues(_._2)

    try {
      evaluator.evaluate(containerMap) match {
        case None =>
        case Some(result) =>
          inputPoints = updatedInputs
          measSink(pointUuid, result)
      }
    } catch {
      case ex: Throwable =>
        logger.warn("Calculation error: " + ex)
        ex.printStackTrace()
        measSink(pointUuid, ErrorMeasurement.build())
    }
  }

  private def setupData() {
    import context.dispatcher

    val inputs = config.getCalcInputsList.toSeq

    if (inputs.nonEmpty) {

      val measClient = MeasurementService.client(session)

      val allPointUuids = inputs.map(_.getPointUuid)

      val subFut = measClient.getCurrentValuesAndSubscribe(allPointUuids)

      val queries = inputs.flatMap(requestForInput)

      val histsFut: Future[Seq[PointMeasurementValues]] = Future.sequence {
        queries.map(measClient.getHistory)
      }

      val allFut = subFut zip histsFut

      allFut.onSuccess {
        case ((current, sub), hists) => self ! RequestsCompleted(current, sub, hists)
      }
      subFut.onFailure {
        case ex =>
          subFut.foreach(_._2.cancel())
          self ! RequestsFailure(ex)
      }

    } else {

      self ! ConstantValue
    }
  }

  private def initBuckets(current: Seq[PointMeasurementValue], histories: Seq[PointMeasurementValues]) {

    val uuidToHistMap: Map[ModelUUID, Seq[Measurement]] = histories.map(pvs => (pvs.getPointUuid, pvs.getValueList.toSeq)).toMap

    val mapUpdates = current.flatMap { pv =>
      val point = pv.getPointUuid
      val m = pv.getValue

      val histOpt = uuidToHistMap.get(point)

      val measSeq = histOpt.map { hist =>
        if (hist.last.getTime < m.getTime) {
          hist :+ m
        } else {
          hist
        }
      } getOrElse {
        Seq(m)
      }

      inputPoints.get(point).map { bucket =>
        (point, bucket.added(measSeq))
      }
    }

    inputPoints = inputPoints ++ mapUpdates
  }

  override def postStop() {
    logger.debug("Canceling subscription binding for " + pointUuid.getValue)
    binding.foreach(_.cancel())
  }

  private def scheduleMsg(timeMs: Long, msg: AnyRef) {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(
      Duration(timeMs, MILLISECONDS),
      self,
      msg)
  }
}