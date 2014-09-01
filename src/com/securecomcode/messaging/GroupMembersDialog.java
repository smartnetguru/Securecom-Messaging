package com.securecomcode.messaging;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.Recipients;
import com.securecomcode.messaging.util.GroupUtil;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

public class GroupMembersDialog extends AsyncTask<Void, Void, Recipients> {

  private final Recipients recipients;
  private final Context    context;

  private ProgressDialog progress = null;

  public GroupMembersDialog(Context context, Recipients recipients) {
    this.recipients = recipients;
    this.context    = context;
  }

  @Override
  public void onPreExecute() {
    progress = ProgressDialog.show(context, "Members...", "Members...", true, false);
  }

  @Override
  protected Recipients doInBackground(Void... params) {
    try {
      String groupId = recipients.getPrimaryRecipient().getNumber();
      return DatabaseFactory.getGroupDatabase(context)
                            .getGroupMembers(GroupUtil.getDecodedId(groupId), true);
    } catch (IOException e) {
      Log.w("ConverstionActivity", e);
      return new Recipients(new LinkedList<Recipient>());
    }
  }

  @Override
  public void onPostExecute(Recipients members) {
    if (progress != null) {
      progress.dismiss();
    }

    List<String> recipientStrings = new LinkedList<String>();

    for (Recipient recipient : members.getRecipientsList()) {
      recipientStrings.add(recipient.toShortString());
    }

    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle(R.string.ConversationActivity_group_conversation_recipients);
    builder.setIcon(R.drawable.ic_menu_groups_holo_dark);
    builder.setCancelable(true);
    builder.setItems(recipientStrings.toArray(new String[]{}), null);
    builder.setPositiveButton(android.R.string.ok, null);
    builder.show();
  }

  public void display() {
    if (recipients.isGroupRecipient()) execute();
    else                               onPostExecute(recipients);
  }
}
