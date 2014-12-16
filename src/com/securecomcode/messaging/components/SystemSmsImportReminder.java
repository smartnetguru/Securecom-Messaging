package com.securecomcode.messaging.components;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.View.OnClickListener;

import com.securecomcode.messaging.ConversationListActivity;
import com.securecomcode.messaging.DatabaseMigrationActivity;
import com.securecomcode.messaging.R;
import com.securecomcode.messaging.service.ApplicationMigrationService;
import com.securecomcode.messaging.crypto.MasterSecret;

public class SystemSmsImportReminder extends Reminder {

  public SystemSmsImportReminder(final Context context, final MasterSecret masterSecret) {
    super(R.drawable.sms_system_import_icon,
          R.string.reminder_header_sms_import_title,
          R.string.reminder_header_sms_import_text);

    final OnClickListener okListener = new OnClickListener() {
      @Override
      public void onClick(View v) {
        Intent intent = new Intent(context, ApplicationMigrationService.class);
        intent.setAction(ApplicationMigrationService.MIGRATE_DATABASE);
        intent.putExtra("master_secret", masterSecret);
        context.startService(intent);

        Intent nextIntent = new Intent(context, ConversationListActivity.class);
        intent.putExtra("master_secret", masterSecret);

        Intent activityIntent = new Intent(context, DatabaseMigrationActivity.class);
        activityIntent.putExtra("master_secret", masterSecret);
        activityIntent.putExtra("next_intent", nextIntent);
        context.startActivity(activityIntent);
      }
    };
    final OnClickListener cancelListener = new OnClickListener() {
      @Override
      public void onClick(View v) {
        ApplicationMigrationService.setDatabaseImported(context);
      }
    };
    setOkListener(okListener);
    setCancelListener(cancelListener);
  }

  public static boolean isEligible(Context context) {
    return !ApplicationMigrationService.isDatabaseImported(context);
  }
}
