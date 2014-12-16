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
package com.securecomcode.messaging.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.whispersystems.libaxolotl.InvalidMessageException;
import com.securecomcode.messaging.crypto.MasterCipher;

import java.util.LinkedList;
import java.util.List;

public class DraftDatabase extends Database {

  private static final String TABLE_NAME  = "drafts";
  public  static final String ID          = "_id";
  public  static final String THREAD_ID   = "thread_id";
  public  static final String DRAFT_TYPE  = "type";
  public  static final String DRAFT_VALUE = "value";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" + ID + " INTEGER PRIMARY KEY, " +
                                            THREAD_ID + " INTEGER, " + DRAFT_TYPE + " TEXT, " + DRAFT_VALUE + " TEXT);";

  public static final String[] CREATE_INDEXS = {
    "CREATE INDEX IF NOT EXISTS draft_thread_index ON " + TABLE_NAME + " (" + THREAD_ID + ");",
  };

  public DraftDatabase(Context context, SQLiteOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public void insertDrafts(MasterCipher masterCipher, long threadId, List<Draft> drafts) {
    SQLiteDatabase db    = databaseHelper.getWritableDatabase();

    for (Draft draft : drafts) {
      ContentValues values = new ContentValues(3);
      values.put(THREAD_ID, threadId);
      values.put(DRAFT_TYPE, masterCipher.encryptBody(draft.getType()));
      values.put(DRAFT_VALUE, masterCipher.encryptBody(draft.getValue()));

      db.insert(TABLE_NAME, null, values);
    }
  }

  public void clearDrafts(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, THREAD_ID + " = ?", new String[] {threadId+""});
  }

  public List<Draft> getDrafts(MasterCipher masterCipher, long threadId) {
    SQLiteDatabase db   = databaseHelper.getReadableDatabase();
    List<Draft> results = new LinkedList<Draft>();
    Cursor cursor       = null;

    try {
      cursor = db.query(TABLE_NAME, null, THREAD_ID + " = ?", new String[] {threadId+""}, null, null, null);

      while (cursor != null && cursor.moveToNext()) {
        try {
          String encryptedType  = cursor.getString(cursor.getColumnIndexOrThrow(DRAFT_TYPE));
          String encryptedValue = cursor.getString(cursor.getColumnIndexOrThrow(DRAFT_VALUE));

          results.add(new Draft(masterCipher.decryptBody(encryptedType),
                                masterCipher.decryptBody(encryptedValue)));
        } catch (InvalidMessageException ime) {
          Log.w("DraftDatabase", ime);
        }
      }

      return results;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public static class Draft {
    public static final String TEXT  = "text";
    public static final String IMAGE = "image";
    public static final String VIDEO = "video";
    public static final String AUDIO = "audio";
    public static final String OTHER = "application";

    private final String type;
    private final String value;

    public Draft(String type, String value) {
      this.type  = type;
      this.value = value;
    }

    public String getType() {
      return type;
    }

    public String getValue() {
      return value;
    }
  }
}
