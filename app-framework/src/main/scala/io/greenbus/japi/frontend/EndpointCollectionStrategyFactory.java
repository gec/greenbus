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
package io.greenbus.japi.frontend;

import io.greenbus.app.actor.AllEndpointsStrategy;
import io.greenbus.app.actor.EndpointCollectionStrategy;
import io.greenbus.app.actor.ProtocolsEndpointStrategy;
import scala.Option;
import scala.collection.JavaConversions;

import java.util.List;

/**
 * Factory for strategies that determine what subset of Endpoints the management library retrieves
 * configuration for.
 */
public class EndpointCollectionStrategyFactory {

    private EndpointCollectionStrategyFactory() {
    }

    /**
     * Strategy for all Endpoints in the system.
     *
     * @param endpointNames Names of Endpoints that are whitelisted. Null to allow all Endpoints.
     * @return Endpoint collection strategy
     */
    public static EndpointCollectionStrategy anyAndAll(List<String> endpointNames) {
        return new AllEndpointsStrategy(convertToOptionalSet(endpointNames));
    }

    /**
     * Strategy for only Endpoints with one of a set of specified protocols.
     *
     * @param protocolNames Names of Endpoint protocols
     * @param endpointNames Names of Endpoints that are whitelisted. Null to allow all Endpoints.
     * @return Endpoint collection strategy
     */
    public static EndpointCollectionStrategy protocolStrategy(List<String> protocolNames, List<String> endpointNames) {
        scala.collection.Iterable<String> stringIterable = JavaConversions.asScalaIterable(protocolNames);
        scala.collection.immutable.Set<String> set = stringIterable.toSet();
        return new ProtocolsEndpointStrategy(set, convertToOptionalSet(endpointNames));
    }


    private static <A> scala.Option<scala.collection.immutable.Set<A>> convertToOptionalSet(List<A> objs) {

        if (objs != null) {

            scala.collection.Iterable<A> stringIterable = JavaConversions.asScalaIterable(objs);
            scala.collection.immutable.Set<A> set = stringIterable.toSet();
            return Option.apply(set);

        } else {

            scala.collection.immutable.Set<A> nullvar = null;
            return Option.apply(nullvar);
        }
    }
}
