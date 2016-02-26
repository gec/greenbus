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
package io.greenbus.services.model

import java.util.UUID

import io.greenbus.client.service.proto.Measurements.{ MeasurementSamples, Measurement }
import io.greenbus.mstore.sql.{ SqlCurrentValueAndHistorian, MeasurementStoreSchema, HistoricalValueRow }
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class MeasurementSamplingTest extends ServiceTestBase {

  def meas(v: Double, t: Long) = {
    Measurement.newBuilder()
      .setType(Measurement.Type.DOUBLE)
      .setDoubleVal(v)
      .setTime(t)
      .build()
  }
  def measBytes(v: Double, t: Long) = {
    meas(v, t).toByteArray
  }

  class TestRig {

    val id = UUID.randomUUID()

    def row(v: Double, t: Long): HistoricalValueRow = {
      HistoricalValueRow(id, t, measBytes(v, t))
    }

  }

  def checkSample(r: MeasurementSamples, i: Int, start: Long, end: Long, count: Int, mean: Double, max: Double, min: Double): Unit = {
    r.getTimeStart(i) should equal(start)
    r.getTimeEnd(i) should equal(end)
    r.getSampleCount(i) should equal(count)
    r.getAverage(i) should equal(mean)
    r.getMaximum(i) should equal(max)
    r.getMinimum(i) should equal(min)
  }

  test("typical") {
    val r = new TestRig
    import r._

    val history = Seq(
      row(1, 1),
      row(2, 3),
      row(3, 4),
      row(4, 5),

      row(5, 7),
      row(6, 8),

      row(7, 11),
      row(8, 12),
      row(9, 14),

      row(10, 16),
      row(11, 17),
      row(12, 18),
      row(13, 19),

      row(14, 22),

      row(15, 27))

    MeasurementStoreSchema.historicalValues.insert(history)

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    val results = MeasurementSampling.sampleRecur2(mstore, r.id, 6, 25, 5, 3)

    println(results)

    checkSample(results, 0, 6, 11, 2, (5 * 1 + 6 * 3) / 4.0, 6, 5)
    checkSample(results, 1, 11, 16, 3, (7 * 1 + 8 * 2 + 9 * 2) / 5.0, 9, 7)
    checkSample(results, 2, 16, 21, 4, (10 * 1 + 11 * 1 + 12 * 1 + 13 * 2) / 5.0, 13, 10)
    checkSample(results, 3, 21, 26, 1, (13 * 1 + 14 * 4) / 5.0, 14, 13)
  }

  test("empty window") {
    val r = new TestRig
    import r._

    val history = Seq(
      row(5, 7),
      row(6, 8),

      row(12, 18),

      row(15, 27))

    MeasurementStoreSchema.historicalValues.insert(history)

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    val results = MeasurementSampling.sampleRecur2(mstore, r.id, 6, 25, 5, 3)

    results.getTimeStartCount should equal(4)

    checkSample(results, 0, 6, 11, 2, (5 * 1 + 6 * 3) / 4.0, 6, 5)
    checkSample(results, 1, 11, 16, 0, 6.0, 6, 6)
    checkSample(results, 2, 16, 21, 1, (6 * 2 + 12 * 3) / 5.0, 12, 6)
    checkSample(results, 3, 21, 26, 0, 12.0, 12, 12)
  }

  test("empty start") {
    val r = new TestRig
    import r._

    val history = Seq(
      row(2, 3),

      row(12, 18),

      row(15, 27))

    MeasurementStoreSchema.historicalValues.insert(history)

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    val results = MeasurementSampling.sampleRecur2(mstore, r.id, 6, 25, 5, 3)

    results.getTimeStartCount should equal(4)

    checkSample(results, 0, 6, 11, 0, 0.0, 0, 0)
    checkSample(results, 1, 11, 16, 0, 0, 0, 0)
    checkSample(results, 2, 16, 21, 1, (12 * 3) / 5.0, 12, 0.0)
    checkSample(results, 3, 21, 26, 0, 12.0, 12, 12)
  }

  test("empty totally") {
    val r = new TestRig
    import r._

    val history = Seq(
      row(2, 3),

      row(15, 27))

    MeasurementStoreSchema.historicalValues.insert(history)

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    val results = MeasurementSampling.sampleRecur2(mstore, r.id, 6, 25, 5, 3)

    results.getTimeStartCount should equal(4)

    checkSample(results, 0, 6, 11, 0, 0.0, 0, 0)
    checkSample(results, 1, 11, 16, 0, 0, 0, 0)
    checkSample(results, 2, 16, 21, 0, 0, 0, 0)
    checkSample(results, 3, 21, 26, 0, 0, 0, 0)
  }

  test("single window") {
    val r = new TestRig
    import r._

    val history = Seq(
      row(2, 3),

      row(14, 22),

      row(15, 27))

    MeasurementStoreSchema.historicalValues.insert(history)

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    {
      val results = MeasurementSampling.sampleRecur2(mstore, r.id, 6, 10, 5, 3)
      results.getTimeStartCount should equal(1)
      checkSample(results, 0, 6, 11, 0, 0.0, 0, 0)
    }

    {
      val results = MeasurementSampling.sampleRecur2(mstore, r.id, 26, 30, 5, 3)
      results.getTimeStartCount should equal(1)
      checkSample(results, 0, 26, 31, 1, 15, 15, 15)
    }

    {
      val results = MeasurementSampling.sampleRecur2(mstore, r.id, 21, 30, 5, 3)
      results.getTimeStartCount should equal(2)
      checkSample(results, 0, 21, 26, 1, 14, 14, 14)
      checkSample(results, 1, 26, 31, 1, (14.0 * 1 + 15.0 * 4) / 5.0, 15, 14)
    }
  }

  test("handle empty window") {
    val r = new TestRig
    import r._

    val history = Seq(
      row(2, 3),

      row(15, 27))

    MeasurementStoreSchema.historicalValues.insert(history)

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    val results = MeasurementSampling.sampleRecur2(mstore, r.id, 10, 10, 5, 3)

    results.getTimeStartCount should equal(0)
  }

  test("handle backwards window") {
    val r = new TestRig
    import r._

    val history = Seq(
      row(2, 3),

      row(15, 27))

    MeasurementStoreSchema.historicalValues.insert(history)

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    val results = MeasurementSampling.sampleRecur2(mstore, r.id, 10, 6, 5, 3)
    results.getTimeStartCount should equal(0)
  }

  test("handle invalid window length") {
    val r = new TestRig
    import r._

    val history = Seq(
      row(2, 3),

      row(15, 27))

    MeasurementStoreSchema.historicalValues.insert(history)

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    intercept[IllegalArgumentException] {
      MeasurementSampling.sampleRecur2(mstore, r.id, 6, 25, 0, 3)
    }
    intercept[IllegalArgumentException] {
      MeasurementSampling.sampleRecur2(mstore, r.id, 6, 25, -1, 3)
    }
  }
  test("handle invalid page length") {
    val r = new TestRig
    import r._

    val history = Seq(
      row(2, 3),

      row(15, 27))

    MeasurementStoreSchema.historicalValues.insert(history)

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    intercept[IllegalArgumentException] {
      MeasurementSampling.sampleRecur2(mstore, r.id, 6, 25, 5, 0)
    }
    intercept[IllegalArgumentException] {
      MeasurementSampling.sampleRecur2(mstore, r.id, 6, 25, 5, -1)
    }
  }

}
