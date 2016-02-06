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
package io.greenbus.services

import io.greenbus.sql.{ DbConnector, SqlSettings, DbConnection }
import io.greenbus.services.model.SquerylEntityModel
import io.greenbus.util.Timing
import io.greenbus.services.data.ServicesSchema
import io.greenbus.services.framework.NullModelNotifier

object DbBenchmarking {

  def connectSql(): DbConnection = {
    val config = SqlSettings.load("../io.greenbus.sql.cfg")
    DbConnector.connect(config)
  }

  def buildEntities(count: Int): Seq[(String, Seq[String])] = {
    Range(0, count).map(i => (s"ent$i", Seq("typeA", "typeB", "typeC")))
  }

  def trial(sql: DbConnection, entCount: Int, record: Boolean) {
    val ents = buildEntities(entCount)

    sql.transaction {
      ServicesSchema.reset()
    }

    val sepTrans = Timing.benchmark {
      ents.foreach { ent =>
        sql.transaction {
          SquerylEntityModel.putEntities(NullModelNotifier, Nil, Seq((ent._1, ent._2.toSet)))
        }
      }
    }

    val sepTransSingle = Timing.benchmark {
      ents.foreach { ent =>
        sql.transaction {
          SquerylEntityModel.putEntity(NullModelNotifier, ent._1, ent._2, false, false, false)
        }
      }
    }

    val oneTransSeparate = Timing.benchmark {
      sql.transaction {
        ents.foreach(ent => SquerylEntityModel.putEntity(NullModelNotifier, ent._1, ent._2, false, false, false))
      }
    }

    val batched = Timing.benchmark {
      sql.transaction {
        SquerylEntityModel.putEntities(NullModelNotifier, Nil, ents.map(ent => (ent._1, ent._2.toSet)))
      }
    }

    if (record) {
      println(Seq(entCount, sepTrans, sepTransSingle, oneTransSeparate, batched).mkString("\t"))
    }
  }

  def main(args: Array[String]) {

    val sql = connectSql()

    // warmup
    trial(sql, 5, false)

    Range(0, 12).foreach(n => trial(sql, Math.pow(2, n).toInt, true))

  }
}
