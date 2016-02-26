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

import io.greenbus.client.service.proto.Measurements.{ Measurement, MeasurementSamples }
import io.greenbus.mstore.MeasurementHistorySource

import scala.annotation.tailrec

object MeasurementSampling {

  val historyWindowMax = 10000

  def measToAnalogOpt(m: Measurement): Option[Double] = {
    m.getType match {
      case Measurement.Type.INT => if (m.hasIntVal) Some(m.getIntVal.toDouble) else None
      case Measurement.Type.DOUBLE => if (m.hasDoubleVal) Some(m.getDoubleVal) else None
      case _ => None
    }
  }

  def measToAnalogWithTimeOpt(m: Measurement): Option[(Double, Long)] = {
    m.getType match {
      case Measurement.Type.INT => if (m.hasIntVal) Some((m.getIntVal.toDouble, m.getTime)) else None
      case Measurement.Type.DOUBLE => if (m.hasDoubleVal) Some((m.getDoubleVal, m.getTime)) else None
      case _ => None
    }
  }

  class ResultBuilders {

    private val startTimes = Vector.newBuilder[Long]
    private val endTimes = Vector.newBuilder[Long]

    private val sampleCounts = Vector.newBuilder[Long]

    private val averages = Vector.newBuilder[Double]
    private val maximums = Vector.newBuilder[Double]
    private val minimums = Vector.newBuilder[Double]

    private val triangles = Vector.newBuilder[Double]
    private var bonusTriOpt = Option.empty[Double]

    def add(startTime: Long, endTime: Long, sampleCount: Long, average: Double, maximum: Double, minimum: Double): Unit = {

      startTimes += startTime
      endTimes += endTime
      sampleCounts += sampleCount
      averages += average
      maximums += maximum
      minimums += minimum
    }

    def addTri(v: Double): Unit = {
      triangles += v
    }
    def setLastTriAux(v: Double): Unit = {
      bonusTriOpt = Some(v)
    }

    def result(): MeasurementSamples = {
      val b = MeasurementSamples.newBuilder()

      startTimes.result().foreach(b.addTimeStart)
      endTimes.result().foreach(b.addTimeEnd)
      sampleCounts.result().foreach(b.addSampleCount)
      averages.result().foreach(b.addAverage)
      maximums.result().foreach(b.addMaximum)
      minimums.result().foreach(b.addMinimum)

      val tris = triangles.result()
      bonusTriOpt.map(v => tris :+ v).getOrElse(tris).foreach(b.addVisuallySignificant)

      b.build()
    }

  }

  def integratedAverage(windowStart: Long, windowEnd: Long, prevOpt: Option[Double], valuesAndTimes: IndexedSeq[(Double, Long)]): Double = {
    val set = prevOpt match {
      case None => valuesAndTimes
      case Some(v) =>
        (v, windowStart) +: valuesAndTimes
    }
    integratedAverage(windowEnd, set)
  }

  def integratedAverage(windowEnd: Long, valuesAndTimes: IndexedSeq[(Double, Long)]): Double = {
    val count = valuesAndTimes.size
    var integral = 0.0
    Range(0, count).foreach { i =>
      val (v, startTime) = valuesAndTimes(i)
      val nextTime = if ((i + 1) < count) valuesAndTimes(i + 1)._2 else windowEnd
      //println(s"Adding: $v, $nextTime - $startTime")
      integral += (v * (nextTime - startTime))
    }

    val globalStart = valuesAndTimes.head._2

    integral / (windowEnd - globalStart)
  }

  def addSample(b: ResultBuilders, windowStart: Long, windowEnd: Long, state: RollingState, measurements: IndexedSeq[Measurement]): RollingState = {

    //println(s"built! $windowStart -> $windowEnd")

    val valuesAndTimes = measurements.flatMap(measToAnalogWithTimeOpt)

    val (firstValue, lastValue, avg) = if (valuesAndTimes.nonEmpty) {

      val count = valuesAndTimes.size

      val mean = integratedAverage(windowStart, windowEnd, state.prevOpt, valuesAndTimes)

      val sortedValues = valuesAndTimes.map(_._1).sorted

      //println("Building: " + valuesAndTimes)

      val firstTime = valuesAndTimes.head._2

      val inWindowMax = sortedValues.last
      val max = state.prevOpt match {
        case None => inWindowMax
        case Some(prev) =>
          if (firstTime > windowStart && inWindowMax < prev) prev else inWindowMax
      }

      val inWindowMin = sortedValues.head
      val min = state.prevOpt match {
        case None => inWindowMin
        case Some(prev) =>
          if (firstTime > windowStart && inWindowMin > prev) prev else inWindowMin
      }

      b.add(
        startTime = windowStart,
        endTime = windowEnd,
        sampleCount = count,
        average = mean,
        maximum = max,
        minimum = min)

      (valuesAndTimes.head._1, valuesAndTimes.last._1, mean)

    } else {

      val v = state.prevOpt getOrElse 0.0

      b.add(
        startTime = windowStart,
        endTime = windowEnd,
        sampleCount = 0,
        average = v,
        maximum = v,
        minimum = v)

      (v, v, v)
    }

    val valuesAndTimesToCache = if (valuesAndTimes.isEmpty) {
      IndexedSeq((lastValue, windowEnd))
    } else {
      valuesAndTimes
    }

    (state.leftTriOpt, state.midValuesAndTimesOpt) match {
      case (None, _) =>
        b.addTri(firstValue)
        val midTime = ((windowEnd - windowStart) / 2) + windowStart
        RollingState(Some(lastValue), Some((avg, midTime)), None)

      case (Some(leftTri), None) =>
        b.setLastTriAux(lastValue)
        RollingState(Some(lastValue), Some(leftTri), Some(valuesAndTimesToCache))

      case (Some(leftTri), Some(midValuesAndTimes)) =>
        // 3 points, left, mid, right. We are currently right. We use the old left value and the current average (right) to
        // evaluate points we kept around from the previous (mid) window to see which triplet forms the triangle with the greatest
        // area
        val rx = ((windowEnd - windowStart) / 2) + windowStart
        val ry = avg

        val (ly, lx) = leftTri

        val triPoint = midValuesAndTimes.maxBy {
          case (my, mx) =>
            Math.abs(((mx - lx) * (ry - ly)) - ((my - ly) * (rx - lx))) /* / 2.0 */ // no need
        }

        b.addTri(triPoint._1)
        b.setLastTriAux(lastValue)

        RollingState(Some(lastValue), Some(triPoint), Some(valuesAndTimesToCache))
    }
  }

