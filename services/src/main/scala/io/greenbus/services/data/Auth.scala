/**
 * Copyright 2011-2016 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the GNU Affero General Public License
 * Version 3.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.gnu.org/licenses/agpl.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.greenbus.services.data

import java.util.UUID
import org.squeryl.KeyedEntity

/**
 * Helpers for handling the implementation of salted password and encoded passwords
 * http://www.jasypt.org/howtoencryptuserpasswords.html
 */
object SaltedPasswordHelper {
  import org.apache.commons.codec.binary.Base64
  import java.security.{ SecureRandom, MessageDigest }

  def enc64(original: String): String = {
    new String(Base64.encodeBase64(original.getBytes("UTF-8")), "US-ASCII")
  }

  def dec64(original: String): String = {
    new String(Base64.decodeBase64(original.getBytes("US-ASCII")), "UTF-8")
  }

  /**
   * @returns tuple of (digest, salt)
   */
  def makeDigestAndSalt(password: String): (String, String) = {
    val b = new Array[Byte](20)
    new SecureRandom().nextBytes(b)
    val salt = dec64(enc64(new String(b, "UTF-8")))
    (calcDigest(salt, password), salt)
  }

  def calcDigest(salt: String, pass: String) = {
    val combinedSaltPassword = (salt + pass).getBytes("UTF-8")
    val digestBytes = MessageDigest.getInstance("SHA-256").digest(combinedSaltPassword)
    // TODO: figure out how to roundtrip bytes through UTF-8
    dec64(enc64(new String(digestBytes, "UTF-8")))
  }
}

object AgentRow {
  import SaltedPasswordHelper._

  def apply(id: UUID, name: String, password: String): AgentRow = {
    val (digest, salt) = SaltedPasswordHelper.makeDigestAndSalt(password)
    AgentRow(id, name, enc64(digest), enc64(salt))
  }
}
case class AgentRow(id: UUID,
    name: String,
    digest: String,
    salt: String) extends KeyedEntity[UUID] {

  def checkPassword(password: String): Boolean = {
    import SaltedPasswordHelper._
    dec64(digest) == calcDigest(dec64(salt), password)
  }

  def withUpdatedPassword(password: String): AgentRow = {
    import SaltedPasswordHelper._
    val (digest, saltText) = makeDigestAndSalt(password)
    this.copy(digest = enc64(digest), salt = enc64(saltText))
  }

  override def toString: String = {
    s"AgentRow($id, $name, [digest], [salt])"
  }
}

case class PermissionSetRow(id: Long,
    name: String,
    protoBytes: Array[Byte]) extends KeyedEntity[Long] {
}

case class AuthTokenRow(id: Long,
    token: String,
    agentId: UUID,
    loginLocation: String,
    clientVersion: String,
    revoked: Boolean,
    issueTime: Long,
    expirationTime: Long) extends KeyedEntity[Long] {
}

case class AgentPermissionSetJoinRow(permissionSetId: Long, agentId: UUID)
case class AuthTokenPermissionSetJoinRow(permissionSetId: Long, authTokenId: Long)