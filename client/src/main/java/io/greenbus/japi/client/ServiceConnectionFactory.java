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

import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.greenbus.msg.amqp.AmqpBroker;
import io.greenbus.msg.amqp.japi.AmqpConnection;
import io.greenbus.msg.amqp.japi.AmqpConnectionFactory;
import io.greenbus.msg.amqp.japi.AmqpSettings;
import io.greenbus.msg.japi.ConnectionCloseListener;
import io.greenbus.msg.japi.Session;
import io.greenbus.msg.japi.amqp.AmqpServiceOperations;
import io.greenbus.client.ServiceHeaders;
import io.greenbus.client.ServiceMessagingCodec;
import io.greenbus.client.exception.InternalServiceException;
import io.greenbus.client.service.proto.LoginRequests;
import io.greenbus.client.version.Version;
import io.greenbus.japi.client.service.LoginService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class ServiceConnectionFactory
{

    public static ServiceConnection create( AmqpSettings amqpSettings, AmqpBroker broker, long timeoutMs )
    {

        final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool( 5 );

        final AmqpConnectionFactory amqpConnectionFactory = new AmqpConnectionFactory( amqpSettings, broker, timeoutMs, scheduledExecutorService );

        final AmqpConnection amqpConnection = amqpConnectionFactory.connect();

        return new DefaultConnection( amqpConnection, scheduledExecutorService );
    }


    private static class DefaultConnection implements ServiceConnection
    {

        private final AmqpConnection connection;
        private final ScheduledExecutorService executorService;

        public DefaultConnection( AmqpConnection connection, ScheduledExecutorService executorService )
        {
            this.connection = connection;
            this.executorService = executorService;
        }

        public void addConnectionCloseListener( ConnectionCloseListener listener )
        {
            connection.addConnectionCloseListener( listener );
        }

        public void removeConnectionCloseListener( ConnectionCloseListener listener )
        {
            connection.removeConnectionCloseListener( listener );
        }

        public void disconnect()
        {
            connection.disconnect();
            executorService.shutdown();
        }

        @Override
        public ListenableFuture<Session> login( String user, String password )
        {

            final Session session = createSession();

            final LoginService.Client client = LoginService.client( session );

            final LoginRequests.LoginRequest request =
                LoginRequests.LoginRequest.newBuilder().setName( user ).setPassword( password ).setLoginLocation( "" ).setClientVersion(
                        Version.clientVersion() ).build();

            final ListenableFuture<LoginRequests.LoginResponse> loginFuture = client.login( request );

            return Futures.transform( loginFuture, new AsyncFunction<LoginRequests.LoginResponse, Session>() {
                @Override
                public ListenableFuture<Session> apply( LoginRequests.LoginResponse input ) throws Exception
                {
                    try
                    {
                        session.addHeader( ServiceHeaders.tokenHeader(), input.getToken() );
                        return Futures.immediateFuture( session );
                    }
                    catch ( Throwable ex )
                    {
                        return Futures.immediateFailedFuture( new InternalServiceException( "Problem handling login response: " + ex.getMessage() ) );
                    }
                }
            } );
        }

        public Session createSession()
        {
            return connection.createSession( ServiceMessagingCodec.codec() );
        }

        public AmqpServiceOperations getServiceOperations()
        {
            return connection.getServiceOperations();
        }
    }


}
