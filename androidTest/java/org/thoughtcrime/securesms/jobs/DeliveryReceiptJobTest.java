package com.securecomcode.messaging.jobs;

import android.test.AndroidTestCase;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import com.securecomcode.messaging.crypto.MasterSecret;
import org.whispersystems.textsecure.api.TextSecureMessageSender;
import org.whispersystems.textsecure.api.push.PushAddress;
import org.whispersystems.textsecure.api.push.exceptions.NotFoundException;
import org.whispersystems.textsecure.api.push.exceptions.PushNetworkException;

import java.io.IOException;

import dagger.Module;
import dagger.ObjectGraph;
import dagger.Provides;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static com.securecomcode.messaging.dependencies.TextSecureCommunicationModule.TextSecureMessageSenderFactory;

public class DeliveryReceiptJobTest extends AndroidTestCase {

  public void testDelivery() throws IOException {
    TextSecureMessageSender textSecureMessageSender = mock(TextSecureMessageSender.class);
    long                    timestamp               = System.currentTimeMillis();

    DeliveryReceiptJob deliveryReceiptJob = new DeliveryReceiptJob(getContext(),
                                                                   "+14152222222",
                                                                   timestamp, "foo");

    ObjectGraph objectGraph = ObjectGraph.create(new TestModule(textSecureMessageSender));
    objectGraph.inject(deliveryReceiptJob);

    deliveryReceiptJob.onRun();

    ArgumentCaptor<PushAddress> captor = ArgumentCaptor.forClass(PushAddress.class);
    verify(textSecureMessageSender).sendDeliveryReceipt(captor.capture(), eq(timestamp));

    assertTrue(captor.getValue().getRelay().equals("foo"));
    assertTrue(captor.getValue().getNumber().equals("+14152222222"));
  }

  public void testNetworkError() throws IOException {
    TextSecureMessageSender textSecureMessageSender = mock(TextSecureMessageSender.class);
    long                    timestamp               = System.currentTimeMillis();

    Mockito.doThrow(new PushNetworkException("network error"))
           .when(textSecureMessageSender)
           .sendDeliveryReceipt(any(PushAddress.class), eq(timestamp));


    DeliveryReceiptJob deliveryReceiptJob = new DeliveryReceiptJob(getContext(),
                                                                   "+14152222222",
                                                                   timestamp, "foo");

    ObjectGraph objectGraph = ObjectGraph.create(new TestModule(textSecureMessageSender));
    objectGraph.inject(deliveryReceiptJob);

    try {
      deliveryReceiptJob.onRun();
      throw new AssertionError();
    } catch (IOException e) {
      assertTrue(deliveryReceiptJob.onShouldRetry(e));
    }

    Mockito.doThrow(new NotFoundException("not found"))
           .when(textSecureMessageSender)
           .sendDeliveryReceipt(any(PushAddress.class), eq(timestamp));

    try {
      deliveryReceiptJob.onRun();
      throw new AssertionError();
    } catch (IOException e) {
      assertFalse(deliveryReceiptJob.onShouldRetry(e));
    }
  }

  @Module(injects = DeliveryReceiptJob.class)
  public static class TestModule {

    private final TextSecureMessageSender textSecureMessageSender;

    public TestModule(TextSecureMessageSender textSecureMessageSender) {
      this.textSecureMessageSender = textSecureMessageSender;
    }

    @Provides TextSecureMessageSenderFactory provideTextSecureMessageSenderFactory() {
      return new TextSecureMessageSenderFactory() {
        @Override
        public TextSecureMessageSender create(MasterSecret masterSecret) {
          return textSecureMessageSender;
        }
      };
    }
  }

}
