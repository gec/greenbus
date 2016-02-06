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

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FunSuite
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import ProtoHelper._

@RunWith(classOf[JUnitRunner])
class ActionsTest extends FunSuite with ShouldMatchers {

  test("BoolTransform") {
    val transformer = new Actions.BoolEnumTransformer("CLOSED", "OPEN")

    transformer.apply(makeBool(true)).getStringVal should equal("OPEN")
    transformer.apply(makeBool(false)).getStringVal should equal("CLOSED")

    transformer.apply(makeInt(0)).getStringVal should equal("")
  }

  test("IntTransform") {
    val map = List((-1).toLong -> "Disabled", 0.toLong -> "Searching").toMap
    val transformer = new Actions.IntegerEnumTransformer(map, Some("Otherwise"))

    transformer.apply(makeInt(-1)).getStringVal should equal("Disabled")
    transformer.apply(makeInt(0)).getStringVal should equal("Searching")

    transformer.apply(makeInt(10)).getStringVal should equal("Otherwise")
  }

  test("Suppression") {

    val suppressor = new SuppressAction("testAction", false, Action.High)

    val m = makeInt(5)

    suppressor.process(m, false, false) should equal(Some(m))
    suppressor.process(m, true, false) should equal(None)
    suppressor.process(m, false, true) should equal(Some(m))
    suppressor.process(m, true, true) should equal(None)

  }
}