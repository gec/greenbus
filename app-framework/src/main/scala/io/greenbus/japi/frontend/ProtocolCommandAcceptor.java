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

import com.google.common.util.concurrent.ListenableFuture;
import io.greenbus.client.service.proto.Commands;

/**
 * Handle for the manager to notify protocol implementations of command requests.
 */
public interface ProtocolCommandAcceptor {

    /**
     *  Notifies the protocol implementation of command requests.
     *
     * @param commandName Name of the Command the request applies to.
     * @param request The CommandRequest object the protocol implementation should handle.
     * @return A future containing the result of handling the command request.
     */
    ListenableFuture<Commands.CommandResult> issue(String commandName, Commands.CommandRequest request);
}
