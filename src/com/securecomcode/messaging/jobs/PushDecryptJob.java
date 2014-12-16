package com.securecomcode.messaging.jobs;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import com.securecomcode.messaging.ApplicationContext;
import com.securecomcode.messaging.crypto.MasterSecret;
import com.securecomcode.messaging.crypto.SecurityEvent;
import com.securecomcode.messaging.crypto.storage.TextSecureAxolotlStore;
import com.securecomcode.messaging.crypto.storage.TextSecureSessionStore;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.EncryptingSmsDatabase;
import com.securecomcode.messaging.database.MmsDatabase;
import com.securecomcode.messaging.database.NoSuchMessageException;
import com.securecomcode.messaging.database.PushDatabase;
import com.securecomcode.messaging.groups.GroupMessageProcessor;
import com.securecomcode.messaging.jobs.requirements.MasterSecretRequirement;
import com.securecomcode.messaging.mms.IncomingMediaMessage;
import com.securecomcode.messaging.notifications.MessageNotifier;
import com.securecomcode.messaging.recipients.RecipientFactory;
import com.securecomcode.messaging.recipients.RecipientFormattingException;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.service.KeyCachingService;
import com.securecomcode.messaging.sms.IncomingEncryptedMessage;
import com.securecomcode.messaging.sms.IncomingEndSessionMessage;
import com.securecomcode.messaging.sms.IncomingPreKeyBundleMessage;
import com.securecomcode.messaging.sms.IncomingTextMessage;
import com.securecomcode.messaging.util.Base64;
import com.securecomcode.messaging.util.TextSecurePreferences;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libaxolotl.DuplicateMessageException;
import org.whispersystems.libaxolotl.InvalidKeyException;
import org.whispersystems.libaxolotl.InvalidKeyIdException;
import org.whispersystems.libaxolotl.InvalidMessageException;
import org.whispersystems.libaxolotl.InvalidVersionException;
import org.whispersystems.libaxolotl.LegacyMessageException;
import org.whispersystems.libaxolotl.NoSessionException;
import org.whispersystems.libaxolotl.UntrustedIdentityException;
import org.whispersystems.libaxolotl.state.AxolotlStore;
import org.whispersystems.libaxolotl.state.SessionStore;
import org.whispersystems.libaxolotl.util.guava.Optional;
import org.whispersystems.textsecure.api.messages.TextSecureEnvelope;
import org.whispersystems.textsecure.api.messages.TextSecureGroup;
import org.whispersystems.textsecure.api.messages.TextSecureMessage;
import org.whispersystems.textsecure.api.crypto.TextSecureCipher;

import ws.com.google.android.mms.MmsException;

public class PushDecryptJob extends MasterSecretJob {

  public static final String TAG = PushDecryptJob.class.getSimpleName();

  private final long messageId;

  public PushDecryptJob(Context context, long messageId) {
    super(context, JobParameters.newBuilder()
                                .withPersistence()
                                .withRequirement(new MasterSecretRequirement(context))
                                .create());
    this.messageId = messageId;
  }

  @Override
  public void onAdded() {
    if (KeyCachingService.getMasterSecret(context) == null) {
      MessageNotifier.updateNotification(context, null);
    }
  }

  @Override
  public void onRun(MasterSecret masterSecret) throws NoSuchMessageException {
    PushDatabase       database = DatabaseFactory.getPushDatabase(context);
    TextSecureEnvelope envelope = database.get(messageId);

    handleMessage(masterSecret, envelope);
    database.delete(messageId);
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    return false;
  }

  @Override
  public void onCanceled() {

  }

