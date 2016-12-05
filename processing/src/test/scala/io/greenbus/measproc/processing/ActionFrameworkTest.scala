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

import scala.collection.mutable
import io.greenbus.client.service.proto.Measurements.Measurement
import ProtoHelper._

@RunWith(classOf[JUnitRunner])
class ActionFrameworkTest extends FunSuite with Matchers {
  import Action._

  class TestRig {
    val evalCalls = mutable.Queue[Measurement]()

    def eval(ret: Measurement): Action.Evaluation = (m: Measurement) => {
      evalCalls enqueue m
      ret
    }

    def action(act: ActivationType, disabled: Boolean, ret: Measurement) =
      new BasicAction("action01", disabled, act, eval(ret))
  }

  test("Disabled") {
    val r = new TestRig
    val input = makeAnalog(5.3)
    val output = makeAnalog(5300.0)
    val result = r.action(High, true, output).process(input, true, true).get
    result should equal(input)
    r.evalCalls.length should equal(0)
  }

  def scenario(state: Boolean, prev: Boolean, act: ActivationType, works: Boolean) = {
    val r = new TestRig
    val input = makeAnalog(5.3)
    val output = makeAnalog(5300.0)
    val result = r.action(act, false, output).process(input, state, prev).get
    if (works) {
      result should equal(output)
      r.evalCalls.length should equal(1)
      r.evalCalls.dequeue should equal(input)
    } else {
      result should equal(input)
      r.evalCalls.length should equal(0)
    }
  }

  def matrix(act: ActivationType, col: Tuple4[Boolean, Boolean, Boolean, Boolean]) = {
    scenario(true, true, act, col._1)
    scenario(true, false, act, col._2)
    scenario(false, true, act, col._3)
    scenario(false, false, act, col._4)
  }

  test("High") {
    //    prev:   true  false true   false
    //    now:    true  true  false  false
    matrix(High, (true, true, false, false))
  }
  test("Low") {
    //    prev:   true  false true   false
    //    now:    true  true  false  false
    matrix(Low, (false, false, true, true))
  }
  test("Rising") {
    //    prev:     true  false true   false
    //    now:      true  true  false  false
    matrix(Rising, (false, true, false, false))
  }
  test("Falling") {
    //    prev:      true  false true   false
    //    now:       true  true  false  false
    matrix(Falling, (false, false, true, false))
  }
  test("Transition") {
    //    prev:         true  false true   false
    //    now:          true  true  false  false
    matrix(Transition, (false, true, true, false))
  }
}