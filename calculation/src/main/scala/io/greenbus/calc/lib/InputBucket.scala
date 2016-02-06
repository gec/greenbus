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
package io.greenbus.calc.lib

import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.calc.lib.eval.{ ValueRange, OperationValue }
import io.greenbus.client.service.proto.Calculations.CalculationInput
import scala.collection.immutable.Queue
import io.greenbus.client.service.proto.Calculations.SingleMeasurement.MeasurementStrategy

// Difference between singular input and collection that happens to have length 1
sealed trait MeasContainer
case class MeasRange(range: Seq[Measurement]) extends MeasContainer
case class SingleMeas(m: Measurement) extends MeasContainer

sealed trait MeasRequest
case object SingleLatest extends MeasRequest
case class MultiSince(from: Long, limit: Int) extends MeasRequest
case class MultiLimit(count: Int) extends MeasRequest
case class SincePublishing(count: Int) extends MeasRequest

trait InputBucket {
  def added(m: Measurement): InputBucket
  def snapshot(): (Option[MeasContainer], InputBucket)

  def added(seq: Seq[Measurement]): InputBucket = {
    seq.foldLeft(this) { (b, m) => b.added(m) }
  }
}

object InputBucket {

  def build(calc: CalculationInput): InputBucket = {
    import io.greenbus.util.Optional._

    val limitOpt = for {
      range <- optGet(calc.hasRange, calc.getRange)
      limit <- optGet(range.hasLimit, range.getLimit)
    } yield limit

    val limit = limitOpt.getOrElse(100)

    if (calc.hasSingle && calc.getSingle.hasStrategy) {
      calc.getSingle.getStrategy match {
        case MeasurementStrategy.MOST_RECENT => new SingleLatestBucket(None)
        case x => throw new Exception("Unknown single measurement strategy: " + x)
      }
    } else if (calc.hasRange && calc.getRange.hasFromMs) {
      new FromRangeBucket(Queue.empty[Measurement], SystemTimeSource, calc.getRange.getFromMs, limit)
    } else if (calc.hasRange && calc.getRange.hasLimit) {
      new LimitRangeBucket(Queue.empty[Measurement], limit)
    } else if (calc.hasRange && calc.getRange.hasSinceLast && calc.getRange.getSinceLast) {
      new NoStorageBucket(Queue.empty[Measurement], limit)
    } else {
      throw new Exception("Cannot build input from configuration")
    }
  }

  class FromRangeBucket(queue: Queue[Measurement], timeSource: TimeSource, from: Long, limit: Int, minimum: Int = 1) extends InputBucket {

    private def copy(q: Queue[Measurement]) = {
      new FromRangeBucket(q, timeSource, from, limit, minimum)
    }

    private def prune(current: Queue[Measurement]): Queue[Measurement] = {
      val horizon = timeSource.now + from

      val extra = current.size - limit
      val trimmed = if (extra > 0) {
        current.drop(extra)
      } else {
        current
      }

      trimmed.dropWhile(m => m.getTime <= horizon)
    }

    def added(m: Measurement): InputBucket = {
      val q = queue.enqueue(m)
      new FromRangeBucket(prune(q), timeSource, from, limit)
    }

    def snapshot(): (Option[MeasContainer], InputBucket) = {
      val pruned = prune(queue)

      val bucket = if (pruned == queue) this else copy(pruned)

      val opVal = if (pruned.nonEmpty && pruned.size >= minimum) {
        Some(MeasRange(pruned))
      } else {
        None
      }

      (opVal, bucket)
    }
  }

  class LimitRangeBucket(queue: Queue[Measurement], limit: Int, minimum: Int = 1) extends InputBucket {

    private def copy(q: Queue[Measurement]) = {
      new LimitRangeBucket(q, limit, minimum)
    }

    def added(m: Measurement): InputBucket = {
      val q = queue.enqueue(m)
      val extra = q.size - limit
      val trimmed = if (extra > 0) {
        q.drop(extra)
      } else {
        q
      }
      copy(trimmed)
    }

    def snapshot(): (Option[MeasContainer], InputBucket) = {
      val opVal = if (queue.nonEmpty && queue.size >= minimum) {
        Some(MeasRange(queue))
      } else {
        None
      }

      (opVal, this)
    }
  }

  case class NoStorageBucket(queue: Queue[Measurement], limit: Int) extends InputBucket {

    def added(m: Measurement): InputBucket = {
      var q = queue.enqueue(m)
      while (q.size > limit) {
        val (_, temp) = q.dequeue
        q = temp
      }
      copy(q)
    }

    def snapshot(): (Option[MeasContainer], InputBucket) = {
      val opVal = if (queue.nonEmpty) {
        Some(MeasRange(queue))
      } else {
        None
      }

      (opVal, copy(queue = Queue.empty[Measurement]))
    }
  }

  class SingleLatestBucket(meas: Option[Measurement]) extends InputBucket {

    def added(m: Measurement): InputBucket = {
      new SingleLatestBucket(Some(m))
    }

    def snapshot(): (Option[MeasContainer], InputBucket) = {
      (meas.map(SingleMeas), this)
    }
  }
}
