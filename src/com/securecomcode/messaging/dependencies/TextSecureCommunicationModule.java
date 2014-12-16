package com.securecomcode.messaging.dependencies;

import android.content.Context;

import com.securecomcode.messaging.Release;
import com.securecomcode.messaging.crypto.MasterSecret;
import com.securecomcode.messaging.crypto.storage.TextSecureAxolotlStore;
import com.securecomcode.messaging.jobs.AttachmentDownloadJob;
import com.securecomcode.messaging.jobs.AvatarDownloadJob;
import com.securecomcode.messaging.jobs.CleanPreKeysJob;
import com.securecomcode.messaging.jobs.CreateSignedPreKeyJob;
import com.securecomcode.messaging.jobs.DeliveryReceiptJob;
import com.securecomcode.messaging.jobs.PushGroupSendJob;
import com.securecomcode.messaging.jobs.PushMediaSendJob;
import com.securecomcode.messaging.jobs.PushTextSendJob;
import com.securecomcode.messaging.jobs.RefreshPreKeysJob;
import com.securecomcode.messaging.push.SecurityEventListener;
import com.securecomcode.messaging.push.TextSecurePushTrustStore;
import com.securecomcode.messaging.util.TextSecurePreferences;
import org.whispersystems.libaxolotl.util.guava.Optional;
import org.whispersystems.textsecure.api.TextSecureAccountManager;
import org.whispersystems.textsecure.api.TextSecureMessageReceiver;
import org.whispersystems.textsecure.api.TextSecureMessageSender;

import dagger.Module;
import dagger.Provides;

@Module(complete = false, injects = {CleanPreKeysJob.class,
                                     CreateSignedPreKeyJob.class,
                                     DeliveryReceiptJob.class,
                                     PushGroupSendJob.class,
                                     PushTextSendJob.class,
                                     PushMediaSendJob.class,
                                     AttachmentDownloadJob.class,
                                     RefreshPreKeysJob.class})
public class TextSecureCommunicationModule {

  private final Context context;

  public TextSecureCommunicationModule(Context context) {
    this.context = context;
  }

  @Provides TextSecureAccountManager provideTextSecureAccountManager() {
    return new TextSecureAccountManager(Release.PUSH_URL,
                                        new TextSecurePushTrustStore(context),
                                        TextSecurePreferences.getLocalNumber(context),
                                        TextSecurePreferences.getPushServerPassword(context));
  }

  @Provides TextSecureMessageSenderFactory provideTextSecureMessageSenderFactory() {
    return new TextSecureMessageSenderFactory() {
      @Override
      public TextSecureMessageSender create(MasterSecret masterSecret) {
        return new TextSecureMessageSender(Release.PUSH_URL,
                                           new TextSecurePushTrustStore(context),
                                           TextSecurePreferences.getLocalNumber(context),
                                           TextSecurePreferences.getPushServerPassword(context),
                                           new TextSecureAxolotlStore(context, masterSecret),
                                           Optional.of((TextSecureMessageSender.EventListener)
                                                           new SecurityEventListener(context)));
      }
    };
  }

  @Provides TextSecureMessageReceiver provideTextSecureMessageReceiver() {
    return new TextSecureMessageReceiver(Release.PUSH_URL,
                                           new TextSecurePushTrustStore(context),
                                           TextSecurePreferences.getLocalNumber(context),
                                           TextSecurePreferences.getPushServerPassword(context));
  }

  public static interface TextSecureMessageSenderFactory {
    public TextSecureMessageSender create(MasterSecret masterSecret);
  }

}
