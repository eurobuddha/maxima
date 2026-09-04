package com.eurobuddha.maxima.app.portal.ide.ide;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Local library of KISS VM scripts. */
public class ScriptDB extends SQLiteOpenHelper {

    public static final int DATABASE_VERSION = 2;
    public static final String DATABASE_NAME = "scripts.db";

    public static class Script {
        public long id;
        public String name;
        public String source;
        public long modified;
        public String address;   // deployed address, or ""
        // Persisted runscript test inputs so contract iteration survives editor restarts.
        public String testState = "";
        public String testPrevState = "";
        public String testGlobals = "";
        public String testSignatures = "";
    }

    private final SQLiteDatabase mDB;

    public ScriptDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        mDB = getWritableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table if not exists scripts ("
                + "_id integer primary key,"
                + "name text not null,"
                + "source text not null,"
                + "modified integer not null,"
                + "address text not null default '',"
                + "teststate text not null default '',"
                + "testprev text not null default '',"
                + "testglobals text not null default '',"
                + "testsigs text not null default '')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Never drop user scripts; additive migrations only.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE scripts ADD COLUMN teststate text not null default ''");
            db.execSQL("ALTER TABLE scripts ADD COLUMN testprev text not null default ''");
            db.execSQL("ALTER TABLE scripts ADD COLUMN testglobals text not null default ''");
            db.execSQL("ALTER TABLE scripts ADD COLUMN testsigs text not null default ''");
        }
    }

    public void saveTestInputs(long id, String state, String prev, String globals, String sigs) {
        ContentValues v = new ContentValues();
        v.put("teststate", state);
        v.put("testprev", prev);
        v.put("testglobals", globals);
        v.put("testsigs", sigs);
        mDB.update("scripts", v, "_id=?", new String[]{"" + id});
    }

    public long insert(String name, String source) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("source", source);
        v.put("modified", System.currentTimeMillis());
        v.put("address", "");
        return mDB.insert("scripts", null, v);
    }

    public void update(long id, String name, String source) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("source", source);
        v.put("modified", System.currentTimeMillis());
        mDB.update("scripts", v, "_id=?", new String[]{"" + id});
    }

    public void setAddress(long id, String address) {
        ContentValues v = new ContentValues();
        v.put("address", address);
        mDB.update("scripts", v, "_id=?", new String[]{"" + id});
    }

    public void delete(long id) {
        mDB.delete("scripts", "_id=?", new String[]{"" + id});
    }

    public Script get(long id) {
        Cursor c = mDB.query("scripts", null, "_id=?", new String[]{"" + id}, null, null, null);
        try {
            if (c.moveToFirst()) return fromCursor(c);
        } finally {
            c.close();
        }
        return null;
    }

    public List<Script> getAll() {
        List<Script> out = new ArrayList<>();
        Cursor c = mDB.query("scripts", null, null, null, null, null, "modified DESC");
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    private Script fromCursor(Cursor c) {
        Script s = new Script();
        s.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        s.name = c.getString(c.getColumnIndexOrThrow("name"));
        s.source = c.getString(c.getColumnIndexOrThrow("source"));
        s.modified = c.getLong(c.getColumnIndexOrThrow("modified"));
        s.address = c.getString(c.getColumnIndexOrThrow("address"));
        s.testState = c.getString(c.getColumnIndexOrThrow("teststate"));
        s.testPrevState = c.getString(c.getColumnIndexOrThrow("testprev"));
        s.testGlobals = c.getString(c.getColumnIndexOrThrow("testglobals"));
        s.testSignatures = c.getString(c.getColumnIndexOrThrow("testsigs"));
        return s;
    }
}
