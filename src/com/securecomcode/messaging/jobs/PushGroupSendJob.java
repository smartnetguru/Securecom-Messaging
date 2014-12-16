package com.securecomcode.messaging.jobs;

import android.content.Context;
import android.util.Log;

import com.securecomcode.messaging.crypto.MasterSecret;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.MmsDatabase;
import com.securecomcode.messaging.database.MmsSmsColumns;
import com.securecomcode.messaging.database.NoSuchMessageException;
import com.securecomcode.messaging.dependencies.InjectableType;
import com.securecomcode.messaging.jobs.requirements.MasterSecretRequirement;
import com.securecomcode.messaging.mms.PartParser;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.RecipientFormattingException;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.sms.IncomingIdentityUpdateMessage;
import com.securecomcode.messaging.util.Base64;
import com.securecomcode.messaging.util.GroupUtil;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;
import org.whispersystems.textsecure.api.TextSecureMessageSender;
import org.whispersystems.textsecure.api.crypto.UntrustedIdentityException;
import org.whispersystems.textsecure.api.messages.TextSecureAttachment;
import org.whispersystems.textsecure.api.messages.TextSecureGroup;
import org.whispersystems.textsecure.api.messages.TextSecureMessage;
import org.whispersystems.textsecure.api.push.PushAddress;
import org.whispersystems.textsecure.internal.push.PushMessageProtos;
import org.whispersystems.textsecure.api.push.exceptions.EncapsulatedExceptions;
import org.whispersystems.textsecure.api.util.InvalidNumberException;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;

import ws.com.google.android.mms.MmsException;
import ws.com.google.android.mms.pdu.SendReq;

import static com.securecomcode.messaging.dependencies.TextSecureCommunicationModule.TextSecureMessageSenderFactory;

public class PushGroupSendJob extends PushSendJob implements InjectableType {

  private static final String TAG = PushGroupSendJob.class.getSimpleName();

  @Inject transient TextSecureMessageSenderFactory messageSenderFactory;

  private final long messageId;

  public PushGroupSendJob(Context context, long messageId, String destination) {
    super(context, JobParameters.newBuilder()
                                .withPersistence()
                                .withGroupId(destination)
                                .withRequirement(new MasterSecretRequirement(context))
                                .withRequirement(new NetworkRequirement(context))
                                .withRetryCount(5)
                                .create());

    this.messageId = messageId;
  }

  @Override
  public void onAdded() {

  }

  @Override
  public void onRun(MasterSecret masterSecret) throws MmsException, IOException, NoSuchMessageException {
    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    SendReq     message  = database.getOutgoingMessage(masterSecret, messageId);

    try {
      deliver(masterSecret, message);

      database.markAsPush(messageId);
      database.markAsSecure(messageId);
      database.markAsSent(messageId, "push".getBytes(), 0);
    } catch (InvalidNumberException | RecipientFormattingException e) {
      Log.w(TAG, e);
      database.markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    } catch (EncapsulatedExceptions e) {
      Log.w(TAG, e);
      if (!e.getUnregisteredUserExceptions().isEmpty()) {
        database.markAsSentFailed(messageId);
      }

      for (UntrustedIdentityException uie : e.getUntrustedIdentityExceptions()) {
        IncomingIdentityUpdateMessage identityUpdateMessage = IncomingIdentityUpdateMessage.createFor(message.getTo()[0].getString(), uie.getIdentityKey());
        DatabaseFactory.getEncryptingSmsDatabase(context).insertMessageInbox(masterSecret, identityUpdateMessage);
        database.markAsSentFailed(messageId);
      }

      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    if (exception instanceof IOException) return true;
    return false;
  }

  @Override
  public void onCanceled() {
    DatabaseFactory.getMmsDatabase(context).markAsSentFailed(messageId);
  }

  private void deliver(MasterSecret masterSecret, SendReq message)
      throws IOException, RecipientFormattingException, InvalidNumberException, EncapsulatedExceptions
  {
    TextSecureMessageSender    messageSender = messageSenderFactory.create(masterSecret);
    byte[]                     groupId       = GroupUtil.getDecodedId(message.getTo()[0].getString());
    Recipients                 recipients    = DatabaseFactory.getGroupDatabase(context).getGroupMembers(groupId, false);
    List<PushAddress>          addresses     = getPushAddresses(recipients);
    List<TextSecureAttachment> attachments   = getAttachments(message);

    if (MmsSmsColumns.Types.isGroupUpdate(message.getDatabaseMessageBox()) ||
        MmsSmsColumns.Types.isGroupQuit(message.getDatabaseMessageBox()))
    {
      String content = PartParser.getMessageText(message.getBody());

      if (content != null && !content.trim().isEmpty()) {
        PushMessageProtos.PushMessageContent.GroupContext groupContext = PushMessageProtos.PushMessageContent.GroupContext.parseFrom(Base64.decode(content));
        TextSecureAttachment avatar       = attachments.isEmpty() ? null : attachments.get(0);
        TextSecureGroup.Type type         = MmsSmsColumns.Types.isGroupQuit(message.getDatabaseMessageBox()) ? TextSecureGroup.Type.QUIT : TextSecureGroup.Type.UPDATE;
        TextSecureGroup      group        = new TextSecureGroup(type, groupId, groupContext.getName(), groupContext.getMembersList(), avatar);
        TextSecureMessage groupMessage = new TextSecureMessage(message.getSentTimestamp(), group, null, null);

        messageSender.sendMessage(addresses, groupMessage);
      }
    } else {
      String            body         = PartParser.getMessageText(message.getBody());
      TextSecureGroup   group        = new TextSecureGroup(groupId);
      TextSecureMessage groupMessage = new TextSecureMessage(message.getSentTimestamp(), group, attachments, body);

      messageSender.sendMessage(addresses, groupMessage);
    }
  }

  private List<PushAddress> getPushAddresses(Recipients recipients) throws InvalidNumberException {
    List<PushAddress> addresses = new LinkedList<>();

    for (Recipient recipient : recipients.getRecipientsList()) {
      addresses.add(getPushAddress(recipient));
    }

    return addresses;
  }

}
