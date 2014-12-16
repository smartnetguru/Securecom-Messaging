package com.securecomcode.messaging.crypto;

import android.content.Context;

import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.RecipientFactory;
import com.securecomcode.messaging.recipients.RecipientFormattingException;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.sms.IncomingEncryptedMessage;
import com.securecomcode.messaging.sms.IncomingKeyExchangeMessage;
import com.securecomcode.messaging.sms.IncomingPreKeyBundleMessage;
import com.securecomcode.messaging.sms.IncomingTextMessage;
import com.securecomcode.messaging.sms.OutgoingKeyExchangeMessage;
import com.securecomcode.messaging.sms.OutgoingPrekeyBundleMessage;
import com.securecomcode.messaging.sms.OutgoingTextMessage;
import com.securecomcode.messaging.sms.SmsTransportDetails;
import org.whispersystems.libaxolotl.DuplicateMessageException;
import org.whispersystems.libaxolotl.InvalidKeyException;
import org.whispersystems.libaxolotl.InvalidKeyIdException;
import org.whispersystems.libaxolotl.InvalidMessageException;
import org.whispersystems.libaxolotl.InvalidVersionException;
import org.whispersystems.libaxolotl.LegacyMessageException;
import org.whispersystems.libaxolotl.NoSessionException;
import org.whispersystems.libaxolotl.SessionBuilder;
import org.whispersystems.libaxolotl.SessionCipher;
import org.whispersystems.libaxolotl.StaleKeyExchangeException;
import org.whispersystems.libaxolotl.UntrustedIdentityException;
import org.whispersystems.libaxolotl.protocol.CiphertextMessage;
import org.whispersystems.libaxolotl.protocol.KeyExchangeMessage;
import org.whispersystems.libaxolotl.protocol.PreKeyWhisperMessage;
import org.whispersystems.libaxolotl.protocol.WhisperMessage;
import org.whispersystems.libaxolotl.state.AxolotlStore;
import org.whispersystems.textsecure.api.push.PushAddress;

import java.io.IOException;

public class SmsCipher {

  private final SmsTransportDetails transportDetails = new SmsTransportDetails();

  private final AxolotlStore axolotlStore;

  public SmsCipher(AxolotlStore axolotlStore) {
    this.axolotlStore = axolotlStore;
  }

  public IncomingTextMessage decrypt(Context context, IncomingTextMessage message)
      throws LegacyMessageException, InvalidMessageException,
             DuplicateMessageException, NoSessionException
  {
    try {
      Recipients     recipients     = RecipientFactory.getRecipientsFromString(context, message.getSender(), false);
      long           recipientId    = recipients.getPrimaryRecipient().getRecipientId();
      byte[]         decoded        = transportDetails.getDecodedMessage(message.getMessageBody().getBytes());
      WhisperMessage whisperMessage = new WhisperMessage(decoded);
      SessionCipher  sessionCipher  = new SessionCipher(axolotlStore, recipientId, 1);
      byte[]         padded         = sessionCipher.decrypt(whisperMessage);
      byte[]         plaintext      = transportDetails.getStrippedPaddingMessageBody(padded);

      if (message.isEndSession() && "TERMINATE".equals(new String(plaintext))) {
        axolotlStore.deleteSession(recipientId, 1);
      }

      return message.withMessageBody(new String(plaintext));
    } catch (RecipientFormattingException | IOException e) {
      throw new InvalidMessageException(e);
    }
  }

  public IncomingEncryptedMessage decrypt(Context context, IncomingPreKeyBundleMessage message)
      throws InvalidVersionException, InvalidMessageException, DuplicateMessageException,
             UntrustedIdentityException, LegacyMessageException
  {
    try {
      Recipients           recipients    = RecipientFactory.getRecipientsFromString(context, message.getSender(), false);
      byte[]               decoded       = transportDetails.getDecodedMessage(message.getMessageBody().getBytes());
      PreKeyWhisperMessage preKeyMessage = new PreKeyWhisperMessage(decoded);
      SessionCipher        sessionCipher = new SessionCipher(axolotlStore, recipients.getPrimaryRecipient().getRecipientId(), 1);
      byte[]               padded        = sessionCipher.decrypt(preKeyMessage);
      byte[]               plaintext     = transportDetails.getStrippedPaddingMessageBody(padded);

      return new IncomingEncryptedMessage(message, new String(plaintext));
    } catch (RecipientFormattingException | IOException | InvalidKeyException | InvalidKeyIdException e) {
      throw new InvalidMessageException(e);
    }
  }

  public OutgoingTextMessage encrypt(OutgoingTextMessage message) throws NoSessionException {
    byte[] paddedBody  = transportDetails.getPaddedMessageBody(message.getMessageBody().getBytes());
    long   recipientId = message.getRecipients().getPrimaryRecipient().getRecipientId();

    if (!axolotlStore.containsSession(recipientId, PushAddress.DEFAULT_DEVICE_ID)) {
      throw new NoSessionException("No session for: " + recipientId);
    }

    SessionCipher     cipher            = new SessionCipher(axolotlStore, recipientId, PushAddress.DEFAULT_DEVICE_ID);
    CiphertextMessage ciphertextMessage = cipher.encrypt(paddedBody);
    String            encodedCiphertext = new String(transportDetails.getEncodedMessage(ciphertextMessage.serialize()));

    if (ciphertextMessage.getType() == CiphertextMessage.PREKEY_TYPE) {
      return new OutgoingPrekeyBundleMessage(message, encodedCiphertext);
    } else {
      return message.withBody(encodedCiphertext);
    }
  }

  public OutgoingKeyExchangeMessage process(Context context, IncomingKeyExchangeMessage message)
      throws UntrustedIdentityException, StaleKeyExchangeException,
             InvalidVersionException, LegacyMessageException, InvalidMessageException
  {
    try {
      Recipient          recipient       = RecipientFactory.getRecipientsFromString(context, message.getSender(), false).getPrimaryRecipient();
      KeyExchangeMessage exchangeMessage = new KeyExchangeMessage(transportDetails.getDecodedMessage(message.getMessageBody().getBytes()));
      SessionBuilder     sessionBuilder  = new SessionBuilder(axolotlStore, recipient.getRecipientId(), 1);

      KeyExchangeMessage response        = sessionBuilder.process(exchangeMessage);

      if (response != null) {
        byte[] serializedResponse = transportDetails.getEncodedMessage(response.serialize());
        return new OutgoingKeyExchangeMessage(recipient, new String(serializedResponse));
      } else {
        return null;
      }
    } catch (RecipientFormattingException | IOException | InvalidKeyException e) {
      throw new InvalidMessageException(e);
    }
  }

}
