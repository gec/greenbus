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
package io.greenbus.services.model

import scala.collection.mutable
import org.squeryl.dsl.ast.{ BinaryOperatorNodeLogicalBoolean, LogicalBoolean }

object ModelHelpers {

  def groupSortedAndPreserveOrder[A, B](input: Seq[(A, B)]): Seq[(A, Seq[B])] = {
    val results = mutable.ListBuffer.empty[(A, Seq[B])]
    var current = Option.empty[A]
    var currentAccum = mutable.ListBuffer.empty[B]
    val itr = input.iterator
    while (itr.hasNext) {
      val (a, b) = itr.next()
      current match {
        case None =>
          current = Some(a)
          currentAccum += b
        case Some(currentA) => {
          if (currentA != a) {
            results += ((currentA, currentAccum))
            current = Some(a)
            currentAccum = mutable.ListBuffer.empty[B]
            currentAccum += b
          } else {
            currentAccum += b
          }
        }
      }
    }

    current.foreach { curr =>
      results += ((curr, currentAccum))
    }
    results
  }

  def logicalCombine(op: String)(a: LogicalBoolean, b: LogicalBoolean) = {
    new BinaryOperatorNodeLogicalBoolean(a, b, op)
  }

  def logicalAnd = logicalCombine("and") _
  def logicalOr = logicalCombine("or") _
}
