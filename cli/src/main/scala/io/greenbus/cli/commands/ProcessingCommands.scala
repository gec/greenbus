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
package io.greenbus.cli.commands

import io.greenbus.cli.{ CliContext, Command }
import io.greenbus.client.service.proto.Measurements.{ Quality, Measurement }
import io.greenbus.client.service.proto.ModelRequests.EntityKeySet
import io.greenbus.client.service.proto.Processing.MeasOverride
import io.greenbus.client.service.{ MeasurementService, ModelService, ProcessingService }

import scala.concurrent.Await
import scala.concurrent.duration._

class MeasBlockCommand extends Command[CliContext] {

  val commandName = "meas:block"
  val description = "Block measurement from being updated"

  val measName = strings.arg("measurement name", "Measurement name")

  protected def execute(context: CliContext) {

    val modelClient = ModelService.client(context.session)
    val pointFut = modelClient.getPoints(EntityKeySet.newBuilder().addNames(measName.value).build())
    val pointResults = Await.result(pointFut, 5000.milliseconds)

    if (pointResults.isEmpty) {
      throw new IllegalArgumentException(s"Point '${measName.value}' not found")
    }

    val pointUuid = pointResults.head.getUuid

    val procClient = ProcessingService.client(context.session)

    val overs = MeasOverride.newBuilder()
      .setPointUuid(pointUuid)
      .build()

    Await.result(procClient.putOverrides(Seq(overs)), 5000.milliseconds)

    println("Success")
  }
}

class MeasUnblockCommand extends Command[CliContext] {

  val commandName = "meas:unblock"
  val description = "Unblock measurement"

  val measName = strings.arg("measurement name", "Measurement name")

  protected def execute(context: CliContext) {

    val modelClient = ModelService.client(context.session)
    val pointFut = modelClient.getPoints(EntityKeySet.newBuilder().addNames(measName.value).build())
    val pointResults = Await.result(pointFut, 5000.milliseconds)

    if (pointResults.isEmpty) {
      throw new IllegalArgumentException(s"Point '${measName.value}' not found")
    }

    val pointUuid = pointResults.head.getUuid

    val procClient = ProcessingService.client(context.session)

    Await.result(procClient.deleteOverrides(Seq(pointUuid)), 5000.milliseconds)

    println("Success")

  }
}

class MeasReplaceCommand extends Command[CliContext] {

  val commandName = "meas:replace"
  val description = "Block measurement and replace with manual value"

  val measName = strings.arg("measurement name", "Measurement name")

  val boolVal = ints.option(Some("b"), Some("bool-val"), "Boolean value [0, 1]")

  val intVal = ints.option(Some("i"), Some("int-val"), "Integer value")

  val floatVal = doubles.option(Some("f"), Some("float-val"), "Floating point value")

  val stringVal = strings.option(Some("s"), Some("string-val"), "String value")

  val isTest = optionSwitch(Some("t"), Some("test"), "Replace is a test value")

  protected def execute(context: CliContext) {

    List(boolVal.value, intVal.value, floatVal.value, stringVal.value).flatten.size match {
      case 0 => throw new IllegalArgumentException("Must include at least one value option")
      case 1 =>
      case n => throw new IllegalArgumentException("Must include only one value option")
    }

    def reinitializeBuilder(b: Measurement.Builder) {
      if (boolVal.value.nonEmpty) {
        if (boolVal.value.get == 0) {
          b.setBoolVal(false).setType(Measurement.Type.BOOL)
        } else if (boolVal.value.get == 1) {
          b.setBoolVal(true).setType(Measurement.Type.BOOL)
        } else {
          throw new IllegalArgumentException("Boolean values must be 0 or 1")
        }
      } else if (intVal.value.nonEmpty) {
        b.setIntVal(intVal.value.get).setType(Measurement.Type.INT)
      } else if (floatVal.value.nonEmpty) {
        b.setDoubleVal(floatVal.value.get).setType(Measurement.Type.DOUBLE)
      } else if (stringVal.value.nonEmpty) {
        b.setStringVal(stringVal.value.get).setType(Measurement.Type.STRING)
      } else {
        throw new IllegalArgumentException("Must include at least one value option")
      }
    }

    val modelClient = ModelService.client(context.session)
    val pointFut = modelClient.getPoints(EntityKeySet.newBuilder().addNames(measName.value).build())
    val pointResults = Await.result(pointFut, 5000.milliseconds)

    if (pointResults.isEmpty) {
      throw new IllegalArgumentException(s"Point '${measName.value}' not found")
    }

    val pointUuid = pointResults.head.getUuid

    val measClient = MeasurementService.client(context.session)

    val measFut = measClient.getCurrentValues(Seq(pointUuid))

    val measResults = Await.result(measFut, 5000.milliseconds)

    val measBuilder = if (measResults.isEmpty) {
      Measurement.newBuilder().setQuality(Quality.newBuilder().build()).setTime(System.currentTimeMillis())
    } else {
      measResults.head.getValue.toBuilder
    }

    reinitializeBuilder(measBuilder)

    val procClient = ProcessingService.client(context.session)

    val overBuilder = MeasOverride.newBuilder()
      .setPointUuid(pointUuid)
      .setMeasurement(measBuilder.build())

    if (isTest.value) {
      overBuilder.setTestValue(true)
    }

    val overs = overBuilder.build()

    Await.result(procClient.putOverrides(Seq(overs)), 5000.milliseconds)

    println("Success")
  }
}
