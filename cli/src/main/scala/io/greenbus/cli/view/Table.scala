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
package io.greenbus.cli.view

import java.io.PrintStream

object Table {

  val colSeparator = "  |  "

  def normalizeNumCols(rows: Seq[Seq[String]]) = {
    val max = rows.map(_.length).max
    rows.map(row => row.padTo(max, ""))
  }

  def getWidths(rows: Seq[Seq[String]], minWidths: Seq[Int] = List.empty[Int]): Seq[Int] = {
    rows.foldLeft(minWidths) {
      case (widths, row) =>
        val w = if (widths.length < row.length) widths.padTo(row.length, 0) else widths
        row.map(_.length).zip(w).map { case (a, b) => if (a > b) a else b }
    }
  }

  def justifyColumns(rows: Seq[Seq[String]], widths: Seq[Int]) = {
    rows.map { row =>
      justifyColumnsInRow(row, widths)
    }
  }

  def justifyColumnsInRow(row: Seq[String], widths: Seq[Int]) = {
    row.zip(widths).map {
      case (str, width) =>
        str.padTo(width, " ").mkString
    }
  }

  def rowLength(line: Seq[String]) = {
    line.foldLeft(0)(_ + _.length)
  }

  def printTable(header: Seq[String], rows: Seq[Seq[String]], minWidths: Seq[Int] = Seq.empty[Int], stream: PrintStream = Console.out): Seq[Int] = {
    val overallList = normalizeNumCols(Seq(header) ++ rows)
    val widths = getWidths(overallList, minWidths)
    val just = justifyColumns(overallList, widths)
    val headStr = just.head.mkString("     ")
    //stream.println("Found: " + rows.size)
    stream.println(headStr)
    stream.println("".padTo(headStr.length, "-").mkString)
    just.tail.foreach(line => stream.println(line.mkString(colSeparator)))
    widths
  }

  def renderRows(rows: Seq[Seq[String]], sep: String = "", minWidths: Seq[Int] = List.empty[Int], stream: PrintStream = Console.out): Seq[Int] = {
    val normalizedRows = normalizeNumCols(rows)
    val widths = getWidths(normalizedRows, minWidths)
    Table.justifyColumns(normalizedRows, widths).foreach(line => stream.println(line.mkString(sep)))
    widths
  }

  def renderTableRow(row: Seq[String], widths: Seq[Int], sep: String = colSeparator, stream: PrintStream = Console.out) = {
    stream.println(Table.justifyColumnsInRow(row, widths).mkString(sep))
  }
}