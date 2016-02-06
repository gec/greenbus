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

import scala.collection.mutable.ListBuffer
import org.apache.commons.cli.{ CommandLine, Options }
import org.apache.commons.cli
import java.io.PrintStream

trait RequiredInputRef[A] {
  def value: A
}
trait OptionalInputRef[A] {
  def value: Option[A]
}
trait SeqInputRef[A] {
  def value: Seq[A]
}
trait SwitchInputRef {
  def value: Boolean
}

sealed trait SettableInputRef
trait SingleRef extends SettableInputRef {
  def set(in: String)
}
trait SeqRef extends SettableInputRef {
  def set(in: Seq[String])
}
trait SwitchRef extends SettableInputRef {
  def set(v: Boolean)
}

class RequiredRefContainer[A](parser: String => A) extends RequiredInputRef[A] with SingleRef {
  protected var v = Option.empty[A]
  def value: A = v.get
  def set(in: String) {
    v = Some(parser(in))
  }
}
class OptionalRefContainer[A](parser: String => A) extends OptionalInputRef[A] with SingleRef {
  protected var v = Option.empty[A]
  def value: Option[A] = v
  def set(in: String) {
    v = Some(parser(in))
  }
}
class SeqRefContainer[A](parser: String => A) extends SeqInputRef[A] with SeqRef {
  protected var v = Seq.empty[A]
  def value: Seq[A] = v
  def set(in: Seq[String]) {
    v = in map parser
  }
}
class SwitchRefContainer extends SwitchInputRef with SwitchRef {
  protected var v = false
  def value: Boolean = v
  def set(v: Boolean) {
    this.v = v
  }
}

sealed trait PresenceType
case object SingleRequired extends PresenceType
case object SingleOptional extends PresenceType
case object Sequential extends PresenceType
case object Switch extends PresenceType

trait ArgumentDesc {
  val name: String
  val displayName: String
  val typ: PresenceType
}

trait OptionDesc {
  val shortName: Option[String]
  val longName: Option[String]
  val desc: String
  val typ: PresenceType
}

case class RegisteredArg(ref: SingleRef, name: String, displayName: String, typ: PresenceType) extends ArgumentDesc
case class RegisteredRepeatedArg(ref: SeqRef, name: String, displayName: String, typ: PresenceType) extends ArgumentDesc
case class RegisteredOption(ref: SettableInputRef, shortName: Option[String], longName: Option[String], desc: String, typ: PresenceType) extends OptionDesc

object Command {

  def optionString(regOp: OptionDesc): String = {
    regOp.shortName.map("-" + _ + "  ").getOrElse("") + regOp.longName.map("--" + _).getOrElse("") + (if (regOp.typ == Switch) "" else "  <value>")
  }
}

trait Command[ContextType] {

  val commandName: String
  val description: String

  private val argsRequired = new ListBuffer[RegisteredArg]
  private val argsOptional = new ListBuffer[RegisteredArg]
  private var argsRepeated = Option.empty[RegisteredRepeatedArg]

  private val options = new ListBuffer[RegisteredOption]
  private val optionMap = scala.collection.mutable.Map.empty[String, SettableInputRef]

  def argumentDescriptions: Seq[ArgumentDesc] = argsRequired.toVector ++ argsOptional.toVector ++ argsRepeated.toVector
  def optionDescriptions: Seq[OptionDesc] = options.toVector

  private def addArg[A](ref: RequiredInputRef[A] with SingleRef, name: String, desc: String): RequiredInputRef[A] = {
    if (argsOptional.nonEmpty) {
      throw new IllegalArgumentException("Cannot add a required argument after an optional argument")
    }
    if (argsRepeated.nonEmpty) {
      throw new IllegalArgumentException("Cannot add a required argument after a repeated argument")
    }
    argsRequired += RegisteredArg(ref, name, desc, SingleRequired)
    ref
  }
  private def addArgOpt[A](ref: OptionalInputRef[A] with SingleRef, name: String, desc: String): OptionalInputRef[A] = {
    if (argsRepeated.nonEmpty) {
      throw new IllegalArgumentException("Cannot add an optional argument after a repeated argument")
    }
    argsOptional += RegisteredArg(ref, name, desc, SingleOptional)
    ref
  }
  private def addArgRepeated[A](ref: SeqInputRef[A] with SeqRef, name: String, desc: String): SeqInputRef[A] = {
    if (argsOptional.nonEmpty) {
      throw new IllegalArgumentException("Cannot add a repeated argument after an optional argument")
    }
    if (argsRepeated.nonEmpty) {
      throw new IllegalArgumentException("Cannot only have one repeated argument")
    }
    argsRepeated = Some(RegisteredRepeatedArg(ref, name, desc, Sequential))
    ref
  }

  private def addAnyOption[A <: SettableInputRef](ref: A, short: Option[String], long: Option[String], desc: String, typ: PresenceType): A = {
    if (short.isEmpty && long.isEmpty) {
      throw new IllegalArgumentException(s"Must include either a short or long option form for option")
    }
    options += RegisteredOption(ref, short, long, desc, typ)
    short.foreach { s => optionMap += (s -> ref) }
    long.foreach { s => optionMap += (s -> ref) }
    ref
  }

  private def addOption[A](ref: OptionalInputRef[A] with SettableInputRef, short: Option[String], long: Option[String], desc: String): OptionalInputRef[A] = {
    addAnyOption(ref, short, long, desc, SingleOptional)
  }

