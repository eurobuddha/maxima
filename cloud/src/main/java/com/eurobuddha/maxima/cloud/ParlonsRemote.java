package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.rpc.RpcPeer;
import com.eurobuddha.maxima.core.session.Bootstrap;

import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Thin-client SDK: how a DEVICE drives a Parlons Cloud account.
 *
 * The device holds its OWN identity (never the account's). It attaches to the fleet only to
 * carry {@link ParlonsControl} RPCs to the cloud node and receive the replies — the account's
 * identity, chats and wallet stay on the cloud node. Any front-end (the CLI here, the desktop
 * and Android apps next) is a thin shell over this class. "One account, all devices" = many
 * devices, each a paired {@code ParlonsRemote}, driving the one always-on node.
 */
public final class ParlonsRemote {

    private static final String PROTOCOL = "1.0.48";

    private final MaximaIdentity mDeviceId;
    private final MaximaNode mNode;
    private volatile String mCloudMax = "";
    private volatile String mCloudLive = "";
    private ScheduledExecutorService mPump;

    public ParlonsRemote(MaximaIdentity zDeviceIdentity) {
        mDeviceId = zDeviceIdentity;
        mNode = new MaximaNode(zDeviceIdentity, PROTOCOL, 2);
        mNode.setNodeKind("device");
    }

    /** This device's key — what the account authorizes / approves / revokes. */
    public String deviceKey() {
        return new MiniData(mDeviceId.publicKey()).to0xString();
    }

    /** Attach to the fleet and resolve the cloud account's live address from its MAX# (or use a
     *  bare {@code Mx…@host:port} as-is). Call once before any command. */
    public void connect(String zCloudAddress) throws Exception {
        mNode.start(new ArrayList<>(Bootstrap.RELAYS), 30_000);
        for (int i = 0; i < 10 && mNode.rpc().myAddresses().isEmpty(); i++) {
            mNode.maintain(20_000);
            Thread.sleep(1000);
        }
        resolve(zCloudAddress);
        mPump = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "parlons-remote-maint");
            t.setDaemon(true);
            return t;
        });
        mPump.scheduleWithFixedDelay(() -> {
            try { mNode.maintain(20_000); } catch (Exception ignored) { }
        }, 20, 20, TimeUnit.SECONDS);
    }

    private void resolve(String zCloudAddress) throws Exception {
        mCloudMax = zCloudAddress;
        if (!zCloudAddress.startsWith("MAX#")) {
            mCloudLive = zCloudAddress;
            return;
        }
        // A permanent MAX# resolves through the account's pinned MLS. Retry with backoff: an
        // always-on account is always published, but its directory record can briefly lag (a
        // just-started node, or MLS propagation), and a transient relay miss shouldn't fail login.
        Exception last = null;
        for (int i = 0; i < 12; i++) {
            try {
                String r = mNode.resolvePermanent(zCloudAddress);
                if (r != null && !r.isEmpty()) {
                    mCloudLive = r;
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(2500);
        }
        throw new IllegalStateException("could not resolve the cloud account — is it online? ("
                + (last == null ? "no record" : last.getMessage()) + ")");
    }

    /** One RPC round-trip. Re-resolves the cloud address once if the first send fails (the
     *  account's live location may have rotated since we cached it). */
    public JSONObject rpc(String zMethod, JSONObject zPayload) throws Exception {
        try {
            return callOnce(zMethod, zPayload);
        } catch (Exception first) {
            try {
                resolve(mCloudMax);
                return callOnce(zMethod, zPayload);
            } catch (Exception second) {
                throw first;
            }
        }
    }

    private JSONObject callOnce(String zMethod, JSONObject zPayload) throws Exception {
        final CompletableFuture<String> fut = new CompletableFuture<>();
        byte[] payload = (zPayload == null ? new JSONObject() : zPayload)
                .toString().getBytes(StandardCharsets.UTF_8);
        mNode.rpc().call(mCloudLive, zMethod, payload, new RpcPeer.ResponseHandler() {
            public void onResponse(byte[] p) {
                fut.complete(new String(p, StandardCharsets.UTF_8));
            }
            public void onError(String m) {
                fut.completeExceptionally(new RuntimeException(m));
            }
        }, 30_000);
        String resp = fut.get(35, TimeUnit.SECONDS);
        Object o = new JSONParser().parse(resp);
        return o instanceof JSONObject ? (JSONObject) o : new JSONObject();
    }

    // ---- typed commands (thin wrappers over ParlonsControl methods) ----

    public JSONObject pair(String zLabel, String zCode) throws Exception {
        JSONObject p = new JSONObject();
        p.put("label", zLabel);
        p.put("code", zCode);
        return rpc(ParlonsControl.M_PAIR, p);
    }

    public JSONObject ping() throws Exception {
        return rpc(ParlonsControl.M_PING, new JSONObject());
    }

    /** VPS-node telemetry for the Node tab: uptime, version, hosts, mailboxHeld, relayOn, meshPeers, pairedDevices. */
    public JSONObject nodeStatus() throws Exception {
        return rpc(ParlonsControl.M_NODE_STATUS, new JSONObject());
    }

    /** Set the account's display name; the node re-announces it to every contact. */
    public JSONObject setName(String zName) throws Exception {
        JSONObject p = new JSONObject();
        p.put("name", zName);
        return rpc(ParlonsControl.M_SET_NAME, p);
    }

    /** Rename a contact locally on the account (display-name override). */
    public JSONObject renameContact(String zKey, String zName) throws Exception {
        JSONObject p = new JSONObject();
        p.put("key", zKey);
        p.put("name", zName);
        return rpc(ParlonsControl.M_CONTACT_RENAME, p);
    }

    /** Force-heal one contact NOW: the node asks their directory for their current address and
     *  persists it. Returns {updated, address} or a clear "they may be offline" error. */
    public JSONObject resolveContact(String zKey) throws Exception {
        JSONObject p = new JSONObject();
        p.put("key", zKey);
        return rpc(ParlonsControl.M_CONTACT_RESOLVE, p);
    }

    /** Mark a conversation read on the account (clears unread; sends the read receipt if the
     *  account allows it). */
    public JSONObject markRead(String zPeer) throws Exception {
        JSONObject p = new JSONObject();
        p.put("peer", zPeer);
        return rpc(ParlonsControl.M_MARK_READ, p);
    }

    public JSONObject devices() throws Exception {
        return rpc(ParlonsControl.M_PAIR_LIST, new JSONObject());
    }

    public JSONObject approve(String zDeviceKey) throws Exception {
        JSONObject p = new JSONObject();
        p.put("device", zDeviceKey);
        return rpc(ParlonsControl.M_PAIR_APPROVE, p);
    }

    public JSONObject revoke(String zDeviceKey) throws Exception {
        JSONObject p = new JSONObject();
        p.put("device", zDeviceKey);
        return rpc(ParlonsControl.M_PAIR_REVOKE, p);
    }

    /** Mint a fresh one-time bootstrap code on the node (written to its pair-code.txt, never returned). */
    public JSONObject newCode() throws Exception {
        return rpc(ParlonsControl.M_PAIR_NEWCODE, new JSONObject());
    }

    public JSONObject contacts() throws Exception {
        return rpc(ParlonsControl.M_CONTACTS, new JSONObject());
    }

    public JSONObject addContact(String zAddress) throws Exception {
        JSONObject p = new JSONObject();
        p.put("address", zAddress);
        return rpc(ParlonsControl.M_CONTACT_ADD, p);
    }

    public JSONObject summaries() throws Exception {
        return rpc(ParlonsControl.M_SUMMARIES, new JSONObject());
    }

    public JSONObject conversation(String zPeer) throws Exception {
        JSONObject p = new JSONObject();
        p.put("peer", zPeer);
        return rpc(ParlonsControl.M_CONVERSATION, p);
    }

    public JSONObject send(String zPeer, String zBody) throws Exception {
        JSONObject p = new JSONObject();
        p.put("peer", zPeer);
        p.put("body", zBody);
        return rpc(ParlonsControl.M_SEND, p);
    }

    public JSONObject walletAddress() throws Exception {
        return rpc(ParlonsControl.M_WALLET_ADDR, new JSONObject());
    }

    public JSONObject setWatch(String zAddress) throws Exception {
        JSONObject p = new JSONObject();
        p.put("address", zAddress);
        return rpc(ParlonsControl.M_WALLET_SET, p);
    }

    public JSONObject balance() throws Exception {
        return rpc(ParlonsControl.M_WALLET_BAL, new JSONObject());
    }

    public void close() {
        if (mPump != null) {
            mPump.shutdownNow();
        }
        try { mNode.stop(); } catch (Exception ignored) { }
    }
}
