/**
 * Copyright (C) 2013 Open Whisper Systems
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
package org.whispersystems.libaxolotl.ecc;

import org.whispersystems.libaxolotl.InvalidKeyException;

public class Curve {

  public  static final int DJB_TYPE   = 0x05;

  public static ECKeyPair generateKeyPair() {
    return Curve25519.generateKeyPair();
  }

  public static ECPublicKey decodePoint(byte[] bytes, int offset)
      throws InvalidKeyException
  {
    int type = bytes[offset];

    if (type == DJB_TYPE) {
      return Curve25519.decodePoint(bytes, offset);
    } else {
      throw new InvalidKeyException("Unknown key type: " + type);
    }
  }

  public static ECPrivateKey decodePrivatePoint(byte[] bytes) {
    return new DjbECPrivateKey(bytes);
  }

  public static byte[] calculateAgreement(ECPublicKey publicKey, ECPrivateKey privateKey)
      throws InvalidKeyException
  {
    if (publicKey.getType() != privateKey.getType()) {
      throw new InvalidKeyException("Public and private keys must be of the same type!");
    }

    if (publicKey.getType() == DJB_TYPE) {
      return Curve25519.calculateAgreement(publicKey, privateKey);
    } else {
      throw new InvalidKeyException("Unknown type: " + publicKey.getType());
    }
  }

  public static boolean verifySignature(ECPublicKey signingKey, byte[] message, byte[] signature)
      throws InvalidKeyException
  {
    if (signingKey.getType() == DJB_TYPE) {
      return Curve25519.verifySignature(signingKey, message, signature);
    } else {
      throw new InvalidKeyException("Unknown type: " + signingKey.getType());
    }
  }

  public static byte[] calculateSignature(ECPrivateKey signingKey, byte[] message)
      throws InvalidKeyException
  {
    if (signingKey.getType() == DJB_TYPE) {
      return Curve25519.calculateSignature(signingKey, message);
    } else {
      throw new InvalidKeyException("Unknown type: " + signingKey.getType());
    }
  }
}
