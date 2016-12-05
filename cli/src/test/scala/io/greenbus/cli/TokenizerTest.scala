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
import org.scalatest.Matchers

@RunWith(classOf[JUnitRunner])
class TokenizerTest extends FunSuite with Matchers {

  test("line tokenizing with quotes") {
    val input = """ a thing   is "something good" I think """
    val input2 = """the quote is " on the end of the line  """"

    SearchingTokenizer.tokenize(input) should equal(List("a", "thing", "is", "something good", "I", "think"))
    SearchingTokenizer.tokenize(input2) should equal(List("the", "quote", "is", " on the end of the line  "))
  }

  test("line tokenizing with quotes realistic") {
    val input = """ -o "a value" -b "another value" -d -v 5 """

    SearchingTokenizer.tokenize(input) should equal(List("-o", "a value", "-b", "another value", "-d", "-v", "5"))
  }

}
