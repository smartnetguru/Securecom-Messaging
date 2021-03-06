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

package com.securecomcode.messaging.crypto;

import android.content.Context;
import android.util.Log;

import com.google.thoughtcrimegson.Gson;

import com.securecomcode.messaging.crypto.storage.TextSecurePreKeyStore;
import com.securecomcode.messaging.util.Util;
import org.whispersystems.libaxolotl.IdentityKeyPair;
import org.whispersystems.libaxolotl.InvalidKeyException;
import org.whispersystems.libaxolotl.InvalidKeyIdException;
import org.whispersystems.libaxolotl.ecc.Curve;
import org.whispersystems.libaxolotl.ecc.Curve25519;
import org.whispersystems.libaxolotl.ecc.ECKeyPair;
import org.whispersystems.libaxolotl.state.SignedPreKeyRecord;
import org.whispersystems.libaxolotl.state.SignedPreKeyStore;
import org.whispersystems.libaxolotl.state.PreKeyRecord;
import org.whispersystems.libaxolotl.state.PreKeyStore;
import org.whispersystems.libaxolotl.util.Medium;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;

public class PreKeyUtil {

  public static final int BATCH_SIZE = 100;

  public static List<PreKeyRecord> generatePreKeys(Context context, MasterSecret masterSecret) {
    PreKeyStore        preKeyStore    = new TextSecurePreKeyStore(context, masterSecret);
    List<PreKeyRecord> records        = new LinkedList<>();
    int                preKeyIdOffset = getNextPreKeyId(context);

    for (int i=0;i<BATCH_SIZE;i++) {
      int          preKeyId = (preKeyIdOffset + i) % Medium.MAX_VALUE;
      ECKeyPair    keyPair  = Curve25519.generateKeyPair();
      PreKeyRecord record   = new PreKeyRecord(preKeyId, keyPair);

      preKeyStore.storePreKey(preKeyId, record);
      records.add(record);
    }

    setNextPreKeyId(context, (preKeyIdOffset + BATCH_SIZE + 1) % Medium.MAX_VALUE);
    return records;
  }

  public static SignedPreKeyRecord generateSignedPreKey(Context context, MasterSecret masterSecret,
                                                        IdentityKeyPair identityKeyPair)
  {
    try {
      SignedPreKeyStore  signedPreKeyStore = new TextSecurePreKeyStore(context, masterSecret);
      int                signedPreKeyId    = getNextSignedPreKeyId(context);
      ECKeyPair          keyPair           = Curve25519.generateKeyPair();
      byte[]             signature         = Curve.calculateSignature(identityKeyPair.getPrivateKey(), keyPair.getPublicKey().serialize());
      SignedPreKeyRecord record            = new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), keyPair, signature);

      signedPreKeyStore.storeSignedPreKey(signedPreKeyId, record);
      setNextSignedPreKeyId(context, (signedPreKeyId + 1) % Medium.MAX_VALUE);

      return record;
    } catch (InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }

  public static PreKeyRecord generateLastResortKey(Context context, MasterSecret masterSecret) {
    PreKeyStore preKeyStore = new TextSecurePreKeyStore(context, masterSecret);

    if (preKeyStore.containsPreKey(Medium.MAX_VALUE)) {
      try {
        return preKeyStore.loadPreKey(Medium.MAX_VALUE);
      } catch (InvalidKeyIdException e) {
        Log.w("PreKeyUtil", e);
        preKeyStore.removePreKey(Medium.MAX_VALUE);
      }
    }

    ECKeyPair    keyPair = Curve25519.generateKeyPair();
    PreKeyRecord record  = new PreKeyRecord(Medium.MAX_VALUE, keyPair);

    preKeyStore.storePreKey(Medium.MAX_VALUE, record);

    return record;
  }

  private static void setNextPreKeyId(Context context, int id) {
    try {
      File             nextFile = new File(getPreKeysDirectory(context), PreKeyIndex.FILE_NAME);
      FileOutputStream fout     = new FileOutputStream(nextFile);
      fout.write(new Gson().toJson(new PreKeyIndex(id)).getBytes());
      fout.close();
    } catch (IOException e) {
      Log.w("PreKeyUtil", e);
    }
  }

  private static void setNextSignedPreKeyId(Context context, int id) {
    try {
      File             nextFile = new File(getSignedPreKeysDirectory(context), SignedPreKeyIndex.FILE_NAME);
      FileOutputStream fout     = new FileOutputStream(nextFile);
      fout.write(new Gson().toJson(new SignedPreKeyIndex(id)).getBytes());
      fout.close();
    } catch (IOException e) {
      Log.w("PreKeyUtil", e);
    }
  }

  private static int getNextPreKeyId(Context context) {
    try {
      File nextFile = new File(getPreKeysDirectory(context), PreKeyIndex.FILE_NAME);

      if (!nextFile.exists()) {
        return Util.getSecureRandom().nextInt(Medium.MAX_VALUE);
      } else {
        InputStreamReader reader = new InputStreamReader(new FileInputStream(nextFile));
        PreKeyIndex       index  = new Gson().fromJson(reader, PreKeyIndex.class);
        reader.close();
        return index.nextPreKeyId;
      }
    } catch (IOException e) {
      Log.w("PreKeyUtil", e);
      return Util.getSecureRandom().nextInt(Medium.MAX_VALUE);
    }
  }

  private static int getNextSignedPreKeyId(Context context) {
    try {
      File nextFile = new File(getSignedPreKeysDirectory(context), SignedPreKeyIndex.FILE_NAME);

      if (!nextFile.exists()) {
        return Util.getSecureRandom().nextInt(Medium.MAX_VALUE);
      } else {
        InputStreamReader reader = new InputStreamReader(new FileInputStream(nextFile));
        SignedPreKeyIndex index  = new Gson().fromJson(reader, SignedPreKeyIndex.class);
        reader.close();
        return index.nextSignedPreKeyId;
      }
    } catch (IOException e) {
      Log.w("PreKeyUtil", e);
      return Util.getSecureRandom().nextInt(Medium.MAX_VALUE);
    }
  }

  private static File getPreKeysDirectory(Context context) {
    return getKeysDirectory(context, TextSecurePreKeyStore.PREKEY_DIRECTORY);
  }

  private static File getSignedPreKeysDirectory(Context context) {
    return getKeysDirectory(context, TextSecurePreKeyStore.SIGNED_PREKEY_DIRECTORY);
  }

  private static File getKeysDirectory(Context context, String name) {
    File directory = new File(context.getFilesDir(), name);

    if (!directory.exists())
      directory.mkdirs();

    return directory;
  }

  private static class PreKeyIndex {
    public static final String FILE_NAME = "index.dat";

    private int nextPreKeyId;

    public PreKeyIndex() {}

    public PreKeyIndex(int nextPreKeyId) {
      this.nextPreKeyId = nextPreKeyId;
    }
  }

  private static class SignedPreKeyIndex {
    public static final String FILE_NAME = "index.dat";

    private int nextSignedPreKeyId;

    public SignedPreKeyIndex() {}

    public SignedPreKeyIndex(int nextSignedPreKeyId) {
      this.nextSignedPreKeyId = nextSignedPreKeyId;
    }
  }


}
