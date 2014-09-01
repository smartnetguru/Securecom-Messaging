package com.securecomcode.messaging.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.util.Pair;

import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.EncryptingSmsDatabase;
import com.securecomcode.messaging.database.GroupDatabase;
import com.securecomcode.messaging.notifications.MessageNotifier;
import com.securecomcode.messaging.sms.IncomingGroupMessage;
import com.securecomcode.messaging.sms.IncomingTextMessage;
import org.whispersystems.textsecure.crypto.MasterSecret;
import org.whispersystems.textsecure.push.IncomingPushMessage;
import org.whispersystems.textsecure.util.Base64;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static com.securecomcode.messaging.database.GroupDatabase.GroupRecord;
import static org.whispersystems.textsecure.push.PushMessageProtos.PushMessageContent;
import static org.whispersystems.textsecure.push.PushMessageProtos.PushMessageContent.GroupContext;

public class GroupReceiver {

  private static final String TAG =  GroupReceiver.class.getSimpleName();;
  private final Context context;

  public GroupReceiver(Context context) {
    this.context = context.getApplicationContext();
  }

  public void process(MasterSecret masterSecret,
                      IncomingPushMessage message,
                      PushMessageContent messageContent,
                      boolean secure)
  {
    if (!messageContent.getGroup().hasId()) {
      Log.w(TAG, "Received group message with no id! Ignoring...");
      return;
    }

    if (!secure) {
      Log.w(TAG, "Received insecure group push action! Ignoring...");
      return;
    }

    GroupDatabase database = DatabaseFactory.getGroupDatabase(context);
    GroupContext  group    = messageContent.getGroup();
    byte[]        id       = group.getId().toByteArray();
    int           type     = group.getType().getNumber();
    GroupRecord   record   = database.getGroup(id);

    if (record != null && type == GroupContext.Type.UPDATE_VALUE) {
      handleGroupUpdate(masterSecret, message, group, record);
    } else if (record == null && type == GroupContext.Type.UPDATE_VALUE) {
      handleGroupCreate(masterSecret, message, group);
    } else if (record != null && type == GroupContext.Type.QUIT_VALUE) {
      handleGroupLeave(masterSecret, message, group, record);
    } else if (type == GroupContext.Type.UNKNOWN_VALUE) {
      Log.w(TAG, "Received unknown type, ignoring...");
    }
  }

  private void handleGroupCreate(MasterSecret masterSecret,
                                 IncomingPushMessage message,
                                 GroupContext group)
  {
    GroupDatabase database = DatabaseFactory.getGroupDatabase(context);
    byte[]        id       = group.getId().toByteArray();

    database.create(id, group.getName(), group.getMembersList(),
                    group.getAvatar(), message.getRelay());

    storeMessage(masterSecret, message, group);
  }

  private void handleGroupUpdate(MasterSecret masterSecret,
                                 IncomingPushMessage message,
                                 GroupContext group,
                                 GroupRecord groupRecord)
  {

    GroupDatabase database = DatabaseFactory.getGroupDatabase(context);
    byte[]        id       = group.getId().toByteArray();

    Set<String> recordMembers = new HashSet<String>(groupRecord.getMembers());
    Set<String> messageMembers = new HashSet<String>(group.getMembersList());

    Set<String> addedMembers = new HashSet<String>(messageMembers);
    addedMembers.removeAll(recordMembers);

    Set<String> missingMembers = new HashSet<String>(recordMembers);
    missingMembers.removeAll(messageMembers);

    if (addedMembers.size() > 0) {
      Set<String> unionMembers = new HashSet<String>(recordMembers);
      unionMembers.addAll(messageMembers);
      database.updateMembers(id, new LinkedList<String>(unionMembers));

      group = group.toBuilder().clearMembers().addAllMembers(addedMembers).build();
    } else {
      group = group.toBuilder().clearMembers().build();
    }

    if (missingMembers.size() > 0) {
      // TODO We should tell added and missing about each-other.
    }

    if (group.hasName() || group.hasAvatar()) {
      database.update(id, group.getName(), group.getAvatar());
    }

    if (group.hasName() && group.getName() != null && group.getName().equals(groupRecord.getTitle())) {
      group = group.toBuilder().clearName().build();
    }

    if (!groupRecord.isActive()) database.setActive(id, true);
    storeMessage(masterSecret, message, group);
  }

  private void handleGroupLeave(MasterSecret        masterSecret,
                                IncomingPushMessage message,
                                GroupContext        group,
                                GroupRecord         record)
  {
    GroupDatabase database = DatabaseFactory.getGroupDatabase(context);
    byte[]        id       = group.getId().toByteArray();
    List<String>  members  = record.getMembers();

    if (members.contains(message.getSource())) {
      database.remove(id, message.getSource());

      storeMessage(masterSecret, message, group);
    }
  }


  private void storeMessage(MasterSecret masterSecret, IncomingPushMessage message, GroupContext group) {
    if (group.hasAvatar()) {
      Intent intent = new Intent(context, SendReceiveService.class);
      intent.setAction(SendReceiveService.DOWNLOAD_AVATAR_ACTION);
      intent.putExtra("group_id", group.getId().toByteArray());
      context.startService(intent);
    }

    EncryptingSmsDatabase smsDatabase  = DatabaseFactory.getEncryptingSmsDatabase(context);
    String                body         = Base64.encodeBytes(group.toByteArray());
    IncomingTextMessage   incoming     = new IncomingTextMessage(message, body, group);
    IncomingGroupMessage  groupMessage = new IncomingGroupMessage(incoming, group, body);

    Pair<Long, Long> messageAndThreadId = smsDatabase.insertMessageInbox(masterSecret, groupMessage);
    smsDatabase.updateMessageBody(masterSecret, messageAndThreadId.first, body);

    MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
  }

}
