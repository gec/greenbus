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
package io.greenbus.ldr

import java.io.File

import org.apache.commons.io.FileUtils
import io.greenbus.loader.set.Mdl.FlatModelFragment
import io.greenbus.loader.set._

object KvFileStorage {

  val ignored = Set("calculation", "triggerSet")

  def mapKeyValuesToFileReferences(flat: FlatModelFragment, buildFilename: (String, String) => String): (FlatModelFragment, Seq[(String, Array[Byte])]) = {

    def mapFields(f: EntityFields): (EntityFields, Seq[(String, Array[Byte])]) = {
      val name = f.id match {
        case FullEntId(_, name) => name
        case NamedEntId(name) => name
        case _ => throw new LoadingException("Names needs to be resolved to write key values")
      }

      val fileDescs = Vector.newBuilder[(String, Array[Byte])]

      val mappedKvs = f.kvs.map {
        case (key, vh) =>
          if (ignored.contains(key)) {
            (key, vh)
          } else {
            vh match {
              case ByteArrayValue(bytes) =>
                val filename = buildFilename(name, key)
                fileDescs += ((filename, bytes))
                (key, FileReference(filename))
              case v => (key, v)
            }
          }
      }

      (f.copy(kvs = mappedKvs), fileDescs.result())
    }

    val refs = Vector.newBuilder[(String, Array[Byte])]

    def genFieldMap(f: EntityFields): EntityFields = {
      val (mappedFields, mappedRefs) = mapFields(f)
      refs ++= mappedRefs
      mappedFields
    }

    val ents = flat.modelEntities.map(obj => obj.copy(fields = genFieldMap(obj.fields)))
    val points = flat.points.map(obj => obj.copy(fields = genFieldMap(obj.fields)))
    val commands = flat.commands.map(obj => obj.copy(fields = genFieldMap(obj.fields)))
    val endpoints = flat.endpoints.map(obj => obj.copy(fields = genFieldMap(obj.fields)))

    val model = flat.copy(modelEntities = ents, points = points, commands = commands, endpoints = endpoints)

    (model, refs.result())
  }

  def resolveFileReferences(flat: FlatModelFragment, parent: File): FlatModelFragment = {

    def resolveFields(fields: EntityFields): EntityFields = {
      val kvs = fields.kvs.map {
        case (key, vh) =>
          vh match {
            case FileReference(filename) =>
              val bytes = try {
                FileUtils.readFileToByteArray(new File(parent, filename))
              } catch {
                case ex: Throwable => throw new LoadingException(s"Could not open file $filename: " + ex.getMessage)
              }
              (key, ByteArrayValue(bytes))
            case _ => (key, vh)
          }
      }
      fields.copy(kvs = kvs)
    }

    flat.copy(
      modelEntities = flat.modelEntities.map(obj => obj.copy(fields = resolveFields(obj.fields))),
      points = flat.points.map(obj => obj.copy(fields = resolveFields(obj.fields))),
      commands = flat.commands.map(obj => obj.copy(fields = resolveFields(obj.fields))),
      endpoints = flat.endpoints.map(obj => obj.copy(fields = resolveFields(obj.fields))))
  }
}
