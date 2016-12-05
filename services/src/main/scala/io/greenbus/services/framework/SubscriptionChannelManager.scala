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
package io.greenbus.services.framework

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.proto.Envelope.SubscriptionEventType
import io.greenbus.msg.amqp.{ AmqpAddressedMessage, AmqpMessage, AmqpServiceOperations }
import io.greenbus.services.NotificationConversions

import scala.annotation.tailrec
import scala.collection.mutable

object SubscriptionChannelManager {
  def toRoutingKey(parts: Seq[Option[String]]): String = {
    val mapped: Seq[String] = parts.map {
      case None => "*"
      case Some(part) => part.replace(".", "-")
    }

    mapped.mkString(".")
  }

  def routingKeyPermutations(parts: Seq[Seq[String]]): Seq[String] = {
    val count = parts.size

    @tailrec
    def makePerms(results: List[String], prefix: Int, remaining: List[Seq[String]]): Seq[String] = {
      remaining match {
        case Nil => results
        case head :: tail =>
          val column = head.map(str => Nil.padTo(prefix, None) ::: (Some(str) :: Nil.padTo(count - prefix - 1, None)))
          val built = (column map toRoutingKey).toList
          makePerms(built ++ results, prefix + 1, tail)
      }
    }

    makePerms(Nil, 0, parts.toList)
  }

  def makeKey(parts: Seq[String]): String = {
    parts.map(_.replace('.', '-')).mkString(".")
  }
}

trait SubscriptionDescriptor[Proto] {

  def keyParts(payload: Proto): Seq[String]

  def notification(eventType: SubscriptionEventType, payload: Proto): Array[Byte]
}

trait SubscriptionChannelBinder {
  def bindAll(queue: String)
  def bindEach(queue: String, params: Seq[Seq[String]])
  def bindTogether(queue: String, params: Seq[Seq[Option[String]]])
}

class SubscriptionChannelManager[Proto](descriptor: SubscriptionDescriptor[Proto], protected val ops: AmqpServiceOperations, protected val exchange: String)
    extends SubscriptionChannelBinder
    with ChannelBinderImpl
    with ModelEventTransformer[Proto]
    with LazyLogging {

  import NotificationConversions._
  import SubscriptionChannelManager._

  ops.declareExchange(exchange)

  def toMessage(typ: ModelEvent, event: Proto): AmqpAddressedMessage = {
    val key = makeKey(descriptor.keyParts(event))
    val notification = descriptor.notification(protoType(typ), event)
    AmqpAddressedMessage(exchange, key, AmqpMessage(notification, None, None))
  }
}

class SubscriptionChannelBinderOnly(protected val ops: AmqpServiceOperations, protected val exchange: String) extends SubscriptionChannelBinder with ChannelBinderImpl {

  ops.declareExchange(exchange)
}

trait ChannelBinderImpl extends LazyLogging {
  protected val ops: AmqpServiceOperations
  protected val exchange: String

  import SubscriptionChannelManager._

  def bindAll(queue: String) {
    logger.debug(s"Binding queue $queue to exchange $exchange with key #")
    ops.bindQueue(queue, exchange, "#")
  }

  def bindEach(queue: String, params: Seq[Seq[String]]): Unit = {
    val keys = routingKeyPermutations(params)

    if (keys.isEmpty) {
      bindAll(queue)
    } else {
      logger.debug(s"Binding queue $queue to exchange $exchange with keys: " + keys)
      keys.foreach(key => ops.bindQueue(queue, exchange, key))
    }
  }

  def bindTogether(queue: String, params: Seq[Seq[Option[String]]]): Unit = {
    val keys = params.map(toRoutingKey)

    if (keys.isEmpty) {
      bindAll(queue)
    } else {
      logger.debug(s"Binding queue $queue to exchange $exchange with keys: " + keys)
      keys.foreach(key => ops.bindQueue(queue, exchange, key))
    }

  }
}

trait ModelEventTransformer[A] {
  def toMessage(typ: ModelEvent, event: A): AmqpAddressedMessage
}

sealed trait ModelEvent
case object Created extends ModelEvent
case object Updated extends ModelEvent
case object Deleted extends ModelEvent

trait ModelNotifier {
  def notify[A](typ: ModelEvent, event: A)
}

object NullModelNotifier extends ModelNotifier {
  def notify[A](typ: ModelEvent, event: A) {}
}

class BufferingModelNotifier(mapper: ModelEventMapper, ops: AmqpServiceOperations, observer: (Int, Int) => Unit) extends ModelNotifier {
  private val buffer = scala.collection.mutable.ArrayBuffer.empty[AmqpAddressedMessage]

  def notify[A](typ: ModelEvent, event: A) {
    mapper.toMessage(typ, event).foreach(buffer +=)
  }

  def flush() {
    val size = buffer.size
    val flushStart = System.currentTimeMillis()

    ops.publishBatch(buffer.toSeq)

    val flushTime = (System.currentTimeMillis() - flushStart).toInt
    observer(size, flushTime)
  }

}

class SimpleModelNotifier(mapper: ModelEventMapper, ops: AmqpServiceOperations, observer: (Int, Int) => Unit) extends ModelNotifier {
  def notify[A](typ: ModelEvent, event: A) {
    mapper.toMessage(typ, event).foreach { msg =>
      ops.publishEvent(msg.exchange, msg.message.msg, msg.key)
    }
  }
}

class ModelEventMapper extends LazyLogging {

  private val map = mutable.Map.empty[Class[_], ModelEventTransformer[_]]
  private val missed = mutable.Set.empty[Class[_]]

  def register[A](klass: Class[A], publisher: ModelEventTransformer[A]) {
    map.update(klass, publisher)
  }

  def toMessage[A](typ: ModelEvent, event: A): Option[AmqpAddressedMessage] = {
    val klass = event.getClass
    val transformerOpt = map.get(klass)
    if (transformerOpt.isEmpty && !missed.contains(klass)) {
      logger.warn("No event publisher for class: " + klass)
      missed += klass
    }
    transformerOpt.map(trans => trans.asInstanceOf[ModelEventTransformer[A]].toMessage(typ, event))
  }
}
