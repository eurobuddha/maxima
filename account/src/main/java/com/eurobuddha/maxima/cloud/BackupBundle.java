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

    // ---- the PORTABLE ACCOUNT block (optional; format 1). Everything an account needs to
    //      come back on another host with the same identity: the paired devices, the host
    //      settings, every collection of the node and chat stores, and the node's logs.
    //      Readers that predate it (the phone app's v1 reader) ignore the key. ----
    public static final int ACCOUNT_FORMAT = 1;
    /** 0 when absent. */
    public int accountFormat;
    /** The paired-device registry, as the raw JSON text of devices.json (or ""). */
    public String devicesJson = "";
    /** cloud-settings.properties as key -> value. */
    public Map<String, String> settings = new LinkedHashMap<>();
    /** store name ("node", "chat") -> collection -> key -> value. */
    public Map<String, Map<String, Map<String, String>>> stores = new LinkedHashMap<>();
    /** store name -> log name -> lines. */
    public Map<String, Map<String, java.util.List<String>>> logs = new LinkedHashMap<>();

    public boolean hasAccount() {
        return accountFormat > 0;
    }

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
            if (accountFormat > 0) {
                JSONObject a = new JSONObject();
                a.put("format", accountFormat);
                a.put("devices", devicesJson == null ? "" : devicesJson);
                JSONObject st = new JSONObject();
                for (Map.Entry<String, String> e : settings.entrySet()) {
                    st.put(e.getKey(), e.getValue());
                }
                a.put("settings", st);
                JSONObject stores0 = new JSONObject();
                for (Map.Entry<String, Map<String, Map<String, String>>> s : stores.entrySet()) {
                    JSONObject cols = new JSONObject();
                    for (Map.Entry<String, Map<String, String>> col : s.getValue().entrySet()) {
                        JSONObject kv = new JSONObject();
                        for (Map.Entry<String, String> e : col.getValue().entrySet()) {
                            kv.put(e.getKey(), e.getValue());
                        }
                        cols.put(col.getKey(), kv);
                    }
                    stores0.put(s.getKey(), cols);
                }
                a.put("stores", stores0);
                JSONObject logs0 = new JSONObject();
                for (Map.Entry<String, Map<String, java.util.List<String>>> s : logs.entrySet()) {
                    JSONObject ls = new JSONObject();
                    for (Map.Entry<String, java.util.List<String>> l : s.getValue().entrySet()) {
                        ls.put(l.getKey(), new org.json.JSONArray(l.getValue()));
                    }
                    logs0.put(s.getKey(), ls);
                }
                a.put("logs", logs0);
                o.put("account", a);
            }
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
            JSONObject a = o.optJSONObject("account");
            if (a != null) {
                b.accountFormat = a.optInt("format", 0);
                b.devicesJson = a.optString("devices", "");
                JSONObject st = a.optJSONObject("settings");
                if (st != null) {
                    for (Iterator<String> it = st.keys(); it.hasNext(); ) {
                        String key = it.next();
                        b.settings.put(key, st.getString(key));
                    }
                }
                JSONObject stores0 = a.optJSONObject("stores");
                if (stores0 != null) {
                    for (Iterator<String> si = stores0.keys(); si.hasNext(); ) {
                        String sname = si.next();
                        JSONObject cols = stores0.getJSONObject(sname);
                        Map<String, Map<String, String>> colMap = new LinkedHashMap<>();
                        for (Iterator<String> ci = cols.keys(); ci.hasNext(); ) {
                            String cname = ci.next();
                            JSONObject kv = cols.getJSONObject(cname);
                            Map<String, String> m = new LinkedHashMap<>();
                            for (Iterator<String> ki = kv.keys(); ki.hasNext(); ) {
                                String key = ki.next();
                                m.put(key, kv.getString(key));
                            }
                            colMap.put(cname, m);
                        }
                        b.stores.put(sname, colMap);
                    }
                }
                JSONObject logs0 = a.optJSONObject("logs");
                if (logs0 != null) {
                    for (Iterator<String> si = logs0.keys(); si.hasNext(); ) {
                        String sname = si.next();
                        JSONObject ls = logs0.getJSONObject(sname);
                        Map<String, java.util.List<String>> lm = new LinkedHashMap<>();
                        for (Iterator<String> li = ls.keys(); li.hasNext(); ) {
                            String lname = li.next();
                            org.json.JSONArray arr = ls.getJSONArray(lname);
                            java.util.List<String> lines = new java.util.ArrayList<>();
                            for (int i = 0; i < arr.length(); i++) {
                                lines.add(arr.getString(i));
                            }
                            lm.put(lname, lines);
                        }
                        b.logs.put(sname, lm);
                    }
                }
            }
            return b;
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a valid backup", e);
        }
    }
}
