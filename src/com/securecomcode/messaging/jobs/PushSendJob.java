package com.securecomcode.messaging.jobs;

import android.content.Context;
import android.util.Log;

import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.jobs.requirements.MasterSecretRequirement;
import com.securecomcode.messaging.notifications.MessageNotifier;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.util.GroupUtil;
import com.securecomcode.messaging.util.TextSecurePreferences;
import com.securecomcode.messaging.util.Util;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;
import org.whispersystems.textsecure.api.messages.TextSecureAttachment;
import org.whispersystems.textsecure.api.messages.TextSecureAttachmentStream;
import com.securecomcode.messaging.database.TextSecureDirectory;
import org.whispersystems.textsecure.api.push.PushAddress;
import org.whispersystems.textsecure.api.util.InvalidNumberException;

import java.io.ByteArrayInputStream;
import java.util.LinkedList;
import java.util.List;

import ws.com.google.android.mms.ContentType;
import ws.com.google.android.mms.pdu.SendReq;

public abstract class PushSendJob extends MasterSecretJob {

  private static final String TAG = PushSendJob.class.getSimpleName();

  protected PushSendJob(Context context, JobParameters parameters) {
    super(context, parameters);
  }

  protected static JobParameters constructParameters(Context context, String destination) {
    JobParameters.Builder builder = JobParameters.newBuilder();
    builder.withPersistence();
    builder.withGroupId(destination);
    builder.withRequirement(new MasterSecretRequirement(context));

    if (!isSmsFallbackSupported(context, destination)) {
      builder.withRequirement(new NetworkRequirement(context));
      builder.withRetryCount(5);
    }

    return builder.create();
  }

  protected static boolean isSmsFallbackSupported(Context context, String destination) {
    if (GroupUtil.isEncodedGroup(destination)) {
      return false;
    }

    if (!TextSecurePreferences.isFallbackSmsAllowed(context)) {
      return false;
    }

    TextSecureDirectory directory = TextSecureDirectory.getInstance(context);
    return directory.isSmsFallbackSupported(destination);
  }

  protected PushAddress getPushAddress(Recipient recipient) throws InvalidNumberException {
    String e164number = Util.canonicalizeNumber(context, recipient.getNumber());
    String relay      = TextSecureDirectory.getInstance(context).getRelay(e164number);
    return new PushAddress(recipient.getRecipientId(), e164number, 1, relay);
  }

  protected boolean isSmsFallbackApprovalRequired(String destination) {
    return (isSmsFallbackSupported(context, destination) && TextSecurePreferences.isFallbackSmsAskRequired(context));
  }

  protected List<TextSecureAttachment> getAttachments(SendReq message) {
    List<TextSecureAttachment> attachments = new LinkedList<>();

    for (int i=0;i<message.getBody().getPartsNum();i++) {
      String contentType = Util.toIsoString(message.getBody().getPart(i).getContentType());
      if (ContentType.isImageType(contentType) ||
          ContentType.isAudioType(contentType) ||
          ContentType.isVideoType(contentType) ||
          ContentType.isOtherType(contentType))
      {
        byte[] data = message.getBody().getPart(i).getData();
        Log.w(TAG, "Adding attachment...");
        attachments.add(new TextSecureAttachmentStream(new ByteArrayInputStream(data), contentType, data.length));
      }
    }

    return attachments;
  }

  protected void notifyMediaMessageDeliveryFailed(Context context, long messageId) {
    long       threadId   = DatabaseFactory.getMmsDatabase(context).getThreadIdForMessage(messageId);
    Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);

    MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
  }
}
