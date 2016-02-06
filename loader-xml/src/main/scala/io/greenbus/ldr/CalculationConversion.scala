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
package io.greenbus.ldr

import java.util.UUID

import io.greenbus.ldr.xml._
import io.greenbus.client.service.proto.Calculations
import io.greenbus.client.service.proto.Calculations._
import io.greenbus.loader.set.{ LoadingException, EntityId }

import scala.collection.JavaConversions._

object CalculationConversion {

  def toProto(calc: xml.Calculation): (CalculationDescriptor.Builder, Seq[(EntityId, CalculationInput.Builder)]) = {

    val (inputs, inputStratOpt) = if (calc.isSetInputs) {
      val inputs = calc.getInputs

      val stratOpt = if (inputs.isSetInputQualityStrategy) {
        Some(inputStratToProto(inputs.getInputQualityStrategy))
      } else {
        None
      }

      val ins = inputs.getSingleOrMulti.map { inputType =>

        val nameOpt = if (inputType.isSetPointName) Some(inputType.getPointName) else None
        val uuidOpt = if (inputType.isSetPointUuid) Some(inputType.getPointUuid) else None
        if (nameOpt.isEmpty && uuidOpt.isEmpty) throw new LoadingXmlException("Reference must have name or UUID", inputType)

        if (nameOpt.isEmpty && uuidOpt.isEmpty) {
          throw new LoadingXmlException("Calculation input must include name or UUID or both", inputType)
        }

        val entId = EntityId(uuidOpt.map(UUID.fromString), nameOpt)

        val variable = if (inputType.isSetVariable) inputType.getVariable else throw new LoadingXmlException("Calculation missing variable name", inputType)

        val b = CalculationInput.newBuilder().setVariableName(variable)
        inputType match {
          case single: xml.Single =>
            b.setSingle(
              SingleMeasurement.newBuilder().setStrategy(
                SingleMeasurement.MeasurementStrategy.MOST_RECENT))
          case multi: xml.Multi =>
            val fromOpt = if (multi.isSetFrom) Some(multi.getFrom) else None
            val limitOpt = if (multi.isSetLimit) Some(multi.getLimit) else None
            val sinceLast = if (multi.isSetSinceLastPublish) Some(multi.getSinceLastPublish) else None

            val range = MeasurementRange.newBuilder()
            fromOpt.foreach(range.setFromMs)
            limitOpt.foreach(range.setLimit)
            sinceLast.foreach(range.setSinceLast)
            b.setRange(range)
        }

        (entId, b)
      }

      (ins, stratOpt)
    } else {
      (Seq(), None)
    }

    if (!(calc.isSetFormula && calc.getFormula.isSetValue)) {
      throw new LoadingXmlException("Calculation must include formula", calc)
    }

    val form = calc.getFormula.getValue

    val strat = if (calc.isSetTriggering) {
      val trigXml = calc.getTriggering
      if (trigXml.isSetUpdateOnAnyChange) {
        TriggerStrategy.newBuilder()
          .setUpdateAny(trigXml.getUpdateOnAnyChange)
          .build()
      } else if (trigXml.isSetUpdateEveryPeriodInMilliseconds) {
        TriggerStrategy.newBuilder()
          .setPeriodMs(trigXml.getUpdateEveryPeriodInMilliseconds)
          .build()
      } else {
        throw new LoadingXmlException("Calculation missing valid trigger strategy", calc)
      }
    } else {
      throw new LoadingXmlException("Calculation missing valid trigger strategy", calc)
    }

    val qualOutput = if (calc.isSetOutput && calc.getOutput.isSetOutputQualityStrategy) {
      val protoOutputStrat = outputStratToProto(calc.getOutput.getOutputQualityStrategy)
      OutputQuality.newBuilder().setStrategy(protoOutputStrat)
    } else {
      OutputQuality.newBuilder().setStrategy(Calculations.OutputQuality.Strategy.WORST_QUALITY)
    }

    val builder = CalculationDescriptor.newBuilder()
      .setFormula(form)
      .setTriggering(strat)
      .setQualityOutput(qualOutput)

    inputStratOpt.foreach(strat => builder.setTriggeringQuality(InputQuality.newBuilder().setStrategy(strat)))

    (builder, inputs)
  }

