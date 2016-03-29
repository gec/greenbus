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
package io.greenbus.measproc.processing

import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.client.service.proto.Measurements._
import io.greenbus.measproc.processing.Trigger.{ KeyedCondition, SimpleCondition }
import io.greenbus.util.Optional._

object Trigger extends Logging {

  /**
   * Super-type for condition logic.
   */
  //type Condition = (String, Measurement, Boolean) => Boolean

  sealed trait Condition
  trait SimpleCondition extends Condition {
    def apply(m: Measurement, b: Boolean): Boolean
  }
  trait KeyedCondition extends Condition {
    def apply(key: String, m: Measurement, b: Boolean): Boolean
  }

  /**
   * Helper function to extract analog values from measurements
   * @param m   Measurement
   * @return    Value in form of Double, or None
   */
  def analogValue(m: Measurement): Option[Double] = {
    optGet(m.hasDoubleVal, m.getDoubleVal) orElse
      optGet(m.hasIntVal, m.getIntVal.asInstanceOf[Double])
  }

  /**
   * Evaluates a list of triggers against a measurement
   * @param m           Measurement input
   * @param cache       Previous trigger state cache
   * @param triggers    Triggers associated with the measurement point.
   * @return            Result of trigger/action processing.
   */
  def processAll(pointKey: String, m: Measurement, cache: ObjectCache[Boolean], triggers: List[Trigger]): Option[Measurement] = {
    val r = triggers.foldLeft(m) { (meas, trigger) =>
      // Evaluate trigger
      logger.trace("Applying trigger: " + trigger + " to meas: " + meas)

      trigger.process(pointKey, meas, cache) match {
        case None => return None
        case Some((result, true)) => return Some(result)
        case Some((result, false)) => result
      }
    }
    Some(r)
  }

}

/**
 * Interface for conditional measurement processing logic
 */
trait Trigger {
  /**
   * Conditionally process input measurement. May raise flag to stop further processing.
   *
   * @param pointKey  Point of measurement
   * @param m         Input measurement.
   * @param cache     Previous trigger state cache.
   * @return          Measurement result, flag to stop further processing.
   */
  def process(pointKey: String, m: Measurement, cache: ObjectCache[Boolean]): Option[(Measurement, Boolean)]
}

/**
 * Implementation of conditional measurement processing logic. Maintains a condition function
 * and a list of actions to perform. Additionally may stop processing when condition is in a
 * given state.
 */
class BasicTrigger(
  cacheId: String,
  conditions: List[Trigger.Condition],
  actions: List[Action],
  stopProcessing: Option[Action.ActivationType])
    extends Trigger with Logging {

  def process(pointKey: String, m: Measurement, cache: ObjectCache[Boolean]): Option[(Measurement, Boolean)] = {

    // Get the previous state of this trigger
    logger.trace("CacheId: " + cacheId + ", Prev cache: " + cache.get(cacheId))
    val prev = cache.get(cacheId) getOrElse false

    // Evaluate the current state
    //info("Conditions: " + conditions)
    val state = if (conditions.isEmpty) {
      true
    } else {
      conditions.forall {
        case cond: SimpleCondition => cond(m, prev)
        case cond: KeyedCondition => cond(pointKey, m, prev)
      }
    }

    // Store the state in the previous state cache
    cache.put(cacheId, state)

    // Allow actions to determine if they should evaluate, roll up a result measurement
    //info("Trigger state: " + state)

    def evalActions(meas: Measurement, a: List[Action]): Option[Measurement] = a match {
      case Nil => Some(meas)
      case head :: tail => {
        head.process(meas, state, prev).flatMap(next => evalActions(next, tail))
      }
    }

    evalActions(m, actions).map { result =>
      // Check stop processing flag (default to continue processing)
      val stopProc = stopProcessing.map(_(state, prev)) getOrElse false
      (result, stopProc)
    }
  }

  override def toString = cacheId + " (" + actions.mkString(", ") + ")"
}

