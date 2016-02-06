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

import io.greenbus.cli.{ ManagementCliContext, Command }
import scala.concurrent.Await
import scala.concurrent.duration._
import io.greenbus.client.service.LoginService
import io.greenbus.client.service.proto.LoginRequests.LogoutRequest
import io.greenbus.client.ServiceHeaders

class LoginCommand extends Command[ManagementCliContext] {

  val commandName = "login"
  val description = "Obtain an auth token from the services"

  val name = strings.arg("user name", "Agent name to login as.")

  val passOpt = strings.option(Some("p"), Some("password"), "Supply password instead of prompting, useful for scripting. WARNING: Password will be visible in command history.")

  protected def execute(context: ManagementCliContext) {

    val password = passOpt.value.getOrElse {
      println("Enter password: ")
      readLine()
    }

    val session = Await.result(context.connection.login(name.value, password), 5000.milliseconds)

    context.setSession(Some(session))
    context.setAgent(Some(name.value))
    context.setToken(session.headers.get(ServiceHeaders.tokenHeader()))
  }
}

class LogoutCommand extends Command[ManagementCliContext] {

  val commandName = "logout"
  val description = "Invalidate auth token and remove it"

  protected def execute(context: ManagementCliContext) {

    val sess = context.session
    val token = sess.headers.get(ServiceHeaders.tokenHeader()).getOrElse {
      throw new IllegalArgumentException("No auth token in session")
    }

    val client = LoginService.client(sess)
    val success = Await.result(client.logout(LogoutRequest.newBuilder().setToken(token).build()), 5000.milliseconds)

    context.setSession(None)
    context.setAgent(None)
  }
}