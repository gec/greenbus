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
package io.greenbus.mstore.sql.rotate

import java.util.UUID

import io.greenbus.client.service.proto.Measurements.{ Measurement, Quality }
import io.greenbus.mstore.sql.{ HistoricalValueRow, MeasurementStoreSchema }
import io.greenbus.sql.test.{ DatabaseUsingTestBase, RunTestsInsideTransaction }
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.squeryl.Table

@RunWith(classOf[JUnitRunner])
class RotationTest extends DatabaseUsingTestBase with RunTestsInsideTransaction {

  val sliceCount = 5
  val rotateSchema = new RotatingStoreSchema(sliceCount)
  def schemas = List(MeasurementStoreSchema, rotateSchema)

  def buildMeas(v: Int, time: Long): Measurement = {
    Measurement.newBuilder
      .setType(Measurement.Type.INT)
      .setIntVal(v)
      .setTime(time)
      .setQuality(Quality.newBuilder)
      .build
  }

  def simplify(m: Measurement): (Long, Long) = {
    (m.getIntVal, m.getTime)
  }

  def check(correct: Set[(UUID, (Long, Long))])(op: => Seq[(UUID, Measurement)]) {
    val results = op
    results.size should equal(correct.size)
    results.map(tup => (tup._1, simplify(tup._2))).toSet should equal(correct)
  }

  def checkHist(correct: Seq[(Long, Long)])(op: => Seq[Measurement]) {
    val results = op
    results.map(simplify).toList should equal(correct)
    results.size should equal(correct.size)
  }

  def hmeas(uuid: UUID, v: Int, time: Long): (UUID, Long, Array[Byte]) = {
    (uuid, time, buildMeas(v, time).toByteArray)
  }

  def ins(table: Table[HistoricalValueRow], uuid: UUID, v: Int, time: Long): Unit = {
    table.insert(HistoricalValueRow(uuid, time, buildMeas(v, time).toByteArray))
  }

  /*
    slice count: 5 (4 active)
    slice width: 10

    now: 68
    absolute slice epoch
    0 - 1 - 2 - 3 - 4 - 5 - 6 - 7 - 8 -
    _ - _ - _ - 3 - 4 - 0 - 1 - _ - _ -
    current slice roles

    3 | 30 - 39
    4 | 40 - 49
    0 | 50 - 59
    1 | 60 - 69

   */

  class HistReadRig {
    val timeWidth = 10
    val tables = rotateSchema.rotation
    val store = new RotatingHistorianStore(sliceCount, timeWidth, rotateSchema.rotation)

    val now = 68

    val pA = UUID.randomUUID()
    ins(tables(3), pA, 1, 35)
    ins(tables(4), pA, 2, 43)
    ins(tables(0), pA, 3, 57)
    ins(tables(1), pA, 4, 62)
    ins(tables(2), pA, 99, 23)
    ins(tables(1), pA, 61, 13) // OUT OF WINDOW
    ins(tables(0), pA, 62, 8) // OUT OF WINDOW
    ins(tables(3), pA, 63, 84) // OUT OF WINDOW
    ins(tables(4), pA, 64, 93) // OUT OF WINDOW

    val pB = UUID.randomUUID()
    ins(tables(4), pB, 1, 43)
    ins(tables(4), pB, 2, 48)
    ins(tables(0), pB, 3, 54)
    ins(tables(0), pB, 4, 55)
    ins(tables(0), pB, 5, 57)
    ins(tables(2), pB, 99, 23)
    ins(tables(1), pB, 61, 14) // OUT OF WINDOW
    ins(tables(0), pB, 62, 7) // OUT OF WINDOW
    ins(tables(3), pB, 63, 84) // OUT OF WINDOW
    ins(tables(4), pB, 64, 93) // OUT OF WINDOW

    val pC = UUID.randomUUID()
    ins(tables(4), pC, 1, 43)
    ins(tables(4), pC, 2, 48)
    ins(tables(1), pC, 3, 64)
    ins(tables(1), pC, 4, 67)
    ins(tables(1), pC, 61, 15) // OUT OF WINDOW
    ins(tables(0), pC, 62, 6) // OUT OF WINDOW
    ins(tables(3), pC, 63, 84) // OUT OF WINDOW
    ins(tables(4), pC, 64, 93) // OUT OF WINDOW

    val uuidToName = Map(pA -> "A", pB -> "A", pC -> "C")

    def printTables(nameMap: Map[UUID, String]): Unit = {
      tables.zipWithIndex.foreach {
        case (t, i) =>
          import org.squeryl.PrimitiveTypeMode._
          logger.info(s"Table $i: " + t.where(t => true === true).toVector.map(e => (nameMap.getOrElse(e.pointId, "?"), e.time)))
      }

    }
  }

