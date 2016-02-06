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
package io.greenbus.cli

import io.greenbus.msg.Session
import jline.console.ConsoleReader
import io.greenbus.client.ServiceConnection

trait CliContext {
  def reader: ConsoleReader
  def session: Session
  def agent: String
}

trait ManagementCliContext extends CliContext {

  def setSession(sessOpt: Option[Session])
  def setToken(token: Option[String])
  def setAgent(agentOpt: Option[String])

  def connection: ServiceConnection
  def tokenOption: Option[String]
  def agentOption: Option[String]
}

object CliContext {
  def apply(reader: ConsoleReader, connection: ServiceConnection): ManagementCliContext = new DefaultCliContext(reader, connection)

  private class DefaultCliContext(val reader: ConsoleReader, val connection: ServiceConnection) extends ManagementCliContext {
    private var sess = Option.empty[Session]
    private var agentName = Option.empty[String]
    private var tokenString = Option.empty[String]

    def session: Session = sess.getOrElse(throw new IllegalArgumentException("Not logged in."))
    def agent: String = agentName.getOrElse(throw new IllegalArgumentException("Not logged in."))

    def setSession(sessOpt: Option[Session]) {
      sess = sessOpt
    }
    def setToken(token: Option[String]) {
      tokenString = token
    }
    def setAgent(agentOpt: Option[String]) {
      agentName = agentOpt
    }

    def token = tokenString

    def agentOption = agentName
    def tokenOption = tokenString
  }
}
