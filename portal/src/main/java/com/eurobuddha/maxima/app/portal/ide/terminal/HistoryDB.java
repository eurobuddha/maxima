package com.eurobuddha.maxima.app.portal.ide.terminal;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Persistent command history + named favorites for the terminal. */
public class HistoryDB extends SQLiteOpenHelper {

    public static final int DATABASE_VERSION = 1;
    public static final String DATABASE_NAME = "terminalide.db";

    private static final int MAX_HISTORY = 500;

    private final SQLiteDatabase mDB;

    public HistoryDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        mDB = getWritableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table if not exists history ("
                + "_id integer primary key,"
                + "cmd text not null,"
                + "date integer not null)");
        db.execSQL("create table if not exists favorites ("
                + "_id integer primary key,"
                + "name text not null,"
                + "cmd text not null)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS history");
        db.execSQL("DROP TABLE IF EXISTS favorites");
        onCreate(db);
    }

    public void addHistory(String cmd) {
        // De-dup consecutive repeats.
        List<String> last = getHistory(1);
        if (!last.isEmpty() && last.get(0).equals(cmd)) return;
        ContentValues v = new ContentValues();
        v.put("cmd", cmd);
        v.put("date", System.currentTimeMillis());
        mDB.insert("history", null, v);
        mDB.execSQL("DELETE FROM history WHERE _id NOT IN "
                + "(SELECT _id FROM history ORDER BY _id DESC LIMIT " + MAX_HISTORY + ")");
    }

    /** Most-recent-first. */
    public List<String> getHistory(int limit) {
        List<String> out = new ArrayList<>();
        Cursor c = mDB.query("history", new String[]{"cmd"}, null, null, null, null,
                "_id DESC", "" + limit);
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    public void addFavorite(String name, String cmd) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("cmd", cmd);
        mDB.insert("favorites", null, v);
    }

    public void deleteFavorite(long id) {
        mDB.delete("favorites", "_id=?", new String[]{"" + id});
    }

    /** Returns rows as {id, name, cmd}. */
    public List<String[]> getFavorites() {
        List<String[]> out = new ArrayList<>();
        Cursor c = mDB.query("favorites", new String[]{"_id", "name", "cmd"}, null, null,
                null, null, "name ASC", null);
        try {
            while (c.moveToNext()) out.add(new String[]{"" + c.getLong(0), c.getString(1), c.getString(2)});
        } finally {
            c.close();
        }
        return out;
    }
}
