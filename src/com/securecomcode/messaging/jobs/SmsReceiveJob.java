package com.securecomcode.messaging.jobs;

import android.content.Context;
import android.telephony.SmsMessage;
import android.util.Pair;

import com.securecomcode.messaging.ApplicationContext;
import com.securecomcode.messaging.crypto.MasterSecret;
import com.securecomcode.messaging.crypto.MasterSecretUtil;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.EncryptingSmsDatabase;
import com.securecomcode.messaging.notifications.MessageNotifier;
import com.securecomcode.messaging.protocol.WirePrefix;
import com.securecomcode.messaging.service.KeyCachingService;
import com.securecomcode.messaging.sms.IncomingTextMessage;
import com.securecomcode.messaging.sms.MultipartSmsMessageHandler;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libaxolotl.util.guava.Optional;

import java.util.LinkedList;
import java.util.List;

public class SmsReceiveJob extends ContextJob {

    private static final String TAG = SmsReceiveJob.class.getSimpleName();

    private static MultipartSmsMessageHandler multipartMessageHandler = new MultipartSmsMessageHandler();

    private final Object[] pdus;

    public SmsReceiveJob(Context context, Object[] pdus) {
        super(context, JobParameters.newBuilder()
                .withPersistence()
                .create());

        this.pdus = pdus;
    }

    @Override
    public void onAdded() {}

    @Override
    public void onRun() {
    /*Optional<IncomingTextMessage> message = assembleMessageFragments(pdus);

    if (message.isPresent()) {
      Pair<Long, Long> messageAndThreadId = storeMessage(message.get());
      MessageNotifier.updateNotification(context, KeyCachingService.getMasterSecret(context), messageAndThreadId.second);
    }*/
    }

    @Override
    public void onCanceled() {

    }

    @Override
    public boolean onShouldRetry(Exception exception) {
        return false;
    }

    private Pair<Long, Long> storeMessage(IncomingTextMessage message) {
        EncryptingSmsDatabase database     = DatabaseFactory.getEncryptingSmsDatabase(context);
        MasterSecret          masterSecret = KeyCachingService.getMasterSecret(context);

        Pair<Long, Long> messageAndThreadId;

        if (message.isSecureMessage()) {
            messageAndThreadId = database.insertMessageInbox((MasterSecret)null, message);
        } else if (masterSecret == null) {
            messageAndThreadId = database.insertMessageInbox(MasterSecretUtil.getAsymmetricMasterSecret(context, null), message);
        } else {
            messageAndThreadId = database.insertMessageInbox(masterSecret, message);
        }

        if (masterSecret == null || message.isSecureMessage() || message.isKeyExchange()) {
            ApplicationContext.getInstance(context)
                    .getJobManager()
                    .add(new SmsDecryptJob(context, messageAndThreadId.first));
        } else {
            MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
        }

        return messageAndThreadId;
    }

    private Optional<IncomingTextMessage> assembleMessageFragments(Object[] pdus) {
        List<IncomingTextMessage> messages = new LinkedList<>();

        for (Object pdu : pdus) {
            messages.add(new IncomingTextMessage(SmsMessage.createFromPdu((byte[])pdu)));
        }

        if (messages.isEmpty()) {
            return Optional.absent();
        }

        IncomingTextMessage message =  new IncomingTextMessage(messages);

        if (WirePrefix.isEncryptedMessage(message.getMessageBody()) ||
                WirePrefix.isKeyExchange(message.getMessageBody())      ||
                WirePrefix.isPreKeyBundle(message.getMessageBody())     ||
                WirePrefix.isEndSession(message.getMessageBody()))
        {
            return Optional.fromNullable(multipartMessageHandler.processPotentialMultipartMessage(message));
        } else {
            return Optional.of(message);
        }
    }
}
