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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers

@RunWith(classOf[JUnitRunner])
class OperationIntegrationTest extends FunSuite with ShouldMatchers {

  def parseFormula(f: String): Formula = {
    Formula(OperationParser.parseFormula(f), BasicOperations.getSource)
  }

  test("Constant value") {
    val f = "8.443"
    val expr = parseFormula(f)
    val result = NumericConst(8.443)

    expr.evaluate(new ValueMap(Map())) should equal(result)
  }

  test("Simple math") {
    val f = "5 * 2"
    val expr = parseFormula(f)
    val result = NumericConst(10.0)

    expr.evaluate(new ValueMap(Map())) should equal(result)
  }

  test("Average") {
    val f = "B + AVERAGE(A)"
    val expr = parseFormula(f)
    val result = NumericConst(11.5)

    val doubleValues = Map("B" -> 1.5, "A" -> ValueRange(List(NumericConst(5.0), NumericConst(10.0), NumericConst(15.0))))
    expr.evaluate(new ValueMap(doubleValues)) should equal(result)

    // show that math works when we pass in Longs and cast them up to doubles
    val longValues = Map("B" -> 1.5, "A" -> ValueRange(List(LongConst(5), LongConst(10), LongConst(15))))
    expr.evaluate(new ValueMap(longValues)) should equal(result)
  }

  test("Numeric MAX") {

    val tests = List(
      (5.0, List(5.0)),
      (10.0, List(10.0, 5.0)),
      (-10.0, List(-10.0, -50.0)),
      (50.0, List(50.0, 49.9, 49.8)))

    testNumeric("MAX(A)", tests)
  }

  test("Numeric MIN") {

    val tests = List(
      (5.0, List(5.0)),
      (5.0, List(10.0, 5.0)),
      (-50.0, List(-10.0, -50.0)),
      (49.8, List(50.0, 49.9, 49.8)))

    testNumeric("MIN(A)", tests)
  }

  test("Numeric ABS") {

    val tests = List(
      (5.0, List(5.0)),
      (5.0, List(-5.0)))

    testNumeric("ABS(A)", tests)
  }

  test("Boolean AND") {

    val tests = List(
      ("AND(true)", BooleanConst(true)),
      ("AND(false)", BooleanConst(false)),
      ("AND(true,true)", BooleanConst(true)),
      ("AND(true,false)", BooleanConst(false)),
      ("AND(false,false)", BooleanConst(false)))

    testWithoutValues(tests)
  }

  test("Boolean OR") {

    val tests = List(
      ("OR(true)", BooleanConst(true)),
      ("OR(false)", BooleanConst(false)),
      ("OR(true,true)", BooleanConst(true)),
      ("OR(true,false)", BooleanConst(true)),
      ("OR(false,false)", BooleanConst(false)))

    testWithoutValues(tests)
  }

  test("Boolean NOT") {

    val tests = List(
      ("NOT(true)", BooleanConst(false)),
      ("NOT(false)", BooleanConst(true)))

    testWithoutValues(tests)

    val errorTests = List(
      ("NOT(true,true)", "requires one"))

    testErrorsWithoutValues(errorTests)
  }

  test("Boolean COUNT") {

    val tests = List(
      ("COUNT(true)", LongConst(1)),
      ("COUNT(false)", LongConst(0)),
      ("COUNT(true,true)", LongConst(2)),
      ("COUNT(true,false)", LongConst(1)),
      ("COUNT(false,false)", LongConst(0)))

    testWithoutValues(tests)
  }

  test("Boolean GT") {

    val tests = List(
      ("GT(0,0)", BooleanConst(false)),
      ("GT(1,0)", BooleanConst(true)),
      ("GT(5.5,5.4)", BooleanConst(true)),
      ("GT(-19,-25)", BooleanConst(true)),
      ("GT(-25,-20)", BooleanConst(false)),
      ("GT(4,7)", BooleanConst(false)))

    testWithoutValues(tests)
  }

  test("Boolean GTE") {

    val tests = List(
      ("GTE(0,0)", BooleanConst(true)),
      ("GTE(1,0)", BooleanConst(true)),
      ("GTE(5.5,5.4)", BooleanConst(true)),
      ("GTE(-19,-25)", BooleanConst(true)),
      ("GTE(-25,-20)", BooleanConst(false)),
      ("GTE(4,7)", BooleanConst(false)))

    testWithoutValues(tests)
  }

