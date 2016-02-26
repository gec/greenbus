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

import java.util.UUID
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.sql.DbConnection
import org.squeryl.PrimitiveTypeMode._
import MeasurementStoreSchema._
import io.greenbus.mstore.{ MeasurementHistorySource, MeasurementValueStore, MeasurementValueSource }

trait MeasurementStore {
  def get(ids: Seq[UUID]): Seq[(UUID, Measurement)]
  def put(entries: Seq[(UUID, Measurement)])
}

class SqlCurrentValueAndHistorian(sql: DbConnection) extends MeasurementValueStore with MeasurementHistorySource {

  def declare(points: Seq[UUID]) {
    sql.inTransaction {
      CurrentValueOperations.declare(points)
    }
  }

  def put(entries: Seq[(UUID, Measurement)]) {

    val withTime: Seq[(UUID, Long, Array[Byte])] =
      entries.map {
        case (id, meas) =>
          val time = if (meas.hasTime) meas.getTime else System.currentTimeMillis()
          (id, time, meas.toByteArray)
      }

    val withoutTime = withTime.map(tup => (tup._1, tup._3))

    sql.inTransaction {
      CurrentValueOperations.put(withoutTime)
      HistoricalValueOperations.put(withTime)
    }
  }

  def get(ids: Seq[UUID]): Seq[(UUID, Measurement)] = {
    sql.inTransaction {
      CurrentValueOperations.get(ids)
    }
  }

  def getHistory(id: UUID, begin: Option[Long], end: Option[Long], limit: Int, latest: Boolean): Seq[Measurement] = {
    sql.inTransaction {
      HistoricalValueOperations.getHistory(id, begin, end, limit, latest)
    }
  }
}

// Used in services that already set up a squeryl transaction
object SimpleInTransactionCurrentValueStore extends MeasurementValueSource {
  def get(ids: Seq[UUID]): Seq[(UUID, Measurement)] = {
    CurrentValueOperations.get(ids)
  }
}
object SimpleInTransactionHistoryStore extends MeasurementHistorySource {
  def getHistory(id: UUID, begin: Option[Long], end: Option[Long], limit: Int, latest: Boolean): Seq[Measurement] = {
    HistoricalValueOperations.getHistory(id, begin, end, limit, latest)
  }
}

object CurrentValueOperations {

  def declare(points: Seq[UUID]) {
    val distinctPoints = points.distinct
    val existing =
      from(currentValues)(v =>
        where(v.id in distinctPoints)
          select (v.id)).toSet

    val inserts = distinctPoints.filterNot(existing.contains).map(CurrentValueRow(_, None))
    currentValues.insert(inserts)
  }

  def put(entries: Seq[(UUID, Array[Byte])]) {
    val rows = entries.map {
      case (id, bytes) => CurrentValueRow(id, Some(bytes))
    }

    currentValues.forceUpdate(rows)
  }

  def get(ids: Seq[UUID]): Seq[(UUID, Measurement)] = {
    val results =
      from(currentValues)(cv =>
        where((cv.id in ids) and (cv.bytes isNotNull))
          select (cv.id, cv.bytes)).toSeq

    results.map {
      case (id, bytes) => (id, Measurement.parseFrom(bytes.get))
    }
  }
}

object HistoricalValueOperations {

  def put(entries: Seq[(UUID, Long, Array[Byte])]) {
    val rows = entries.map {
      case (id, time, bytes) => HistoricalValueRow(id, time, bytes)
    }

    historicalValues.insert(rows)
  }

  def getHistory(id: UUID, begin: Option[Long], end: Option[Long], limit: Int, latest: Boolean): Seq[Measurement] = {

    import org.squeryl.dsl.ast.{ OrderByArg, ExpressionNode }
    def timeOrder(time: ExpressionNode) = {
      if (!latest) {
        new OrderByArg(time).asc
      } else {
        new OrderByArg(time).desc
      }
    }

    // Switch ordering to get either beginning of the window or end of the window
    val bytes: Seq[Array[Byte]] =
      from(historicalValues)(hv =>
        where(hv.pointId === id and
          (hv.time gt begin.?) and
          (hv.time lte end.?))
          select (hv.bytes)
          orderBy (timeOrder(hv.time))).page(0, limit).toSeq

    val inOrder = if (!latest) bytes else bytes.reverse

    inOrder.map(Measurement.parseFrom).toVector
  }
}

