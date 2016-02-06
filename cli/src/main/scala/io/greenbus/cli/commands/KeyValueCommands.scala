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
package io.greenbus.cli.commands

import io.greenbus.cli.view.KvView
import io.greenbus.cli.{ CliContext, Command }
import io.greenbus.client.service.ModelService
import io.greenbus.client.service.proto.Model.{ EntityKeyValue, StoredValue }
import io.greenbus.client.service.proto.ModelRequests.{ EntityKeyPair, EntityKeySet }

import scala.concurrent.Await
import scala.concurrent.duration._

class KvListCommand extends Command[CliContext] {

  val commandName = "kv:list"
  val description = "List keys of key-values for entity specified by name"

  val name = strings.arg("entity name", "Entity name")

  protected def execute(context: CliContext) {
    val client = ModelService.client(context.session)

    val result = Await.result(client.get(EntityKeySet.newBuilder().addNames(name.value).build()), 5000.milliseconds)

    result.headOption match {
      case None => println(s"Entity with name '${name.value}' not found.")
      case Some(ent) => {
        val keyResults = Await.result(client.getEntityKeys(Seq(ent.getUuid)), 5000.milliseconds)

        if (keyResults.nonEmpty) {
          val kvResults = Await.result(client.getEntityKeyValues(keyResults), 5000.milliseconds)

          val nameAndKv = kvResults.map(r => (name.value, r)).sortBy(_._2.getKey)
          KvView.printTable(nameAndKv)
        }
      }
    }
  }
}

class KvViewCommand extends Command[CliContext] {

  val commandName = "kv:view"
  val description = "View key-value for entity"

  val name = strings.arg("entity name", "Entity name")
  val key = strings.arg("key", "Key")

  protected def execute(context: CliContext) {
    val client = ModelService.client(context.session)

    val result = Await.result(client.get(EntityKeySet.newBuilder().addNames(name.value).build()), 5000.milliseconds)

    result.headOption match {
      case None => println(s"Entity with name '${name.value}' not found.")
      case Some(ent) => {
        val keyPair = EntityKeyPair.newBuilder().setUuid(ent.getUuid).setKey(key.value).build()
        val kvpResults = Await.result(client.getEntityKeyValues(Seq(keyPair)), 5000.milliseconds)

        kvpResults.headOption match {
          case None => println(s"Key not found.")
          case Some(kvp) => {
            KvView.printInspect(kvp)
          }
        }
      }
    }
  }
}

class KvSetCommand extends Command[CliContext] {

  val commandName = "kv:set"
  val description = "Set key-value for entity"

  val name = strings.arg("entity name", "Entity name")
  val key = strings.arg("key", "Key")

  val boolVal = ints.option(Some("b"), Some("bool-val"), "Boolean value [0, 1]")

  val intVal = ints.option(Some("i"), Some("int-val"), "Integer value")

  val floatVal = doubles.option(Some("f"), Some("float-val"), "Floating point value")

  val stringVal = strings.option(Some("s"), Some("string-val"), "String value")

  protected def execute(context: CliContext) {

    List(boolVal.value, intVal.value, floatVal.value, stringVal.value).flatten.size match {
      case 0 => throw new IllegalArgumentException("Must include at least one value option")
      case 1 =>
      case n => throw new IllegalArgumentException("Must include only one value option")
    }

    def storedValue = {
      val b = StoredValue.newBuilder()
      if (boolVal.value.nonEmpty) {
        b.setBoolValue(boolVal.value != Some(0))
      } else if (intVal.value.nonEmpty) {
        b.setInt64Value(intVal.value.get)
      } else if (floatVal.value.nonEmpty) {
        b.setDoubleValue(floatVal.value.get)
      } else if (stringVal.value.nonEmpty) {
        b.setStringValue(stringVal.value.get)
      } else {
        throw new IllegalArgumentException("Must include one value option")
      }
      b.build()
    }

    val client = ModelService.client(context.session)

    val result = Await.result(client.get(EntityKeySet.newBuilder().addNames(name.value).build()), 5000.milliseconds)

    result.headOption match {
      case None => println(s"Entity with name '${name.value}' not found.")
      case Some(ent) => {
        val kv = EntityKeyValue.newBuilder()
          .setUuid(ent.getUuid)
          .setKey(key.value)
          .setValue(storedValue)
          .build()

        val kvpResults = Await.result(client.putEntityKeyValues(Seq(kv)), 5000.milliseconds)

        kvpResults.headOption match {
          case None => println(s"Key not put.")
          case Some(kvp) => {
            KvView.printInspect(kvp)
          }
        }
      }
    }
  }
}

class KvDeleteCommand extends Command[CliContext] {

  val commandName = "kv:delete"
  val description = "Delete key-value for entity specified by name"

  val name = strings.arg("entity name", "Entity name")
  val key = strings.arg("key", "Key")

  protected def execute(context: CliContext) {
    val client = ModelService.client(context.session)

    val result = Await.result(client.get(EntityKeySet.newBuilder().addNames(name.value).build()), 5000.milliseconds)

    result.headOption match {
      case None => println(s"Entity with name '${name.value}' not found.")
      case Some(ent) => {
        val keyPair = EntityKeyPair.newBuilder().setUuid(ent.getUuid).setKey(key.value).build()
        val kvpResults = Await.result(client.deleteEntityKeyValues(Seq(keyPair)), 5000.milliseconds)

        kvpResults.headOption match {
          case None => println(s"Key not found.")
          case Some(kvp) => {
            KvView.printInspect(kvp)
          }
        }
      }
    }
  }
}