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
package com.securecomcode.messaging.util;

import org.whispersystems.textsecure.internal.push.PushServiceSocket;
import com.securecomcode.messaging.push.TextSecurePushTrustStore;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;
import android.os.AsyncTask;

import com.securecomcode.messaging.R;
import com.securecomcode.messaging.database.NotInDirectoryException;
import com.securecomcode.messaging.database.TextSecureDirectory;
import com.securecomcode.messaging.push.TextSecureCommunicationFactory;
import com.securecomcode.messaging.recipients.Recipients;
import org.whispersystems.textsecure.api.TextSecureAccountManager;
import org.whispersystems.textsecure.api.push.ContactTokenDetails;
import org.whispersystems.textsecure.api.util.InvalidNumberException;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class DirectoryHelper {
  private static final String TAG = DirectoryHelper.class.getSimpleName();

  public static void refreshDirectoryWithProgressDialog(final Context context) {
    refreshDirectoryWithProgressDialog(context, null);
  }

  public static void refreshDirectoryWithProgressDialog(final Context context, final DirectoryUpdateFinishedListener listener) {
    if (!TextSecurePreferences.isPushRegistered(context)) {
      Toast.makeText(context.getApplicationContext(),
                     context.getString(R.string.SingleContactSelectionActivity_you_are_not_registered_with_the_push_service),
                     Toast.LENGTH_LONG).show();
      return;
    }

    new ProgressDialogAsyncTask<Void,Void,Void>(context,
                                                R.string.SingleContactSelectionActivity_updating_directory,
                                                R.string.SingleContactSelectionActivity_updating_push_directory)
    {
      @Override
      protected Void doInBackground(Void... voids) {
        DirectoryHelper.refreshDirectory(context.getApplicationContext());
        return null;
      }

      @Override
      protected void onPostExecute(Void aVoid) {
        super.onPostExecute(aVoid);
        if (listener != null) listener.onUpdateFinished();
      }
    }.execute();

  }

  public static void refreshDirectory(final Context context) {
    refreshDirectory(context, TextSecureCommunicationFactory.createManager(context));
  }

  public static void refreshDirectory(final Context context, final TextSecureAccountManager accountManager) {
    refreshDirectory(context, accountManager, TextSecurePreferences.getLocalNumber(context));
  }

  public static void refreshDirectory(final Context context, final TextSecureAccountManager accountManager, final String localNumber) {
    Set<String> eligibleContactNumbers = null;
    TextSecureDirectory       directory              = TextSecureDirectory.getInstance(context);
        if (TextSecurePreferences.getRegistrationOptionSelected(context).equalsIgnoreCase("Phone")) {
            eligibleContactNumbers = directory.getPushEligibleContactNumbers(localNumber, null);
        } else if (TextSecurePreferences.getRegistrationOptionSelected(context).equalsIgnoreCase("Email")) {
            eligibleContactNumbers = directory.getPushEligibleContactNumbers(localNumber, TextSecurePreferences.getCountryCodeSelected(context));
        }
    Map<String, String>       tokenMap               = DirectoryUtil.getDirectoryServerTokenMap(eligibleContactNumbers);
    List<ContactTokenDetails> activeTokens           = accountManager.getContacts(tokenMap.keySet());

    if (activeTokens != null) {
      for (ContactTokenDetails activeToken : activeTokens) {
        eligibleContactNumbers.remove(tokenMap.get(activeToken.getToken()));
        activeToken.setNumber(tokenMap.get(activeToken.getToken()));
      }

      directory.setNumbers(activeTokens, eligibleContactNumbers);
    }
  }

    public static boolean isPushDestination(Context context, Recipients recipients) {
        String number = "";
        String e164number = "";
        boolean result = false;

        try {
            if (recipients == null) {
                return false;
            }

      if (!TextSecurePreferences.isPushRegistered(context)) {
        return false;
      }

      if (!recipients.isSingleRecipient()) {
        return false;
      }

      if (recipients.isGroupRecipient()) {
        return true;
      }

            number = recipients.getPrimaryRecipient().getNumber();

      if (number == null) {
        return false;
      }


            if (!org.whispersystems.textsecure.internal.util.Util.isValidEmail(number)) {
                e164number = Util.canonicalizeNumber(context, number);
            } else {
                e164number = number;
            }

            result = TextSecureDirectory.getInstance(context).isActiveNumber(e164number);

            if (!result) {
                TextSecureDirectory directory = TextSecureDirectory.getInstance(context);
                try {
                    final PushServiceSocket socket = new PushServiceSocket(com.securecomcode.messaging.Release.PUSH_URL,
                            new TextSecurePushTrustStore(context),
                            TextSecurePreferences.getLocalNumber(context),
                            TextSecurePreferences.getPushServerPassword(context));

                    final String contactToken = DirectoryUtil.getDirectoryServerToken(e164number);
                    ContactTokenDetails registeredUser = null;
                    try {
                        registeredUser = new AsyncTask<Void, Void, ContactTokenDetails>() {
                            @Override
                            protected ContactTokenDetails doInBackground(Void... params) {
                                try {
                                    final String encodedToken = URLEncoder.encode(contactToken, "UTF-8");
                                    return socket.getContactTokenDetails(encodedToken);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                                return null;
                            }
                        }.execute().get();
                    } catch (InterruptedException exception) {
                        exception.printStackTrace();
                    } catch (ExecutionException exception) {
                        exception.printStackTrace();
                    }

                    if (registeredUser != null) {
                        registeredUser.setNumber(e164number);
                        directory.setNumber(registeredUser, true);
                        result = true;
                    }
                } catch (Exception e1) {
                    Log.w("ListClickListener", e1);
                }
            }

            return result;
        } catch (InvalidNumberException e) {
            Log.w(TAG, e);
            return false;
        } catch (NotInDirectoryException e) {
            return false;
        }
    }

  public static interface DirectoryUpdateFinishedListener {
    public void onUpdateFinished();
  }
}
