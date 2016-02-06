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
package io.greenbus.cli.commands

import jline.console.ConsoleReader
import scala.concurrent.{ Await, Future }
import scala.annotation.tailrec
import scala.concurrent.duration._

object Paging {

  def page[A, B](reader: ConsoleReader, getResults: (Option[B], Int) => Future[Seq[A]], pageBoundary: Seq[A] => B, displayFirst: (Seq[A], Seq[Int]) => Seq[Int], displaySubsequent: (Seq[A], Seq[Int]) => Seq[Int]) {

    @tailrec
    def getAndPrint(last: Option[B], minColWidths: Seq[Int]) {
      val terminalHeight = reader.getTerminal.getHeight
      val pageSize = if (last.isEmpty) terminalHeight - 3 else terminalHeight

      val pageResults = Await.result(getResults(last, pageSize), 5000.milliseconds)

      if (pageResults.nonEmpty) {

        val columnWidths = if (last.isEmpty) {
          displayFirst(pageResults.toList, minColWidths)
        } else {
          displaySubsequent(pageResults.toList, minColWidths)
        }

        if (pageResults.size == pageSize) {
          val inputInt = reader.readCharacter()
          val input = inputInt.asInstanceOf[Char]
          if (!(input == 'q')) {
            getAndPrint(Some(pageBoundary(pageResults)), columnWidths)
          }
        }

      }
    }

    getAndPrint(None, Nil)
  }
}
