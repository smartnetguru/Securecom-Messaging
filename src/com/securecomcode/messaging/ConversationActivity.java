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
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.google.protobuf.ByteString;

import com.securecomcode.messaging.components.EmojiDrawer;
import com.securecomcode.messaging.components.EmojiToggle;
import com.securecomcode.messaging.contacts.ContactAccessor;
import com.securecomcode.messaging.contacts.ContactAccessor.ContactData;
import com.securecomcode.messaging.crypto.KeyExchangeInitiator;
import com.securecomcode.messaging.crypto.KeyExchangeProcessor;
import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.DraftDatabase;
import com.securecomcode.messaging.database.DraftDatabase.Draft;
import com.securecomcode.messaging.database.GroupDatabase;
import com.securecomcode.messaging.database.ThreadDatabase;
import com.securecomcode.messaging.mms.AttachmentManager;
import com.securecomcode.messaging.mms.AttachmentTypeSelectorAdapter;
import com.securecomcode.messaging.mms.MediaTooLargeException;
import com.securecomcode.messaging.mms.MmsSendHelper;
import com.securecomcode.messaging.mms.OutgoingGroupMediaMessage;
import com.securecomcode.messaging.mms.OutgoingMediaMessage;
import com.securecomcode.messaging.mms.OutgoingSecureMediaMessage;
import com.securecomcode.messaging.mms.Slide;
import com.securecomcode.messaging.mms.SlideDeck;
import com.securecomcode.messaging.notifications.MessageNotifier;
import com.securecomcode.messaging.protocol.Tag;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.RecipientFactory;
import com.securecomcode.messaging.recipients.RecipientFormattingException;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.service.KeyCachingService;
import com.securecomcode.messaging.sms.MessageSender;
import com.securecomcode.messaging.sms.OutgoingEncryptedMessage;
import com.securecomcode.messaging.sms.OutgoingEndSessionMessage;
import com.securecomcode.messaging.sms.OutgoingTextMessage;
import com.securecomcode.messaging.util.BitmapDecodingException;
import com.securecomcode.messaging.util.BitmapUtil;
import com.securecomcode.messaging.util.CharacterCalculator;
import com.securecomcode.messaging.util.Dialogs;
import com.securecomcode.messaging.util.DirectoryHelper;
import com.securecomcode.messaging.util.DynamicLanguage;
import com.securecomcode.messaging.util.DynamicTheme;
import com.securecomcode.messaging.util.EncryptedCharacterCalculator;
import com.securecomcode.messaging.util.GroupUtil;
import com.securecomcode.messaging.util.MemoryCleaner;
import com.securecomcode.messaging.util.TextSecurePreferences;
import com.securecomcode.messaging.util.Emoji;
import org.whispersystems.textsecure.crypto.InvalidMessageException;
import org.whispersystems.textsecure.crypto.MasterCipher;
import org.whispersystems.textsecure.crypto.MasterSecret;
import org.whispersystems.textsecure.storage.RecipientDevice;
import org.whispersystems.textsecure.storage.Session;
import org.whispersystems.textsecure.storage.SessionRecordV2;
import org.whispersystems.textsecure.util.Util;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import ws.com.google.android.mms.MmsException;

import static com.securecomcode.messaging.database.GroupDatabase.GroupRecord;
import static com.securecomcode.messaging.recipients.Recipient.RecipientModifiedListener;
import static org.whispersystems.textsecure.push.PushMessageProtos.PushMessageContent.GroupContext;

/**
 * Activity for displaying a message thread, as well as
 * composing/sending a new message into that thread.
 *
 * @author Moxie Marlinspike
 *
 */
