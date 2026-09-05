package com.eurobuddha.maxima.app.portal.wallet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

public final class LocalStore {
    private static final String PREF = "nftwallet_statenft";
    private static final String KEY_COLLECTIONS = "collections";
    private static final String KEY_PENDING = "pending";

    private LocalStore() {}

    public static JSONArray load(Context c) {
        String raw = prefs(c).getString(KEY_COLLECTIONS, "[]");
        try { return new JSONArray(raw); } catch (Exception e) { return new JSONArray(); }
    }

    public static void save(Context c, JSONArray arr) {
        prefs(c).edit().putString(KEY_COLLECTIONS, arr.toString()).apply();
    }

    public static long nextId(Context c) {
        SharedPreferences p = prefs(c);
        long id = p.getLong("next_id", 1);
        p.edit().putLong("next_id", id + 1).apply();
        return id;
    }

    public static void upsert(Context c, JSONObject row) {
        JSONArray arr = load(c);
        long id = row.optLong("id", 0);
        String tokenid = row.optString("tokenid", "");
        boolean done = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject cur = arr.optJSONObject(i);
            if (cur != null && (cur.optLong("id", -1) == id
                    || (!tokenid.isEmpty() && tokenid.equals(cur.optString("tokenid", ""))))) {
                try { arr.put(i, row); } catch (Exception ignored) {}
                done = true;
                break;
            }
        }
        if (!done) arr.put(row);
        save(c, arr);
    }

    public static void setPending(Context c, String coinid, int tip) {
        try {
            JSONObject p = pending(c);
            p.put(coinid, tip);
            prefs(c).edit().putString(KEY_PENDING, p.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static boolean pendingOk(Context c, String coinid, int tip) {
        JSONObject p = pending(c);
        if (!p.has(coinid)) return true;
        return tip - p.optInt(coinid, 0) >= 6;
    }

    public static void prunePending(Context c, int tip) {
        JSONObject p = pending(c);
        JSONArray names = p.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String k = names.optString(i);
            if (tip - p.optInt(k, 0) >= 6) p.remove(k);
        }
        prefs(c).edit().putString(KEY_PENDING, p.toString()).apply();
    }

    public static JSONObject findById(Context c, long id) {
        JSONArray arr = load(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r != null && r.optLong("id", -1) == id) return r;
        }
        return null;
    }

    private static JSONObject pending(Context c) {
        try { return new JSONObject(prefs(c).getString(KEY_PENDING, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