  private def addOptionRepeated[A](ref: SeqInputRef[A] with SettableInputRef, short: Option[String], long: Option[String], desc: String): SeqInputRef[A] = {
    addAnyOption(ref, short, long, desc, Sequential)
  }

  private def addOptionSwitch[A](ref: SwitchInputRef with SettableInputRef, short: Option[String], long: Option[String], desc: String): SwitchInputRef = {
    addAnyOption(ref, short, long, desc, Switch)
  }

  protected class Registry[A](parser: String => A) {
    def arg(name: String, desc: String): RequiredInputRef[A] = addArg(new RequiredRefContainer[A](parser), name, desc)
    def argOptional(name: String, desc: String): OptionalInputRef[A] = addArgOpt(new OptionalRefContainer[A](parser), name, desc)
    def argRepeated(name: String, desc: String): SeqInputRef[A] = addArgRepeated(new SeqRefContainer[A](parser), name, desc)

    def option(short: Option[String], long: Option[String], desc: String): OptionalInputRef[A] = addOption(new OptionalRefContainer[A](parser), short, long, desc)
    def optionRepeated(short: Option[String], long: Option[String], desc: String): SeqInputRef[A] = addOptionRepeated(new SeqRefContainer[A](parser), short, long, desc)
  }

  def optionSwitch(short: Option[String], long: Option[String], desc: String): SwitchInputRef = addOptionSwitch(new SwitchRefContainer, short, long, desc)

  protected val strings = new Registry[String](v => v)
  protected val ints = new Registry[Int](_.toInt)
  protected val doubles = new Registry[Double](_.toDouble)

  def printHelpString(stream: PrintStream = System.out) {
    import stream._
    println("DESCRIPTION")
    println(s"\t$commandName")
    println("")
    println(s"\t$description")
    println("")
    println("SYNTAX")
    println(s"\t$syntax")
    println("")
    if (argsRequired.nonEmpty || argsOptional.nonEmpty || argsRepeated.nonEmpty) {
      println("ARGUMENTS")
      (argsRequired ++ argsOptional).foreach { regArg =>
        println("\t" + regArg.name)
        println("\t\t" + regArg.displayName)
        println("")
      }
      argsRepeated.foreach { regArg =>
        println("\t" + regArg.name)
        println("\t\t" + regArg.displayName)
        println("")
      }
    } else {
      println("")
    }
    if (options.nonEmpty) {
      println("OPTIONS")
      options.foreach { regOp =>
        val optStr = Command.optionString(regOp)
        println("\t" + optStr)
        println("\t\t" + regOp.desc)
        println("")
      }
      if (options.isEmpty) {
        println("")
      }
    }
  }

  def syntax: String = {
    val optString = if (options.nonEmpty) "[options] " else ""

    commandName + " " +
      optString +
      argsRequired.toList.map("<" + _.name + ">").mkString(" ") + " " +
      argsRepeated.map("[<" + _.name + "> ...]").getOrElse("") +
      argsOptional.map("[<" + _.name + ">]").mkString(" ")
  }

  def buildOptions: Options = {
    val opts = new Options

    options.foreach { regOpt =>

      val shortName = regOpt.shortName.getOrElse(null)
      val longName = regOpt.longName.getOrElse(null)

      regOpt.ref match {
        case ref: SingleRef => opts.addOption(new cli.Option(shortName, longName, true, regOpt.desc))
        case ref: SeqRef => opts.addOption(new cli.Option(shortName, longName, true, regOpt.desc))
        case ref: SwitchRef => opts.addOption(new cli.Option(shortName, longName, false, regOpt.desc))
      }
    }

    opts
  }

  private def interpretLine(line: CommandLine) {

    options.foreach { regOpt =>
      val key = (regOpt.shortName orElse regOpt.longName).getOrElse { throw new IllegalStateException("Must have short or long name for option") }
      if (line.hasOption(key)) {
        regOpt.ref match {
          case ref: SingleRef => ref.set(line.getOptionValue(key))
          case ref: SeqRef => ref.set(line.getOptionValues(key).toSeq)
          case ref: SwitchRef => ref.set(true)
        }
      }
    }

    val argsList = line.getArgs.toList

    if (argsList.size < argsRequired.size) {
      throw new IllegalArgumentException("Missing arguments: " + argsRequired.drop(argsList.size).map("<" + _.name + ">").mkString(" "))
    }
    val (reqdArgs, extraArgs) = argsList.splitAt(argsRequired.size)

    argsRequired.map(_.ref).zip(reqdArgs).foreach {
      case (ref: SingleRef, v: String) => ref.set(v)
    }
    if (argsOptional.nonEmpty) {
      argsOptional.map(_.ref).zip(extraArgs).foreach {
        case (ref: SingleRef, v: String) => ref.set(v)
      }
    } else if (argsRepeated.nonEmpty) {
      argsRepeated.get.ref.set(extraArgs)
    }
  }

  def run(args: Array[String], context: ContextType) {
    val cmdOptions = buildOptions
    val parser = new cli.BasicParser

    val line = try {
      parser.parse(cmdOptions, args)
    } catch {
      case ex: Throwable =>
        throw new IllegalArgumentException(ex.getMessage)
    }

    interpretLine(line)

    execute(context)
  }

  protected def execute(context: ContextType)
}
