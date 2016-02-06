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
package io.greenbus.japi.client;

import com.google.common.util.concurrent.ListenableFuture;
import io.greenbus.msg.japi.ConnectionCloseListener;
import io.greenbus.msg.japi.Session;
import io.greenbus.msg.japi.amqp.AmqpServiceOperations;

public interface ServiceConnection
{

    void addConnectionCloseListener( ConnectionCloseListener listener );

    void removeConnectionCloseListener( ConnectionCloseListener listener );

    void disconnect();

    ListenableFuture<Session> login( String user, String password );

    Session createSession();

    AmqpServiceOperations getServiceOperations();

}
