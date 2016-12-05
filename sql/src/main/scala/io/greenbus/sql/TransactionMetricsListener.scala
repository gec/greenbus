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

import org.squeryl.logging.{ StatementInvocationEvent, StatisticsListener }
import io.greenbus.jmx.Metrics
import com.typesafe.scalalogging.LazyLogging

class TransactionMetrics(metrics: Metrics) {

  val queryCount = metrics.average("SqlQueryCount")
  val queryTime = metrics.average("SqlQueryTime")
  val timePerQuery = metrics.average("SqlTimePerQuery")
}

class TransactionMetricsListener(requestId: String) extends StatisticsListener with LazyLogging {

  private var queryCount: Int = 0
  private var queryTime: Int = 0

  def queryExecuted(se: StatementInvocationEvent): Unit = {
    queryCount += 1
    val elapsed = (se.end - se.start).toInt
    queryTime += elapsed
    logger.trace(s"$requestId - Query ($elapsed) [${se.rowCount}]: " + se.jdbcStatement.take(30).replace('\n', ' ') + " : " + se.definitionOrCallSite)
  }

  def updateExecuted(se: StatementInvocationEvent): Unit = {
    val elapsed = (se.end - se.start).toInt
    logger.trace(s"$requestId - Update ($elapsed) [${se.rowCount}]: " + se.jdbcStatement.take(30).replace('\n', ' '))
  }

  def insertExecuted(se: StatementInvocationEvent): Unit = {
    val elapsed = (se.end - se.start).toInt
    logger.trace(s"$requestId - Insert ($elapsed) [${se.rowCount}]: " + se.jdbcStatement.take(30).replace('\n', ' '))
  }

  def deleteExecuted(se: StatementInvocationEvent): Unit = {
    val elapsed = (se.end - se.start).toInt
    logger.trace(s"$requestId - Delete ($elapsed) [${se.rowCount}]: " + se.jdbcStatement.take(30).replace('\n', ' '))
  }

  def resultSetIterationEnded(statementInvocationId: String, iterationEndTime: Long, rowCount: Int, iterationCompleted: Boolean): Unit = {

  }

  def report(metrics: TransactionMetrics) {
    metrics.queryCount(queryCount)
    metrics.queryTime(queryTime)
    val timePerQuery = if (queryCount > 0) (queryTime.toDouble / queryCount.toDouble).toInt else 0
    metrics.timePerQuery(timePerQuery)
  }
}
