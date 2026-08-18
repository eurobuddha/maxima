package com.eurobuddha.maxima.desktop.wallet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * A local, append-only record of payments made or received through the desktop
 * wallet — the counterpart of the phone's {@code WalletLedger}. The wallet's key
 * index has no node history, so this local ledger is the source of truth for the
 * History tab. Bounded, newest-first, stored as one JSON file under the data dir.
 */
public final class DesktopWalletLedger {

    private static final int CAP = 200;
    private final File file;

    public DesktopWalletLedger(File walletDir) {
        walletDir.mkdirs();
        file = new File(walletDir, "ledger.json");
    }

    public static final class Row {
        public long time;
        public boolean sent;
        public String amount = "";
        public String token = "MINIMA";
        public String who = "";
        public String txid = "";
        public String tokenid = "0x00";
    }

    /** Record one payment. Best-effort: a ledger write must never break a send. */
    public synchronized void add(boolean sent, String amount, String token, String who,
                                 String txid, String tokenid) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("s", sent);
            o.put("a", amount == null ? "" : amount);
            o.put("tk", token == null ? "MINIMA" : token);
            o.put("w", who == null ? "" : who);
            o.put("x", txid == null ? "" : txid);
            o.put("ti", tokenid == null || tokenid.isEmpty() ? "0x00" : tokenid);
            JSONArray prev = load();
            JSONArray next = new JSONArray();
            next.put(o);
            for (int i = 0; i < prev.length() && next.length() < CAP; i++) {
                next.put(prev.get(i));
            }
            Files.write(file.toPath(), next.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public synchronized List<Row> list() {
        List<Row> out = new ArrayList<>();
        try {
            JSONArray arr = load();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Row r = new Row();
                r.time = o.optLong("t");
                r.sent = o.optBoolean("s");
                r.amount = o.optString("a", "");
                r.token = o.optString("tk", "MINIMA");
                r.who = o.optString("w", "");
                r.txid = o.optString("x", "");
                r.tokenid = o.optString("ti", "0x00");
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private JSONArray load() {
        try {
            if (file.exists()) {
                return new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        return new JSONArray();
    }
}
