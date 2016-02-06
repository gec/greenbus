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

object Optional {

  /**
   * calls the has (or all of the has) functions, taking advantage of short circuiting, to return None if any of the has functions
   * fail or the result of the get function wrapped in Some.
   */
  def optGet[A](has: => Boolean, get: => A): Option[A] = if (has) Some(get) else None
  def optGet[A](has: => Boolean, has1: => Boolean, get: => A): Option[A] = if (has && has1) Some(get) else None
  def optGet[A](has: => Boolean, has1: => Boolean, has2: => Boolean, get: => A): Option[A] = if (has && has1 && has2) Some(get) else None

  // if (isTrue) Some(obj) else None   --->    isTrue thenGet obj | isTrue ? obj
  implicit def boolWrapper(b: Boolean) = new OptionalBoolean(b)
  class OptionalBoolean(b: Boolean) {
    def thenGet[A](t: => A): Option[A] = if (b) Some(t) else None
  }
}
