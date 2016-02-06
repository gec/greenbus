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
package io.greenbus.mstore.sql

import io.greenbus.sql.test.{ RunTestsInsideTransaction, DatabaseUsingTestBase }
import java.util.UUID
import io.greenbus.client.service.proto.Measurements.{ Quality, Measurement }
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SqlCurrentValueAndHistorianTest extends DatabaseUsingTestBase with RunTestsInsideTransaction {

  def schemas = List(MeasurementStoreSchema)

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
    results.size should equal(correct.size)
    results.map(simplify).toList should equal(correct)
  }

  test("Current value") {

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    val point01 = UUID.randomUUID()
    val point02 = UUID.randomUUID()

    mstore.declare(Seq(point01, point02))

    mstore.get(Seq(point01)).size should equal(0)

    mstore.put(Seq((point01, buildMeas(1, 44))))

    check(Set((point01, (1, 44)))) {
      mstore.get(Seq(point01))
    }

    mstore.put(Seq((point02, buildMeas(2, 55))))

    check(Set((point02, (2, 55)))) {
      mstore.get(Seq(point02))
    }
    check(Set((point01, (1, 44)), (point02, (2, 55)))) {
      mstore.get(Seq(point01, point02))
    }

    mstore.put(Seq((point01, buildMeas(3, 33)), (point02, buildMeas(4, 66))))

    check(Set((point01, (3, 33)), (point02, (4, 66)))) {
      mstore.get(Seq(point01, point02))
    }
  }

  test("Historical values") {

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    val point01 = UUID.randomUUID()
    val point02 = UUID.randomUUID()

    mstore.getHistory(point01, None, None, 100, false).size should equal(0)

    mstore.put(Seq((point01, buildMeas(100, 1))))

    checkHist(Seq((100, 1))) {
      mstore.getHistory(point01, None, None, 100, false)
    }

    mstore.put(Seq((point01, buildMeas(101, 2))))
    mstore.put(Seq((point02, buildMeas(200, 1))))
    mstore.put(Seq((point01, buildMeas(102, 3))))
    mstore.put(Seq((point02, buildMeas(201, 2))))
    mstore.put(Seq((point01, buildMeas(104, 4))))

    checkHist(Seq((100, 1), (101, 2), (102, 3), (104, 4))) {
      mstore.getHistory(point01, None, None, 100, false)
    }
    checkHist(Seq((200, 1), (201, 2))) {
      mstore.getHistory(point02, None, None, 100, false)
    }
  }

  test("Historical paging") {

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    val point01 = UUID.randomUUID()

    mstore.put(Seq((point01, buildMeas(100, 1))))
    mstore.put(Seq((point01, buildMeas(101, 2))))
    mstore.put(Seq((point01, buildMeas(102, 3))))
    mstore.put(Seq((point01, buildMeas(103, 4))))
    mstore.put(Seq((point01, buildMeas(104, 5))))
    mstore.put(Seq((point01, buildMeas(105, 6))))

    checkHist(Seq((100, 1), (101, 2))) {
      mstore.getHistory(point01, None, None, 2, false)
    }
    checkHist(Seq((102, 3), (103, 4))) {
      mstore.getHistory(point01, Some(2), None, 2, false)
    }
    checkHist(Seq((104, 5), (105, 6))) {
      mstore.getHistory(point01, Some(4), None, 2, false)
    }
  }

  test("Historical paging backwards") {

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    val point01 = UUID.randomUUID()

    mstore.put(Seq((point01, buildMeas(100, 1))))
    mstore.put(Seq((point01, buildMeas(101, 2))))
    mstore.put(Seq((point01, buildMeas(102, 3))))
    mstore.put(Seq((point01, buildMeas(103, 4))))
    mstore.put(Seq((point01, buildMeas(104, 5))))
    mstore.put(Seq((point01, buildMeas(105, 6))))

    // window end is >=, otherwise we lose when multi per ms
    checkHist(Seq((104, 5), (105, 6))) {
      mstore.getHistory(point01, None, None, 2, true)
    }
    checkHist(Seq((102, 3), (103, 4), (104, 5))) {
      mstore.getHistory(point01, None, Some(5), 3, true)
    }
    checkHist(Seq((100, 1), (101, 2), (102, 3))) {
      mstore.getHistory(point01, None, Some(3), 3, true)
    }
  }

  test("Historical limit justification") {

    val mstore = new SqlCurrentValueAndHistorian(dbConnection)

    val point01 = UUID.randomUUID()

    mstore.put(Seq((point01, buildMeas(100, 1))))
    mstore.put(Seq((point01, buildMeas(101, 2))))
    mstore.put(Seq((point01, buildMeas(102, 3))))
    mstore.put(Seq((point01, buildMeas(103, 4))))
    mstore.put(Seq((point01, buildMeas(104, 5))))
    mstore.put(Seq((point01, buildMeas(105, 6))))

    checkHist(Seq((100, 1), (101, 2))) {
      mstore.getHistory(point01, None, None, 2, false)
    }
    checkHist(Seq((104, 5), (105, 6))) {
      mstore.getHistory(point01, None, None, 2, true)
    }
    checkHist(Seq((102, 3), (103, 4))) {
      mstore.getHistory(point01, Some(2), Some(5), 2, false)
    }
    checkHist(Seq((103, 4), (104, 5))) {
      mstore.getHistory(point01, Some(2), Some(5), 2, true)
    }
  }

}
