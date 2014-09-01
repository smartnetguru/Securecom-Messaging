/**
 * Copyright (C) 2014 Open Whisper Systems
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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gcm.GCMRegistrar;

import com.securecomcode.messaging.R;
import com.securecomcode.messaging.crypto.IdentityKeyUtil;
import com.securecomcode.messaging.gcm.GcmIntentService;
import com.securecomcode.messaging.gcm.GcmRegistrationTimeoutException;
import com.securecomcode.messaging.push.PushServiceSocketFactory;
import com.securecomcode.messaging.util.DirectoryHelper;
import com.securecomcode.messaging.util.TextSecurePreferences;
import org.whispersystems.textsecure.crypto.IdentityKey;
import org.whispersystems.textsecure.crypto.MasterSecret;
import org.whispersystems.textsecure.crypto.PreKeyUtil;
import org.whispersystems.textsecure.crypto.ecc.Curve;
import org.whispersystems.textsecure.push.ExpectationFailedException;
import org.whispersystems.textsecure.push.PushServiceSocket;
import org.whispersystems.textsecure.storage.PreKeyRecord;
import org.whispersystems.textsecure.util.Util;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The RegisterationService handles the process of PushService registration and verification.
 * If it receives an intent with a REGISTER_NUMBER_ACTION, it does the following through
 * an executor:
 *
 * 1) Generate secrets.
 * 2) Register the specified number and those secrets with the server.
 * 3) Wait for a challenge SMS.
 * 4) Verify the challenge with the server.
 * 5) Start the GCM registration process.
 * 6) Retrieve the current directory.
 *
 * The RegistrationService broadcasts its state throughout this process, and also makes its
 * state available through service binding.  This enables a View to display progress.
 *
 * @author Moxie Marlinspike
 *
 */

public class RegistrationService extends Service {

  public static final String REGISTER_NUMBER_ACTION = "com.securecomcode.messaging.RegistrationService.REGISTER_NUMBER";
  public static final String VOICE_REQUESTED_ACTION = "com.securecomcode.messaging.RegistrationService.VOICE_REQUESTED";
  public static final String VOICE_REGISTER_ACTION  = "com.securecomcode.messaging.RegistrationService.VOICE_REGISTER";

  public static final String NOTIFICATION_TITLE     = "com.securecomcode.messaging.NOTIFICATION_TITLE";
  public static final String NOTIFICATION_TEXT      = "com.securecomcode.messaging.NOTIFICATION_TEXT";
  public static final String CHALLENGE_EVENT        = "com.securecomcode.messaging.CHALLENGE_EVENT";
  public static final String REGISTRATION_EVENT     = "com.securecomcode.messaging.REGISTRATION_EVENT";
  public static final String GCM_REGISTRATION_EVENT = "com.securecomcode.messaging.GCM_REGISTRATION_EVENT";

  public static final String CHALLENGE_EXTRA        = "CAAChallenge";
  public static final String GCM_REGISTRATION_ID    = "GCMRegistrationId";

  private static final long REGISTRATION_TIMEOUT_MILLIS = 120000;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Binder          binder   = new RegistrationServiceBinder();

  private volatile RegistrationState registrationState = new RegistrationState(RegistrationState.STATE_IDLE);

  private volatile Handler                 registrationStateHandler;
  private volatile ChallengeReceiver       challengeReceiver;
  private volatile GcmRegistrationReceiver gcmRegistrationReceiver;
  private          String                  challenge;
  private          String                  gcmRegistrationId;
  private          long                    verificationStartTime;
  private          boolean                 generatingPreKeys;

