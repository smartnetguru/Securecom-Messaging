/**
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2014 Securecom
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

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;

import com.securecomcode.messaging.R;
import com.securecomcode.messaging.crypto.KeyExchangeProcessor;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.EncryptingSmsDatabase;
import com.securecomcode.messaging.database.SmsDatabase;
import com.securecomcode.messaging.database.model.SmsMessageRecord;
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
import org.whispersystems.textsecure.storage.Session;

public class SmsSender {

  private final Context             context;
  private final SystemStateListener systemStateListener;
  private final ToastHandler        toastHandler;

  public SmsSender(Context context, SystemStateListener systemStateListener, ToastHandler toastHandler) {
    this.context             = context;
    this.systemStateListener = systemStateListener;
    this.toastHandler        = toastHandler;
  }

  public void process(MasterSecret masterSecret, Intent intent) {
    if (SendReceiveService.SEND_SMS_ACTION.equals(intent.getAction())) {
      handleSendMessage(masterSecret, intent);
    } else if (SendReceiveService.SENT_SMS_ACTION.equals(intent.getAction())) {
      handleSentMessage(intent);
    } else if (SendReceiveService.DELIVERED_SMS_ACTION.equals(intent.getAction())) {
      handleDeliveredMessage(intent);
    }
  }

  private void handleSendMessage(MasterSecret masterSecret, Intent intent) {
    long messageId                      = intent.getLongExtra("message_id", -1);
    UniversalTransport transport        = new UniversalTransport(context, masterSecret);
    EncryptingSmsDatabase database      = DatabaseFactory.getEncryptingSmsDatabase(context);

    EncryptingSmsDatabase.Reader reader = null;
    SmsMessageRecord record;

    Log.w("SmsSender", "Sending message: " + messageId);

    try {
      if (messageId != -1) reader = database.getMessage(masterSecret, messageId);
      else                 reader = database.getOutgoingMessages(masterSecret);

      while (reader != null && (record = reader.getNext()) != null) {
        try {
          database.markAsSending(record.getId());

          transport.deliver(record);
        } catch (InsecureFallbackApprovalException ifae) {
          Log.w("SmsSender", ifae);
        } catch (SecureFallbackApprovalException sfae) {
          Log.w("SmsSender", sfae);
        } catch (UntrustedIdentityException e) {
          IncomingIdentityUpdateMessage identityUpdateMessage = IncomingIdentityUpdateMessage.createFor(e.getE164Number(), e.getIdentityKey());
          DatabaseFactory.getEncryptingSmsDatabase(context).insertMessageInbox(masterSecret, identityUpdateMessage);
          DatabaseFactory.getSmsDatabase(context).markAsSentFailed(record.getId());
          Log.w("SmsSender", e);
        } catch (UndeliverableMessageException ude) {
            DatabaseFactory.getSmsDatabase(context).markAsSentFailed(record.getId());
            MessageNotifier.notifyMessageDeliveryFailed(context, record.getRecipients(), record.getThreadId());
          Log.w("SmsSender", ude);
        } catch (RetryLaterException rle) {
          Log.w("SmsSender", rle);
        }
      }
    } finally {
      if (reader != null)
        reader.close();
    }
  }

  private void handleSentMessage(Intent intent) {
    long    messageId = intent.getLongExtra("message_id", -1);
    int     result    = intent.getIntExtra("ResultCode", -31337);
    boolean upgraded  = intent.getBooleanExtra("upgraded", false);
    boolean push      = intent.getBooleanExtra("push", false);

    Log.w("SMSReceiverService", "Intent resultcode: " + result);
    Log.w("SMSReceiverService", "Running sent callback: " + messageId);

    if (result == Activity.RESULT_OK) {
      SmsDatabase        database = DatabaseFactory.getSmsDatabase(context);
      Cursor             cursor   = database.getMessage(messageId);
      SmsDatabase.Reader reader   = database.readerFor(cursor);

      if (push)     database.markAsPush(messageId);
      if (upgraded) database.markAsSecure(messageId);
      database.markAsSent(messageId);

      SmsMessageRecord record = reader.getNext();

      if (record != null && record.isEndSession()) {
        Log.w("SmsSender", "Ending session...");
        Session.abortSessionFor(context, record.getIndividualRecipient());
        KeyExchangeProcessor.broadcastSecurityUpdateEvent(context, record.getThreadId());
      }

      unregisterForRadioChanges();
    } else if (result == SmsManager.RESULT_ERROR_NO_SERVICE || result == SmsManager.RESULT_ERROR_RADIO_OFF) {
        Log.w("SmsSender", "Error no service");
    } else {
      long threadId         = DatabaseFactory.getSmsDatabase(context).getThreadIdForMessage(messageId);
      Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);

      DatabaseFactory.getSmsDatabase(context).markAsSentFailed(messageId);
      MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
      unregisterForRadioChanges();
    }
  }

  private void handleDeliveredMessage(Intent intent) {
    long messageId     = intent.getLongExtra("message_id", -1);
    byte[] pdu         = intent.getByteArrayExtra("pdu");
    SmsMessage message = SmsMessage.createFromPdu(pdu);

    if (message == null) {
        return;
    }

    DatabaseFactory.getSmsDatabase(context).markStatus(messageId, message.getStatus());
  }

  private void registerForRadioChanges() {
	systemStateListener.registerForConnectivityChange();
  }

  private void unregisterForRadioChanges() {
    systemStateListener.unregisterForConnectivityChange();
  }

  private void scheduleQuickRetryAlarm() {
    ((AlarmManager)context.getSystemService(Context.ALARM_SERVICE))
        .set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + (30 * 1000),
             PendingIntent.getService(context, 0,
                                      new Intent(SendReceiveService.SEND_SMS_ACTION,
                                                 null, context, SendReceiveService.class),
                                      PendingIntent.FLAG_UPDATE_CURRENT));
  }

}
