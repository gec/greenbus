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
package io.greenbus.calc.lib.eval

trait Formula {
  def evaluate(inputs: VariableSource): OperationValue
}

class AccumulatedFormula(var lastValue: OperationValue, formula: Formula) extends Formula {

  def evaluate(inputs: VariableSource) = {
    val incrementalValue = formula.evaluate(inputs)
    val result = OperationValue.combine(lastValue, incrementalValue)
    lastValue = result
    result
  }
}

object Formula {
  def apply(expr: Expression, opSource: OperationSource) = {
    new BasicFormulaEvaluator(expr, opSource)
  }

  class BasicFormulaEvaluator(expr: Expression, opSource: OperationSource) extends Formula {

    private val prepared = expr.prepare(opSource)

    def evaluate(inputs: VariableSource): OperationValue = {
      prepared.evaluate(inputs)
    }
  }
}
