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
 *
 */
package com.securecomcode.messaging.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.util.Log;

import org.whispersystems.textsecure.api.push.ContactTokenDetails;
import org.whispersystems.textsecure.api.util.InvalidNumberException;
import org.whispersystems.textsecure.api.util.PhoneNumberFormatter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TextSecureDirectory {

    private static final int INTRODUCED_CHANGE_FROM_TOKEN_TO_E164_NUMBER = 2;

    private static final String DATABASE_NAME    = "whisper_directory.db";
    private static final int    DATABASE_VERSION = 2;

    private static final String TABLE_NAME   = "directory";
    private static final String ID           = "_id";
    private static final String NUMBER       = "number";
    private static final String REGISTERED   = "registered";
    private static final String RELAY        = "relay";
    private static final String SUPPORTS_SMS = "supports_sms";
    private static final String TIMESTAMP    = "timestamp";
    private static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + "(" + ID + " INTEGER PRIMARY KEY, " +
            NUMBER       + " TEXT UNIQUE, " +
            REGISTERED   + " INTEGER, " +
            RELAY        + " TEXT, " +
            SUPPORTS_SMS + " INTEGER, " +
            TIMESTAMP    + " INTEGER);";

    private static final Object instanceLock = new Object();
    private static volatile TextSecureDirectory instance;

    public static TextSecureDirectory getInstance(Context context) {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new TextSecureDirectory(context);
                }
            }
        }

        return instance;
    }

    private final DatabaseHelper databaseHelper;
    private final Context        context;

    private TextSecureDirectory(Context context) {
        this.context = context;
        this.databaseHelper = new DatabaseHelper(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    public boolean isSmsFallbackSupported(String e164number) {
        SQLiteDatabase db = databaseHelper.getReadableDatabase();
        Cursor cursor = null;

        try {
            cursor = db.query(TABLE_NAME, new String[] {SUPPORTS_SMS}, NUMBER + " = ?",
                    new String[]{e164number}, null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0) == 1;
            } else {
                return false;
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
    }

    public boolean isActiveNumber(String e164number) throws NotInDirectoryException {
        if (e164number == null || e164number.length() == 0) {
            return false;
        }

        SQLiteDatabase db = databaseHelper.getReadableDatabase();
        Cursor cursor = null;

        try {
            cursor = db.query(TABLE_NAME,
                    new String[]{REGISTERED}, NUMBER + " = ?",
                    new String[] {e164number}, null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0) == 1;
            } else {
                throw new NotInDirectoryException();
            }

        } finally {
            if (cursor != null)
                cursor.close();
        }
    }

    public String getRelay(String e164number) {
        SQLiteDatabase database = databaseHelper.getReadableDatabase();
        Cursor         cursor   = null;

        try {
            cursor = database.query(TABLE_NAME, null, NUMBER + " = ?", new String[]{e164number}, null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(RELAY));
            }

            return null;
        } finally {
            if (cursor != null)
                cursor.close();
        }
    }

    public void setNumber(ContactTokenDetails token, boolean active) {
        SQLiteDatabase db     = databaseHelper.getWritableDatabase();
        ContentValues  values = new ContentValues();
        values.put(NUMBER, token.getNumber());
        values.put(RELAY, token.getRelay());
        values.put(REGISTERED, active ? 1 : 0);
        values.put(SUPPORTS_SMS, token.isSupportsSms() ? 1 : 0);
        values.put(TIMESTAMP, System.currentTimeMillis());
        db.replace(TABLE_NAME, null, values);
    }

    public void setNumbers(List<ContactTokenDetails> activeTokens, Collection<String> inactiveTokens) {
        long timestamp    = System.currentTimeMillis();
        SQLiteDatabase db = databaseHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            for (ContactTokenDetails token : activeTokens) {
                Log.w("Directory", "Adding active token: " + token);
                ContentValues values = new ContentValues();
                values.put(NUMBER, token.getNumber());
                values.put(REGISTERED, 1);
                values.put(TIMESTAMP, timestamp);
                values.put(RELAY, token.getRelay());
                values.put(SUPPORTS_SMS, token.isSupportsSms() ? 1 : 0);
                db.replace(TABLE_NAME, null, values);
            }

            for (String token : inactiveTokens) {
                ContentValues values = new ContentValues();
                values.put(NUMBER, token);
                values.put(REGISTERED, 0);
                values.put(TIMESTAMP, timestamp);
                db.replace(TABLE_NAME, null, values);
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public Set<String> getPushEligibleContactNumbers(String localNumber, String countryCode) {
        final Uri uri = Phone.CONTENT_URI;
        final Set<String> results = new HashSet<String>();
        Cursor      cursor  = null;
        Cursor cursorPhoneContacts = null;

        try {
            cursor = context.getContentResolver().query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, new String[]{ContactsContract.CommonDataKinds.Email.DATA}, null, null, null);

            while (cursor != null && cursor.moveToNext()) {
                final String rawNumber = cursor.getString(0);
                if (rawNumber != null) {

                    if (!rawNumber.equalsIgnoreCase(localNumber)) {
                        results.add(rawNumber);
                    }
                }
            }

            if (cursor != null)
                cursor.close();

            final SQLiteDatabase readableDb = databaseHelper.getReadableDatabase();
            if (readableDb != null) {
                cursor = readableDb.query(TABLE_NAME, new String[]{NUMBER},
                        null, null, null, null, null);

                while (cursor != null && cursor.moveToNext()) {
                    if (!cursor.getString(0).equalsIgnoreCase(localNumber)) {
                        results.add(cursor.getString(0));
                    }
                }
            }

        } finally {
            if (cursor != null)
                cursor.close();
        }

        try {
            cursorPhoneContacts = context.getContentResolver().query(uri, new String[]{Phone.NUMBER}, null, null, null);

            while (cursorPhoneContacts != null && cursorPhoneContacts.moveToNext()) {
                final String rawNumber = cursorPhoneContacts.getString(0);
                String e164Number = "";
                if (rawNumber != null) {
                    try {
                        if (countryCode == null) {
                            if(!rawNumber.matches("[a-zA-Z]+")){
                                e164Number = PhoneNumberFormatter.formatNumber(rawNumber, localNumber);
                            }

                        } else {
                            e164Number = PhoneNumberFormatter.formatNumber(rawNumber, "+" + countryCode + org.whispersystems.textsecure.internal.util.Util.NUMBER_FORMAT_HELPER);
                        }
                        if (!e164Number.equalsIgnoreCase(localNumber)) {
                            results.add(e164Number);
                        }
                    } catch (InvalidNumberException e) {
                        Log.w("Directory", "Invalid number: " + rawNumber);
                    }
                }
            }
            return results;
        } finally {
            if (cursorPhoneContacts != null)
                cursorPhoneContacts.close();
        }
    }

    public List<String> getActiveNumbers() {
        final List<String> results = new ArrayList<String>();
        Cursor cursor = null;
        try {
            cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[]{NUMBER},
                    REGISTERED + " = 1", null, null, null, null);

            while (cursor != null && cursor.moveToNext()) {
                results.add(cursor.getString(0));
            }
            return results;
        } finally {
            if (cursor != null)
                cursor.close();
        }
    }

    private class DatabaseHelper extends SQLiteOpenHelper {

        public DatabaseHelper(Context context, String name,
                              SQLiteDatabase.CursorFactory factory,
                              int version)
        {
            super(context, name, factory, version);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(CREATE_TABLE);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < INTRODUCED_CHANGE_FROM_TOKEN_TO_E164_NUMBER) {
                db.execSQL("DROP TABLE directory;");
                db.execSQL("CREATE TABLE directory ( _id INTEGER PRIMARY KEY, " +
                        "number TEXT UNIQUE, " +
                        "registered INTEGER, " +
                        "relay TEXT, " +
                        "supports_sms INTEGER, " +
                        "timestamp INTEGER);");
            }
        }
    }

}
