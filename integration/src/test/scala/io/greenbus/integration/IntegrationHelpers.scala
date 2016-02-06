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

import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.client.service.MeasurementService
import io.greenbus.client.service.proto.Measurements.{ Measurement, MeasurementBatch, PointMeasurementValue, Quality }
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.client.service.proto.ModelRequests.EndpointDisabledUpdate
import io.greenbus.client.service.proto.Processing
import io.greenbus.client.service.proto.Processing._
import io.greenbus.msg.Session
import org.scalatest.matchers.ShouldMatchers

import scala.annotation.tailrec
import scala.concurrent._
import scala.concurrent.duration._

object IntegrationHelpers extends ShouldMatchers with Logging {

  def analogBatch(uuid: ModelUUID, v: Double, time: Long): MeasurementBatch = {
    MeasurementBatch.newBuilder()
      .addPointMeasurements(PointMeasurementValue.newBuilder()
        .setPointUuid(uuid)
        .setValue(analogMeas(v, time)))
      .build()
  }

  def analogMeas(v: Double, time: Long): Measurement = {
    Measurement.newBuilder()
      .setDoubleVal(v)
      .setType(Measurement.Type.DOUBLE)
      .setTime(time)
      .build()
  }

  def checkAnalog(m: Measurement, v: Double, time: Option[Long], qual: Quality.Validity = Quality.Validity.GOOD) {
    m.hasDoubleVal should equal(true)
    m.getDoubleVal should equal(v)
    m.getType should equal(Measurement.Type.DOUBLE)
    time.foreach(t => m.getTime should equal(t))
    m.getQuality.getValidity should equal(qual)
  }

  def postAndGet(session: Session, point: ModelUUID, batch: MeasurementBatch, address: String, time: Long = 5000): Measurement = {
    val measClient = MeasurementService.client(session)
    val postResult = Await.result(measClient.postMeasurements(batch, address), time.milliseconds)
    postResult should equal(true)

    val getResult = Await.result(measClient.getCurrentValues(List(point)), time.milliseconds)
    getResult.size should equal(1)

    getResult.head.getValue
  }

  def endUpdate(uuid: ModelUUID, disabled: Boolean) = EndpointDisabledUpdate.newBuilder().setEndpointUuid(uuid).setDisabled(disabled).build()

  @tailrec
  def retryFutureUntilSuccess[A](times: Int, wait: Long)(f: => Future[A]): A = {
    if (times == 0) {
      throw new RuntimeException("Failed too many times")
    }
    try {
      Await.result(f, wait.milliseconds)
    } catch {
      case ex: Throwable =>
        logger.warn("Request failure: " + ex)
        retryFutureUntilSuccess(times - 1, wait)(f)
    }
  }

  @tailrec
  def retryUntilSuccess[A](times: Int, wait: Long)(f: => A): A = {
    if (times == 0) {
      throw new RuntimeException("Failed too many times")
    }
    try {
      f
    } catch {
      case ex: Throwable =>
        logger.warn("Attempt failure: " + ex)
        Thread.sleep(wait)
        retryUntilSuccess(times - 1, wait)(f)
    }
  }
}

class TypedEventQueue[A] {

  private var queue = Seq.empty[A]
  private var chk = Option.empty[(Promise[Seq[A]], Seq[A] => Boolean)]

  private val mutex = new Object

  def received(obj: A): Unit = {
    mutex.synchronized {
      queue = queue ++ Vector(obj)
      chk.foreach {
        case (prom, check) =>
          if (check(queue)) {
            prom.success(queue)
            chk = None
          }
      }
    }
  }

  def listen(check: Seq[A] => Boolean): Future[Seq[A]] = {
    mutex.synchronized {
      val prom = promise[Seq[A]]

      if (check(queue)) {
        prom.success(queue)
      } else {
        chk = Some((prom, check))
      }
      prom.future

    }
  }
}
