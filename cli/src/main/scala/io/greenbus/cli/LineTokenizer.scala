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

import scala.annotation.tailrec

object LineTokenizer {

  val tokenRegex = """".*"\s+|".*"$|\S+\s+|\S+$""".r

  def tokenize(line: String): Seq[String] = {

    @tailrec
    def findAll(s: String, tokens: List[String]): List[String] = {
      val in = s.trim
      tokenRegex.findPrefixOf(in) match {
        case None => tokens.reverse
        case Some(rawTok) =>
          val trimmed = rawTok.trim
          val tok = if (trimmed.size >= 2 && trimmed.head == '\"' && trimmed.last == '\"') {
            trimmed.drop(1).dropRight(1)
          } else {
            trimmed
          }

          findAll(in.drop(rawTok.size), tok :: tokens)
      }
    }

    findAll(line, Nil)
  }
}

object SearchingTokenizer {

  def isQuote(c: Char): Boolean = c == '\"'

  def tokenize(line: String): Seq[String] = {

    def untilSpaceSearch(s: String, tokens: List[String]): List[String] = {
      val found = s.indexWhere(c => c.isWhitespace)
      if (found < 0) {
        (s :: tokens).reverse
      } else {
        val tok = s.take(found)
        inBetweenSearch(s.drop(found + 1), tok :: tokens)
      }
    }

    def untilQuoteSearch(s: String, tokens: List[String]): List[String] = {
      val found = s.indexWhere(c => c == '\"')
      if (found < 0) {
        throw new IllegalArgumentException("Unterminated quote in input")
      } else {
        val tok = s.take(found)
        inBetweenSearch(s.drop(found + 1), tok :: tokens)
      }
    }

    def inBetweenSearch(s: String, tokens: List[String]): List[String] = {
      val found = s.indexWhere(c => !c.isWhitespace)
      if (found < 0) {
        tokens.reverse
      } else {
        if (s.charAt(found) == '\"') {
          untilQuoteSearch(s.drop(found + 1), tokens)
        } else {
          untilSpaceSearch(s.drop(found), tokens)
        }
      }
    }

    inBetweenSearch(line, Nil)
  }
}
