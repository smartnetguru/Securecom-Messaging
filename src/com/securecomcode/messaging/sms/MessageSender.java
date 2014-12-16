/**
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2014 Securecom Inc
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
package com.securecomcode.messaging.sms;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import com.securecomcode.messaging.ApplicationContext;
import com.securecomcode.messaging.crypto.MasterSecret;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.EncryptingSmsDatabase;
import com.securecomcode.messaging.database.MmsDatabase;
import com.securecomcode.messaging.database.NotInDirectoryException;
import com.securecomcode.messaging.database.TextSecureDirectory;
import com.securecomcode.messaging.database.ThreadDatabase;
import com.securecomcode.messaging.database.model.MessageRecord;
import com.securecomcode.messaging.jobs.MmsSendJob;
import com.securecomcode.messaging.jobs.PushGroupSendJob;
import com.securecomcode.messaging.jobs.PushMediaSendJob;
import com.securecomcode.messaging.jobs.PushTextSendJob;
import com.securecomcode.messaging.jobs.SmsSendJob;
import com.securecomcode.messaging.mms.OutgoingMediaMessage;
import com.securecomcode.messaging.push.TextSecureCommunicationFactory;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.util.GroupUtil;
import com.securecomcode.messaging.util.TextSecurePreferences;
import com.securecomcode.messaging.util.Util;
import org.whispersystems.jobqueue.JobManager;
import org.whispersystems.libaxolotl.util.guava.Optional;
import org.whispersystems.textsecure.api.TextSecureAccountManager;
import org.whispersystems.textsecure.api.push.ContactTokenDetails;
import com.securecomcode.messaging.util.DirectoryUtil;
import org.whispersystems.textsecure.api.util.InvalidNumberException;

import java.io.IOException;

import ws.com.google.android.mms.MmsException;

public class MessageSender {

  private static final String TAG = MessageSender.class.getSimpleName();

  public static long send(final Context context,
                          final MasterSecret masterSecret,
                          final OutgoingTextMessage message,
                          final long threadId,
                          final boolean forceSms)
  {
    EncryptingSmsDatabase database    = DatabaseFactory.getEncryptingSmsDatabase(context);
    Recipients            recipients  = message.getRecipients();
    boolean               keyExchange = message.isKeyExchange();

    long allocatedThreadId;

    if (threadId == -1) {
      allocatedThreadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipients);
    } else {
      allocatedThreadId = threadId;
    }

    long messageId = database.insertMessageOutbox(masterSecret, allocatedThreadId, message, forceSms);

    sendTextMessage(context, recipients, forceSms, keyExchange, messageId);

    return allocatedThreadId;
  }

  public static long send(final Context context,
                          final MasterSecret masterSecret,
                          final OutgoingMediaMessage message,
                          final long threadId,
                          final boolean forceSms)
  {
    try {
      ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
      MmsDatabase    database       = DatabaseFactory.getMmsDatabase(context);

      long allocatedThreadId;

      if (threadId == -1) {
        allocatedThreadId = threadDatabase.getThreadIdFor(message.getRecipients(), message.getDistributionType());
      } else {
        allocatedThreadId = threadId;
      }

      Recipients recipients = message.getRecipients();
      long       messageId  = database.insertMessageOutbox(masterSecret, message, allocatedThreadId, forceSms);

      sendMediaMessage(context, masterSecret, recipients, forceSms, messageId);

      return allocatedThreadId;
    } catch (MmsException e) {
      Log.w(TAG, e);
      return threadId;
    }
  }

  public static void resend(Context context, MasterSecret masterSecret, MessageRecord messageRecord) {
    try {
      Recipients recipients  = messageRecord.getRecipients();
      long       messageId   = messageRecord.getId();
      boolean    forceSms    = messageRecord.isForcedSms();
      boolean    keyExchange = messageRecord.isKeyExchange();

      if (messageRecord.isMms()) {
        sendMediaMessage(context, masterSecret, recipients, forceSms, messageId);
      } else {
        sendTextMessage(context, recipients, forceSms, keyExchange, messageId);
      }
    } catch (MmsException e) {
      Log.w(TAG, e);
    }
  }

  private static void sendMediaMessage(Context context, MasterSecret masterSecret,
                                       Recipients recipients, boolean forceSms, long messageId)
      throws MmsException
  {
    if (isGroupPushSend(recipients)) {
      sendGroupPush(context, recipients, messageId);
    } else if (!forceSms && isPushMediaSend(context, recipients)) {
      sendMediaPush(context, recipients, messageId);
    }
  }

  private static void sendTextMessage(Context context, Recipients recipients,
                                      boolean forceSms, boolean keyExchange, long messageId)
  {
    if (!forceSms && isPushTextSend(context, recipients, keyExchange)) {
      sendTextPush(context, recipients, messageId);
    }
  }

  private static void sendTextSelf(Context context, long messageId) {
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);

    database.markAsSent(messageId);
    database.markAsPush(messageId);

    Pair<Long, Long> messageAndThreadId = database.copyMessageInbox(messageId);
    database.markAsPush(messageAndThreadId.first);
  }

  private static void sendMediaSelf(Context context, MasterSecret masterSecret, long messageId)
      throws MmsException
  {

  }

  private static void sendTextPush(Context context, Recipients recipients, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new PushTextSendJob(context, messageId, recipients.getPrimaryRecipient().getNumber()));
  }

  private static void sendMediaPush(Context context, Recipients recipients, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new PushMediaSendJob(context, messageId, recipients.getPrimaryRecipient().getNumber()));
  }

  private static void sendGroupPush(Context context, Recipients recipients, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new PushGroupSendJob(context, messageId, recipients.getPrimaryRecipient().getNumber()));
  }

  private static void sendSms(Context context, Recipients recipients, long messageId) {

  }

  private static void sendMms(Context context, long messageId) {

  }

  private static boolean isPushTextSend(Context context, Recipients recipients, boolean keyExchange) {
    try {
      if (!TextSecurePreferences.isPushRegistered(context)) {
        return false;
      }

      if (keyExchange) {
        return false;
      }

      Recipient recipient   = recipients.getPrimaryRecipient();
      String    destination = Util.canonicalizeNumber(context, recipient.getNumber());

      return isPushDestination(context, destination);
    } catch (InvalidNumberException e) {
      Log.w(TAG, e);
      return false;
    }
  }

  private static boolean isPushMediaSend(Context context, Recipients recipients) {
    try {
      if (!TextSecurePreferences.isPushRegistered(context)) {
        return false;
      }

      if (recipients.getRecipientsList().size() > 1) {
        return false;
      }

      Recipient recipient   = recipients.getPrimaryRecipient();
      String    destination = Util.canonicalizeNumber(context, recipient.getNumber());

      return isPushDestination(context, destination);
    } catch (InvalidNumberException e) {
      Log.w(TAG, e);
      return false;
    }
  }

  private static boolean isGroupPushSend(Recipients recipients) {
    return GroupUtil.isEncodedGroup(recipients.getPrimaryRecipient().getNumber());
  }

  private static boolean isSelfSend(Context context, Recipients recipients) {
    try {
      if (!TextSecurePreferences.isPushRegistered(context)) {
        return false;
      }

      if (!recipients.isSingleRecipient()) {
        return false;
      }

      if (recipients.isGroupRecipient()) {
        return false;
      }

      String e164number = Util.canonicalizeNumber(context, recipients.getPrimaryRecipient().getNumber());
      return TextSecurePreferences.getLocalNumber(context).equals(e164number);
    } catch (InvalidNumberException e) {
      Log.w("MessageSender", e);
      return false;
    }
  }

  private static boolean isPushDestination(Context context, String destination) {
    TextSecureDirectory directory = TextSecureDirectory.getInstance(context);

    try {
      return directory.isActiveNumber(destination);
    } catch (NotInDirectoryException e) {
      try {
        TextSecureAccountManager      accountManager = TextSecureCommunicationFactory.createManager(context);
        String                        contactToken   = DirectoryUtil.getDirectoryServerToken(destination);
        Optional<ContactTokenDetails> registeredUser = accountManager.getContact(contactToken);

        if (!registeredUser.isPresent()) {
          registeredUser = Optional.of(new ContactTokenDetails());
          registeredUser.get().setNumber(destination);
          directory.setNumber(registeredUser.get(), false);
          return false;
        } else {
          registeredUser.get().setNumber(destination);
          directory.setNumber(registeredUser.get(), true);
          return true;
        }
      } catch (IOException e1) {
        Log.w(TAG, e1);
        return false;
      }
    }
  }

}
