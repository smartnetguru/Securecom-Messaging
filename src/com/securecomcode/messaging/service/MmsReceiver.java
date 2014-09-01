/**
 * Copyright (C) 2011 Whisper Systems
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
package com.securecomcode.messaging.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.util.Pair;

import org.whispersystems.textsecure.crypto.MasterSecret;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.MmsDatabase;
import com.securecomcode.messaging.mms.IncomingMediaMessage;
import com.securecomcode.messaging.util.TextSecurePreferences;
import org.whispersystems.textsecure.push.IncomingPushMessage;
import org.whispersystems.textsecure.push.PushServiceSocket;

import java.io.File;
import java.io.IOException;
import java.util.List;

import ws.com.google.android.mms.MmsException;
import ws.com.google.android.mms.pdu.GenericPdu;
import ws.com.google.android.mms.pdu.NotificationInd;
import ws.com.google.android.mms.pdu.PduHeaders;
import ws.com.google.android.mms.pdu.PduParser;

public class MmsReceiver {

  private final Context context;

  public MmsReceiver(Context context) {
    this.context = context;
  }

  public void process(MasterSecret masterSecret, Intent intent) {
    if (intent.getAction().equals(SendReceiveService.RECEIVE_MMS_ACTION)) {
      handleMmsNotification(intent);
    }
  }

  private void handleMmsNotification(Intent intent) {
    byte[] mmsData   = intent.getByteArrayExtra("data");
    PduParser parser = new PduParser(mmsData);
    GenericPdu pdu   = parser.parse();

    if (pdu.getMessageType() == PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) {
      MmsDatabase database                = DatabaseFactory.getMmsDatabase(context);
      Pair<Long, Long> messageAndThreadId = database.insertMessageInbox((NotificationInd)pdu);

      Log.w("MmsReceiver", "Inserted received MMS notification...");
      scheduleDownload((NotificationInd)pdu, messageAndThreadId.first, messageAndThreadId.second);
    }
  }

  private void scheduleDownload(NotificationInd pdu, long messageId, long threadId) {
    Intent intent = new Intent(SendReceiveService.DOWNLOAD_MMS_ACTION, null, context, SendReceiveService.class);
    intent.putExtra("content_location", new String(pdu.getContentLocation()));
    intent.putExtra("message_id", messageId);
    intent.putExtra("transaction_id", pdu.getTransactionId());
    intent.putExtra("thread_id", threadId);
    intent.putExtra("automatic", true);

    context.startService(intent);
  }

}
