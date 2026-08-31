package com.eurobuddha.maxima.app.portal;

import android.content.Context;

import com.eurobuddha.maxima.core.chat.ChatPay;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A client-side transaction ledger for the watch-only portal. The account's real wallet lives on
 * the VPS and the portal has no chain view, so the History tab is built from the events THIS device
 * has seen: outgoing sends (walletsent / walletfail push events from the Wallet tab) and incoming
 * payments (ChatPay messages pushed into a conversation). It's an honest record of activity while
 * paired — not a full chain history — persisted locally (newest 200) and deduped by txid+pid so a
 * retried push never double-lists an entry.
 */
public final class WalletLedger {

    public static final String SENT = "sent";
    public static final String RECEIVED = "received";
    public static final String FAILED = "failed";

    private static final int CAP = 200;
    private static final Object LOCK = new Object();
    private static List<JSONObject> sEntries;   // in-memory cache, newest-last on disk

    public static final class Entry {
        public String direction;   // SENT / RECEIVED / FAILED
        public String amount;
        public String token;
        public String counterparty;   // name or address (full)
        public String txid;
        public String memo;
        public long time;
        public String error;         // for FAILED
    }

    private WalletLedger() {
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "wallet-ledger.json");
    }

    private static List<JSONObject> load(Context c) {
        if (sEntries != null) {
            return sEntries;
        }
        List<JSONObject> list = new ArrayList<>();
        try {
            File f = file(c);
            if (f.exists()) {
                byte[] raw = new byte[(int) f.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int off = 0, n;
                    while (off < raw.length && (n = in.read(raw, off, raw.length - off)) > 0) {
                        off += n;
                    }
                }
                Object o = new JSONParser().parse(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
                if (o instanceof JSONArray) {
                    for (Object e : (JSONArray) o) {
                        if (e instanceof JSONObject) {
                            list.add((JSONObject) e);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        sEntries = list;
        return list;
    }

    private static void save(Context c, List<JSONObject> list) {
        try {
            JSONArray arr = new JSONArray();
            arr.addAll(list);
            File f = file(c);
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                // best-effort fallback
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    out.write(arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String key(String direction, String txid, String pid) {
        return direction + "|" + (txid == null ? "" : txid) + "|" + (pid == null ? "" : pid);
    }

    private static void add(Context c, JSONObject entry) {
        synchronized (LOCK) {
            List<JSONObject> list = load(c);
            String k = String.valueOf(entry.get("_k"));
            for (JSONObject e : list) {
                if (k.equals(String.valueOf(e.get("_k")))) {
                    return;   // dedup: same event pushed twice
                }
            }
            list.add(entry);
            while (list.size() > CAP) {
                list.remove(0);
            }
            save(c, list);
        }
    }

    /** From the central push handler (see CloudSession.installPush). */
    public static void onEvent(Context c, JSONObject ev) {
        String type = String.valueOf(ev.get("type"));
        if ("walletsent".equals(type)) {
            JSONObject e = new JSONObject();
            e.put("_k", key(SENT, str(ev, "txid"), str(ev, "pid")));
            e.put("direction", SENT);
            e.put("amount", str(ev, "amount"));
            e.put("token", "MINIMA");
            e.put("counterparty", str(ev, "to"));
            e.put("txid", str(ev, "txid"));
            e.put("time", now(ev));
            add(c, e);
        } else if ("walletfail".equals(type)) {
            JSONObject e = new JSONObject();
            e.put("_k", key(FAILED, "", str(ev, "pid")));
            e.put("direction", FAILED);
            e.put("counterparty", str(ev, "to"));
            e.put("error", str(ev, "error"));
            e.put("time", now(ev));
            add(c, e);
        } else if ("message".equals(type)) {
            // An inbound payment message (someone sent US MINIMA) — record as received.
            String body = str(ev, "body");
            if (ChatPay.isPayment(body)) {
                JSONObject e = new JSONObject();
                String txid = ChatPay.txid(body);
                e.put("_k", key(RECEIVED, txid, str(ev, "id")));
                e.put("direction", RECEIVED);
                e.put("amount", ChatPay.amount(body));
                e.put("token", ChatPay.tokenName(body));
                String name = str(ev, "name");
                e.put("counterparty", name.isEmpty() ? str(ev, "peer") : name);
                e.put("txid", txid);
                e.put("memo", ChatPay.memo(body));
                e.put("time", now(ev));
                add(c, e);
            }
        }
    }

    /** Newest-first snapshot for the History UI. */
    public static List<Entry> entries(Context c) {
        List<Entry> out = new ArrayList<>();
        synchronized (LOCK) {
            List<JSONObject> list = load(c);
            for (JSONObject j : list) {
                Entry e = new Entry();
                e.direction = str(j, "direction");
                e.amount = str(j, "amount");
                e.token = str(j, "token");
                e.counterparty = str(j, "counterparty");
                e.txid = str(j, "txid");
                e.memo = str(j, "memo");
                e.error = str(j, "error");
                Object t = j.get("time");
                e.time = t instanceof Number ? ((Number) t).longValue() : 0L;
                out.add(e);
            }
        }
        Collections.reverse(out);   // stored oldest-last → newest-first
        return out;
    }

    private static long now(JSONObject ev) {
        Object t = ev.get("time");
        return t instanceof Number ? ((Number) t).longValue() : System.currentTimeMillis();
    }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
