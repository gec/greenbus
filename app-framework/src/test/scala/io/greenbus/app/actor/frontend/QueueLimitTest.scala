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

import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import io.greenbus.client.service.proto.Measurements.Measurement

@RunWith(classOf[JUnitRunner])
class QueueLimitTest extends FunSuite with ShouldMatchers {

  def meas(t: Int) = Measurement.newBuilder().setType(Measurement.Type.INT).setIntVal(5).setTime(t).build()

  test("Queue limit, leave one in") {
    val original = Seq(
      ("nameA", meas(0)),
      ("nameB", meas(1)),
      ("nameC", meas(2)),
      ("nameA", meas(3)),
      ("nameA", meas(4)),
      ("nameA", meas(5)),
      ("nameA", meas(6)),
      ("nameC", meas(7)))

    val result = ProcessingProxy.maintainQueueLimit(original, 3)

    result.map(_._2.getTime) should equal(Seq(1, 6, 7))
  }

  test("Queue limit, more than limit left") {
    val original = Seq(
      ("nameA", meas(0)),
      ("nameB", meas(1)),
      ("nameC", meas(2)),
      ("nameD", meas(3)),
      ("nameE", meas(4)),
      ("nameF", meas(5)),
      ("nameA", meas(6)),
      ("nameC", meas(7)))

    val result = ProcessingProxy.maintainQueueLimit(original, 3)

    result.map(_._2.getTime) should equal(Seq(1, 3, 4, 5, 6, 7))
  }

}
