/**
 * Copyright (C) 2011 Whisper Systems
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
package com.securecomcode.messaging;


import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.securecomcode.messaging.contacts.ContactAccessor;
import com.securecomcode.messaging.contacts.ContactAccessor.ContactData;
import com.securecomcode.messaging.contacts.ContactSelectionListAdapter;
import com.securecomcode.messaging.contacts.ContactSelectionListAdapter.ViewHolder;
import com.securecomcode.messaging.contacts.ContactSelectionListAdapter.DataHolder;
import com.securecomcode.messaging.contacts.ContactsDatabase;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.ThreadDatabase;
import com.securecomcode.messaging.util.TextSecurePreferences;

import com.securecomcode.messaging.database.TextSecureDirectory;
import org.whispersystems.textsecure.api.push.ContactTokenDetails;
import org.whispersystems.textsecure.internal.push.PushServiceSocket;
import com.securecomcode.messaging.push.TextSecurePushTrustStore;
import com.securecomcode.messaging.util.DirectoryUtil;
import org.whispersystems.textsecure.api.util.InvalidNumberException;
import org.whispersystems.textsecure.api.util.PhoneNumberFormatter;
import com.securecomcode.messaging.util.Util;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import se.emilsjolander.stickylistheaders.StickyListHeadersListView;

/**
 * Fragment for selecting a one or more contacts from a list.
 *
 * @author Moxie Marlinspike
 *
 */

