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

import com.google.common.util.concurrent.SettableFuture;
import io.greenbus.client.service.proto.Commands;

/**
 * Represents the protocol framework library's interface to a bus-driven protocol implementation.
 */
public interface BusDrivenProtocol {

    /**
     * Callback for the protocol implementation to handle command requests.
     *
     * @param commandRequest Command request to be handled.
     * @param promise Promise of a CommandResult, used to asynchronously notify the library that the request has been handled.
     */
    void handleCommandRequest(Commands.CommandRequest commandRequest, SettableFuture<Commands.CommandResult> promise);

    /**
     * Callback for the protocol implementation to clean up when connection to the bus has been lost.
     */
    void onClose();
}
