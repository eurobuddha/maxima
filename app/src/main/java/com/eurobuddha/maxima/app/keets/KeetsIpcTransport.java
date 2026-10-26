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
    private final Context mContext;
    private volatile String mPubkey;

    public KeetsIpcTransport(Context zContext) {
        mContext=zContext.getApplicationContext();
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
        if (zBytes.length > (MAX_PUBLISH_HEX_CHARS-2)/2) {
            try{return fileMedia().publish(zPath,zBytes);}
            catch(Exception e){fileMedia=null;throw e;}
        }
        String hx = hex(zBytes);
        JSONObject r = sync("keets action:publish path:" + zPath + " datahex:" + hx);
        return r.getJSONObject("response").getString("url");
    }

    /** Never heap-decode an attacker-sized blob: chat photos are ~200KB. */
    private static final int MAX_FETCH_HEX_CHARS = 16 * 1024 * 1024;   // 8MB of bytes

    @Override
    public byte[] fetch(String zUrl) throws Exception {
        if (!com.eurobuddha.maxima.core.chat.KeetsTransport.validRef(zUrl)) {
            throw new Exception("invalid keets ref");
        }
        // no file: here - the node can't read our sandbox; inline datahex replies
        // over 100K chars come back via the IPC's own content:// file hand-off
        JSONObject r = sync("keets action:fetch url:" + zUrl);
        String hx = r.getJSONObject("response").getString("datahex");
        if (hx.length() > MAX_FETCH_HEX_CHARS) {
            throw new Exception("keets fetch too large: " + (hx.length() / 2) + " bytes");
        }
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

    private volatile com.eurobuddha.maxima.core.chat.KeetsMediaClient fileMedia;
    private JSONObject file(String action,String path) throws Exception {
        CountDownLatch gate=new CountDownLatch(1);
        AtomicReference<JSONObject> result=new AtomicReference<>();
        mApi.FileCommand(action,path,null,null,r->{result.set(r);gate.countDown();});
        if(!gate.await(35,TimeUnit.SECONDS))throw new java.io.IOException("File access needs ADMIN; enable it for Parlons in your node Apps tab");
        JSONObject r=result.get();
        if(r==null || !r.optBoolean("status"))throw new java.io.IOException("File access needs ADMIN; enable it for Parlons in your node Apps tab");
        return r;
    }
    /** Salon's existing FILE capability handoff, with canonical path and size validation. */
    private synchronized com.eurobuddha.maxima.core.chat.KeetsMediaClient fileMedia() throws Exception {
        if(fileMedia!=null)return fileMedia;
        JSONObject info=sync("keets action:info").getJSONObject("response");
        if(!info.optBoolean("boundedMedia"))throw new java.io.IOException("Update your Keets node to receive files");
        String data=sync("status").getJSONObject("response").getString("data");
        JSONObject stat=file("stat",new java.io.File(data,"keets/apitoken").getPath());
        java.nio.file.Path base=java.nio.file.Paths.get(stat.optString("base")).normalize();
        java.nio.file.Path path=java.nio.file.Paths.get(stat.optString("canonical")).normalize();
        if(!stat.optBoolean("exists") || stat.optBoolean("isdir") || stat.optLong("size",0)<1 || stat.optLong("size",0)>128
                || !base.isAbsolute() || !path.isAbsolute() || !path.startsWith(base)
                || !path.endsWith(java.nio.file.Paths.get("keets","apitoken")))throw new java.io.IOException("Invalid node file response");
        android.net.Uri uri=android.net.Uri.parse(file("get",base.relativize(path).toString()).getString("uri"));
        if(!"content".equals(uri.getScheme()))throw new java.io.IOException("Invalid node file response");
        byte[] raw;
        try(java.io.InputStream in=mContext.getContentResolver().openInputStream(uri)) {
            raw=com.eurobuddha.maxima.core.chat.KeetsMediaClient.read(in,128);
        }
        String token=new String(raw,StandardCharsets.US_ASCII).trim();java.util.Arrays.fill(raw,(byte)0);
        return fileMedia=new com.eurobuddha.maxima.core.chat.KeetsMediaClient(info.getInt("apiport"),token);
    }
    @Override public byte[] fetch(String url,int maxBytes) throws Exception {
        try{return fileMedia().fetch(url,maxBytes);}
        catch(Exception e){fileMedia=null;throw e;}
    }

    public void destroy() {
        mApi.onDestroy();
    }
}
