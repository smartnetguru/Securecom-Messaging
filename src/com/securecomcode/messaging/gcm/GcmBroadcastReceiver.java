package com.securecomcode.messaging.gcm;

import android.content.Context;

public class GcmBroadcastReceiver extends com.google.android.gcm.GCMBroadcastReceiver {

  @Override
  protected String getGCMIntentServiceClassName(Context context) {
    return "com.securecomcode.messaging.gcm.GcmIntentService";
  }

}