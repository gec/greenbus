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

import scala.annotation.tailrec
import java.util.concurrent.TimeoutException
import com.typesafe.scalalogging.slf4j.Logging

object PollingUtils extends Logging {

  def pollForSuccess[A](delayMs: Long, timeoutMs: Long)(f: => A): A = {
    pollForSuccessWithIter(delayMs, timeoutMs) { _ => f }
  }

  def pollForSuccessWithIter[A](delayMs: Long, timeoutMs: Long)(f: Int => A): A = {
    val start = System.currentTimeMillis()

    @tailrec
    def poll(i: Int): A = {
      val now = System.currentTimeMillis()
      if ((now - start) < timeoutMs) {

        val resultOpt = try {
          logger.info("Polling")
          Some(f(i))
        } catch {
          case ex: Throwable =>
            logger.info("Missed poll: " + ex)
            None
        }

        resultOpt match {
          case Some(result) => result
          case None =>
            val elapsedInPoll = System.currentTimeMillis() - now
            if (elapsedInPoll >= delayMs) {
              poll(i + 1)
            } else {
              Thread.sleep(delayMs - elapsedInPoll)
              poll(i + 1)
            }
        }
      } else {
        throw new TimeoutException("Failed operation within time limit")
      }
    }

    poll(1)
  }
}
