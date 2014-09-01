package com.securecomcode.messaging.protocol;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.securecomcode.messaging.ApplicationPreferencesActivity;

public class Tag {

  public static final String WHITESPACE_TAG = "             ";

  public static boolean isTaggable(String message) {
    return message.matches(".*[^\\s].*")                                       &&
           message.replaceAll("\\s+$", "").length() + WHITESPACE_TAG.length() <= 158;
  }

  public static boolean isTagged(String message) {
    return message != null && message.matches(".*[^\\s]" + WHITESPACE_TAG + "$");
  }

  public static String getTaggedMessage(String message) {
    return message.replaceAll("\\s+$", "") + WHITESPACE_TAG;
  }

  public static String stripTag(String message) {
    if (isTagged(message))
      return message.substring(0, message.length() - WHITESPACE_TAG.length());

    return message;
  }

}
