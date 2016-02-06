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
package io.greenbus.measproc.processing

trait ObjectCache[A] {

  def put(values: Seq[(String, A)]): Unit = values.foreach { case (k, v) => put(k, v) }

  def put(key: String, value: A): Unit

  def get(name: String): Option[A]

  def delete(name: String)
}

class MapCache[A] extends ObjectCache[A] {

  private var map = Map.empty[String, A]

  def put(key: String, value: A) {
    map += (key -> value)
  }

  def get(name: String): Option[A] = {
    map.get(name)
  }

  def delete(name: String) {
    map -= name
  }

  def reset() {
    map = Map.empty[String, A]
  }
}