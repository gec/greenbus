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
package io.greenbus.sql.test

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, FunSuite }
import io.greenbus.util.Timing
import com.typesafe.scalalogging.slf4j.Logging
import org.squeryl.Schema
import io.greenbus.sql.{ SqlSettings, DbConnection, DbConnector }

object ConnectionStorage {
  private var lastConnection = Option.empty[DbConnection]
  private var lastOptions = Option.empty[SqlSettings]

  def connect(sqlSettings: SqlSettings): DbConnection = {
    if (lastOptions != Some(sqlSettings)) {
      lastConnection = Some(DbConnector.connect(sqlSettings))
      lastOptions = Some(sqlSettings)
    }
    lastConnection.get
  }

  var dbNeedsReset = true
}

trait DatabaseUsingTestBaseNoTransaction extends FunSuite with ShouldMatchers with BeforeAndAfterAll with BeforeAndAfterEach with Logging {
  lazy val dbConnection = ConnectionStorage.connect(SqlSettings.load("io.greenbus.test.cfg"))

  def schemas: Seq[Schema]

  protected def alwaysReset = true

  override def beforeAll() {
    if (ConnectionStorage.dbNeedsReset || alwaysReset) {
      val prepareTime = Timing.benchmark {
        dbConnection.transaction {
          schemas.foreach { s =>
            s.drop
            s.create
          }
        }
      }
      logger.info("Prepared db in: " + prepareTime)
      ConnectionStorage.dbNeedsReset = false
    }
  }

  /**
   * we only need to rebuild the database schema when a Non-Transaction-Safe test suite has been run.
   */
  protected def resetDbAfterTestSuite: Boolean
  override def afterAll() {
    ConnectionStorage.dbNeedsReset = resetDbAfterTestSuite
  }
}

abstract class DatabaseUsingTestBase extends DatabaseUsingTestBaseNoTransaction with RunTestsInsideTransaction {
  protected override def resetDbAfterTestSuite = false
}

abstract class DatabaseUsingTestNotTransactionSafe extends DatabaseUsingTestBaseNoTransaction {
  protected override def resetDbAfterTestSuite = true
}