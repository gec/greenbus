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
package io.greenbus.integration

import io.greenbus.client.service.proto.EventRequests.EventConfigTemplate
import io.greenbus.client.service.proto.Events.EventConfig
import io.greenbus.client.service.proto.Measurements.Quality
import io.greenbus.client.service.proto.Processing._
import io.greenbus.client.service.proto.{ Model, Processing }
import io.greenbus.loader.set.Actions._
import io.greenbus.loader.set.Mdl.EdgeDesc
import io.greenbus.loader.set.{ NamedEntId, ByteArrayValue, Upload }
import io.greenbus.msg.Session

object IntegrationConfig {

  def buildAnalogPoint(prefix: String, cache: ActionCache): String = {

    val name = prefix + "PointA"
    cache.pointPuts += PutPoint(None, name, Set("TypeA"), Model.PointCategory.ANALOG, "unit1")

    val rangeTrigger = Trigger.newBuilder()
      .setAnalogLimit(AnalogLimit.newBuilder()
        .setUpperLimit(2.0d)
        .setLowerLimit(1.0d)
        .build())
      .addActions(Action.newBuilder()
        .setActionName("WentOutOfRange")
        .setType(Processing.ActivationType.RISING)
        .setEvent(EventGeneration.newBuilder()
          .setEventType("OutOfRangeEvent")))
      .addActions(Action.newBuilder()
        .setActionName("MakeItQuestionable")
        .setType(Processing.ActivationType.RISING)
        .setQualityAnnotation(Quality.newBuilder()
          .setValidity(Quality.Validity.QUESTIONABLE)))
      .addActions(Action.newBuilder()
        .setActionName("WentBackInRange")
        .setType(Processing.ActivationType.FALLING)
        .setEvent(EventGeneration.newBuilder()
          .setEventType("BackInRangeEvent")))
      .build()

    val ts = TriggerSet.newBuilder().addTriggers(rangeTrigger).build()

    cache.keyValuePutByNames += PutKeyValueByName(name, "triggerSet", ByteArrayValue(ts.toByteArray))

    name
  }

  def buildStatusPoint(prefix: String, cache: ActionCache): String = {

    val name = prefix + "PointB"
    cache.pointPuts += PutPoint(None, name, Set("TypeA"), Model.PointCategory.STATUS, "unit1")

    val rangeTrigger = Trigger.newBuilder()
      .setBoolValue(false)
      .addActions(Action.newBuilder()
        .setActionName("WentAbnormal")
        .setType(Processing.ActivationType.RISING)
        .setEvent(EventGeneration.newBuilder()
          .setEventType("AbnormalEvent")))
      .build()

    val ts = TriggerSet.newBuilder().addTriggers(rangeTrigger).build()

    cache.keyValuePutByNames += PutKeyValueByName(name, "triggerSet", ByteArrayValue(ts.toByteArray))

    name
  }

  def eventConfigs(): Seq[EventConfigTemplate] = {

    List(EventConfigTemplate.newBuilder()
      .setEventType("OutOfRangeEvent")
      .setDesignation(EventConfig.Designation.EVENT)
      .setSeverity(6)
      .setResource("out of range")
      .build(),
      EventConfigTemplate.newBuilder()
        .setEventType("AbnormalEvent")
        .setDesignation(EventConfig.Designation.EVENT)
        .setSeverity(7)
        .setResource("abnormal")
        .build())
  }

  def loadActions(actionSet: ActionsList, session: Session): Unit = {
    Upload.push(session, actionSet, Seq())
  }

  def buildConfig(prefix: String): ActionsList = {

    val cache = new ActionCache

    val pointAName = buildAnalogPoint(prefix, cache)
    val pointBName = buildStatusPoint(prefix, cache)

    val endpointName = prefix + "Endpoint"
    cache.endpointPuts += PutEndpoint(None, endpointName, Set(), "test")

    cache.edgePuts += PutEdge(EdgeDesc(NamedEntId(endpointName), "source", NamedEntId(pointAName)))
    cache.edgePuts += PutEdge(EdgeDesc(NamedEntId(endpointName), "source", NamedEntId(pointBName)))

    cache.result()
  }

  def buildFilterTrigger(): TriggerSet = {
    TriggerSet.newBuilder()
      .addTriggers(Trigger.newBuilder()
        .setFilter(Filter.newBuilder().setType(Filter.FilterType.DUPLICATES_ONLY))
        .addActions(Action.newBuilder()
          .setActionName("suppress")
          .setSuppress(true)
          .setType(Processing.ActivationType.LOW)
          .build()))
      .build()
  }

  def buildOffsetTrigger(offset: Double): TriggerSet = {
    TriggerSet.newBuilder()
      .addTriggers(Trigger.newBuilder()
        .setAnalogLimit(AnalogLimit.newBuilder()
          .setLowerLimit(1.2d)
          .build())
        .addActions(Action.newBuilder()
          .setActionName("offsetaction")
          .setType(Processing.ActivationType.HIGH)
          .setLinearTransform(
            LinearTransform.newBuilder()
              .setScale(1.0)
              .setOffset(offset)
              .build())
          .build())
        .build())
      .build()
  }

  def buildUnconditionalOffsetTrigger(offset: Double): TriggerSet = {
    TriggerSet.newBuilder()
      .addTriggers(Trigger.newBuilder()
        .addActions(Action.newBuilder()
          .setActionName("offsetaction2")
          .setType(Processing.ActivationType.HIGH)
          .setLinearTransform(
            LinearTransform.newBuilder()
              .setScale(1.0)
              .setOffset(offset)
              .build())
          .build())
        .build())
      .build()
  }
}
