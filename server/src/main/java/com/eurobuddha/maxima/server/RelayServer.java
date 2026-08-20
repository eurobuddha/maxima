package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.directory.MlsService;
import com.eurobuddha.maxima.core.directory.MlsStore;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.mailbox.Mailbox;
import com.eurobuddha.maxima.core.msg.CryptoPackage;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.msg.MaxTxPoW;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.msg.MaximaInternal;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.msg.MaximaPackage;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A public Maxima relay: host, directory and mailbox in one process.
 *
 * This is what makes NAT'd devices reachable. A client dials out and holds the
 * connection open; we map the routing key it announces to that socket and push
 * anything addressed to it back down the same pipe.
 *
 * Decentralisation depends on these being cheap and plentiful - it runs on a
 * Raspberry Pi, and the whole point is that anyone can run one. A network with
 * three relays is not decentralised no matter how good the protocol is.
 *
 * Wire-compatible with classic clients: a stock Minima node can attach here and
 * be relayed, and stock clients can send through us.
 */
public final class RelayServer {

    /** Anything larger than this on an inbound socket is not something we want. */
    private static final int MAX_KEEP = 1024 * 1024;

    private final MaximaIdentity mIdentity;
    private final int mPort;
    private final String mVersion;

    /** routing public key (uppercase 0x hex) -> the connection to push down. */
    private final Map<String, Conn> mRoutes = new ConcurrentHashMap<>();
    private final Map<Long, Conn> mConns = new ConcurrentHashMap<>();

