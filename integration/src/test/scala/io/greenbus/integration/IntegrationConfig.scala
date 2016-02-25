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
import io.greenbus.loader.set.Downloader.DownloadSet
import io.greenbus.loader.set.Mdl.{ EndpointDesc, PointDesc, FlatModelFragment, EdgeDesc }
import io.greenbus.loader.set._
import io.greenbus.msg.Session

object IntegrationConfig {

  def buildAnalogPoint(prefix: String): (String, PointDesc) = {

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

    val keyValueTup = ("triggerSet", ByteArrayValue(ts.toByteArray))

    val name = prefix + "PointA"

    val desc = PointDesc(EntityFields(NamedEntId(name), Set("TypeA"), Seq(keyValueTup)), Model.PointCategory.ANALOG, "unit1", NoCalculation)

    (name, desc)
  }

  def buildStatusPoint(prefix: String): (String, PointDesc) = {

    val rangeTrigger = Trigger.newBuilder()
      .setBoolValue(false)
      .addActions(Action.newBuilder()
        .setActionName("WentAbnormal")
        .setType(Processing.ActivationType.RISING)
        .setEvent(EventGeneration.newBuilder()
          .setEventType("AbnormalEvent")))
      .build()

    val ts = TriggerSet.newBuilder().addTriggers(rangeTrigger).build()

    val keyValueTup = ("triggerSet", ByteArrayValue(ts.toByteArray))

    val name = prefix + "PointB"

    val desc = PointDesc(EntityFields(NamedEntId(name), Set("TypeA"), Seq(keyValueTup)), Model.PointCategory.STATUS, "unit1", NoCalculation)

    (name, desc)
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

  def loadFragment(flatModel: FlatModelFragment, session: Session): Unit = {

    val downloadSet = DownloadSet(Seq(), Seq(), Seq(), Seq(), Seq(), Seq())

    val ((actions, diff), idTuples) = Importer.importDiff(session, flatModel, downloadSet)

    Upload.push(session, actions, idTuples)
  }

  def buildConfigModel(prefix: String): FlatModelFragment = {

    val (pointAName, pointA) = buildAnalogPoint(prefix)
    val (pointBName, pointB) = buildStatusPoint(prefix)

    val endpointName = prefix + "Endpoint"
    val endpoint = EndpointDesc(EntityFields(NamedEntId(endpointName), Set(), Seq()), "test")

    val edges = Seq(EdgeDesc(NamedEntId(endpointName), "source", NamedEntId(pointAName)),
      EdgeDesc(NamedEntId(endpointName), "source", NamedEntId(pointBName)))

    FlatModelFragment(Seq(), Seq(pointA, pointB), Seq(), Seq(endpoint), edges)
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
