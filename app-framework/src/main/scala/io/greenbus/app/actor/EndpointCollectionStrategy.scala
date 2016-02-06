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
package io.greenbus.app.actor

import io.greenbus.client.service.ModelService
import io.greenbus.client.service.proto.Model.{ Endpoint, EndpointNotification }
import io.greenbus.client.service.proto.ModelRequests.{ EntityKeySet, EndpointQuery, EndpointSubscriptionQuery, EntityPagingParams }
import io.greenbus.msg.{ Session, Subscription }

import scala.collection.JavaConversions._
import scala.concurrent.{ ExecutionContext, Future }

sealed trait CollectionMembership
case object AnyAndAllMembership extends CollectionMembership
case object NamedMembership extends CollectionMembership
case class ProtocolMembership(validProtocols: Set[String]) extends CollectionMembership

trait EndpointCollectionStrategy {
  def membership: CollectionMembership

  def nameWhitelist: Option[Set[String]]

  def configuration(session: Session)(implicit context: ExecutionContext): Future[(Seq[Endpoint], Subscription[EndpointNotification])]
}

class AllEndpointsStrategy(whitelistOpt: Option[Set[String]] = None) extends EndpointCollectionStrategy {

  def membership = AnyAndAllMembership

  def nameWhitelist: Option[Set[String]] = whitelistOpt

  def configuration(session: Session)(implicit context: ExecutionContext): Future[(Seq[Endpoint], Subscription[EndpointNotification])] = {
    val modelClient = ModelService.client(session)

    val subFut = modelClient.subscribeToEndpoints(EndpointSubscriptionQuery.newBuilder().build())

    subFut.flatMap {
      case (emptyCurrent, sub) =>
        val currFut = whitelistOpt match {
          case None =>
            val query = EndpointQuery.newBuilder()
              .setPagingParams(EntityPagingParams.newBuilder()
                .setPageSize(Int.MaxValue)) // TODO: page it if we can't trust the strategy choice to be sane?
              .build()

            modelClient.endpointQuery(query)

          case Some(whitelist) =>
            modelClient.getEndpoints(EntityKeySet.newBuilder().addAllNames(whitelist).build())
        }

        currFut.onFailure { case ex => sub.cancel() }

        currFut.map { ends =>
          (ends, sub)
        }
    }
  }
}

class ProtocolsEndpointStrategy(validProtocols: Set[String], whitelistOpt: Option[Set[String]] = None) extends EndpointCollectionStrategy {

  def membership = ProtocolMembership(validProtocols)

  def nameWhitelist: Option[Set[String]] = None

  def configuration(session: Session)(implicit context: ExecutionContext): Future[(Seq[Endpoint], Subscription[EndpointNotification])] = {
    val modelClient = ModelService.client(session)

    val subQuery = EndpointSubscriptionQuery.newBuilder()
      .addAllProtocols(validProtocols)
      .build()

    val subFut = modelClient.subscribeToEndpoints(subQuery)

    subFut.flatMap {
      case (emptyCurrent, sub) =>

        val currFut = whitelistOpt match {
          case None =>
            val query = EndpointQuery.newBuilder()
              .addAllProtocols(validProtocols)
              .setPagingParams(EntityPagingParams.newBuilder()
                .setPageSize(Int.MaxValue)) // TODO: page it if we can't trust the strategy choice to be sane?
              .build()

            modelClient.endpointQuery(query)

          case Some(whitelist) =>
            modelClient.getEndpoints(EntityKeySet.newBuilder().addAllNames(whitelist).build()).map { results =>
              results.filter(r => validProtocols.contains(r.getProtocol))
            }
        }

        currFut.onFailure { case ex => sub.cancel() }

        currFut.map { ends =>
          (ends, sub)
        }
    }
  }
}
