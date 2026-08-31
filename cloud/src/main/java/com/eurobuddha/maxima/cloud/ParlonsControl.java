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
    public static final String M_SETTINGS_GET = "parlons.settings.get";
    public static final String M_SETTINGS_SET = "parlons.settings.set";
    public static final String M_CONTACT_REMOVE = "parlons.contacts.remove";
    public static final String M_PAY          = "parlons.chat.pay";

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

    private static final class Taken {
        final String device;
        final long at = System.currentTimeMillis();
        Taken(String zDevice) { device = zDevice; }
    }

    /** callId → first answering device + when (first-answer-wins; swept after 10 min). */
    private final java.util.Map<String, Taken> mCallTaken = new java.util.concurrent.ConcurrentHashMap<>();

    // ---- executor LANES. One shared thread let a 55s media publish bury a call offer, and a
    // push to one dead device address (20s blocking connect) starved everything behind it.
    /** Latency-critical: call-signal relay + declines. Nothing slow may ever run here. */
    private final java.util.concurrent.ExecutorService mCallExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "parlons-call-relay");
                t.setDaemon(true);
                return t;
            });
    /** Chat sends, group roster fan-out, read receipts — sequential, may block on a dead peer. */
    private final java.util.concurrent.ExecutorService mSendExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "parlons-chat-send");
                t.setDaemon(true);
                return t;
            });
    /** Media publish+replicate (up to ~55s each) — its own lane. */
    private final java.util.concurrent.ExecutorService mMediaExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "parlons-media-publish");
                t.setDaemon(true);
                return t;
            });
    /** Push fan-out: one task per device so one dead device can't stall the others. */
    private final java.util.concurrent.ExecutorService mPushPool =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "parlons-push");
                t.setDaemon(true);
                return t;
            });

    private static final class Upload {
        final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        volatile long touched = System.currentTimeMillis();
    }

    /** In-flight chunked media uploads: transfer id → buffer (idle entries swept). */
    private final java.util.Map<String, Upload> mUploads =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** COMPLETED uploads (tid → final ack json + when): a retried last chunk whose reply was
     *  lost must replay the stored ack, not re-publish the media twice. */
    private final java.util.Map<String, Object[]> mDoneUploads =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Recent group creations (name → at): a retried create must not mint a duplicate. */
    private final java.util.Map<String, Long> mRecentGroups =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Called from the node's maintenance pump: sweep idle uploads / stale call + group records. */
    public void maintenanceSweep() {
        long now = System.currentTimeMillis();
        mUploads.entrySet().removeIf(e -> now - e.getValue().touched > 10 * 60_000L);
        mDoneUploads.entrySet().removeIf(e -> now - (Long) e.getValue()[1] > 5 * 60_000L);
        mCallTaken.entrySet().removeIf(e -> now - e.getValue().at > 10 * 60_000L);
        mRecentGroups.entrySet().removeIf(e -> now - e.getValue() > 5 * 60_000L);
    }

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

    /** Account settings owned by {@link ParlonsCore} (it persists them across restarts). */
    public interface SettingsSink {
        boolean readReceipts();
        void setReadReceipts(boolean zSend);
    }

    private volatile SettingsSink mSettingsSink;

    public void setSettingsSink(SettingsSink zSink) {
        mSettingsSink = zSink;
    }

    /** The account's own wallet (the Parlons pattern: the seed IS the wallet). Wired by
     *  {@link ParlonsCore} once the heavy WOTS derivation has run off-thread. */
    public interface PaySource {
        String myWalletAddress();          // Mx… receive address ("" until derived)
        CloudPaymentSender sender();       // null until the wallet is open
    }

    private volatile PaySource mPaySource;

    public void setPaySource(PaySource zSource) {
        mPaySource = zSource;
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
            String device = str(parse(req), "device");
            boolean done = mPairing.revoke(req.fromPublicKey, device);
            if (done) {
                // Cut the push feed IMMEDIATELY — a revoked device must not keep receiving
                // message bodies for the rest of its live window.
                mLive.keySet().removeIf(k -> k.equalsIgnoreCase(device));
            }
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
                // Bounded reply: a full media body is a multi-KB manifest, and the WHOLE reply
                // must fit one 256K wire message or it silently black-holes. Previews only.
                String last = safe(s.lastBody);
                if (com.eurobuddha.maxima.core.chat.ChatMedia.isMedia(last)) {
                    last = com.eurobuddha.maxima.core.chat.ChatMedia.preview(last);
                } else if (last.length() > 200) {
                    last = last.substring(0, 200);
                }
                o.put("last", last);
                o.put("lastSender", safe(s.lastSender));
                o.put("lastMine", s.lastMine);
                o.put("time", s.lastTime);
                o.put("unread", s.unread);
                arr.add(o);
            }
            // Groups with no messages yet still belong in the list — the app shows groups()
            // alongside summaries; without this a freshly created group was invisible.
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Object o : arr) {
                seen.add(String.valueOf(((JSONObject) o).get("peer")));
            }
            for (com.eurobuddha.maxima.core.chat.Group g : mChat.groups()) {
                if (seen.contains(g.id)) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("peer", safe(g.id));
                o.put("name", safe(g.name));
                o.put("group", true);
                o.put("last", "");
                o.put("lastMine", false);
                o.put("time", g.lastActivity);
                o.put("unread", 0);
                arr.add(o);
            }
            JSONObject out = ok();
            out.put("summaries", arr);
            return bytes(out);
        });
        zReg.register(M_CONVERSATION, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String peer = str(in, "peer");
            // Bounded reply (256K wire ceiling): newest `limit` entries, optional `before`
            // time-cursor for paging back. History only grows — unbounded replies would one
            // day black-hole and the conversation would never load again.
            int limit = (int) lngOf(in, "limit");
            if (limit <= 0 || limit > 200) {
                limit = 100;
            }
            long before = lngOf(in, "before");
            long after = lngOf(in, "after");
            java.util.List<ChatEngine.Entry> entries = mChat.conversation(peer);
            entries.sort((a, b) -> Long.compare(a.time, b.time));
            if (before > 0) {
                entries.removeIf(e -> e.time >= before);
            }
            if (after > 0) {
                // Delta poll: only entries NEWER than the cursor — tiny replies, so the
                // fallback poll stops re-shipping the whole page every few seconds.
                entries.removeIf(e -> e.time <= after);
            }
            if (entries.size() > limit) {
                entries = entries.subList(entries.size() - limit, entries.size());
            }
            JSONArray arr = new JSONArray();
            for (ChatEngine.Entry e : entries) {
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
            final boolean isGroup = mChat.group(peer) != null;
            if (!isGroup && mNode.contact(peer) == null) {
                return bytes(err("unknown contact " + peer));
            }
            // A send blocks up to the socket timeouts (×addresses, ×members for a group) — on
            // the pump thread that deafened the WHOLE node whenever a peer was offline. Queue
            // it on the send lane; the honest delivery state reaches the device moments later
            // as a push (setState fires the listener) + the conversation poll.
            final String fpeer = peer;
            final String fbody = body;
            final Contact fc = isGroup ? null : mNode.contact(peer);   // pre-checked: never null here
            mSendExec.execute(() -> {
                try {
                    if (isGroup) {
                        mChat.sendGroup(fpeer, fbody);
                    } else {
                        mChat.send(fc, fbody);
                    }
                } catch (Exception ignored) {
                }
            });
            JSONObject out = ok();
            out.put("state", "queued");
            return bytes(out);
        });
        zReg.register(M_MARK_READ, req -> {
            requireAuth(req);
            String peer = str(parse(req), "peer");
            if (peer.isEmpty()) {
                return bytes(err("peer required"));
            }
            // markRead SENDS the read receipt — off the pump, on the send lane.
            mSendExec.execute(() -> {
                try { mChat.markRead(peer); } catch (Exception ignored) { }
            });
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
                Taken taken = mCallTaken.putIfAbsent(id, new Taken(dev));
                if (taken != null && !taken.device.equalsIgnoreCase(dev)) {
                    return bytes(err("answered on another device"));
                }
                JSONObject ev = new JSONObject();
                ev.put("type", "call");
                ev.put("kind", "taken");
                ev.put("ref", id);
                push(ev, dev);
            } else if ("bye".equals(kind) && !mCallTaken.containsKey(id)) {
                // A DECLINE from one device stops the others ringing too — without this the
                // siblings ring out their full 45s for a call already refused.
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
            // The dedicated CALL lane: never queued behind pushes or media publishes, and off
            // this thread because a signal send blocks on a dead peer's socket timeouts.
            mNode.log("call relay " + kind + " → " + safe(c.name) + " (device→peer)");
            mCallExec.execute(() -> {
                try {
                    mChat.sendCallSignal(c, m);
                } catch (Exception e) {
                    mNode.log("call relay " + kind + " to " + safe(c.name) + " FAILED: "
                            + (e.getMessage() == null ? e.toString() : e.getMessage()));
                }
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
            long off = lngOf(in, "off");
            // A retried LAST chunk whose reply was lost: the upload is done and the media
            // already sent — replay the stored ack instead of re-publishing a duplicate.
            Object[] done = mDoneUploads.get(tid);
            if (done != null) {
                return bytes((JSONObject) done[0]);
            }
            Upload up = mUploads.computeIfAbsent(tid, k -> new Upload());
            up.touched = System.currentTimeMillis();
            synchronized (up.buf) {
                // Offset idempotency: an RPC retry whose original REQUEST was processed (only
                // the reply got lost) re-sends the same chunk — appending it blindly corrupted
                // the media. A duplicate (off < size) is acked as already-received; a gap fails.
                if (off < up.buf.size()) {
                    JSONObject out = ok();
                    out.put("got", up.buf.size());
                    return bytes(out);
                }
                if (off > up.buf.size()) {
                    mUploads.remove(tid);
                    return bytes(err("chunk gap — resend the media"));
                }
                if (up.buf.size() + chunk.length > 16 * 1024 * 1024) {
                    mUploads.remove(tid);
                    return bytes(err("media too big (16MB max)"));
                }
                try { up.buf.write(chunk); } catch (java.io.IOException ignored) { }
            }
            if (!bool(in, "last")) {
                JSONObject out = ok();
                out.put("got", up.buf.size());
                return bytes(out);
            }
            mUploads.remove(tid);
            final String peer = str(in, "peer");
            final String mime = str(in, "mime");
            final String caption = str(in, "caption");
            final boolean group = bool(in, "group");
            final byte[] media = up.buf.toByteArray();
            if (peer.isEmpty() || mime.isEmpty() || media.length == 0) {
                return bytes(err("peer, mime and data required"));
            }
            if (!group && mNode.contact(peer) == null) {
                return bytes(err("unknown contact " + peer));
            }
            // publish + replicate can take up to ~55s — the media lane, never the pump thread.
            // The result reaches the device through the conversation state (+ push).
            mMediaExec.execute(() -> {
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
            mDoneUploads.put(tid, new Object[]{out, System.currentTimeMillis()});
            return bytes(out);
        });

        // --- in-chat payments: the Parlons pattern — the account seed IS the wallet. Build +
        //     sign on THIS node, publish via the read+relay gateway, bubble via ChatPay. ---
        zReg.register(M_PAY, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String peer = str(in, "peer");
            String amount = str(in, "amount").trim();
            final String memo = str(in, "memo");
            final Contact c = mNode.contact(peer);
            if (c == null) {
                return bytes(err("unknown contact " + peer));
            }
            if (mChat.group(peer) != null) {
                return bytes(err("payments are one-to-one for now"));
            }
            PaySource ps = mPaySource;
            if (ps == null || ps.sender() == null) {
                return bytes(err("the account wallet is still opening — try again in a moment"));
            }
            final org.minima.objects.base.MiniNumber amt;
            try {
                amt = new org.minima.objects.base.MiniNumber(amount);
            } catch (Exception e) {
                return bytes(err("that amount doesn't look right"));
            }
            if (amt.isLessEqual(org.minima.objects.base.MiniNumber.ZERO)) {
                return bytes(err("the amount must be more than zero"));
            }
            final String to = mChat.walletAddress(peer);
            if (to == null || to.isEmpty()) {
                return bytes(err("no wallet address from them yet — ask them to open this chat"));
            }
            final CloudPaymentSender sender = ps.sender();
            // Build+sign+publish are blocking network work — the send lane, never the pump.
            // States flow to devices as pushes: QUEUED bubble at sign time, SENT on publish,
            // FAILED (or a payfail toast) if anything breaks.
            mSendExec.execute(() -> {
                ChatEngine.Entry e = null;
                try {
                    CloudPaymentSender.Built built = sender.build(to, amt);
                    e = mChat.beginPayment(c, amt.toString(), "Minima", memo, built.txid);
                    sender.publish(built);
                    mChat.completePayment(c, e);
                    mNode.log("payment " + amt + " → " + safe(c.name) + " txid " + built.txid);
                } catch (Exception ex) {
                    String why = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    if (e != null) {
                        mChat.failPayment(e);
                    }
                    mNode.log("payment to " + safe(c.name) + " FAILED: " + why);
                    JSONObject ev = new JSONObject();
                    ev.put("type", "payfail");
                    ev.put("peer", safe(c.publicKey));
                    ev.put("error", why);
                    push(ev);
                }
            });
            JSONObject out = ok();
            out.put("state", "building");
            return bytes(out);
        });

        // --- account settings (persisted by ParlonsCore across restarts) ---
        zReg.register(M_SETTINGS_GET, req -> {
            requireAuth(req);
            SettingsSink s = mSettingsSink;
            JSONObject out = ok();
            out.put("readReceipts", s != null && s.readReceipts());
            return bytes(out);
        });
        zReg.register(M_SETTINGS_SET, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            SettingsSink s = mSettingsSink;
            if (s == null) {
                return bytes(err("settings unavailable"));
            }
            Object rr = in.get("readReceipts");
            if (rr instanceof Boolean) {
                s.setReadReceipts((Boolean) rr);
            }
            JSONObject out = ok();
            out.put("readReceipts", s.readReceipts());
            return bytes(out);
        });

        // --- contacts: remove (tells the peer, classic-style; network send off the pump) ---
        zReg.register(M_CONTACT_REMOVE, req -> {
            requireAuth(req);
            String key = str(parse(req), "key");
            if (key.isEmpty() || mNode.contact(key) == null) {
                return bytes(err("no such contact"));
            }
            mSendExec.execute(() -> {
                try { mNode.removeContact(key); } catch (Exception ignored) { }
            });
            return bytes(ok());
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
            final java.util.List<String> keys = new java.util.ArrayList<>();
            for (Object o : mems) {
                keys.add(String.valueOf(o));
            }
            // createGroup pushes the roster to every member SYNCHRONOUSLY (20s+ per offline
            // member) — on the pump thread that deafens the whole node and the client's 35s
            // retry then minted a DUPLICATE group. Run it on the send lane; a retry inside the
            // dedup window is acknowledged, not repeated.
            Long recent = mRecentGroups.get(name);
            if (recent != null && System.currentTimeMillis() - recent < 60_000) {
                JSONObject out = ok();
                out.put("name", name);
                out.put("status", "creating");
                return bytes(out);
            }
            mRecentGroups.put(name, System.currentTimeMillis());   // fresh window each real create
            mSendExec.execute(() -> {
                try { mChat.createGroup(name, keys); } catch (Exception ignored) { }
            });
            JSONObject out = ok();
            out.put("name", name);
            out.put("status", "creating");
            return bytes(out);
        });

        // --- the account wallet: receive = the account's own address (the Parlons pattern);
        //     a device can still point the WATCH at a different (cold) address instead. ---
        zReg.register(M_WALLET_ADDR, req -> {
            requireAuth(req);
            PaySource ps = mPaySource;
            String own = ps == null ? "" : safe(ps.myWalletAddress());
            String watch = safe(mWallet.watchAddress());
            JSONObject out = ok();
            out.put("address", watch.isEmpty() ? own : watch);
            out.put("own", own);
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
            PaySource ps = mPaySource;
            String own = ps == null ? "" : safe(ps.myWalletAddress());
            String watch = safe(mWallet.watchAddress());
            String addr = watch.isEmpty() ? own : watch;
            if (addr.isEmpty()) {
                return bytes(err("wallet still opening — try again in a moment"));
            }
            JSONObject bal = mWallet.cmd("balance megammr:true address:" + addr);
            JSONObject out = ok();
            out.put("address", addr);
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
        for (java.util.Map.Entry<String, Live> en : mLive.entrySet()) {
            if (zExceptDeviceKey != null && en.getKey().equalsIgnoreCase(zExceptDeviceKey)) {
                continue;
            }
            final Live l = en.getValue();
            if (now - l.seen > LIVE_MS || l.addrs == null) {
                continue;
            }
            // One pool task PER DEVICE: a dead device's blocking connects delay only itself.
            mPushPool.execute(() -> {
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
            });
        }
    }

    /** New inbound message on the account → tell every live device NOW (instant chat + notification). */
    public void pushMessage(ChatEngine.Entry e) {
        JSONObject ev = new JSONObject();
        ev.put("type", "message");
        ev.put("peer", e.isGroup() ? e.groupId : e.peer);
        ev.put("group", e.isGroup());
        ev.put("sender", safe(e.sender));
        ev.put("name", nameFor(e.isGroup() ? e.groupId : e.peer));
        // FULL body: the portal now renders pushed messages directly, and a media body's
        // manifest routinely exceeds any preview cap — truncation broke pushed photo bubbles.
        // An inline chat message already fit one wire message; the 256K ceiling is far away.
        ev.put("body", safe(e.body));
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
        mNode.log("call " + safe(cm.state) + " from " + nameFor(zFromKey) + " → pushing to devices");
        push(ev);
    }

    private void declineCall(String zPeerKey, String zRef) {
        mCallExec.execute(() -> {
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

    private static long lngOf(JSONObject o, String key) {
        Object v = o.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
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
