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
package io.greenbus.measproc.processing

import io.greenbus.client.service.proto.Measurements
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.client.service.proto.Processing.MeasOverride
import io.greenbus.jmx.{ Metrics, MetricsContainer }
import io.greenbus.measproc.processing.ProtoHelper._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers

import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class OverrideProcessorTest extends FunSuite with Matchers {

  class TestRig {

    val measQueue = new mutable.Queue[(String, Measurement)]()

    //val overCache = new MockObjectCache[(Measurement, Boolean)]
    val overCache = new MockObjectCache[(Option[Measurement], Option[Measurement])]
    val measCache = new MockObjectCache[Measurement]

    val metrics = Metrics(MetricsContainer())

    def publish(key: String, m: Measurement): Unit = {
      measQueue.enqueue((key, m))
    }
    def handleRestore(key: String, prevPublishedOpt: Option[Measurement], latestOpt: Option[Measurement]): Unit = {
      val vOpt = latestOpt orElse prevPublishedOpt
      vOpt.foreach(m => measQueue.enqueue((key, m)))
    }

    def handleTestReplaceValue(pointKey: String, replaceValue: Measurement, previousValueOpt: Option[Measurement], alreadyReplaced: Boolean): Unit = {
    }

    val proc = new OverrideProcessor(handleRestore, publish, handleTestReplaceValue, overCache, measCache.get, metrics)

    def configure(config: List[MeasOverride]) {
      config.foreach(proc.add)
    }

    def sendAndCheckMeas(key: String, m: Measurement) {
      proc.process(key, m) should equal(Some(m))
      overCache.putQueue.length should equal(0)
    }

    def sendAndCheckOver(key: String, m: Measurement) {
      proc.process(key, m) should equal(None)
      val (qKey, (qPublishedOpt, qLatestOpt)) = overCache.putQueue.dequeue()
      qKey should equal(key)
      qLatestOpt.isEmpty should equal(false)
      checkSame(m, qLatestOpt.get)
    }
    def receiveAndCheckMeas(key: String, orig: Measurement) {
      val (qKey, qMeas) = measQueue.dequeue()
      qKey should equal(key)
      checkSameExceptTimeIsGreater(orig, qMeas)
    }
    def receiveAndCheckOver(key: String, pubOpt: Option[Measurement], latestOpt: Option[Measurement]) {
      val (qKey, (qPublishedOpt, qLatestOpt)) = overCache.putQueue.dequeue()
      qKey should equal(key)

      checkOptMeas(pubOpt, qPublishedOpt)
      checkOptMeas(latestOpt, qLatestOpt)
    }
    def checkNISPublished(key: String, orig: Measurement) {
      val (qKey, qMeas) = measQueue.dequeue()
      qKey should equal(key)
      checkNIS(orig, qMeas)
    }
    def checkReplacePublished(key: String, repl: Measurement) {
      val (qKey, qMeas) = measQueue.dequeue()
      qKey should equal(key)
      checkReplaced(repl, qMeas)
    }

    def checkOptMeas(aOpt: Option[Measurement], bOpt: Option[Measurement]): Unit = {
      (aOpt, bOpt) match {
        case (Some(a), Some(b)) => checkSame(a, b)
        case (None, None) =>
        case _ => assert(false)
      }
    }
  }

  def makeOverride(name: String): MeasOverride = makeOverride(name, 0)

  def makeNIS(key: String) = {
    MeasOverride.newBuilder.setPointUuid(ModelUUID.newBuilder().setValue(key)).build
  }
  def makeOverride(key: String, value: Double): MeasOverride = {
    MeasOverride.newBuilder
      .setPointUuid(ModelUUID.newBuilder().setValue(key))
      .setMeasurement(Measurement.newBuilder
        .setTime(85)
        .setType(Measurement.Type.DOUBLE)
        .setDoubleVal(value)
        .setQuality(Measurements.Quality.newBuilder.setDetailQual(Measurements.DetailQual.newBuilder)))
      .build
  }

  def checkSame(m: Measurement, meas: Measurement): Unit = {
    m.getType should equal(meas.getType)
    m.getDoubleVal should equal(meas.getDoubleVal)
    m.getTime should equal(meas.getTime)
    m.getQuality should equal(meas.getQuality)
  }

  def checkSameExceptTimeIsGreater(orig: Measurement, meas: Measurement): Unit = {
    meas.getType should equal(orig.getType)
    meas.getDoubleVal should equal(orig.getDoubleVal)
    meas.getQuality should equal(orig.getQuality)
    meas.getTime should be >= (orig.getTime)
  }

  def sameExceptQualityAndTimeIsGreater(orig: Measurement, pub: Measurement): Unit = {
    pub.getType should equal(orig.getType)
    pub.getDoubleVal should equal(orig.getDoubleVal)
    pub.getTime should be >= (orig.getTime)
  }
  def checkNIS(orig: Measurement, pub: Measurement): Unit = {
    sameExceptQualityAndTimeIsGreater(orig, pub)
    pub.getQuality.getDetailQual.getOldData should equal(true)
    pub.getQuality.getOperatorBlocked should equal(true)
  }

  def checkReplaced(repl: Measurement, pub: Measurement): Unit = {
    sameExceptQualityAndTimeIsGreater(repl, pub)
    pub.getQuality.getDetailQual.getOldData should equal(false)
    pub.getQuality.getSource should equal(Measurements.Quality.Source.SUBSTITUTED)
  }

  test("NullOverride") {
    val r = new TestRig
    r.configure(Nil)
    r.sendAndCheckMeas("meas01", makeAnalog(5.3))
  }

  test("Preconfig") {
    val r = new TestRig
    val config = List(makeOverride("meas01", 89))
    r.configure(config)
    r.checkReplacePublished("meas01", config(0).getMeasurement)
    r.sendAndCheckOver("meas01", makeAnalog(5.3))
  }

  test("Multiple") {
    val config = List(makeOverride("meas01", 89), makeOverride("meas02", 44))
    val r = new TestRig
    r.configure(config)

    r.checkReplacePublished("meas01", config(0).getMeasurement)
    r.checkReplacePublished("meas02", config(1).getMeasurement)
    r.sendAndCheckOver("meas01", makeAnalog(5.3))
    r.sendAndCheckOver("meas02", makeAnalog(5.3))
    r.sendAndCheckMeas("meas03", makeAnalog(5.3))
  }

  test("NISWithReplace") {
    val r = new TestRig
    r.configure(Nil)
    val orig = makeAnalog(5.3)
    r.measCache.update("meas01", orig)
    val over = makeOverride("meas01", 89)
    r.proc.add(over)
    r.checkReplacePublished("meas01", over.getMeasurement)
    r.receiveAndCheckOver("meas01", Some(orig), None)
    r.sendAndCheckOver("meas01", makeAnalog(44))
  }

  test("NISThenReplace") {
    val r = new TestRig
    r.configure(Nil)
    val orig = makeAnalog(5.3)
    r.measCache.update("meas01", orig)
    val nis = makeNIS("meas01")
    r.proc.add(nis)
    r.checkNISPublished("meas01", orig)
    r.receiveAndCheckOver("meas01", Some(orig), None)

    val replace = makeOverride("meas01", 89)
    r.proc.add(replace)
    r.checkReplacePublished("meas01", replace.getMeasurement)

    r.sendAndCheckOver("meas01", makeAnalog(44))
  }

  test("DoubleAdd") {
    val r = new TestRig
    r.configure(Nil)

    val orig = makeAnalog(5.3)
    r.measCache.update("meas01", orig)
    val over = makeOverride("meas01", 89)
    r.proc.add(over)
    r.checkReplacePublished("meas01", over.getMeasurement)
    r.receiveAndCheckOver("meas01", Some(orig), None)
    r.sendAndCheckOver("meas01", makeAnalog(44))

    // On the second add, we shouldn't override the cached "real" value
    val over2 = makeOverride("meas01", 55)
    r.proc.add(over2)
    r.checkReplacePublished("meas01", over2.getMeasurement)
    r.overCache.putQueue.length should equal(0)
    r.sendAndCheckOver("meas01", makeAnalog(23))
  }

  test("Removed") {
    val config = List(makeOverride("meas01", 89))
    val r = new TestRig
    r.configure(config)

    r.checkReplacePublished("meas01", config(0).getMeasurement)
    val orig = makeAnalog(5.3)
    r.overCache.update("meas01", (Some(orig), None))
    val nisRemove = makeNIS("meas01")
    r.proc.remove(nisRemove)
    r.receiveAndCheckMeas("meas01", orig)
    r.overCache.delQueue.length should equal(1)
    r.overCache.delQueue.dequeue should equal("meas01")
    r.sendAndCheckMeas("meas01", makeAnalog(33))
  }
}

