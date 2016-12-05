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
package io.greenbus.services.framework

import io.greenbus.msg.amqp.{ AmqpMessage, AmqpAddressedMessage }
import org.scalatest.FunSuite
import org.scalatest.Matchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scala.collection.mutable.Queue

@RunWith(classOf[JUnitRunner])
class ModelNotifierTest extends FunSuite with Matchers {

  case class First(str: String)
  case class Second(str: String)

  test("Mapper") {
    val queue1 = Queue.empty[(ModelEvent, First)]
    val queue2 = Queue.empty[(ModelEvent, Second)]

    val trans1 = new ModelEventTransformer[First] {
      def toMessage(typ: ModelEvent, event: First): AmqpAddressedMessage = {
        queue1 += ((typ, event))
        AmqpAddressedMessage("exch1", "key1", AmqpMessage("str1".getBytes(), None, None))
      }
    }

    val trans2 = new ModelEventTransformer[Second] {
      def toMessage(typ: ModelEvent, event: Second): AmqpAddressedMessage = {
        queue2 += ((typ, event))
        AmqpAddressedMessage("exch2", "key2", AmqpMessage("str2".getBytes(), None, None))
      }
    }

    val mapper = new ModelEventMapper
    mapper.register(classOf[First], trans1)
    mapper.register(classOf[Second], trans2)

    mapper.toMessage(Created, First("one"))
    mapper.toMessage(Deleted, Second("one"))

    queue1.toSeq should equal(Seq((Created, First("one"))))
    queue2.toSeq should equal(Seq((Deleted, Second("one"))))
  }

}