  private void handleMessage(MasterSecret masterSecret, TextSecureEnvelope envelope) {
    try {
      Recipients       recipients   = RecipientFactory.getRecipientsFromString(context, envelope.getSource(), false);
      long             recipientId  = recipients.getPrimaryRecipient().getRecipientId();
      int              deviceId     = envelope.getSourceDevice();
      AxolotlStore     axolotlStore = new TextSecureAxolotlStore(context, masterSecret);
      TextSecureCipher cipher       = new TextSecureCipher(axolotlStore, recipientId, deviceId);

      TextSecureMessage message = cipher.decrypt(envelope);

      if      (message.isEndSession())               handleEndSessionMessage(masterSecret, recipientId, envelope, message);
      else if (message.isGroupUpdate())              handleGroupMessage(masterSecret, envelope, message);
      else if (message.getAttachments().isPresent()) handleMediaMessage(masterSecret, envelope, message);
      else                                           handleTextMessage(masterSecret, envelope, message);

      if (envelope.isPreKeyWhisperMessage()) {
        ApplicationContext.getInstance(context).getJobManager().add(new RefreshPreKeysJob(context));
      }
    } catch (InvalidVersionException e) {
      Log.w(TAG, e);
      handleInvalidVersionMessage(masterSecret, envelope);
    } catch (InvalidMessageException | InvalidKeyIdException | InvalidKeyException | MmsException | RecipientFormattingException e) {
      Log.w(TAG, e);
      handleCorruptMessage(masterSecret, envelope);
    } catch (NoSessionException e) {
      Log.w(TAG, e);
      handleNoSessionMessage(masterSecret, envelope);
    } catch (LegacyMessageException e) {
      Log.w(TAG, e);
      handleLegacyMessage(masterSecret, envelope);
    } catch (DuplicateMessageException e) {
      Log.w(TAG, e);
      handleDuplicateMessage(masterSecret, envelope);
    } catch (UntrustedIdentityException e) {
      Log.w(TAG, e);
      handleUntrustedIdentityMessage(masterSecret, envelope);
    }
  }

  private void handleEndSessionMessage(MasterSecret masterSecret, long recipientId,
                                       TextSecureEnvelope envelope, TextSecureMessage message)
  {
    IncomingTextMessage incomingTextMessage = new IncomingTextMessage(envelope.getSource(),
                                                                      envelope.getSourceDevice(),
                                                                      message.getTimestamp(),
                                                                      "", Optional.<TextSecureGroup>absent());

    IncomingEndSessionMessage incomingEndSessionMessage = new IncomingEndSessionMessage(incomingTextMessage);
    EncryptingSmsDatabase     database                  = DatabaseFactory.getEncryptingSmsDatabase(context);
    Pair<Long, Long>          messageAndThreadId        = database.insertMessageInbox(masterSecret, incomingEndSessionMessage);

    SessionStore sessionStore = new TextSecureSessionStore(context, masterSecret);
    sessionStore.deleteAllSessions(recipientId);

    SecurityEvent.broadcastSecurityUpdateEvent(context, messageAndThreadId.second);
    MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
  }

  private void handleGroupMessage(MasterSecret masterSecret, TextSecureEnvelope envelope, TextSecureMessage message) {
    GroupMessageProcessor.process(context, masterSecret, envelope, message);
  }

  private void handleMediaMessage(MasterSecret masterSecret, TextSecureEnvelope envelope, TextSecureMessage message)
      throws MmsException
  {
    String               localNumber  = TextSecurePreferences.getLocalNumber(context);
    MmsDatabase          database     = DatabaseFactory.getMmsDatabase(context);
    IncomingMediaMessage mediaMessage = new IncomingMediaMessage(masterSecret, envelope.getSource(),
                                                                 localNumber, message.getTimestamp(),
                                                                 Optional.fromNullable(envelope.getRelay()),
                                                                 message.getBody(),
                                                                 message.getGroupInfo(),
                                                                 message.getAttachments());

    Pair<Long, Long> messageAndThreadId;

    if (message.isSecure()) {
      messageAndThreadId = database.insertSecureDecryptedMessageInbox(masterSecret, mediaMessage, -1);
    } else {
      messageAndThreadId = database.insertMessageInbox(masterSecret, mediaMessage, null, -1);
    }

    ApplicationContext.getInstance(context)
                      .getJobManager()
                      .add(new AttachmentDownloadJob(context, messageAndThreadId.first));

    MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
  }

