package com.eurobuddha.maxima.cloud;

import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * WATCH-ONLY wallet for a Parlons Cloud account.
 *
 * Per the split model, the spendable seed lives on the DEVICE, never here — so the node only ever
 * READS a balance for a device-supplied address, through the hosted MegaMMR gateway
 * ({@code balance megammr:true address:…}, no key material). It can neither derive keys nor sign.
 * The watch address (the device's seed-B public address) is set by a paired device and persisted.
 * Spending is a later phase: the node builds an unsigned txn, the device signs, the node relays.
 */
public final class WatchWallet {

    private static final String DEFAULT_GATEWAY_URL = "https://relay.privateprivate.org/cmd";
    private static final String DEFAULT_GATEWAY_TOKEN =
            "c9e6177419dc7e0f200390152c6296e2180fd317071c83f8db74ceab61286188";

    private final Path mWatchFile;   // watch.txt — the device's seed-B public address
    private final String mUrl;
    private final String mToken;

    public WatchWallet(Path zDataDir) {
        mWatchFile = zDataDir.resolve("watch.txt");
        String url = System.getenv("PARLONS_GATEWAY_URL");
        String tok = System.getenv("PARLONS_GATEWAY_TOKEN");
        mUrl = (url == null || url.isEmpty()) ? DEFAULT_GATEWAY_URL : url;
        mToken = (tok == null || tok.isEmpty()) ? DEFAULT_GATEWAY_TOKEN : tok;
    }

    public synchronized String watchAddress() {
        try {
            if (Files.exists(mWatchFile)) {
                return new String(Files.readAllBytes(mWatchFile), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** A paired device sets its (cold) spend address for the node to watch. */
    public synchronized void setWatchAddress(String zAddress) throws Exception {
        Files.write(mWatchFile, zAddress.trim().getBytes(StandardCharsets.UTF_8));
        try {
            Files.setPosixFilePermissions(mWatchFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    /** Read-only balance for the configured watch address via the gateway. Throws if unset. */
    public JSONObject balance() throws Exception {
        String addr = watchAddress();
        if (addr.isEmpty()) {
            throw new IllegalStateException("no watch address set (device: wallet set <Mx address>)");
        }
        return gateway("balance megammr:true address:" + addr);
    }

    // ---- the spend-side surface (mirrors the phone's WalletPublisher, synchronous) ----
    // Signing NEVER happens here: the gateway token is read+relay-only. These carry reads and
    // the pre-signed txnimport → txnbasics → txnpost relay for CloudPaymentSender.

    /** Run one gateway command (synchronous). */
    public JSONObject cmd(String zCommand) throws Exception {
        return gateway(zCommand);
    }

    /** Spendable coins (with MegaMMR proofs known to the gateway node) at an address. */
    public JSONObject coins(String zHexAddress) throws Exception {
        return gateway("coins megammr:true address:" + zHexAddress);
    }

    /** Ask the gateway node to track our address script so our coins carry proofs. */
    public JSONObject trackScript(String zScript) throws Exception {
        return gateway("newscript trackall:true script:\"" + zScript + "\"");
    }

    /**
     * Publish a LOCALLY-SIGNED txn: txnimport → txnbasics (gateway attaches the MMR proofs and
     * scripts) → txnpost. Family hard rule: EVERY error path runs txndelete so a failed send
     * never leaves a dangling signed txn row on the gateway node.
     */
    public JSONObject publish(String zImportCmd, String zId, String zPostCmd) throws Exception {
        try {
            JSONObject r1 = gateway(zImportCmd);
            if (!Boolean.TRUE.equals(r1.get("status"))) {
                throw new Exception("txnimport: " + errOf(r1));
            }
            JSONObject r2 = gateway("txnbasics id:" + zId);
            if (!Boolean.TRUE.equals(r2.get("status"))) {
                throw new Exception("txnbasics: " + errOf(r2));
            }
            JSONObject r3 = gateway(zPostCmd);
            if (!Boolean.TRUE.equals(r3.get("status"))) {
                throw new Exception("txnpost: " + errOf(r3));
            }
            return r3;
        } catch (Exception e) {
            cleanupTxn(zId);   // every txn error path runs txndelete (family hard rule)
            throw e;
        }
    }

    private void cleanupTxn(String zId) {
        try { gateway("txndelete id:" + zId); } catch (Exception ignored) { }
    }

    private static String errOf(JSONObject r) {
        Object e = r.get("error");
        return e == null ? r.toString() : String.valueOf(e);
    }

    private JSONObject gateway(String command) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(mUrl).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + mToken);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(45000);
            JSONObject req = new JSONObject();
            req.put("command", command);
            byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(is);
            if (code >= 400) {
                throw new Exception("gateway HTTP " + code + ": " + resp);
            }
            Object o = new JSONParser().parse(resp);
            return o instanceof JSONObject ? (JSONObject) o : new JSONObject();
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
