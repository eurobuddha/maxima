package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The owner-only control channel of a Parlons Cloud account.
 *
 * These methods are registered on the node's {@link ServiceRegistry}, so a device drives the
 * account by sending a normal (encrypted, signature-verified) Maxima RPC to the node — there is
 * NO public web port. Every method except {@code parlons.pair} first checks that the caller's
 * signature-verified identity ({@link ServiceRegistry.Request#fromPublicKey}) is a paired device
 * ({@link DevicePairing}); an unpaired caller is refused. Payloads are JSON.
 *
 * Phase 2 surface: pairing (pair / approve / revoke / list / newcode), contacts (list / add),
 * chat (summaries / conversation / send). Wallet request-signature arrives in Phase 4.
 */
public final class ParlonsControl {

    public static final String M_PAIR         = "parlons.pair";          // no auth (bootstrap)
    public static final String M_PAIR_APPROVE = "parlons.pair.approve";
    public static final String M_PAIR_REVOKE  = "parlons.pair.revoke";
    public static final String M_PAIR_LIST    = "parlons.pair.list";
    public static final String M_PAIR_NEWCODE = "parlons.pair.newcode";
    public static final String M_PING         = "parlons.ping";
    public static final String M_CONTACTS     = "parlons.contacts.list";
    public static final String M_CONTACT_ADD  = "parlons.contacts.add";
    public static final String M_SUMMARIES    = "parlons.chat.summaries";
    public static final String M_CONVERSATION = "parlons.chat.conversation";
    public static final String M_SEND         = "parlons.chat.send";
    public static final String M_WALLET_ADDR  = "parlons.wallet.address";
    public static final String M_WALLET_SET   = "parlons.wallet.setwatch";
    public static final String M_WALLET_BAL   = "parlons.wallet.balance";
    public static final String M_NODE_STATUS  = "parlons.node.status";
    public static final String M_SET_NAME     = "parlons.identity.setname";
    public static final String M_CONTACT_RENAME = "parlons.contacts.rename";
    public static final String M_CONTACT_RESOLVE = "parlons.contacts.resolve";
    public static final String M_MARK_READ    = "parlons.chat.markread";
    public static final String M_PUSH_REG     = "parlons.push.register";
    public static final String M_CALL_SIGNAL  = "parlons.call.signal";
    /** The method a DEVICE serves — the cloud dials the device's own node with events. */
    public static final String DEVICE_PUSH    = "parlons.push";
    public static final String M_MEDIA_UP     = "parlons.media.up";
    public static final String M_GROUP_CREATE = "parlons.group.create";

    /**
     * The VPS-node telemetry the account control channel can't read from {@link MaximaNode} alone —
     * uptime, build version, fleet-attach count, relay/mesh state. Supplied by {@link ParlonsCore}
     * (which owns the relay + start clock), so the Node tab shows a node's real superpowers.
     */
    public interface StatusSource {
        long uptimeMillis();
        String version();
        int hosts();
        boolean relayOn();
        int meshPeers();
    }

    private final MaximaNode mNode;
    private final ChatEngine mChat;
    private final DevicePairing mPairing;
    private final WatchWallet mWallet;
    private volatile StatusSource mStatus;

    // ---- the push channel: cloud → device ----
    // Every authorized RPC refreshes the caller's LIVE record (its signature-verified key + the
    // reply addresses it advertised). Events — new messages, delivery-state changes, call
    // signals — are then PUSHED to every recently-live device by dialling its own node's
    // parlons.push service. This is what makes the portal instant instead of a 3s poll, and it
    // is the only way an incoming CALL can ring a device in time.
    private static final long LIVE_MS = 3 * 60_000L;   // a device is "live" this long after its last RPC

    private static final class Live {
        volatile List<String> addrs;
        volatile long seen;
    }

    private final java.util.Map<String, Live> mLive = new java.util.concurrent.ConcurrentHashMap<>();
    /** callId → device key that answered first (first-answer-wins across paired devices). */
    private final java.util.Map<String, String> mCallTaken = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService mPush =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "parlons-push");
                t.setDaemon(true);
                return t;
            });
    /** In-flight chunked media uploads: transfer id → accumulated bytes. */
    private final java.util.Map<String, java.io.ByteArrayOutputStream> mUploads =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ParlonsControl(MaximaNode zNode, ChatEngine zChat, DevicePairing zPairing, WatchWallet zWallet) {
        mNode = zNode;
        mChat = zChat;
        mPairing = zPairing;
        mWallet = zWallet;
    }

    /** Wire the node telemetry source. Set before the node starts serving requests. */
    public void setStatusSource(StatusSource zSource) {
        mStatus = zSource;
    }

    public void registerOn(ServiceRegistry zReg) {
        // --- pairing ---
        zReg.register(M_PAIR, req -> {                     // NO auth: this IS how you get authorized
            JSONObject in = parse(req);
            DevicePairing.Result r = mPairing.requestPair(
                    req.fromPublicKey, str(in, "label"), str(in, "code"));
            JSONObject out = ok();
            out.put("status", r.name().toLowerCase());
            return bytes(out);
        });
        zReg.register(M_PAIR_APPROVE, req -> {
            requireAuth(req);
            boolean done = mPairing.approve(req.fromPublicKey, str(parse(req), "device"));
            return bytes(done ? ok() : err("no such pending device"));
        });
        zReg.register(M_PAIR_REVOKE, req -> {
            requireAuth(req);
            boolean done = mPairing.revoke(req.fromPublicKey, str(parse(req), "device"));
            return bytes(done ? ok() : err("no such device"));
        });
        zReg.register(M_PAIR_NEWCODE, req -> {
            requireAuth(req);
            // The code goes to the operator's ssh (pair-code.txt), NOT back over the wire.
            mPairing.newBootstrapCode();
            JSONObject out = ok();
            out.put("note", "a fresh bootstrap code was written to the node's pair-code.txt");
            return bytes(out);
        });
        zReg.register(M_PAIR_LIST, req -> {
            requireAuth(req);
            JSONArray auth = new JSONArray();
            for (DevicePairing.Device d : mPairing.authorized()) {
                JSONObject o = new JSONObject();
                o.put("key", d.key);
                o.put("label", d.label);
                o.put("pairedAt", d.pairedAt);
                auth.add(o);
            }
            JSONArray pend = new JSONArray();
            for (String k : mPairing.pendingKeys()) {
                pend.add(k);
            }
            JSONObject out = ok();
            out.put("authorized", auth);
            out.put("pending", pend);
            return bytes(out);
        });

        // --- account status ---
        zReg.register(M_PING, req -> {
            requireAuth(req);
            JSONObject out = ok();
            out.put("name", safe(mNode.name()));
            out.put("permanent", safe(permanent()));
            out.put("primary", safe(mNode.primaryAddress()));
            return bytes(out);
        });

        // --- node status: the VPS-king surface (always-on, relay, mesh, paired devices) ---
        zReg.register(M_NODE_STATUS, req -> {
            requireAuth(req);
            JSONObject out = ok();
            out.put("name", safe(mNode.name()));
            out.put("permanent", safe(permanent()));
            out.put("primary", safe(mNode.primaryAddress()));
            StatusSource s = mStatus;
            int hosts = s == null ? 0 : s.hosts();
            out.put("uptime", s == null ? 0L : s.uptimeMillis());
            out.put("version", s == null ? "" : safe(s.version()));
            out.put("hosts", hosts);
            out.put("mailboxHeld", hosts > 0);          // attached => this node holds its routing key
            out.put("relayOn", s != null && s.relayOn());
            out.put("meshPeers", s == null ? 0 : s.meshPeers());
            out.put("pairedDevices", mPairing.authorizedCount());
            return bytes(out);
        });

        // --- identity: set the account's display name (and re-announce to contacts) ---
        zReg.register(M_SET_NAME, req -> {
            requireAuth(req);
            String name = str(parse(req), "name").trim();
            if (name.isEmpty()) {
                return bytes(err("name required"));
            }
            mNode.setName(name);
            // Re-announce to every contact so they see the new name — same as the app's
            // Settings (setName + refreshContacts). Off-thread: it fans out over the network.
            new Thread(mNode::refreshContacts, "parlons-setname-refresh").start();
            JSONObject out = ok();
            out.put("name", name);
            return bytes(out);
        });

        // --- contacts ---
        zReg.register(M_CONTACTS, req -> {
            requireAuth(req);
            JSONArray arr = new JSONArray();
            for (Contact c : mNode.contacts()) {
                JSONObject o = new JSONObject();
                o.put("key", safe(c.publicKey));
                o.put("name", safe(c.name));
                o.put("address", safe(c.primaryAddress()));
                o.put("lastSeen", c.lastSeen);
                arr.add(o);
            }
            JSONObject out = ok();
            out.put("contacts", arr);
            return bytes(out);
        });
        zReg.register(M_CONTACT_ADD, req -> {
            requireAuth(req);
            String address = str(parse(req), "address");
            if (address.isEmpty()) {
                return bytes(err("address required"));
            }
            mNode.introduce(address, true);
            return bytes(ok());
        });
        zReg.register(M_CONTACT_RENAME, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String key = str(in, "key");
            String name = str(in, "name").trim();
            if (key.isEmpty() || name.isEmpty()) {
                return bytes(err("key and name required"));
            }
            Contact c = mNode.contact(key);
            if (c == null) {
                return bytes(err("no such contact"));
            }
            c.name = name;                 // local display-name override
            mNode.storeContact(c);         // persist
            return bytes(ok());
        });
        zReg.register(M_CONTACT_RESOLVE, req -> {
            requireAuth(req);
            String key = str(parse(req), "key");
            Contact c = mNode.contact(key);
            if (c == null) {
                return bytes(err("no such contact"));
            }
            // On-demand heal: ask the contact's OWN directory (their pinned MLS) for their
            // CURRENT address. mlsLookup persists a fresh address at the FRONT of the stored
            // set (MaximaNode.mlsLookup), so every later send stops retrying dead relays.
            // Blocking network call — the device gave us 30s, sendRaw's timeouts fit inside.
            boolean updated = mNode.mlsLookup(c);
            Contact fresh = mNode.contact(key);
            String addr = fresh == null ? "" : safe(fresh.primaryAddress());
            if (!updated && addr.isEmpty()) {
                return bytes(err("their directory has no fresh record — they may be offline"));
            }
            JSONObject out = ok();
            out.put("updated", updated);
            out.put("address", addr);
            return bytes(out);
        });

        // --- chat ---
        zReg.register(M_SUMMARIES, req -> {
            requireAuth(req);
            JSONArray arr = new JSONArray();
            for (ChatEngine.Summary s : mChat.summaries()) {
                JSONObject o = new JSONObject();
                o.put("peer", safe(s.conversation));
                o.put("name", safe(nameFor(s.conversation)));
                o.put("group", mChat.group(s.conversation) != null);
                o.put("last", safe(s.lastBody));
                o.put("lastSender", safe(s.lastSender));
                o.put("lastMine", s.lastMine);
                o.put("time", s.lastTime);
                o.put("unread", s.unread);
                arr.add(o);
            }
            JSONObject out = ok();
            out.put("summaries", arr);
            return bytes(out);
        });
        zReg.register(M_CONVERSATION, req -> {
            requireAuth(req);
            String peer = str(parse(req), "peer");
            JSONArray arr = new JSONArray();
            for (ChatEngine.Entry e : mChat.conversation(peer)) {
                JSONObject o = new JSONObject();
                o.put("id", safe(e.id));
                o.put("sender", safe(e.sender));
                o.put("body", safe(e.body));
                o.put("mine", e.mine);
                o.put("time", e.time);
                o.put("state", safe(e.state));
                arr.add(o);
            }
            JSONObject out = ok();
            out.put("peer", peer);
            out.put("messages", arr);
            return bytes(out);
        });
        zReg.register(M_SEND, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String peer = str(in, "peer");
            String body = str(in, "body");
            if (peer.isEmpty() || body.isEmpty()) {
                return bytes(err("peer and body required"));
            }
            ChatEngine.Entry e;
            if (mChat.group(peer) != null) {
                e = mChat.sendGroup(peer, body);
            } else {
                Contact c = mNode.contact(peer);
                if (c == null) {
                    return bytes(err("unknown contact " + peer));
                }
                e = mChat.send(c, body);
            }
            JSONObject out = ok();
            out.put("id", e == null ? "" : safe(e.id));
            // Honest immediate state: send() is synchronous, so "failed" here means every
            // address AND the inline MLS heal failed — the peer is genuinely unreachable
            // right now. The node's resend heartbeat keeps retrying failed entries for 24h.
            out.put("state", e == null ? "" : safe(e.state));
            return bytes(out);
        });
        zReg.register(M_MARK_READ, req -> {
            requireAuth(req);
            String peer = str(parse(req), "peer");
            if (peer.isEmpty()) {
                return bytes(err("peer required"));
            }
            mChat.markRead(peer);
            return bytes(ok());
        });

        // --- push channel: an explicit heartbeat. requireAuth records the live addresses. ---
        zReg.register(M_PUSH_REG, req -> {
            requireAuth(req);
            return bytes(ok());
        });

        // --- calls: a paired device makes/answers calls AS the account. The device terminates
        //     the WebRTC media itself; we relay the opaque SDP/ICE under the account key. ---
        zReg.register(M_CALL_SIGNAL, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String peer = str(in, "peer");
            String id = str(in, "id");
            String kind = str(in, "kind");
            String payload = str(in, "payload");
            String memo = str(in, "memo");
            if (peer.isEmpty() || id.isEmpty() || kind.isEmpty()) {
                return bytes(err("peer, id and kind required"));
            }
            String dev = new com.eurobuddha.maxima.core.codec.MiniData(req.fromPublicKey).to0xString();
            if ("answer".equals(kind)) {
                // First answer wins across the account's devices; the rest stop ringing.
                String taken = mCallTaken.putIfAbsent(id, dev);
                if (taken != null && !taken.equalsIgnoreCase(dev)) {
                    return bytes(err("answered on another device"));
                }
                JSONObject ev = new JSONObject();
                ev.put("type", "call");
                ev.put("kind", "taken");
                ev.put("ref", id);
                push(ev, dev);
            }
            Contact c = mNode.contact(peer);
            if (c == null) {
                return bytes(err("unknown contact " + peer));
            }
            com.eurobuddha.maxima.core.chat.ChatMessage m =
                    com.eurobuddha.maxima.core.chat.ChatMessage.call(id, kind, payload);
            if (!memo.isEmpty()) {
                m.memo = memo;
            }
            // Fire-and-forget OFF this thread: a signal send blocks up to the socket timeouts on
            // a dead peer, and this handler runs on the transport pump.
            mPush.execute(() -> {
                try { mChat.sendCallSignal(c, m); } catch (Exception ignored) { }
            });
            return bytes(ok());
        });

        // --- media: chunked upload (RPC ceiling is 256K/message), then the node publishes the
        //     blobs (chunks live on the ALWAYS-ON VPS + replicas) and sends the media message. ---
        zReg.register(M_MEDIA_UP, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String tid = str(in, "tid");
            if (tid.isEmpty()) {
                return bytes(err("tid required"));
            }
            byte[] chunk;
            try {
                chunk = java.util.Base64.getDecoder().decode(str(in, "data"));
            } catch (Exception e) {
                return bytes(err("bad chunk encoding"));
            }
            java.io.ByteArrayOutputStream buf =
                    mUploads.computeIfAbsent(tid, k -> new java.io.ByteArrayOutputStream());
            synchronized (buf) {
                if (buf.size() + chunk.length > 16 * 1024 * 1024) {
                    mUploads.remove(tid);
                    return bytes(err("media too big (16MB max)"));
                }
                try { buf.write(chunk); } catch (java.io.IOException ignored) { }
            }
            if (!bool(in, "last")) {
                JSONObject out = ok();
                out.put("got", buf.size());
                return bytes(out);
            }
            mUploads.remove(tid);
            final String peer = str(in, "peer");
            final String mime = str(in, "mime");
            final String caption = str(in, "caption");
            final boolean group = bool(in, "group");
            final byte[] media = buf.toByteArray();
            if (peer.isEmpty() || mime.isEmpty() || media.length == 0) {
                return bytes(err("peer, mime and data required"));
            }
            if (!group && mNode.contact(peer) == null) {
                return bytes(err("unknown contact " + peer));
            }
            // publish + replicate can take up to ~55s — never on the pump thread. The result
            // reaches the device through the normal conversation state (+ push).
            mPush.execute(() -> {
                try {
                    if (group) {
                        mChat.sendGroupMedia(peer, media, mime, caption);
                    } else {
                        mChat.sendMedia(mNode.contact(peer), media, mime, caption);
                    }
                } catch (Exception e) {
                    // surfaced as a failed entry state by the engine where possible
                }
            });
            JSONObject out = ok();
            out.put("size", media.length);
            out.put("status", "publishing");
            return bytes(out);
        });

        // --- groups: core is fully group-capable; expose create (roster pushes to members). ---
        zReg.register(M_GROUP_CREATE, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String name = str(in, "name").trim();
            JSONArray mems = (JSONArray) in.get("members");
            if (name.isEmpty() || mems == null || mems.isEmpty()) {
                return bytes(err("name and members required"));
            }
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (Object o : mems) {
                keys.add(String.valueOf(o));
            }
            com.eurobuddha.maxima.core.chat.Group g = mChat.createGroup(name, keys);
            JSONObject out = ok();
            out.put("id", g.id);
            out.put("name", g.name);
            return bytes(out);
        });

        // --- watch-only wallet (funds stay COLD on the device; the node only reads) ---
        zReg.register(M_WALLET_ADDR, req -> {
            requireAuth(req);
            JSONObject out = ok();
            out.put("address", safe(mWallet.watchAddress()));
            return bytes(out);
        });
        zReg.register(M_WALLET_SET, req -> {
            requireAuth(req);
            String address = str(parse(req), "address");
            if (address.isEmpty()) {
                return bytes(err("address required"));
            }
            mWallet.setWatchAddress(address);
            return bytes(ok());
        });
        zReg.register(M_WALLET_BAL, req -> {
            requireAuth(req);
            JSONObject bal = mWallet.balance();   // gateway read; throws if unset → ERROR envelope
            JSONObject out = ok();
            out.put("address", safe(mWallet.watchAddress()));
            out.put("balance", bal);
            return bytes(out);
        });
    }

    // ---- helpers ----

    private void requireAuth(ServiceRegistry.Request req) {
        if (!mPairing.isAuthorized(req.fromPublicKey)) {
            // Thrown → dispatch turns it into an ERROR envelope for the caller.
            throw new SecurityException("unpaired device — not authorized for this account");
        }
        // Every authorized round-trip refreshes this device's live record for the push channel.
        if (req.replyTo != null && !req.replyTo.isEmpty()) {
            String key = new com.eurobuddha.maxima.core.codec.MiniData(req.fromPublicKey).to0xString();
            Live l = mLive.computeIfAbsent(key, k -> new Live());
            l.addrs = new java.util.ArrayList<>(req.replyTo);
            l.seen = System.currentTimeMillis();
        }
    }

    // ---- push: cloud → devices ----

    private boolean anyLive() {
        long now = System.currentTimeMillis();
        for (Live l : mLive.values()) {
            if (now - l.seen < LIVE_MS) {
                return true;
            }
        }
        return false;
    }

    /** Fire one event at every live device (all its reply addresses — the device dedups by eid). */
    private void push(JSONObject event) {
        push(event, null);
    }

    private void push(JSONObject event, String zExceptDeviceKey) {
        event.put("eid", java.util.UUID.randomUUID().toString());
        final byte[] bytes = event.toString().getBytes(StandardCharsets.UTF_8);
        final long now = System.currentTimeMillis();
        mPush.execute(() -> {
            for (java.util.Map.Entry<String, Live> en : mLive.entrySet()) {
                if (zExceptDeviceKey != null && en.getKey().equalsIgnoreCase(zExceptDeviceKey)) {
                    continue;
                }
                Live l = en.getValue();
                if (now - l.seen > LIVE_MS || l.addrs == null) {
                    continue;
                }
                for (String addr : l.addrs) {
                    try {
                        mNode.rpc().call(addr, DEVICE_PUSH, bytes,
                                new com.eurobuddha.maxima.core.rpc.RpcPeer.ResponseHandler() {
                                    public void onResponse(byte[] p) { }
                                    public void onError(String m) { }
                                }, 10_000);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /** New inbound message on the account → tell every live device NOW (instant chat + notification). */
    public void pushMessage(ChatEngine.Entry e) {
        JSONObject ev = new JSONObject();
        ev.put("type", "message");
        ev.put("peer", e.isGroup() ? e.groupId : e.peer);
        ev.put("group", e.isGroup());
        ev.put("sender", safe(e.sender));
        ev.put("name", nameFor(e.isGroup() ? e.groupId : e.peer));
        String body = safe(e.body);
        ev.put("body", body.length() > 500 ? body.substring(0, 500) : body);
        ev.put("id", safe(e.id));
        ev.put("time", e.time);
        push(ev);
    }

    /** A delivery-state change (✓ → ✓✓ → read) → live tick updates on every device. */
    public void pushState(ChatEngine.Entry e) {
        JSONObject ev = new JSONObject();
        ev.put("type", "state");
        ev.put("peer", e.isGroup() ? e.groupId : e.peer);
        ev.put("id", safe(e.id));
        ev.put("state", safe(e.state));
        push(ev);
    }

    /**
     * A call signal from a PEER arrived at the account. The account's phone is this node — but
     * the humans are on paired devices, so forward the signal verbatim; the device terminates
     * the WebRTC media itself (the SDP/ICE payloads are opaque to us). If NO device is live, an
     * offer is declined immediately — honest, instead of letting the caller ring out for 45s.
     */
    public void forwardCallSignal(String zFromKey, com.eurobuddha.maxima.core.chat.ChatMessage cm) {
        if ("offer".equals(cm.state)) {
            // Only a known contact may ring the account's devices — an authenticated stranger
            // who knows our key must not drive full-screen rings (same rule as the app).
            if (mNode.contact(zFromKey) == null) {
                return;
            }
            if (!anyLive()) {
                declineCall(zFromKey, cm.ref);
                return;
            }
        }
        JSONObject ev = new JSONObject();
        ev.put("type", "call");
        ev.put("from", safe(zFromKey));
        ev.put("name", nameFor(zFromKey));
        ev.put("ref", safe(cm.ref));
        ev.put("kind", safe(cm.state));
        ev.put("payload", safe(cm.body));
        ev.put("memo", safe(cm.memo));
        ev.put("time", cm.time);
        push(ev);
    }

    private void declineCall(String zPeerKey, String zRef) {
        mPush.execute(() -> {
            try {
                Contact c = mNode.contact(zPeerKey);
                if (c != null) {
                    mChat.sendCallSignal(c,
                            com.eurobuddha.maxima.core.chat.ChatMessage.call(zRef, "bye", ""));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private String nameFor(String peerKey) {
        try {
            com.eurobuddha.maxima.core.chat.Group g = mChat.group(peerKey);
            if (g != null && g.name != null && !g.name.isEmpty()) {
                return g.name;
            }
            Contact c = mNode.contact(peerKey);
            return c == null ? peerKey : c.name;
        } catch (Exception e) {
            return peerKey;
        }
    }

    private static boolean bool(JSONObject o, String key) {
        Object v = o.get(key);
        return v instanceof Boolean && (Boolean) v;
    }

    private String permanent() {
        try {
            return mNode.permanentAddress();
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject parse(ServiceRegistry.Request req) {
        try {
            if (req.payload == null || req.payload.length == 0) {
                return new JSONObject();
            }
            Object o = new JSONParser().parse(new String(req.payload, StandardCharsets.UTF_8));
            return o instanceof JSONObject ? (JSONObject) o : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static JSONObject ok() {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        return o;
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("error", msg);
        return o;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static byte[] bytes(JSONObject o) {
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }
}
