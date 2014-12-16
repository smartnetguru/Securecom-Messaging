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

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.securecomcode.messaging.contacts.ContactAccessor;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.ThreadDatabase;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.RecipientFactory;
import com.securecomcode.messaging.recipients.RecipientFormattingException;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.util.DirectoryHelper;
import com.securecomcode.messaging.util.DynamicLanguage;
import com.securecomcode.messaging.util.DynamicTheme;
import com.securecomcode.messaging.util.NumberUtil;
import com.securecomcode.messaging.util.TextSecurePreferences;
import com.securecomcode.messaging.crypto.MasterSecret;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static com.securecomcode.messaging.contacts.ContactAccessor.ContactData;

/**
 * Activity container for selecting a list of contacts.
 *
 * @author Moxie Marlinspike
 *
 */
public class NewConversationActivity extends PassphraseRequiredActionBarActivity {
  private final static String TAG                 = "ContactSelectActivity";
  public  final static String MASTER_SECRET_EXTRA = "master_secret";

  private final DynamicTheme    dynamicTheme    = new DynamicTheme   ();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();
  private       MasterSecret    masterSecret;

  private PushContactSelectionListFragment contactsFragment;

  @Override
  protected void onCreate(Bundle icicle) {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
    super.onCreate(icicle);

    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    setContentView(R.layout.new_conversation_activity);
    initializeResources();
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
    getSupportActionBar().setTitle(R.string.AndroidManifest__select_contacts);
    masterSecret = getIntent().getParcelableExtra(MASTER_SECRET_EXTRA);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    if (TextSecurePreferences.isPushRegistered(this)) inflater.inflate(R.menu.push_directory, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    switch (item.getItemId()) {
    case R.id.menu_refresh_directory:  handleDirectoryRefresh();  return true;
    case R.id.menu_selection_finished: handleSelectionFinished(); return true;
    case android.R.id.home:            finish();                  return true;
    }
    return false;
  }

  private void initializeResources() {
    contactsFragment = (PushContactSelectionListFragment) getSupportFragmentManager().findFragmentById(R.id.contact_selection_list_fragment);
    contactsFragment.setOnContactSelectedListener(new PushContactSelectionListFragment.OnContactSelectedListener() {
      @Override
      public void onContactSelected(ContactData contactData) {
        Log.i(TAG, "Choosing contact from list.");
        Recipients recipients = contactDataToRecipients(contactData);
        openNewConversation(recipients);
      }
    });
  }

  private void handleSelectionFinished() {
    final Intent resultIntent = getIntent();
    final List<ContactData> selectedContacts = contactsFragment.getSelectedContacts();
    if (selectedContacts != null) {
      resultIntent.putParcelableArrayListExtra("contacts", new ArrayList<ContactData>(contactsFragment.getSelectedContacts()));
    }
    setResult(RESULT_OK, resultIntent);
    finish();
  }

  private void handleDirectoryRefresh() {
    DirectoryHelper.refreshDirectoryWithProgressDialog(this, new DirectoryHelper.DirectoryUpdateFinishedListener() {
      @Override
      public void onUpdateFinished() {
        contactsFragment.update();
      }
    });
  }

  private Recipients contactDataToRecipients(ContactData contactData) {
    if (contactData == null || contactData.numbers == null) return null;
    Recipients recipients = new Recipients(new LinkedList<Recipient>());
    for (ContactAccessor.NumberData numberData : contactData.numbers) {
      if (NumberUtil.isValidSmsOrEmailOrGroup(numberData.number)) {
        try {
          Recipients recipientsForNumber = RecipientFactory.getRecipientsFromString(NewConversationActivity.this,
                                                                                    numberData.number,
                                                                                    false);
          recipients.getRecipientsList().addAll(recipientsForNumber.getRecipientsList());
        } catch (RecipientFormattingException rfe) {
          Log.w(TAG, "Caught RecipientFormattingException when trying to convert a selected number to a Recipient.", rfe);
        }
      }
    }
    return recipients;
  }

  private void openNewConversation(Recipients recipients) {
    if (recipients != null) {
      Intent intent = new Intent(this, ConversationActivity.class);
      intent.putExtra(ConversationActivity.RECIPIENTS_EXTRA, recipients.toIdString());
      intent.putExtra(ConversationActivity.MASTER_SECRET_EXTRA, masterSecret);
      intent.putExtra(ConversationActivity.DRAFT_TEXT_EXTRA, getIntent().getStringExtra(ConversationActivity.DRAFT_TEXT_EXTRA));
      intent.putExtra(ConversationActivity.DRAFT_AUDIO_EXTRA, getIntent().getParcelableExtra(ConversationActivity.DRAFT_AUDIO_EXTRA));
      intent.putExtra(ConversationActivity.DRAFT_VIDEO_EXTRA, getIntent().getParcelableExtra(ConversationActivity.DRAFT_VIDEO_EXTRA));
      intent.putExtra(ConversationActivity.DRAFT_OTHER_EXTRA, getIntent().getParcelableExtra(ConversationActivity.DRAFT_OTHER_EXTRA));
      intent.putExtra(ConversationActivity.DRAFT_IMAGE_EXTRA, getIntent().getParcelableExtra(ConversationActivity.DRAFT_IMAGE_EXTRA));
      long existingThread = DatabaseFactory.getThreadDatabase(this).getThreadIdIfExistsFor(recipients);
      intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, existingThread);
      intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, ThreadDatabase.DistributionTypes.DEFAULT);
      startActivity(intent);
      finish();
    }
  }
}
