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
package io.greenbus.app.actor.util

import akka.actor.Actor
import com.typesafe.scalalogging.LazyLogging

trait StateMachine extends Actor {
  protected type StateType
  protected def start: StateType
  private var current: StateType = start

  protected def machine: PartialFunction[(Any, StateType), StateType]

  protected def unhandled(obj: Any, state: StateType)

  def receive = {
    case obj =>
      if (machine.isDefinedAt((obj, current))) {
        current = machine.apply((obj, current))
      } else {
        unhandled(obj, current)
      }
  }

}

trait TraceMessage

object NestedStateMachine {

  def stateToString(state: Any): String = {
    def prod(pr: Product) = pr.productIterator.map(_.getClass.getSimpleName).mkString("(", ", ", ")")
    state match {
      case pr: Tuple2[_, _] => prod(pr)
      case pr: Tuple3[_, _, _] => prod(pr)
      case pr: Tuple4[_, _, _, _] => prod(pr)
      case pr: Tuple5[_, _, _, _, _] => prod(pr)
      case o => o.getClass.getSimpleName
    }
  }
}
trait NestedStateMachine extends Actor with LazyLogging {
  import NestedStateMachine._

  protected def instanceId: Option[String] = None
  private def instancePrefix: String = instanceId.map(_ + ": ").getOrElse("")
  protected type StateType
  protected def start: StateType
  private var current: StateType = start

  protected def machine: PartialFunction[StateType, PartialFunction[Any, StateType]]

  protected def unhandled(obj: Any, state: StateType): Unit = {
    logger.warn(instancePrefix + "Saw unhandled event " + obj.getClass.getSimpleName + " in state " + stateToString(state))
  }

  final override def postStop(): Unit = {
    onShutdown(current)
  }

  protected def onShutdown(state: StateType): Unit = {
    logger.debug(instancePrefix + s"Shut down in state ${stateToString(current)} ($instanceId)")
  }

  def receive = {
    case obj =>
      obj match {
        case _: TraceMessage => if (logger.underlying.isTraceEnabled) {
          logger.trace(instancePrefix + s"Event ${obj.getClass.getSimpleName} in state ${stateToString(current)}")
        }
        case _ => if (logger.underlying.isDebugEnabled) {
          logger.debug(instancePrefix + s"Event ${obj.getClass.getSimpleName} in state ${stateToString(current)}")
        }
      }
      if (machine.isDefinedAt(current)) {
        val handler = machine.apply(current)
        if (handler.isDefinedAt(obj)) {
          val result = handler.apply(obj)

          if (logger.underlying.isDebugEnabled) {
            val resultStr = stateToString(result)
            val currentStr = stateToString(current)
            if (resultStr != currentStr) {
              logger.debug(instancePrefix + s"State change $currentStr to $resultStr")
            }
          }
          current = result
        } else {
          unhandled(obj, current)
        }
      } else {
        unhandled(obj, current)
      }
  }

}
