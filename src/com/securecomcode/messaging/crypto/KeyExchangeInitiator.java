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
import com.securecomcode.messaging.crypto.protocol.KeyExchangeMessageV2;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.sms.MessageSender;
import com.securecomcode.messaging.sms.OutgoingKeyExchangeMessage;
import com.securecomcode.messaging.util.Dialogs;
import org.whispersystems.textsecure.crypto.IdentityKeyPair;
import org.whispersystems.textsecure.crypto.MasterSecret;
import org.whispersystems.textsecure.crypto.ecc.Curve;
import org.whispersystems.textsecure.crypto.ecc.ECKeyPair;
import org.whispersystems.textsecure.storage.RecipientDevice;
import org.whispersystems.textsecure.storage.SessionRecordV2;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

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
    int             sequence     = getRandomSequence();
    int             flags        = KeyExchangeMessageV2.INITIATE_FLAG;
    ECKeyPair       baseKey      = Curve.generateKeyPair(true);
    ECKeyPair       ephemeralKey = Curve.generateKeyPair(true);
    IdentityKeyPair identityKey  = IdentityKeyUtil.getIdentityKeyPair(context, masterSecret);

    KeyExchangeMessageV2 message = new KeyExchangeMessageV2(sequence, flags,
                                                            baseKey.getPublicKey(),
                                                            ephemeralKey.getPublicKey(),
                                                            identityKey.getPublicKey());

    OutgoingKeyExchangeMessage textMessage = new OutgoingKeyExchangeMessage(recipient, message.serialize());
    RecipientDevice recipientDevice = new RecipientDevice(recipient.getRecipientId(), RecipientDevice.DEFAULT_DEVICE_ID);

    SessionRecordV2 sessionRecordV2 = new SessionRecordV2(context, masterSecret, recipientDevice);
    sessionRecordV2.getSessionState().setPendingKeyExchange(sequence, baseKey, ephemeralKey, identityKey);
    sessionRecordV2.save();

    MessageSender.send(context, masterSecret, textMessage, -1, false);
  }

  private static boolean hasInitiatedSession(Context context, MasterSecret masterSecret,
                                             Recipient recipient)
  {
    RecipientDevice recipientDevice = new RecipientDevice(recipient.getRecipientId(), RecipientDevice.DEFAULT_DEVICE_ID);
    return
        new SessionRecordV2(context, masterSecret, recipientDevice)
            .getSessionState()
            .hasPendingKeyExchange();
  }

  private static int getRandomSequence() {
    try {
      SecureRandom random    = SecureRandom.getInstance("SHA1PRNG");
      int          candidate = Math.abs(random.nextInt());

      return candidate % 65535;
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
