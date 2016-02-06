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

object Documenter {

  def main(args: Array[String]): Unit = {
    val commands = CliMain.commandList.map(f => f())

    val sorted = commands.sortBy(_.commandName)

    val namePrefix = "#### "
    val usagePrefix = "\t"

    val descriptions = sorted.map { cmd =>
      val sb = new StringBuilder

      sb.append(namePrefix + cmd.commandName + "\n")
      sb.append("\n")
      sb.append(cmd.description + "\n")
      sb.append("\n")
      sb.append("Usage:\n")
      sb.append("\n")
      sb.append(usagePrefix + cmd.syntax + "\n")
      sb.append("\n")

      val args = cmd.argumentDescriptions

      if (args.nonEmpty) {
        sb.append("Arguments:\n")
        sb.append("\n")
        args.foreach { arg =>
          sb.append("`" + arg.name + "`" + " - " + arg.displayName + "\n\n")
        }
      }

      val options = cmd.optionDescriptions

      if (options.nonEmpty) {
        sb.append("Options:\n")
        sb.append("\n")
        options.foreach { opt =>
          sb.append("\t" + Command.optionString(opt) + "\n\n")
          sb.append(opt.desc + "\n\n")
        }
      }

      sb.result()
    }

    println(descriptions.mkString("\n"))
  }
}
