/**
 * Copyright (C) 2011 Whisper Systems
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
package com.securecomcode.messaging;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.securecomcode.messaging.crypto.IdentityKeyParcelable;
import com.securecomcode.messaging.crypto.MasterSecret;
import com.securecomcode.messaging.crypto.storage.TextSecureIdentityKeyStore;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.EncryptingSmsDatabase;
import com.securecomcode.messaging.database.IdentityDatabase;
import com.securecomcode.messaging.database.PushDatabase;
import com.securecomcode.messaging.jobs.PushDecryptJob;
import com.securecomcode.messaging.jobs.SmsDecryptJob;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.sms.IncomingIdentityUpdateMessage;
import com.securecomcode.messaging.sms.IncomingKeyExchangeMessage;
import com.securecomcode.messaging.sms.IncomingPreKeyBundleMessage;
import com.securecomcode.messaging.sms.IncomingTextMessage;
import com.securecomcode.messaging.util.Base64;
import com.securecomcode.messaging.util.MemoryCleaner;
import org.whispersystems.libaxolotl.IdentityKey;
import org.whispersystems.libaxolotl.InvalidKeyException;
import org.whispersystems.libaxolotl.InvalidMessageException;
import org.whispersystems.libaxolotl.InvalidVersionException;
import org.whispersystems.libaxolotl.LegacyMessageException;
import org.whispersystems.libaxolotl.protocol.KeyExchangeMessage;
import org.whispersystems.libaxolotl.protocol.PreKeyWhisperMessage;
import org.whispersystems.libaxolotl.state.IdentityKeyStore;
import org.whispersystems.libaxolotl.util.guava.Optional;
import org.whispersystems.textsecure.api.messages.TextSecureEnvelope;
import org.whispersystems.textsecure.api.messages.TextSecureGroup;

import java.io.IOException;

/**
 * Activity for displaying sent/received session keys.
 *
 * @author Moxie Marlinspike
 */

public class ReceiveKeyActivity extends Activity {

  private TextView descriptionText;

  private Button confirmButton;
  private Button cancelButton;

  private Recipient recipient;
  private int       recipientDeviceId;
  private long      messageId;

