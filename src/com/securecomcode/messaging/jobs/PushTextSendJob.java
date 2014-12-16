package com.securecomcode.messaging.jobs;

import android.content.Context;
import android.util.Log;

import com.securecomcode.messaging.ApplicationContext;
import com.securecomcode.messaging.crypto.MasterSecret;
import com.securecomcode.messaging.crypto.storage.TextSecureAxolotlStore;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.EncryptingSmsDatabase;
import com.securecomcode.messaging.database.NoSuchMessageException;
import com.securecomcode.messaging.database.model.SmsMessageRecord;
import com.securecomcode.messaging.dependencies.InjectableType;
import com.securecomcode.messaging.notifications.MessageNotifier;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.sms.IncomingIdentityUpdateMessage;
import com.securecomcode.messaging.transport.InsecureFallbackApprovalException;
import com.securecomcode.messaging.transport.RetryLaterException;
import com.securecomcode.messaging.transport.SecureFallbackApprovalException;
import org.whispersystems.libaxolotl.state.AxolotlStore;
import org.whispersystems.textsecure.api.TextSecureMessageSender;
import org.whispersystems.textsecure.api.crypto.UntrustedIdentityException;
import org.whispersystems.textsecure.api.messages.TextSecureMessage;
import org.whispersystems.textsecure.api.push.PushAddress;
import org.whispersystems.textsecure.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.textsecure.api.util.InvalidNumberException;

import java.io.IOException;

import javax.inject.Inject;

import static com.securecomcode.messaging.dependencies.TextSecureCommunicationModule.TextSecureMessageSenderFactory;

public class PushTextSendJob extends PushSendJob implements InjectableType {

  private static final String TAG = PushTextSendJob.class.getSimpleName();

  @Inject transient TextSecureMessageSenderFactory messageSenderFactory;

  private final long messageId;

  public PushTextSendJob(Context context, long messageId, String destination) {
    super(context, constructParameters(context, destination));
    this.messageId = messageId;
  }

  @Override
  public void onAdded() {

  }

  @Override
  public void onRun(MasterSecret masterSecret) throws NoSuchMessageException, RetryLaterException {
    EncryptingSmsDatabase database     = DatabaseFactory.getEncryptingSmsDatabase(context);
    SmsMessageRecord      record       = database.getMessage(masterSecret, messageId);
    String                destination  = record.getIndividualRecipient().getNumber();

    try {
      Log.w(TAG, "Sending message: " + messageId);

      deliver(masterSecret, record, destination);

      database.markAsPush(messageId);
      database.markAsSecure(messageId);
      database.markAsSent(messageId);
    } catch (InsecureFallbackApprovalException e) {
      Log.w(TAG, e);
      database.markAsPendingInsecureSmsFallback(record.getId());
      MessageNotifier.notifyMessageDeliveryFailed(context, record.getRecipients(), record.getThreadId());
    } catch (SecureFallbackApprovalException e) {
      Log.w(TAG, e);
      database.markAsPendingSecureSmsFallback(record.getId());
      MessageNotifier.notifyMessageDeliveryFailed(context, record.getRecipients(), record.getThreadId());
    } catch (UntrustedIdentityException e) {
      Log.w(TAG, e);
      IncomingIdentityUpdateMessage identityUpdateMessage = IncomingIdentityUpdateMessage.createFor(e.getE164Number(), e.getIdentityKey());
      database.insertMessageInbox(masterSecret, identityUpdateMessage);
      database.markAsSentFailed(record.getId());
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    if (exception instanceof RetryLaterException) return true;

    return false;
  }

  @Override
  public void onCanceled() {
    DatabaseFactory.getSmsDatabase(context).markAsSentFailed(messageId);

    long       threadId   = DatabaseFactory.getSmsDatabase(context).getThreadIdForMessage(messageId);
    Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);

    MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
  }

  private void deliver(MasterSecret masterSecret, SmsMessageRecord message, String destination)
      throws UntrustedIdentityException, SecureFallbackApprovalException,
             InsecureFallbackApprovalException, RetryLaterException
  {
    boolean isSmsFallbackSupported = isSmsFallbackSupported(context, destination);

    try {
      PushAddress             address       = getPushAddress(message.getIndividualRecipient());
      TextSecureMessageSender messageSender = messageSenderFactory.create(masterSecret);

      if (message.isEndSession()) {
        messageSender.sendMessage(address, new TextSecureMessage(message.getDateSent(), null,
                                                                 null, null, true, true));
      } else {
        messageSender.sendMessage(address, new TextSecureMessage(message.getDateSent(), null,
                                                                 message.getBody().getBody()));
      }
    } catch (InvalidNumberException | UnregisteredUserException e) {
      Log.w(TAG, e);
      if (isSmsFallbackSupported) fallbackOrAskApproval(masterSecret, message, destination);
      else                        DatabaseFactory.getSmsDatabase(context).markAsSentFailed(messageId);
    } catch (IOException e) {
      Log.w(TAG, e);
      if (isSmsFallbackSupported) fallbackOrAskApproval(masterSecret, message, destination);
      else                        throw new RetryLaterException(e);
    }
  }

  private void fallbackOrAskApproval(MasterSecret masterSecret, SmsMessageRecord smsMessage, String destination)
      throws SecureFallbackApprovalException, InsecureFallbackApprovalException
  {
    Recipient    recipient                     = smsMessage.getIndividualRecipient();
    boolean      isSmsFallbackApprovalRequired = isSmsFallbackApprovalRequired(destination);
    AxolotlStore axolotlStore                  = new TextSecureAxolotlStore(context, masterSecret);

    if (!isSmsFallbackApprovalRequired) {
      Log.w(TAG, "Falling back to SMS");
      DatabaseFactory.getSmsDatabase(context).markAsForcedSms(smsMessage.getId());
      ApplicationContext.getInstance(context).getJobManager().add(new SmsSendJob(context, messageId, destination));
    } else if (!axolotlStore.containsSession(recipient.getRecipientId(), PushAddress.DEFAULT_DEVICE_ID)) {
      Log.w(TAG, "Marking message as pending insecure fallback.");
      throw new InsecureFallbackApprovalException("Pending user approval for fallback to insecure SMS");
    } else {
      Log.w(TAG, "Marking message as pending secure fallback.");
      throw new SecureFallbackApprovalException("Pending user approval for fallback to secure SMS");
    }
  }


}
