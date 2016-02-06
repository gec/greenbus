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
import io.greenbus.msg.japi.Session;

/**
 * Factory handed to protocol registration library to instantiate protocol implementations upon registration.
 */
public interface BusDrivenProtocolFactory {

    /**
     * Used to instantiate protocol implementation.
     *
     * @param endpoint Endpoint the protocol implementation is serving.
     * @param session Logged-in session from the protocol registration library.
     * @param protocolUpdater Interface for publishg measurements and updating frontend communication status.
     * @return Handle for protocol management.
     */
    BusDrivenProtocol initialize(Model.Endpoint endpoint, Session session, ProtocolUpdater protocolUpdater);
}
