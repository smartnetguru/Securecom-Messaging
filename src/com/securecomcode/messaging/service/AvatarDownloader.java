package com.securecomcode.messaging.service;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;

import com.securecomcode.messaging.database.DatabaseFactory;
import com.securecomcode.messaging.database.GroupDatabase;
import com.securecomcode.messaging.push.PushServiceSocketFactory;
import com.securecomcode.messaging.recipients.Recipient;
import com.securecomcode.messaging.recipients.RecipientFactory;
import com.securecomcode.messaging.recipients.RecipientFormattingException;
import com.securecomcode.messaging.util.BitmapDecodingException;
import com.securecomcode.messaging.util.BitmapUtil;
import com.securecomcode.messaging.util.GroupUtil;
import com.securecomcode.messaging.database.PartDatabase;
import org.whispersystems.textsecure.crypto.AttachmentCipherInputStream;
import org.whispersystems.textsecure.crypto.InvalidMessageException;
import org.whispersystems.textsecure.crypto.MasterSecret;
import org.whispersystems.textsecure.push.PushServiceSocket;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class AvatarDownloader {

  private final Context context;

  public AvatarDownloader(Context context) {
    this.context = context.getApplicationContext();
  }

  public void process(MasterSecret masterSecret, Intent intent) {
    try {
      if (!SendReceiveService.DOWNLOAD_AVATAR_ACTION.equals(intent.getAction()))
        return;

      byte[]                    groupId  = intent.getByteArrayExtra("group_id");
      GroupDatabase             database = DatabaseFactory.getGroupDatabase(context);
      GroupDatabase.GroupRecord record   = database.getGroup(groupId);

      if (record != null) {
        long        avatarId           = record.getAvatarId();
        byte[]      key                = record.getAvatarKey();
        String      relay              = record.getRelay();

        if (avatarId == -1 || key == null) {
          return;
        }

        File        attachment         = downloadAttachment(relay, avatarId);
        InputStream scaleInputStream   = new AttachmentCipherInputStream(attachment, key);
        InputStream measureInputStream = new AttachmentCipherInputStream(attachment, key);
        Bitmap      avatar             = BitmapUtil.createScaledBitmap(measureInputStream, scaleInputStream, 500, 500);

        database.updateAvatar(groupId, avatar);

        try {
          Recipient groupRecipient = RecipientFactory.getRecipientsFromString(context, GroupUtil.getEncodedId(groupId), true)
                                                     .getPrimaryRecipient();
          groupRecipient.setContactPhoto(avatar);
        } catch (RecipientFormattingException e) {
          Log.w("AvatarDownloader", e);
        }

//        avatar.recycle();
        attachment.delete();
      }
    } catch (IOException e) {
      Log.w("AvatarDownloader", e);
    } catch (InvalidMessageException e) {
      Log.w("AvatarDownloader", e);
    } catch (BitmapDecodingException e) {
      Log.w("AvatarDownloader", e);
    }
  }

  private File downloadAttachment(String relay, long contentLocation) throws IOException {
    PushServiceSocket socket = PushServiceSocketFactory.create(context);
    return socket.retrieveAttachment(relay, contentLocation);
  }

}
