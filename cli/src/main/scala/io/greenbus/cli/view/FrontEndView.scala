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

import java.util.Date

import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.client.service.proto.Model.{ Command, Endpoint, Point }

import scala.collection.JavaConversions._

object EndpointView extends TableView[Endpoint] {
  def header: List[String] = "Name" :: "Protocol" :: "Disabled" :: "Types" :: Nil

  def row(obj: Endpoint): List[String] = {
    obj.getName ::
      obj.getProtocol ::
      obj.getDisabled.toString ::
      "(" + obj.getTypesList.toVector.sorted.mkString(", ") + ")" ::
      Nil
  }
}

object EndpointStatusView extends TableView[(Endpoint, Option[(FrontEndConnectionStatus.Status, Long)])] {
  def header: List[String] = "Name" :: "Status " :: "Last Status" :: "Protocol" :: "Disabled" :: "Types" :: Nil

  def row(obj: (Endpoint, Option[(FrontEndConnectionStatus.Status, Long)])): List[String] = {
    val (end, (statusOpt)) = obj
    end.getName ::
      statusOpt.map(_._1.toString).getOrElse("--") ::
      statusOpt.map(t => new Date(t._2).toString).getOrElse("--") ::
      end.getProtocol ::
      end.getDisabled.toString ::
      "(" + end.getTypesList.toVector.sorted.mkString(", ") + ")" ::
      Nil
  }
}

object PointView extends TableView[Point] {
  def header: List[String] = "Name" :: "Category" :: "Unit" :: "Types" :: Nil

  def row(obj: Point): List[String] = {
    obj.getName ::
      obj.getPointCategory.toString ::
      obj.getUnit ::
      "(" + obj.getTypesList.toVector.sorted.mkString(", ") + ")" ::
      Nil
  }
}
object PointWithCommandsView extends TableView[(Point, Seq[String])] {
  def header: List[String] = "Name" :: "Category" :: "Unit" :: "Commands" :: "Types" :: Nil

  def row(obj: (Point, Seq[String])): List[String] = {
    obj._1.getName ::
      obj._1.getPointCategory.toString ::
      obj._1.getUnit ::
      obj._2.mkString(", ") ::
      "(" + obj._1.getTypesList.toVector.sorted.mkString(", ") + ")" ::
      Nil
  }
}

object CommandView extends TableView[Command] {
  def header: List[String] = "Name" :: "DisplayName" :: "Category" :: "Types" :: Nil

  def row(obj: Command): List[String] = {
    obj.getName ::
      obj.getDisplayName ::
      obj.getCommandCategory.toString ::
      "(" + obj.getTypesList.toVector.sorted.mkString(", ") + ")" ::
      Nil
  }
}

object CommandWithPointsView extends TableView[(Command, Seq[String])] {
  def header: List[String] = "Name" :: "DisplayName" :: "Category" :: "Points" :: "Types" :: Nil

  def row(obj: (Command, Seq[String])): List[String] = {
    obj._1.getName ::
      obj._1.getDisplayName ::
      obj._1.getCommandCategory.toString ::
      obj._2.mkString(", ") ::
      "(" + obj._1.getTypesList.toVector.sorted.mkString(", ") + ")" ::
      Nil
  }
}
