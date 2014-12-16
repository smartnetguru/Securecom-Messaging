/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.textsecure.api.push;

import com.google.thoughtcrimegson.GsonBuilder;
import com.google.thoughtcrimegson.JsonDeserializationContext;
import com.google.thoughtcrimegson.JsonDeserializer;
import com.google.thoughtcrimegson.JsonElement;
import com.google.thoughtcrimegson.JsonParseException;
import com.google.thoughtcrimegson.JsonPrimitive;
import com.google.thoughtcrimegson.JsonSerializationContext;
import com.google.thoughtcrimegson.JsonSerializer;

import org.whispersystems.libaxolotl.ecc.ECPublicKey;
import org.whispersystems.textsecure.internal.util.Base64;
import org.whispersystems.textsecure.internal.push.PreKeyEntity;

import java.io.IOException;
import java.lang.reflect.Type;

public class SignedPreKeyEntity extends PreKeyEntity {

  private byte[] signature;

  public SignedPreKeyEntity() {}

  public SignedPreKeyEntity(int keyId, ECPublicKey publicKey, byte[] signature) {
    super(keyId, publicKey);
    this.signature = signature;
  }

  public byte[] getSignature() {
    return signature;
  }

  public static String toJson(SignedPreKeyEntity entity) {
    GsonBuilder builder = new GsonBuilder();
    return forBuilder(builder).create().toJson(entity);
  }

  public static SignedPreKeyEntity fromJson(String serialized) {
    GsonBuilder builder = new GsonBuilder();
    return forBuilder(builder).create().fromJson(serialized, SignedPreKeyEntity.class);
  }

  public static GsonBuilder forBuilder(GsonBuilder builder) {
    return PreKeyEntity.forBuilder(builder)
                       .registerTypeAdapter(byte[].class, new ByteArrayJsonAdapter());

  }

  private static class ByteArrayJsonAdapter
      implements JsonSerializer<byte[]>, JsonDeserializer<byte[]>
  {
    @Override
    public JsonElement serialize(byte[] signature, Type type,
                                 JsonSerializationContext jsonSerializationContext)
    {
      return new JsonPrimitive(Base64.encodeBytesWithoutPadding(signature));
    }

    @Override
    public byte[] deserialize(JsonElement jsonElement, Type type,
                              JsonDeserializationContext jsonDeserializationContext)
        throws JsonParseException
    {
      try {
        return Base64.decodeWithoutPadding(jsonElement.getAsJsonPrimitive().getAsString());
      } catch (IOException e) {
        throw new JsonParseException(e);
      }
    }
  }
}