  def toXml(calc: CalculationDescriptor, uuidToNameMap: Map[UUID, String]): xml.Calculation = {

    val inputs = new Inputs
    calc.getCalcInputsList.map(in => inputToXml(in, uuidToNameMap)).foreach(inputs.getSingleOrMulti.add)
    val inputQualStrat = if (calc.hasTriggeringQuality) calc.getTriggeringQuality else throw new LoadingException("Calculation missing input quality")
    inputs.setInputQualityStrategy(inputStratToXml(inputQualStrat.getStrategy))

    val exprString = if (calc.hasFormula) calc.getFormula else throw new LoadingException("Calculation missing formula")
    val formula = new Formula
    formula.setValue(exprString)

    val triggeringElem = new Triggering
    val triggeringProto = if (calc.hasTriggering) calc.getTriggering else throw new LoadingException("Calculation missing triggering")
    if (triggeringProto.hasPeriodMs) {
      triggeringElem.setUpdateEveryPeriodInMilliseconds(triggeringProto.getPeriodMs)
    } else if (triggeringProto.hasUpdateAny) {
      triggeringElem.setUpdateOnAnyChange(true)
    }

    val qualityOutput = if (calc.hasQualityOutput) calc.getQualityOutput else throw new LoadingException("Calculation quality output")
    val outputElem = new Output
    outputElem.setOutputQualityStrategy(outputStratToXml(qualityOutput.getStrategy))

    val elem = new xml.Calculation
    elem.setInputs(inputs)
    elem.setFormula(formula)
    elem.setTriggering(triggeringElem)
    elem.setOutput(outputElem)

    elem
  }

  private def inputToXml(input: CalculationInput, uuidToNameMap: Map[UUID, String]) = {
    val uuid = if (input.hasPointUuid) input.getPointUuid else throw new LoadingException("No uuid for calculation input")
    val variable = if (input.hasVariableName) input.getVariableName else throw new LoadingException("No variable name for calculation input")

    if (input.hasSingle) {

      val elem = new xml.Single
      elem.setVariable(variable)
      elem.setPointUuid(uuid.getValue)
      uuidToNameMap.get(UUID.fromString(uuid.getValue)).foreach(elem.setPointName)
      elem

    } else if (input.hasRange) {

      val range = input.getRange

      val elem = new Multi
      elem.setVariable(variable)
      elem.setPointUuid(uuid.getValue)
      uuidToNameMap.get(UUID.fromString(uuid.getValue)).foreach(elem.setPointName)

      val fromMsOpt = if (range.hasFromMs) Some(range.getFromMs) else None
      val limitOpt = if (range.hasLimit) Some(range.getLimit) else None

      val sinceLast = range.hasSinceLast && range.getSinceLast

      fromMsOpt.foreach(elem.setFrom)
      limitOpt.foreach(elem.setLimit)
      elem.setSinceLastPublish(sinceLast)

      elem

    } else {
      throw new LoadingException("Calculation input has no single/multi configuration")
    }
  }

  private def outputStratToXml(outputStrat: Calculations.OutputQuality.Strategy): OutputQualityEnum = {
    outputStrat match {
      case Calculations.OutputQuality.Strategy.ALWAYS_OK => OutputQualityEnum.ALWAYS_OK
      case Calculations.OutputQuality.Strategy.WORST_QUALITY => OutputQualityEnum.WORST_QUALITY
    }
  }
  private def outputStratToProto(outputStrat: OutputQualityEnum): Calculations.OutputQuality.Strategy = {
    outputStrat match {
      case OutputQualityEnum.ALWAYS_OK => Calculations.OutputQuality.Strategy.ALWAYS_OK
      case OutputQualityEnum.WORST_QUALITY => Calculations.OutputQuality.Strategy.WORST_QUALITY
    }
  }

  private def inputStratToXml(inputStrat: Calculations.InputQuality.Strategy): InputQualityEnum = {
    inputStrat match {
      case Calculations.InputQuality.Strategy.ACCEPT_ALL => InputQualityEnum.ACCEPT_ALL
      case Calculations.InputQuality.Strategy.ONLY_WHEN_ALL_OK => InputQualityEnum.ONLY_WHEN_ALL_OK
      case Calculations.InputQuality.Strategy.REMOVE_BAD_AND_CALC => InputQualityEnum.REMOVE_BAD_AND_CALC
    }
  }
  private def inputStratToProto(inputStrat: InputQualityEnum): Calculations.InputQuality.Strategy = {
    inputStrat match {
      case InputQualityEnum.ACCEPT_ALL => Calculations.InputQuality.Strategy.ACCEPT_ALL
      case InputQualityEnum.ONLY_WHEN_ALL_OK => Calculations.InputQuality.Strategy.ONLY_WHEN_ALL_OK
      case InputQualityEnum.REMOVE_BAD_AND_CALC => Calculations.InputQuality.Strategy.REMOVE_BAD_AND_CALC
    }
  }
}
