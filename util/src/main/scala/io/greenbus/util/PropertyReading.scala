/**
 * Copyright 2011-2016 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.greenbus.util

import java.util.Properties
import scala.collection.JavaConversions._
import java.io.FileInputStream

class ConfigurationException(message: String) extends Exception(message)

object PropertyReading {

  def loadFile(filename: String): Map[String, String] = {
    val properties = new Properties
    try {
      properties.load(new FileInputStream(filename))
      asMap(properties)
    } catch {
      case ex: Throwable =>
        throw new ConfigurationException("Problem loading configuration file: " + ex.getMessage)
    }
  }

  private def asMap(properties: Properties): Map[String, String] = {
    properties.stringPropertyNames().map(name => (name, properties.getProperty(name))).toMap
  }

  private def missing(name: String) = new ConfigurationException(s"Missing property: '$name'")

  def optional(props: Map[String, String], name: String): Option[String] = {
    Option(System.getProperty(name)) orElse props.get(name)
  }

  def get(props: Map[String, String], name: String): String = {
    optional(props, name) getOrElse (throw missing(name))
  }

  def optionalLong(props: Map[String, String], name: String): Option[Long] = {
    checkSignedInt(name) {
      optional(props, name).map(_.toLong)
    }
  }

  def getLong(props: Map[String, String], name: String): Long = {
    checkSignedInt(name) {
      get(props, name).toLong
    }
  }

  def optionalInt(props: Map[String, String], name: String): Option[Int] = {
    checkSignedInt(name) {
      optional(props, name).map(_.toInt)
    }
  }

  def getInt(props: Map[String, String], name: String): Int = {
    checkSignedInt(name) {
      get(props, name).toInt
    }
  }

  private def checkSignedInt[A](name: String)(fun: => A): A = {
    try {
      fun
    } catch {
      case ex: NumberFormatException =>
        throw new ConfigurationException(s"Configuration property '$name' must be a signed integer.")
    }
  }
}
