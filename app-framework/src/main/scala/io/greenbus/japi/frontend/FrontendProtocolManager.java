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

import io.greenbus.app.actor.EndpointCollectionStrategy;
import io.greenbus.japi.frontend.impl.FrontendProtocolManagerImpl;

import java.util.List;

// Wrapped to keep as many Java-accessible classes in JavaDoc as possible

/**
 * Hosts a set of front-end connections. Is responsible for establishing the connection
 * to the services, requesting and subscribing to the relevant parts of the system model,
 * and notifying protocol implementations of Endpoints they should provide service for.
 *
 * @param <ProtocolConfig>
 */
public class FrontendProtocolManager<ProtocolConfig> {

    private final FrontendProtocolManagerImpl<ProtocolConfig> impl;

    /**
     *
     * @param protocol The protocol implementation.
     * @param protocolConfigurer Used by the library to determine whether a valid configuration exists.
     * @param endpointStrategy Strategy for the subset of Endpoints the protocol implementation should provide services for.
     * @param amqpConfigPath Path to find the AMQP connection configuration.
     * @param userConfigPath Path to find the user configuration.
     */
    public FrontendProtocolManager(
            MasterProtocol<ProtocolConfig> protocol,
            ProtocolConfigurer<ProtocolConfig> protocolConfigurer,
            List<String> configKeys,
            EndpointCollectionStrategy endpointStrategy,
            String amqpConfigPath,
            String userConfigPath) {

        this.impl = new FrontendProtocolManagerImpl<ProtocolConfig>(protocol, protocolConfigurer, configKeys, endpointStrategy, amqpConfigPath, userConfigPath);
    }

    /**
     *
     */
    public void start() {
        impl.start();
    }

    /**
     * Notifies all protocol implementations they should stop, and closes the connection to the services.
     */
    public void shutdown() {
        impl.shutdown();
    }
}
