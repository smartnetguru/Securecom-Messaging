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
import android.util.Log;

import com.securecomcode.messaging.ApplicationContext;
import com.securecomcode.messaging.crypto.MasterSecret;
import com.securecomcode.messaging.crypto.storage.TextSecureAxolotlStore;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.MmsDatabase;
import com.securecomcode.messaging.database.NoSuchMessageException;
import com.securecomcode.messaging.dependencies.InjectableType;
import com.securecomcode.messaging.mms.PartParser;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.RecipientFactory;
import com.securecomcode.messaging.recipients.RecipientFormattingException;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.sms.IncomingIdentityUpdateMessage;
import com.securecomcode.messaging.transport.InsecureFallbackApprovalException;
import com.securecomcode.messaging.transport.RetryLaterException;
import com.securecomcode.messaging.transport.SecureFallbackApprovalException;
import org.whispersystems.libaxolotl.state.AxolotlStore;
import org.whispersystems.textsecure.api.TextSecureMessageSender;
import org.whispersystems.textsecure.api.crypto.UntrustedIdentityException;
import org.whispersystems.textsecure.api.messages.TextSecureAttachment;
import org.whispersystems.textsecure.api.messages.TextSecureMessage;
import org.whispersystems.textsecure.api.push.PushAddress;
import org.whispersystems.textsecure.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.textsecure.api.util.InvalidNumberException;

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;

import ws.com.google.android.mms.MmsException;
import ws.com.google.android.mms.pdu.SendReq;

import static com.securecomcode.messaging.dependencies.TextSecureCommunicationModule.TextSecureMessageSenderFactory;

public class PushMediaSendJob extends PushSendJob implements InjectableType {

  private static final String TAG = PushMediaSendJob.class.getSimpleName();

  @Inject transient TextSecureMessageSenderFactory messageSenderFactory;

  private final long messageId;

  public PushMediaSendJob(Context context, long messageId, String destination) {
    super(context, constructParameters(context, destination));
    this.messageId = messageId;
  }

  @Override
  public void onAdded() {

  }

  @Override
  public void onRun(MasterSecret masterSecret)
      throws RetryLaterException, MmsException, NoSuchMessageException
  {
    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    SendReq     message  = database.getOutgoingMessage(masterSecret, messageId);

    try {
      deliver(masterSecret, message);

      database.markAsPush(messageId);
      database.markAsSecure(messageId);
      database.markAsSent(messageId, "push".getBytes(), 0);
    } catch (InsecureFallbackApprovalException ifae) {
      Log.w(TAG, ifae);
      database.markAsPendingInsecureSmsFallback(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    } catch (SecureFallbackApprovalException sfae) {
      Log.w(TAG, sfae);
      database.markAsPendingSecureSmsFallback(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    } catch (UntrustedIdentityException uie) {
      IncomingIdentityUpdateMessage identityUpdateMessage = IncomingIdentityUpdateMessage.createFor(message.getTo()[0].getString(), uie.getIdentityKey());
      DatabaseFactory.getEncryptingSmsDatabase(context).insertMessageInbox(masterSecret, identityUpdateMessage);
      database.markAsSentFailed(messageId);
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    if (exception instanceof RequirementNotMetException) return true;
    return false;
  }

  @Override
  public void onCanceled() {
    DatabaseFactory.getMmsDatabase(context).markAsSentFailed(messageId);
    notifyMediaMessageDeliveryFailed(context, messageId);
  }


  private void deliver(MasterSecret masterSecret, SendReq message)
      throws RetryLaterException, SecureFallbackApprovalException,
             InsecureFallbackApprovalException, UntrustedIdentityException
  {
    MmsDatabase             database               = DatabaseFactory.getMmsDatabase(context);
    TextSecureMessageSender messageSender          = messageSenderFactory.create(masterSecret);
    String                  destination            = message.getTo()[0].getString();
    boolean                 isSmsFallbackSupported = isSmsFallbackSupported(context, destination);

    try {
      Recipients                 recipients   = RecipientFactory.getRecipientsFromString(context, destination, false);
      PushAddress                address      = getPushAddress(recipients.getPrimaryRecipient());
      List<TextSecureAttachment> attachments  = getAttachments(message);
      String                     body         = PartParser.getMessageText(message.getBody());
      TextSecureMessage          mediaMessage = new TextSecureMessage(message.getSentTimestamp(), attachments, body);

      messageSender.sendMessage(address, mediaMessage);
    } catch (InvalidNumberException | UnregisteredUserException e) {
      Log.w(TAG, e);
      database.markAsSentFailed(messageId);
    } catch (IOException | RecipientFormattingException e) {
      Log.w(TAG, e);
      throw new RetryLaterException(e);
    }
  }

  private void fallbackOrAskApproval(MasterSecret masterSecret, SendReq mediaMessage, String destination)
      throws SecureFallbackApprovalException, InsecureFallbackApprovalException
  {
    try {
      Recipient    recipient                     = RecipientFactory.getRecipientsFromString(context, destination, false).getPrimaryRecipient();
      boolean      isSmsFallbackApprovalRequired = isSmsFallbackApprovalRequired(destination);
      AxolotlStore axolotlStore                  = new TextSecureAxolotlStore(context, masterSecret);

      if (!isSmsFallbackApprovalRequired) {
        Log.w(TAG, "Falling back to MMS");
        DatabaseFactory.getMmsDatabase(context).markAsForcedSms(mediaMessage.getDatabaseMessageId());
        ApplicationContext.getInstance(context).getJobManager().add(new MmsSendJob(context, messageId));
      } else if (!axolotlStore.containsSession(recipient.getRecipientId(), PushAddress.DEFAULT_DEVICE_ID)) {
        Log.w(TAG, "Marking message as pending insecure SMS fallback");
        throw new InsecureFallbackApprovalException("Pending user approval for fallback to insecure SMS");
      } else {
        Log.w(TAG, "Marking message as pending secure SMS fallback");
        throw new SecureFallbackApprovalException("Pending user approval for fallback secure to SMS");
      }
    } catch (RecipientFormattingException rfe) {
      Log.w(TAG, rfe);
      DatabaseFactory.getMmsDatabase(context).markAsSentFailed(messageId);
    }
  }


}
