package com.securecomcode.messaging.push;

import android.content.Context;

import com.securecomcode.messaging.Release;
import com.securecomcode.messaging.crypto.SecurityEvent;
import com.securecomcode.messaging.crypto.MasterSecret;
import com.securecomcode.messaging.crypto.storage.TextSecureAxolotlStore;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.recipients.RecipientFactory;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.util.TextSecurePreferences;
import org.whispersystems.libaxolotl.util.guava.Optional;
import org.whispersystems.textsecure.api.TextSecureAccountManager;
import org.whispersystems.textsecure.api.TextSecureMessageReceiver;
import org.whispersystems.textsecure.api.TextSecureMessageSender;

import static org.whispersystems.textsecure.api.TextSecureMessageSender.EventListener;

public class TextSecureCommunicationFactory {

  public static TextSecureAccountManager createManager(Context context) {
    return new TextSecureAccountManager(Release.PUSH_URL,
                                        new TextSecurePushTrustStore(context),
                                        TextSecurePreferences.getLocalNumber(context),
                                        TextSecurePreferences.getPushServerPassword(context));
  }

  public static TextSecureAccountManager createManager(Context context, String number, String password) {
    return new TextSecureAccountManager(Release.PUSH_URL, new TextSecurePushTrustStore(context),
                                        number, password);
  }

}