public class ConversationActivity extends PassphraseRequiredSherlockFragmentActivity
    implements ConversationFragment.ConversationFragmentListener,
               AttachmentManager.AttachmentListener
{
  private static final String TAG = ConversationActivity.class.getSimpleName();

  public static final String RECIPIENTS_EXTRA        = "recipients";
  public static final String THREAD_ID_EXTRA         = "thread_id";
  public static final String MASTER_SECRET_EXTRA     = "master_secret";
  public static final String DRAFT_TEXT_EXTRA        = "draft_text";
  public static final String DRAFT_IMAGE_EXTRA       = "draft_image";
  public static final String DRAFT_AUDIO_EXTRA       = "draft_audio";
  public static final String DRAFT_VIDEO_EXTRA       = "draft_video";
  public static final String DRAFT_OTHER_EXTRA       = "draft_other";
  public static final String DISTRIBUTION_TYPE_EXTRA = "distribution_type";

  private static final int PICK_IMAGE        = 1;
  private static final int PICK_VIDEO        = 2;
  private static final int PICK_AUDIO        = 3;
  private static final int PICK_CONTACT_INFO = 4;
  private static final int GROUP_EDIT        = 5;
  private static final int PICK_OTHERS       = 6;

  private static final int SEND_ATTRIBUTES[] = new int[]{R.attr.conversation_send_button_push,
                                                         R.attr.conversation_send_button_sms_secure,
                                                         R.attr.conversation_send_button_sms_insecure};

  private MasterSecret    masterSecret;
  private EditText        composeText;
  private ImageButton     sendButton;
  private TextView        charactersLeft;

  private AttachmentTypeSelectorAdapter attachmentAdapter;
  private AttachmentManager             attachmentManager;
  private BroadcastReceiver             securityUpdateReceiver;
  private BroadcastReceiver             groupUpdateReceiver;
  private EmojiDrawer                   emojiDrawer;
  private EmojiToggle                   emojiToggle;

  private Recipients recipients;
  private long       threadId;
  private int        distributionType;
  private boolean    isEncryptedConversation;
  private boolean    isMmsEnabled = true;
  private boolean    isCharactersLeftViewEnabled;

  private CharacterCalculator characterCalculator = new CharacterCalculator();
  private DynamicTheme        dynamicTheme        = new DynamicTheme();
  private DynamicLanguage     dynamicLanguage     = new DynamicLanguage();

  @Override
  protected void onCreate(Bundle state) {
    overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
    super.onCreate(state);

    setContentView(R.layout.conversation_activity);
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    initializeReceivers();
    initializeResources();
    initializeDraft();
    initializeTitleBar();
  }

  @Override
  protected void onStart() {
    super.onStart();
  }

  @Override
  protected void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);

    initializeSecurity();
    initializeTitleBar();
    initializeEnabledCheck();
    initializeIme();
    initializeCharactersLeftViewEnabledCheck();
    calculateCharactersRemaining();

    MessageNotifier.setVisibleThread(threadId);
    markThreadAsRead();
  }

  @Override
  protected void onPause() {
    super.onPause();
    MessageNotifier.setVisibleThread(-1L);
    if (isFinishing()) overridePendingTransition(R.anim.fade_scale_in, R.anim.slide_to_right);
  }

  @Override
  protected void onDestroy() {
    unregisterReceiver(securityUpdateReceiver);
    unregisterReceiver(groupUpdateReceiver);
    saveDraft();
    MemoryCleaner.clean(masterSecret);
    super.onDestroy();
  }

  @Override
  public void onActivityResult(int reqCode, int resultCode, Intent data) {
    Log.w(TAG, "onActivityResult called: " + reqCode + ", " + resultCode + " , " + data);
    super.onActivityResult(reqCode, resultCode, data);

    if (data == null || resultCode != RESULT_OK) return;
    switch (reqCode) {
    case PICK_IMAGE:
      addAttachmentImage(data.getData());
      break;
    case PICK_VIDEO:
      addAttachmentVideo(data.getData());
      break;
    case PICK_AUDIO:
      addAttachmentAudio(data.getData());
      break;
    case PICK_OTHERS:
      addAttachmentOthers(data.getData());
      break;
    case PICK_CONTACT_INFO:
      addContactInfo(data.getData());
      break;
    case GROUP_EDIT:
      this.recipients = data.getParcelableExtra(GroupCreateActivity.GROUP_RECIPIENT_EXTRA);
      initializeTitleBar();
      break;
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getSupportMenuInflater();
    menu.clear();

    boolean pushRegistered = TextSecurePreferences.isPushRegistered(this);

    if (isSingleConversation() && isEncryptedConversation) {
      inflater.inflate(R.menu.conversation_secure_identity, menu);
      inflater.inflate(R.menu.conversation_secure_sms, menu.findItem(R.id.menu_security).getSubMenu());
    } else if (isSingleConversation() && !pushRegistered) {
      inflater.inflate(R.menu.conversation_insecure, menu);
    }

	  if (isGroupConversation()) {
      inflater.inflate(R.menu.conversation_group_options, menu);

      if (!isPushGroupConversation()) {
        inflater.inflate(R.menu.conversation_mms_group_options, menu);
        if (distributionType == ThreadDatabase.DistributionTypes.BROADCAST) {
          menu.findItem(R.id.menu_distribution_broadcast).setChecked(true);
        } else {
          menu.findItem(R.id.menu_distribution_conversation).setChecked(true);
        }
      } else if (isActiveGroup()) {
        inflater.inflate(R.menu.conversation_push_group_options, menu);
      }
    }

    inflater.inflate(R.menu.conversation, menu);
    super.onPrepareOptionsMenu(menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    switch (item.getItemId()) {
    case R.id.menu_call:                      handleDial(getRecipients().getPrimaryRecipient()); return true;
    case R.id.menu_delete_thread:             handleDeleteThread();                              return true;
    case R.id.menu_add_contact_info:          handleAddContactInfo();                            return true;
    case R.id.menu_add_attachment:            handleAddAttachment();                             return true;
    case R.id.menu_start_secure_session:      handleStartSecureSession();                        return true;
    case R.id.menu_abort_session:             handleAbortSecureSession();                        return true;
    case R.id.menu_verify_identity:           handleVerifyIdentity();                            return true;
    case R.id.menu_group_recipients:          handleDisplayGroupRecipients();                    return true;
    case R.id.menu_distribution_broadcast:    handleDistributionBroadcastEnabled(item);          return true;
    case R.id.menu_distribution_conversation: handleDistributionConversationEnabled(item);       return true;
    case R.id.menu_edit_group:                handleEditPushGroup();                             return true;
    case R.id.menu_leave:                     handleLeavePushGroup();                            return true;
    case android.R.id.home:                   handleReturnToConversationList();                  return true;
    }

    return false;
  }

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
    if (isEncryptedConversation && isSingleConversation()) {
      boolean   isPushDestination = DirectoryHelper.isPushDestination(this, getRecipients());
      Recipient primaryRecipient  = getRecipients() == null ? null : getRecipients().getPrimaryRecipient();
      boolean   hasSession        = Session.hasSession(this, masterSecret, primaryRecipient);

      getMenuInflater().inflate(R.menu.conversation_button_context, menu);

      if (!isPushDestination) {
        menu.removeItem(R.id.menu_context_send_push);
      }

    }
  }

  @Override
  public boolean onContextItemSelected(android.view.MenuItem item) {
    switch (item.getItemId()) {
      case R.id.menu_context_send_push:            sendMessage(false, false); return true;
    }

    return false;
  }

  @Override
  public void onBackPressed() {
    if (emojiDrawer.getVisibility() == View.VISIBLE) {
      emojiDrawer.setVisibility(View.GONE);
      emojiToggle.toggle();
    } else {
      super.onBackPressed();
    }
  }

  //////// Event Handlers

  private void handleReturnToConversationList() {
    Intent intent = new Intent(this, ConversationListActivity.class);
    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    intent.putExtra("master_secret", masterSecret);
    startActivity(intent);
    finish();
  }

  private void handleVerifyIdentity() {
    Intent verifyIdentityIntent = new Intent(this, VerifyIdentityActivity.class);
    verifyIdentityIntent.putExtra("recipient", getRecipients().getPrimaryRecipient());
    verifyIdentityIntent.putExtra("master_secret", masterSecret);
    startActivity(verifyIdentityIntent);
  }

  private void handleStartSecureSession() {
    if (getRecipients() == null) {
      Toast.makeText(this, getString(R.string.ConversationActivity_invalid_recipient),
                     Toast.LENGTH_LONG).show();
      return;
    }

    final Recipient recipient   = getRecipients().getPrimaryRecipient();
    String recipientName        = (recipient.getName() == null ? recipient.getNumber() : recipient.getName());
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.ConversationActivity_initiate_secure_session_question);
    builder.setIcon(Dialogs.resolveIcon(this, R.attr.dialog_info_icon));
    builder.setCancelable(true);
    builder.setMessage(String.format(getString(R.string.ConversationActivity_initiate_secure_session_with_s_question),
                       recipientName));
    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        KeyExchangeInitiator.initiate(ConversationActivity.this, masterSecret,
                                      recipient, true);
      }
    });

    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleAbortSecureSession() {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.ConversationActivity_abort_secure_session_confirmation);
    builder.setIcon(Dialogs.resolveIcon(this, R.attr.dialog_alert_icon));
    builder.setCancelable(true);
    builder.setMessage(R.string.ConversationActivity_are_you_sure_that_you_want_to_abort_this_secure_session_question);
    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        if (isSingleConversation()) {
          ConversationActivity self      = ConversationActivity.this;
          Recipient            recipient = getRecipients().getPrimaryRecipient();

          if (SessionRecordV2.hasSession(self, masterSecret,
                                         recipient.getRecipientId(),
                                         RecipientDevice.DEFAULT_DEVICE_ID))
          {
            OutgoingEndSessionMessage endSessionMessage =
                new OutgoingEndSessionMessage(new OutgoingTextMessage(getRecipients(), "TERMINATE"));

            long allocatedThreadId = MessageSender.send(self, masterSecret,
                                                        endSessionMessage, threadId, false);

            sendComplete(recipients, allocatedThreadId, allocatedThreadId != self.threadId);
          } else {
            Session.abortSessionFor(self, recipient);
            initializeSecurity();
            initializeTitleBar();
          }
        }
      }
    });
    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleLeavePushGroup() {
    if (getRecipients() == null) {
      Toast.makeText(this, getString(R.string.ConversationActivity_invalid_recipient),
                     Toast.LENGTH_LONG).show();
      return;
    }

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(getString(R.string.ConversationActivity_leave_group));
    builder.setIcon(Dialogs.resolveIcon(this, R.attr.dialog_info_icon));
    builder.setCancelable(true);
    builder.setMessage(getString(R.string.ConversationActivity_are_you_sure_you_want_to_leave_this_group));
    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        try {
          Context self    = ConversationActivity.this;
          byte[]  groupId = GroupUtil.getDecodedId(getRecipients().getPrimaryRecipient().getNumber());
          DatabaseFactory.getGroupDatabase(self).setActive(groupId, false);

          GroupContext context = GroupContext.newBuilder()
                                             .setId(ByteString.copyFrom(groupId))
                                             .setType(GroupContext.Type.QUIT)
                                             .build();

          OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(self, getRecipients(),
                                                                                    context, null);
          MessageSender.send(self, masterSecret, outgoingMessage, threadId, false);
          DatabaseFactory.getGroupDatabase(self).remove(groupId, TextSecurePreferences.getLocalNumber(self));
          initializeEnabledCheck();
        } catch (IOException e) {
          Log.w(TAG, e);
          Toast.makeText(ConversationActivity.this, "Error leaving group....", Toast.LENGTH_LONG).show();
        } catch (MmsException e) {
          Log.w(TAG, e);
          Toast.makeText(ConversationActivity.this, "Error leaving group...", Toast.LENGTH_LONG).show();
        }
      }
    });

    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleEditPushGroup() {
    Intent intent = new Intent(ConversationActivity.this, GroupCreateActivity.class);
    intent.putExtra(GroupCreateActivity.MASTER_SECRET_EXTRA, masterSecret);
    intent.putExtra(GroupCreateActivity.GROUP_RECIPIENT_EXTRA, recipients);
    startActivityForResult(intent, GROUP_EDIT);
  }

  private void handleDistributionBroadcastEnabled(MenuItem item) {
    distributionType = ThreadDatabase.DistributionTypes.BROADCAST;
    item.setChecked(true);

    if (threadId != -1) {
      new AsyncTask<Void, Void, Void>() {
        @Override
        protected Void doInBackground(Void... params) {
          DatabaseFactory.getThreadDatabase(ConversationActivity.this)
                         .setDistributionType(threadId, ThreadDatabase.DistributionTypes.BROADCAST);
          return null;
        }
      }.execute();
    }
  }

  private void handleDistributionConversationEnabled(MenuItem item) {
    distributionType = ThreadDatabase.DistributionTypes.CONVERSATION;
    item.setChecked(true);

    if (threadId != -1) {
      new AsyncTask<Void, Void, Void>() {
        @Override
        protected Void doInBackground(Void... params) {
          DatabaseFactory.getThreadDatabase(ConversationActivity.this)
                         .setDistributionType(threadId, ThreadDatabase.DistributionTypes.CONVERSATION);
          return null;
        }
      }.execute();
    }
  }

  private void handleDial(Recipient recipient) {
    try {
      if (recipient == null) return;

      Intent dialIntent = new Intent(Intent.ACTION_DIAL,
                              Uri.parse("tel:" + recipient.getNumber()));
      startActivity(dialIntent);
    } catch (ActivityNotFoundException anfe) {
      Log.w(TAG, anfe);
      Dialogs.showAlertDialog(this,
                           getString(R.string.ConversationActivity_calls_not_supported),
                           getString(R.string.ConversationActivity_this_device_does_not_appear_to_support_dial_actions));
    }
  }

  private void handleDisplayGroupRecipients() {
    new GroupMembersDialog(this, getRecipients()).display();
  }

  private void handleDeleteThread() {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.ConversationActivity_delete_thread_confirmation);
    builder.setIcon(Dialogs.resolveIcon(this, R.attr.dialog_alert_icon));
    builder.setCancelable(true);
    builder.setMessage(R.string.ConversationActivity_are_you_sure_that_you_want_to_permanently_delete_this_conversation_question);
    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        if (threadId > 0) {
          DatabaseFactory.getThreadDatabase(ConversationActivity.this).deleteConversation(threadId);
          finish();
        }
      }
    });

    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleAddContactInfo() {
    Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
    startActivityForResult(intent, PICK_CONTACT_INFO);
  }

  private void handleAddAttachment() {
    if (this.isMmsEnabled || DirectoryHelper.isPushDestination(this, getRecipients())) {
      AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.TextSecure_Light_Dialog));
      builder.setIcon(R.drawable.ic_dialog_attach);
      builder.setTitle(R.string.ConversationActivity_add_attachment);
      builder.setAdapter(attachmentAdapter, new AttachmentTypeListener());
      builder.show();
    } else {
      handleManualMmsRequired();
    }
  }

  private void handleManualMmsRequired() {
    Toast.makeText(this, R.string.MmsDownloader_error_reading_mms_settings, Toast.LENGTH_LONG).show();

    Intent intent = new Intent(this, PromptMmsActivity.class);
    intent.putExtras(getIntent().getExtras());
    startActivity(intent);
  }

  ///// Initializers

  private void initializeTitleBar() {
    String title    = null;
    String subtitle = null;

    if (isSingleConversation()) {
      title = getRecipients().getPrimaryRecipient().getName();

      if (title == null || title.trim().length() == 0) {
        title = getRecipients().getPrimaryRecipient().getNumber();
      } else {
        subtitle = getRecipients().getPrimaryRecipient().getNumber();
      }
    } else if (isGroupConversation()) {
      if (isPushGroupConversation()) {
        final String groupName = getRecipients().getPrimaryRecipient().getName();
        title = (!TextUtils.isEmpty(groupName)) ? groupName : getString(R.string.ConversationActivity_unnamed_group);
        final Bitmap avatar = getRecipients().getPrimaryRecipient().getContactPhoto();
        if (avatar != null) {
          getSupportActionBar().setIcon(new BitmapDrawable(getResources(), BitmapUtil.getCircleCroppedBitmap(avatar)));
        }
      } else {
        title = getString(R.string.ConversationActivity_group_conversation);
        int size = getRecipients().getRecipientsList().size();
        subtitle = (size == 1) ? getString(R.string.ConversationActivity_d_recipients_in_group_singular)
            : String.format(getString(R.string.ConversationActivity_d_recipients_in_group), size);
      }
    } else {
      title    = getString(R.string.ConversationActivity_compose_message);
      subtitle = "";
    }

    this.getSupportActionBar().setTitle(title);

    if (subtitle != null && !Util.isEmpty(subtitle))
      this.getSupportActionBar().setSubtitle(PhoneNumberUtils.formatNumber(subtitle));

    this.invalidateOptionsMenu();
  }

  private void initializeDraft() {
    String draftText  = getIntent().getStringExtra(DRAFT_TEXT_EXTRA);
    Uri    draftImage = getIntent().getParcelableExtra(DRAFT_IMAGE_EXTRA);
    Uri    draftAudio = getIntent().getParcelableExtra(DRAFT_AUDIO_EXTRA);
    Uri    draftVideo = getIntent().getParcelableExtra(DRAFT_VIDEO_EXTRA);
    Uri    draftOther = getIntent().getParcelableExtra(DRAFT_OTHER_EXTRA);

    if (draftText != null)  composeText.setText(draftText);
    if (draftImage != null) addAttachmentImage(draftImage);
    if (draftAudio != null) addAttachmentAudio(draftAudio);
    if (draftVideo != null) addAttachmentVideo(draftVideo);
    if (draftOther != null) addAttachmentOthers(draftOther);

    if (draftText == null && draftImage == null && draftAudio == null && draftVideo == null && draftOther == null) {
      initializeDraftFromDatabase();
    }
  }

  private void initializeEnabledCheck() {
    boolean enabled = !(isPushGroupConversation() && !isActiveGroup());
    composeText.setEnabled(enabled);
    sendButton.setEnabled(enabled);
  }

  private void initializeCharactersLeftViewEnabledCheck() {
    isCharactersLeftViewEnabled = !(isPushGroupConversation() ||
        (TextSecurePreferences.isPushRegistered(this) && !TextSecurePreferences.isFallbackSmsAllowed(this)));
  }

  private void initializeDraftFromDatabase() {
    new AsyncTask<Void, Void, List<Draft>>() {
      @Override
      protected List<Draft> doInBackground(Void... params) {
        MasterCipher masterCipher   = new MasterCipher(masterSecret);
        DraftDatabase draftDatabase = DatabaseFactory.getDraftDatabase(ConversationActivity.this);
        List<Draft> results         = draftDatabase.getDrafts(masterCipher, threadId);

        draftDatabase.clearDrafts(threadId);

        return results;
      }

      @Override
      protected void onPostExecute(List<Draft> drafts) {
        boolean nativeEmojiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
        Context context              = ConversationActivity.this;

        for (Draft draft : drafts) {
          if (draft.getType().equals(Draft.TEXT) && !nativeEmojiSupported) {
            composeText.setText(Emoji.getInstance(context).emojify(draft.getValue()),
                                TextView.BufferType.SPANNABLE);
          } else if (draft.getType().equals(Draft.TEXT)) {
            composeText.setText(draft.getValue());
          } else if (draft.getType().equals(Draft.IMAGE)) {
            addAttachmentImage(Uri.parse(draft.getValue()));
          } else if (draft.getType().equals(Draft.AUDIO)) {
            addAttachmentAudio(Uri.parse(draft.getValue()));
          } else if (draft.getType().equals(Draft.VIDEO)) {
            addAttachmentVideo(Uri.parse(draft.getValue()));
          } else if (draft.getType().equals(Draft.OTHER)) {
            addAttachmentOthers(Uri.parse(draft.getValue()));
          }
        }
      }
    }.execute();
  }

  private void initializeSecurity() {
    TypedArray drawables           = obtainStyledAttributes(SEND_ATTRIBUTES);
    Recipient  primaryRecipient    = getRecipients() == null ? null : getRecipients().getPrimaryRecipient();
    boolean    isPushDestination   = DirectoryHelper.isPushDestination(this, getRecipients());
    boolean    isSecureDestination = isSingleConversation() && Session.hasSession(this, masterSecret, primaryRecipient);

    if (isPushDestination || isSecureDestination) {
      this.isEncryptedConversation = true;
      this.characterCalculator     = new EncryptedCharacterCalculator();
    } else {
      this.isEncryptedConversation = false;
      this.characterCalculator     = new CharacterCalculator();
    }

    if (isPushDestination) {
      sendButton.setImageDrawable(drawables.getDrawable(0));
      setComposeTextHint(getString(R.string.conversation_activity__type_message_push));
    }

    drawables.recycle();

    calculateCharactersRemaining();
  }


  private void initializeMmsEnabledCheck() {
    new AsyncTask<Void, Void, Boolean>() {
      @Override
      protected Boolean doInBackground(Void... params) {
        return MmsSendHelper.hasNecessaryApnDetails(ConversationActivity.this);
      }

      @Override
      protected void onPostExecute(Boolean isMmsEnabled) {
        ConversationActivity.this.isMmsEnabled = isMmsEnabled;
      }
    }.execute();
  }

  private void initializeIme() {
    if (TextSecurePreferences.isEnterImeKeyEnabled(this)) {
      composeText.setInputType(composeText.getInputType() & (~InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE));
    } else {
      composeText.setInputType(composeText.getInputType() | (InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE));
    }
  }

  private void initializeResources() {
    recipients          = RecipientFactory.getRecipientsForIds(this, getIntent().getStringExtra(RECIPIENTS_EXTRA), true);
    threadId            = getIntent().getLongExtra(THREAD_ID_EXTRA, -1);
    distributionType    = getIntent().getIntExtra(DISTRIBUTION_TYPE_EXTRA,
                                                  ThreadDatabase.DistributionTypes.DEFAULT);
    sendButton          = (ImageButton)findViewById(R.id.send_button);
    composeText         = (EditText)findViewById(R.id.embedded_text_editor);
    masterSecret        = getIntent().getParcelableExtra(MASTER_SECRET_EXTRA);
    charactersLeft      = (TextView)findViewById(R.id.space_left);
    emojiDrawer         = (EmojiDrawer)findViewById(R.id.emoji_drawer);
    emojiToggle         = (EmojiToggle)findViewById(R.id.emoji_toggle);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      emojiToggle.setVisibility(View.GONE);
    }

    attachmentAdapter   = new AttachmentTypeSelectorAdapter(this);
    attachmentManager   = new AttachmentManager(this, this);

    SendButtonListener        sendButtonListener        = new SendButtonListener();
    ComposeKeyPressedListener composeKeyPressedListener = new ComposeKeyPressedListener();

    sendButton.setOnClickListener(sendButtonListener);
    sendButton.setEnabled(true);
    composeText.setOnKeyListener(composeKeyPressedListener);
    composeText.addTextChangedListener(composeKeyPressedListener);
    composeText.setOnEditorActionListener(sendButtonListener);
    composeText.setOnClickListener(composeKeyPressedListener);
    emojiDrawer.setComposeEditText(composeText);
    emojiToggle.setOnClickListener(new EmojiToggleListener());

    recipients.addListener(new RecipientModifiedListener() {
      @Override
      public void onModified(Recipient recipient) {
        initializeTitleBar();
      }
    });

    registerForContextMenu(sendButton);
  }

  private void initializeReceivers() {
    securityUpdateReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (intent.getLongExtra("thread_id", -1) == -1)
          return;

        if (intent.getLongExtra("thread_id", -1) == threadId) {
          initializeSecurity();
          initializeTitleBar();
          calculateCharactersRemaining();
        }
      }
    };

    groupUpdateReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        Log.w("ConversationActivity", "Group update received...");
        if (recipients != null) {
          String ids = recipients.toIdString();
          Log.w("ConversationActivity", "Looking up new recipients...");
          recipients = RecipientFactory.getRecipientsForIds(context, ids, false);
          initializeTitleBar();
        }
      }
    };

    registerReceiver(securityUpdateReceiver,
                     new IntentFilter(KeyExchangeProcessor.SECURITY_UPDATE_EVENT),
                     KeyCachingService.KEY_PERMISSION, null);

    registerReceiver(groupUpdateReceiver,
                     new IntentFilter(GroupDatabase.DATABASE_UPDATE_ACTION));
  }

  //////// Helper Methods

  private void addAttachment(int type) {
    Log.w("ComposeMessageActivity", "Selected: " + type);
    switch (type) {
    case AttachmentTypeSelectorAdapter.ADD_IMAGE:
      AttachmentManager.selectImage(this, PICK_IMAGE); break;
    case AttachmentTypeSelectorAdapter.ADD_VIDEO:
      AttachmentManager.selectVideo(this, PICK_VIDEO); break;
    case AttachmentTypeSelectorAdapter.ADD_SOUND:
      AttachmentManager.selectAudio(this, PICK_AUDIO); break;
    case AttachmentTypeSelectorAdapter.ADD_OTHERS:
      AttachmentManager.selectOthers(this, PICK_OTHERS); break;

    }
  }

  private void addAttachmentImage(Uri imageUri) {
    try {
      attachmentManager.setImage(imageUri);
    } catch (IOException e) {
      Log.w(TAG, e);
      attachmentManager.clear();
      Toast.makeText(this, R.string.ConversationActivity_sorry_there_was_an_error_setting_your_attachment,
                     Toast.LENGTH_LONG).show();
    } catch (BitmapDecodingException e) {
      Log.w(TAG, e);
      attachmentManager.clear();
      Toast.makeText(this, R.string.ConversationActivity_sorry_there_was_an_error_setting_your_attachment,
                     Toast.LENGTH_LONG).show();
    }
  }

  private void addAttachmentVideo(Uri videoUri) {
    try {
      attachmentManager.setVideo(videoUri);
    } catch (IOException e) {
      attachmentManager.clear();
      Toast.makeText(this, R.string.ConversationActivity_sorry_there_was_an_error_setting_your_attachment,
                     Toast.LENGTH_LONG).show();
      Log.w("ComposeMessageActivity", e);
    } catch (MediaTooLargeException e) {
      attachmentManager.clear();
      Toast.makeText(this, R.string.ConversationActivity_sorry_the_selected_video_exceeds_message_size_restrictions,
                     Toast.LENGTH_LONG).show();
      Log.w("ComposeMessageActivity", e);
    }
  }

  private void addAttachmentAudio(Uri audioUri) {
    try {
      attachmentManager.setAudio(audioUri);
    } catch (IOException e) {
      attachmentManager.clear();
      Toast.makeText(this, R.string.ConversationActivity_sorry_there_was_an_error_setting_your_attachment,
                     Toast.LENGTH_LONG).show();
      Log.w("ComposeMessageActivity", e);
    } catch (MediaTooLargeException e) {
      attachmentManager.clear();
      Toast.makeText(this, R.string.ConversationActivity_sorry_the_selected_audio_exceeds_message_size_restrictions,
                     Toast.LENGTH_LONG).show();
      Log.w("ComposeMessageActivity", e);
    }
  }

    private void addAttachmentOthers(Uri otherUri) {
        try {
            attachmentManager.setOther(otherUri);
        } catch (IOException e) {
            attachmentManager.clear();
            Toast.makeText(this, R.string.ConversationActivity_sorry_there_was_an_error_setting_your_attachment,
                    Toast.LENGTH_LONG).show();
            Log.w("ComposeMessageActivity", e);
        } catch (MediaTooLargeException e) {
            attachmentManager.clear();
            Toast.makeText(this, R.string.ConversationActivity_sorry_the_selected_other_exceeds_message_size_restrictions,
                    Toast.LENGTH_LONG).show();
            Log.w("ComposeMessageActivity", e);
        }
    }

  private void addContactInfo(Uri contactUri) {
    ContactAccessor contactDataList = ContactAccessor.getInstance();
    ContactData contactData = contactDataList.getContacInfo(this, contactUri);

    if      (contactData.numbers.size() == 1) {
        composeText.append(contactData.name);
        composeText.append(" ");
        composeText.append(contactData.numbers.get(0).number);
    }
    else if (contactData.numbers.size() > 1)  selectContactInfo(contactData);
  }

  private void selectContactInfo(ContactData contactData) {
    final String name = contactData.name;
    final CharSequence[] numbers = new CharSequence[contactData.numbers.size()];
    final CharSequence[] numberItems = new CharSequence[contactData.numbers.size()];
    for (int i = 0; i < contactData.numbers.size(); i++) {
      numbers[i] = contactData.numbers.get(i).number;
      numberItems[i] = contactData.numbers.get(i).type + ": " + contactData.numbers.get(i).number;
    }

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setIcon(R.drawable.ic_contact_picture);
    builder.setTitle(R.string.ConversationActivity_select_contact_info);

    builder.setItems(numberItems, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        composeText.append(name);
        composeText.append(" ");
        composeText.append(numbers[which]);
      }
    });
    builder.show();
  }

  private List<Draft> getDraftsForCurrentState() {
    List<Draft> drafts = new LinkedList<Draft>();

    if (!Util.isEmpty(composeText)) {
      drafts.add(new Draft(Draft.TEXT, composeText.getText().toString()));
    }

    for (Slide slide : attachmentManager.getSlideDeck().getSlides()) {
      if      (slide.hasImage()) drafts.add(new Draft(Draft.IMAGE, slide.getUri().toString()));
      else if (slide.hasAudio()) drafts.add(new Draft(Draft.AUDIO, slide.getUri().toString()));
      else if (slide.hasVideo()) drafts.add(new Draft(Draft.VIDEO, slide.getUri().toString()));
      else if (slide.hasOther()) drafts.add(new Draft(Draft.OTHER, slide.getUri().toString()));
    }

    return drafts;
  }

  private void saveDraft() {
    if (this.threadId <= 0 || this.recipients == null || this.recipients.isEmpty())
      return;

    final List<Draft> drafts = getDraftsForCurrentState();

    if (drafts.size() <= 0)
      return;

    final long thisThreadId             = this.threadId;
    final MasterSecret thisMasterSecret = this.masterSecret.parcelClone();

    new AsyncTask<Void, Void, Void>() {
      @Override
      protected void onPostExecute(Void aVoid) {
        super.onPostExecute(aVoid);
        Toast.makeText(ConversationActivity.this,
                       R.string.ConversationActivity_saved_draft,
                       Toast.LENGTH_SHORT).show();
      }

      @Override
      protected Void doInBackground(Void... params) {
        MasterCipher masterCipher = new MasterCipher(thisMasterSecret);
        DatabaseFactory.getDraftDatabase(ConversationActivity.this).insertDrafts(masterCipher, thisThreadId, drafts);
        MemoryCleaner.clean(thisMasterSecret);
        return null;
      }
    }.execute();
  }

  private void calculateCharactersRemaining() {
    int charactersSpent                               = composeText.getText().toString().length();
    CharacterCalculator.CharacterState characterState = characterCalculator.calculateCharacters(charactersSpent);
    if (characterState.charactersRemaining <= 15 && charactersLeft.getVisibility() != View.VISIBLE && isCharactersLeftViewEnabled) {
      charactersLeft.setVisibility(View.VISIBLE);
    } else if (characterState.charactersRemaining > 15 && charactersLeft.getVisibility() != View.GONE) {
      charactersLeft.setVisibility(View.GONE);
    }
    charactersLeft.setText(characterState.charactersRemaining + "/" + characterState.maxMessageSize + " (" + characterState.messagesSpent + ")");
  }

  private boolean isExistingConversation() {
    return this.recipients != null && this.threadId != -1;
  }

  private boolean isSingleConversation() {
    return getRecipients() != null && getRecipients().isSingleRecipient() && !getRecipients().isGroupRecipient();
  }

  private boolean isActiveGroup() {
    if (!isGroupConversation()) return false;

    try {
      byte[]      groupId = GroupUtil.getDecodedId(getRecipients().getPrimaryRecipient().getNumber());
      GroupRecord record  = DatabaseFactory.getGroupDatabase(this).getGroup(groupId);

      return record != null && record.isActive();
    } catch (IOException e) {
      Log.w("ConversationActivity", e);
      return false;
    }
  }

  private boolean isGroupConversation() {
    return getRecipients() != null &&
        (!getRecipients().isSingleRecipient() || getRecipients().isGroupRecipient());
  }

  private boolean isPushGroupConversation() {
    return getRecipients() != null && getRecipients().isGroupRecipient();
  }

  private Recipients getRecipients() {
    return this.recipients;
  }

  private String getMessage() throws InvalidMessageException {
    String rawText = composeText.getText().toString();

    if (rawText.length() < 1 && !attachmentManager.isAttachmentPresent())
      throw new InvalidMessageException(getString(R.string.ConversationActivity_message_is_empty_exclamation));

    if (!isEncryptedConversation && Tag.isTaggable(rawText))
      rawText = Tag.getTaggedMessage(rawText);

    return rawText;
  }

  private void markThreadAsRead() {
    new AsyncTask<Long, Void, Void>() {
      @Override
      protected Void doInBackground(Long... params) {
        DatabaseFactory.getThreadDatabase(ConversationActivity.this).setRead(params[0]);
        MessageNotifier.updateNotification(ConversationActivity.this, masterSecret);
        return null;
      }
    }.execute(threadId);
  }

  private void sendComplete(Recipients recipients, long threadId, boolean refreshFragment) {
    attachmentManager.clear();
    composeText.setText("");

    this.recipients = recipients;
    this.threadId   = threadId;

    ConversationFragment fragment = (ConversationFragment) getSupportFragmentManager()
                                                          .findFragmentById(R.id.fragment_content);
    if (refreshFragment) {
      fragment.reload(recipients, threadId);

      initializeTitleBar();
      initializeSecurity();
    }
    fragment.scrollToBottom();
  }

  private void sendMessage(boolean forcePlaintext, boolean forceSms) {
    try {
      Recipients recipients = getRecipients();

      if (recipients == null)
        throw new RecipientFormattingException("Badly formatted");

      long allocatedThreadId;

      if ((!recipients.isSingleRecipient() || recipients.isEmailRecipient()) && !isMmsEnabled) {
        handleManualMmsRequired();
        return;
      } else if (attachmentManager.isAttachmentPresent() || !recipients.isSingleRecipient() || recipients.isGroupRecipient()) {
        allocatedThreadId = sendMediaMessage(forcePlaintext, forceSms);
      } else {
        allocatedThreadId = sendTextMessage(forcePlaintext, forceSms);
      }

      sendComplete(recipients, allocatedThreadId, allocatedThreadId != this.threadId);
    } catch (RecipientFormattingException ex) {
      Toast.makeText(ConversationActivity.this,
                     R.string.ConversationActivity_recipient_is_not_a_valid_sms_or_email_address_exclamation,
                     Toast.LENGTH_LONG).show();
      Log.w(TAG, ex);
    } catch (InvalidMessageException ex) {
      Toast.makeText(ConversationActivity.this, R.string.ConversationActivity_message_is_empty_exclamation,
                     Toast.LENGTH_SHORT).show();
      Log.w(TAG, ex);
    } catch (MmsException e) {
      Log.w(TAG, e);
    }
  }

  private long sendMediaMessage(boolean forcePlaintext, boolean forceSms)
      throws InvalidMessageException, MmsException
  {
    SlideDeck slideDeck;

    if (attachmentManager.isAttachmentPresent()) {
        slideDeck = attachmentManager.getSlideDeck();
    }
    else                                         {
        slideDeck = new SlideDeck();
    }

    OutgoingMediaMessage outgoingMessage = new OutgoingMediaMessage(this, recipients, slideDeck,
                                                                    getMessage(), distributionType);

    if (isEncryptedConversation && !forcePlaintext) {
      outgoingMessage = new OutgoingSecureMediaMessage(outgoingMessage);
    }

    return MessageSender.send(this, masterSecret, outgoingMessage, threadId, forceSms);
  }

  private long sendTextMessage(boolean forcePlaintext, boolean forceSms)
      throws InvalidMessageException
  {
    OutgoingTextMessage message;

    if (isEncryptedConversation && !forcePlaintext) {
      message = new OutgoingEncryptedMessage(recipients, getMessage());
    } else {
      message = new OutgoingTextMessage(recipients, getMessage());
    }

    Log.w(TAG, "Sending message...");
    return MessageSender.send(ConversationActivity.this, masterSecret, message, threadId, forceSms);
  }


  // Listeners

  private class AttachmentTypeListener implements DialogInterface.OnClickListener {
    @Override
    public void onClick(DialogInterface dialog, int which) {
      addAttachment(attachmentAdapter.buttonToCommand(which));
    }
  }

  private class EmojiToggleListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      InputMethodManager input = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);

      if (emojiDrawer.getVisibility() == View.VISIBLE) {
        input.showSoftInput(composeText, 0);
        emojiDrawer.setVisibility(View.GONE);
      } else {
        input.hideSoftInputFromWindow(composeText.getWindowToken(), 0);
        emojiDrawer.setVisibility(View.VISIBLE);
      }
    }
  }

  private class SendButtonListener implements OnClickListener, TextView.OnEditorActionListener {
    @Override
    public void onClick(View v) {
      sendMessage(false, false);
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
      if (actionId == EditorInfo.IME_ACTION_SEND) {
        sendButton.performClick();
        composeText.clearFocus();
        return true;
      }
      return false;
    }
  }

  private class ComposeKeyPressedListener implements OnKeyListener, OnClickListener, TextWatcher {
    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
      if (event.getAction() == KeyEvent.ACTION_DOWN) {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
          if (TextSecurePreferences.isEnterSendsEnabled(ConversationActivity.this)) {
            sendButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            sendButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            return true;
          }
        }
      }
      return false;
    }

    @Override
    public void onClick(View v) {
      if (emojiDrawer.isOpen()) {
        emojiToggle.performClick();
      }
    }

    @Override
    public void afterTextChanged(Editable s) {
      calculateCharactersRemaining();
    }
    @Override
    public void beforeTextChanged(CharSequence s, int start, int count,int after) {}
    @Override
    public void onTextChanged(CharSequence s, int start, int before,int count) {}
  }

  @Override
  public void setComposeText(String text) {
    this.composeText.setText(text);
  }

  private void setComposeTextHint(String hint){
    if (hint == null) {
      this.composeText.setHint(null);
    } else {
      SpannableString span = new SpannableString(hint);
      span.setSpan(new RelativeSizeSpan(0.8f), 0, hint.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
      this.composeText.setHint(span);
    }
  }

  @Override
  public void onAttachmentChanged() {
    initializeSecurity();
  }
}
