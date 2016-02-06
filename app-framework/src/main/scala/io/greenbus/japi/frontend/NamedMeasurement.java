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

import io.greenbus.client.service.proto.Measurements;

/**
 * Structure that holds a measurement value and the name of the Point it applies to.
 */
public class NamedMeasurement {
    final private String name;
    final private Measurements.Measurement value;

    public NamedMeasurement(String name, Measurements.Measurement value) {
        this.name = name;
        this.value = value;
    }

    /**
     * Gets the name of the Point associated with the Measurement.
     *
     * @return The name of the Point.
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the Measurement value.
     *
     * @return The Measurement value.
     */
    public Measurements.Measurement getValue() {
        return value;
    }
}
