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

import org.scalatest.FunSuite
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import ProtoHelper._

import scala.collection.mutable
import io.greenbus.client.service.proto.Measurements.Measurement

@RunWith(classOf[JUnitRunner])
class TriggerFrameworkTest extends FunSuite with Matchers {

  class TestRig {
    val triggerCalls = mutable.Queue[Measurement]()
    val conditionCalls = mutable.Queue[(Measurement, Boolean)]()
    val actionCalls = mutable.Queue[(Measurement, Boolean, Boolean)]()

    def trigger(ret: Measurement, stop: Boolean): Trigger = new MockTrigger(ret, stop)
    class MockTrigger(ret: Measurement, stop: Boolean) extends Trigger {
      def process(key: String, m: Measurement, cache: ObjectCache[Boolean]) = {
        triggerCalls enqueue m
        Some((ret, stop))
      }
    }

    def condition(ret: Boolean): Trigger.Condition = new MockCondition(ret)
    class MockCondition(ret: Boolean) extends Trigger.SimpleCondition {
      def apply(m: Measurement, prev: Boolean): Boolean = {
        conditionCalls.enqueue((m, prev))
        ret
      }
    }
    def action(ret: Measurement): Action = new MockAction(ret)
    class MockAction(ret: Measurement, var disabled: Boolean = false, val name: String = "action01") extends Action {
      def process(m: Measurement, state: Boolean, prev: Boolean): Option[Measurement] = {
        actionCalls.enqueue((m, state, prev))
        Some(ret)
      }
    }

    val cache = new MockObjectCache[Boolean]
  }

  test("Test Cache Lookup") {
    val r = new TestRig

    val input = makeAnalog(5.3)
    val trigger = new BasicTrigger("meas01.trig01", List(r.condition(true)), Nil, None)
    r.cache.update("meas01.trig01", true)
    val (result, stop) = trigger.process("meas01", input, r.cache).get

    r.conditionCalls.length should equal(1)
    r.conditionCalls.dequeue should equal((input, true))
    r.cache.putQueue.length should equal(1)
    r.cache.putQueue.dequeue should equal(("meas01.trig01", true))
  }

  test("testMultipleTriggers") {
    val r = new TestRig

    val input = makeAnalog(5.3)
    val output1 = makeAnalog(5300.0)
    val output2 = makeAnalog(0.053)

    val triggers = List(r.trigger(output1, false), r.trigger(output2, false))

    val result = Trigger.processAll("meas01", input, r.cache, triggers).get

    r.triggerCalls.length should equal(2)
    r.triggerCalls.dequeue should equal(input)
    r.triggerCalls.dequeue should equal(output1)
    result should equal(output2)
  }

  test("testMultipleTriggerShortCircuit") {
    val r = new TestRig

    val input = makeAnalog(5.3)
    val output1 = makeAnalog(5300.0)
    val output2 = makeAnalog(0.053)

    val triggers = List(r.trigger(output1, true), r.trigger(output2, false))

    val result = Trigger.processAll("meas01", input, r.cache, triggers).get

    r.triggerCalls.length should equal(1)
    r.triggerCalls.dequeue should equal(input)
    result should equal(output1)
  }

  test("testMultipleConditions") {
    val r = new TestRig

    val input = makeAnalog(5.3)
    val output = makeAnalog(5300.0)
    val trigger = new BasicTrigger("meas01.trig01", List(r.condition(true), r.condition(true)), List(r.action(output)), None)
    val (result, stop) = trigger.process("meas01", input, r.cache).get

    r.conditionCalls.length should equal(2)
    r.conditionCalls.dequeue should equal((input, false))
    r.conditionCalls.dequeue should equal((input, false))

    r.actionCalls.length should equal(1)

    result should equal(output)
  }
  test("testMultipleConditionAnd") {
    val r = new TestRig

    val input = makeAnalog(5.3)
    val output = makeAnalog(5300.0)
    val trigger = new BasicTrigger("meas01.trig01", List(r.condition(true), r.condition(false)), List(r.action(output)), None)
    val (result, stop) = trigger.process("meas01", input, r.cache).get

    r.conditionCalls.length should equal(2)
    r.conditionCalls.dequeue should equal((input, false))
    r.conditionCalls.dequeue should equal((input, false))

    r.actionCalls.length should equal(1)
    r.actionCalls.dequeue should equal((input, false, false))
  }

  test("testMultipleConditionShortCircuit") {
    val r = new TestRig

    val input = makeAnalog(5.3)
    val output = makeAnalog(5300.0)
    val trigger = new BasicTrigger("meas01.trig01", List(r.condition(false), r.condition(true)), List(r.action(output)), None)
    val (result, stop) = trigger.process("meas01", input, r.cache).get

    r.conditionCalls.length should equal(1)
    r.conditionCalls.dequeue should equal((input, false))

    r.actionCalls.length should equal(1)
    r.actionCalls.dequeue should equal((input, false, false))
  }

  test("testMultipleActions") {
    val r = new TestRig

    val input = makeAnalog(5.3)
    val output1 = makeAnalog(5300.0)
    val output2 = makeAnalog(0.053)

    val trigger = new BasicTrigger("meas01.trig01", List(r.condition(true)), List(r.action(output1), r.action(output2)), None)
    val (result, stop) = trigger.process("meas01", input, r.cache).get

    r.conditionCalls.length should equal(1)
    r.conditionCalls.dequeue should equal((input, false))
    r.cache.putQueue.length should equal(1)
    r.cache.putQueue.dequeue should equal(("meas01.trig01", true))

    r.actionCalls.length should equal(2)
    r.actionCalls.dequeue should equal((input, true, false))
    r.actionCalls.dequeue should equal((output1, true, false))
    result should equal(output2)
  }
}
