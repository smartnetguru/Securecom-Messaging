package com.securecomcode.messaging.jobs;

import android.content.Context;
import android.util.Log;

import com.securecomcode.messaging.ApplicationContext;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.util.TextSecurePreferences;
import org.whispersystems.jobqueue.JobManager;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libaxolotl.InvalidVersionException;
import org.whispersystems.textsecure.api.messages.TextSecureEnvelope;
import com.securecomcode.messaging.database.TextSecureDirectory;
import com.securecomcode.messaging.database.NotInDirectoryException;
import org.whispersystems.textsecure.api.push.ContactTokenDetails;

import java.io.IOException;

public class PushReceiveJob extends ContextJob {

  private static final String TAG = PushReceiveJob.class.getSimpleName();

  private final String data;

  public PushReceiveJob(Context context, String data) {
    super(context, JobParameters.newBuilder()
                                .withPersistence()
                                .create());

    this.data = data;
  }

  @Override
  public void onAdded() {}

  @Override
  public void onRun() {
    try {
      String             sessionKey = TextSecurePreferences.getSignalingKey(context);
      TextSecureEnvelope envelope   = new TextSecureEnvelope(data, sessionKey);

      if (!isActiveNumber(context, envelope.getSource())) {
        TextSecureDirectory directory           = TextSecureDirectory.getInstance(context);
        ContactTokenDetails contactTokenDetails = new ContactTokenDetails();
        contactTokenDetails.setNumber(envelope.getSource());

        directory.setNumber(contactTokenDetails, true);
      }

      if (envelope.isReceipt()) handleReceipt(envelope);
      else                     handleMessage(envelope);
    } catch (IOException | InvalidVersionException e) {
      Log.w(TAG, e);
    }
  }

  @Override
  public void onCanceled() {

  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    return false;
  }

  private void handleMessage(TextSecureEnvelope envelope) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    long       messageId  = DatabaseFactory.getPushDatabase(context).insert(envelope);

    jobManager.add(new DeliveryReceiptJob(context, envelope.getSource(),
                                          envelope.getTimestamp(),
                                          envelope.getRelay()));

    jobManager.add(new PushDecryptJob(context, messageId));
  }

  private void handleReceipt(TextSecureEnvelope envelope) {
    Log.w(TAG, String.format("Received receipt: (XXXXX, %d)", envelope.getTimestamp()));
    DatabaseFactory.getMmsSmsDatabase(context).incrementDeliveryReceiptCount(envelope.getSource(),
                                                                             envelope.getTimestamp());
  }

  private boolean isActiveNumber(Context context, String e164number) {
    boolean isActiveNumber;

    try {
      isActiveNumber = TextSecureDirectory.getInstance(context).isActiveNumber(e164number);
    } catch (NotInDirectoryException e) {
      isActiveNumber = false;
    }

    return isActiveNumber;
  }

}
