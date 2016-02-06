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
package io.greenbus.client.exception

import io.greenbus.client.proto.Envelope

class RequestException(message: String, cause: Throwable) extends Exception(message, cause) {
  def this(message: String) {
    this(message, null)
  }
}

class MalformedResponseException(message: String) extends RequestException(message)

class ServiceException(message: String, status: Envelope.Status, cause: Throwable) extends RequestException(message, cause) {
  def this(message: String, status: Envelope.Status) = {
    this(message, status, null)
  }

  def getStatus: Envelope.Status = status
}

class ReplyException(message: String, status: Envelope.Status, cause: Throwable) extends ServiceException(message, status, cause) {
  def this(message: String, status: Envelope.Status) = {
    this(message, status, null)
  }
}

class BadRequestException(message: String) extends ReplyException(message, Envelope.Status.BAD_REQUEST)

class InternalServiceException(message: String) extends ServiceException(message, Envelope.Status.INTERNAL_ERROR)

class UnauthorizedException(message: String) extends ServiceException(message, Envelope.Status.UNAUTHORIZED)

class ForbiddenException(message: String) extends ServiceException(message, Envelope.Status.FORBIDDEN)

class LockedException(message: String) extends ServiceException(message, Envelope.Status.LOCKED)

class BusUnavailableException(message: String) extends ServiceException(message, Envelope.Status.BUS_UNAVAILABLE)

object StatusCodes {

  def isSuccess(status: Envelope.Status): Boolean = {
    status match {
      case Envelope.Status.OK => true
      case Envelope.Status.CREATED => true
      case Envelope.Status.UPDATED => true
      case Envelope.Status.DELETED => true
      case Envelope.Status.NOT_MODIFIED => true
      case _ => false
    }
  }

  def toException(status: Envelope.Status, error: String): ServiceException = status match {
    case Envelope.Status.BAD_REQUEST => new BadRequestException(error)
    case Envelope.Status.UNAUTHORIZED => new UnauthorizedException(error)
    case Envelope.Status.FORBIDDEN => new ForbiddenException(error)
    case Envelope.Status.INTERNAL_ERROR => new InternalServiceException(error)
    case Envelope.Status.LOCKED => new LockedException(error)
    case Envelope.Status.BUS_UNAVAILABLE => new BusUnavailableException(error)
    case _ => new ServiceException(error, status)
  }

}