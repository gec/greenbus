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
package io.greenbus.app.actor.frontend.json

import io.greenbus.app.actor.frontend.FrontendRegistrationConfig

case class JsonFrontendRegistrationConfig(
  loginRetryMs: Option[Long],
  registrationRetryMs: Option[Long],
  releaseTimeoutMs: Option[Long],
  statusHeartbeatPeriodMs: Option[Long],
  lapsedTimeMs: Option[Long],
  statusRetryPeriodMs: Option[Long],
  measRetryPeriodMs: Option[Int],
  measQueueLimit: Option[Int],
  configRequestRetryMs: Option[Int])

object JsonFrontendRegistrationConfig {
  import play.api.libs.json._
  implicit val writer = Json.writes[JsonFrontendRegistrationConfig]
  implicit val reader = Json.reads[JsonFrontendRegistrationConfig]

  def load(input: Array[Byte]): Option[JsonFrontendConfiguration] = {

    val jsObj = Json.parse(input)

    Json.fromJson(jsObj)(JsonFrontendConfiguration.reader).asOpt
  }

  def read(json: JsonFrontendRegistrationConfig): FrontendRegistrationConfig = {
    val default = FrontendRegistrationConfig.defaults
    FrontendRegistrationConfig(
      loginRetryMs = json.loginRetryMs.getOrElse(default.loginRetryMs),
      registrationRetryMs = json.registrationRetryMs.getOrElse(default.registrationRetryMs),
      releaseTimeoutMs = json.releaseTimeoutMs.getOrElse(default.releaseTimeoutMs),
      statusHeartbeatPeriodMs = json.statusHeartbeatPeriodMs.getOrElse(default.statusHeartbeatPeriodMs),
      lapsedTimeMs = json.lapsedTimeMs.getOrElse(default.lapsedTimeMs),
      statusRetryPeriodMs = json.statusRetryPeriodMs.getOrElse(default.statusRetryPeriodMs),
      measRetryPeriodMs = json.measRetryPeriodMs.getOrElse(default.measRetryPeriodMs),
      measQueueLimit = json.measQueueLimit.getOrElse(default.measQueueLimit),
      configRequestRetryMs = json.configRequestRetryMs.getOrElse(default.configRequestRetryMs))
  }
}
