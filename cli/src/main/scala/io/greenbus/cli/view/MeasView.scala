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

import io.greenbus.client.service.proto.Measurements.{ Measurement, PointMeasurementValue, Quality }
import io.greenbus.client.service.proto.Model.Point

object MeasurementHistoryView extends TableView[Measurement] {
  import io.greenbus.cli.view.MeasViewCommon._
  def header: List[String] = "Time" :: "Value" :: "Type" :: "Q" :: "Off" :: Nil

  def row(m: Measurement): List[String] = {
    val (value, typ) = valueAndType(m)
    timeString(m) :: value.toString :: typ :: shortQuality(m) :: offset(m) :: Nil
  }

}

object MeasWithEmptiesView extends TableView[(Point, Option[Measurement])] {
  import io.greenbus.cli.view.MeasViewCommon._

  def header: List[String] = "Name" :: "Value" :: "Type" :: "Unit" :: "Q" :: "Time" :: "Off" :: Nil

  def row(obj: (Point, Option[Measurement])): List[String] = {

    obj._2 match {
      case None => obj._1.getName :: "--" :: "--" :: "--" :: "--" :: "--" :: "--" :: Nil
      case Some(m) =>
        val (value, typ) = valueAndType(m)
        obj._1.getName :: value.toString :: typ :: unit(obj._1) :: shortQuality(m) :: timeString(m) :: offset(m) :: Nil
    }
  }

}

object SimpleMeasurementView extends TableView[(String, Measurement, String)] {
  import io.greenbus.cli.view.MeasViewCommon._

  def header: List[String] = "Name" :: "Value" :: "Type" :: "Unit" :: "Q" :: "Time" :: "Off" :: Nil

  def row(obj: (String, Measurement, String)): List[String] = {
    val (name, m, unit) = obj
    val (value, typ) = valueAndType(m)
    name :: value.toString :: typ :: unit :: shortQuality(m) :: timeString(m) :: offset(m) :: Nil
  }

}

object MeasViewCommon {
  def valueAndType(m: Measurement): (Any, String) = {
    if (m.getType == Measurement.Type.BOOL) {
      val repr = if (m.getBoolVal) "HIGH" else "LOW"
      (repr, "Binary")
    } else if (m.getType == Measurement.Type.DOUBLE) {
      (String.format("%.3f", m.getDoubleVal.asInstanceOf[AnyRef]), "Analog")
    } else if (m.getType == Measurement.Type.INT) {
      (m.getIntVal.toString, "Analog")
    } else if (m.getType == Measurement.Type.STRING) {
      (m.getStringVal, "String")
    } else {
      ("(unknown)", "(unknown)")
    }
  }

  def value(m: Measurement): Any = {
    val (value, typ) = valueAndType(m)
    value
  }

  def unit(p: Point) = if (p.hasUnit) p.getUnit else ""

  def shortQuality(m: Measurement) = {
    val q = m.getQuality

    if (q.getSource == Quality.Source.SUBSTITUTED) {
      "R"
    } else if (q.getOperatorBlocked) {
      "N"
    } else if (q.getTest) {
      "T"
    } else if (q.getDetailQual.getOldData) {
      "O"
    } else if (q.getValidity == Quality.Validity.QUESTIONABLE) {
      "A"
    } else if (q.getValidity != Quality.Validity.GOOD) {
      "B"
    } else {
      ""
    }
  }

  def longQuality(m: Measurement): String = {
    val q = m.getQuality
    longQuality(q)
  }

  def longQuality(q: Quality): String = {
    val dq = q.getDetailQual

    var list = List.empty[String]
    if (q.getOperatorBlocked) list ::= "NIS"
    if (q.getSource == Quality.Source.SUBSTITUTED) list ::= "replaced"
    if (q.getTest) list ::= "test"
    if (dq.getOverflow) list ::= "overflow"
    if (dq.getOutOfRange) list ::= "out of range"
    if (dq.getBadReference) list ::= "bad reference"
    if (dq.getOscillatory) list ::= "oscillatory"
    if (dq.getFailure) list ::= "failure"
    if (dq.getOldData) list ::= "old"
    if (dq.getInconsistent) list ::= "inconsistent"
    if (dq.getInaccurate) list ::= "inaccurate"

    val overall = q.getValidity match {
      case Quality.Validity.GOOD => "Good"
      case Quality.Validity.INVALID => "Invalid"
      case Quality.Validity.QUESTIONABLE => "Questionable"
    }

    overall + " (" + list.reverse.mkString("; ") + ")"
  }

  def timeString(m: Measurement): String = {
    (if (m.hasIsDeviceTime && m.getIsDeviceTime) "~" else "") + new java.util.Date(m.getTime).toString
  }
  def systemTimeString(m: Measurement): String = {
    if (m.hasSystemTime) new java.util.Date(m.getSystemTime).toString
    else "--"
  }

  def offset(m: Measurement): String = {
    if (m.hasSystemTime) (m.getSystemTime - m.getTime).toString
    else "--"
  }
}