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

import io.greenbus.calc.CalcConfigException
import io.greenbus.calc.lib.eval.{ ValueRange, _ }
import io.greenbus.client.service.proto.Calculations.CalculationDescriptor
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.client.service.proto.Model.ModelUUID

import scala.collection.JavaConversions._

object CalculationEvaluator {

  def containerToOpValue(mc: MeasContainer): OperationValue = {
    mc match {
      case SingleMeas(m) => MeasurementConverter.convertMeasurement(m)
      case MeasRange(seq) => ValueRange(seq map MeasurementConverter.convertMeasurement)
    }
  }

  def containerToSeq(mc: MeasContainer): Seq[Measurement] = {
    mc match {
      case SingleMeas(m) => Seq(m)
      case MeasRange(seq) => seq
    }
  }

  def build(calc: CalculationDescriptor, operations: OperationSource): CalculationEvaluator = {

    val expr = if (calc.hasFormula) {
      OperationParser.parseFormula(calc.getFormula)
    } else {
      throw new CalcConfigException("Need formula in calculation config")
    }

    val formula = Formula(expr, operations)

    val qualInputStrat = if (calc.hasTriggeringQuality && calc.getTriggeringQuality.hasStrategy) {
      QualityInputStrategy.build(calc.getTriggeringQuality.getStrategy)
    } else {
      throw new CalcConfigException("Need quality input strategy in calculation config")
    }

    val qualOutputStrat = if (calc.hasQualityOutput && calc.getQualityOutput.hasStrategy) {
      QualityOutputStrategy.build(calc.getQualityOutput.getStrategy)
    } else {
      throw new CalcConfigException("Need quality output strategy in calculation config")
    }

    val varNameToPointMap = calc.getCalcInputsList.map { in =>
      if (!in.hasPointUuid) {
        throw new CalcConfigException("Input must contain point uuid")
      }
      if (!in.hasVariableName) {
        throw new CalcConfigException("Input must contain variable name")
      }

      (in.getVariableName, in.getPointUuid)
    }.toMap

    new CalculationEvaluator(formula, varNameToPointMap, qualInputStrat, qualOutputStrat)
  }
}

class CalculationEvaluator(formula: Formula,
    varNameToPointMap: Map[String, ModelUUID],
    qualInputStrategy: QualityInputStrategy,
    qualOutputStrategy: QualityOutputStrategy) {

  import io.greenbus.calc.lib.CalculationEvaluator._

  def evaluate(inputs: Map[ModelUUID, MeasContainer]): Option[Measurement] = {

    // Only calculate if all inputs are present and nonempty
    val filteredInputs: Option[Map[ModelUUID, MeasContainer]] =
      qualInputStrategy.checkInputs(inputs)

    val variablesOpt: Option[Map[String, MeasContainer]] =
      filteredInputs.flatMap(mapPointsToVars)

    variablesOpt.map { variableMap =>

      val inputOpValues = variableMap.mapValues(containerToOpValue)

      val result = formula.evaluate(new MappedVariableSource(inputOpValues))

      val inputMeases = variableMap.mapValues(containerToSeq)

      val qual = qualOutputStrategy.getQuality(inputMeases)

      val time = TimeStrategy.getTime(inputMeases)

      MeasurementConverter.convertOperationValue(result)
        .setQuality(qual)
        .setTime(time)
        .build()
    }
  }

  private def mapPointsToVars(inputs: Map[ModelUUID, MeasContainer]): Option[Map[String, MeasContainer]] = {

    if (varNameToPointMap.values.forall(inputs.contains)) {
      val toVarNames = varNameToPointMap.map {
        case (varName, pointUuid) => (varName, inputs(pointUuid))
      }
      Some(toVarNames)
    } else {
      None
    }
  }
}

