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

import android.content.Context;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.securecomcode.messaging.crypto.MasterSecret;
import com.securecomcode.messaging.crypto.MmsCipher;
import com.securecomcode.messaging.crypto.storage.TextSecureAxolotlStore;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.MmsDatabase;
import com.securecomcode.messaging.database.NoSuchMessageException;
import com.securecomcode.messaging.jobs.requirements.MasterSecretRequirement;
import com.securecomcode.messaging.mms.ApnUnavailableException;
import com.securecomcode.messaging.mms.MmsRadio;
import com.securecomcode.messaging.mms.MmsRadioException;
import com.securecomcode.messaging.mms.MmsSendResult;
import com.securecomcode.messaging.mms.OutgoingMmsConnection;
import com.securecomcode.messaging.notifications.MessageNotifier;
import com.securecomcode.messaging.recipients.RecipientFormattingException;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.transport.InsecureFallbackApprovalException;
import com.securecomcode.messaging.transport.UndeliverableMessageException;
import com.securecomcode.messaging.util.Hex;
import com.securecomcode.messaging.util.NumberUtil;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;
import org.whispersystems.libaxolotl.NoSessionException;

import java.io.IOException;
import java.util.Arrays;

import ws.com.google.android.mms.MmsException;
import ws.com.google.android.mms.pdu.EncodedStringValue;
import ws.com.google.android.mms.pdu.PduComposer;
import ws.com.google.android.mms.pdu.PduHeaders;
import ws.com.google.android.mms.pdu.SendConf;
import ws.com.google.android.mms.pdu.SendReq;

public class MmsSendJob extends MasterSecretJob {

  private static final String TAG = MmsSendJob.class.getSimpleName();

  private final long messageId;

  public MmsSendJob(Context context, long messageId) {
    super(context, JobParameters.newBuilder()
                                .withGroupId("mms-operation")
                                .withRequirement(new NetworkRequirement(context))
                                .withRequirement(new MasterSecretRequirement(context))
                                .withPersistence()
                                .create());

    this.messageId = messageId;
  }

  @Override
  public void onAdded() {

  }

  @Override
  public void onRun(MasterSecret masterSecret) throws MmsException, NoSuchMessageException {

  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    return false;
  }

  @Override
  public void onCanceled() {
    DatabaseFactory.getMmsDatabase(context).markAsSentFailed(messageId);
    notifyMediaMessageDeliveryFailed(context, messageId);
  }

  public MmsSendResult deliver(MasterSecret masterSecret, SendReq message)
      throws UndeliverableMessageException, InsecureFallbackApprovalException
  {

    validateDestinations(message);

    MmsRadio radio = MmsRadio.getInstance(context);

    try {
      if (isCdmaDevice()) {
        Log.w(TAG, "Sending MMS directly without radio change...");
        try {
          return sendMms(masterSecret, radio, message, false, false);
        } catch (IOException e) {
          Log.w(TAG, e);
        }
      }

      Log.w(TAG, "Sending MMS with radio change and proxy...");
      radio.connect();

      try {
        MmsSendResult result = sendMms(masterSecret, radio, message, true, true);
        radio.disconnect();
        return result;
      } catch (IOException e) {
        Log.w(TAG, e);
      }

      Log.w(TAG, "Sending MMS with radio change and without proxy...");

      try {
        MmsSendResult result = sendMms(masterSecret, radio, message, true, false);
        radio.disconnect();
        return result;
      } catch (IOException ioe) {
        Log.w(TAG, ioe);
        radio.disconnect();
        throw new UndeliverableMessageException(ioe);
      }

    } catch (MmsRadioException mre) {
      Log.w(TAG, mre);
      throw new UndeliverableMessageException(mre);
    }
  }

  private MmsSendResult sendMms(MasterSecret masterSecret, MmsRadio radio, SendReq message,
                                boolean usingMmsRadio, boolean useProxy)
      throws IOException, UndeliverableMessageException, InsecureFallbackApprovalException
  {
    return null;
  }

  private SendReq getEncryptedMessage(MasterSecret masterSecret, SendReq pdu)
      throws InsecureFallbackApprovalException
  {
    try {
      MmsCipher cipher = new MmsCipher(new TextSecureAxolotlStore(context, masterSecret));
      return cipher.encrypt(context, pdu);
    } catch (NoSessionException e) {
      throw new InsecureFallbackApprovalException(e);
    } catch (RecipientFormattingException e) {
      throw new AssertionError(e);
    }
  }

  private boolean isInconsistentResponse(SendReq message, SendConf response) {
    Log.w(TAG, "Comparing: " + Hex.toString(message.getTransactionId()));
    Log.w(TAG, "With:      " + Hex.toString(response.getTransactionId()));
    return !Arrays.equals(message.getTransactionId(), response.getTransactionId());
  }

  private boolean isCdmaDevice() {
    return ((TelephonyManager)context
        .getSystemService(Context.TELEPHONY_SERVICE))
        .getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA;
  }

  private void validateDestination(EncodedStringValue destination) throws UndeliverableMessageException {
    if (destination == null || !NumberUtil.isValidSmsOrEmail(destination.getString())) {
      throw new UndeliverableMessageException("Invalid destination: " +
                                                  (destination == null ? null : destination.getString()));
    }
  }

  private void validateDestinations(SendReq message) throws UndeliverableMessageException {
    if (message.getTo() != null) {
      for (EncodedStringValue to : message.getTo()) {
        validateDestination(to);
      }
    }

    if (message.getCc() != null) {
      for (EncodedStringValue cc : message.getCc()) {
        validateDestination(cc);
      }
    }

    if (message.getBcc() != null) {
      for (EncodedStringValue bcc : message.getBcc()) {
        validateDestination(bcc);
      }
    }

    if (message.getTo() == null && message.getCc() == null && message.getBcc() == null) {
      throw new UndeliverableMessageException("No to, cc, or bcc specified!");
    }
  }

  private void notifyMediaMessageDeliveryFailed(Context context, long messageId) {
    long       threadId   = DatabaseFactory.getMmsDatabase(context).getThreadIdForMessage(messageId);
    Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);

    MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
  }



}
