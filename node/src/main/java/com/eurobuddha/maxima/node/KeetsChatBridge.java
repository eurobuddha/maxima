package com.eurobuddha.maxima.node;

import com.eurobuddha.maxima.cloud.ParlonsCore;
import com.eurobuddha.maxima.core.chat.ChatMessage;
import com.eurobuddha.maxima.core.chat.KeetsTransport;
import com.eurobuddha.maxima.core.chat.KeetsWire;
import com.eurobuddha.maxima.core.identity.Keys;

import org.minima.objects.base.MiniData;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * KeetChat interop for the Parlons Node: the ONE drainer of this node's keets
 * inbox (acks only after the chat engine has stored a message durably), and the
 * {@link KeetsTransport} the chat engine sends through. Runs only when the
 * embedded node was started with {@code -keets}.
 *
 * pay-address requests are answered here, not in the engine: from the node
 * wallet, CONTACTS-ONLY (a stranger must not link a chat pubkey to a wallet)
 * and at most once per peer per ten minutes.
 */
final class KeetsChatBridge implements KeetsTransport {

    private static final long POLL_MS = 5_000;
    private static final long PAYADDR_COOLDOWN_MS = 10 * 60_000;

    private final ParlonsCore mCore;
    /* contacts-gated, so bounded by the contact book */
    private final Map<String, Long> mPayAddrAnswered = new HashMap<>();
    private volatile boolean mRunning = true;
    private volatile String mPubkey;
    private String mLastErr = "";

    KeetsChatBridge(ParlonsCore zCore) {
        mCore = zCore;
    }

    void start() {
        Thread t = new Thread(this::loop, "keets-chat-bridge");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        mRunning = false;
    }

    @Override
    public String myPubkey() {
        return mPubkey;
    }

