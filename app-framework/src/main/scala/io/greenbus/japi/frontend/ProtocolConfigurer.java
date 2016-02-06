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

import java.util.List;

/**
 * Used by the library to manage the configuration for an Endpoint's front-end connection.
 *
 * @param <ProtocolConfig> Type of the assembled configuration for the protocol.
 */
public interface ProtocolConfigurer<ProtocolConfig> {

    /**
     * Provided by a protocol implementation to transform the list of ConfigFiles to the
     * protocol-specific form of configuration.
     *
     * Should return null if the configuration is missing or can't be parsed.
     *
     * @param endpoint Endpoint the configuration is for.
     * @param configFiles The ConfigFiles associated with the Endpoint.
     * @return The extracted configuration, of null if not found.
     */
    ProtocolConfig evaluate(Model.Endpoint endpoint, List<Model.EntityKeyValue> configFiles);

    /**
     * Provided by a protocol implementation to inform the library if two instances of the
     * protocol configuration are equivalent or represent an update that requires re-initialization.
     *
     * @param latest The latest instance received by the library.
     * @param previous The previous instance delivered to the protocol implementation.
     * @return true if the front-end does not need to be re-initialized, false if it does.
     */
    boolean equivalent(ProtocolConfig latest, ProtocolConfig previous);
}
