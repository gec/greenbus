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

import io.greenbus.client.service.proto.Model;

/**
 *
 * Interface for a protocol manager.
 *
 * The underlying library uses add() and remove() callbacks to notify the user that service for
 * Endpoints is required.
 *
 * @param <ProtocolConfig> Type of the assembled configuration for the protocol.
 */
public interface MasterProtocol<ProtocolConfig> {

    /**
     * Called by the library to notify user code that service is requested for a particular Endpoint.
     *
     * @param endpoint Endpoint the front-end connection is for.
     * @param protocolConfig The assembled configuration for the protocol.
     * @param updater An interface for the front-end to notify the library of measurement and status updates.
     * @return An interface for the underlying library to notify the front-end of command requests.
     */
    ProtocolCommandAcceptor add(Model.Endpoint endpoint, ProtocolConfig protocolConfig, ProtocolUpdater updater);

    /**
     * Called by the library to notify user code that service should no longer be provided for a particular Endpoint.
     *
     * @param endpointUuid UUID of the Endpoint service should no longer be provided for.
     */
    void remove(Model.ModelUUID endpointUuid);

    /**
     * Called when the entire system is shutting down. All front-end connections should be closed and cleaned up.
     */
    void shutdown();
}