  test("Boolean LT") {

    val tests = List(
      ("LT(0,0)", BooleanConst(false)),
      ("LT(1,0)", BooleanConst(false)),
      ("LT(5.5,5.4)", BooleanConst(false)),
      ("LT(-19,-25)", BooleanConst(false)),
      ("LT(-25,-20)", BooleanConst(true)),
      ("LT(4,7)", BooleanConst(true)))

    testWithoutValues(tests)
  }

  test("Boolean LTE") {

    val tests = List(
      ("LTE(0,0)", BooleanConst(true)),
      ("LTE(1,0)", BooleanConst(false)),
      ("LTE(5.5,5.4)", BooleanConst(false)),
      ("LTE(-19,-25)", BooleanConst(false)),
      ("LTE(-25,-20)", BooleanConst(true)),
      ("LTE(4,7)", BooleanConst(true)))

    testWithoutValues(tests)
  }

  test("Boolean EQ") {

    val tests = List(
      ("EQ(0,0)", BooleanConst(true)),
      ("EQ(-1,-1)", BooleanConst(true)),
      ("EQ(5.5,5.5)", BooleanConst(true)),
      ("EQ(5.5,5.4)", BooleanConst(false)),
      ("EQ(-19,-25)", BooleanConst(false)))

    testWithoutValues(tests)
  }

  test("Boolean IF") {

    val tests = List(
      ("IF(true,3,4)", LongConst(3)),
      ("IF(false,3,4)", LongConst(4)))

    testWithoutValues(tests)

    val errorTests = List(
      ("IF(1.0,3,4)", "condition must be a boolean expression"),
      ("IF(3,4)", ""),
      ("IF(true,4)", ""),
      ("IF(true)", ""))

    testErrorsWithoutValues(errorTests)
  }

  test("Numeric INTEGRATE") {

    val f = "INTEGRATE(A)"

    val tests = List(
      (20 * 5.0, List((5.0, 0), (5.0, 10), (5.0, 20))),
      (0.0, List((0.0, 0), (0.0, 10), (0.0, 20))),
      (100.0, List((0.0, 0), (5.0, 10), (10.0, 20))),
      (100.0, List((10.0, 0), (5.0, 10), (0.0, 20))),
      (300.0, List((10.0, 0), (10.0, 10), (20.0, 10), (20.0, 20))),
      (5.0 * 10, List((5.0, 10), (300.0, 8), (5.0, 20)))) // Ignore out of order

    tests.foreach {
      case (output, inputs) =>
        val values = Map("A" -> ValueRange(inputs.map { v => NumericMeas(v._1, v._2) }))
        val result = NumericConst(output)
        val expr = parseFormula(f)
        expr.evaluate(new ValueMap(values)) should equal(result)
    }
  }

  test("Numeric INTEGRATE (Accumulated)") {

    val f = "INTEGRATE(A)"
    val expr = new AccumulatedFormula(NumericConst(0), parseFormula(f))

    val tests = List(
      (10.0, List((5.0, 0), (5.0, 1), (5.0, 2))),
      (30.0, List((10.0, 2), (10.0, 3), (10.0, 4))),
      (30.0, List((20.0, 4))),
      (50.0, List((20.0, 5))))

    tests.foreach {
      case (output, inputs) =>
        val values = Map("A" -> ValueRange(inputs.map { v => NumericMeas(v._1, v._2) }))
        val result = NumericConst(output)
        expr.evaluate(new ValueMap(values)) should equal(result)
    }
  }

  private def testErrorsWithoutValues(errorTests: List[(String, String)]) {
    errorTests.foreach {
      case (f, errString) =>
        val expr = parseFormula(f)
        intercept[EvalException] {
          expr.evaluate(new ValueMap(Map()))
        }.getMessage should include(errString)
    }
  }

  private def testWithValues(map: ValueMap, tests: List[(String, OperationValue)]) {
    tests.foreach {
      case (f, result) =>
        val expr = parseFormula(f)
        expr.evaluate(map) should equal(result)
    }
  }

  private def testWithoutValues(tests: List[(String, OperationValue)]) {
    tests.foreach {
      case (f, result) =>
        val expr = parseFormula(f)
        expr.evaluate(new ValueMap(Map())) should equal(result)
    }
  }

  private def testNumeric(f: String, tests: List[(Double, List[Double])]) {
    tests.foreach {
      case (output, inputs) =>
        val values = Map("A" -> ValueRange(inputs.map { NumericConst(_) }))
        val result = NumericConst(output)
        val expr = parseFormula(f)
        expr.evaluate(new ValueMap(values)) should equal(result)
    }
  }
}
