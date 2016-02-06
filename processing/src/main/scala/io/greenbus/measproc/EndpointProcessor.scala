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
package io.greenbus.measproc

import java.util.UUID

import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.client.service.proto.EventRequests.EventTemplate
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.client.service.proto.Model.{ Point, ModelUUID }
import io.greenbus.client.service.proto.Processing.{ MeasOverride, TriggerSet }
import io.greenbus.jmx.MetricsManager
import io.greenbus.measproc.pipeline.ProcessingPipeline
import io.greenbus.measproc.processing.MapCache
import io.greenbus.mstore.MeasurementValueStore

class EndpointProcessor(endpointName: String,
    points: Seq[(ModelUUID, String)],
    overrides: Seq[MeasOverride],
    triggerSets: Seq[(ModelUUID, TriggerSet)],
    store: MeasurementValueStore,
    notifications: Seq[(ModelUUID, String, Measurement)] => Unit,
    publishEvents: Seq[EventTemplate.Builder] => Unit) extends Logging {

  private val endpointMetrics = new EndpointMetrics(endpointName)
  import endpointMetrics._

  private var pointMap: PointMap = new PointMap(points)

  private val service = new ProcessorService(endpointName, metrics)

  private val pipeline = new ProcessingPipeline(
    endpointName,
    points.map(_._1.getValue),
    postMeasurements,
    getMeasurement,
    publishEvents,
    new MapCache[Boolean],
    new MapCache[(Option[Measurement], Option[Measurement])])

  overrides.foreach(pipeline.addOverride)
  triggerSets.foreach { case (uuid, ts) => pipeline.addTriggerSet(uuid, ts) }
  store.declare(points.map(_._1).map(ru => UUID.fromString(ru.getValue)))

  private def postMeasurements(mlist: Seq[(String, Measurement)]) {
    logger.trace("Posting measurements: " + mlist)
    val withIds = mlist map { case (key, m) => (UUID.fromString(key), m) }

    val count = mlist.size
    putSize(count)

    storePuts(1)
    storePutTime {
      store.put(withIds)
    }

    val measWithIds = mlist.flatMap {
      case (key, m) =>
        val modelId = ModelUUID.newBuilder().setValue(key).build()
        pointMap.idToName.get(key).map { name =>
          (modelId, name, m)
        }
    }

    notifications(measWithIds)
  }
  private def getMeasurement(key: String): Option[Measurement] = {
    currentValueGets(1)
    currentValueGetTime {
      store.get(Seq(UUID.fromString(key))).headOption.map(_._2)
    }
  }

  def modifyLastValues(measurements: Seq[(String, Measurement)]): Unit = {
    pipeline.modifyLastValues(measurements)
  }

  def handle(msg: Array[Byte], response: Array[Byte] => Unit): Unit = {
    service.handle(msg, response, pipeline.process, pointMap)
  }

  def pointSet: Seq[UUID] = {
    pointMap.all.map(tup => UUID.fromString(tup._1.getValue))
  }

  def pointAdded(point: (ModelUUID, String), measOverride: Option[MeasOverride], triggerSet: Option[TriggerSet]): Unit = {
    val pointList = pointMap.all :+ point
    store.declare(List(UUID.fromString(point._1.getValue)))
    pointMap = new PointMap(pointList)
    pipeline.updatePointsList(pointList.map(_._1.getValue))
    measOverride.foreach(pipeline.addOverride)
    triggerSet.foreach(t => pipeline.addTriggerSet(point._1, t))

    logger.debug(s"Endpoint $endpointName points updated: " + pointMap.nameToId.keySet.size)
  }

  def pointRemoved(point: ModelUUID): Unit = {
    val pointList = pointMap.all.filterNot { case (uuid, _) => uuid == point }

    pointMap = new PointMap(pointList)
    pipeline.updatePointsList(pointList.map(_._1.getValue))
    pipeline.removeOverride(point)
    pipeline.removeTriggerSet(point)
  }

  def pointModified(point: Point): Unit = {
    val pointTups = pointMap.all.filterNot { case (uuid, _) => uuid == point.getUuid } ++ Seq((point.getUuid, point.getName))
    pointMap = new PointMap(pointTups)
  }

  def overridePut(v: MeasOverride): Unit = {
    logger.debug(s"Endpoint $endpointName override added: " + pointMap.idToName.get(v.getPointUuid.getValue))
    pipeline.addOverride(v)
  }
  def overrideDeleted(v: MeasOverride): Unit = {
    logger.debug(s"Endpoint $endpointName override removed: " + pointMap.idToName.get(v.getPointUuid.getValue))
    pipeline.removeOverride(v)
  }

  def triggerSetPut(uuid: ModelUUID, v: TriggerSet): Unit = {
    logger.debug(s"Endpoint $endpointName trigger set added: " + pointMap.idToName.get(uuid.getValue))
    pipeline.addTriggerSet(uuid, v)
  }

  def triggerSetDeleted(uuid: ModelUUID): Unit = {
    logger.debug(s"Endpoint $endpointName trigger set removed: " + pointMap.idToName.get(uuid.getValue))
    pipeline.removeTriggerSet(uuid)
  }

  def register() {
    metricsMgr.register()
  }

  def unregister() {
    metricsMgr.unregister()
  }
}

class EndpointMetrics(endpointName: String) {
  val metricsMgr = MetricsManager("io.greenbus.measproc." + endpointName)
  val metrics = metricsMgr.metrics("Processor")

  val currentValueGets = metrics.counter("CurrentValueGets")
  val currentValueGetTime = metrics.timer("CurrentValueGetTime")
  val storePuts = metrics.counter("StorePuts")
  val storePutTime = metrics.timer("StorePutTime")
  val putSize = metrics.average("StorePutSize")
}

class PointMap(points: Seq[(ModelUUID, String)]) {
  val nameToId: Map[String, ModelUUID] = points.map(tup => (tup._2, tup._1)).toMap
  val idToName: Map[String, String] = points.map(tup => (tup._1.getValue, tup._2)).toMap

  def all: Seq[(ModelUUID, String)] = points
}