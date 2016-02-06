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
package io.greenbus.app.actor.frontend

import io.greenbus.app.actor.{ EndpointCollectionStrategy, AmqpConnectionConfig }

case class FrontendRegistrationConfig(
  loginRetryMs: Long,
  registrationRetryMs: Long,
  releaseTimeoutMs: Long,
  statusHeartbeatPeriodMs: Long,
  lapsedTimeMs: Long,
  statusRetryPeriodMs: Long,
  measRetryPeriodMs: Int,
  measQueueLimit: Int,
  configRequestRetryMs: Int)

object FrontendRegistrationConfig {

  def defaults = FrontendRegistrationConfig(
    loginRetryMs = 5000,
    registrationRetryMs = 5000,
    releaseTimeoutMs = 20000,
    statusHeartbeatPeriodMs = 5000,
    lapsedTimeMs = 11000,
    statusRetryPeriodMs = 2000,
    measRetryPeriodMs = 2000,
    measQueueLimit = 1000,
    configRequestRetryMs = 5000)

  def build(loginRetryMs: Long,
    registrationRetryMs: Long,
    releaseTimeoutMs: Long,
    statusHeartbeatPeriodMs: Long,
    lapsedTimeMs: Long,
    statusRetryPeriodMs: Long,
    measRetryPeriodMs: Int,
    measQueueLimit: Int,
    configRequestRetryMs: Int): FrontendRegistrationConfig = {
    FrontendRegistrationConfig(
      loginRetryMs,
      registrationRetryMs,
      releaseTimeoutMs,
      statusHeartbeatPeriodMs,
      lapsedTimeMs,
      statusRetryPeriodMs,
      measRetryPeriodMs,
      measQueueLimit,
      configRequestRetryMs)
  }
}

case class FrontendProcessConfig(
  amqpConfig: AmqpConnectionConfig,
  userConfigPath: String,
  collectionStrategy: EndpointCollectionStrategy,
  nodeId: String,
  registrationConfig: FrontendRegistrationConfig)
