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
package io.greenbus.sql.postgres

import org.squeryl.Session
import org.squeryl.adapters.PostgreSqlAdapter

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.sql.{ DbConnector, SqlSettings }
import io.greenbus.sql.impl.{ SessionDbConnection, SlowQueryTracing }
import org.squeryl.logging.StatisticsListener

class Connector extends DbConnector with LazyLogging {

  def connect(sqlSettings: SqlSettings) = {

    val pool = new org.apache.commons.dbcp.BasicDataSource
    pool.setDriverClassName("org.postgresql.Driver")
    pool.setUrl(sqlSettings.url)
    pool.setUsername(sqlSettings.user)
    pool.setPassword(sqlSettings.password)
    pool.setMaxActive(sqlSettings.poolMaxActive)

    logger.info("Initializing postgres connection pool: " + sqlSettings)

    def sessionFactory(listener: Option[StatisticsListener]) = {
      new Session(
        pool.getConnection(),
        new PostgreSqlAdapter with SlowQueryTracing {
          val slowQueryTimeMilli = sqlSettings.slowQueryTimeMilli
        },
        listener)
    }

    new SessionDbConnection(sessionFactory)
  }
}
