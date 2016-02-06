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

import io.greenbus.client.service.proto.Model.{ EntityKeyValue, StoredValue }

object KvView extends TableView[(String, EntityKeyValue)] {

  def storedValueRow(v: StoredValue): String = {
    if (v.hasBoolValue) {
      v.getBoolValue.toString
    } else if (v.hasInt32Value) {
      v.getInt32Value.toString
    } else if (v.hasInt64Value) {
      v.getInt64Value.toString
    } else if (v.hasUint32Value) {
      v.getUint32Value.toString
    } else if (v.hasUint64Value) {
      v.getUint64Value.toString
    } else if (v.hasDoubleValue) {
      v.getDoubleValue.toString
    } else if (v.hasStringValue) {
      "\"" + v.getStringValue + "\""
    } else if (v.hasByteArrayValue) {
      val size = v.getByteArrayValue.size()
      s"[bytes] ($size)"
    } else {
      "?"
    }
  }

  def header: List[String] = "Name" :: "Key" :: "Value" :: Nil

  def row(obj: (String, EntityKeyValue)): List[String] = {
    obj._1 ::
      obj._2.getKey ::
      storedValueRow(obj._2.getValue) ::
      Nil
  }

  def printInspect(v: EntityKeyValue) = {
    val lines =
      ("uuid" :: v.getUuid.getValue :: Nil) ::
        ("key" :: v.getKey :: Nil) ::
        ("value" :: storedValueRow(v.getValue) :: Nil) ::
        Nil

    Table.renderRows(lines, " | ")
  }
}
