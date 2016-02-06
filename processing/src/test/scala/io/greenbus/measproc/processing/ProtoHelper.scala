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
package io.greenbus.measproc.processing

import io.greenbus.client.service.proto.Model.{ ModelUUID, Entity }
import io.greenbus.client.service.proto.Measurements.{ Quality, DetailQual, Measurement }
import io.greenbus.client.service.proto.Processing.{ TriggerSet, Trigger => ProtoTrigger, Action => ProtoAction, MeasOverride }

object ProtoHelper {

  def makeAnalog(value: Double, time: Long = System.currentTimeMillis): Measurement = {
    val m = Measurement.newBuilder
    m.setTime(time)
    m.setSystemTime(time)
    m.setType(Measurement.Type.DOUBLE)
    m.setDoubleVal(value)
    m.setQuality(makeNominalQuality)
    m.build
  }

  def makeInt(value: Long, time: Long = System.currentTimeMillis): Measurement = {
    Measurement.newBuilder
      .setTime(time)
      .setSystemTime(time)
      .setType(Measurement.Type.INT)
      .setIntVal(value)
      .setQuality(makeNominalQuality)
      .build

  }
  def makeBool(value: Boolean, time: Long = System.currentTimeMillis): Measurement = {
    Measurement.newBuilder
      .setTime(time)
      .setSystemTime(time)
      .setType(Measurement.Type.BOOL)
      .setBoolVal(value)
      .setQuality(makeNominalQuality)
      .build

  }

  def makeString(value: String, time: Long = System.currentTimeMillis): Measurement = {
    Measurement.newBuilder
      .setTime(time)
      .setSystemTime(time)
      .setType(Measurement.Type.STRING)
      .setStringVal(value)
      .setQuality(makeNominalQuality)
      .build
  }

  def makeNominalQuality() = {
    Quality.newBuilder.setDetailQual(DetailQual.newBuilder).build
  }

  def makeAbnormalQuality() = {
    Quality.newBuilder.setDetailQual(DetailQual.newBuilder).setValidity(Quality.Validity.INVALID).build
  }

  def updateQuality(m: Measurement, q: Quality): Measurement = {
    m.toBuilder.setQuality(q).build
  }

  def makeNodeById(nodeId: String): Entity = {
    Entity.newBuilder.setUuid(ModelUUID.newBuilder.setValue(nodeId)).build
  }

  def makeNodeById(nodeId: ModelUUID): Entity = {
    Entity.newBuilder.setUuid(nodeId).build
  }

  def makeNodeByName(name: String): Entity = {
    Entity.newBuilder.setName(name).build
  }

  def makeNIS(key: String) = {
    MeasOverride.newBuilder.setPointUuid(ModelUUID.newBuilder().setValue(key)).build
  }
  def makeOverride(key: String, value: Double): MeasOverride = {
    MeasOverride.newBuilder
      .setPointUuid(ModelUUID.newBuilder().setValue(key))
      .setMeasurement(Measurement.newBuilder
        .setTime(85)
        .setSystemTime(85)
        .setType(Measurement.Type.DOUBLE)
        .setDoubleVal(value)
        .setQuality(Quality.getDefaultInstance))
      .build
  }

  def fakeUuid(str: String) = ModelUUID.newBuilder().setValue(str).build()

  def triggerSet: TriggerSet = {
    TriggerSet.newBuilder
      .addTriggers(triggerRlcLow("meas01"))
      .addTriggers(triggerTransformation("meas01"))
      .build
  }
  def triggerRlcLow(measName: String): ProtoTrigger = {
    import io.greenbus.client.service.proto.Processing._
    ProtoTrigger.newBuilder
      .setTriggerName("rlclow")
      .setStopProcessingWhen(ActivationType.HIGH)
      .setAnalogLimit(AnalogLimit.newBuilder.setLowerLimit(0).setDeadband(5))
      .addActions(
        ProtoAction.newBuilder
          .setActionName("strip")
          .setType(ActivationType.HIGH)
          .setStripValue(true))
      .addActions(
        ProtoAction.newBuilder
          .setActionName("qual")
          .setType(ActivationType.HIGH)
          .setQualityAnnotation(Quality.newBuilder.setValidity(Quality.Validity.QUESTIONABLE)))
      .addActions(
        ProtoAction.newBuilder
          .setActionName("eventrise")
          .setType(ActivationType.RISING)
          .setEvent(EventGeneration.newBuilder.setEventType("event01")))
      .addActions(
        ProtoAction.newBuilder
          .setActionName("eventfall")
          .setType(ActivationType.FALLING)
          .setEvent(EventGeneration.newBuilder.setEventType("event02")))
      .build
  }
  def triggerTransformation(measName: String): ProtoTrigger = {
    import io.greenbus.client.service.proto.Processing._
    ProtoTrigger.newBuilder
      .setTriggerName("trans")
      .addActions(
        ProtoAction.newBuilder
          .setActionName("linear")
          .setType(ActivationType.HIGH)
          .setLinearTransform(LinearTransform.newBuilder.setScale(10).setOffset(50000)))
      .build
  }
}
