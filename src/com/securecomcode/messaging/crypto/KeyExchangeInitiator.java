/**
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 Open Whisper Systems
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
package com.securecomcode.messaging.crypto;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import com.securecomcode.messaging.R;
import com.securecomcode.messaging.crypto.storage.TextSecureIdentityKeyStore;
import com.securecomcode.messaging.crypto.storage.TextSecurePreKeyStore;
import com.securecomcode.messaging.crypto.storage.TextSecureSessionStore;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.sms.MessageSender;
import com.securecomcode.messaging.sms.OutgoingKeyExchangeMessage;
import com.securecomcode.messaging.util.Base64;
import com.securecomcode.messaging.util.Dialogs;
import org.whispersystems.libaxolotl.SessionBuilder;
import org.whispersystems.libaxolotl.protocol.KeyExchangeMessage;
import org.whispersystems.libaxolotl.state.IdentityKeyStore;
import org.whispersystems.libaxolotl.state.PreKeyStore;
import org.whispersystems.libaxolotl.state.SessionRecord;
import org.whispersystems.libaxolotl.state.SessionStore;
import org.whispersystems.libaxolotl.state.SignedPreKeyStore;
import org.whispersystems.textsecure.api.push.PushAddress;

public class KeyExchangeInitiator {

  public static void initiate(final Context context, final MasterSecret masterSecret, final Recipient recipient, boolean promptOnExisting) {
    if (promptOnExisting && hasInitiatedSession(context, masterSecret, recipient)) {
      AlertDialog.Builder dialog = new AlertDialog.Builder(context);
      dialog.setTitle(R.string.KeyExchangeInitiator_initiate_despite_existing_request_question);
      dialog.setMessage(R.string.KeyExchangeInitiator_youve_already_sent_a_session_initiation_request_to_this_recipient_are_you_sure);
      dialog.setIcon(Dialogs.resolveIcon(context, R.attr.dialog_alert_icon));
      dialog.setCancelable(true);
      dialog.setPositiveButton(R.string.KeyExchangeInitiator_send, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int which) {
          initiateKeyExchange(context, masterSecret, recipient);
        }
      });
      dialog.setNegativeButton(android.R.string.cancel, null);
      dialog.show();
    } else {
      initiateKeyExchange(context, masterSecret, recipient);
    }
  }

  private static void initiateKeyExchange(Context context, MasterSecret masterSecret, Recipient recipient) {
    SessionStore      sessionStore      = new TextSecureSessionStore(context, masterSecret);
    PreKeyStore       preKeyStore       = new TextSecurePreKeyStore(context, masterSecret);
    SignedPreKeyStore signedPreKeyStore = new TextSecurePreKeyStore(context, masterSecret);
    IdentityKeyStore  identityKeyStore  = new TextSecureIdentityKeyStore(context, masterSecret);

    SessionBuilder    sessionBuilder    = new SessionBuilder(sessionStore, preKeyStore, signedPreKeyStore,
                                                             identityKeyStore, recipient.getRecipientId(),
                                                             PushAddress.DEFAULT_DEVICE_ID);

    KeyExchangeMessage         keyExchangeMessage = sessionBuilder.process();
    String                     serializedMessage  = Base64.encodeBytesWithoutPadding(keyExchangeMessage.serialize());
    OutgoingKeyExchangeMessage textMessage        = new OutgoingKeyExchangeMessage(recipient, serializedMessage);

    MessageSender.send(context, masterSecret, textMessage, -1, false);
  }

  private static boolean hasInitiatedSession(Context context, MasterSecret masterSecret,
                                             Recipient recipient)
  {
    SessionStore  sessionStore  = new TextSecureSessionStore(context, masterSecret);
    SessionRecord sessionRecord = sessionStore.loadSession(recipient.getRecipientId(), PushAddress.DEFAULT_DEVICE_ID);

    return sessionRecord.getSessionState().hasPendingKeyExchange();
  }
}
