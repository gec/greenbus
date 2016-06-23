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

import scala.annotation.tailrec
import scala.collection.mutable

object RotatingStoreSchema {
  def tableName(i: Int): String = "history_" + i
}
class RotatingStoreSchema(count: Int, withIndexes: Boolean = true) extends Schema {
  import RotatingStoreSchema._
  import org.squeryl.PrimitiveTypeMode._

  val rotation: Vector[Table[HistoricalValueRow]] = {
    Range(0, count).map { i =>
      val currentTable = table[HistoricalValueRow](tableName(i))
      if (withIndexes) {
        on(currentTable)(s => declare(columns(s.pointId.~, s.time) are (indexed)))
      }
      currentTable
    }.toVector
  }

  def createIndexesOnly(): Unit = {
    createColumnGroupConstraintsAndIndexes
  }
}
class IndividualizedRotatingStoreSchema(index: Int, withIndex: Boolean = true) extends Schema {
  import RotatingStoreSchema._
  import org.squeryl.PrimitiveTypeMode._

  val indexTable = table[HistoricalValueRow](tableName(index))

  if (withIndex) {
    on(indexTable)(s => declare(columns(s.pointId.~, s.time) are (indexed)))
  }
}

object RotatingHistorianStore {

  def widthOfWindow(sliceCount: Int, sliceDurationMs: Long): Long = {
    (sliceCount - 1) * sliceDurationMs
  }

  def limitOfCurrentWindow(now: Long, sliceCount: Int, sliceDurationMs: Long): Long = {

    if (sliceCount < 3) throw new IllegalArgumentException("Must have more than 2 slices (2 active, 1 dead)")

    val startOfActiveSlice = startOfLatestActiveSlice(now, sliceCount, sliceDurationMs)

    val numberOfArchivedSlices = sliceCount - 2 // 1 is current, 1 is "dead"

    startOfActiveSlice - numberOfArchivedSlices * sliceDurationMs
  }

  def indexOfSliceForTime(time: Long, sliceCount: Int, sliceDurationMs: Long): Int = {

    val numberOfSlicesSinceEpochToTime = time / sliceDurationMs

    (numberOfSlicesSinceEpochToTime % sliceCount).toInt
  }

  def indexOfSliceForTimeWithBeginTime(time: Long, sliceCount: Int, sliceDurationMs: Long): (Int, Long) = {

    val numberOfSlicesSinceEpochToTime = time / sliceDurationMs

    val beginTime = numberOfSlicesSinceEpochToTime * sliceDurationMs

    ((numberOfSlicesSinceEpochToTime % sliceCount).toInt, beginTime)
  }

  private def startOfLatestActiveSlice(now: Long, sliceCount: Int, sliceDurationMs: Long): Long = {

    val numberOfSlicesSinceEpochToNow = now / sliceDurationMs

    numberOfSlicesSinceEpochToNow * sliceDurationMs
  }

  def earliestValidSliceIndexAndBeginTime(now: Long, sliceCount: Int, sliceDurationMs: Long): (Int, Long) = {

    if (sliceCount < 3) throw new IllegalArgumentException("Must have more than 2 slices (2 active, 1 dead)")

    val (indexOfLatest, latestBeginTime) = indexOfSliceForTimeWithBeginTime(now, sliceCount, sliceDurationMs)

    val indexOfEarliest = (indexOfLatest + 2) % sliceCount

    val numberOfArchivedSlices = sliceCount - 2 // 1 is current, 1 is "dead"

    val beginOfEarliest = latestBeginTime - (numberOfArchivedSlices * sliceDurationMs)

    (indexOfEarliest, beginOfEarliest)
  }

  type MeasTuple = (UUID, Long, Array[Byte])

