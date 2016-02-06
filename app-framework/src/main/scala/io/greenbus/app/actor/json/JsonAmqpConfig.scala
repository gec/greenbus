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
package io.greenbus.app.actor.json

import io.greenbus.app.actor.AmqpConnectionConfig

case class JsonAmqpConfig(amqpConfigFileList: Option[IndexedSeq[String]],
  failureLimit: Option[Int],
  retryDelayMs: Option[Long],
  connectionTimeoutMs: Option[Long])

object JsonAmqpConfig {
  import play.api.libs.json._
  implicit val writer = Json.writes[JsonAmqpConfig]
  implicit val reader = Json.reads[JsonAmqpConfig]

  def load(input: Array[Byte]): Option[JsonAmqpConfig] = {

    val jsObj = Json.parse(input)

    Json.fromJson(jsObj)(JsonAmqpConfig.reader).asOpt
  }

  def read(json: JsonAmqpConfig, defaultPaths: Seq[String]): AmqpConnectionConfig = {

    val default = AmqpConnectionConfig.default(defaultPaths)

    AmqpConnectionConfig(
      json.amqpConfigFileList.getOrElse(default.amqpConfigFileList),
      failureLimit = json.failureLimit.getOrElse(default.failureLimit),
      retryDelayMs = json.retryDelayMs.getOrElse(default.retryDelayMs),
      connectionTimeoutMs = json.connectionTimeoutMs.getOrElse(default.connectionTimeoutMs))
  }
}
