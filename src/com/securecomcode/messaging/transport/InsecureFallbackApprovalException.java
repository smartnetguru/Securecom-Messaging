package com.securecomcode.messaging.transport;

public class InsecureFallbackApprovalException extends Exception {
  public InsecureFallbackApprovalException(String detailMessage) {
    super(detailMessage);
  }
  public InsecureFallbackApprovalException(Exception e) {
    super(e);
  }
}
