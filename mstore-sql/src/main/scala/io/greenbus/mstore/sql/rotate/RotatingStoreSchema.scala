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

import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.mstore.{ MeasurementHistorySource, MeasurementValueStore }
import io.greenbus.mstore.sql.{ CurrentValueOperations, HistoricalValueRow }
import io.greenbus.sql.DbConnection
import org.squeryl.{ Table, Schema }

import scala.collection.mutable

object RotatingStoreSchema {
  def tableName(i: Int): String = "history_" + i
}
class RotatingStoreSchema(count: Int) extends Schema {
  import RotatingStoreSchema._

  val rotation: Vector[Table[HistoricalValueRow]] = {
    Range(0, count).map { i =>
      table[HistoricalValueRow](tableName(i))
    }.toVector
  }
}

object RotatingHistorianStore {

  def limitOfCurrentWindow(now: Long, sliceCount: Int, sliceDurationMs: Long): Long = {

    if (sliceCount < 3) throw new IllegalArgumentException("Must have more than 2 slices (2 active, 1 dead)")

    val numberOfSlicesSinceEpochToNow = now / sliceDurationMs

    val startOfActiveSlice = numberOfSlicesSinceEpochToNow * sliceDurationMs

    val numberOfArchivedSlices = sliceCount - 2 // 1 is current, 1 is "dead"

    startOfActiveSlice - numberOfArchivedSlices * sliceDurationMs
  }

  def storeForTime(time: Long, sliceCount: Int, sliceDurationMs: Long): Int = {

    val numberOfSlicesSinceEpochToTime = time / sliceDurationMs

    (numberOfSlicesSinceEpochToTime % sliceCount).toInt
  }

  type MeasTuple = (UUID, Long, Array[Byte])

  def splitIntoSlices(entries: Seq[MeasTuple], now: Long, sliceCount: Int, sliceDurationMs: Long): Vector[(Int, Vector[MeasTuple])] = {

    val sliceInserts = Array.ofDim[Option[mutable.Builder[MeasTuple, Vector[MeasTuple]]]](sliceCount)
    Range(0, sliceCount).foreach(i => sliceInserts(i) = None)

    entries.foreach {
      case tup =>
        val store = storeForTime(tup._2, sliceCount, sliceDurationMs)
        sliceInserts(store) match {
          case None =>
            val vb = Vector.newBuilder[MeasTuple]
            vb += tup
            sliceInserts(store) = Some(vb)
          case Some(vb) =>
            vb += tup
        }
    }

    val storesAndVecs: Vector[(Int, Vector[MeasTuple])] = sliceInserts.zipWithIndex.flatMap {
      case (Some(b), i) => Some((i, b.result()))
      case _ => None
    }.toVector

    storesAndVecs
  }

}
class RotatingHistorianStore(sliceCount: Int, sliceDurationMs: Long, tables: Vector[Table[HistoricalValueRow]]) extends Logging {
  import RotatingHistorianStore._

  def put(entries: Seq[(UUID, Long, Array[Byte])]): Unit = {

    val now = System.currentTimeMillis()

    val limitOfWindow = limitOfCurrentWindow(now, sliceCount, sliceDurationMs)

    val splitItems = splitIntoSlices(entries, now, sliceCount, sliceDurationMs)

    splitItems.foreach {
      case (i, vec) =>
        val rows = vec.flatMap {
          case (uuid, time, bytes) =>
            if (time < limitOfWindow) {
              logger.warn("Measurement for " + uuid + " had time older than the active window")
              None
            } else {
              Some(HistoricalValueRow(uuid, time, bytes))
            }
        }
        tables(i).insert(rows)
    }
  }

  def getHistory(id: UUID, begin: Option[Long], end: Option[Long], limit: Int, latest: Boolean): Seq[Measurement] = ???

}

class RotatingHistorian(sql: DbConnection, historianStore: RotatingHistorianStore) extends MeasurementValueStore with MeasurementHistorySource {

  def getHistory(id: UUID, begin: Option[Long], end: Option[Long], limit: Int, latest: Boolean): Seq[Measurement] = ???

  def put(entries: Seq[(UUID, Measurement)]): Unit = {
    val withTime: Seq[(UUID, Long, Array[Byte])] =
      entries.map {
        case (id, meas) =>
          val time = if (meas.hasTime) meas.getTime else System.currentTimeMillis()
          (id, time, meas.toByteArray)
      }

    val withoutTime = withTime.map(tup => (tup._1, tup._3))

    sql.inTransaction {
      CurrentValueOperations.put(withoutTime)
      historianStore.put(withTime)
    }
  }

  def declare(points: Seq[UUID]): Unit = {
    sql.inTransaction {
      CurrentValueOperations.declare(points)
    }
  }

  def get(ids: Seq[UUID]): Seq[(UUID, Measurement)] = {
    sql.inTransaction {
      CurrentValueOperations.get(ids)
    }
  }
}