  case class RollingState(prevOpt: Option[Double], leftTriOpt: Option[(Double, Long)], midValuesAndTimesOpt: Option[IndexedSeq[(Double, Long)]])

  def sampleRecur2(history: MeasurementHistorySource, pointId: UUID, queryStartTime: Long, queryEndTime: Long, timeWindowLength: Long, windowMax: Int): MeasurementSamples = {

    var pullTime = 0L
    var consumeTime = 0L
    var buildTime = 0L

    val builders = new ResultBuilders

    @tailrec
    def consume(windowStart: Long, state: RollingState, rest: IndexedSeq[Measurement], nothingLeft: Boolean): (IndexedSeq[Measurement], RollingState, Long, Long) = {
      val nextWindowStart = windowStart + timeWindowLength

      if (windowStart < queryEndTime) {

        //println(s"consume: $windowStart -> ${nextWindowStart - 1}")

        val (inWindow, other) = rest.partition(_.getTime < nextWindowStart)

        //println("inWindow: " + inWindow.map(_.getTime))

        if (other.nonEmpty || nothingLeft) {

          val buildStart = System.nanoTime()
          val nextState = addSample(builders, windowStart, nextWindowStart, state, inWindow)
          buildTime += (System.nanoTime() - buildStart)

          //println(s"building: $windowStart -> ${nextWindowStart - 1}")

          if (other.nonEmpty || (nothingLeft && nextWindowStart <= queryEndTime)) {
            consume(nextWindowStart, nextState, other, nothingLeft)
          } else {
            // we believe we are completely finished
            //println(s" : totallyfinished??? : $nextWindowStart")

            (Vector(), nextState, nextWindowStart, nextWindowStart)
          }
        } else {
          val lastTime = if (inWindow.nonEmpty) {
            inWindow.last.getTime + 1
          } else {
            windowStart
          }
          // we need more measurements to complete this window
          //println(s" : not enough windowStart: $windowStart")
          (inWindow, state, windowStart, lastTime)
        }

      } else {

        (Vector(), state, nextWindowStart, nextWindowStart)

      }
    }

    @tailrec
    def pull(windowStart: Long, pageStart: Long, state: RollingState, leftovers: IndexedSeq[Measurement]): Unit = {

      //println(s"Query: $pageStart - $queryEndTime, $windowMax, from $windowStart")

      val pullStart = System.nanoTime()
      val queryResults = history.getHistory(pointId, Some(pageStart - 1), Some(queryEndTime - 1), windowMax, latest = false)
      pullTime += (System.nanoTime() - pullStart)

      //println(s"QueryResults: " + queryResults.size)
      //println(s"Queried: " + queryResults.map(_.getTime))

      if (queryResults.size < windowMax) {
        val consumeStart = System.nanoTime()
        consume(windowStart, state, leftovers ++ queryResults, nothingLeft = true)
        consumeTime += (System.nanoTime() - consumeStart)

      } else {

        val consumeStart = System.nanoTime()
        val (remains, nextState, nextWindowStart, nextPageStart) = consume(windowStart, state, leftovers ++ queryResults, nothingLeft = false)
        consumeTime += (System.nanoTime() - consumeStart)

        //println(s"nextWindowStart: $nextWindowStart")
        pull(nextWindowStart, nextPageStart, nextState, remains)
      }
    }

    if (timeWindowLength <= 0) {
      throw new IllegalArgumentException("Must have positive, non-zero time window length")
    }
    if (windowMax <= 0) {
      throw new IllegalArgumentException("Must have positive, non-zero query page size")
    }

    pull(queryStartTime, queryStartTime, RollingState(None, None, None), Vector())

    println(s"PullTime: ${pullTime / 1000000.0}")
    println(s"ConsumeTime: ${consumeTime / 1000000.0}")
    println(s"BuildTime: ${buildTime / 1000000.0}")

    builders.result()
  }
}
