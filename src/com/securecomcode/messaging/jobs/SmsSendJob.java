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

package com.securecomcode.messaging.jobs;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;
import android.util.Log;

import com.securecomcode.messaging.crypto.MasterSecret;
import com.securecomcode.messaging.crypto.SmsCipher;
import com.securecomcode.messaging.crypto.storage.TextSecureAxolotlStore;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.EncryptingSmsDatabase;
import com.securecomcode.messaging.database.NoSuchMessageException;
import com.securecomcode.messaging.database.model.SmsMessageRecord;
import com.securecomcode.messaging.jobs.requirements.MasterSecretRequirement;
import com.securecomcode.messaging.jobs.requirements.ServiceRequirement;
import com.securecomcode.messaging.notifications.MessageNotifier;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.service.SmsDeliveryListener;
import com.securecomcode.messaging.sms.MultipartSmsMessageHandler;
import com.securecomcode.messaging.sms.OutgoingTextMessage;
import com.securecomcode.messaging.transport.InsecureFallbackApprovalException;
import com.securecomcode.messaging.transport.UndeliverableMessageException;
import com.securecomcode.messaging.util.NumberUtil;
import com.securecomcode.messaging.util.TextSecurePreferences;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libaxolotl.NoSessionException;

import java.util.ArrayList;

public class SmsSendJob extends MasterSecretJob {

  private static final String TAG = SmsSendJob.class.getSimpleName();

  private final long messageId;

  public SmsSendJob(Context context, long messageId, String name) {
    super(context, JobParameters.newBuilder()
                                .withPersistence()
                                .withRequirement(new MasterSecretRequirement(context))
                                .withRequirement(new ServiceRequirement(context))
                                .withGroupId(name)
                                .create());

    this.messageId = messageId;
  }

  @Override
  public void onAdded() {

  }

  @Override
  public void onRun(MasterSecret masterSecret) throws NoSuchMessageException {

  }

  @Override
  public boolean onShouldRetryThrowable(Exception throwable) {
    return false;
  }

  @Override
  public void onCanceled() {
    Log.w(TAG, "onCanceled()");
    long       threadId   = DatabaseFactory.getSmsDatabase(context).getThreadIdForMessage(messageId);
    Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);

