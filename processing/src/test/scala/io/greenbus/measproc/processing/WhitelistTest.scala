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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.FunSuite
import org.scalatest.Matchers
import scala.collection.mutable
import ProtoHelper._
import io.greenbus.jmx.{ MetricsContainer, Metrics }

@RunWith(classOf[JUnitRunner])
class WhitelistTest extends FunSuite with Matchers {
  test("Ignores meases") {
    val metrics = Metrics(MetricsContainer())

    val filter = new MeasurementWhiteList(List("ok1", "ok2"), metrics)

    val m = makeAnalog(100)

    filter.process("ok1", m) should equal(Some(m))
    filter.process("ok2", m) should equal(Some(m))

    filter.process("bad1", m) should equal(None)
    filter.process("bad2", m) should equal(None)
    filter.process("bad1", m) should equal(None)

    filter.process("ok1", m) should equal(Some(m))
  }
}