  test("basic") {
    val r = new HistReadRig

    checkHist(Seq((1, 35), (2, 43), (3, 57), (4, 62))) {
      r.store.getHistory(r.now, r.pA, None, None, 100, latest = false)
    }
    checkHist(Seq((1, 35), (2, 43), (3, 57), (4, 62))) {
      r.store.getHistory(r.now, r.pA, None, None, 100, latest = true)
    }

    // first/latest limit
    checkHist(Seq((1, 35), (2, 43))) {
      r.store.getHistory(r.now, r.pA, None, None, 2, latest = false)
    }
    checkHist(Seq((3, 57), (4, 62))) {
      r.store.getHistory(r.now, r.pA, None, None, 2, latest = true)
    }

    // restrict by time
    def timeSliceTests(l: Boolean): Unit = {
      checkHist(Seq((2, 43), (3, 57), (4, 62))) {
        r.store.getHistory(r.now, r.pA, Some(38), None, 100, latest = l)
      }
      checkHist(Seq((2, 43), (3, 57), (4, 62))) {
        r.store.getHistory(r.now, r.pA, Some(41), None, 100, latest = l)
      }
      checkHist(Seq((1, 35), (2, 43), (3, 57))) {
        r.store.getHistory(r.now, r.pA, None, Some(61), 100, latest = l)
      }
      checkHist(Seq((1, 35), (2, 43), (3, 57))) {
        r.store.getHistory(r.now, r.pA, None, Some(59), 100, latest = l)
      }
      checkHist(Seq((2, 43), (3, 57))) {
        r.store.getHistory(r.now, r.pA, Some(38), Some(61), 100, latest = l)
      }
      checkHist(Seq((2, 43), (3, 57))) {
        r.store.getHistory(r.now, r.pA, Some(41), Some(59), 100, latest = l)
      }
    }

    timeSliceTests(true)
    timeSliceTests(false)
    checkHist(Seq((2, 43), (3, 57))) {
      r.store.getHistory(r.now, r.pA, Some(38), None, 2, latest = false)
    }
    checkHist(Seq((2, 43), (3, 57))) {
      r.store.getHistory(r.now, r.pA, None, Some(59), 2, latest = true)
    }

  }

  /*
    ins(tables(4), pB, 1, 43)
    ins(tables(4), pB, 2, 48)
    ins(tables(0), pB, 3, 54)
    ins(tables(0), pB, 4, 55)
    ins(tables(0), pB, 5, 57)
    ins(tables(2), pB, 99, 23)
   */

  test("multi in slice") {
    val r = new HistReadRig

    checkHist(Seq((1, 43), (2, 48), (3, 54), (4, 55), (5, 57))) {
      r.store.getHistory(r.now, r.pB, None, None, 100, latest = false)
    }
    checkHist(Seq((1, 43), (2, 48), (3, 54), (4, 55), (5, 57))) {
      r.store.getHistory(r.now, r.pB, None, None, 100, latest = true)
    }

    checkHist(Seq((2, 48), (3, 54), (4, 55))) {
      r.store.getHistory(r.now, r.pB, Some(46), Some(56), 100, latest = false)
    }
    checkHist(Seq((2, 48), (3, 54), (4, 55))) {
      r.store.getHistory(r.now, r.pB, Some(46), Some(56), 100, latest = true)
    }

    checkHist(Seq((2, 48), (3, 54))) {
      r.store.getHistory(r.now, r.pB, Some(46), Some(56), 2, latest = false)
    }
    checkHist(Seq((3, 54), (4, 55))) {
      r.store.getHistory(r.now, r.pB, Some(46), Some(56), 2, latest = true)
    }
  }

  /*
    ins(tables(4), pC, 1, 43)
    ins(tables(4), pC, 2, 48)
    ins(tables(1), pC, 3, 64)
    ins(tables(1), pC, 4, 67)
   */

  test("slices with none") {
    val r = new HistReadRig

    checkHist(Seq((1, 43), (2, 48), (3, 64), (4, 67))) {
      r.store.getHistory(r.now, r.pC, None, None, 100, latest = false)
    }
    checkHist(Seq((1, 43), (2, 48), (3, 64), (4, 67))) {
      r.store.getHistory(r.now, r.pC, None, None, 100, latest = true)
    }

    checkHist(Seq((2, 48), (3, 64))) {
      r.store.getHistory(r.now, r.pC, Some(45), Some(66), 100, latest = false)
    }
    checkHist(Seq((2, 48), (3, 64))) {
      r.store.getHistory(r.now, r.pC, Some(45), Some(66), 100, latest = true)
    }

    checkHist(Seq((2, 48), (3, 64))) {
      r.store.getHistory(r.now, r.pC, Some(45), None, 2, latest = false)
    }
    checkHist(Seq((2, 48), (3, 64))) {
      r.store.getHistory(r.now, r.pC, None, Some(66), 2, latest = true)
    }
  }

  test("insert basic") {
    val r = new HistReadRig
    import r._

    val pR = UUID.randomUUID()
    r.store.put(now, Seq(
      hmeas(pR, 1, 66),
      hmeas(pR, 2, 67)))

    checkHist(Seq((1, 66), (2, 67))) {
      r.store.getHistory(r.now, pR, None, None, 100, latest = false)
    }
  }

  test("insert across slices") {
    val r = new HistReadRig
    import r._

    val pR = UUID.randomUUID()
    r.store.put(now, Seq(
      hmeas(pR, 1, 58),
      hmeas(pR, 2, 59),
      hmeas(pR, 3, 60),
      hmeas(pR, 4, 61),
      hmeas(pR, 5, 62),
      hmeas(pR, 6, 63)))

    //r.printTables(uuidToName ++ Map(pR -> "R"))

    checkHist(Seq((1, 58), (2, 59), (3, 60), (4, 61), (5, 62), (6, 63))) {
      r.store.getHistory(r.now, pR, None, None, 100, latest = false)
    }
  }

  test("insert time bounds") {
    val r = new HistReadRig
    import r._

    val pR = UUID.randomUUID()
    r.store.put(now, Seq(
      hmeas(pR, 1, 29),
      hmeas(pR, 2, 30),
      hmeas(pR, 3, 69),
      hmeas(pR, 4, 70),
      hmeas(pR, 5, 85)))

    checkHist(Seq((2, 30), (3, 69))) {
      val res = r.store.getHistory(r.now, pR, None, None, 100, latest = false)
      println(res)
      res
    }
  }

}
