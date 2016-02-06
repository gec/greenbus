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

import io.greenbus.app.actor.frontend.FepConfigLoader;
import io.greenbus.app.actor.frontend.FrontendProcessConfig;
import scala.collection.JavaConversions;

import java.util.Set;

/**
 * Helper class for loading frontend process configuration from JSON, with fallbacks and default settings.
 */
public class FrontendConfiguration {

    /**
     *
     * @param jsonConfigPath Path of frontend JSON configuration format.
     * @param defaultAmqpConfigPath Path of an AMQP configuration file if defaults are to be used.
     * @param defaultUserConfigPath Path of a user configuration file if defaults are to be used.
     * @param protocols Set of protocols the protocol implementation provides for.
     * @return Configuration structure for protocol frontends.
     */
    public static FrontendProcessConfig load(String jsonConfigPath, String defaultAmqpConfigPath, String defaultUserConfigPath, Set<String> protocols) {
        return FepConfigLoader.loadConfig(jsonConfigPath, defaultAmqpConfigPath, defaultUserConfigPath, JavaConversions.asScalaSet(protocols).<String>toSet());
    }
}
