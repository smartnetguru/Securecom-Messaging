package com.securecomcode.messaging.push;

import android.content.Context;

import com.securecomcode.messaging.crypto.SecurityEvent;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.recipients.RecipientFactory;
import com.securecomcode.messaging.recipients.Recipients;
import org.whispersystems.textsecure.api.TextSecureMessageSender;

public class SecurityEventListener implements TextSecureMessageSender.EventListener {

  private static final String TAG = SecurityEventListener.class.getSimpleName();

  private final Context context;

  public SecurityEventListener(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public void onSecurityEvent(long recipientId) {
    Recipients recipients = RecipientFactory.getRecipientsForIds(context, String.valueOf(recipientId), false);
    long       threadId   = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipients);

    SecurityEvent.broadcastSecurityUpdateEvent(context, threadId);
  }

}
