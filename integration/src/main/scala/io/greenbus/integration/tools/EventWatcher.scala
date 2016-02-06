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
package io.greenbus.integration.tools

import scala.concurrent.{ Future, Promise }

class EventWatcher[A] {
  private val mutex = new Object
  private var updates = List.empty[A]
  private var check = Option.empty[(List[A] => Boolean, Promise[List[A]])]

  def reset() {
    mutex.synchronized {
      updates = Nil
    }
  }

  def future(f: List[A] => Boolean): Future[List[A]] = {
    mutex.synchronized {
      val promise = Promise[List[A]]()
      check = Some((f, promise))
      promise.future
    }
  }

  def currentUpdates(): List[A] = {
    mutex.synchronized {
      updates.reverse
    }
  }

  def enqueue(a: A) {
    mutex.synchronized {
      updates ::= a
      check.foreach {
        case (fun, promise) =>
          val reversed = updates.reverse
          if (fun(reversed)) {
            check = None
            promise.success(reversed)
          }
      }
    }
  }
}
