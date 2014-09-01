package com.securecomcode.messaging.sms;

import com.securecomcode.messaging.database.model.SmsMessageRecord;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.Recipients;

public class OutgoingTextMessage {

  private final Recipients recipients;
  private final String     message;

  public OutgoingTextMessage(Recipient recipient, String message) {
    this(new Recipients(recipient), message);
  }

  public OutgoingTextMessage(Recipients recipients, String message) {
    this.recipients = recipients;
    this.message    = message;
  }

  protected OutgoingTextMessage(OutgoingTextMessage base, String body) {
    this.recipients = base.getRecipients();
    this.message    = body;
  }

  public String getMessageBody() {
    return message;
  }

  public Recipients getRecipients() {
    return recipients;
  }

  public boolean isKeyExchange() {
    return false;
  }

  public boolean isSecureMessage() {
    return false;
  }

  public boolean isEndSession() {
    return false;
  }

  public boolean isPreKeyBundle() {
    return false;
  }

  public static OutgoingTextMessage from(SmsMessageRecord record) {
    if (record.isSecure()) {
      return new OutgoingEncryptedMessage(record.getIndividualRecipient(), record.getBody().getBody());
    } else if (record.isKeyExchange()) {
      return new OutgoingKeyExchangeMessage(record.getIndividualRecipient(), record.getBody().getBody());
    } else if (record.isEndSession()) {
      return new OutgoingEndSessionMessage(new OutgoingTextMessage(record.getIndividualRecipient(), record.getBody().getBody()));
    } else {
      return new OutgoingTextMessage(record.getIndividualRecipient(), record.getBody().getBody());
    }
  }

  public OutgoingTextMessage withBody(String body) {
    return new OutgoingTextMessage(this, body);
  }
}
