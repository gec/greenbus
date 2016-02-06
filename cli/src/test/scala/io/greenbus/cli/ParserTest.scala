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
package io.greenbus.cli

import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

@RunWith(classOf[JUnitRunner])
class ParserTest extends FunSuite with ShouldMatchers {

  class TestAll extends Command[Boolean] {

    val commandName = "Test01"
    val description = "Test01"

    val name = strings.arg("entity name", "Entity name")
    val uuid = strings.argOptional("entity uuid", "Entity uuid")

    val displayName = strings.option(Some("d"), Some("display"), "Display name")
    val types = strings.optionRepeated(Some("t"), Some("type"), "Entity type")

    protected def execute(context: Boolean) {
    }
  }

  test("multi") {
    val cmd = new TestAll

    cmd.run(SearchingTokenizer.tokenize("-t type01 --type type02 -d displayName01 name01 uuid01").toArray, false)

    cmd.name.value should equal("name01")
    cmd.uuid.value should equal(Some("uuid01"))
    cmd.displayName.value should equal(Some("displayName01"))
    cmd.types.value should equal(Seq("type01", "type02"))
  }

  test("after string encapsulated") {
    val cmd = new TestAll

    cmd.run(SearchingTokenizer.tokenize("-t type01 --type \"type 02\" -d displayName01 name01 uuid01").toArray, false)

    cmd.name.value should equal("name01")
    cmd.uuid.value should equal(Some("uuid01"))
    cmd.displayName.value should equal(Some("displayName01"))
    cmd.types.value should equal(Seq("type01", "type 02"))
  }

}
