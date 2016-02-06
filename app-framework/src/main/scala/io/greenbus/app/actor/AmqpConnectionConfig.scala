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
package io.greenbus.app.actor

case class AmqpConnectionConfig(amqpConfigFileList: IndexedSeq[String],
  failureLimit: Int,
  retryDelayMs: Long,
  connectionTimeoutMs: Long)

object AmqpConnectionConfig {

  def default(path: String): AmqpConnectionConfig = {
    AmqpConnectionConfig(Vector(path), failureLimit = 1, retryDelayMs = 5000, connectionTimeoutMs = 10000)
  }

  def default(paths: Seq[String]): AmqpConnectionConfig = {
    AmqpConnectionConfig(paths.toVector, failureLimit = 1, retryDelayMs = 5000, connectionTimeoutMs = 10000)
  }

  def load(paths: Seq[String], failureLimit: Int, retryDelayMs: Long, connectionTimeoutMs: Long): AmqpConnectionConfig = {
    AmqpConnectionConfig(paths.toVector, failureLimit, retryDelayMs, connectionTimeoutMs)
  }
}
