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

package com.securecomcode.messaging;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;

import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.util.DynamicLanguage;
import com.securecomcode.messaging.util.DynamicTheme;
import com.securecomcode.messaging.util.MemoryCleaner;
import com.securecomcode.messaging.util.TextSecurePreferences;
import org.whispersystems.textsecure.crypto.MasterSecret;

/**
 * An activity to quickly share content with contacts
 *
 * @author Jake McGinty
 */
public class ShareActivity extends PassphraseRequiredSherlockFragmentActivity
    implements ShareFragment.ConversationSelectedListener
  {
  public final static String MASTER_SECRET_EXTRA = "master_secret";

  private final DynamicTheme    dynamicTheme    = new DynamicTheme   ();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private ShareFragment fragment;
  private MasterSecret  masterSecret;

  @Override
  public void onCreate(Bundle icicle) {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
    super.onCreate(icicle);

    setContentView(R.layout.share_activity);
    initializeResources();
  }

  @Override
  protected void onNewIntent(Intent intent) {
      super.onNewIntent(intent);
      setIntent(intent);
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
    getSupportActionBar().setTitle(R.string.ShareActivity_share_with);
  }

  @Override
  public void onPause() {
    super.onPause();
    if (!isFinishing()) finish();
  }

  @Override
  public void onDestroy() {
    MemoryCleaner.clean(masterSecret);
    super.onDestroy();
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getSupportMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.share, menu);
    super.onPrepareOptionsMenu(menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    switch (item.getItemId()) {
    case R.id.menu_new_message: handleNewConversation(); return true;
    case android.R.id.home:     finish();                return true;
    }
    return false;
  }

  @Override
  public void onMasterSecretCleared() {
    startActivity(new Intent(this, RoutingActivity.class));
    super.onMasterSecretCleared();
  }

  private void handleNewConversation() {
    Intent intent = getBaseShareIntent(NewConversationActivity.class);
    startActivity(intent);
  }

  @Override
  public void onCreateConversation(long threadId, Recipients recipients, int distributionType) {
    createConversation(threadId, recipients, distributionType);
  }

  private void createConversation(long threadId, Recipients recipients, int distributionType) {
    final Intent intent = getBaseShareIntent(ConversationActivity.class);
    intent.putExtra(ConversationActivity.RECIPIENTS_EXTRA, recipients.toIdString());
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, distributionType);

    startActivity(intent);
  }

  private void initializeResources() {
    this.masterSecret = getIntent().getParcelableExtra(MASTER_SECRET_EXTRA);

    this.fragment = (ShareFragment)this.getSupportFragmentManager()
        .findFragmentById(R.id.fragment_content);

    this.fragment.setMasterSecret(masterSecret);
  }

  private Intent getBaseShareIntent(final Class<?> target) {
    final Intent intent = new Intent(this, target);
    final Intent originalIntent = getIntent();
    final String draftText  = originalIntent.getStringExtra(ConversationActivity.DRAFT_TEXT_EXTRA);
    final Uri    draftImage = originalIntent.getParcelableExtra(ConversationActivity.DRAFT_IMAGE_EXTRA);
    final Uri    draftAudio = originalIntent.getParcelableExtra(ConversationActivity.DRAFT_AUDIO_EXTRA);
    final Uri    draftVideo = originalIntent.getParcelableExtra(ConversationActivity.DRAFT_VIDEO_EXTRA);
    final Uri    draftOther = originalIntent.getParcelableExtra(ConversationActivity.DRAFT_OTHER_EXTRA);

    intent.putExtra(ConversationActivity.DRAFT_TEXT_EXTRA, draftText);
    intent.putExtra(ConversationActivity.DRAFT_IMAGE_EXTRA, draftImage);
    intent.putExtra(ConversationActivity.DRAFT_AUDIO_EXTRA, draftAudio);
    intent.putExtra(ConversationActivity.DRAFT_VIDEO_EXTRA, draftVideo);
    intent.putExtra(ConversationActivity.DRAFT_OTHER_EXTRA, draftVideo);
    intent.putExtra(NewConversationActivity.MASTER_SECRET_EXTRA, masterSecret);

    return intent;
  }
}