    DatabaseFactory.getSmsDatabase(context).markAsSentFailed(messageId);
    MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
  }

  private void deliver(MasterSecret masterSecret, SmsMessageRecord record)
      throws UndeliverableMessageException, InsecureFallbackApprovalException
  {
    if (!NumberUtil.isValidSmsOrEmail(record.getIndividualRecipient().getNumber())) {
      throw new UndeliverableMessageException("Not a valid SMS destination! " + record.getIndividualRecipient().getNumber());
    }

    if (record.isSecure() || record.isKeyExchange() || record.isEndSession()) {
      deliverSecureMessage(masterSecret, record);
    } else {
      deliverPlaintextMessage(record);
    }
  }

  private void deliverSecureMessage(MasterSecret masterSecret, SmsMessageRecord message)
      throws UndeliverableMessageException, InsecureFallbackApprovalException
  {
    MultipartSmsMessageHandler multipartMessageHandler = new MultipartSmsMessageHandler();
    OutgoingTextMessage transportMessage               = OutgoingTextMessage.from(message);

    if (message.isSecure() || message.isEndSession()) {
      transportMessage = getAsymmetricEncrypt(masterSecret, transportMessage);
    }

    ArrayList<String> messages                = multipartMessageHandler.divideMessage(transportMessage);
    ArrayList<PendingIntent> sentIntents      = constructSentIntents(message.getId(), message.getType(), messages, message.isSecure());
    ArrayList<PendingIntent> deliveredIntents = constructDeliveredIntents(message.getId(), message.getType(), messages);

    Log.w("SmsTransport", "Secure divide into message parts: " + messages.size());

    for (int i=0;i<messages.size();i++) {
      // NOTE 11/04/14 -- There's apparently a bug where for some unknown recipients
      // and messages, this will throw an NPE.  We have no idea why, so we're just
      // catching it and marking the message as a failure.  That way at least it
      // doesn't repeatedly crash every time you start the app.
      try {
        SmsManager.getDefault().sendTextMessage(message.getIndividualRecipient().getNumber(), null, messages.get(i),
                                                sentIntents.get(i),
                                                deliveredIntents == null ? null : deliveredIntents.get(i));
      } catch (NullPointerException npe) {
        Log.w(TAG, npe);
        Log.w(TAG, "Recipient: " + message.getIndividualRecipient().getNumber());
        Log.w(TAG, "Message Total Parts/Current: " + messages.size() + "/" + i);
        Log.w(TAG, "Message Part Length: " + messages.get(i).getBytes().length);
        throw new UndeliverableMessageException(npe);
      } catch (IllegalArgumentException iae) {
        Log.w(TAG, iae);
        throw new UndeliverableMessageException(iae);
      }
    }
  }

  private void deliverPlaintextMessage(SmsMessageRecord message)
      throws UndeliverableMessageException
  {
    ArrayList<String> messages                = SmsManager.getDefault().divideMessage(message.getBody().getBody());
    ArrayList<PendingIntent> sentIntents      = constructSentIntents(message.getId(), message.getType(), messages, false);
    ArrayList<PendingIntent> deliveredIntents = constructDeliveredIntents(message.getId(), message.getType(), messages);
    String recipient                          = message.getIndividualRecipient().getNumber();

    // NOTE 11/04/14 -- There's apparently a bug where for some unknown recipients
    // and messages, this will throw an NPE.  We have no idea why, so we're just
    // catching it and marking the message as a failure.  That way at least it doesn't
    // repeatedly crash every time you start the app.
    try {
      SmsManager.getDefault().sendMultipartTextMessage(recipient, null, messages, sentIntents, deliveredIntents);
    } catch (NullPointerException npe) {
      Log.w(TAG, npe);
      Log.w(TAG, "Recipient: " + recipient);
      Log.w(TAG, "Message Parts: " + messages.size());

      try {
        for (int i=0;i<messages.size();i++) {
          SmsManager.getDefault().sendTextMessage(recipient, null, messages.get(i),
                                                  sentIntents.get(i),
                                                  deliveredIntents == null ? null : deliveredIntents.get(i));
        }
      } catch (NullPointerException npe2) {
        Log.w(TAG, npe);
        throw new UndeliverableMessageException(npe2);
      }
    }
  }

  private OutgoingTextMessage getAsymmetricEncrypt(MasterSecret masterSecret,
                                                   OutgoingTextMessage message)
      throws InsecureFallbackApprovalException
  {
    try {
      return new SmsCipher(new TextSecureAxolotlStore(context, masterSecret)).encrypt(message);
    } catch (NoSessionException e) {
      throw new InsecureFallbackApprovalException(e);
    }
  }

  private ArrayList<PendingIntent> constructSentIntents(long messageId, long type,
                                                        ArrayList<String> messages, boolean secure)
  {
    ArrayList<PendingIntent> sentIntents = new ArrayList<>(messages.size());

    for (String ignored : messages) {
      sentIntents.add(PendingIntent.getBroadcast(context, 0,
                                                 constructSentIntent(context, messageId, type, secure, false),
                                                 0));
    }

    return sentIntents;
  }

  private ArrayList<PendingIntent> constructDeliveredIntents(long messageId, long type, ArrayList<String> messages) {
    if (!TextSecurePreferences.isSmsDeliveryReportsEnabled(context)) {
      return null;
    }

    ArrayList<PendingIntent> deliveredIntents = new ArrayList<>(messages.size());

    for (String ignored : messages) {
      deliveredIntents.add(PendingIntent.getBroadcast(context, 0,
                                                      constructDeliveredIntent(context, messageId, type),
                                                      0));
    }

    return deliveredIntents;
  }

  private Intent constructSentIntent(Context context, long messageId, long type,
                                       boolean upgraded, boolean push)
  {
    Intent pending = new Intent(SmsDeliveryListener.SENT_SMS_ACTION,
                                Uri.parse("custom://" + messageId + System.currentTimeMillis()),
                                context, SmsDeliveryListener.class);

    pending.putExtra("type", type);
    pending.putExtra("message_id", messageId);
    pending.putExtra("upgraded", upgraded);
    pending.putExtra("push", push);

    return pending;
  }

  protected Intent constructDeliveredIntent(Context context, long messageId, long type) {
    Intent pending = new Intent(SmsDeliveryListener.DELIVERED_SMS_ACTION,
                                Uri.parse("custom://" + messageId + System.currentTimeMillis()),
                                context, SmsDeliveryListener.class);
    pending.putExtra("type", type);
    pending.putExtra("message_id", messageId);

    return pending;
  }


}
