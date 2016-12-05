/**
 * Copyright 2011-2016 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.greenbus.sql

import com.typesafe.scalalogging.LazyLogging

object SqlSettings extends LazyLogging {
  import io.greenbus.util.PropertyReading._

  def load(file: String): SqlSettings = {
    apply(loadFile(file))
  }

  def apply(props: Map[String, String]): SqlSettings = {
    SqlSettings(
      get(props, "io.greenbus.sql.url"),
      get(props, "io.greenbus.sql.username"),
      get(props, "io.greenbus.sql.password"),
      getLong(props, "io.greenbus.sql.slowquery"),
      optionalInt(props, "io.greenbus.sql.maxactive") getOrElse 8)
  }
}

case class SqlSettings(url: String, user: String, password: String,
    slowQueryTimeMilli: Long, poolMaxActive: Int) {

  // custom to hide password
  override def toString() = user + "@" + url
}
