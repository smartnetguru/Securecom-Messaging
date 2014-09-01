package com.securecomcode.messaging.util;

import android.app.Activity;
import android.content.Intent;
import android.preference.PreferenceManager;

import com.securecomcode.messaging.ApplicationPreferencesActivity;
import com.securecomcode.messaging.ConversationActivity;
import com.securecomcode.messaging.ConversationListActivity;
import com.securecomcode.messaging.R;

public class DynamicTheme {

  private int currentTheme;

  public void onCreate(Activity activity) {
    currentTheme = getSelectedTheme(activity);
    activity.setTheme(currentTheme);
  }

  public void onResume(Activity activity) {
    if (currentTheme != getSelectedTheme(activity)) {
      Intent intent = activity.getIntent();
      activity.finish();
      OverridePendingTransition.invoke(activity);
      activity.startActivity(intent);
      OverridePendingTransition.invoke(activity);
    }
  }

  private static int getSelectedTheme(Activity activity) {
    String theme = TextSecurePreferences.getTheme(activity);

    if (theme.equals("light")) {
      if (activity instanceof ConversationListActivity) return R.style.TextSecure_LightTheme_NavigationDrawer;
      else if (activity instanceof ConversationActivity) return R.style.TextSecure_LightTheme_ConversationActivity;
      else                                              return R.style.TextSecure_LightTheme;
    } else if (theme.equals("dark")) {
      if (activity instanceof ConversationListActivity) return R.style.TextSecure_DarkTheme_NavigationDrawer;
      else if (activity instanceof ConversationActivity) return R.style.TextSecure_DarkTheme_ConversationActivity;
      else                                              return R.style.TextSecure_DarkTheme;
    }

    return R.style.TextSecure_LightTheme;
  }

  private static final class OverridePendingTransition {
    static void invoke(Activity activity) {
      activity.overridePendingTransition(0, 0);
    }
  }
}
