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
package io.greenbus.mstore

case class RotationalStoreSettings(sliceCount: Int, sliceDurationMs: Long)

object MeasurementStoreSettings {
  import io.greenbus.util.PropertyReading._

  def load(path: String): MeasurementStoreSettings = {
    apply(optionallyLoadFile(path))
  }

  def apply(props: Map[String, String]): MeasurementStoreSettings = {
    optionalBool(props, "io.greenbus.mstore.rotation.enabled") match {
      case None => default
      case Some(false) => default
      case Some(true) =>
        val sliceCount = getInt(props, "io.greenbus.mstore.rotation.slicecount")
        val sliceDurationMs = getLong(props, "io.greenbus.mstore.rotation.sliceduration")
        val storeSettings = RotationalStoreSettings(sliceCount, sliceDurationMs)
        MeasurementStoreSettings(rotation = true, Some(storeSettings))
    }
  }

  def default: MeasurementStoreSettings = {
    MeasurementStoreSettings(rotation = false, None)
  }
}
case class MeasurementStoreSettings(rotation: Boolean, rotationSettingsOpt: Option[RotationalStoreSettings])