  private MasterSecret               masterSecret;
  private IncomingKeyExchangeMessage message;
  private IdentityKey                identityKey;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.receive_key_activity);

    initializeResources();

    try {
      initializeKey();
      initializeText();
    } catch (InvalidKeyException | InvalidVersionException | InvalidMessageException | LegacyMessageException ike) {
      Log.w("ReceiveKeyActivity", ike);
    }
    initializeListeners();
  }

  @Override
  protected void onDestroy() {
    MemoryCleaner.clean(masterSecret);
    super.onDestroy();
  }

  private void initializeText() {
    if (isTrusted(this.identityKey)) {
      initializeTrustedText();
    } else {
      initializeUntrustedText();
    }
  }

  private void initializeTrustedText() {
    descriptionText.setText(getString(R.string.ReceiveKeyActivity_the_signature_on_this_key_exchange_is_trusted_but));
  }

  private void initializeUntrustedText() {
    SpannableString spannableString = new SpannableString(getString(R.string.ReceiveKeyActivity_the_signature_on_this_key_exchange_is_different) + " " +
                                                          getString(R.string.ReceiveKeyActivity_you_may_wish_to_verify_this_contact));
    spannableString.setSpan(new ClickableSpan() {
      @Override
      public void onClick(View widget) {
        Intent intent = new Intent(ReceiveKeyActivity.this, VerifyIdentityActivity.class);
        intent.putExtra("recipient", recipient);
        intent.putExtra("master_secret", masterSecret);
        intent.putExtra("remote_identity", new IdentityKeyParcelable(identityKey));
        startActivity(intent);
      }
    }, getString(R.string.ReceiveKeyActivity_the_signature_on_this_key_exchange_is_different).length() +1,
       spannableString.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    descriptionText.setText(spannableString);
    descriptionText.setMovementMethod(LinkMovementMethod.getInstance());
  }

  private boolean isTrusted(IdentityKey identityKey) {
    long             recipientId      = recipient.getRecipientId();
    IdentityKeyStore identityKeyStore = new TextSecureIdentityKeyStore(this, masterSecret);

    return identityKeyStore.isTrustedIdentity(recipientId, identityKey);
  }

  private void initializeKey()
      throws InvalidKeyException, InvalidVersionException,
             InvalidMessageException, LegacyMessageException
  {
    IncomingTextMessage message = new IncomingTextMessage(recipient.getNumber(),
                                                          recipientDeviceId,
                                                          System.currentTimeMillis(),
                                                          getIntent().getStringExtra("body"),
                                                          Optional.<TextSecureGroup>absent());

    if (getIntent().getBooleanExtra("is_bundle", false)) {
      this.message = new IncomingPreKeyBundleMessage(message, message.getMessageBody());
    } else if (getIntent().getBooleanExtra("is_identity_update", false)) {
      this.message = new IncomingIdentityUpdateMessage(message, message.getMessageBody());
    } else {
      this.message = new IncomingKeyExchangeMessage(message, message.getMessageBody());
    }

    this.identityKey = getIdentityKey(this.message);
  }

  private void initializeResources() {
    this.descriptionText      = (TextView) findViewById(R.id.description_text);
    this.confirmButton        = (Button)   findViewById(R.id.ok_button);
    this.cancelButton         = (Button)   findViewById(R.id.cancel_button);
    this.recipient            = getIntent().getParcelableExtra("recipient");
    this.recipientDeviceId    = getIntent().getIntExtra("recipient_device_id", -1);
    this.messageId            = getIntent().getLongExtra("message_id", -1);
    this.masterSecret         = getIntent().getParcelableExtra("master_secret");
  }

  private void initializeListeners() {
    this.confirmButton.setOnClickListener(new OkListener());
    this.cancelButton.setOnClickListener(new CancelListener());
  }

  private IdentityKey getIdentityKey(IncomingKeyExchangeMessage message)
      throws InvalidKeyException, InvalidVersionException,
             InvalidMessageException, LegacyMessageException
  {
    try {
      if (message.isIdentityUpdate()) {
        return new IdentityKey(Base64.decodeWithoutPadding(message.getMessageBody()), 0);
      } else if (message.isPreKeyBundle()) {
        boolean isPush = getIntent().getBooleanExtra("is_push", false);

        if (isPush) return new PreKeyWhisperMessage(Base64.decode(message.getMessageBody())).getIdentityKey();
        else        return new PreKeyWhisperMessage(Base64.decodeWithoutPadding(message.getMessageBody())).getIdentityKey();
      } else {
        return new KeyExchangeMessage(Base64.decodeWithoutPadding(message.getMessageBody())).getIdentityKey();
      }
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private class OkListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      new AsyncTask<Void, Void, Void> () {
        private ProgressDialog dialog;

        @Override
        protected void onPreExecute() {
          dialog = ProgressDialog.show(ReceiveKeyActivity.this,
                                       getString(R.string.ReceiveKeyActivity_processing),
                                       getString(R.string.ReceiveKeyActivity_processing_key_exchange),
                                       true);
        }

        @Override
        protected Void doInBackground(Void... params) {
          Context               context          = ReceiveKeyActivity.this;
          IdentityDatabase      identityDatabase = DatabaseFactory.getIdentityDatabase(context);
          EncryptingSmsDatabase smsDatabase      = DatabaseFactory.getEncryptingSmsDatabase(context);
          PushDatabase          pushDatabase     = DatabaseFactory.getPushDatabase(context);

          identityDatabase.saveIdentity(masterSecret, recipient.getRecipientId(), identityKey);

          if (message.isIdentityUpdate()) {
            smsDatabase.markAsProcessedKeyExchange(messageId);
          } else {
            if (getIntent().getBooleanExtra("is_push", false)) {
              try {
                byte[]             body     = Base64.decode(message.getMessageBody());
                TextSecureEnvelope envelope = new TextSecureEnvelope(3, message.getSender(),
                                                                     message.getSenderDeviceId(), "",
                                                                     message.getSentTimestampMillis(),
                                                                     body);

                long pushId = pushDatabase.insert(envelope);

                ApplicationContext.getInstance(context)
                                  .getJobManager()
                                  .add(new PushDecryptJob(context, pushId));

                smsDatabase.deleteMessage(messageId);
              } catch (IOException e) {
                throw new AssertionError(e);
              }
            } else {
              ApplicationContext.getInstance(context)
                                .getJobManager()
                                .add(new SmsDecryptJob(context, messageId));
            }
          }

          return null;
        }

        @Override
        protected void onPostExecute(Void result) {
          dialog.dismiss();
          finish();
        }
      }.execute();
    }
  }

  private class CancelListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      ReceiveKeyActivity.this.finish();
    }
  }
}
