package com.eurobuddha.maxima.desktop.keets;

import com.eurobuddha.maxima.core.chat.KeetsTransport;
import com.eurobuddha.maxima.desktop.wallet.DesktopNodeLink;

import org.minima.utils.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * keets over the user's LOCAL minimaCore Desktop node (the DesktopNodeLink RPC,
 * 127.0.0.1). file: hand-offs work because the node shares this machine's
 * filesystem. The node's OWN chat app (minimaCore Desktop 0.18.4+) owns the
 * inbox and fans every drained message to registered companions - this
 * transport never polls or acks; inbound rides {@link KeetsCompanionServer}.
 */
public final class KeetsNodeLinkTransport implements KeetsTransport {

    /** Chat photos are ~200KB; never heap-load an attacker-sized blob. */
    private static final long MAX_FETCH_BYTES = 8 * 1024 * 1024;

    private final DesktopNodeLink mLink;
    private volatile String mPubkey;
    private final AtomicBoolean mRefreshing = new AtomicBoolean(false);

    public KeetsNodeLinkTransport(DesktopNodeLink zLink) {
        mLink = zLink;
        refreshPubkeyAsync();
    }

    private JSONObject ok(JSONObject zR) throws Exception {
        if (zR == null || !Boolean.TRUE.equals(zR.get("status"))) {
            throw new Exception(zR == null ? "no reply from the linked node"
                    : String.valueOf(zR.getOrDefault("error", zR.toString())));
        }
        return zR;
    }

    private JSONObject response(JSONObject zR) {
        Object resp = zR.get("response");
        return resp instanceof JSONObject ? (JSONObject) resp : new JSONObject();
    }

    /** The linked node's VERSIONED data dir (status.data) - where the companion
     *  registration file lives. Blocking; call off-EDT. */
    public String nodeDataDir() throws Exception {
        return String.valueOf(response(ok(mLink.cmd("status"))).get("data"));
    }

    private static String hex(byte[] zBytes) {
        StringBuilder sb = new StringBuilder(zBytes.length * 2 + 2).append("0x");
        for (byte b : zBytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Override
    public boolean send(String zPubkey, String zPayload) throws Exception {
        if (zPayload == null || zPubkey == null || !zPubkey.matches("(?i)0x[0-9a-f]{64}")) {
            return false;
        }
        ok(mLink.cmd("keets action:send to:" + zPubkey.toLowerCase()
                + " datahex:" + hex(zPayload.getBytes(StandardCharsets.UTF_8))));
        return true;
    }

    @Override
    public String publish(String zPath, byte[] zBytes) throws Exception {
        // file: hand-off - megabyte params do not survive the command tokenizer
        File tmp = File.createTempFile("keets-pub", ".bin");
        try {
            Files.write(tmp.toPath(), zBytes);
            JSONObject r = ok(mLink.cmd("keets action:publish path:" + zPath
                    + " file:" + tmp.getAbsolutePath()));
            return String.valueOf(response(r).get("url"));
        } finally {
            tmp.delete();
        }
    }

    @Override
    public byte[] fetch(String zUrl) throws Exception {
        if (!KeetsTransport.validRef(zUrl)) {
            throw new Exception("invalid keets ref");
        }
        File tmp = File.createTempFile("keets-fetch", ".bin");
        try {
            ok(mLink.cmd("keets action:fetch url:" + zUrl + " file:" + tmp.getAbsolutePath()));
            long len = tmp.length();
            if (len > MAX_FETCH_BYTES) {
                throw new Exception("keets fetch too large: " + len + " bytes");
            }
            return Files.readAllBytes(tmp.toPath());
        } finally {
            tmp.delete();
        }
    }

    /** Cached; a miss kicks an async refresh and returns null - never blocks
     *  the EDT on the node link's socket timeouts. */
    @Override
    public String myPubkey() {
        if (mPubkey == null) {
            refreshPubkeyAsync();
        }
        return mPubkey;
    }

    private void refreshPubkeyAsync() {
        if (!mRefreshing.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                String pk = String.valueOf(response(ok(mLink.cmd("keets action:info"))).get("pubkey"));
                if (pk.matches("(?i)0x[0-9a-f]{64}")) {
                    mPubkey = pk;
                }
            } catch (Exception ignored) {
                // node link down or keets off on that node - stays null
            } finally {
                mRefreshing.set(false);
            }
        }, "keets-link-info").start();
    }
}
