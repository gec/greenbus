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
package io.greenbus.sql.impl

import org.squeryl.{ PrimitiveTypeMode, Session }
import java.sql.Connection
import io.greenbus.sql.DbConnection
import com.typesafe.scalalogging.slf4j.Logging
import org.squeryl.logging.StatisticsListener

/**
 * default DbConnection implementation that takes a session factory function and generates
 * a new session whenever a new transaction is needed
 */
class SessionDbConnection(sessionFactory: (Option[StatisticsListener]) => Session) extends DbConnection with Logging {

  def transaction[A](fun: => A): A = {
    transaction(None)(fun)
  }

  def transaction[A](listener: Option[StatisticsListener])(fun: => A): A = {
    val session = sessionFactory(listener)
    if (logger.underlying.isTraceEnabled) {
      session.setLogger(s => logger.trace(s))
    }

    val result = PrimitiveTypeMode.using(session) {
      PrimitiveTypeMode.transaction(session) {
        fun
      }
    }
    result
  }

  def inTransaction[A](fun: => A): A = {

    if (Session.hasCurrentSession) fun
    else transaction(fun)

  }

  def underlyingConnection[A](fun: (Connection) => A) = {
    val c = if (Session.hasCurrentSession) Session.currentSession.connection
    else sessionFactory(None).connection

    try {
      fun(c)
    } finally {
      // return the connection to the pool
      c.close()
    }

  }

}