  def splitIntoSlices(entries: Seq[MeasTuple], now: Long, sliceCount: Int, sliceDurationMs: Long): Vector[(Int, Vector[MeasTuple])] = {

    val sliceInserts = Array.ofDim[Option[mutable.Builder[MeasTuple, Vector[MeasTuple]]]](sliceCount)
    Range(0, sliceCount).foreach(i => sliceInserts(i) = None)

    entries.foreach {
      case tup =>
        val store = indexOfSliceForTime(tup._2, sliceCount, sliceDurationMs)
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

  def put(now: Long, entries: Seq[(UUID, Long, Array[Byte])]): Unit = {

    val limitOfWindow = limitOfCurrentWindow(now, sliceCount, sliceDurationMs)
    val rightLimitOfWindow = limitOfWindow + widthOfWindow(sliceCount, sliceDurationMs)

    val splitItems = splitIntoSlices(entries, now, sliceCount, sliceDurationMs)

    splitItems.foreach {
      case (i, vec) =>
        val rows = vec.flatMap {
          case (uuid, time, bytes) =>
            if (time < limitOfWindow || time >= rightLimitOfWindow) {
              logger.warn("Measurement for " + uuid + " had time earlier or later than the active window")
              None
            } else {
              Some(HistoricalValueRow(uuid, time, bytes))
            }
        }
        tables(i).insert(rows)
    }
  }

  def getHistory(now: Long, id: UUID, begin: Option[Long], end: Option[Long], limit: Int, latest: Boolean): Seq[Measurement] = {

    val (nowSliceIndex, nowSliceBeginTime) = indexOfSliceForTimeWithBeginTime(now, sliceCount, sliceDurationMs)
    val invalidSliceIndex = (nowSliceIndex + 1) % sliceCount

    val leftLimitOfWindow = limitOfCurrentWindow(now, sliceCount, sliceDurationMs)
    val rightLimitOfWindow = leftLimitOfWindow + (sliceCount - 1) * sliceDurationMs

    if (begin.exists(_ >= rightLimitOfWindow) || end.exists(_ < leftLimitOfWindow)) {
      Seq()
    } else if (latest) {

      val (firstValidSlice, firstValidBeginTime) = end match {
        case None =>
          (nowSliceIndex, nowSliceBeginTime)
        case Some(rightLimitOfQuery) =>
          indexOfSliceForTimeWithBeginTime(rightLimitOfQuery, sliceCount, sliceDurationMs)
      }

      rightToLeftQuery(Vector(), firstValidSlice, firstValidBeginTime, invalidSliceIndex, id, begin, end, limit)

    } else {

      val (firstValidSlice, firstValidBeginTime) = begin match {
        case None =>
          earliestValidSliceIndexAndBeginTime(now, sliceCount, sliceDurationMs)
        case Some(leftLimitOfQuery) =>
          if (leftLimitOfQuery < leftLimitOfWindow) {
            earliestValidSliceIndexAndBeginTime(now, sliceCount, sliceDurationMs)
          } else {
            indexOfSliceForTimeWithBeginTime(leftLimitOfQuery, sliceCount, sliceDurationMs)
          }
      }

      leftToRightQuery(Vector(), firstValidSlice, firstValidBeginTime, invalidSliceIndex, id, begin, end, limit)
    }

  }

  @tailrec
  private def rightToLeftQuery(found: Vector[Measurement],
    currentSliceIndex: Int,
    currentSliceBegin: Long,
    invalidSliceIndex: Int,
    id: UUID,
    begin: Option[Long],
    end: Option[Long],
    limit: Int): Seq[Measurement] = {

    import org.squeryl.PrimitiveTypeMode._

    val remaining = limit - found.size

    val table = tables(currentSliceIndex)

    val bytes: Seq[Array[Byte]] =
      from(table)(hv =>
        where(hv.pointId === id and
          (hv.time gt begin.?) and
          (hv.time lte end.?))
          select (hv.bytes)
          orderBy (hv.time).desc).page(0, remaining).toSeq

    val measResults = bytes.reverse.map(Measurement.parseFrom).toVector

    val allResults = measResults ++ found

    val nextSliceIndex = (currentSliceIndex + sliceCount - 1) % sliceCount
    val beginWasInThisWindow = begin.exists(_ > currentSliceBegin)

    if (allResults.size < limit && nextSliceIndex != invalidSliceIndex && !beginWasInThisWindow) {
      rightToLeftQuery(allResults, nextSliceIndex, currentSliceBegin + sliceDurationMs, invalidSliceIndex, id, begin, end, limit)
    } else {
      allResults
    }
  }

  @tailrec
  private def leftToRightQuery(found: Vector[Measurement],
    currentSliceIndex: Int,
    currentSliceBegin: Long,
    invalidSliceIndex: Int,
    id: UUID,
    begin: Option[Long],
    end: Option[Long],
    limit: Int): Seq[Measurement] = {

    import org.squeryl.PrimitiveTypeMode._

    val remaining = limit - found.size

    val table = tables(currentSliceIndex)

    val bytes: Seq[Array[Byte]] =
      from(table)(hv =>
        where(hv.pointId === id and
          (hv.time gt begin.?) and
          (hv.time lte end.?))
          select (hv.bytes)
          orderBy (hv.time).asc).page(0, remaining).toSeq

    val measResults = bytes.map(Measurement.parseFrom).toVector

    val allResults = found ++ measResults

    val nextSliceIndex = (currentSliceIndex + 1) % sliceCount
    val endWasInThisWindow = end.exists(_ < currentSliceBegin + sliceDurationMs)

    if (allResults.size < limit && nextSliceIndex != invalidSliceIndex && !endWasInThisWindow) {
      leftToRightQuery(allResults, nextSliceIndex, currentSliceBegin + sliceDurationMs, invalidSliceIndex, id, begin, end, limit)
    } else {
      allResults
    }
  }

}

class InTransactionRotatingHistorySource(historianStore: RotatingHistorianStore) extends MeasurementHistorySource {
  override def getHistory(id: UUID, begin: Option[Long], end: Option[Long], limit: Int, latest: Boolean): Seq[Measurement] = {
    val now = System.currentTimeMillis()
    historianStore.getHistory(now, id, begin, end, limit, latest)
  }
}

class RotatingHistorian(sql: DbConnection, historianStore: RotatingHistorianStore) extends MeasurementValueStore with MeasurementHistorySource {

  def getHistory(id: UUID, begin: Option[Long], end: Option[Long], limit: Int, latest: Boolean): Seq[Measurement] = {
    val now = System.currentTimeMillis()
    sql.inTransaction {
      historianStore.getHistory(now, id, begin, end, limit, latest)
    }
  }

  def put(entries: Seq[(UUID, Measurement)]): Unit = {
    val withTime: Seq[(UUID, Long, Array[Byte])] =
      entries.map {
        case (id, meas) =>
          val time = if (meas.hasTime) meas.getTime else System.currentTimeMillis()
          (id, time, meas.toByteArray)
      }

    val withoutTime = withTime.map(tup => (tup._1, tup._3))

    val now = System.currentTimeMillis()

    sql.inTransaction {
      CurrentValueOperations.put(withoutTime)
      historianStore.put(now, withTime)
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