public class PushContactSelectionListFragment extends    Fragment
        implements LoaderManager.LoaderCallbacks<Cursor>
{
    private static final String TAG = "ContactSelectFragment";

    private TextView emptyText;

    private Map<Long, ContactData>    selectedContacts;
    private OnContactSelectedListener onContactSelectedListener;
    private boolean                   multi = false;
    private StickyListHeadersListView listView;
    private EditText                  filterEditText;
    private String                    cursorFilter;


    @Override
    public void onActivityCreated(Bundle icicle) {
        super.onCreate(icicle);
        initializeResources();
        initializeCursor();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ContactsDatabase.destroyInstance();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.push_contact_selection_list_activity, container, false);
    }

    public List<ContactData> getSelectedContacts() {
        if (selectedContacts == null) return null;

        List<ContactData> selected = new LinkedList<ContactData>();
        selected.addAll(selectedContacts.values());

        return selected;
    }

    public void setMultiSelect(boolean multi) {
        this.multi = multi;
    }

    private void addContact(DataHolder data) {
        final ContactData contactData = new ContactData(data.id, data.name);
        final CharSequence label = ContactsContract.CommonDataKinds.Email.getTypeLabel(getResources(),
                data.numberType, "");

        contactData.numbers.add(new ContactAccessor.NumberData(label.toString(), data.number));
        if (multi) {
            selectedContacts.put(contactData.id, contactData);
        }
        if (onContactSelectedListener != null) {
            onContactSelectedListener.onContactSelected(contactData);
        }
    }

    private void removeContact(DataHolder contactData) {
        selectedContacts.remove(contactData.id);
    }

    private void initializeCursor() {
        ContactSelectionListAdapter adapter = new ContactSelectionListAdapter(getActivity(), null, multi);
        selectedContacts = adapter.getSelectedContacts();
        listView.setAdapter(adapter);
        this.getLoaderManager().initLoader(0, null, this);
    }

    private void initializeResources() {
        emptyText = (TextView) getView().findViewById(android.R.id.empty);
        listView  = (StickyListHeadersListView) getView().findViewById(android.R.id.list);
        listView.setFocusable(true);
        listView.setFastScrollEnabled(true);
        listView.setDrawingListUnderStickyHeader(false);
        listView.setOnItemClickListener(new ListClickListener());
        filterEditText = (EditText) getView().findViewById(R.id.filter);
        filterEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                cursorFilter = charSequence.toString();
                getLoaderManager().restartLoader(0, null, PushContactSelectionListFragment.this);
            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });
        cursorFilter = null;
    }

    public void update() {
        this.getLoaderManager().restartLoader(0, null, this);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        if (getActivity().getIntent().getBooleanExtra(PushContactSelectionActivity.PUSH_ONLY_EXTRA, false)) {
            return ContactAccessor.getInstance().getCursorLoaderForPushContacts(getActivity(), cursorFilter);
        } else {
            return ContactAccessor.getInstance().getCursorLoaderForContacts(getActivity(), cursorFilter);
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        ((CursorAdapter) listView.getAdapter()).changeCursor(data);
        emptyText.setText(R.string.contact_selection_group_activity__no_contacts);
        if (data != null && data.getCount() < 40) listView.setFastScrollAlwaysVisible(false);
        else                                      listView.setFastScrollAlwaysVisible(true);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        ((CursorAdapter) listView.getAdapter()).changeCursor(null);
    }

    private class ListClickListener implements AdapterView.OnItemClickListener {
        @Override
        public void onItemClick(AdapterView<?> l, View v, int position, long id) {

            if(!com.securecomcode.messaging.util.Util.showAlertOnNoData(getActivity())){
                return;
            }

            if(!TextSecurePreferences.isPushRegistered(getActivity())){
                AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity());
                dialog.setTitle(R.string.securecom_messaging_email_not_registered_with_server_exclamation);
                dialog.setIcon(android.R.drawable.ic_dialog_info);
                dialog.setMessage(R.string.securecom_messaging_the_contact_you_selected_is_not_registered_with_securecom_messaging_both_parties_of_securecom_messaging_need_to_have_securecom_messaging_installed);
                dialog.setCancelable(true);
                dialog.setPositiveButton(R.string.securecom_messaging_yes_exclamation, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int arg) {
                        Intent intent = new Intent(getActivity(), RegistrationActivity.class);
                        intent.putExtra("master_secret", getActivity().getIntent().getParcelableExtra("master_secret"));
                        startActivity(intent);
                    }
                });

                dialog.setNegativeButton(R.string.securecom_messaging_no_thanks_exclamation, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int arg) {

                    }
                });
                dialog.show();
                return;
            }

            final DataHolder contactData = (DataHolder) v.getTag(R.id.contact_info_tag);
            final ViewHolder holder      = (ViewHolder) v.getTag(R.id.holder_tag);
            TextSecureDirectory directory = TextSecureDirectory.getInstance(getActivity());

            String tcontactToken = "";

            if(contactData.type == 0){
                try {
                    final PushServiceSocket socket = new PushServiceSocket(Release.PUSH_URL,
                            new TextSecurePushTrustStore(getActivity().getApplicationContext()),
                            TextSecurePreferences.getLocalNumber(getActivity().getApplicationContext()),
                            TextSecurePreferences.getPushServerPassword(getActivity().getApplicationContext()));

                    if(!org.whispersystems.textsecure.internal.util.Util.isValidEmail(contactData.number)){
                        String registerOption = TextSecurePreferences.getRegistrationOptionSelected(getActivity());
                        if (registerOption.equalsIgnoreCase("Phone")) {
                            tcontactToken   = DirectoryUtil.getDirectoryServerToken(PhoneNumberFormatter.formatNumber(contactData.number, TextSecurePreferences.getLocalNumber(getActivity())));
                        }else if (registerOption.equalsIgnoreCase("Email")) {
                            tcontactToken   = DirectoryUtil.getDirectoryServerToken(PhoneNumberFormatter.formatNumber(contactData.number, "+"+TextSecurePreferences.getCountryCodeSelected(getActivity())+org.whispersystems.textsecure.internal.util.Util.NUMBER_FORMAT_HELPER));
                        }
                    }else{
                        tcontactToken   = DirectoryUtil.getDirectoryServerToken(contactData.number);
                    }
                    final String contactToken = tcontactToken;
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
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                    }

                    if (registeredUser != null) {
                        if(!org.whispersystems.textsecure.internal.util.Util.isValidEmail(contactData.number)){
                            registeredUser.setNumber(PhoneNumberFormatter.formatNumber(contactData.number, "+"+TextSecurePreferences.getCountryCodeSelected(getActivity())+org.whispersystems.textsecure.internal.util.Util.NUMBER_FORMAT_HELPER));
                        }else{
                            registeredUser.setNumber(contactData.number);
                        }

                        directory.setNumber(registeredUser, true);

                        if (multi) holder.checkBox.toggle();

                        if (!multi || holder.checkBox.isChecked()) {
                            addContact(contactData);
                        } else if (multi) {
                            removeContact(contactData);
                        }

                        return;
                    }
                } catch (Exception e1) {
                    Log.w("ListClickListener", e1);
                } catch (InvalidNumberException e) {
                    e.printStackTrace();
                }

                AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity());
                dialog.setTitle(R.string.securecom_messaging_email_not_registered_with_server_exclamation);
                dialog.setIcon(android.R.drawable.ic_dialog_info);
                dialog.setMessage(R.string.securecom_messaging_the_email_you_selected_is_not_registered_with_securecom_messaging_both_parties_of_securecom_messaging_need_to_have_securecom_messaging_installed);
                dialog.setCancelable(false);
                dialog.setPositiveButton(R.string.securecom_messaging_yes_exclamation, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int arg) {
                        String accEmail = TextSecurePreferences.getLocalNumber(getActivity());
                        new AsyncTask<Void, Void, String>() {
                            @Override
                            protected String doInBackground(Void... params) {
                                PushServiceSocket socket = new PushServiceSocket(Release.PUSH_URL,
                                        new TextSecurePushTrustStore(getActivity().getApplicationContext()),
                                        TextSecurePreferences.getLocalNumber(getActivity().getApplicationContext()),
                                        TextSecurePreferences.getPushServerPassword(getActivity().getApplicationContext()));

                                String response = "";
                                try {
                                    if(!org.whispersystems.textsecure.internal.util.Util.isValidEmail(contactData.number)){
                                        String countrycode = TextSecurePreferences.getCountryCodeSelected(getActivity());
                                        if(countrycode.equalsIgnoreCase("")){
                                            countrycode = ""+1;
                                        }
                                        response = socket.sendInvitation(PhoneNumberFormatter.formatNumber(contactData.number, "+"+countrycode+org.whispersystems.textsecure.internal.util.Util.NUMBER_FORMAT_HELPER));
                                    }else{
                                        response = socket.sendInvitation(contactData.number);
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                } catch (InvalidNumberException e) {
                                    e.printStackTrace();
                                }

                                return response;
                            }

                            @Override
                            protected void onPostExecute(String result) {
                                if(result.equalsIgnoreCase("200")){
                                    Toast.makeText(getActivity(),
                                            getString(R.string.text_invitation_successfully_sent),
                                            Toast.LENGTH_LONG).show();
                                }else{
                                    Toast.makeText(getActivity(),
                                            getString(R.string.text_invitation_failed_to_send),
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        }.execute();
                    }
                });
                dialog.setNegativeButton(R.string.securecom_messaging_no_thanks_exclamation, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int arg) {
                    }
                });
                dialog.show();
                return;
            }

            if (holder == null) {
                Log.w(TAG, "ViewHolder was null, can't proceed with click logic.");
                return;
            }

            if (multi) holder.checkBox.toggle();

            if (!multi || holder.checkBox.isChecked()) {
                addContact(contactData);
            } else if (multi) {
                removeContact(contactData);
            }
        }

    }

    public void setOnContactSelectedListener(OnContactSelectedListener onContactSelectedListener) {
        this.onContactSelectedListener = onContactSelectedListener;
    }

    public interface OnContactSelectedListener {
        public void onContactSelected(ContactData contactData);
    }
}
