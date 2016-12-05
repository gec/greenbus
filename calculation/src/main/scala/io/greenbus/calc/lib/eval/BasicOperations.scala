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

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.calc.lib.eval.OperationPatterns._

object BasicOperations {

  def getSource = {
    new BasicOperationSource(List(
      (List("SUM", "+"), () => new Sum),
      (List("-"), () => new Subtract),
      (List("PRODUCT", "*"), () => new Product),
      (List("QUOTIENT", "/"), () => new Divide),
      (List("POWER", "^"), () => new Power),
      (List("AVERAGE"), () => new Average),
      (List("MAX"), () => new Max),
      (List("MIN"), () => new Min),
      (List("ABS"), () => new Abs),
      (List("GT"), () => new Greater),
      (List("GTE"), () => new Gte),
      (List("LT"), () => new Less),
      (List("LTE"), () => new Lte),
      (List("EQ"), () => new Eq),
      (List("SQRT"), () => new SquareRoot),
      (List("COUNT"), () => new Count),
      (List("AND"), () => new And),
      (List("OR"), () => new Or),
      (List("NOT"), () => new Not),
      (List("IF"), () => new If),
      (List("LATEST"), () => new Latest),
      (List("INTEGRATE"), () => new Integrate)))
  }

  class Sum extends MultiNumericOperation {
    def eval(args: List[Double]): Double = {
      args.foldLeft(0.0) { _ + _ }
    }
  }

  class Subtract extends PairNumericOperation {
    def eval(l: Double, r: Double): Double = { l - r }
  }

  class Product extends MultiNumericOperation {
    def eval(args: List[Double]): Double = {
      args.reduceLeft(_ * _)
    }
  }

  class Divide extends PairNumericOperation {
    def eval(l: Double, r: Double): Double = { l / r }
  }

  class Power extends PairNumericOperation {
    def eval(l: Double, r: Double): Double = { math.pow(l, r) }
  }

  class Average extends MultiNumericOperation {
    def eval(args: List[Double]): Double = {
      args.foldLeft(0.0) { _ + _ } / args.size
    }
  }

  class Max extends MultiNumericOperation {
    def eval(args: List[Double]): Double = {
      args.foldLeft(Option.empty[Double]) { (result, value) =>
        result match {
          case Some(last) => Some(if (last > value) last else value)
          case None => Some(value)
        }
      }.get
    }
  }

  class Min extends MultiNumericOperation {
    def eval(args: List[Double]): Double = {
      args.foldLeft(Option.empty[Double]) { (result, value) =>
        result match {
          case Some(last) => Some(if (last < value) last else value)
          case None => Some(value)
        }
      }.get
    }
  }

  class Abs extends SingleNumericOperation {
    def eval(v: Double): Double = math.abs(v)
  }

  class Greater extends ConditionalPairNumericOperation {
    def eval(l: Double, r: Double) = l > r
  }

  class Gte extends ConditionalPairNumericOperation {
    def eval(l: Double, r: Double) = l >= r
  }

  class Less extends ConditionalPairNumericOperation {
    def eval(l: Double, r: Double) = l < r
  }

  class Lte extends ConditionalPairNumericOperation {
    def eval(l: Double, r: Double) = l <= r
  }

  class Eq extends ConditionalPairNumericOperation {
    def eval(l: Double, r: Double) = l == r
  }

  class SquareRoot extends SingleNumericOperation {
    def eval(v: Double): Double = math.sqrt(v)
  }

  class Not extends SingleBooleanOperation {
    def eval(arg: Boolean) = !arg
  }

  class And extends BooleanReduceOperation {
    def eval(args: List[Boolean]) = {
      args.foldLeft(true) { case (out, v) => out && v }
    }
  }

  class Or extends BooleanReduceOperation {
    def eval(args: List[Boolean]) = {
      args.foldLeft(false) { case (out, v) => out || v }
    }
  }

  class Count extends BooleanFoldOperation {
    def eval(args: List[Boolean]) = {
      args.foldLeft(0) { case (sum, v) => sum + (if (v) 1 else 0) }
    }
  }

  class If extends AbstractOperation {
    def apply(args: List[OperationValue]): OperationValue = {
      args match {
        case List(condition, consequent, alternative) => {
          condition match {
            case BooleanValue(conditionResult) => {
              if (conditionResult) consequent else alternative
            }
            case _ => throw new EvalException("If statement condition must be a boolean expression.")
          }
        }
        case _ => throw new EvalException("If statement must take three arguments: IF(condition, value if true, value if false)")
      }
    }
  }

  class Integrate extends AccumulatedNumericOperation with LazyLogging {
    def eval(initialValue: AccumulatedValue, args: List[NumericMeas]) = {
      args.foldLeft(initialValue.copy(value = 0)) {
        case (state, meas) =>
          state.lastMeas match {
            case Some(NumericMeas(v, t)) =>

              val time = meas.time - t
              if (time < 0) {
                logger.warn("Measurements out of order. new: " + meas.time + " previous: " + t + " delta: " + time)
                state
              } else {
                val area = if (time > 0) ((meas.doubleValue + v) * time) / 2
                else 0
                state.copy(lastMeas = Some(meas), value = state.value + area)
              }
            case None =>
              state.copy(lastMeas = Some(meas))
          }
      }
    }
  }

  class Latest extends AbstractOperation {
    def apply(args: List[OperationValue]): OperationValue = {
      val measurements = args.flatMap {
        case m: MeasuredValue => Some(m)
        case _ => None
      }

      if (measurements.nonEmpty) {
        measurements.maxBy(_.time)
      } else {
        throw new EvalException("Latest function must have measurements as inputs")
      }
    }

  }
}
