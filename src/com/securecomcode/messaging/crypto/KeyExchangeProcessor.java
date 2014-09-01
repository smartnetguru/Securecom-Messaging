/**
 * Copyright (C) 2011 Whisper Systems
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
import android.content.Intent;

import com.securecomcode.messaging.crypto.protocol.KeyExchangeMessage;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.RecipientFactory;
import com.securecomcode.messaging.service.KeyCachingService;
import org.whispersystems.textsecure.crypto.InvalidMessageException;
import org.whispersystems.textsecure.crypto.MasterSecret;
import org.whispersystems.textsecure.storage.RecipientDevice;

public abstract class KeyExchangeProcessor {

  public static final String SECURITY_UPDATE_EVENT = "com.securecomcode.messaging.KEY_EXCHANGE_UPDATE";

  public abstract boolean isStale(KeyExchangeMessage message);
  public abstract boolean isTrusted(KeyExchangeMessage message);
  public abstract void processKeyExchangeMessage(KeyExchangeMessage message, long threadid)
      throws InvalidMessageException;

  public static KeyExchangeProcessor createFor(Context context, MasterSecret masterSecret,
                                               RecipientDevice recipientDevice,
                                               KeyExchangeMessage message)
  {
    return new KeyExchangeProcessorV2(context, masterSecret, recipientDevice);
  }

  public static void broadcastSecurityUpdateEvent(Context context, long threadId) {
    Intent intent = new Intent(SECURITY_UPDATE_EVENT);
    intent.putExtra("thread_id", threadId);
    intent.setPackage(context.getPackageName());
    context.sendBroadcast(intent, KeyCachingService.KEY_PERMISSION);
  }

}