  @Override
  public int onStartCommand(final Intent intent, int flags, int startId) {
    if (intent != null) {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          if      (REGISTER_NUMBER_ACTION.equals(intent.getAction())) handleSmsRegistrationIntent(intent);
          else if (VOICE_REQUESTED_ACTION.equals(intent.getAction())) handleVoiceRequestedIntent(intent);
          else if (VOICE_REGISTER_ACTION.equals(intent.getAction()))  handleVoiceRegistrationIntent(intent);
        }
      });
    }

    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    executor.shutdown();
    shutdown();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  public void shutdown() {
    shutdownChallengeListener();
    markAsVerifying(false);
    registrationState = new RegistrationState(RegistrationState.STATE_IDLE);
  }

  public synchronized int getSecondsRemaining() {
    long millisPassed;

    if (verificationStartTime == 0) millisPassed = 0;
    else                            millisPassed = System.currentTimeMillis() - verificationStartTime;

    return Math.max((int)(REGISTRATION_TIMEOUT_MILLIS - millisPassed) / 1000, 0);
  }

  public RegistrationState getRegistrationState() {
    return registrationState;
  }

  private void initializeChallengeListener() {
    this.challenge      = null;
    challengeReceiver = new ChallengeReceiver();
    IntentFilter filter = new IntentFilter(CHALLENGE_EVENT);
    registerReceiver(challengeReceiver, filter);
  }

  private void initializeGcmRegistrationListener() {
    this.gcmRegistrationId = null;
    gcmRegistrationReceiver = new GcmRegistrationReceiver();
    IntentFilter filter = new IntentFilter(GCM_REGISTRATION_EVENT);
    registerReceiver(gcmRegistrationReceiver, filter);
  }

  private synchronized void shutdownChallengeListener() {
    if (challengeReceiver != null) {
      unregisterReceiver(challengeReceiver);
      challengeReceiver = null;
    }
  }

  private synchronized void shutdownGcmRegistrationListener() {
    if (gcmRegistrationReceiver != null) {
      unregisterReceiver(gcmRegistrationReceiver);
      gcmRegistrationReceiver = null;
    }
  }

  private void handleVoiceRequestedIntent(Intent intent) {
      if(TextSecurePreferences.getRegistrationOptionSelected(getApplicationContext()).equalsIgnoreCase("Phone")){
          setState(new RegistrationState(RegistrationState.STATE_VOICE_REQUESTED,
                  intent.getStringExtra("e164number"),
                  intent.getStringExtra("password")));
      }else if(TextSecurePreferences.getRegistrationOptionSelected(getApplicationContext()).equalsIgnoreCase("Email")){
          setState(new RegistrationState(RegistrationState.STATE_VOICE_REQUESTED,
                  intent.getStringExtra("email_address"),
                  intent.getStringExtra("password")));
      }

  }

  private void handleVoiceRegistrationIntent(Intent intent) {
    markAsVerifying(true);

    String       number       = intent.getStringExtra("e164number");
    String       password     = intent.getStringExtra("password"  );
    String       signalingKey = intent.getStringExtra("signaling_key");
    MasterSecret masterSecret = intent.getParcelableExtra("master_secret");

    try {
      initializeGcmRegistrationListener();

      PushServiceSocket socket = PushServiceSocketFactory.create(this, number, password);

      handleCommonRegistration(masterSecret, socket, number);

      markAsVerified(number, password, signalingKey);

      setState(new RegistrationState(RegistrationState.STATE_COMPLETE, number));
      broadcastComplete(true);
    } catch (UnsupportedOperationException uoe) {
      Log.w("RegistrationService", uoe);
      setState(new RegistrationState(RegistrationState.STATE_GCM_UNSUPPORTED, number));
      broadcastComplete(false);
    } catch (IOException e) {
      Log.w("RegistrationService", e);
      setState(new RegistrationState(RegistrationState.STATE_NETWORK_ERROR, number));
      broadcastComplete(false);
    } catch (GcmRegistrationTimeoutException e) {
      Log.w("RegistrationService", e);
      setState(new RegistrationState(RegistrationState.STATE_GCM_TIMEOUT));
      broadcastComplete(false);
    } finally {
      shutdownGcmRegistrationListener();
    }
  }

  private void handleSmsRegistrationIntent(Intent intent) {
    markAsVerifying(true);
    String number = "";
      if(TextSecurePreferences.getRegistrationOptionSelected(getApplicationContext()).equalsIgnoreCase("Phone")){
          number       = intent.getStringExtra("e164number");
      }else if(TextSecurePreferences.getRegistrationOptionSelected(getApplicationContext()).equalsIgnoreCase("Email")){
          number       = intent.getStringExtra("email_address");
      }

    MasterSecret masterSecret = intent.getParcelableExtra("master_secret");
    int          registrationId = TextSecurePreferences.getLocalRegistrationId(this);

    if (registrationId == 0) {
      registrationId = Util.generateRegistrationId();
      TextSecurePreferences.setLocalRegistrationId(this, registrationId);
    }

    try {
      String password     = Util.getSecret(18);
      String signalingKey = Util.getSecret(52);

      initializeChallengeListener();
      initializeGcmRegistrationListener();

      setState(new RegistrationState(RegistrationState.STATE_CONNECTING, number));
      PushServiceSocket socket = PushServiceSocketFactory.create(this, number, password);
      socket.createAccount(false);

      setState(new RegistrationState(RegistrationState.STATE_SMS_VERIFICATION, number));
      String challenge = waitForChallenge();

      socket.verifyAccount(challenge, signalingKey, true, registrationId);

      handleCommonRegistration(masterSecret, socket, number);
      markAsVerified(number, password, signalingKey);

      setState(new RegistrationState(RegistrationState.STATE_COMPLETE, number));
      broadcastComplete(true);
    } catch (ExpectationFailedException efe) {
      Log.w("RegistrationService", efe);
      setState(new RegistrationState(RegistrationState.STATE_MULTI_REGISTERED, number));
      broadcastComplete(false);
    } catch (UnsupportedOperationException uoe) {
      Log.w("RegistrationService", uoe);
      setState(new RegistrationState(RegistrationState.STATE_GCM_UNSUPPORTED, number));
      broadcastComplete(false);
    } catch (AccountVerificationTimeoutException avte) {
      Log.w("RegistrationService", avte);
      setState(new RegistrationState(RegistrationState.STATE_TIMEOUT, number));
      broadcastComplete(false);
    } catch (IOException e) {
      Log.w("RegistrationService", e);
      setState(new RegistrationState(RegistrationState.STATE_NETWORK_ERROR, number));
      broadcastComplete(false);
    } catch (GcmRegistrationTimeoutException e) {
      Log.w("RegistrationService", e);
      setState(new RegistrationState(RegistrationState.STATE_GCM_TIMEOUT));
      broadcastComplete(false);
    } finally {
      shutdownChallengeListener();
      shutdownGcmRegistrationListener();
    }
  }

  private void handleCommonRegistration(MasterSecret masterSecret, PushServiceSocket socket, String number)
      throws GcmRegistrationTimeoutException, IOException
  {
    setState(new RegistrationState(RegistrationState.STATE_GENERATING_KEYS, number));
    IdentityKey        identityKey = IdentityKeyUtil.getIdentityKey(this);
    List<PreKeyRecord> records     = PreKeyUtil.generatePreKeys(this, masterSecret);
    PreKeyRecord       lastResort  = PreKeyUtil.generateLastResortKey(this, masterSecret);
    socket.registerPreKeys(identityKey, lastResort, records);

    setState(new RegistrationState(RegistrationState.STATE_GCM_REGISTERING, number));
    GCMRegistrar.register(this, GcmIntentService.GCM_SENDER_ID);
    String gcmRegistrationId = waitForGcmRegistrationId();
    socket.registerGcmId(gcmRegistrationId);

    DirectoryHelper.refreshDirectory(this, socket, number);

    DirectoryRefreshListener.schedule(this);
  }

  private synchronized String waitForChallenge() throws AccountVerificationTimeoutException {
    this.verificationStartTime = System.currentTimeMillis();

    if (this.challenge == null) {
      try {
        wait(REGISTRATION_TIMEOUT_MILLIS);
      } catch (InterruptedException e) {
        throw new IllegalArgumentException(e);
      }
    }

    if (this.challenge == null)
      throw new AccountVerificationTimeoutException();

    return this.challenge;
  }

  private synchronized String waitForGcmRegistrationId() throws GcmRegistrationTimeoutException {
    if (this.gcmRegistrationId == null) {
      try {
        wait(10 * 60 * 1000);
      } catch (InterruptedException e) {
        throw new IllegalArgumentException(e);
      }
    }

    if (this.gcmRegistrationId == null)
      throw new GcmRegistrationTimeoutException();

    return this.gcmRegistrationId;
  }

  public synchronized void challengeReceived(String challenge) {
    this.challenge = challenge;
    notifyAll();
  }

  private synchronized void gcmRegistrationReceived(String gcmRegistrationId) {
    this.gcmRegistrationId = gcmRegistrationId;
    notifyAll();
  }

  private void markAsVerifying(boolean verifying) {
    TextSecurePreferences.setVerifying(this, verifying);

    if (verifying) {
      TextSecurePreferences.setPushRegistered(this, false);
    }
  }

  private void markAsVerified(String number, String password, String signalingKey) {
    TextSecurePreferences.setVerifying(this, false);
    TextSecurePreferences.setPushRegistered(this, true);
    TextSecurePreferences.setLocalNumber(this, number);
    TextSecurePreferences.setPushServerPassword(this, password);
    TextSecurePreferences.setSignalingKey(this, signalingKey);
  }

  private void setState(RegistrationState state) {
    this.registrationState = state;

    if (registrationStateHandler != null) {
      registrationStateHandler.obtainMessage(state.state, state).sendToTarget();
    }
  }

  private void broadcastComplete(boolean success) {
    Intent intent = new Intent();
    intent.setAction(REGISTRATION_EVENT);

    if (success) {
      intent.putExtra(NOTIFICATION_TITLE, getString(R.string.RegistrationService_registration_complete));
      intent.putExtra(NOTIFICATION_TEXT, getString(R.string.RegistrationService_securecom_messaging_registration_has_successfully_completed));
    } else {
      intent.putExtra(NOTIFICATION_TITLE, getString(R.string.RegistrationService_registration_error));
      intent.putExtra(NOTIFICATION_TEXT, getString(R.string.RegistrationService_securecom_messaging_registration_has_encountered_a_problem));
    }

    this.sendOrderedBroadcast(intent, null);
  }

  public void setRegistrationStateHandler(Handler registrationStateHandler) {
    this.registrationStateHandler = registrationStateHandler;
  }

  public class RegistrationServiceBinder extends Binder {
    public RegistrationService getService() {
      return RegistrationService.this;
    }
  }

  private class GcmRegistrationReceiver extends BroadcastReceiver {
    public void onReceive(Context context, Intent intent) {
      Log.w("RegistrationService", "Got gcm registration broadcast...");
      gcmRegistrationReceived(intent.getStringExtra(GCM_REGISTRATION_ID));
    }
  }

  private class ChallengeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      Log.w("RegistrationService", "Got a challenge broadcast...");
      challengeReceived(intent.getStringExtra(CHALLENGE_EXTRA));
    }
  }

  public static class RegistrationState {

    public static final int STATE_IDLE                 =  0;
    public static final int STATE_CONNECTING           =  1;
    public static final int STATE_VERIFYING            =  2;
    public static final int STATE_TIMER                =  3;
    public static final int STATE_COMPLETE             =  4;
    public static final int STATE_TIMEOUT              =  5;
    public static final int STATE_NETWORK_ERROR        =  6;

    public static final int STATE_GCM_UNSUPPORTED      =  8;
    public static final int STATE_GCM_REGISTERING      =  9;
    public static final int STATE_GCM_TIMEOUT          = 10;

    public static final int STATE_VOICE_REQUESTED      = 12;
    public static final int STATE_GENERATING_KEYS      = 13;

    public static final int STATE_MULTI_REGISTERED     = 14;
    public static final int STATE_SMS_VERIFICATION     = 15;


    public final int    state;
    public final String number;
    public final String password;

    public RegistrationState(int state) {
      this(state, null);
    }

    public RegistrationState(int state, String number) {
      this(state, number, null);
    }

    public RegistrationState(int state, String number, String password) {
      this.state        = state;
      this.number       = number;
      this.password     = password;
    }
  }
}
