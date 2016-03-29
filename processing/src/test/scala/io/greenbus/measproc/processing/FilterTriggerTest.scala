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

import java.util.UUID

import io.greenbus.measproc.PointMap
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import io.greenbus.client.service.proto.Measurements.{ DetailQual, Quality, Measurement }
import io.greenbus.client.service.proto.Measurements.Quality.Validity
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.client.service.proto.Processing.{ Trigger => TriggerProto, Filter => FilterProto, Action => ActionProto, ActivationType }
import ProtoHelper._
import io.greenbus.client.service.proto.EventRequests.EventTemplate

@RunWith(classOf[JUnitRunner])
class FilterTriggerTest extends FunSuite with ShouldMatchers {

  val pointMap = new PointMap(Seq())

  test("Factory") {
    val cache = new MockObjectCache[Measurement]()
    def publish(ev: EventTemplate.Builder): Unit = {}

    val fac = new TriggerProcessingFactory(publish, cache, () => pointMap)

    val stateCache = new MockObjectCache[Boolean]

    val proto = TriggerProto.newBuilder
      .setTriggerName("testTrigger")
      .setFilter(
        FilterProto.newBuilder
          .setType(FilterProto.FilterType.DUPLICATES_ONLY))
      .addActions(
        ActionProto.newBuilder
          .setSuppress(true)
          .setActionName("action01")
          .setType(ActivationType.LOW))
      .build

    val trig = fac.buildTrigger(proto, "point01", ModelUUID.newBuilder().setValue(UUID.randomUUID().toString).build())

    val m = makeInt(10)
    trig.process("test01", m, stateCache) should equal(Some((m, false)))

    trig.process("test01", m, stateCache) should equal(None)

  }

  class Fixture(band: FilterTrigger.Filter) {
    val cache = new MockObjectCache[Measurement]()
    val t = new FilterTrigger(cache, band)

    def sendAndBlock(key: String, m: Measurement) {
      t.apply(key, m, false) should equal(false)
      cache.putQueue.size should equal(0)
    }
    def sendAndReceive(key: String, m: Measurement) {
      t.apply(key, m, false) should equal(true)
      cache.putQueue.size should equal(1)
      cache.putQueue.dequeue should equal(key, m)
    }
  }

  test("Duplicates first through") {
    val f = new Fixture(new FilterTrigger.NoDuplicates)

    val m = makeAnalog(4.234)

    f.sendAndReceive("test01", m)

    f.sendAndBlock("test01", m)
  }

  test("No duplicates") {
    val f = new Fixture(new FilterTrigger.NoDuplicates)

    val m = makeAnalog(4.234)

    f.cache.update("test01", m)

    f.sendAndBlock("test01", m)

    f.sendAndReceive("test01", makeAnalog(3.3))
  }

  test("Duplicates") {
    duplicateTest(makeAnalog(4.234))
    duplicateTest(makeInt(10))
    duplicateTest(makeBool(true))
    duplicateTest(makeString("string"))
  }

  def duplicateTest(m: Measurement) {
    val f = new Fixture(new FilterTrigger.NoDuplicates)
    f.cache.update("test01", m)
    f.sendAndBlock("test01", m)
  }

  test("Isolate quality") {
    val f = new Fixture(new FilterTrigger.NoDuplicates)

    val m = makeAnalog(4.234)
    f.cache.update("test01", m)

    val m2 = Measurement.newBuilder(m).setQuality(Quality.newBuilder.setDetailQual(DetailQual.newBuilder).setValidity(Validity.INVALID)).build
    f.sendAndReceive("test01", m2)
  }

  test("Ints") {
    val f = new Fixture(new FilterTrigger.Deadband(2))

    val m = makeInt(10)

    f.sendAndReceive("test01", m)

    f.sendAndBlock("test01", makeInt(11))

    f.sendAndBlock("test01", makeInt(12))

    f.sendAndReceive("test01", makeInt(13))

    f.sendAndReceive("test01", makeInt(10))
  }

  test("Double") {
    val f = new Fixture(new FilterTrigger.Deadband(1.5))

    f.sendAndReceive("test01", makeAnalog(10.01))

    f.sendAndBlock("test01", makeAnalog(11.01))

    f.sendAndBlock("test01", makeAnalog(11.51))

    f.sendAndReceive("test01", makeAnalog(11.52))

    f.sendAndReceive("test01", makeAnalog(9.99))
  }
}