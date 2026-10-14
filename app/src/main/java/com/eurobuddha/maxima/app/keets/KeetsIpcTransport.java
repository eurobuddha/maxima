package com.eurobuddha.maxima.app.keets;

import android.content.Context;

import com.eurobuddha.maxima.core.chat.KeetsTransport;
import com.eurobuddha.minimaapi.MinimaAPI;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The keets side-channel for the Parlons app: the phone's minimaCore node
 * (Pandamonium/PandaBear/BlackBear, keets-enabled) runs the transport; we ride
 * its broadcast-Intent IPC (minimaapi). Outbound goes through the node's
 * `keets` command; INBOUND arrives via {@link KeetsNotifyReceiver} - the node's
 * own drainer owns the inbox (single-drainer rule) and fans every chat message
 * out to enabled companions, so this transport never polls or acks.
 *
 * Registration is automatic and pairs to whichever ONE node app answers; the
 * user must enable Parlons in the node's Apps tab. {@link #myPubkey()} stays
 * null until the node has answered `keets action:info`.
 */
public final class KeetsIpcTransport implements KeetsTransport {

    /** IPC commands cap at 256K chars; leave headroom for the command text. */
    private static final int MAX_PUBLISH_HEX_CHARS = 240_000;
    private static final long CMD_TIMEOUT_S = 90;

    private final MinimaAPI mApi;
    private volatile String mPubkey;

    public KeetsIpcTransport(Context zContext) {
        android.util.Log.i("KeetsIPC", "constructing - registering with the node family");
        mApi = new MinimaAPI(zContext.getApplicationContext(), r -> {
            android.util.Log.i("KeetsIPC", "register reply: " + r);
            if (r != null && r.optBoolean("status", false)) {
                refreshPubkey();
            }
        });
    }

    private void refreshPubkey() {
        mApi.Command("keets action:info", r -> {
            android.util.Log.i("KeetsIPC", "info reply: " + r);
            try {
                String pk = r.getJSONObject("response").getString("pubkey");
                if (pk.matches("(?i)0x[0-9a-f]{64}")) {
                    mPubkey = pk;
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** Block for one IPC command. NEVER call on the main thread - responses are
     *  delivered on it (ChatEngine's send/ingest paths are all off-main). */
    private JSONObject sync(String zCmd) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<JSONObject> out = new AtomicReference<>();
        mApi.Command(zCmd, r -> {
            out.set(r);
            gate.countDown();
        });
        if (!gate.await(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            throw new Exception("node IPC timeout: " + zCmd.split(" ")[0]);
        }
        JSONObject r = out.get();
        if (r == null || !r.optBoolean("status", false)) {
            throw new Exception("node refused: "
                    + (r == null ? "no reply" : r.optString("error", r.toString())));
        }
        return r;
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
        sync("keets action:send to:" + zPubkey.toLowerCase()
                + " datahex:" + hex(zPayload.getBytes(StandardCharsets.UTF_8)));
        return true;
    }

    @Override
    public String publish(String zPath, byte[] zBytes) throws Exception {
        String hx = hex(zBytes);
        if (hx.length() > MAX_PUBLISH_HEX_CHARS) {
            // ponytail: media over ~118KB can't cross the broadcast IPC as datahex;
            // a content://-based hand-off is the upgrade path if it ever matters
            throw new Exception("media too large for the node IPC (" + zBytes.length + " bytes)");
        }
        JSONObject r = sync("keets action:publish path:" + zPath + " datahex:" + hx);
        return r.getJSONObject("response").getString("url");
    }

    @Override
    public byte[] fetch(String zUrl) throws Exception {
        // no file: here - the node can't read our sandbox; inline datahex replies
        // over 100K chars come back via the IPC's own content:// file hand-off
        JSONObject r = sync("keets action:fetch url:" + zUrl);
        String hx = r.getJSONObject("response").getString("datahex");
        if (hx.startsWith("0x") || hx.startsWith("0X")) {
            hx = hx.substring(2);
        }
        byte[] out = new byte[hx.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hx.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Override
    public String myPubkey() {
        if (mPubkey == null) {
            refreshPubkey();   // registration may have completed since; async refresh
        }
        return mPubkey;
    }

    public void destroy() {
        mApi.onDestroy();
    }
}
