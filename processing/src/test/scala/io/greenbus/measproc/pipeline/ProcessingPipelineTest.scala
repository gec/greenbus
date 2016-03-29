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
package io.greenbus.measproc.pipeline

import io.greenbus.measproc.PointMap
import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import scala.collection.mutable

import io.greenbus.client.service.proto.Measurements.{ Quality, Measurement }
import io.greenbus.measproc.processing.MockObjectCache
import io.greenbus.measproc.processing.ProtoHelper._
import io.greenbus.client.service.proto.EventRequests.EventTemplate

@RunWith(classOf[JUnitRunner])
class ProcessingPipelineTest extends FunSuite with ShouldMatchers {

  class TestRig {
    val measQueue = mutable.Queue[(String, Measurement)]()
    val eventQueue = mutable.Queue[EventTemplate.Builder]()
    val measCache = new MockObjectCache[Measurement]
    val overCache = new MockObjectCache[(Option[Measurement], Option[Measurement])]
    val stateCache = new MockObjectCache[Boolean]
    val pointMap = new PointMap(Seq())

    def publish(meases: Seq[(String, Measurement)]) {

    }

    val proc = new ProcessingPipeline(
      "endpoint01",
      List("meas01"),
      measQueue.enqueue,
      measCache.get,
      eventQueue.enqueue,
      () => pointMap,
      stateCache,
      overCache)

    def process(key: String, m: Measurement) {
      proc.process(Seq((key, m)), None)
    }
  }

  test("Typical") {
    val r = new TestRig
    r.proc.addTriggerSet(fakeUuid("meas01"), triggerSet)

    val m = makeAnalog(5.3, 0)
    r.process("meas01", m)

    r.measQueue.length should equal(1)
    val (key, meas) = r.measQueue.dequeue
    key should equal("meas01")
    checkGood(meas)

    r.eventQueue.length should equal(0)
    r.stateCache.putQueue.length should equal(2)
  }

  test("Failure") {
    val r = new TestRig
    r.proc.addTriggerSet(fakeUuid("meas01"), triggerSet)

    val m = makeAnalog(-5.3, 0)

    // First bad RLC check
    r.process("meas01", m)
    r.measQueue.length should equal(1)
    checkStripped(r.measQueue.dequeue._2)
    r.eventQueue.length should equal(1)
    r.eventQueue.dequeue.getEventType should equal("event01")

    // Check second bad value doesn't generate event
    r.process("meas01", m)
    r.measQueue.length should equal(1)
    checkStripped(r.measQueue.dequeue._2)
    r.eventQueue.length should equal(0)

    // Check deadband still bad
    val m2 = makeAnalog(4.2, 0)
    r.process("meas01", m2)
    r.measQueue.length should equal(1)
    checkStripped(r.measQueue.dequeue._2)
    r.eventQueue.length should equal(0)

    // Check return to normal
    val m3 = makeAnalog(5.3, 0)
    r.process("meas01", m3)
    r.measQueue.length should equal(1)
    checkGood(r.measQueue.dequeue._2)
    r.eventQueue.length should equal(1)
    r.eventQueue.dequeue.getEventType should equal("event02")
  }

  def checkPrevInPutQueue(r: TestRig, key: String, meas: Measurement): Unit = {
    val (overKey, (pubMeasOpt, latestMeasOpt)) = r.overCache.putQueue.dequeue
    pubMeasOpt.isEmpty should equal(false)
    pubMeasOpt.get should equal(meas)
    overKey should equal(key)
    latestMeasOpt.isEmpty should equal(true)
  }

  def checkLatestInPutQueue(r: TestRig, key: String, published: Measurement, latest: Measurement): Unit = {
    val (overKey, (pubMeasOpt, latestMeasOpt)) = r.overCache.putQueue.dequeue
    overKey should equal(key)

    pubMeasOpt.isEmpty should equal(false)
    pubMeasOpt.get should equal(published)

    latestMeasOpt.isEmpty should equal(false)
    latestMeasOpt.get should equal(latest)
  }

  test("NIS") {
    val r = new TestRig
    r.proc.addTriggerSet(fakeUuid("meas01"), triggerSet)

    val m = makeAnalog(5.3, 0)
    r.process("meas01", m)

    r.measQueue.length should equal(1)
    val (key, meas) = r.measQueue.dequeue()
    checkGood(meas)

    r.measCache.put("meas01", meas)

    r.proc.addOverride(makeNIS("meas01"))

    {
      r.overCache.putQueue.length should equal(1)
      checkPrevInPutQueue(r, key, meas)
    }

    r.measQueue.length should equal(1)
    val (nisedKey, nised) = r.measQueue.dequeue
    nised.getQuality.getOperatorBlocked should equal(true)
    nised.getQuality.getDetailQual.getOldData should equal(true)

    val m2 = makeAnalog(48, 0)
    r.process("meas01", m2)
    r.measQueue.length should equal(0)

    {
      r.overCache.putQueue.length should equal(1)
      checkLatestInPutQueue(r, key, meas, m2)
    }

    r.proc.removeOverride(makeNIS("meas01"))
    r.measQueue.length should equal(1)
    checkGood(r.measQueue.dequeue._2, 48)
    r.overCache.delQueue.length should equal(1)
    r.overCache.delQueue.dequeue should equal("meas01")
  }

