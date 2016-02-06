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

import io.greenbus.msg.{ Subscription, Session }
import io.greenbus.client.service.proto.FrontEnd.{ FrontEndConnectionStatusNotification, FrontEndConnectionStatus }
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.client.service.{ FrontEndService, MeasurementService, ModelService }
import io.greenbus.client.service.proto.ModelRequests.EntityRelationshipFlatQuery
import scala.concurrent.Await
import scala.concurrent.duration._
import io.greenbus.client.service.proto.Measurements.{ MeasurementNotification, PointMeasurementValue }
import io.greenbus.client.service.proto.ModelRequests.EndpointSubscriptionQuery

object ServiceWatcher {
  def getOwnedPoints(session: Session, owner: String): Seq[ModelUUID] = {
    val modelClient = ModelService.client(session)

    val relationQuery =
      EntityRelationshipFlatQuery.newBuilder()
        .addStartNames(owner)
        .setDescendantOf(true)
        .setRelationship("owns")
        .addEndTypes("Point")
        .build()

    val pointEntities = Await.result(modelClient.relationshipFlatQuery(relationQuery), 5000.milliseconds)

    pointEntities.map(_.getUuid)
  }

  def measurementWatcherForOwner(session: Session, owner: String): (Seq[PointMeasurementValue], ServiceWatcher[MeasurementNotification]) = {
    val pointUuids = getOwnedPoints(session, owner)
    val measClient = MeasurementService.client(session)

    val subFut = measClient.getCurrentValuesAndSubscribe(pointUuids)

    val (originals, sub) = Await.result(subFut, 5000.milliseconds)

    (originals, new ServiceWatcher(sub))
  }

  def statusWatcher(session: Session, endpointName: String): (Seq[FrontEndConnectionStatus], ServiceWatcher[FrontEndConnectionStatusNotification]) = {
    val client = FrontEndService.client(session)

    val (current, sub) = Await.result(client.subscribeToFrontEndConnectionStatuses(EndpointSubscriptionQuery.newBuilder().addNames(endpointName).build()), 5000.milliseconds)

    (current, new ServiceWatcher(sub))
  }

}

class ServiceWatcher[A](subscription: Subscription[A]) {

  val watcher = new EventWatcher[A]

  subscription.start { subNot => watcher.enqueue(subNot) }

  def cancel() {
    subscription.cancel()
  }

}

