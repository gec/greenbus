/**
 * Copyright 2011-2016 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the GNU Affero General Public License
 * Version 3.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.gnu.org/licenses/agpl.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.greenbus.measproc.lock

import java.sql.SQLDataException
import java.util.UUID

import org.squeryl.PrimitiveTypeMode._
import io.greenbus.services.data.{ ProcessingLockSchema, ProcessingLock }
import ProcessingLockSchema._
import org.squeryl.Session
import io.greenbus.sql.DbConnection

trait ProcessingLockService {
  def acquireOrHold(uuid: UUID, nodeId: String, expiryDurationMs: Long): Boolean
}

object PostgresLockModel extends ProcessingLockService {

  private def selectNow(): Long = {
    val s = Session.currentSession

    val st = s.connection.prepareStatement("select now()")
    val rs = st.executeQuery()

    if (!rs.next()) {
      throw new SQLDataException("select now() failed")
    }
    rs.getTimestamp(1).getTime
  }

  def acquireOrHold(uuid: UUID, nodeId: String, expiryDurationMs: Long): Boolean = {

    val current = processingLocks.where(r => r.id === uuid).forUpdate.toVector

    val dbNow = selectNow()

    current.headOption match {
      case None =>
        processingLocks.insert(ProcessingLock(uuid, nodeId, dbNow))
        true
      case Some(row) =>
        if (row.nodeId == nodeId || dbNow - row.lastCheckIn > expiryDurationMs) {
          processingLocks.update(ProcessingLock(uuid, nodeId, dbNow))
          true
        } else {
          false
        }
    }
  }
}

class TransactionPostgresLockModel(sql: DbConnection) extends ProcessingLockService {
  def acquireOrHold(uuid: UUID, nodeId: String, expiryDurationMs: Long): Boolean = {
    sql.transaction {
      PostgresLockModel.acquireOrHold(uuid, nodeId, expiryDurationMs)
    }
  }
}
