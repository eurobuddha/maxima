package com.eurobuddha.maxima.cloud;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The full, portable identity: everything a clean device needs to BECOME this
 * user — not just the seed (which reproduces the identity keypair/address) but
 * the state the seed can't: contacts, the pinned MLS host, display name, and the
 * Winternitz key-use counter. Serialized as JSON, then password-encrypted by
 * {@link BackupCrypto} before it ever touches disk.
 */
public final class BackupBundle {

    public static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;
    public String phrase = "";
    public String displayName = "";
    public String mls = "";
    /** Contact store, verbatim: store key -> contactToJson value. */
    public Map<String, String> contacts = new LinkedHashMap<>();
    /** Every key-use counter across namespaces: entry name -> count. */
    public Map<String, Integer> keyUses = new LinkedHashMap<>();

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", version);
            o.put("phrase", phrase);
            o.put("displayName", displayName == null ? "" : displayName);
            o.put("mls", mls == null ? "" : mls);
            JSONObject c = new JSONObject();
            for (Map.Entry<String, String> e : contacts.entrySet()) {
                c.put(e.getKey(), e.getValue());
            }
            o.put("contacts", c);
            JSONObject k = new JSONObject();
            for (Map.Entry<String, Integer> e : keyUses.entrySet()) {
                k.put(e.getKey(), (int) e.getValue());
            }
            o.put("keyUses", k);
            return o.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode backup", e);
        }
    }

    public static BackupBundle fromJson(String zJson) {
        try {
            JSONObject o = new JSONObject(zJson);
            BackupBundle b = new BackupBundle();
            b.version = o.optInt("version", 0);
            b.phrase = o.optString("phrase", "");
            b.displayName = o.optString("displayName", "");
            b.mls = o.optString("mls", "");
            JSONObject c = o.optJSONObject("contacts");
            if (c != null) {
                for (Iterator<String> it = c.keys(); it.hasNext(); ) {
                    String key = it.next();
                    b.contacts.put(key, c.getString(key));
                }
            }
            JSONObject k = o.optJSONObject("keyUses");
            if (k != null) {
                for (Iterator<String> it = k.keys(); it.hasNext(); ) {
                    String key = it.next();
                    b.keyUses.put(key, k.getInt(key));
                }
            }
            return b;
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a valid backup", e);
        }
    }
}
