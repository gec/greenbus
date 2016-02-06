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

import java.sql.Connection
import org.squeryl.logging.StatisticsListener

/**
 * contains the session factory necessary to talk to the database. Handles the setting up of the
 * session and transaction blocks.
 *
 * These functions should be used instead of PrimitiveTypeMode.transaction
 */
trait DbConnection {

  /**
   * will open and close a new transaction around the passed in code
   */
  def transaction[A](listener: Option[StatisticsListener])(fun: => A): A
  def transaction[A](fun: => A): A

  /**
   * will open a new transaction if one doesn't already exist, otherwise goes into same transaction
   */
  def inTransaction[A](fun: => A): A

  /**
   * get the underlying jdbc Connection for use in low-level tasks like schema setup, cleanup.
   * Handled in a block because we need to return the connection to the session pool when we are done
   */
  def underlyingConnection[A](fun: (Connection) => A): A
}

