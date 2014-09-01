/**
 * Copyright (C) 2013 Open Whisper Systems
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
package com.securecomcode.messaging.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.securecomcode.messaging.R;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.MmsDatabase;
import com.securecomcode.messaging.database.ThreadDatabase;
import com.securecomcode.messaging.mms.MmsSendResult;
import com.securecomcode.messaging.notifications.MessageNotifier;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.service.SendReceiveService.ToastHandler;
import com.securecomcode.messaging.sms.IncomingIdentityUpdateMessage;
import com.securecomcode.messaging.transport.InsecureFallbackApprovalException;
import com.securecomcode.messaging.transport.RetryLaterException;
import com.securecomcode.messaging.transport.SecureFallbackApprovalException;
import com.securecomcode.messaging.transport.UndeliverableMessageException;
import com.securecomcode.messaging.transport.UniversalTransport;
import com.securecomcode.messaging.transport.UntrustedIdentityException;
import org.whispersystems.textsecure.crypto.MasterSecret;

import ws.com.google.android.mms.MmsException;
import ws.com.google.android.mms.pdu.SendReq;

public class MmsSender {

  private final Context             context;
  private final SystemStateListener systemStateListener;
  private final ToastHandler        toastHandler;

  public MmsSender(Context context, SystemStateListener systemStateListener, ToastHandler toastHandler) {
    this.context             = context;
    this.systemStateListener = systemStateListener;
    this.toastHandler        = toastHandler;
  }

  public void process(MasterSecret masterSecret, Intent intent) {
    Log.w("MmsSender", "Got intent action: " + intent.getAction());
    if (SendReceiveService.SEND_MMS_ACTION.equals(intent.getAction())) {
      handleSendMms(masterSecret, intent);
    }
  }

  private void handleSendMms(MasterSecret masterSecret, Intent intent) {
    long               messageId = intent.getLongExtra("message_id", -1);
    MmsDatabase        database  = DatabaseFactory.getMmsDatabase(context);
    ThreadDatabase     threads   = DatabaseFactory.getThreadDatabase(context);
    UniversalTransport transport = new UniversalTransport(context, masterSecret);

    try {
      SendReq[] messages = database.getOutgoingMessages(masterSecret, messageId);

      for (SendReq message : messages) {
        long threadId = database.getThreadIdForMessage(message.getDatabaseMessageId());

        try {
          Log.w("MmsSender", "Passing to MMS transport: " + message.getDatabaseMessageId());
          database.markAsSending(message.getDatabaseMessageId());
          MmsSendResult result = transport.deliver(message, threadId);

          if(result == null){
              return;
          }

          if (result.isUpgradedSecure()) database.markAsSecure(message.getDatabaseMessageId());
          if (result.isPush())           database.markAsPush(message.getDatabaseMessageId());

          database.markAsSent(message.getDatabaseMessageId(), result.getMessageId(),
                              result.getResponseStatus());

          systemStateListener.unregisterForConnectivityChange();
        } catch (InsecureFallbackApprovalException ifae) {
          Log.w("MmsSender", ifae);
          notifyMessageDeliveryFailed(context, threads, threadId);
        } catch (SecureFallbackApprovalException sfae) {
          Log.w("MmsSender", sfae);
          notifyMessageDeliveryFailed(context, threads, threadId);
        } catch (UndeliverableMessageException e) {
          Log.w("MmsSender", e);
          notifyMessageDeliveryFailed(context, threads, threadId);
        } catch (UntrustedIdentityException uie) {
          IncomingIdentityUpdateMessage identityUpdateMessage = IncomingIdentityUpdateMessage.createFor(message.getTo()[0].getString(), uie.getIdentityKey());
          DatabaseFactory.getEncryptingSmsDatabase(context).insertMessageInbox(masterSecret, identityUpdateMessage);
          database.markAsSentFailed(messageId);
          Log.w("MmsSender", uie);
        } catch (RetryLaterException e) {
          Log.w("MmsSender", e);
          toastHandler
              .obtainMessage(0, context.getString(R.string.SmsReceiver_currently_unable_to_send_your_sms_message))
              .sendToTarget();
        }
      }
    } catch (MmsException e) {
        Log.w("MmsSender", e);
    }
  }

  private static void notifyMessageDeliveryFailed(Context context, ThreadDatabase threads, long threadId) {
    Recipients recipients = threads.getRecipientsForThreadId(threadId);
    MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
  }

  private void scheduleQuickRetryAlarm() {
    ((AlarmManager)context.getSystemService(Context.ALARM_SERVICE))
        .set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + (30 * 1000),
             PendingIntent.getService(context, 0,
                                      new Intent(SendReceiveService.SEND_MMS_ACTION,
                                                 null, context, SendReceiveService.class),
                                      PendingIntent.FLAG_UPDATE_CURRENT));
  }
}
