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
package io.greenbus.services.authz

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import io.greenbus.services.model.{ ServiceTestBase, ModelTestHelpers }
import org.squeryl.PrimitiveTypeMode._
import io.greenbus.services.data.ServicesSchema._

@RunWith(classOf[JUnitRunner])
class EntityFilterTest extends ServiceTestBase {
  import ModelTestHelpers._

  def check(results: Set[String], denies: Seq[ResourceSelector], allows: Seq[ResourceSelector]) {
    val filter = ResourceSelector.buildFilter(denies, allows)
    val sql = from(entities)(ent => where(filter(ent.id)) select (ent.name))
    //println(sql.statement)
    val names = sql.toSeq
    names.size should equal(results.size)
    names.toSet should equal(results)
  }

  /*
   5  1  2  6
    3  4
   */
  test("Set filtering") {
    val ent01 = createEntity("ent01", Seq("typeA"))
    val ent02 = createEntity("ent02", Seq("typeB"))
    val ent03 = createEntity("ent03", Seq("typeX", "typeY"))
    val ent04 = createEntity("ent04", Seq("typeY", "typeZ"))
    val ent05 = createEntity("ent05", Seq("typeB"))
    val ent06 = createEntity("ent06", Seq("typeA"))
    createEdge(ent01.id, ent03.id, "owns", 1)
    createEdge(ent01.id, ent04.id, "owns", 1)
    createEdge(ent02.id, ent04.id, "owns", 1)
    createEdge(ent02.id, ent06.id, "unrelated", 1)

    val all = Set("ent01", "ent02", "ent03", "ent04", "ent05", "ent06")

    {
      val denies = Seq(TypeSelector(Seq("typeA", "typeB")))
      val allows = Nil
      check(Set("ent03", "ent04"), denies, allows)
    }

    {
      val denies = Nil
      val allows = Seq(TypeSelector(Seq("typeY")))
      check(Set("ent03", "ent04"), denies, allows)
    }

    {
      val denies = Seq(TypeSelector(Seq("typeZ")))
      val allows = Seq(TypeSelector(Seq("typeY")))
      check(Set("ent03"), denies, allows)
    }

    {
      val denies = Nil
      val allows = Seq(ParentSelector(Seq("ent01")))
      check(Set("ent03", "ent04"), denies, allows)
    }

    {
      val denies = Seq(ParentSelector(Seq("ent02")))
      val allows = Seq(ParentSelector(Seq("ent01")))
      check(Set("ent03"), denies, allows)
    }

  }
}