    /**
     * Every routing key that has EVER registered a route with us this run.
     *
     * We only hold mailbox for a key that has actually attached here at some
     * point - i.e. a real user of this relay who happens to be offline now.
     * Storing for a never-seen key is exactly the attack: a flood to a million
     * random keys, none of which will ever collect, allocating a box each.
     */
    private final Map<String, Boolean> mKnownRoutes = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > MAX_KNOWN_ROUTES;
                }
            });

    /**
     * Concurrent connections we will hold. Kept modest for the Pi target: the
     * relay is thread-per-connection, so 2048 threads was ~1-2 GB of stacks -
     * enough to OOM the box before the cap even bit. A relay serving real
     * clients needs far fewer, and clients re-attach if reaped.
     */
    private volatile int mMaxConnections = 512;
    /** Concurrent connections from one source IP (a CGNAT still fits many users). */
    private volatile int mMaxPerSource = 16;
    /** Idle ms before an UNREGISTERED connection (never became a client) is reaped. */
    private static final int IDLE_TIMEOUT_MS = 120_000;
    /** Cap on every rate-limit / bookkeeping map, so none can grow unbounded. */
    private static final int MAX_RATE_ENTRIES = 50_000;

    /**
     * The exact length of a valid routing key: the DER encoding of an RSA-1024
     * public key. A CTRL/TYPE_ID announcing anything else is junk (the 1-byte
     * key spam that filled the route maps), and is refused before it touches
     * them.
     */
    private static final int ROUTING_KEY_DER_LEN = 162;

    /** Per-source inbound frame budget - the master flood cap. */
    private static final int PER_SOURCE_FRAMES_PER_MIN = 2000;
    /** Per-source budget for the EXPENSIVE addressed-to-us (RSA decrypt) path. */
    private static final int PER_SOURCE_TOUS_PER_MIN = 120;
    /** Distinct routing keys one connection may register (a client needs one). */
    private static final int MAX_ROUTES_PER_CONN = 4;
    /** Global cap on remembered routing keys, LRU-evicted. */
    private static final int MAX_KNOWN_ROUTES = 100_000;

    private final Map<String, Integer> mPerSource = new ConcurrentHashMap<>();
    private final Map<String, RateLimit> mFrameLimits = new ConcurrentHashMap<>();
    private final Map<String, RateLimit> mTousLimits = new ConcurrentHashMap<>();

    /** The relay's own private key, parsed ONCE (not rebuilt from DER per decrypt). */
    private volatile java.security.PrivateKey mPrivateKey;

    private final MlsStore mDirectory = new MlsStore();

    /** Open-pool MLS: -Dmaxima.mls.open=true (or env MAXIMA_MLS_OPEN=true) -
     *  every published identity resolves for anyone, the public staticMLS
     *  pool semantic. Off = classic allow-list + permanent-list behaviour. */
    private static final boolean MLS_OPEN =
            Boolean.parseBoolean(System.getProperty("maxima.mls.open",
                    String.valueOf(Boolean.parseBoolean(
                            System.getenv("MAXIMA_MLS_OPEN")))));

    {
        if (MLS_OPEN) {
            mDirectory.setOpenResolve(true);
            System.out.println("MLS OPEN-RESOLVE: this relay is a public staticMLS pool server");
        }
    }

    /**
     * Our public address, if the operator told us one.
     *
     * Empty means "say nothing in the greeting" - see Greeting.commsOnly. Only
     * worth setting when the address a client dials is NOT the address it
     * should keep using, e.g. behind a load balancer.
     */
    private volatile String mPublicHost = "";
    private final MlsService mMls = new MlsService(mDirectory);
    private final Mailbox mMailbox = new Mailbox();

    /**
     * Relay-gossip, the classic way: peers learned from inbound greetings whose
     * host claim matches the connection's source IP, dial-back verified before
     * they are ever shared, then served in OUR greeting's "peers" list — the
     * same vocabulary classic Minima uses (Greeting extraData "host"/"port"/
     * "peers", P2PPeersChecker's verify-before-adopt).
     */
    private final com.eurobuddha.maxima.core.session.RelayPeers mPeers =
            new com.eurobuddha.maxima.core.session.RelayPeers();
    /** Claims are cheap to send but cost us a verification dial — cap per source. */
    private static final int PER_SOURCE_CLAIMS_PER_MIN = 6;
    private final Map<String, RateLimit> mClaimLimits = new ConcurrentHashMap<>();

    /**
     * The media shelf: ciphertext chunks parked here by attached users so a
     * phone's published media survives the phone sleeping. Null = blob service
     * off (a relay without --blobstore). Content-addressed, byte-capped, LRU.
     */
    private volatile com.eurobuddha.maxima.core.store.BlobStore mBlobs;
    /** Chunk puts cost us disk — cap per source. GETs ride the to-us limit. */
    private static final int PER_SOURCE_BLOB_PUTS_PER_MIN = 60;
    private final Map<String, RateLimit> mBlobPutLimits = new ConcurrentHashMap<>();

    public void setBlobStore(com.eurobuddha.maxima.core.store.BlobStore zStore) {
        mBlobs = zStore;
    }

    public com.eurobuddha.maxima.core.store.BlobStore blobStore() {
        return mBlobs;
    }

    private final AtomicLong mConnSeq = new AtomicLong();
    private final AtomicLong mRelayed = new AtomicLong();

    /** Units that arrived WITHOUT the protocol's minimum proof-of-work. */
    private volatile long mPowFails = 0;

    /** Enforce the PoW floor (reject) vs log-only. Default LOG-ONLY so the
     *  fleet can measure old-client traffic before flipping. */
    private static final boolean POW_ENFORCE =
            Boolean.parseBoolean(System.getProperty("maxima.pow.enforce",
                    String.valueOf("true".equalsIgnoreCase(
                            System.getenv("MAXIMA_POW_ENFORCE")))));
    private final AtomicLong mDropped = new AtomicLong();
    private final AtomicLong mStored = new AtomicLong();
    private final AtomicLong mKeepalives = new AtomicLong();

    private volatile boolean mRunning;
    private ServerSocket mServer;

    /** Per-identity rate limit. PoW is never verified, so this must be real. */
    private final Map<String, RateLimit> mLimits = new ConcurrentHashMap<>();
    private volatile int mMaxPerMinute = 600;

    /** Probes cost us an outbound dial, so they get a tighter, separate cap. */
    private final Map<String, RateLimit> mProbeLimits = new ConcurrentHashMap<>();
    private static final int MAX_PROBES_PER_MINUTE = 12;
    private static final int PROBE_CONNECT_MS = 4000;
    private static final int PROBE_READ_MS = 2000;

    private static final class RateLimit {
        long windowStart = System.currentTimeMillis();
        int count;
    }

    private final class Conn {
        final long id = mConnSeq.incrementAndGet();
        final Socket socket;
        final String sourceIp;
        final DataInputStream in;
        final DataOutputStream out;
        volatile String routingKey;
        volatile long lastSeen = System.currentTimeMillis();
        /** When we last WROTE anything down this socket. The reference drops a
         *  peer it has not READ from in 10 min, and a peer reads from us only
         *  when we write - so a quiet relay must write a keep-alive on this
         *  cadence or a classic client drops us. Distinct from lastSeen (their
         *  traffic to us): a client can be sending us data while never reading
         *  from us, and would still drop us without this. */
        volatile long lastWrite = System.currentTimeMillis();
        /** When we last re-drained held mail to this conn (periodic delivery). */
        volatile long lastDrain;
        /** Every route this conn registered, so cleanup removes ALL of them. */
        final java.util.Set<String> routes =
                java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
        /** cleanup() runs its body once even if two threads reach it. */
        final java.util.concurrent.atomic.AtomicBoolean cleaned =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        Conn(Socket zSocket) throws Exception {
            socket = zSocket;
            sourceIp = zSocket.getInetAddress() == null
                    ? "?" : zSocket.getInetAddress().getHostAddress();
            in = new DataInputStream(zSocket.getInputStream());
            out = new DataOutputStream(zSocket.getOutputStream());
        }

        synchronized void write(byte[] zBody) throws Exception {
            Frame.write(out, zBody);
            lastWrite = System.currentTimeMillis();
        }

        void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    public RelayServer(MaximaIdentity zIdentity, int zPort, String zVersion) {
        mIdentity = zIdentity;
        mPort = zPort;
        mVersion = zVersion;
        // Parse the private key ONCE. handleForUs used to rebuild it from DER on
        // every message addressed to us, which an attacker could force in a hot
        // loop - a needless per-packet KeyFactory parse plus RSA op.
        try {
            mPrivateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(
                    new java.security.spec.PKCS8EncodedKeySpec(
                            mIdentity.keyPair().getPrivate().getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("could not parse relay private key", e);
        }
    }

    public void setPublicHost(String zHost) {
        mPublicHost = zHost == null ? "" : zHost.trim();
    }

    /**
     * Give the mailbox durable backing so held ciphertext survives the
     * {@code Restart=always} the systemd unit runs under. The directory is
     * deliberately NOT persisted: its entries carry a 24h TTL and clients
     * republish on every refresh, so it self-heals within one cycle and
     * persisting it would only risk serving a stale address after downtime.
     */
    public void setStore(com.eurobuddha.maxima.core.store.Store zStore) {
        // Write-behind: the mailbox is the hot path and must not fsync the whole
        // file per stored item. Flushed on the maintenance tick + shutdown.
        if (zStore instanceof com.eurobuddha.maxima.core.store.FileStore) {
            ((com.eurobuddha.maxima.core.store.FileStore) zStore).setWriteBehind(true);
        }
        mMailbox.setStore(zStore);
    }

    public MlsStore directory() {
        return mDirectory;
    }

    public Mailbox mailbox() {
        return mMailbox;
    }

    public long relayedCount() {
        return mRelayed.get();
    }

    public long droppedCount() {
        return mDropped.get();
    }

    /** Units that arrived without the protocol's minimum proof-of-work. */
    public long powFailCount() {
        return mPowFails;
    }

    public long storedCount() {
        return mStored.get();
    }

    public int routeCount() {
        return mRoutes.size();
    }

    public int connectionCount() {
        return mConns.size();
    }

    public void setRateLimit(int zPerMinute) {
        mMaxPerMinute = zPerMinute;
    }

    public void start() throws Exception {
        mServer = new ServerSocket();
        mServer.setReuseAddress(true);
        mServer.bind(new InetSocketAddress("0.0.0.0", mPort));
        mRunning = true;

        Thread accept = new Thread(() -> {
            while (mRunning) {
                try {
                    Socket s = mServer.accept();
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    Conn c = new Conn(s);

                    // Admission control BEFORE we spend a thread. Relaying is
                    // free and PoW is never verified, so an unbounded accept
                    // loop is a slow-loris / FD-exhaustion invitation.
                    if (mConns.size() >= mMaxConnections) {
                        log("refused (global cap " + mMaxConnections + ") from " + c.sourceIp);
                        c.close();
                        continue;
                    }
                    int fromSource = mPerSource.merge(c.sourceIp, 1, Integer::sum);
                    if (fromSource > mMaxPerSource) {
                        mPerSource.merge(c.sourceIp, -1, Integer::sum);
                        log("refused (per-source cap) from " + c.sourceIp);
                        c.close();
                        continue;
                    }

                    mConns.put(c.id, c);
                    Thread t = new Thread(() -> serve(c), "relay-conn-" + c.id);
                    t.setDaemon(true);
                    t.start();
                } catch (Exception e) {
                    if (mRunning) {
                        log("accept error: " + e);
                    }
                }
            }
        }, "relay-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public void stop() {
        mRunning = false;
        try {
            if (mServer != null) {
                mServer.close();
            }
        } catch (Exception ignored) {
        }
        for (Conn c : mConns.values()) {
            c.close();
        }
        mConns.clear();
        mRoutes.clear();
    }

    // ---------------------------------------------------------------

    private void serve(Conn zConn) {
        try {
            // A real client holds the connection open and is legitimately quiet
            // for long stretches while it waits for pushes, so the timeout is
            // not a hard deadline - it is a wake-up. On expiry we reap ONLY a
            // connection that never became a client (no routing key): that is
            // the slow-loris / idle-socket that costs a thread for nothing.
            zConn.socket.setSoTimeout(IDLE_TIMEOUT_MS);
            while (mRunning && !zConn.socket.isClosed()) {
                byte[] body;
                try {
                    body = Frame.readOrSkip(zConn.in, MAX_KEEP);
                } catch (java.net.SocketTimeoutException te) {
                    // Reap only a connection that never became a client (no
                    // route). A REGISTERED connection is a real client that has
                    // proven itself and is legitimately quiet while it waits for
                    // pushes - it sends nothing for long stretches by design.
                    // Reaping it on idle disconnects every phone every few
                    // minutes, and where it cannot cleanly re-attach (e.g. its
                    // home relay via hairpin NAT) it goes dark. Registered
                    // connections are instead bounded by the per-source (16) and
                    // global (512) connection caps plus TCP keepalive; the
                    // unregistered slow-loris - a socket that greets and never
                    // registers - is what actually needs reaping.
                    if (zConn.routingKey == null
                            && System.currentTimeMillis() - zConn.lastSeen > IDLE_TIMEOUT_MS) {
                        log("reaping idle non-client from " + zConn.sourceIp);
                        break;
                    }
                    continue;
                }
                if (body == null || body.length < 1) {
                    continue;
                }
                zConn.lastSeen = System.currentTimeMillis();
                // Master flood cap: bound inbound frames per source IP before any
                // expensive work. The source IP of an established TCP connection
                // cannot be rotated, which is exactly why it is the right key.
                if (!allow(mFrameLimits, zConn.sourceIp, PER_SOURCE_FRAMES_PER_MIN)) {
                    continue;   // silently drop; a flooding source gets nothing
                }
                handleFrame(zConn, body);
            }
        } catch (Exception e) {
            // Normal on disconnect.
        } finally {
            cleanup(zConn);
        }
    }

    private void cleanup(Conn zConn) {
        // Idempotent: the sweep (maintain thread) and serve()'s finally can both
        // reach here for the same Conn - closing the socket in the sweep makes
        // serve()'s blocked read throw. Without this guard the per-source counter
        // double-decrements and the connection cap silently loosens.
        if (!zConn.cleaned.compareAndSet(false, true)) {
            return;
        }
        mConns.remove(zConn.id);
        mPerSource.computeIfPresent(zConn.sourceIp, (k, v) -> v <= 1 ? null : v - 1);
        // Remove EVERY route this conn held, not just the last. The old code
        // removed only zConn.routingKey, so any earlier key it registered leaked
        // permanently, each still pinning this dead Conn (and its socket) in the
        // route map - a straight path to OOM under a key-spam flood.
        for (String k : zConn.routes) {
            mRoutes.remove(k, zConn);
        }
        zConn.close();
    }

    private void handleFrame(Conn zConn, byte[] zBody) throws Exception {
        int type = Frame.typeOf(zBody);
        byte[] payload = new byte[zBody.length - 1];
        System.arraycopy(zBody, 1, payload, 0, payload.length);

        switch (type) {
            case Frame.MSG_GREETING: {
                // Relay-gossip intake, classic style: if the peer's greeting
                // claims a public host:port, and the claimed host is EXACTLY the
                // source IP of this connection (self-nomination only), queue a
                // dial-back verification. Rate-limited — each claim can cost us
                // an outbound dial.
                try {
                    Greeting theirs = Greeting.fromBytes(payload);
                    String extra = theirs.getExtraData();
                    String claimedHost = Greeting.hostOf(extra);
                    int claimedPort = Greeting.portOf(extra);
                    if (!claimedHost.isEmpty() && claimedHost.equals(zConn.sourceIp)
                            && allow(mClaimLimits, zConn.sourceIp, PER_SOURCE_CLAIMS_PER_MIN)) {
                        mPeers.claim(zConn.sourceIp, claimedHost, claimedPort,
                                mPublicHost + ":" + mPort);
                    }
                } catch (Exception ignored) {
                    // A malformed greeting still gets our reply below, as before.
                }

                // Reply with ours — now carrying the relays we have VERIFIED, so
                // every client that attaches learns the wider fleet — then offer
                // ourselves as a directory, exactly as a classic node does.
                zConn.write(Frame.body(Frame.MSG_GREETING,
                        Greeting.commsOnly(mVersion, mPublicHost, mPort, mPeers.share())));
                zConn.write(Frame.body(Frame.MSG_MAXIMA_CTRL,
                        MaximaCTRLMessage.mls(mIdentity.mxIdentity())));
                return;
            }
            case Frame.MSG_MAXIMA_CTRL: {
                MaximaCTRLMessage ctrl = MaximaCTRLMessage.fromBytes(payload);
                if (ctrl.getType().getAsInt() == CTRL_MAILBOX_ACK) {
                    handleMailboxAck(ctrl);
                    return;
                }
                if (ctrl.getType().getAsInt() == MaximaCTRLMessage.TYPE_ID) {
                    // A valid routing key is a full RSA-1024 public-key DER.
                    // Anything else is junk - the 1-byte-key spam that filled the
                    // route maps - and is refused before it touches them.
                    if (ctrl.getData().getLength() != ROUTING_KEY_DER_LEN) {
                        return;
                    }
                    String key = ctrl.getData().to0xString();
                    // Cap distinct routes per connection. One client needs one
                    // key; a stream of fresh keys on a single conn was the OOM
                    // engine. A new key beyond the cap is ignored.
                    if (!zConn.routes.contains(key) && zConn.routes.size() >= MAX_ROUTES_PER_CONN) {
                        return;
                    }
                    // Do NOT displace a live binding for the same key. A routing
                    // key is public, so without this anyone could announce
                    // someone else's key and hijack/blackhole their traffic. The
                    // first live holder keeps the route until it actually drops.
                    Conn existing = mRoutes.get(key);
                    if (existing != null && existing != zConn
                            && !existing.socket.isClosed()) {
                        log("ignoring duplicate route claim for "
                                + safe(key) + " from " + zConn.sourceIp);
                        return;
                    }
                    zConn.routingKey = key;
                    zConn.routes.add(key);
                    mRoutes.put(key, zConn);
                    mKnownRoutes.put(key, Boolean.TRUE);
                    log("route registered " + safe(key) + " conn=" + zConn.id);
                    // Deliver anything held while they were away.
                    drainMailbox(zConn, key);
                }
                return;
            }
            case Frame.MSG_MAXIMA_TXPOW: {
                handleMaxima(zConn, payload);
                return;
            }
            case Frame.MSG_SINGLE_PING: {
                // A connectivity probe - either the reference's fresh-socket
                // reachability check (NIOManager.sendPingMessage) or a peer's
                // keep-alive. Answer with a SINGLE_PONG greeting exactly as a
                // classic node does; an unanswered probe makes the prober mark
                // us unreachable. lastSeen was already stamped by serve().
                zConn.write(Frame.singlePong(Greeting.commsOnly(
                        mVersion, mPublicHost, mPort, mPeers.share())));
                return;
            }
            default:
                // MSG_PING (ack), SINGLE_PONG and everything else: ignore, as a
                // classic node does (an unknown type only logs there).
        }
    }

    private void handleMaxima(Conn zConn, byte[] zPayload) throws Exception {
        MaxTxPoW unit;
        try {
            unit = MaxTxPoW.fromBytes(zPayload);
        } catch (Exception e) {
            zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
            return;
        }

        if (!unit.checkValidTxPoW()) {
            zConn.write(Frame.ack(Frame.RESPONSE_WRONGHASH));
            return;
        }

        // PROOF OF WORK - Maxima's spam control (confirmed by the reference
        // author: un-worked units are rejected on the relay path; classic
        // senders always mine ~10k hashes). Enforcement is flag-gated so the
        // fleet can run LOG-ONLY while older clients update, then flip:
        //   -Dmaxima.pow.enforce=true  (or env MAXIMA_POW_ENFORCE=true)
        if (!unit.mTxPoW.meetsMinWork()) {
            mPowFails++;
            log("NO-POW maxima unit from " + zConn.sourceIp
                    + " (total " + mPowFails + ")"
                    + (POW_ENFORCE ? " - REJECTED" : " - log-only, passed"));
            if (POW_ENFORCE) {
                zConn.write(Frame.ack(Frame.RESPONSE_WRONGHASH));
                return;
            }
        }

        MaximaPackage pkg = unit.mMaxima;
        int size = Codec.serialise(pkg).length;
        if (size > MaximaPackage.MAX_SIZE) {
            zConn.write(Frame.ack(Frame.RESPONSE_TOOBIG));
            return;
        }

        String to = pkg.mTo.to0xString();

        // Addressed to us -> we are the endpoint (directory, mailbox, ...).
        if (to.equalsIgnoreCase(new MiniData(mIdentity.publicKey()).to0xString())) {
            // This path does an RSA-1024 private-key decrypt. It is legitimate
            // low-volume traffic (directory SET/GET, probes), so a tight
            // per-source budget stops an attacker forcing unlimited decrypts to
            // pin the CPU, without hurting real clients.
            if (!allow(mTousLimits, zConn.sourceIp, PER_SOURCE_TOUS_PER_MIN)) {
                zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
                return;
            }
            handleForUs(zConn, pkg);
            return;
        }

        // Otherwise relay it, byte-identical, exactly one hop.
        Conn dest = mRoutes.get(to);
        if (dest == null || dest.socket.isClosed()) {
            // The classic outcome is a silent loss. We can do better: if the
            // recipient is a KNOWN user of this relay (has attached before),
            // hold it for them. We do NOT store for a key that has never
            // registered here - that is the mailbox-flood attack: messages to
            // a million random keys, none of which will ever collect.
            if (mKnownRoutes.containsKey(to)) {
                Mailbox.Result r = mMailbox.store(to, Codec.serialise(pkg));
                if (r == Mailbox.Result.STORED || r == Mailbox.Result.DUPLICATE) {
                    mStored.incrementAndGet();
                    // OK on the wire: the mailbox WILL deliver on reconnect, so
                    // telling the sender "unknown" was a lie that made MaxSolo
                    // show failures for messages that arrive. A sender that
                    // wants proof-of-receipt uses receipts (Parlons does).
                    zConn.write(Frame.ack(Frame.RESPONSE_OK));
                    return;
                }
            }
            mDropped.incrementAndGet();
            zConn.write(Frame.ack(Frame.RESPONSE_UNKNOWN));
            return;
        }

        if (!allow(to)) {
            mDropped.incrementAndGet();
            zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
            return;
        }

        try {
            dest.write(Frame.body(Frame.MSG_MAXIMA_TXPOW, unit));
            mRelayed.incrementAndGet();
            zConn.write(Frame.ack(Frame.RESPONSE_OK));
        } catch (Exception e) {
            cleanup(dest);
            mDropped.incrementAndGet();
            zConn.write(Frame.ack(Frame.RESPONSE_UNKNOWN));
        }
    }

    /**
     * The media shelf. PUT is for attached users only (the connection must hold
     * a registered route — the same anti-flood posture as the mailbox: strangers
     * cannot fill our disk) and rate-limited per source. GET/HAS are open like
     * MaxLite's /blob was: a chunk id is only ever learned from a sealed
     * manifest, the bytes are ciphertext, and the reply is verified by hash on
     * the client anyway.
     */
    private void handleBlob(Conn zConn, String zApp, MaximaMessage zMsg) throws Exception {
        com.eurobuddha.maxima.core.store.BlobStore store = mBlobs;
        if (store == null) {
            zConn.write(Frame.ack(Frame.RESPONSE_UNKNOWN));   // service not offered here
            return;
        }
        byte[] data = zMsg.mData.getBytes();
        switch (zApp) {
            case com.eurobuddha.maxima.core.media.MediaWire.APP_PUT: {
                // Unlike the mailbox (a permanent box per key = a flood target),
                // the shelf is content-addressed with a hard byte cap and
                // least-recently-fetched eviction: a flood just churns the LRU.
                // So the protection is the per-source rate limit + the cap, the
                // same model the probe uses - not a per-connection route gate
                // (a send opens a fresh connection that never registers a route).
                if (!allow(mBlobPutLimits, zConn.sourceIp, PER_SOURCE_BLOB_PUTS_PER_MIN)) {
                    zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
                    return;
                }
                try {
                    String id = store.put(data);
                    // Confirm with the id: the sender checks it matches its own.
                    zConn.write(Frame.body(Frame.MSG_PING, new MiniData(
                            id.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                } catch (Exception e) {
                    zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
                }
                return;
            }
            case com.eurobuddha.maxima.core.media.MediaWire.APP_GET: {
                byte[] chunk;
                try {
                    chunk = store.get(new String(data,
                            java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) {
                    chunk = null;
                }
                if (chunk == null) {
                    zConn.write(Frame.ack(Frame.RESPONSE_UNKNOWN));
                } else {
                    zConn.write(Frame.body(Frame.MSG_PING, new MiniData(chunk)));
                }
                return;
            }
            default: {   // APP_HAS
                boolean has;
                try {
                    has = store.has(new String(data,
                            java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) {
                    has = false;
                }
                zConn.write(Frame.ack(has ? Frame.RESPONSE_OK : Frame.RESPONSE_UNKNOWN));
            }
        }
    }

    /** A message addressed to the relay itself - directory or mailbox traffic. */
    private void handleForUs(Conn zConn, MaximaPackage zPkg) throws Exception {
        try {
            CryptoPackage cp = CryptoPackage.fromBytes(zPkg.mData.getBytes());
            // Cached PrivateKey, not a per-call DER re-parse. Constant-behaviour
            // decrypt: on bad RSA padding it returns a random key and continues
            // through AES, so the padding-valid and padding-invalid paths do the
            // same work - closing the Bleichenbacher timing side-channel.
            byte[] plain = MaximaCrypto.decrypt(cp, mPrivateKey);
            MaximaInternal mi = MaximaInternal.fromBytes(plain);

            if (!MaximaCrypto.verify(mi.mFrom.getBytes(), mi.mData.getBytes(),
                    mi.mSignature.getBytes())) {
                zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
                return;
            }
            MaximaMessage mm = MaximaMessage.fromBytes(mi.mData.getBytes());
            if (!mm.mFrom.equals(mi.mFrom)) {
                zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
                return;
            }

            // Tier 2 reachability probe: dial the caller back at the requested
            // port and report whether an endpoint answered.
            if (com.eurobuddha.maxima.core.net.Probe.APPLICATION.equals(
                    mm.mApplication.toString())) {
                handleProbe(zConn, mm);
                return;
            }

            // The media shelf: park / fetch / check ciphertext chunks.
            String mediaApp = mm.mApplication.toString();
            if (com.eurobuddha.maxima.core.media.MediaWire.isMediaApp(mediaApp)) {
                handleBlob(zConn, mediaApp, mm);
                return;
            }

            // Directory SET/GET reply on the ack channel, classic style.
            MiniData reply = mMls.handleClassic(mm, Frame.RESPONSE_OK, Frame.RESPONSE_UNKNOWN);
            if (reply != null) {
                zConn.write(Frame.body(Frame.MSG_PING, reply));
                return;
            }
            zConn.write(Frame.ack(Frame.RESPONSE_OK));

        } catch (Exception e) {
            zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
        }
    }

    /**
     * Prove (or disprove) the caller's own reachability.
     *
     * THE ONE SECURITY RULE: we dial the SOURCE IP of this connection, never an
     * IP the caller names. A client can only ever prove ITS OWN port, so the
     * service cannot be turned into a port scanner. On top of that: the target
     * port must be high, the source IP must be a real public address (never
     * loopback/RFC1918 - the relay must not be tricked into probing its own
     * LAN), and probes are rate-limited per caller, since each one costs the
     * relay an outbound dial.
     *
     * Reply on the ack channel: OK = reachable, FAIL = not.
     */
    private void handleProbe(Conn zConn, MaximaMessage zMsg) throws Exception {
        int port = com.eurobuddha.maxima.core.net.Probe.portOf(zMsg.mData.getBytes());
        String target = zConn.sourceIp;

        // Rate limit keyed on the SOURCE IP, not the caller's identity key.
        // Identity keypairs are free to mint and PoW is never verified, so a
        // per-identity cap is defeated by rotating mFrom on every request; the
        // source IP of an established TCP connection cannot be rotated, and it
        // is also exactly what we are about to spend an outbound dial against.
        boolean bad = port < com.eurobuddha.maxima.core.net.Probe.MIN_PORT
                || port > 65535
                || !com.eurobuddha.maxima.core.portmap.PortMapper.isPublic(target)
                || !allowProbe(target);
        if (bad) {
            zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
            return;
        }

        boolean reachable = com.eurobuddha.maxima.core.net.Probe.dial(
                target, port, PROBE_CONNECT_MS, PROBE_READ_MS, mVersion);
        log("probe " + target + ":" + port + " -> " + (reachable ? "reachable" : "no"));
        zConn.write(Frame.ack(reachable ? Frame.RESPONSE_OK : Frame.RESPONSE_FAIL));
    }

    /** Probe rate limit: cheap for the caller, an outbound dial for us. */
    private boolean allowProbe(String zCallerKey) {
        if (mProbeLimits.size() > MAX_RATE_ENTRIES) {
            long now = System.currentTimeMillis();
            mProbeLimits.entrySet().removeIf(e -> now - e.getValue().windowStart > 60_000);
        }
        RateLimit rl = mProbeLimits.computeIfAbsent(zCallerKey, k -> new RateLimit());
        synchronized (rl) {
            long now = System.currentTimeMillis();
            if (now - rl.windowStart > 60_000) {
                rl.windowStart = now;
                rl.count = 0;
            }
            rl.count++;
            return rl.count <= MAX_PROBES_PER_MINUTE;
        }
    }

    /**
     * Push everything held for a key down its freshly-registered connection.
     *
     * Cursor-then-acknowledge: we fetch, write each item as a normal TXPOW
     * frame, and only acknowledge on a clean write, so a socket that dies
     * mid-drain leaves the mail in place for next time rather than losing it.
     */
    /** Mailbox handshake CTRL types - OURS, never sent by classic nodes.
     *  INFO (relay->client) carries [key][maxSeq] after a drain; ACK
     *  (client->relay) carries [key][seq][signature] where the signature - made
     *  with the ROUTING PRIVATE KEY - is the proof-of-possession that makes
     *  destructive delete safe (see the drain comment). */
    static final int CTRL_MAILBOX_INFO = 40;
    static final int CTRL_MAILBOX_ACK = 41;

    /** The exact bytes both sides sign/verify for a mailbox ack. */
    static byte[] mailboxAckCanonical(byte[] zKeyDer, long zSeq) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(b);
        d.write("maxack".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        d.write(zKeyDer);
        d.writeLong(zSeq);
        d.flush();
        return b.toByteArray();
    }

    /** A signed ack: verify possession of the routing key, then delete. This is
     *  the "proper full fix" the old drain comment deferred - the jar client
     *  cooperates, classic clients never send it and keep TTL semantics. */
    private void handleMailboxAck(MaximaCTRLMessage zCtrl) {
        try {
            java.io.DataInputStream d = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(zCtrl.getData().getBytes()));
            MiniData key = MiniData.readFromStream(d);
            long seq = com.eurobuddha.maxima.core.codec.MiniNumber
                    .readFromStream(d).getAsLong();
            MiniData sig = MiniData.readFromStream(d);
            if (!com.eurobuddha.maxima.core.crypto.MaximaCrypto.verify(
                    key.getBytes(), mailboxAckCanonical(key.getBytes(), seq),
                    sig.getBytes())) {
                log("mailbox ack BAD SIGNATURE for " + safe(key.to0xString()));
                return;
            }
            int cleared = mMailbox.acknowledge(key.to0xString(), seq);
            if (cleared > 0) {
                log("mailbox acked+cleared " + cleared + " item(s) for "
                        + safe(key.to0xString()));
            }
        } catch (Exception e) {
            log("mailbox ack error: " + e);
        }
    }

    private void drainMailbox(Conn zConn, String zKey) {
        try {
            java.util.List<Mailbox.Item> held = mMailbox.fetch(zKey, 0, 100);
            long maxSeq = 0;
            int sent = 0;
            for (Mailbox.Item item : held) {
                try {
                    MaxTxPoW unit = MaxTxPoW.fromBytes(
                            reWrapForDelivery(item.ciphertext));
                    zConn.write(Frame.body(Frame.MSG_MAXIMA_TXPOW, unit));
                    maxSeq = Math.max(maxSeq, item.sequence);
                    sent++;
                } catch (Exception e) {
                    break;
                }
            }
            // Deletion needs proof-of-possession: route registration is
            // unauthenticated, so acking on registration alone would let anyone
            // who announces a victim's PUBLIC key destroy their held mail. We
            // challenge instead - a client holding the routing PRIVATE key
            // answers with a signed ack (CTRL_MAILBOX_ACK) and the box clears;
            // a classic client says nothing and keeps TTL + dedup semantics.
            if (sent > 0) {
                try {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    java.io.DataOutputStream d = new java.io.DataOutputStream(b);
                    new MiniData(zKey).writeDataStream(d);
                    new com.eurobuddha.maxima.core.codec.MiniNumber(maxSeq)
                            .writeDataStream(d);
                    d.flush();
                    MaximaCTRLMessage info = new MaximaCTRLMessage(CTRL_MAILBOX_INFO);
                    info.setData(new MiniData(b.toByteArray()));
                    zConn.write(Frame.body(Frame.MSG_MAXIMA_CTRL, info));
                } catch (Exception ignored) {
                }
                log("delivered " + sent + " held item(s) to "
                        + safe(zKey) + " (signed-ack challenge sent)");
            }
        } catch (Exception e) {
            log("mailbox drain error: " + e);
        }
    }

    /**
     * A stored item is the serialised MaximaPackage. Delivery needs a carrier
     * TxPoW around it, byte-identically to a live relay - the same synthetic
     * carrier the sender used, reconstructed so the recipient's checkValidTxPoW
     * passes exactly as if we had forwarded it live.
     */
    private byte[] reWrapForDelivery(byte[] zStoredPackage) throws Exception {
        MaximaPackage pkg = Codec.deserialise(new MaximaPackage(), zStoredPackage);
        return Codec.serialise(MaxTxPoW.create(pkg, System.currentTimeMillis()));
    }

    /** Per-destination relay rate limit. */
    private boolean allow(String zKey) {
        return allow(mLimits, zKey, mMaxPerMinute);
    }

    /**
     * Windowed rate limit over an arbitrary keyed map. Used per-destination
     * (relay), per-source (all inbound frames), and per-source (the expensive
     * addressed-to-us path). Bounds the map so a flood of distinct keys cannot
     * grow it without limit.
     */
    private boolean allow(Map<String, RateLimit> zMap, String zKey, int zPerMinute) {
        if (zMap.size() > MAX_RATE_ENTRIES) {
            long now = System.currentTimeMillis();
            zMap.entrySet().removeIf(e -> now - e.getValue().windowStart > 60_000);
        }
        RateLimit rl = zMap.computeIfAbsent(zKey, k -> new RateLimit());
        synchronized (rl) {
            long now = System.currentTimeMillis();
            if (now - rl.windowStart > 60_000) {
                rl.windowStart = now;
                rl.count = 0;
            }
            rl.count++;
            return rl.count <= zPerMinute;
        }
    }

    /** Periodic maintenance: expire directory entries and sweep the rate maps. */
    public void maintain() {
        try {
            mDirectory.flushExpired();
        } catch (Exception ignored) {
        }
        long now = System.currentTimeMillis();
        for (Map<String, RateLimit> m : java.util.Arrays.asList(mLimits, mFrameLimits,
                mTousLimits, mProbeLimits, mClaimLimits, mBlobPutLimits)) {
            m.entrySet().removeIf(e -> now - e.getValue().windowStart > 120_000);
        }
        mPeers.expire();
        sweepConnections(now);
    }

    /**
     * Keep-alive + black-hole reap for registered clients. This is the relay
     * half of the fix: a registered client is never idle-reaped (reaping every
     * quiet phone would be wrong), so without this a client that (a) reads
     * nothing from us drops US after the reference's 10-min read-silence, and
     * (b) a NAT-dropped socket becomes a black hole we keep pushing into.
     *
     * For every registered connection:
     *  - if it has answered NOTHING for {@link Frame#SILENCE_DROP_MS} despite our
     *    keep-alives, it is dead - reap it so its route stops black-holing pushes;
     *  - else if we have not written to it for {@link Frame#KEEPALIVE_INTERVAL_MS},
     *    send a SINGLE_PING so the client keeps reading from us (and the NAT
     *    mapping stays warm). A write that throws means the socket is already
     *    gone - reap immediately.
     *
     * Runs off the 30s maintain tick, finer than the 120s keep-alive cadence.
     */
    private void sweepConnections(long zNow) {
        sweepConnections(zNow, Frame.KEEPALIVE_INTERVAL_MS, Frame.SILENCE_DROP_MS);
    }

    /** Thresholds are parameters so a test can drive the behaviour without
     *  waiting minutes; production always uses the {@link Frame} constants. */
    void sweepConnections(long zNow, long zKeepaliveMs, long zSilenceMs) {
        for (Conn c : mConns.values()) {
            if (c.routingKey == null) {
                continue;   // unregistered: serve() already reaps it on idle
            }
            if (zNow - c.lastSeen > zSilenceMs) {
                log("reaping silent client conn=" + c.id
                        + " silent=" + (zNow - c.lastSeen) / 1000 + "s");
                cleanup(c);
                continue;
            }
            if (zNow - c.lastWrite > zKeepaliveMs) {
                try {
                    c.write(Frame.singlePing());
                    mKeepalives.incrementAndGet();
                } catch (Exception e) {
                    log("keep-alive write failed conn=" + c.id + " -> reap");
                    cleanup(c);
                    continue;
                }
            }
            // Periodically re-deliver held mail to a still-attached client.
            // drainMailbox only fired on a FRESH route registration, so mail
            // that arrived while the client stayed attached (its socket briefly
            // half-dead, then healthy) was never pushed - it waited for a
            // reconnect that swarm relay-switching may send elsewhere. Re-draining
            // here on a slow cadence closes that gap; the client dedups by msgid,
            // and an empty mailbox makes this a cheap no-op.
            if (zNow - c.lastDrain > DRAIN_INTERVAL_MS) {
                c.lastDrain = zNow;
                drainMailbox(c, c.routingKey);
            }
        }
    }

    /** How often to re-push held mail to an attached client (slow: mail that is
     *  already delivered is deduped, so this only matters when mail is waiting). */
    private static final long DRAIN_INTERVAL_MS = 90_000;

    /** How many keep-alive SINGLE_PINGs we have sent (diagnostics / stats). */
    public long keepalivesSent() {
        return mKeepalives.get();
    }

    /** Flush any write-behind persistence. Called on the maintenance tick + shutdown. */
    public void flush() {
        mMailbox.flush();
    }

    /** Strip control chars from an attacker-influenced value before logging it. */
    private static String safe(String zKey) {
        if (zKey == null) {
            return "";
        }
        String k = zKey.length() > 22 ? zKey.substring(0, 22) + "..." : zKey;
        return k.replaceAll("[\\p{Cntrl}]", "?");
    }

    private void log(String zMsg) {
        System.out.println("[relay] " + zMsg);
    }
}