    private void loop() {
        // The keets identity is stable (datadir keypair) - fetch once, announce
        // once: this pubkey is what a KeetChat user adds as a contact.
        while (mRunning && mPubkey == null) {
            try {
                JSONObject info = NodeWallet.response(NodeWallet.run("keets action:info"));
                String pk = String.valueOf(info.getOrDefault("pubkey", ""));
                if (pk.matches("(?i)0x[0-9a-f]{64}")) {
                    mPubkey = pk;
                    System.out.println("[parlons-node] keets identity: " + pk
                            + " (a KeetChat user adds this as a contact)");
                    break;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
        while (mRunning) {
            try {
                pollOnce();
            } catch (Exception e) {
                logOnce("poll: " + e);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void pollOnce() {
        JSONObject res = NodeWallet.run("keets action:inbox");
        if (!Boolean.TRUE.equals(res.get("status"))) {
            logOnce("inbox unavailable: " + res.get("error"));
            return;
        }
        mLastErr = "";
        Object resp = res.get("response");
        if (!(resp instanceof JSONArray)) {
            return;
        }
        for (Object o : (JSONArray) resp) {
            if (!(o instanceof JSONObject)) {
                continue;
            }
            JSONObject m = (JSONObject) o;
            String id = String.valueOf(m.getOrDefault("id", ""));
            String from = String.valueOf(m.getOrDefault("from", ""));
            String payload = String.valueOf(m.getOrDefault("message", ""));
            boolean consumed;
            try {
                consumed = handle(from, payload, id);
            } catch (Exception e) {
                logOnce("handle: " + e);
                continue;   // not acked - redelivered next poll
            }
            if (consumed) {
                NodeWallet.run("keets action:ack id:" + id);
            }
        }
    }

    private boolean handle(String zFrom, String zPayload, String zTransportId) {
        KeetsWire.Msg k = KeetsWire.decode(zPayload);
        if (k != null && "payaddr-req".equals(k.state)) {
            answerPayAddr(zFrom);
            return true;
        }
        return mCore.chat().onKeetsInbound(zFrom, zPayload, zTransportId);
    }

    private void answerPayAddr(String zFrom) {
        String key = Keys.norm(zFrom);
        if (mCore.node().contact(key) == null) {
            return;   // strangers get silence, never a wallet link
        }
        Long last = mPayAddrAnswered.get(key);
        long now = System.currentTimeMillis();
        if (last != null && now - last < PAYADDR_COOLDOWN_MS) {
            return;
        }
        JSONObject addr = NodeWallet.response(NodeWallet.run("getaddress"));
        String mini = String.valueOf(addr.getOrDefault("miniaddress", ""));
        if (mini.isEmpty()) {
            return;
        }
        ChatMessage am = new ChatMessage();
        am.type = ChatMessage.TYPE_ADDRESS;
        am.id = "pn-" + now;
        am.time = now;
        am.address = mini;
        try {
            if (send(zFrom, KeetsWire.encode(am, mCore.node().name()))) {
                mPayAddrAnswered.put(key, now);
            }
        } catch (Exception e) {
            logOnce("payaddr: " + e);
        }
    }

    // ------------------------------------------------------------------
    // KeetsTransport - the engine's send side
    // ------------------------------------------------------------------

    @Override
    public boolean send(String zPubkey, String zPayload) throws Exception {
        if (zPayload == null || zPubkey == null
                || !zPubkey.matches("(?i)0x[0-9a-f]{64}")) {
            return false;   // never interpolate a malformed target into the command
        }
        String hex = new MiniData(zPayload.getBytes(StandardCharsets.UTF_8)).to0xString();
        JSONObject res = NodeWallet.run("keets action:send to:" + zPubkey.toLowerCase()
                + " datahex:" + hex);
        return Boolean.TRUE.equals(res.get("status"));
    }

    @Override
    public String publish(String zPath, byte[] zBytes) throws Exception {
        // file: hand-off - megabyte params do not survive the command tokenizer
        File tmp = File.createTempFile("keets-pub", ".bin");
        try {
            Files.write(tmp.toPath(), zBytes);
            JSONObject res = NodeWallet.run("keets action:publish path:" + zPath
                    + " file:" + tmp.getAbsolutePath());
            if (!Boolean.TRUE.equals(res.get("status"))) {
                throw new Exception("keets publish failed: " + res.get("error"));
            }
            return String.valueOf(NodeWallet.response(res).getOrDefault("url", ""));
        } finally {
            tmp.delete();
        }
    }

    /** Never heap-load an attacker-sized blob: chat photos are ~200KB. */
    private static final long MAX_FETCH_BYTES = 8 * 1024 * 1024;

    @Override
    public byte[] fetch(String zUrl) throws Exception {
        if (!KeetsTransport.validRef(zUrl)) {
            throw new Exception("invalid keets ref");
        }
        File tmp = File.createTempFile("keets-fetch", ".bin");
        try {
            JSONObject res = NodeWallet.run("keets action:fetch url:" + zUrl
                    + " file:" + tmp.getAbsolutePath());
            if (!Boolean.TRUE.equals(res.get("status"))) {
                throw new Exception("keets fetch failed: " + res.get("error"));
            }
            long len = tmp.length();
            if (len > MAX_FETCH_BYTES) {
                throw new Exception("keets fetch too large: " + len + " bytes");
            }
            return Files.readAllBytes(tmp.toPath());
        } finally {
            tmp.delete();
        }
    }

    private volatile com.eurobuddha.maxima.core.chat.KeetsMediaClient fileMedia;
    @Override public byte[] fetch(String url, int maxBytes) throws Exception {
        try {
            if(fileMedia==null) {
                JSONObject info=NodeWallet.response(NodeWallet.run("keets action:info"));
                if(!Boolean.TRUE.equals(info.get("boundedMedia")))throw new java.io.IOException("Update your Keets node to receive files");
                String data=String.valueOf(NodeWallet.response(NodeWallet.run("status")).get("data"));
                fileMedia=com.eurobuddha.maxima.core.chat.KeetsMediaClient.local(Integer.parseInt(String.valueOf(info.get("apiport"))),data);
            }
            return fileMedia.fetch(url,maxBytes);
        } catch(Exception e){fileMedia=null;throw e;}
    }

    /** Log each distinct error once, not every five seconds. */
    private void logOnce(String zErr) {
        if (!zErr.equals(mLastErr)) {
            mLastErr = zErr;
            System.out.println("[parlons-node] keets bridge: " + zErr);
        }
    }
}
