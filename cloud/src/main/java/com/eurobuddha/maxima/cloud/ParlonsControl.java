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
            Contact c = mNode.contact(peer);
            if (c == null) {
                return bytes(err("unknown contact " + peer));
            }
            ChatEngine.Entry e = mChat.send(c, body);
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
    }

    private String nameFor(String peerKey) {
        try {
            Contact c = mNode.contact(peerKey);
            return c == null ? peerKey : c.name;
        } catch (Exception e) {
            return peerKey;
        }
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
