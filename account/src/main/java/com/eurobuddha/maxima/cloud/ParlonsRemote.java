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
import java.util.List;
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
    /** The relays this device starts from to find the account: the caller's seeds (its own,
     *  remembered, and the compiled-in list if still on); {@code Bootstrap.RELAYS} by default. */
    private volatile List<String> mSeedRelays = new ArrayList<>(Bootstrap.RELAYS);

    public void setSeedRelays(List<String> zSeeds) {
        if (zSeeds != null && !zSeeds.isEmpty()) {
            mSeedRelays = new ArrayList<>(zSeeds);
        }
    }

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
        connect(zCloudAddress, null);
    }

    /**
     * Connect with a FAST PATH: if the caller remembered the account's last resolved live
     * address, probe it with one short ping before falling into the MLS resolve ladder
     * (12×2.5s worst case). An always-on account rarely moves, so a warm reconnect usually
     * skips the ladder entirely — this is most of the cold-start delay.
     */
    public void connect(String zCloudAddress, String zCachedLive) throws Exception {
        long t0 = System.currentTimeMillis();
        int attached = mNode.start(new ArrayList<>(mSeedRelays), 30_000);
        long tAttach = System.currentTimeMillis();
        int waits = 0;
        for (int i = 0; i < 10 && mNode.rpc().myAddresses().isEmpty(); i++) {
            mNode.maintain(20_000);
            Thread.sleep(1000);
            waits++;
        }
        long tAddr = System.currentTimeMillis();
        if (zCachedLive != null && !zCachedLive.isEmpty() && zCloudAddress.startsWith("MAX#")) {
            mCloudMax = zCloudAddress;
            mCloudLive = zCachedLive;
            try {
                callOnce(ParlonsControl.M_PING, new JSONObject(), 8_000);
                startPump();
                log("connected (warm) in " + (System.currentTimeMillis() - t0) + "ms: attach "
                        + (tAttach - t0) + "ms/" + attached + " relays, addresses " + (tAddr - tAttach)
                        + "ms/" + waits + " waits, probe " + (System.currentTimeMillis() - tAddr) + "ms");
                return;                       // warm reconnect — ladder skipped
            } catch (Exception probeFailed) {
                // stale address — fall through to the full resolve
            }
        }
        long tProbe = System.currentTimeMillis();
        resolve(zCloudAddress);
        startPump();
        log("connected (resolved) in " + (System.currentTimeMillis() - t0) + "ms: attach "
                + (tAttach - t0) + "ms/" + attached + " relays, addresses " + (tAddr - tAttach)
                + "ms/" + waits + " waits, probe " + (tProbe - tAddr) + "ms, resolve "
                + (System.currentTimeMillis() - tProbe) + "ms");
    }

    /** Timing/diagnostic line — stdout on the CLI/cloud, logcat on Android (System.out is
     *  redirected there); never carries a secret. */
    private static void log(String zLine) {
        System.out.println("[remote] " + zLine);
    }

    /** The account's currently resolved live address — cache it for the next warm reconnect. */
    public String liveAddress() {
        return mCloudLive;
    }

    private void startPump() {
        if (mPump != null) {
            return;
        }
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
        return callOnce(zMethod, zPayload, 30_000);
    }

    /** {@link #rpc(String, JSONObject)} with an explicit reply timeout. */
    public JSONObject rpc(String zMethod, JSONObject zPayload, long zTimeoutMs) throws Exception {
        try {
            return callOnce(zMethod, zPayload, zTimeoutMs);
        } catch (Exception first) {
            try {
                resolve(mCloudMax);
                return callOnce(zMethod, zPayload, zTimeoutMs);
            } catch (Exception second) {
                throw first;
            }
        }
    }

    private JSONObject callOnce(String zMethod, JSONObject zPayload, long zTimeoutMs) throws Exception {
        final CompletableFuture<String> fut = new CompletableFuture<>();
        byte[] payload = (zPayload == null ? new JSONObject() : zPayload)
                .toString().getBytes(StandardCharsets.UTF_8);
        final long tCall = System.currentTimeMillis();
        mNode.rpc().call(mCloudLive, zMethod, payload, new RpcPeer.ResponseHandler() {
            public void onResponse(byte[] p) {
                fut.complete(new String(p, StandardCharsets.UTF_8));
            }
            public void onError(String m) {
                fut.completeExceptionally(new RuntimeException(m));
            }
        }, zTimeoutMs);
        long tSent = System.currentTimeMillis();
        String resp = fut.get(zTimeoutMs + 5_000, TimeUnit.MILLISECONDS);
        log("rpc " + zMethod + ": send leg " + (tSent - tCall) + "ms, reply wait "
                + (System.currentTimeMillis() - tSent) + "ms; to " + mCloudLive
                + "; my addresses " + mNode.rpc().myAddresses());
        Object o = new JSONParser().parse(resp);
        return o instanceof JSONObject ? (JSONObject) o : new JSONObject();
    }

    // ---- typed commands (thin wrappers over ParlonsControl methods) ----

    /** Receives events the cloud PUSHES to this device (messages, ticks, call signals). */
    public interface PushListener {
        void onPush(JSONObject zEvent);
    }

    /**
     * Serve the {@code parlons.push} method on THIS device's node so the cloud can push events
     * (instant messages, delivery ticks, incoming calls) instead of us polling. Only events
     * signed by the ACCOUNT's identity are accepted; duplicates (the cloud fires every reply
     * address we advertise) are dropped by event id.
     */
    public void setPushListener(PushListener zListener) {
        final String accountKey = accountKeyHex();
        if (accountKey.isEmpty()) {
            // A bare Mx…@host:port account has no recoverable identity key, so pushes could
            // never be verified — and silently rejecting every event was the worst failure
            // mode. Fail LOUDLY at install time: push needs the MAX# permanent.
            throw new IllegalStateException(
                    "push needs the account's MAX# permanent address (bare Mx address given)");
        }
        final java.util.Set<String> seen =
                java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<String, Boolean>() {
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> e) {
                        return size() > 256;
                    }
                });
        mNode.rpc().services().register(ParlonsControl.DEVICE_PUSH, req -> {
            String from = new MiniData(req.fromPublicKey).to0xString();
            if (accountKey.isEmpty() || !accountKey.equalsIgnoreCase(from)) {
                throw new SecurityException("push not from the account");
            }
            Object o = new JSONParser().parse(new String(req.payload, StandardCharsets.UTF_8));
            if (o instanceof JSONObject) {
                JSONObject ev = (JSONObject) o;
                String eid = String.valueOf(ev.get("eid"));
                synchronized (seen) {
                    if (!seen.add(eid)) {
                        return "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    }
                }
                zListener.onPush(ev);
            }
            return "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        });
    }

    /** The account's identity key (0x-hex) parsed from its MAX# permanent, or "". */
    public String accountKeyHex() {
        String m = mCloudMax;
        if (m != null && m.startsWith("MAX#")) {
            int end = m.indexOf('#', 4);
            if (end > 4) {
                return m.substring(4, end);
            }
        }
        return "";
    }

    /** Heartbeat: refreshes this device's live addresses on the node so pushes keep arriving. */
    public JSONObject registerPush() throws Exception {
        return rpc(ParlonsControl.M_PUSH_REG, new JSONObject());
    }

    /** Relay one call signal (offer/answer/ice/bye/busy) to a peer, sent AS the account. */
    public JSONObject callSignal(String zPeer, String zCallId, String zKind,
                                 String zPayload, String zMemo) throws Exception {
        JSONObject p = new JSONObject();
        p.put("peer", zPeer);
        p.put("id", zCallId);
        p.put("kind", zKind);
        p.put("payload", zPayload == null ? "" : zPayload);
        p.put("memo", zMemo == null ? "" : zMemo);
        return rpc(ParlonsControl.M_CALL_SIGNAL, p);
    }

    /** Chunk size for media upload — inside the 256K RPC ceiling with base64 + envelope room. */
    public static final int MEDIA_CHUNK = 120 * 1024;

    /**
     * Send media (photo / voice note) through the account: chunked upload, then the NODE
     * publishes the blobs (they live on the always-on VPS) and sends the media message.
     */
    public JSONObject sendMedia(String zPeer, boolean zGroup, byte[] zBytes, String zMime,
                                String zCaption) throws Exception {
        String tid = java.util.UUID.randomUUID().toString();
        int off = 0;
        JSONObject last = new JSONObject();
        while (off < zBytes.length) {
            int n = Math.min(MEDIA_CHUNK, zBytes.length - off);
            byte[] part = new byte[n];
            System.arraycopy(zBytes, off, part, 0, n);
            JSONObject p = new JSONObject();
            p.put("tid", tid);
            p.put("off", off);         // offset idempotency: a retried chunk must not append twice
            p.put("data", java.util.Base64.getEncoder().encodeToString(part));
            boolean isLast = off + n >= zBytes.length;
            p.put("last", isLast);
            if (isLast) {
                p.put("peer", zPeer);
                p.put("group", zGroup);
                p.put("mime", zMime);
                p.put("caption", zCaption == null ? "" : zCaption);
            }
            last = rpc(ParlonsControl.M_MEDIA_UP, p);
            Object ok = last.get("ok");
            if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                return last;
            }
            Object got = last.get("got");
            if (!isLast && got instanceof Number && ((Number) got).longValue() != off + n) {
                JSONObject e = new JSONObject();
                e.put("ok", false);
                e.put("error", "upload out of sync — try again");
                return e;
            }
            off += n;
        }
        return last;
    }

    /** Newest page of a conversation ({@code limit} entries, or those before a time cursor). */
    public JSONObject conversation(String zPeer, int zLimit, long zBefore) throws Exception {
        JSONObject p = new JSONObject();
        p.put("peer", zPeer);
        if (zLimit > 0) {
            p.put("limit", zLimit);
        }
        if (zBefore > 0) {
            p.put("before", zBefore);
        }
        return rpc(ParlonsControl.M_CONVERSATION, p);
    }

    /** Delta poll: only entries NEWER than the cursor (tiny reply — the cheap fallback poll).
     *  NO retry and a short leash: the cadence retries it anyway, and the full retry ladder
     *  (35s + re-resolve + 35s) held the interactive lane hostage on a bad network. */
    public JSONObject conversationAfter(String zPeer, long zAfter) throws Exception {
        JSONObject p = new JSONObject();
        p.put("peer", zPeer);
        p.put("after", zAfter);
        return callOnce(ParlonsControl.M_CONVERSATION, p, 12_000);
    }

    /** Create a group on the account; the roster is pushed to every member. */
    public JSONObject createGroup(String zName, java.util.List<String> zMemberKeys) throws Exception {
        JSONObject p = new JSONObject();
        p.put("name", zName);
        org.minima.utils.json.JSONArray arr = new org.minima.utils.json.JSONArray();
        arr.addAll(zMemberKeys);
        p.put("members", arr);
        return rpc(ParlonsControl.M_GROUP_CREATE, p);
    }

    /** This device's own node — the portal hangs its MediaService/BlobStore off it so received
     *  media chunks are fetched over MediaWire exactly as the phone app does. */
    public com.eurobuddha.maxima.core.MaximaNode node() {
        return mNode;
    }

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

    /** The node's recent event-log lines (newest first). {@code clear=true} clears the ring. */
    public JSONObject nodeLog(boolean zClear) throws Exception {
        JSONObject p = new JSONObject();
        p.put("clear", zClear);
        return rpc(ParlonsControl.M_NODE_LOG, p);
    }

    /** Transport figures: hosts (connected flag), mailbox/outbox, reachability, mesh, relay stats. */
    public JSONObject nodeFigures() throws Exception {
        return rpc(ParlonsControl.M_NODE_FIGURES, new JSONObject());
    }

    /** Add / remove an attach host at runtime. Pass "" to skip either. */
    public JSONObject nodeHosts(String zAdd, String zRemove) throws Exception {
        JSONObject p = new JSONObject();
        if (zAdd != null) p.put("add", zAdd);
        if (zRemove != null) p.put("remove", zRemove);
        return rpc(ParlonsControl.M_NODE_HOSTS, p);
    }

    /** Switch the account's use of the compiled-in relay list on or off (refused when off would
     *  leave it with no seed). */
    public JSONObject nodeHostsBuiltIn(boolean zOn) throws Exception {
        JSONObject p = new JSONObject();
        p.put("builtin", zOn);
        return rpc(ParlonsControl.M_NODE_HOSTS, p);
    }

    /** MLS/location control: action = pin (optional address) / clear / republish. */
    public JSONObject nodeMls(String zAction, String zAddress) throws Exception {
        JSONObject p = new JSONObject();
        p.put("action", zAction);
        if (zAddress != null) p.put("address", zAddress);
        return rpc(ParlonsControl.M_NODE_MLS, p);
    }

    /** The account wallet's full token balance array. */
    public JSONObject walletTokens() throws Exception {
        return rpc(ParlonsControl.M_WALLET_TOKENS, new JSONObject());
    }

    /** Key-uses count (read); pass raiseTo>0 to raise the counter (raise-only). */
    public JSONObject walletUses(int zRaiseTo) throws Exception {
        JSONObject p = new JSONObject();
        if (zRaiseTo > 0) p.put("raiseTo", zRaiseTo);
        return rpc(ParlonsControl.M_WALLET_USES, p);
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

    /** Pay a contact from the ACCOUNT's wallet (built+signed on the node, gateway-relayed).
     *  Returns fast with state "building"; the bubble + its states arrive via push.
     *  Carries a client idempotency key: rpc() RETRIES on a lost reply, and without the key a
     *  retried M_PAY would queue a SECOND build — one tap, two payments. */
    public JSONObject pay(String zPeer, String zAmount, String zMemo) throws Exception {
        JSONObject p = new JSONObject();
        p.put("peer", zPeer);
        p.put("amount", zAmount);
        p.put("memo", zMemo == null ? "" : zMemo);
        p.put("pid", java.util.UUID.randomUUID().toString());
        return rpc(ParlonsControl.M_PAY, p);
    }

    /** Reveal the account's 24-word seed to THIS paired device (explicit confirm required).
     *  Handle like money: FLAG_SECURE display, sensitive clipboard, never logged. */
    public JSONObject revealSeed() throws Exception {
        JSONObject p = new JSONObject();
        p.put("confirm", true);
        return rpc(ParlonsControl.M_SEED_REVEAL, p);
    }

    /** Export the account's encrypted .pbk backup (PARLONSBK format — phone-compatible).
     *  The node scrypt-encrypts with the given passphrase; returns base64 blob. */
    public JSONObject backupExport(String zPassphrase) throws Exception {
        JSONObject p = new JSONObject();
        p.put("passphrase", zPassphrase);
        JSONObject r = rpc(ParlonsControl.M_BACKUP_EXPORT, p);
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            return r;
        }
        // The bundle is paged (it carries the chat history): stitch every page back together
        // so callers still see one {ok, blob}.
        StringBuilder b64 = new StringBuilder(String.valueOf(r.get("blob")));
        String key = String.valueOf(r.get("key"));
        while (Boolean.TRUE.equals(r.get("more"))) {
            JSONObject next = new JSONObject();
            next.put("key", key);
            next.put("offset", b64.length());
            r = rpc(ParlonsControl.M_BACKUP_EXPORT, next);
            if (!Boolean.TRUE.equals(r.get("ok"))) {
                return r;
            }
            b64.append(String.valueOf(r.get("blob")));
        }
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("blob", b64.toString());
        return out;
    }

    /** Send from the ACCOUNT wallet to any Minima address (pid-idempotent). Result arrives
     *  as a walletsent/walletfail push carrying this pid. */
    public JSONObject walletSend(String zTo, String zAmount, String zPid) throws Exception {
        JSONObject p = new JSONObject();
        p.put("to", zTo);
        p.put("amount", zAmount);
        p.put("pid", zPid);
        return rpc(ParlonsControl.M_WALLET_SEND, p);
    }

    /** Build+SIGN a send on the node (which holds the seed + key-use counter) but do NOT
     *  broadcast — the signed {@code txnimport}/{@code txnpost} blob arrives as a walletbuilt
     *  push (matching this pid), for THIS device to broadcast via its own minimaCore (else
     *  gateway). Keeps signing single-source on the VPS; the relay rides the device's node. */
    public JSONObject buildSend(String zTo, String zAmount, String zPid) throws Exception {
        JSONObject p = new JSONObject();
        p.put("to", zTo);
        p.put("amount", zAmount);
        p.put("pid", zPid);
        return rpc(ParlonsControl.M_WALLET_BUILDSEND, p);
    }

    /** Account settings: {readReceipts}. */
    public JSONObject settings() throws Exception {
        return rpc(ParlonsControl.M_SETTINGS_GET, new JSONObject());
    }

    public JSONObject setReadReceipts(boolean zSend) throws Exception {
        JSONObject p = new JSONObject();
        p.put("readReceipts", zSend);
        return rpc(ParlonsControl.M_SETTINGS_SET, p);
    }

    /** Remove a contact from the account (the peer is told, classic-style). */
    public JSONObject removeContact(String zKey) throws Exception {
        JSONObject p = new JSONObject();
        p.put("key", zKey);
        return rpc(ParlonsControl.M_CONTACT_REMOVE, p);
    }

    /** Clear a conversation locally on the account (does NOT unsend or leave a group). */
    public JSONObject clearConversation(String zPeer) throws Exception {
        JSONObject p = new JSONObject();
        p.put("peer", zPeer);
        return rpc(ParlonsControl.M_CHAT_CLEAR, p);
    }

    /** Search contacts, group names and message bodies. Short leash, NO retry ladder — a
     *  keystroke-driven query must never hold the interactive lane for 90s on a bad network. */
    public JSONObject search(String zQuery) throws Exception {
        JSONObject p = new JSONObject();
        p.put("q", zQuery);
        return callOnce(ParlonsControl.M_CHAT_SEARCH, p, 12_000);
    }

    /** Group roster + admins. */
    public JSONObject groupInfo(String zId) throws Exception {
        JSONObject p = new JSONObject();
        p.put("id", zId);
        return rpc(ParlonsControl.M_GROUP_INFO, p);
    }

    /** Rename a group and/or set its member list (admin only). */
    public JSONObject updateGroup(String zId, String zName, java.util.List<String> zMembers)
            throws Exception {
        JSONObject p = new JSONObject();
        p.put("id", zId);
        if (zName != null) {
            p.put("name", zName);
        }
        if (zMembers != null) {
            org.minima.utils.json.JSONArray arr = new org.minima.utils.json.JSONArray();
            arr.addAll(zMembers);
            p.put("members", arr);
        }
        return rpc(ParlonsControl.M_GROUP_UPDATE, p);
    }

    /** Full contact detail (kind, caps, lastSeen, addresses, minima/wallet). */
    public JSONObject contactInfo(String zKey) throws Exception {
        JSONObject p = new JSONObject();
        p.put("key", zKey);
        return rpc(ParlonsControl.M_CONTACT_INFO, p);
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
        return paged(ParlonsControl.M_CONTACTS, "contacts");
    }

    /**
     * Fetch every page of a paged list reply (see the node's M_CONTACTS / M_SUMMARIES): the
     * node caps each page under the wire message ceiling and says "more"/"next"; older nodes
     * send neither and the single reply stands. Pages are merged into one reply object.
     */
    private JSONObject paged(String zMethod, String zArrayKey) throws Exception {
        JSONObject p = new JSONObject();
        JSONObject first = rpc(zMethod, p);
        if (!Boolean.TRUE.equals(first.get("ok")) || !Boolean.TRUE.equals(first.get("more"))) {
            return first;
        }
        org.minima.utils.json.JSONArray all = new org.minima.utils.json.JSONArray();
        Object a0 = first.get(zArrayKey);
        if (a0 instanceof org.minima.utils.json.JSONArray) {
            all.addAll((org.minima.utils.json.JSONArray) a0);
        }
        JSONObject page = first;
        int guard = 0;
        while (Boolean.TRUE.equals(page.get("more")) && guard++ < 100) {
            p.put("offset", String.valueOf(page.get("next")));
            page = rpc(zMethod, p);
            if (!Boolean.TRUE.equals(page.get("ok"))) {
                break;
            }
            Object a = page.get(zArrayKey);
            if (a instanceof org.minima.utils.json.JSONArray) {
                all.addAll((org.minima.utils.json.JSONArray) a);
            }
        }
        first.put(zArrayKey, all);
        first.put("more", false);
        return first;
    }

    public JSONObject addContact(String zAddress) throws Exception {
        JSONObject p = new JSONObject();
        p.put("address", zAddress);
        return rpc(ParlonsControl.M_CONTACT_ADD, p);
    }

    public JSONObject summaries() throws Exception {
        return paged(ParlonsControl.M_SUMMARIES, "summaries");
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

    /**
     * Run ONE node command on the account's embedded Minima node (Terminal IDE). The node runs
     * it on its own lane and pages the output out in pieces under the Maxima package ceiling;
     * this call polls until the command finishes (up to {@code zMaxWaitMs}) and stitches the
     * pages back together, so the result carries the COMPLETE output text under {@code output}
     * (never truncated), plus {@code ms} (the node-side run time) and {@code command}.
     */
    public JSONObject nodeCmd(String zCommand, long zMaxWaitMs) throws Exception {
        JSONObject p = new JSONObject();
        p.put("cmd", zCommand);
        long deadline = System.currentTimeMillis() + zMaxWaitMs;
        JSONObject r = rpc(ParlonsControl.M_NODE_CMD, p);
        while (Boolean.TRUE.equals(r.get("ok")) && Boolean.TRUE.equals(r.get("pending"))) {
            if (System.currentTimeMillis() > deadline) {
                JSONObject slow = new JSONObject();
                slow.put("ok", false);
                slow.put("error", "still running on the node after " + (zMaxWaitMs / 1000)
                        + "s - it carries on there; check with a follow-up command");
                return slow;
            }
            JSONObject poll = new JSONObject();
            poll.put("key", String.valueOf(r.get("key")));
            r = rpc(ParlonsControl.M_NODE_CMD, poll);
        }
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            return r;
        }
        StringBuilder out = new StringBuilder(String.valueOf(r.get("output")));
        String key = String.valueOf(r.get("key"));
        while (Boolean.TRUE.equals(r.get("more"))) {
            JSONObject next = new JSONObject();
            next.put("key", key);
            next.put("offset", out.length());
            r = rpc(ParlonsControl.M_NODE_CMD, next);
            if (!Boolean.TRUE.equals(r.get("ok"))) {
                return r;
            }
            out.append(String.valueOf(r.get("output")));
        }
        JSONObject done = new JSONObject();
        done.put("ok", true);
        done.put("command", zCommand);
        done.put("output", out.toString());
        done.put("ms", r.get("ms"));
        return done;
    }


    /** Progress of a hosted-file upload: bytes sent so far of the total. */
    public interface UploadProgress {
        void onProgress(long zSent, long zTotal);
    }

    /**
     * Host a file on the account's Parlons Node (NFT art): chunked over the channel,
     * offset-idempotent, verified by sha256 on the node before it is placed. Single files are
     * content-addressed ({@code <sha256>.<ext>}); pass a collection id + index for a State-NFT
     * collection folder ({@code c/<id>/<index>.<ext>}). Returns the node's final reply:
     * {@code {ok, done:true, path, url, sha256, size}} — {@code url} is "" when the operator has
     * not set the node's public base.
     */
    public JSONObject nftPut(byte[] zBytes, String zExt, String zCollection, int zIndex,
                             UploadProgress zProgress) throws Exception {
        if (zBytes == null || zBytes.length == 0) throw new IllegalArgumentException("empty file");
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder sha = new StringBuilder();
        for (byte b : md.digest(zBytes)) sha.append(String.format("%02x", b));
        String uid = java.util.UUID.randomUUID().toString().replace("-", "");
        int off = 0;
        JSONObject last = null;
        while (off < zBytes.length) {
            int n = Math.min(MEDIA_CHUNK, zBytes.length - off);
            JSONObject p = new JSONObject();
            p.put("uid", uid);
            p.put("ext", zExt);
            p.put("size", zBytes.length);
            p.put("sha256", sha.toString());
            p.put("off", off);
            p.put("data", java.util.Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(zBytes, off, off + n)));
            if (zCollection != null && !zCollection.isEmpty()) {
                p.put("collection", zCollection);
                p.put("index", zIndex);
            }
            try {
                last = rpc(ParlonsControl.M_NFT_PUT, p, 60_000);
            } catch (Exception transport) {
                // One retry: chunks are offset-idempotent on the node, so re-sending is safe and
                // beats abandoning a part file after a single lost reply.
                last = rpc(ParlonsControl.M_NFT_PUT, p, 60_000);
            }
            if (!Boolean.TRUE.equals(last.get("ok"))) {
                return last;
            }
            off += n;
            if (zProgress != null) zProgress.onProgress(off, zBytes.length);
        }
        return last;
    }

    /** A fresh collection folder on the node: {@code {ok, collection, base}}. */
    public JSONObject nftNewCollection() throws Exception {
        return rpc(ParlonsControl.M_NFT_NEWCOL, new JSONObject());
    }

    public JSONObject nftList() throws Exception {
        return rpc(ParlonsControl.M_NFT_LIST, new JSONObject());
    }

    public JSONObject nftDelete(String zPath) throws Exception {
        JSONObject p = new JSONObject();
        p.put("path", zPath);
        return rpc(ParlonsControl.M_NFT_DELETE, p);
    }

    /** Re-point the account wallet at a new 24-word phrase; the identity stays. Node accounts only. */
    public JSONObject walletResync(String zPhrase) throws Exception {
        JSONObject p = new JSONObject();
        p.put("phrase", zPhrase);
        p.put("confirm", true);
        return rpc(ParlonsControl.M_WALLET_RESYNC, p);
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