  test("un-NIS same meas") {
    val r = new TestRig
    r.proc.addTriggerSet(fakeUuid("meas01"), triggerSet)

    val m = makeAnalog(5.3, 0)
    r.process("meas01", m)

    r.measQueue.length should equal(1)
    val (key, meas) = r.measQueue.dequeue()
    checkGood(meas)

    r.measCache.put("meas01", meas)

    r.proc.addOverride(makeNIS("meas01"))

    {
      r.overCache.putQueue.length should equal(1)
      checkPrevInPutQueue(r, key, meas)
    }

    r.measQueue.length should equal(1)
    val (nisedKey, nised) = r.measQueue.dequeue
    checkTransformedValue(nised, 5.3)
    nised.getQuality.getOperatorBlocked should equal(true)
    nised.getQuality.getDetailQual.getOldData should equal(true)

    r.proc.removeOverride(makeNIS("meas01"))
    r.measQueue.length should equal(1)
    checkGood(r.measQueue.dequeue._2, 5.3)
    r.overCache.delQueue.length should equal(1)
    r.overCache.delQueue.dequeue should equal("meas01")
  }

  test("replace") {
    val r = new TestRig
    r.proc.addTriggerSet(fakeUuid("meas01"), triggerSet)

    val m = makeAnalog(5.3, 0)
    r.process("meas01", m)

    r.measQueue.length should equal(1)
    val (key, meas) = r.measQueue.dequeue()
    checkGood(meas)

    r.measCache.put("meas01", meas)

    r.proc.addOverride(makeOverride("meas01", 33.0))

    {
      r.overCache.putQueue.length should equal(1)
      checkPrevInPutQueue(r, key, meas)
    }

    r.measQueue.length should equal(1)
    val (replacedKey, replaced) = r.measQueue.dequeue
    replaced.getDoubleVal should equal(33.0)
    replaced.getQuality.getOperatorBlocked should equal(true)
    replaced.getQuality.getSource should equal(Quality.Source.SUBSTITUTED)

    val m2 = makeAnalog(48, 0)
    r.process("meas01", m2)
    r.measQueue.length should equal(0)

    {
      r.overCache.putQueue.length should equal(1)
      checkLatestInPutQueue(r, key, meas, m2)
    }

    r.proc.removeOverride(makeNIS("meas01"))
    r.measQueue.length should equal(1)
    checkGood(r.measQueue.dequeue._2, 48)
    r.overCache.delQueue.length should equal(1)
    r.overCache.delQueue.dequeue should equal("meas01")
  }

  test("un-replace same meas") {
    val r = new TestRig
    r.proc.addTriggerSet(fakeUuid("meas01"), triggerSet)

    val m = makeAnalog(5.3, 0)
    r.process("meas01", m)

    r.measQueue.length should equal(1)
    val (key, meas) = r.measQueue.dequeue()
    checkGood(meas)

    r.measCache.put("meas01", meas)

    r.proc.addOverride(makeOverride("meas01", 33.0))

    {
      r.overCache.putQueue.length should equal(1)
      checkPrevInPutQueue(r, key, meas)
    }

    r.measQueue.length should equal(1)
    val (replacedKey, replaced) = r.measQueue.dequeue
    replaced.getDoubleVal should equal(33.0)
    replaced.getQuality.getOperatorBlocked should equal(true)
    replaced.getQuality.getSource should equal(Quality.Source.SUBSTITUTED)

    r.proc.removeOverride(makeNIS("meas01"))
    r.measQueue.length should equal(1)
    checkGood(r.measQueue.dequeue._2, 5.3)
    r.overCache.delQueue.length should equal(1)
    r.overCache.delQueue.dequeue should equal("meas01")
  }

  test("un-replace publishes event") {
    val r = new TestRig
    r.proc.addTriggerSet(fakeUuid("meas01"), triggerSet)

    val m = makeAnalog(5.3, 0)
    r.process("meas01", m)

    r.measQueue.length should equal(1)
    val (key, meas) = r.measQueue.dequeue()
    checkGood(meas)

    r.measCache.put("meas01", meas)

    r.proc.addOverride(makeOverride("meas01", 33.0))

    {
      r.overCache.putQueue.length should equal(1)
      checkPrevInPutQueue(r, key, meas)
    }

    r.measQueue.length should equal(1)
    val (replacedKey, replaced) = r.measQueue.dequeue
    replaced.getDoubleVal should equal(33.0)
    replaced.getQuality.getOperatorBlocked should equal(true)
    replaced.getQuality.getSource should equal(Quality.Source.SUBSTITUTED)

    val m2 = makeAnalog(-3.0, 0)
    r.process("meas01", m2)
    r.measQueue.length should equal(0)

    {
      r.overCache.putQueue.length should equal(1)
      checkLatestInPutQueue(r, key, meas, m2)
    }

    r.proc.removeOverride(makeNIS("meas01"))
    r.overCache.delQueue.length should equal(1)
    r.overCache.delQueue.dequeue should equal("meas01")

    r.measQueue.length should equal(1)
    checkStripped(r.measQueue.dequeue._2)
    r.eventQueue.length should equal(1)
    r.eventQueue.dequeue.getEventType should equal("event01")
  }

  def checkTransformedValue(m: Measurement, value: Double): Unit = {
    m.getType should equal(Measurement.Type.DOUBLE)
    m.getDoubleVal should equal(value * 10 + 50000)
  }

  def checkGood(m: Measurement, value: Double = 5.3) {
    m.getType should equal(Measurement.Type.DOUBLE)
    m.getDoubleVal should equal(value * 10 + 50000)
  }
  def checkStripped(m: Measurement) {
    m.getType should equal(Measurement.Type.NONE)
    m.hasDoubleVal should equal(false)
    m.getQuality.getValidity should equal(Quality.Validity.QUESTIONABLE)
  }
}
