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

import org.scalatest._
import io.greenbus.sql.DbConnection

trait RunTestsInsideTransaction extends FunSuite with BeforeAndAfterEach {

  def dbConnection: DbConnection

  class TransactionAbortException extends Exception

  /**
   * test classes shouldn't use beforeEach if using RunTestsInsideTransaction
   */
  final override def beforeEach() {}

  final override def afterEach() {}

  /**
   * should be overriden by tests to run setup code inside same transaction
   * as the test run.
   */
  def beforeEachInTransaction() {}

  /**
   * should be overriden by tests to run teardown code inside same transaction
   * as the test run.
   */
  def afterEachInTransaction() {}

  override protected def runTest(testName: String, args: Args): Status = {
    neverCompletingTransaction {
      beforeEachInTransaction()
      val result = super.runTest(testName, args)
      afterEachInTransaction()
      result
    }
  }

  /**
   * each test occur from within a transaction, that way when the test completes _all_ changes
   * made during the test are reverted so each test gets a clean environment to test against
   */
  def neverCompletingTransaction[A](func: => A): A = {
    var result = Option.empty[A]
    try {
      dbConnection.transaction {
        result = Some(func)
        // we abort the transaction if we get to here, so changes get rolled back
        throw new TransactionAbortException
      }
    } catch {
      case ex: TransactionAbortException =>
        result.get
    }
  }
}