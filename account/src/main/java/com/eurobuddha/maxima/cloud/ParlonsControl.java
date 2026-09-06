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
    /** iOS: register/clear this device's APNs wake token and the proxy it chose. */
    public static final String M_PUSH_APNS    = "parlons.push.apns";
    /** Cross-conversation catch-up: entries newer than a cursor, paged (the iOS resume path). */
    public static final String M_CHAT_SINCE   = "parlons.chat.since";
    public static final String M_CALL_SIGNAL  = "parlons.call.signal";
    /** The method a DEVICE serves — the cloud dials the device's own node with events. */
    public static final String DEVICE_PUSH    = "parlons.push";
    public static final String M_MEDIA_UP     = "parlons.media.up";
    public static final String M_GROUP_CREATE = "parlons.group.create";
    public static final String M_SETTINGS_GET = "parlons.settings.get";
    public static final String M_SETTINGS_SET = "parlons.settings.set";
    public static final String M_CONTACT_REMOVE = "parlons.contacts.remove";
    public static final String M_PAY          = "parlons.chat.pay";
    public static final String M_SEED_REVEAL  = "parlons.seed.reveal";
    public static final String M_BACKUP_EXPORT = "parlons.backup.export";
    public static final String M_WALLET_SEND  = "parlons.wallet.send";
    public static final String M_WALLET_BUILDSEND = "parlons.wallet.buildsend";
    public static final String M_CHAT_CLEAR   = "parlons.chat.clear";
    public static final String M_CHAT_SEARCH  = "parlons.chat.search";
    public static final String M_GROUP_INFO   = "parlons.group.info";
    public static final String M_GROUP_UPDATE = "parlons.group.update";
    public static final String M_CONTACT_INFO = "parlons.contacts.info";
    public static final String M_NODE_LOG     = "parlons.node.log";
    public static final String M_NODE_FIGURES = "parlons.node.figures";
    public static final String M_NODE_HOSTS   = "parlons.node.hosts";
    public static final String M_NODE_MLS     = "parlons.node.mls";
    public static final String M_WALLET_TOKENS = "parlons.wallet.tokens";
    public static final String M_WALLET_USES  = "parlons.wallet.uses";
    /** Re-point the account WALLET at a new phrase; the identity stays (node accounts only). */
    public static final String M_WALLET_RESYNC = "parlons.wallet.resync";
    public static final String M_NODE_CMD     = "parlons.node.cmd";      // Terminal IDE: any node command
    public static final String M_NFT_PUT      = "parlons.nft.put";       // host NFT art on the node (chunked)
    public static final String M_NFT_NEWCOL   = "parlons.nft.newcollection";
    public static final String M_NFT_LIST     = "parlons.nft.list";
    public static final String M_NFT_DELETE   = "parlons.nft.delete";

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
    private final AccountWallet mWallet;
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
        /** address -> consecutive push failures; an address failing PUSH_ADDR_FAILS times in a
         *  row is skipped until the device's next RPC refreshes its address list. */
        final java.util.Map<String, Integer> failures = new java.util.concurrent.ConcurrentHashMap<>();
    }
    private static final int PUSH_ADDR_FAILS = 3;
    /** Push socket leashes: a device that vanished behind NAT fails in seconds, not 40. */
    private static final int PUSH_CONNECT_MS = 4_000;
    private static final int PUSH_READ_MS = 6_000;

    private final java.util.Map<String, Live> mLive = new java.util.concurrent.ConcurrentHashMap<>();
    /** The iOS wake path (content-free APNs via the proxy each device chose). */
    private final WakeProxyClient mWake = new WakeProxyClient();
    /** Page size for parlons.chat.since. */
    static final int PAGE_SINCE = 100;

    public WakeProxyClient wakeProxy() {
        return mWake;
    }

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
    /** Chat sends, group roster fan-out, read receipts, wallet ops — KEYED lanes: work for one
     *  peer (or "wallet" / "mls" / "group:<name>") stays in order, different keys run in
     *  parallel (4 threads). One thread for all of it meant a send to an offline peer, blocking
     *  on its socket timeouts, held every payment, receipt and balance refresh behind it. */
    private final com.eurobuddha.maxima.core.util.SerialLanes mSendExec =
            new com.eurobuddha.maxima.core.util.SerialLanes("parlons-send", 4);
    /** Media publish+replicate (up to ~55s each) — its own lane. */
    private final java.util.concurrent.ExecutorService mMediaExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "parlons-media-publish");
                t.setDaemon(true);
                return t;
            });
    /** Push fan-out: one task per device so one dead device can't stall the others. */
    private final java.util.concurrent.ExecutorService mPushPool =
            new java.util.concurrent.ThreadPoolExecutor(4, 16, 60, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(32),   // grows to 16 threads under load
                    r -> {
                        Thread t = new Thread(r, "parlons-push");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    /** State ticks (sent / delivered / read) burst: one push per entry per window, not per tick. */
    private final java.util.Map<String, JSONObject> mStateCoalesce = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean mStateFlushScheduled = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ScheduledExecutorService mStateFlusher =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "parlons-push-state");
                t.setDaemon(true);
                return t;
            });
    private static final long STATE_COALESCE_MS = 400;

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
    /** Payment idempotency (pid → when): a retried M_PAY (lost reply, relay replay) must be
     *  acked, never re-queued — one tap must never pay twice. FUND-CRITICAL. */
    private final java.util.Map<String, Long> mRecentPays =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Balance cache (address → {json, at}) so M_WALLET_BAL never blocks the pump. */
    private final java.util.Map<String, Object[]> mBalanceCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> mBalanceFetching =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Called from the node's maintenance pump: sweep idle uploads / stale call + group records. */
    public void maintenanceSweep() {
        long now = System.currentTimeMillis();
        mUploads.entrySet().removeIf(e -> now - e.getValue().touched > 10 * 60_000L);
        mDoneUploads.entrySet().removeIf(e -> now - (Long) e.getValue()[1] > 5 * 60_000L);
        mCallTaken.entrySet().removeIf(e -> now - e.getValue().at > 10 * 60_000L);
        mRecentGroups.entrySet().removeIf(e -> now - e.getValue() > 5 * 60_000L);
        mRecentPays.entrySet().removeIf(e -> now - e.getValue() > 30 * 60_000L);
    }

    public ParlonsControl(MaximaNode zNode, ChatEngine zChat, DevicePairing zPairing, AccountWallet zWallet) {
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
        boolean ready();                   // false until the wallet is open (payments refused)
        String walletError();              // "" unless the wallet failed to open
        int uses();                        // key uses so far (-1 if wallet not open)
        void raiseUsesTo(int zTo);         // raise-only counter adjust
        String walletScript();             // the account address's spend script (a device tracks it)
        String walletHex();                // the account address as 0x hex (for a device's coin reads)
    }

    private volatile PaySource mPaySource;

    public void setPaySource(PaySource zSource) {
        mPaySource = zSource;
    }

    /** Identity backup surface, wired from {@link ParlonsCore} (it owns the data dir). */
    public interface BackupSource {
        String revealPhrase() throws Exception;
        byte[] exportBackup(char[] zPassword) throws Exception;
    }

    private volatile BackupSource mBackupSource;

    public void setBackupSource(BackupSource zSource) {
        mBackupSource = zSource;
    }

    /** The VPS control surface — node figures, hosts, event log, relay stats (owned by
     *  {@link ParlonsCore}, which holds the pool + relay + log ring). */
    public interface NodeControl {
        java.util.List<String> recentLog(int zMax);
        void clearLog();
        /** A node's own public relay (its cape), "" on the cloud. */
        default String ownRelay() { return ""; }
        default boolean ownRelayAttached() { return false; }
        /** Attached AND proven to relay to us (the self-addressed check-connect). */
        default boolean ownRelayVerified() { return false; }
        java.util.List<String> hosts();             // currently attached host:port list
        java.util.List<String> configuredHosts();   // the seeds (yours, then built-in if on) + attached
        boolean addHost(String zHostPort);          // runtime attach; returns accepted
        boolean removeHost(String zHostPort);       // runtime detach
        /** The compiled-in relay list is one seed source among several: on or off. */
        default boolean builtInRelays() { return true; }
        /** Returns false when refused (switching off with no relay of your own configured). */
        default boolean setBuiltInRelays(boolean zOn) { return false; }
        int mailboxHeld();
        int outboxDepth();
        boolean directlyReachable();
        String directAddress();
        java.util.List<String> meshPeers();
        // relay stats (0 if the relay is off)
        int relayConnections();
        long relayRelayed();
        long relayStored();
    }

    private volatile NodeControl mNodeControl;

    public void setNodeControl(NodeControl zControl) {
        mNodeControl = zControl;
    }

    /**
     * The embedded Minima node's command line, for the Terminal IDE on a paired device. Only a
     * Parlons NODE has one (parlons-cloud carries no chain); null means "no console here".
     * Commands run on their own lane — the control channel replies within a short leash and
     * the device polls the job key until the command finishes.
     */
    public interface NodeConsole {
        org.minima.utils.json.JSONObject run(String zCommand) throws Exception;
    }

    private volatile NodeConsole mConsole;

    public void setNodeConsole(NodeConsole zConsole) {
        mConsole = zConsole;
    }

    /**
     * NFT art hosting on a Parlons Node: the files a token's metadata links to, uploaded from a
     * paired device over this channel and served by the node's public TLS front. Null on the
     * cloud (nothing to serve from).
     */
    public interface NftHost {
        org.minima.utils.json.JSONObject put(String uid, String ext, long size, String sha256, long off,
                                             byte[] chunk, String collection, int index) throws Exception;
        org.minima.utils.json.JSONObject newCollection() throws Exception;
        org.minima.utils.json.JSONObject list() throws Exception;
        boolean delete(String path) throws Exception;
        /** e.g. https://store.eurobuddha.com/parlons-node ("" when the operator has not set it). */
        String publicBase();
    }

    private volatile NftHost mNft;

    public void setNftHost(NftHost zHost) {
        mNft = zHost;
    }

    /** Backup blobs (base64) being paged out to a device, newest last; each dies after
     *  BACKUP_PAGES_TTL_MS whether or not the device came back for the rest. */
    private final java.util.LinkedHashMap<String, String> mBackupPages = new java.util.LinkedHashMap<>();
    private final java.util.HashMap<String, Long> mBackupPagesAt = new java.util.HashMap<>();
    static final long BACKUP_PAGES_TTL_MS = 10 * 60_000L;

    /** Call with mBackupPages held. */
    private void pruneBackupPages() {
        long now = System.currentTimeMillis();
        java.util.Iterator<java.util.Map.Entry<String, Long>> it = mBackupPagesAt.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > BACKUP_PAGES_TTL_MS) {
                mBackupPages.remove(e.getKey());
                it.remove();
            }
        }
    }

    private JSONObject backupPage(String zKey, String zB64, int zOffset) {
        JSONObject out = ok();
        int from = Math.max(0, Math.min(zOffset, zB64.length()));
        int to = Math.min(zB64.length(), from + CMD_CHUNK);
        boolean more = to < zB64.length();
        out.put("key", zKey);
        out.put("blob", zB64.substring(from, to));
        out.put("offset", from);
        out.put("total", zB64.length());
        out.put("more", more);
        if (!more) {
            synchronized (mBackupPages) {
                mBackupPages.remove(zKey);   // the device has the last page
                mBackupPagesAt.remove(zKey);
            }
        }
        return out;
    }

    /** One Terminal command in flight or finished; its output is paged out in CMD_CHUNK pieces. */
    private static final class ConsoleJob {
        final String command;
        final long started = System.currentTimeMillis();
        volatile String output;       // the full JSON text once finished (null while running)
        volatile boolean done;        // finished (output set, or freed after the last page)
        volatile long ms;
        ConsoleJob(String zCommand) { command = zCommand; }
    }

    /** Per reply: well under the 256K Maxima package ceiling even after JSON escaping + encryption. */
    static final int CMD_CHUNK = 120_000;
    /** The most output one command may hold in the node's heap (chars). A MegaMMR node's
     *  {@code coins relevant:false} is hundreds of MB - materialising that on a 3g box is an OOM
     *  and then a StartLimit outage. Over the cap the job returns an error instead. */
    static final int CMD_MAX_OUTPUT = 16_000_000;
    /** How many finished jobs to keep for page fetches (a running job is never evicted). */
    private static final int CMD_KEEP = 6;
    /** How long a single RPC waits for the command before replying "pending" (the node's inbound
     *  reader is blocked for this long at most). */
    private static final long CMD_LEASH_MS = 300;   // the device polls; a long leash only held the RPC lane

    private final java.util.concurrent.ExecutorService mConsoleExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "parlons-console");
                t.setDaemon(true);
                return t;
            });
    /** Insertion-ordered; pruned by {@link #pruneConsoleJobs} - only FINISHED jobs are evicted. */
    private final java.util.LinkedHashMap<String, ConsoleJob> mConsoleJobs = new java.util.LinkedHashMap<>();

    /** Drop the oldest finished jobs beyond CMD_KEEP; a job still running is always kept. */
    private void pruneConsoleJobs() {
        synchronized (mConsoleJobs) {
            int finished = 0;
            for (ConsoleJob j : mConsoleJobs.values()) {
                if (j.done) finished++;
            }
            java.util.Iterator<java.util.Map.Entry<String, ConsoleJob>> it = mConsoleJobs.entrySet().iterator();
            while (finished > CMD_KEEP && it.hasNext()) {
                if (it.next().getValue().done) {
                    it.remove();
                    finished--;
                }
            }
        }
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

        // --- VPS control panel: event log, transport figures, host management, MLS ---
        zReg.register(M_NODE_LOG, req -> {
            requireAuth(req);
            NodeControl nc = mNodeControl;
            JSONObject in = parse(req);
            if (bool(in, "clear") && nc != null) {
                nc.clearLog();
            }
            JSONArray lines = new JSONArray();
            if (nc != null) {
                for (String l : nc.recentLog(80)) {
                    lines.add(l);
                }
            }
            JSONObject out = ok();
            out.put("lines", lines);
            return bytes(out);
        });
        zReg.register(M_NODE_FIGURES, req -> {
            requireAuth(req);
            NodeControl nc = mNodeControl;
            JSONObject out = ok();
            if (nc != null) {
                JSONArray hosts = new JSONArray();
                java.util.List<String> active = nc.hosts();
                for (String h : nc.configuredHosts()) {
                    JSONObject o = new JSONObject();
                    o.put("host", h);
                    o.put("connected", active.contains(h));
                    o.put("builtin", com.eurobuddha.maxima.core.session.SeedRelays.isBuiltIn(h));
                    hosts.add(o);
                }
                out.put("hosts", hosts);
                out.put("builtin", nc.builtInRelays());
                out.put("mailboxHeld", nc.mailboxHeld());
                out.put("outbox", nc.outboxDepth());
                out.put("directlyReachable", nc.directlyReachable());
                out.put("directAddress", safe(nc.directAddress()));
                NftHost nh = mNft;
                out.put("nftBase", nh == null ? "" : safe(nh.publicBase()));
                out.put("ownRelay", safe(nc.ownRelay()));
                out.put("ownRelayAttached", nc.ownRelayAttached());
                out.put("ownRelayVerified", nc.ownRelayVerified());
                JSONArray mesh = new JSONArray();
                for (String p : nc.meshPeers()) {
                    mesh.add(p);
                }
                out.put("meshPeers", mesh);
                out.put("relayConnections", nc.relayConnections());
                out.put("relayRelayed", nc.relayRelayed());
                out.put("relayStored", nc.relayStored());
                out.put("contacts", mNode.contacts().size());
            }
            return bytes(out);
        });
        zReg.register(M_NODE_HOSTS, req -> {
            requireAuth(req);
            NodeControl nc = mNodeControl;
            if (nc == null) {
                return bytes(err("not available"));
            }
            JSONObject in = parse(req);
            String add = str(in, "add").trim();
            String remove = str(in, "remove").trim();
            if (in.containsKey("builtin")) {
                boolean on = Boolean.parseBoolean(String.valueOf(in.get("builtin")));
                if (!nc.setBuiltInRelays(on)) {
                    return bytes(err("add a relay of your own first - the account must keep at least one seed"));
                }
            }
            if (!add.isEmpty()) {
                // Typed host:port, a comma list, or the text of a relay's QR (parlons-relay:...).
                java.util.List<String> hosts = com.eurobuddha.maxima.core.session.SeedRelays.parse(add);
                if (hosts.isEmpty()) {
                    return bytes(err("enter host:port, e.g. 45.77.246.226:9501, or scan a relay QR"));
                }
                for (String h : hosts) {
                    nc.addHost(h);        // attach happens off-pump; log reflects "connecting"
                    mNode.log("host add requested (connecting): " + h);
                }
            }
            if (!remove.isEmpty()) {
                nc.removeHost(remove);
                mNode.log("host detached: " + remove);
            }
            JSONArray hosts = new JSONArray();
            java.util.List<String> active = nc.hosts();
            for (String h : nc.configuredHosts()) {
                JSONObject o = new JSONObject();
                o.put("host", h);
                o.put("connected", active.contains(h));
                o.put("builtin", com.eurobuddha.maxima.core.session.SeedRelays.isBuiltIn(h));
                hosts.add(o);
            }
            JSONObject out = ok();
            out.put("hosts", hosts);
            out.put("builtin", nc.builtInRelays());
            return bytes(out);
        });
        zReg.register(M_NODE_MLS, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String action = str(in, "action");
            try {
                if ("pin".equals(action)) {
                    String anchor = str(in, "address").trim();
                    if (anchor.isEmpty()) {
                        anchor = mNode.bestPoolMls();   // pin the best attached pool relay
                    }
                    if (anchor.isEmpty()) {
                        return bytes(err("no pool relay attached yet — try again shortly"));
                    }
                    mNode.setStaticMls(anchor);
                } else if ("clear".equals(action)) {
                    mNode.setStaticMls("");
                } else if ("republish".equals(action)) {
                    final MaximaNode n = mNode;
                    mSendExec.execute("mls", () -> {
                        try { n.publishToMls(); } catch (Exception ignored) { }
                    });
                }
            } catch (Exception e) {
                return bytes(err(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            JSONObject out = ok();
            out.put("pinned", mNode.isStaticMls());
            out.put("mls", safe(mNode.mlsAddress()));
            out.put("permanent", safe(permanent()));
            return bytes(out);
        });

        // --- wallet: full token list + key-uses (raise-only) ---
        zReg.register(M_WALLET_TOKENS, req -> {
            requireAuth(req);
            PaySource ps = mPaySource;
            String addr = ps == null ? "" : safe(ps.myWalletAddress());
            if (addr.isEmpty()) {
                return bytes(err("wallet still opening"));
            }
            Object[] cached = mBalanceCache.get(addr);
            if (cached == null) {
                // warm it on the send lane; the device retries
                mSendExec.execute("wallet", () -> {
                    try {
                        JSONObject bal = mWallet.cmd("balance megammr:true address:" + addr);
                        mBalanceCache.put(addr, new Object[]{bal, System.currentTimeMillis()});
                    } catch (Exception ignored) { }
                });
                return bytes(err("loading — try again in a moment"));
            }
            JSONObject out = ok();
            out.put("balance", (JSONObject) cached[0]);
            return bytes(out);
        });
        zReg.register(M_WALLET_USES, req -> {
            requireAuth(req);
            final PaySource ps = mPaySource;
            if (ps == null) {
                return bytes(err("wallet still opening"));
            }
            int cur = ps.uses();                 // one cheap (lock-free) read off the two mirrors
            if (cur < 0) {
                return bytes(err("wallet still opening"));
            }
            JSONObject in = parse(req);
            Object raise = in.get("raiseTo");
            int reported = cur;
            if (raise instanceof Number) {
                final int to = ((Number) raise).intValue();
                if (to <= cur) {
                    return bytes(err("can only RAISE above the current " + cur));
                }
                // Fund-critical ceiling: never fold the counter past the key's leaf maximum, or
                // the wallet can never sign again (and tens of thousands of one-time keys burn).
                if (to > mWallet.maxUses()) {
                    return bytes(err("above the key's maximum " + mWallet.maxUses()));
                }
                // The write takes a cross-process FileLock + fsyncs BOTH mirrors — never on the
                // pump. Defer to the send lane; the raise is validated and raise-only, so reply
                // optimistically and the device re-reads the persisted value on its next refresh.
                mSendExec.execute("wallet", () -> {
                    try { ps.raiseUsesTo(to); } catch (Exception ignored) { }
                });
                reported = to;
            }
            JSONObject out = ok();
            out.put("uses", reported);
            out.put("max", mWallet.maxUses());
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
            // PAGED: a reply must fit one 256K wire message or it silently black-holes, which
            // capped a contact list at ~378 entries. offset/limit page it (most recently seen
            // first); "more"/"next" tell the client to fetch the rest. A client that sends
            // nothing gets the first page, as before.
            JSONObject in = parse(req);
            int offset = intOf(in, "offset", 0);
            int limit = Math.max(1, Math.min(PAGE_CONTACTS, intOf(in, "limit", PAGE_CONTACTS)));
            java.util.List<Contact> all = new java.util.ArrayList<>(mNode.contacts());
            all.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
            JSONArray arr = new JSONArray();
            int end = Math.min(all.size(), offset + limit);
            for (int i = Math.min(offset, all.size()); i < end; i++) {
                Contact c = all.get(i);
                JSONObject o = new JSONObject();
                o.put("key", safe(c.publicKey));
                o.put("name", safe(c.name));
                o.put("address", safe(c.primaryAddress()));
                o.put("lastSeen", c.lastSeen);
                arr.add(o);
            }
            JSONObject out = ok();
            out.put("contacts", arr);
            out.put("total", all.size());
            out.put("more", end < all.size());
            out.put("next", end);
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
            // PAGED like contacts (see M_CONTACTS): ~264 conversations filled a wire message.
            JSONObject in = parse(req);
            int offset = intOf(in, "offset", 0);
            int limit = Math.max(1, Math.min(PAGE_SUMMARIES, intOf(in, "limit", PAGE_SUMMARIES)));
            java.util.List<ChatEngine.Summary> allSums = mChat.summaries();
            int sEnd = Math.min(allSums.size(), offset + limit);
            JSONArray arr = new JSONArray();
            for (ChatEngine.Summary s : allSums.subList(Math.min(offset, allSums.size()), sEnd)) {
                JSONObject o = new JSONObject();
                o.put("peer", safe(s.conversation));
                boolean isGrp = mChat.group(s.conversation) != null;
                o.put("name", safe(nameFor(s.conversation)));
                o.put("group", isGrp);
                // In a group, the preview names who spoke ("Alice: hi") — the app's convention.
                if (isGrp && !s.lastMine && s.lastSender != null && !s.lastSender.isEmpty()) {
                    o.put("lastName", nameFor(s.lastSender));
                }
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
            for (com.eurobuddha.maxima.core.chat.Group g : (offset == 0 ? mChat.groups()
                    : java.util.Collections.<com.eurobuddha.maxima.core.chat.Group>emptyList())) {
                if (seen.contains(g.id)) {   // empty groups ride the first page only
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
            out.put("total", allSums.size());
            out.put("more", sEnd < allSums.size());
            out.put("next", sEnd);
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
            boolean grp = mChat.group(peer) != null;
            for (ChatEngine.Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", safe(e.id));
                o.put("sender", safe(e.sender));
                o.put("body", safe(e.body));
                o.put("mine", e.mine);
                o.put("time", e.time);
                o.put("state", safe(e.state));
                o.put("arrived", e.arrived);          // late-relay dual clock
                if (grp && !e.mine) {
                    o.put("sname", nameFor(e.sender));   // group sender name
                }
                if (grp && e.mine) {
                    o.put("delivered", e.deliveredBy().size());   // per-member delivery count
                }
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
            mSendExec.execute(fpeer, () -> {
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
            mSendExec.execute(peer, () -> {
                try { mChat.markRead(peer); } catch (Exception ignored) { }
            });
            return bytes(ok());
        });

        // --- clear a conversation locally on the account (does NOT unsend / leave a group) ---
        zReg.register(M_CHAT_CLEAR, req -> {
            requireAuth(req);
            String peer = str(parse(req), "peer");
            if (peer.isEmpty()) {
                return bytes(err("peer required"));
            }
            // clearConversation does a whole-file rewrite per removed message (the chat store
            // isn't write-behind) — off the pump so a big thread can't hold the node lock.
            final String fpeer = peer;
            mSendExec.execute(fpeer, () -> {
                try { mChat.clearConversation(fpeer); } catch (Exception ignored) { }
            });
            return bytes(ok());
        });

        // --- search: names, group names, message bodies (media captions + payment previews;
        //     voice-note waveform hex excluded) — the app's SearchActivity matching ---
        zReg.register(M_CHAT_SEARCH, req -> {
            requireAuth(req);
            String q = str(parse(req), "q").trim().toLowerCase(java.util.Locale.ROOT);
            JSONObject out = ok();
            JSONArray convs = new JSONArray();
            JSONArray msgs = new JSONArray();
            if (!q.isEmpty()) {
                int convCap = 40;
                for (Contact c : mNode.contacts()) {
                    String nm = c.name == null ? "" : c.name;
                    if (nm.toLowerCase(java.util.Locale.ROOT).contains(q)
                            || safe(c.publicKey).toLowerCase(java.util.Locale.ROOT).contains(q)) {
                        JSONObject o = new JSONObject();
                        o.put("peer", safe(c.publicKey));
                        o.put("name", nm.isEmpty() ? safe(c.publicKey) : nm);
                        o.put("group", false);
                        convs.add(o);
                        if (convs.size() >= convCap) {
                            break;
                        }
                    }
                }
                for (com.eurobuddha.maxima.core.chat.Group g : mChat.groups()) {
                    if (convs.size() >= convCap) {
                        break;
                    }
                    if (g.name != null && g.name.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                        JSONObject o = new JSONObject();
                        o.put("peer", g.id);
                        o.put("name", g.name);
                        o.put("group", true);
                        convs.add(o);
                    }
                }
                // SINGLE pass over all messages (mChat.conversation per-summary rescans the whole
                // store — O(convs × messages)); collect hits, sort newest-first, then cap.
                java.util.List<ChatEngine.Entry> hits = new java.util.ArrayList<>();
                for (ChatEngine.Entry e : mChat.allMessages()) {
                    if (searchable(e.body).toLowerCase(java.util.Locale.ROOT).contains(q)) {
                        hits.add(e);
                    }
                }
                hits.sort((a, b) -> Long.compare(b.time, a.time));   // newest first
                int cap = 60;
                for (ChatEngine.Entry e : hits) {
                    if (msgs.size() >= cap) {
                        break;
                    }
                    String conv = e.isGroup() ? e.groupId : e.peer;
                    JSONObject o = new JSONObject();
                    o.put("peer", safe(conv));
                    o.put("name", nameFor(conv));
                    o.put("group", e.isGroup());
                    o.put("id", safe(e.id));
                    // Snippet only — the whole reply must fit one 256K wire message.
                    o.put("body", snippet(searchable(e.body), q));
                    o.put("time", e.time);
                    o.put("mine", e.mine);
                    msgs.add(o);
                }
            }
            out.put("conversations", convs);
            out.put("messages", msgs);
            return bytes(out);
        });

        // --- group info: roster + admins (from the Group accessors) ---
        zReg.register(M_GROUP_INFO, req -> {
            requireAuth(req);
            String id = str(parse(req), "id");
            com.eurobuddha.maxima.core.chat.Group g = mChat.group(id);
            if (g == null) {
                return bytes(err("no such group"));
            }
            String me = mNode.publicKeyHex();
            JSONArray members = new JSONArray();
            for (String k : g.members()) {
                JSONObject o = new JSONObject();
                o.put("key", safe(k));
                o.put("name", nameFor(k));
                o.put("admin", g.isAdmin(k));
                o.put("me", com.eurobuddha.maxima.core.identity.Keys.same(k, me));
                members.add(o);
            }
            JSONObject out = ok();
            out.put("id", g.id);
            out.put("name", safe(g.name));
            out.put("iAmAdmin", g.isAdmin(me));
            out.put("members", members);
            return bytes(out);
        });

        // --- group update: add/remove members, rename (admin only; roster pushed) ---
        zReg.register(M_GROUP_UPDATE, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String id = str(in, "id");
            com.eurobuddha.maxima.core.chat.Group g = mChat.group(id);
            if (g == null) {
                return bytes(err("no such group"));
            }
            String me = mNode.publicKeyHex();
            if (!g.isAdmin(me)) {
                return bytes(err("only an admin can change the group"));
            }
            // Mutate a COPY off the shared live instance — sendGroup fan-out and the resend
            // loop iterate the live group's sets concurrently. updateGroup persists + swaps it.
            com.eurobuddha.maxima.core.chat.Group edit =
                    new com.eurobuddha.maxima.core.chat.Group(g.id);
            edit.name = g.name;
            edit.setMembers(g.members());
            edit.setAdmins(g.admins());
            String newName = str(in, "name").trim();
            if (!newName.isEmpty()) {
                edit.name = newName;
            }
            JSONArray mems = (JSONArray) in.get("members");
            if (mems != null) {
                java.util.Set<String> keep = new java.util.HashSet<>();
                for (Object o : mems) {
                    keep.add(com.eurobuddha.maxima.core.identity.Keys.norm(String.valueOf(o)));
                }
                keep.add(com.eurobuddha.maxima.core.identity.Keys.norm(me));   // never drop myself
                if (keep.size() > com.eurobuddha.maxima.core.chat.Group.MAX_MEMBERS) {
                    return bytes(err("a group holds at most " + com.eurobuddha.maxima.core.chat.Group.MAX_MEMBERS
                            + " members (" + keep.size() + " asked)"));
                }
                edit.setMembers(keep);
                // A removed member must lose admin too, or pushRoster re-adds them everywhere
                // (handleRoster addMember's every admin) and they stay authorized.
                for (String a : new java.util.ArrayList<>(edit.admins())) {
                    if (!keep.contains(com.eurobuddha.maxima.core.identity.Keys.norm(a))) {
                        edit.removeAdmin(a);
                    }
                }
                edit.addAdmin(me);
            }
            final com.eurobuddha.maxima.core.chat.Group fg = edit;
            mSendExec.execute("group", () -> {
                try { mChat.updateGroup(fg); } catch (Exception ignored) { }
            });
            JSONObject out = ok();
            out.put("name", edit.name);
            return bytes(out);
        });

        // --- contact info: full detail (kind, caps, lastSeen, all addresses, minima/mls) ---
        zReg.register(M_CONTACT_INFO, req -> {
            requireAuth(req);
            String key = str(parse(req), "key");
            Contact c = mNode.contact(key);
            if (c == null) {
                return bytes(err("no such contact"));
            }
            JSONObject out = ok();
            out.put("key", safe(c.publicKey));
            out.put("name", safe(c.name));
            out.put("lastSeen", c.lastSeen);
            out.put("kind", safe(c.kind));
            out.put("classic", c.isClassic());
            out.put("minima", safe(c.minimaAddress));
            String wallet = mChat.walletAddress(key);
            out.put("wallet", wallet == null ? "" : wallet);
            JSONArray addrs = new JSONArray();
            for (String a : c.addresses) {
                addrs.add(a);
            }
            out.put("addresses", addrs);
            return bytes(out);
        });

        // --- push channel: an explicit heartbeat. requireAuth records the live addresses. ---
        zReg.register(M_PUSH_REG, req -> {
            requireAuth(req);
            // {live:false}: the device is about to go dark (iOS background). Forget its live
            // window NOW so the next event wakes it through APNs instead of dialling relay
            // addresses that only mailbox the event; the addresses stay for a later `state`.
            JSONObject in = parse(req);
            if (in.containsKey("live") && !bool(in, "live")) {
                String key = new com.eurobuddha.maxima.core.codec.MiniData(req.fromPublicKey).to0xString();
                Live l = mLive.get(key);
                if (l != null) {
                    l.seen = 0;
                }
            }
            return bytes(ok());
        });
        zReg.register(M_PUSH_APNS, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String token = str(in, "token").trim();
            String env = str(in, "env").trim();
            String proxy = str(in, "proxy").trim();
            if (!token.isEmpty() && !token.matches("[0-9A-Fa-f]{32,512}")) {
                return bytes(err("token must be the APNs device token as hex"));
            }
            if (!env.isEmpty() && !env.equals("prod") && !env.equals("sandbox")) {
                return bytes(err("env must be prod or sandbox"));
            }
            if (!proxy.isEmpty() && !proxy.equalsIgnoreCase("off")
                    && !proxy.matches("https://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~\\-/%]*)?")) {
                return bytes(err("proxy must be an https URL, or off"));
            }
            String key = new com.eurobuddha.maxima.core.codec.MiniData(req.fromPublicKey).to0xString();
            if (!mPairing.setApns(key, token, env, proxy)) {
                return bytes(err("not a paired device"));
            }
            mNode.log("wake registration " + (token.isEmpty() ? "cleared" : "set") + " for a paired device ("
                    + (proxy.isEmpty() ? "no proxy" : proxy.equalsIgnoreCase("off") ? "off" : "proxy " + proxy) + ")");
            JSONObject out = ok();
            out.put("wake", token.isEmpty() || proxy.isEmpty() || proxy.equalsIgnoreCase("off") ? "off" : "proxy");
            return bytes(out);
        });
        zReg.register(M_CHAT_SINCE, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            long cursor = lngOf(in, "cursor");
            int limit = (int) lngOf(in, "limit");
            if (limit <= 0 || limit > PAGE_SINCE) {
                limit = PAGE_SINCE;
            }
            int offset = (int) Math.max(0, lngOf(in, "offset"));
            // Every conversation's entries newer than the cursor, ordered by NEWNESS - the later
            // of time and arrived (a late-relayed message has an old time but a new arrival, and
            // must not be missed by a device that has already moved its cursor past its time).
            java.util.List<ChatEngine.Entry> all = new java.util.ArrayList<>();
            java.util.Map<String, Boolean> groupOf = new java.util.HashMap<>();
            for (ChatEngine.Summary s : mChat.summaries()) {
                boolean grp = mChat.group(s.conversation) != null;
                groupOf.put(s.conversation, grp);
                for (ChatEngine.Entry e : mChat.conversation(s.conversation)) {
                    if (newness(e) > cursor) {
                        all.add(e);
                    }
                }
            }
            all.sort(java.util.Comparator.comparingLong(ParlonsControl::newness));
            int end = Math.min(all.size(), offset + limit);
            JSONArray entries = new JSONArray();
            long maxSeen = cursor;
            for (int i = offset; i < end; i++) {
                ChatEngine.Entry e = all.get(i);
                String conv = e.isGroup() ? e.groupId : e.peer;
                JSONObject o = new JSONObject();
                o.put("peer", conv);
                o.put("group", groupOf.getOrDefault(conv, e.isGroup()));
                o.put("name", nameFor(conv));
                o.put("id", safe(e.id));
                o.put("sender", safe(e.sender));
                o.put("body", safe(e.body));
                o.put("mine", e.mine);
                o.put("time", e.time);
                o.put("state", safe(e.state));
                o.put("arrived", e.arrived);
                if (e.isGroup()) {
                    o.put("sname", nameFor(e.sender));
                }
                entries.add(o);
                maxSeen = Math.max(maxSeen, newness(e));
            }
            JSONObject out = ok();
            out.put("entries", entries);
            out.put("total", all.size());
            out.put("more", end < all.size());
            out.put("next", end);
            // The cursor to keep: the newest entry delivered on this page (only on the LAST page
            // does it move past everything); the device advances it once the page is stored.
            out.put("cursor", end < all.size() ? maxSeen : Math.max(maxSeen, cursor));
            return bytes(out);
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
            String memoRaw = str(in, "memo");
            final String memo = memoRaw.length() > 300 ? memoRaw.substring(0, 300) : memoRaw;
            String pid = str(in, "pid");
            final Contact c = mNode.contact(peer);
            if (c == null) {
                return bytes(err("unknown contact " + peer));
            }
            if (mChat.group(peer) != null) {
                return bytes(err("payments are one-to-one for now"));
            }
            PaySource ps = mPaySource;
            if (ps == null || !ps.ready()) {
                String why = ps == null ? "" : ps.walletError();
                return bytes(err(why == null || why.isEmpty()
                        ? "the account wallet is still opening — try again in a moment"
                        : "the account wallet failed to open: " + why));
            }
            // Plain decimal only — MiniNumber accepts scientific notation ("1e2" pays 100),
            // which is a foot-gun in a money field.
            if (!amount.matches("[0-9]+(\\.[0-9]+)?")) {
                return bytes(err("that amount doesn't look right"));
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
            // IDEMPOTENCY (fund-critical) — record only AFTER validation, so a lost reply to a
            // REJECTED request can retry; a retried valid pid is acked, never queued twice.
            if (!pid.isEmpty() && mRecentPays.putIfAbsent("p:" + pid, System.currentTimeMillis()) != null) {
                JSONObject out = ok();
                out.put("state", "building");
                return bytes(out);
            }
            // Build+sign+publish are blocking network work — the send lane, never the pump.
            // States flow to devices as pushes: QUEUED bubble at sign time, SENT on publish,
            // FAILED (or a payfail toast) if anything breaks. (On a Parlons Node build() has
            // already broadcast, so the bubble appears once the money has moved.)
            mSendExec.execute("wallet", () -> {
                ChatEngine.Entry e = null;
                boolean published = false;
                try {
                    AccountWallet.Payment built = mWallet.build(to, amt);
                    e = mChat.beginPayment(c, amt.toString(), "Minima", memo, built.txid);
                    mWallet.publish(built);
                    published = true;
                    boolean told = mChat.completePayment(c, e);
                    mNode.log("payment " + amt + " → " + safe(c.name) + " txid " + built.txid
                            + (told ? "" : " (peer not yet notified — resend loop owns it)"));
                } catch (Exception ex) {
                    String why = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    boolean gatewaySaidNo = ex instanceof AccountWallet.Rejected;
                    if (e != null && !published) {
                        if (gatewaySaidNo) {
                            // The wallet REPORTED the failure — the txn did not post. Safe ✗.
                            mChat.failPayment(e);
                        } else {
                            // Transport failure at/after the post: the outcome is UNKNOWN — the
                            // money may have moved. NEVER show a plain ✗ that invites a re-pay;
                            // leave the bubble pending and tell the user to check the balance.
                            why = "outcome unknown (network trouble mid-broadcast) — check the "
                                    + "wallet balance before paying again";
                        }
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

        // --- identity lifecycle: seed reveal + encrypted backup (user decision: the
        //     passphrase-encrypted PARLONSBK blob may ride the encrypted RPC; RESTORE and
        //     seed IMPORT stay CLI-only — an RPC restore would let one compromised device
        //     swap the account out from under the others). ---
        zReg.register(M_SEED_REVEAL, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            if (!bool(in, "confirm")) {
                return bytes(err("confirmation required"));
            }
            BackupSource bs = mBackupSource;
            if (bs == null) {
                return bytes(err("not available"));
            }
            JSONObject out = ok();
            out.put("phrase", safe(bs.revealPhrase()));
            mNode.log("seed phrase revealed to a paired device");
            return bytes(out);
        });
        zReg.register(M_BACKUP_EXPORT, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            // PAGED like the Terminal output: the portable bundle carries the chat history and
            // easily exceeds the 256K wire message. First call: {passphrase} -> page 0 + key;
            // then {key, offset} until "more" is false (ParlonsRemote.backupExport stitches).
            String key = str(in, "key");
            if (!key.isEmpty()) {
                String b64;
                synchronized (mBackupPages) {
                    pruneBackupPages();
                    b64 = mBackupPages.get(key);
                }
                if (b64 == null) {
                    return bytes(err("that backup has expired - export again"));
                }
                int offset = intOf(in, "offset", 0);
                return bytes(backupPage(key, b64, offset));
            }
            String pw = str(in, "passphrase");
            if (pw.length() < 6) {
                return bytes(err("use a passphrase of at least 6 characters"));
            }
            BackupSource bs = mBackupSource;
            if (bs == null) {
                return bytes(err("not available"));
            }
            char[] pwc = pw.toCharArray();
            try {
                byte[] blob = bs.exportBackup(pwc);
                String b64 = java.util.Base64.getEncoder().encodeToString(blob);
                String k = new com.eurobuddha.maxima.core.codec.MiniData(
                        com.eurobuddha.maxima.core.crypto.MaximaCrypto.randomBytes(12)).to0xString();
                synchronized (mBackupPages) {
                    pruneBackupPages();
                    while (mBackupPages.size() >= 4) {   // a handful in flight at most
                        String oldest = mBackupPages.keySet().iterator().next();
                        mBackupPages.remove(oldest);
                        mBackupPagesAt.remove(oldest);
                    }
                    mBackupPages.put(k, b64);
                    mBackupPagesAt.put(k, System.currentTimeMillis());
                }
                mNode.log("encrypted backup exported to a paired device (" + blob.length + " bytes)");
                return bytes(backupPage(k, b64, 0));
            } finally {
                java.util.Arrays.fill(pwc, '\0');
            }
        });

        // --- wallet send-to-address (also powers the wallet-detach sweep). pid-idempotent
        //     like chat.pay: one tap must never pay twice. ---
        zReg.register(M_WALLET_SEND, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String to = str(in, "to").trim();
            String amount = str(in, "amount").trim();
            String pid = str(in, "pid");
            // 0x must be a FULL address (64 hex) — a truncated paste would build+sign to a
            // 2-byte unspendable output and burn a key use. Mx is checksummed by the engine.
            if (!to.matches("Mx[0-9A-Z]+") && !to.matches("0x[0-9A-Fa-f]{64}")) {
                return bytes(err("that doesn't look like a full Minima address"));
            }
            PaySource ps = mPaySource;
            if (ps == null || !ps.ready()) {
                String why = ps == null ? "" : ps.walletError();
                return bytes(err(why == null || why.isEmpty()
                        ? "the account wallet is still opening — try again in a moment"
                        : "the account wallet failed to open: " + why));
            }
            if (!amount.matches("[0-9]+(\\.[0-9]+)?")) {
                return bytes(err("that amount doesn't look right"));
            }
            final String fpid = pid;
            // Record the pid ONLY after validation passes — a lost reply to a rejected request
            // must be able to retry, not get acked as "building".
            if (!pid.isEmpty() && mRecentPays.putIfAbsent("w:" + pid, System.currentTimeMillis()) != null) {
                JSONObject out = ok();
                out.put("state", "building");
                return bytes(out);
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
            final String fto = to;
            mSendExec.execute("wallet", () -> {
                try {
                    AccountWallet.Payment built = mWallet.build(fto, amt);
                    mWallet.publish(built);
                    mNode.log("wallet send " + amt + " → " + fto + " txid " + built.txid);
                    JSONObject ev = new JSONObject();
                    ev.put("type", "walletsent");
                    ev.put("to", fto);
                    ev.put("amount", amt.toString());
                    ev.put("txid", built.txid);
                    ev.put("pid", fpid);   // the device gates its detach-watch flip on this
                    push(ev);
                } catch (Exception ex) {
                    String why = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    // Only a wallet-REPORTED rejection is a safe failure; ANYTHING else
                    // (transport error mid-publish) is outcome-unknown — the money may have
                    // moved, so never invite a re-send (same discipline as chat.pay).
                    boolean gatewaySaidNo = ex instanceof AccountWallet.Rejected;
                    if (!gatewaySaidNo) {
                        why = "outcome unknown (network trouble mid-broadcast) — check the "
                                + "wallet balance before sending again";
                    }
                    mNode.log("wallet send to " + fto + " FAILED: " + why);
                    JSONObject ev = new JSONObject();
                    ev.put("type", "walletfail");
                    ev.put("to", fto);
                    ev.put("error", why);
                    ev.put("pid", fpid);
                    push(ev);
                }
            });
            JSONObject out = ok();
            out.put("state", "building");
            return bytes(out);
        });

        // --- wallet: build+sign ONLY, return the signed blob so the FRONT-END broadcasts it via
        //     its own minimaCore (else gateway). sally holds the seed + key-use counter and signs
        //     (reserve-before-sign, single counter → no leaf reuse across devices); it does NOT
        //     relay here. Same validation as M_WALLET_SEND; the signed blob rides the pushed event.
        zReg.register(M_WALLET_BUILDSEND, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String to = str(in, "to").trim();
            String amount = str(in, "amount").trim();
            String pid = str(in, "pid");
            if (!to.matches("Mx[0-9A-Z]+") && !to.matches("0x[0-9A-Fa-f]{64}")) {
                return bytes(err("that doesn't look like a full Minima address"));
            }
            PaySource ps = mPaySource;
            if (ps == null || !ps.ready()) {
                String why = ps == null ? "" : ps.walletError();
                return bytes(err(why == null || why.isEmpty()
                        ? "the account wallet is still opening — try again in a moment"
                        : "the account wallet failed to open: " + why));
            }
            if (!amount.matches("[0-9]+(\\.[0-9]+)?")) {
                return bytes(err("that amount doesn't look right"));
            }
            if (!mWallet.canBuildWithoutPublish()) {
                return bytes(err("this node broadcasts its own transactions — use wallet.send"));
            }
            final String fpid = pid;
            if (!pid.isEmpty() && mRecentPays.putIfAbsent("wb:" + pid, System.currentTimeMillis()) != null) {
                JSONObject out = ok();
                out.put("state", "building");
                return bytes(out);
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
            final String fto = to;
            mSendExec.execute("wallet", () -> {
                try {
                    AccountWallet.Payment built = mWallet.build(fto, amt);   // reserve+sign; NO publish
                    mNode.log("wallet build " + amt + " → " + fto + " txid " + built.txid);
                    JSONObject ev = new JSONObject();
                    ev.put("type", "walletbuilt");
                    ev.put("to", fto);
                    ev.put("amount", amt.toString());
                    ev.put("txid", built.txid);
                    ev.put("importcmd", built.importCmd);   // signed txnimport — the device broadcasts it
                    ev.put("postcmd", built.postCmd);
                    ev.put("pid", fpid);
                    push(ev);
                } catch (Exception ex) {
                    String why = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    mNode.log("wallet build to " + fto + " FAILED: " + why);
                    JSONObject ev = new JSONObject();
                    ev.put("type", "walletfail");
                    ev.put("to", fto);
                    ev.put("error", why);
                    ev.put("pid", fpid);
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
            // This device's wake registration (iOS): what the Settings screen renders.
            DevicePairing.Device d = mPairing.device(
                    new com.eurobuddha.maxima.core.codec.MiniData(req.fromPublicKey).to0xString());
            JSONObject push = new JSONObject();
            push.put("apns", d != null && !d.apnsToken.isEmpty());
            push.put("proxy", d == null ? "" : safe(d.wakeProxy));
            push.put("env", d == null ? "" : safe(d.apnsEnv));
            out.put("push", push);
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
            boolean applied = s.readReceipts();
            if (rr instanceof Boolean) {
                applied = (Boolean) rr;
                final boolean v = applied;
                // The sink persists to disk — off the pump thread.
                mSendExec.execute("misc", () -> {
                    try { s.setReadReceipts(v); } catch (Exception ignored) { }
                });
            }
            JSONObject out = ok();
            out.put("readReceipts", applied);
            return bytes(out);
        });

        // --- contacts: remove (tells the peer, classic-style; network send off the pump) ---
        zReg.register(M_CONTACT_REMOVE, req -> {
            requireAuth(req);
            String key = str(parse(req), "key");
            if (key.isEmpty() || mNode.contact(key) == null) {
                return bytes(err("no such contact"));
            }
            mSendExec.execute(key, () -> {
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
            if (keys.size() + 1 > com.eurobuddha.maxima.core.chat.Group.MAX_MEMBERS) {
                return bytes(err("a group holds at most " + com.eurobuddha.maxima.core.chat.Group.MAX_MEMBERS
                        + " members including you (" + (keys.size() + 1) + " asked)"));
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
            mSendExec.execute("group", () -> {
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
            out.put("script", ps == null ? "" : safe(ps.walletScript()));  // for a device to track+relay
            out.put("hex", ps == null ? "" : safe(ps.walletHex()));
            out.put("canResync", mWallet.canResync());
            out.put("resyncError", safe(mWallet.lastResyncError()));
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
        zReg.register(M_WALLET_RESYNC, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            if (!bool(in, "confirm")) {
                return bytes(err("confirmation required"));
            }
            if (!mWallet.canResync()) {
                return bytes(err("this account's wallet is bound to its identity seed - detach instead"));
            }
            String phrase = str(in, "phrase").trim().replaceAll("\\s+", " ");
            if (phrase.split(" ").length != 24) {
                return bytes(err("a 24-word phrase is required"));
            }
            try {
                // Minima-node phrases carry no checksum word: only the word LIST is enforced
                // (cleanSeedPhrase throws on an unknown or too-short word).
                com.eurobuddha.maxima.core.identity.Bip39.cleanSeedPhrase(phrase);
            } catch (Exception bad) {
                return bytes(err(bad.getMessage()));
            }
            try {
                mWallet.resyncTo(phrase);
            } catch (Exception e) {
                return bytes(err("resync refused: " + e.getMessage()));
            }
            mNode.log("wallet resync to a NEW phrase requested by a paired device - node restarting");
            JSONObject out = ok();
            out.put("state", "resyncing");
            out.put("note", "the node restarts when the resync finishes (about a minute); same account, new wallet address");
            return bytes(out);
        });
        zReg.register(M_NODE_CMD, req -> {
            requireAuth(req);
            JSONObject in = parse(req);
            String key = str(in, "key");
            if (!key.isEmpty()) {
                // a follow-up: poll a running job, or fetch the next page of a finished one
                ConsoleJob job;
                synchronized (mConsoleJobs) { job = mConsoleJobs.get(key); }
                if (job == null) {
                    return bytes(err("that output has expired - run the command again"));
                }
                long offset = 0;
                try { offset = Long.parseLong(str(in, "offset").isEmpty() ? "0" : str(in, "offset")); }
                catch (NumberFormatException ignored) { }
                return bytes(consoleReply(key, job, (int) offset));
            }
            NodeConsole console = mConsole;
            if (console == null) {
                return bytes(err("this account runs on parlons-cloud, which has no embedded Minima node - the Terminal needs a Parlons Node"));
            }
            final String command = str(in, "cmd").trim();
            if (command.isEmpty()) {
                return bytes(err("no command"));
            }
            String head = command.split("\\s+")[0].toLowerCase();
            if ("quit".equals(head)) {
                return bytes(err("quit is refused over the paired channel - restart the node from the box (systemctl restart parlons-node)"));
            }
            final ConsoleJob job = new ConsoleJob(command);
            final String jobKey = Long.toHexString(System.nanoTime()) + Integer.toHexString(command.hashCode());
            synchronized (mConsoleJobs) { mConsoleJobs.put(jobKey, job); }
            pruneConsoleJobs();
            mNode.log("terminal: " + head + " (paired device)");
            mConsoleExec.execute(() -> {
                String out;
                try {
                    JSONObject r = console.run(command);
                    out = r == null ? "{}" : r.toString();
                    if (out.length() > CMD_MAX_OUTPUT) {
                        int mb = out.length() / 1_000_000;
                        out = null;   // let it go before building the reply
                        JSONObject big = new JSONObject();
                        big.put("command", command);
                        big.put("status", false);
                        big.put("error", "output too large for the paired channel (about " + mb
                                + " MB, cap " + (CMD_MAX_OUTPUT / 1_000_000) + " MB) - narrow the command");
                        out = big.toString();
                    }
                } catch (Throwable e) {
                    JSONObject r = new JSONObject();
                    r.put("command", command);
                    r.put("status", false);
                    r.put("error", "node error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    out = r.toString();
                }
                job.ms = System.currentTimeMillis() - job.started;
                job.output = out;   // volatile write publishes ms + output
                job.done = true;
            });
            return bytes(consoleReply(jobKey, job, 0));
        });
        zReg.register(M_NFT_PUT, req -> {
            requireAuth(req);
            NftHost host = mNft;
            if (host == null) {
                return bytes(err("this account runs on parlons-cloud, which hosts nothing - NFT hosting needs a Parlons Node"));
            }
            JSONObject in = parse(req);
            byte[] chunk;
            try {
                chunk = java.util.Base64.getDecoder().decode(str(in, "data"));
            } catch (Exception e) {
                return bytes(err("bad chunk encoding"));
            }
            int index = 0;
            try { index = Integer.parseInt(str(in, "index").isEmpty() ? "0" : str(in, "index")); }
            catch (NumberFormatException ignored) { }
            try {
                JSONObject r = host.put(str(in, "uid"), str(in, "ext"), lngOf(in, "size"), str(in, "sha256"),
                        lngOf(in, "off"), chunk, str(in, "collection"), index);
                JSONObject out = ok();
                out.putAll(r);
                if (Boolean.TRUE.equals(r.get("done"))) {
                    mNode.log("nft hosted: " + r.get("path") + " (" + r.get("size") + " bytes, paired device)");
                }
                return bytes(out);
            } catch (IllegalArgumentException bad) {
                return bytes(err(bad.getMessage()));
            } catch (Exception e) {
                return bytes(err("hosting failed: " + (e.getMessage() == null ? e.toString() : e.getMessage())));
            }
        });
        zReg.register(M_NFT_NEWCOL, req -> {
            requireAuth(req);
            NftHost host = mNft;
            if (host == null) return bytes(err("NFT hosting needs a Parlons Node"));
            try {
                JSONObject out = ok();
                out.putAll(host.newCollection());
                return bytes(out);
            } catch (Exception e) {
                return bytes(err("could not create the collection folder: " + e.getMessage()));
            }
        });
        zReg.register(M_NFT_LIST, req -> {
            requireAuth(req);
            NftHost host = mNft;
            if (host == null) return bytes(err("NFT hosting needs a Parlons Node"));
            try {
                JSONObject out = ok();
                out.putAll(host.list());
                return bytes(out);
            } catch (Exception e) {
                return bytes(err("could not list: " + e.getMessage()));
            }
        });
        zReg.register(M_NFT_DELETE, req -> {
            requireAuth(req);
            NftHost host = mNft;
            if (host == null) return bytes(err("NFT hosting needs a Parlons Node"));
            JSONObject in = parse(req);
            try {
                boolean gone = host.delete(str(in, "path"));
                if (gone) mNode.log("nft unhosted: " + str(in, "path") + " (paired device)");
                JSONObject out = ok();
                out.put("deleted", gone);
                return bytes(out);
            } catch (Exception e) {
                return bytes(err("could not delete: " + e.getMessage()));
            }
        });
        zReg.register(M_WALLET_BAL, req -> {
            requireAuth(req);
            PaySource ps = mPaySource;
            String own = ps == null ? "" : safe(ps.myWalletAddress());
            String watch = safe(mWallet.watchAddress());
            final String addr = watch.isEmpty() ? own : watch;
            if (addr.isEmpty()) {
                return bytes(err("wallet still opening — try again in a moment"));
            }
            // NEVER a gateway HTTP call on the pump thread (node.handle is synchronized — a
            // 60s timeout here deafened the whole node). Serve the cached balance and refresh
            // it in the background; the device's poll picks the fresh one up next round.
            Object[] cached = mBalanceCache.get(addr);
            long now = System.currentTimeMillis();
            if (cached == null || now - (Long) cached[1] > 15_000) {
                if (mBalanceFetching.add(addr)) {
                    mSendExec.execute("wallet", () -> {
                        try {
                            JSONObject bal = mWallet.cmd("balance megammr:true address:" + addr);
                            mBalanceCache.put(addr, new Object[]{bal, System.currentTimeMillis()});
                        } catch (Exception ignored) {
                        } finally {
                            mBalanceFetching.remove(addr);
                        }
                    });
                }
            }
            if (cached == null) {
                return bytes(err("balance loading — try again in a moment"));
            }
            JSONObject out = ok();
            out.put("address", addr);
            out.put("balance", (JSONObject) cached[0]);
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
            l.failures.clear();   // a fresh address list: every address gets a clean slate
            l.seen = System.currentTimeMillis();
            mWake.deviceSeen(key);   // awake: the wake quiet period ends
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

    /** The later of a message's own time and its arrival here (late-relay dual clock). */
    static long newness(ChatEngine.Entry e) {
        return Math.max(e.time, e.arrived);
    }

    private void push(JSONObject event, String zExceptDeviceKey) {
        event.put("eid", java.util.UUID.randomUUID().toString());
        final byte[] bytes = event.toString().getBytes(StandardCharsets.UTF_8);
        final long now = System.currentTimeMillis();
        final String kind = String.valueOf(event.get("type"));
        // Devices that cannot be reached live but registered a wake path: a content-free
        // APNs wake (messages and calls only - never a delivery tick).
        if ("message".equals(kind) || "call".equals(kind)) {
            for (DevicePairing.Device d : mPairing.authorized()) {
                if (!d.canWake()) {
                    continue;
                }
                if (zExceptDeviceKey != null && d.key.equalsIgnoreCase(zExceptDeviceKey)) {
                    continue;
                }
                Live l = mLive.get(d.key);
                boolean live = l != null && l.addrs != null && now - l.seen <= LIVE_MS;
                if (!live) {
                    mWake.wake(d.key, d.wakeProxy, d.apnsToken, d.apnsEnv, kind);
                }
            }
        }
        for (java.util.Map.Entry<String, Live> en : mLive.entrySet()) {
            if (zExceptDeviceKey != null && en.getKey().equalsIgnoreCase(zExceptDeviceKey)) {
                continue;
            }
            final Live l = en.getValue();
            if (now - l.seen > LIVE_MS || l.addrs == null) {
                continue;
            }
            // One pool task PER DEVICE: a dead device's blocking connects delay only itself.
            // Short socket leashes, and an address that failed PUSH_ADDR_FAILS times running is
            // skipped until the device's next RPC refreshes its list.
            final String deviceKey = en.getKey();
            mPushPool.execute(() -> {
                boolean anyDelivered = false;
                for (String addr : l.addrs) {
                    Integer fails = l.failures.get(addr);
                    if (fails != null && fails >= PUSH_ADDR_FAILS) {
                        continue;
                    }
                    try {
                        mNode.rpc().call(addr, DEVICE_PUSH, bytes,
                                new com.eurobuddha.maxima.core.rpc.RpcPeer.ResponseHandler() {
                                    public void onResponse(byte[] p) { }
                                    public void onError(String m) { }
                                }, 10_000, PUSH_CONNECT_MS, PUSH_READ_MS);
                        l.failures.remove(addr);
                        anyDelivered = true;
                    } catch (Exception e) {
                        l.failures.merge(addr, 1, Integer::sum);
                    }
                }
                // A "live" device whose every address is dead went to sleep without saying so
                // (iOS killed it): fall back to the wake path.
                if (!anyDelivered && ("message".equals(kind) || "call".equals(kind))) {
                    DevicePairing.Device d = mPairing.device(deviceKey);
                    if (d != null && d.canWake()) {
                        mWake.wake(d.key, d.wakeProxy, d.apnsToken, d.apnsEnv, kind);
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
        ev.put("name", nameFor(e.isGroup() ? e.groupId : e.peer));   // conversation name (notifier)
        if (e.isGroup()) {
            ev.put("sname", nameFor(e.sender));   // who spoke — the bubble's sender label
        }
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
        // Coalesced: a group message ticks once per member; the devices only need the latest
        // state per entry, STATE_COALESCE_MS after the first tick of a burst.
        mStateCoalesce.put(safe(e.id), ev);
        if (mStateFlushScheduled.compareAndSet(false, true)) {
            mStateFlusher.schedule(() -> {
                mStateFlushScheduled.set(false);
                for (String id : new java.util.ArrayList<>(mStateCoalesce.keySet())) {
                    JSONObject latest = mStateCoalesce.remove(id);
                    if (latest != null) {
                        push(latest);
                    }
                }
            }, STATE_COALESCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
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

    /** ~200-char snippet around the first case-insensitive hit — keeps the search reply well
     *  under the 256K wire ceiling regardless of how long the matched message is. */
    private static String snippet(String zText, String zQueryLower) {
        String low = zText.toLowerCase(java.util.Locale.ROOT);
        int at = low.indexOf(zQueryLower);
        if (at < 0) {
            return zText.length() > 200 ? zText.substring(0, 200) : zText;
        }
        int start = Math.max(0, at - 40);
        int end = Math.min(zText.length(), at + zQueryLower.length() + 120);
        return (start > 0 ? "…" : "") + zText.substring(start, end)
                + (end < zText.length() ? "…" : "");
    }

    /** A message body reduced to searchable text: media → its caption, payment → its preview,
     *  voice-note waveform hex excluded (matches the app's SearchActivity). */
    private static String searchable(String zBody) {
        if (zBody == null) {
            return "";
        }
        if (com.eurobuddha.maxima.core.chat.ChatPay.isPayment(zBody)) {
            return com.eurobuddha.maxima.core.chat.ChatPay.preview(zBody);
        }
        if (com.eurobuddha.maxima.core.chat.ChatMedia.isMedia(zBody)) {
            String cap = com.eurobuddha.maxima.core.chat.ChatMedia.caption(zBody);
            int bar = cap.indexOf('|');   // voice notes: "0:12|<hex>" — drop the waveform
            return bar >= 0 ? cap.substring(0, bar) : cap;
        }
        return zBody;
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

    /** Wait up to the leash for the job, then reply pending / one page of the output. */
    private static JSONObject consoleReply(String zKey, ConsoleJob zJob, int zOffset) {
        long until = System.currentTimeMillis() + CMD_LEASH_MS;
        while (zJob.output == null && System.currentTimeMillis() < until) {
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        JSONObject out = ok();
        out.put("key", zKey);
        out.put("command", zJob.command);
        String full = zJob.output;
        if (full == null) {
            if (zJob.done) {
                return err("that output has already been delivered - run the command again");
            }
            out.put("pending", true);
            out.put("elapsed", System.currentTimeMillis() - zJob.started);
            return out;
        }
        int from = Math.max(0, Math.min(zOffset, full.length()));
        int to = Math.min(full.length(), from + CMD_CHUNK);
        boolean more = to < full.length();
        out.put("pending", false);
        out.put("output", full.substring(from, to));
        out.put("offset", from);
        out.put("total", full.length());
        out.put("more", more);
        out.put("ms", zJob.ms);
        if (!more) {
            zJob.output = null;   // the device has the last page: release the text from the heap
        }
        return out;
    }

    /** Page sizes that keep a reply well under the 256K wire message. */
    private static final int PAGE_CONTACTS = 250;
    private static final int PAGE_SUMMARIES = 200;

    private static int intOf(JSONObject zIn, String zKey, int zDefault) {
        try {
            String v = str(zIn, zKey);
            return v.isEmpty() ? zDefault : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return zDefault;
        }
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
