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

import io.greenbus.client.service.proto.FrontEnd;

import java.util.List;

/**
 * A handle used by the protocol implementation to notify the library of measurement updates and communication
 * status updates.
 */
public interface ProtocolUpdater {

    /**
     * Publishes measurement updates.
     *
     * @param wallTime System time that will be used in measurements that do not contain their own specific time.
     * @param updates List of Point name and measurement pairs that represent the updates.
     */
    void publish(long wallTime, List<NamedMeasurement> updates);

    /**
     * Publishes updates to the connection status.
     *
     * @param status The status the connection should be.
     */
    void updateStatus(FrontEnd.FrontEndConnectionStatus.Status status);
}