  private void handleTextMessage(MasterSecret masterSecret, TextSecureEnvelope envelope, TextSecureMessage message) {
    EncryptingSmsDatabase database    = DatabaseFactory.getEncryptingSmsDatabase(context);
    String                body        = message.getBody().isPresent() ? message.getBody().get() : "";
    IncomingTextMessage   textMessage = new IncomingTextMessage(envelope.getSource(),
                                                                envelope.getSourceDevice(),
                                                                message.getTimestamp(), body,
                                                                message.getGroupInfo());

    if (message.isSecure()) {
      textMessage = new IncomingEncryptedMessage(textMessage, body);
    }

    Pair<Long, Long> messageAndThreadId = database.insertMessageInbox(masterSecret, textMessage);
    MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
  }

  private void handleInvalidVersionMessage(MasterSecret masterSecret, TextSecureEnvelope envelope) {
    Pair<Long, Long> messageAndThreadId = insertPlaceholder(masterSecret, envelope);
    DatabaseFactory.getEncryptingSmsDatabase(context).markAsInvalidVersionKeyExchange(messageAndThreadId.first);

    MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
  }

  private void handleCorruptMessage(MasterSecret masterSecret, TextSecureEnvelope envelope) {
    Pair<Long, Long> messageAndThreadId = insertPlaceholder(masterSecret, envelope);
    DatabaseFactory.getEncryptingSmsDatabase(context).markAsDecryptFailed(messageAndThreadId.first);

    MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
  }

  private void handleNoSessionMessage(MasterSecret masterSecret, TextSecureEnvelope envelope) {
    Pair<Long, Long> messageAndThreadId = insertPlaceholder(masterSecret, envelope);
    DatabaseFactory.getEncryptingSmsDatabase(context).markAsNoSession(messageAndThreadId.first);

    MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
  }

  private void handleLegacyMessage(MasterSecret masterSecret, TextSecureEnvelope envelope) {
    Pair<Long, Long> messageAndThreadId = insertPlaceholder(masterSecret, envelope);
    DatabaseFactory.getEncryptingSmsDatabase(context).markAsLegacyVersion(messageAndThreadId.first);

    MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
  }

  private void handleDuplicateMessage(MasterSecret masterSecret, TextSecureEnvelope envelope) {
    Pair<Long, Long> messageAndThreadId = insertPlaceholder(masterSecret, envelope);
    DatabaseFactory.getEncryptingSmsDatabase(context).markAsDecryptDuplicate(messageAndThreadId.first);

    MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
  }

  private void handleUntrustedIdentityMessage(MasterSecret masterSecret, TextSecureEnvelope envelope) {
    String              encoded     = Base64.encodeBytes(envelope.getMessage());
    IncomingTextMessage textMessage = new IncomingTextMessage(envelope.getSource(), envelope.getSourceDevice(),
                                                              envelope.getTimestamp(), encoded,
                                                              Optional.<TextSecureGroup>absent());

    IncomingPreKeyBundleMessage bundleMessage      = new IncomingPreKeyBundleMessage(textMessage, encoded);
    Pair<Long, Long>            messageAndThreadId = DatabaseFactory.getEncryptingSmsDatabase(context)
                                                                    .insertMessageInbox(masterSecret, bundleMessage);

    MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
  }

  private Pair<Long, Long> insertPlaceholder(MasterSecret masterSecret, TextSecureEnvelope envelope) {
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);

    IncomingTextMessage textMessage = new IncomingTextMessage(envelope.getSource(), envelope.getSourceDevice(),
                                                              envelope.getTimestamp(), "",
                                                              Optional.<TextSecureGroup>absent());

    textMessage = new IncomingEncryptedMessage(textMessage, "");

    return database.insertMessageInbox(masterSecret, textMessage);
  }
}
