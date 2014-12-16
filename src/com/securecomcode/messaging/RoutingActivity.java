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
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.util.Log;

import com.securecomcode.messaging.crypto.MasterSecretUtil;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.recipients.RecipientFactory;
import com.securecomcode.messaging.recipients.RecipientFormattingException;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.util.TextSecurePreferences;
import com.securecomcode.messaging.crypto.MasterSecret;

public class RoutingActivity extends PassphraseRequiredActionBarActivity {

  private static final int STATE_CREATE_PASSPHRASE        = 1;
  private static final int STATE_PROMPT_PASSPHRASE        = 2;

  private static final int STATE_CONVERSATION_OR_LIST     = 3;
  private static final int STATE_UPGRADE_DATABASE         = 4;
  private static final int STATE_PROMPT_PUSH_REGISTRATION = 5;

  private MasterSecret masterSecret   = null;
  private boolean      isVisible      = false;
  private boolean      canceledResult = false;
  private boolean      newIntent      = false;

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    this.newIntent = true;
  }

  @Override
  public void onResume() {
    if (this.canceledResult && !this.newIntent) {
      finish();
    }

    this.newIntent      = false;
    this.canceledResult = false;
    this.isVisible      = true;
    super.onResume();
  }

  @Override
  public void onPause() {
    this.isVisible = false;
    super.onPause();
  }

  @Override
  public void onNewMasterSecret(MasterSecret masterSecret) {
    this.masterSecret = masterSecret;

    if (isVisible) {
      routeApplicationState();
    }
  }

  @Override
  public void onMasterSecretCleared() {
    this.masterSecret = null;

    if (isVisible) {
      routeApplicationState();
    }
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (resultCode == RESULT_CANCELED) {
      canceledResult = true;
    }
  }

  private void routeApplicationState() {
    int state = getApplicationState();

    switch (state) {
    case STATE_CREATE_PASSPHRASE:        handleCreatePassphrase();          break;
    case STATE_PROMPT_PASSPHRASE:        handlePromptPassphrase();          break;
    case STATE_CONVERSATION_OR_LIST:     handleDisplayConversationOrList(); break;
    case STATE_UPGRADE_DATABASE:         handleUpgradeDatabase();           break;
    case STATE_PROMPT_PUSH_REGISTRATION: handlePushRegistration();          break;
    }
  }

  private void handleCreatePassphrase() {
    Intent intent = new Intent(this, PassphraseCreateActivity.class);
    startActivityForResult(intent, 1);
  }

  private void handlePromptPassphrase() {
    Intent intent = new Intent(this, PassphrasePromptActivity.class);
    startActivityForResult(intent, 2);
  }

  private void handleUpgradeDatabase() {
    Intent intent = new Intent(this, DatabaseUpgradeActivity.class);
    intent.putExtra("master_secret", masterSecret);
    intent.putExtra("next_intent", TextSecurePreferences.hasPromptedPushRegistration(this) ?
                                   getConversationListIntent() : getPushRegistrationIntent());

    startActivity(intent);
    finish();
  }

  private void handlePushRegistration() {
    Intent intent = getPushRegistrationIntent();
    intent.putExtra("next_intent", getConversationListIntent());
    startActivity(intent);
    finish();
  }

  private void handleDisplayConversationOrList() {
    final ConversationParameters parameters = getConversationParameters();
    final Intent intent;

    if      (isShareAction())               intent = getShareIntent(parameters);
    else if (parameters.recipients != null) intent = getConversationIntent(parameters);
    else                                    intent = getConversationListIntent();

    startActivity(intent);
    finish();
  }

  private Intent getConversationIntent(ConversationParameters parameters) {
    Intent intent = new Intent(this, ConversationActivity.class);
    intent.putExtra(ConversationActivity.RECIPIENTS_EXTRA, parameters.recipients != null ? parameters.recipients.toIdString() : "");
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, parameters.thread);
    intent.putExtra(ConversationActivity.MASTER_SECRET_EXTRA, masterSecret);
    intent.putExtra(ConversationActivity.DRAFT_TEXT_EXTRA, parameters.draftText);
    intent.putExtra(ConversationActivity.DRAFT_IMAGE_EXTRA, parameters.draftImage);
    intent.putExtra(ConversationActivity.DRAFT_AUDIO_EXTRA, parameters.draftAudio);
    intent.putExtra(ConversationActivity.DRAFT_VIDEO_EXTRA, parameters.draftVideo);
    intent.putExtra(ConversationActivity.DRAFT_OTHER_EXTRA, parameters.draftOther);

    return intent;
  }

  private Intent getShareIntent(ConversationParameters parameters) {
    Intent intent = new Intent(this, ShareActivity.class);
    intent.putExtra("master_secret", masterSecret);

    if (parameters != null) {
      intent.putExtra(ConversationActivity.DRAFT_TEXT_EXTRA, parameters.draftText);
      intent.putExtra(ConversationActivity.DRAFT_IMAGE_EXTRA, parameters.draftImage);
      intent.putExtra(ConversationActivity.DRAFT_AUDIO_EXTRA, parameters.draftAudio);
      intent.putExtra(ConversationActivity.DRAFT_VIDEO_EXTRA, parameters.draftVideo);
      intent.putExtra(ConversationActivity.DRAFT_OTHER_EXTRA, parameters.draftOther);
    }

    return intent;
  }

  private Intent getConversationListIntent() {
    Intent intent = new Intent(this, ConversationListActivity.class);
    intent.putExtra("master_secret", masterSecret);

    return intent;
  }

  private Intent getPushRegistrationIntent() {
    Intent intent = new Intent(this, RegistrationActivity.class);
    intent.putExtra("master_secret", masterSecret);

    return intent;
  }

  private int getApplicationState() {
    if (!MasterSecretUtil.isPassphraseInitialized(this))
      return STATE_CREATE_PASSPHRASE;

    if (masterSecret == null)
      return STATE_PROMPT_PASSPHRASE;

    if (DatabaseUpgradeActivity.isUpdate(this))
      return STATE_UPGRADE_DATABASE;

    if (!TextSecurePreferences.hasPromptedPushRegistration(this))
      return STATE_PROMPT_PUSH_REGISTRATION;

    return STATE_CONVERSATION_OR_LIST;
  }

  private ConversationParameters getConversationParameters() {
    if (isSendAction()) {
      return getConversationParametersForSendAction();
    } else if (isShareAction()) {
      return getConversationParametersForShareAction();
    } else {
      return getConversationParametersForInternalAction();
    }
  }

  private ConversationParameters getConversationParametersForSendAction() {
    Recipients recipients;
    long       threadId = getIntent().getLongExtra("thread_id", -1);

    try {
      String data = getIntent().getData().getSchemeSpecificPart();
      recipients = RecipientFactory.getRecipientsFromString(this, data, false);
      threadId   = DatabaseFactory.getThreadDatabase(this).getThreadIdIfExistsFor(recipients);
    } catch (RecipientFormattingException rfe) {
      recipients = null;
    }

    return new ConversationParameters(threadId, recipients, null, null, null, null, null);
  }

  private ConversationParameters getConversationParametersForShareAction() {
    String type      = getIntent().getType();
    String draftText = getIntent().getStringExtra(Intent.EXTRA_TEXT);
    Uri draftImage   = null;
    Uri draftAudio   = null;
    Uri draftVideo   = null;
    Uri draftOther   = null;

    Uri streamExtra = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);

    if (streamExtra != null) {
      type = getMimeType(streamExtra);
    }

    if (type != null && type.startsWith("image/")) {
      draftImage = streamExtra;
    } else if (type != null && type.startsWith("audio/")) {
      draftAudio = streamExtra;
    } else if (type != null && type.startsWith("video/")) {
      draftVideo = streamExtra;
    } else if (type != null){
      draftOther = streamExtra;
    }

    return new ConversationParameters(-1, null, draftText, draftImage, draftAudio, draftVideo, draftOther);
  }

  private String getMimeType(Uri uri) {
    String type = getContentResolver().getType(uri);

    if (type == null) {
      String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
      type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    }

    return type;
  }

  private ConversationParameters getConversationParametersForInternalAction() {
    long threadId         = getIntent().getLongExtra("thread_id", -1);
    Recipients recipients = getIntent().getParcelableExtra("recipients");

    return new ConversationParameters(threadId, recipients, null, null, null, null, null);
  }

  private boolean isShareAction() {
    return Intent.ACTION_SEND.equals(getIntent().getAction());
  }

  private boolean isSendAction() {
    return Intent.ACTION_SENDTO.equals(getIntent().getAction());
  }

  private static class ConversationParameters {
    public final long       thread;
    public final Recipients recipients;
    public final String     draftText;
    public final Uri        draftImage;
    public final Uri        draftAudio;
    public final Uri        draftVideo;
    public final Uri        draftOther;

    public ConversationParameters(long thread, Recipients recipients,
                                  String draftText, Uri draftImage, Uri draftAudio, Uri draftVideo, Uri draftOther)
    {
     this.thread     = thread;
     this.recipients = recipients;
     this.draftText  = draftText;
     this.draftImage = draftImage;
     this.draftAudio = draftAudio;
     this.draftVideo = draftVideo;
     this.draftOther = draftOther;
    }
  }

}
