package com.eurobuddha.maxima.app.portal.wallet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A multi-batch Distribute job. Persisted to SharedPreferences so a job that spans several
 * blocks (each batch waits for the previous batch's change coin to confirm) survives an app
 * restart — mirrors the web utxoWallet's keypair "distributeJob".
 */
public class DistributeJob {

    private static final String PREFS = "nftwallet_distribute";
    private static final String KEY = "job";

    public String tokenid;
    public String tokenName;
    public String per;                 // amount to each address
    public List<String> remaining = new ArrayList<>();  // addresses not yet paid
    public int batchesDone = 0;
    public int maxBatches = 4;
    public String expectedChangeCoinId = ""; // exact coinid of the last batch's change output (authoritative)
    public String nextChangeAddr = "";     // change address of the last posted batch (fallback match)
    public String expectedChangeAmt = "";  // its amount (fallback match)
    public boolean waiting = false;        // a batch is posted, awaiting its change coin
    public int atBlock = 0;                // chain height when the last batch was posted
    public int retryCount = 0;             // failed attempts at the current batch
    public String currentInternalId = "";  // history row of the in-flight/last batch

    /** Serialize the whole job state for persistence. */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("tokenid", tokenid);
            o.put("tokenName", tokenName);
            o.put("per", per);
            o.put("remaining", new JSONArray(remaining));
            o.put("batchesDone", batchesDone);
            o.put("maxBatches", maxBatches);
            o.put("expectedChangeCoinId", expectedChangeCoinId);
            o.put("nextChangeAddr", nextChangeAddr);
            o.put("expectedChangeAmt", expectedChangeAmt);
            o.put("waiting", waiting);
            o.put("atBlock", atBlock);
            o.put("retryCount", retryCount);
            o.put("currentInternalId", currentInternalId);
        } catch (org.json.JSONException ignored) {}
        return o;
    }

    /** Rebuild a job from its persisted JSON (with safe defaults for missing keys). */
    public static DistributeJob fromJson(JSONObject o) {
        DistributeJob j = new DistributeJob();
        j.tokenid = o.optString("tokenid", "0x00");
        j.tokenName = o.optString("tokenName", "Minima");
        j.per = o.optString("per", "0");
        JSONArray arr = o.optJSONArray("remaining");
        if (arr != null) for (int i = 0; i < arr.length(); i++) j.remaining.add(arr.optString(i, ""));
        j.batchesDone = o.optInt("batchesDone", 0);
        j.maxBatches = o.optInt("maxBatches", 4);
        j.expectedChangeCoinId = o.optString("expectedChangeCoinId", "");
        j.nextChangeAddr = o.optString("nextChangeAddr", "");
        j.expectedChangeAmt = o.optString("expectedChangeAmt", "");
        j.waiting = o.optBoolean("waiting", false);
        j.atBlock = o.optInt("atBlock", 0);
        j.retryCount = o.optInt("retryCount", 0);
        j.currentInternalId = o.optString("currentInternalId", "");
        return j;
    }

    /** Persist this job to SharedPreferences (survives an app restart mid-distribution). */
    public void save(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(KEY, toJson().toString()).apply();
    }

    /** Load a persisted job, or null if none/corrupt. */
    public static DistributeJob load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = p.getString(KEY, "");
        if (s == null || s.isEmpty()) return null;
        try { return fromJson(new JSONObject(s)); } catch (Exception e) { return null; }
    }

    /** Drop the persisted job (on completion or abort). */
    public static void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
