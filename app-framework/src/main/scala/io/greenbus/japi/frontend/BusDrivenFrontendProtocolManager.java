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

import io.greenbus.app.actor.AmqpConnectionConfig;
import io.greenbus.app.actor.EndpointCollectionStrategy;
import io.greenbus.app.actor.frontend.FrontendRegistrationConfig;
import io.greenbus.japi.frontend.impl.BusDrivenFrontendProtocolManagerImpl;

// Wrapped to keep as many Java-accessible classes in JavaDoc as possible

/**
 * Hosts a set of front-end connections. Is responsible for establishing the connection
 * to the services, requesting and subscribing to the relevant parts of the system model,
 * and notifying protocol implementations of Endpoints they should provide service for.
 */
public class BusDrivenFrontendProtocolManager {

    private final BusDrivenFrontendProtocolManagerImpl impl;

    /**
     * @param processName Name of the process for logging purposes.
     * @param amqpConfig Configuration for AMQP, with failover.
     * @param userConfigPath Path to find the user configuration.
     * @param registrationConfig Configuration parameters for front-end registration process.
     * @param nodeId Node ID used to re-register front-end immediately on restart. Set to random UUID as a default.
     * @param endpointStrategy Strategy for the subset of Endpoints the protocol implementation should provide services for.
     * @param factory Factory for bus-driven protocols.
     */
    public BusDrivenFrontendProtocolManager(
            String processName,
            AmqpConnectionConfig amqpConfig,
            String userConfigPath,
            FrontendRegistrationConfig registrationConfig,
            String nodeId,
            EndpointCollectionStrategy endpointStrategy,
            BusDrivenProtocolFactory factory) {

        this.impl = new BusDrivenFrontendProtocolManagerImpl(processName, amqpConfig, userConfigPath, registrationConfig, nodeId, endpointStrategy, factory);
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
