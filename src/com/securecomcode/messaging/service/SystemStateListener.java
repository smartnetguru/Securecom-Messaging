/**
 * Copyright (C) 2013 Open Whisper Systems
 * Copyright (C) 2014 Securecom
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
package com.securecomcode.messaging.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;

public class SystemStateListener {

  private final TelephonyListener    telephonyListener    = new TelephonyListener();
  private final ConnectivityListener connectivityListener = new ConnectivityListener();
  private final Context              context;
  private final TelephonyManager     telephonyManager;
  private final ConnectivityManager  connectivityManager;

  public SystemStateListener(Context context) {
    this.context             = context.getApplicationContext();
    this.telephonyManager    = (TelephonyManager)    context.getSystemService(Context.TELEPHONY_SERVICE);
    this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
  }

  public void registerForRadioChange() {
    Log.w("SystemStateListener", "Registering for radio changes...");
    unregisterForConnectivityChange();

    telephonyManager.listen(telephonyListener, PhoneStateListener.LISTEN_SERVICE_STATE);
  }

  public void registerForConnectivityChange() {
    Log.w("SystemStateListener", "Registering for any connectivity changes...");
    unregisterForConnectivityChange();

    context.registerReceiver(connectivityListener, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
  }

  public void unregisterForConnectivityChange() {
  }

  public boolean isConnected() {
    return
        connectivityManager.getActiveNetworkInfo() != null &&
            connectivityManager.getActiveNetworkInfo().isConnected();
  }

  private void sendSmsOutbox(Context context) {
    Intent smsSenderIntent = new Intent(SendReceiveService.SEND_SMS_ACTION, null, context,
                                        SendReceiveService.class);
    context.startService(smsSenderIntent);
  }

  private void sendMmsOutbox(Context context) {
    Intent mmsSenderIntent = new Intent(SendReceiveService.SEND_MMS_ACTION, null, context,
                                        SendReceiveService.class);
    context.startService(mmsSenderIntent);
  }

  private class TelephonyListener extends PhoneStateListener {
    @Override
    public void onServiceStateChanged(ServiceState state) {
      if (state.getState() == ServiceState.STATE_IN_SERVICE) {
        Log.w("SystemStateListener", "In service, sending sms/mms outboxes...");
        sendSmsOutbox(context);
        sendMmsOutbox(context);
      }
    }
  }

  private class ConnectivityListener extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent != null && ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
        if (connectivityManager.getActiveNetworkInfo() != null &&
            connectivityManager.getActiveNetworkInfo().isConnected())
        {
          Log.w("SystemStateListener", "Got connectivity action: " + intent.toString());
          sendSmsOutbox(context);
          sendMmsOutbox(context);
        }
      }
    }
  }
}
