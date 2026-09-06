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
import com.eurobuddha.maxima.core.msg.MLSPacketGETReq;
import com.eurobuddha.maxima.core.msg.MLSPacketGETResp;
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

    /** Anything larger than this on an inbound socket is not something we want. The largest
     *  legitimate frame is a relayed unit: a MaximaPackage at its wire ceiling plus a small
     *  TxPoW carrier. The buffer is allocated the moment the length header arrives, so this
     *  is also the per-connection memory an attacker can pin with a slow-drip frame: 1 MiB
     *  here across a few dozen sockets was a dead 96 MB heap. */
    private static final int MAX_KEEP = MaximaPackage.MAX_SIZE + 64 * 1024;

    /** A write blocked on a socket longer than this is a stalled peer (a full kernel buffer
     *  nobody reads: a phone that vanished behind NAT, a client that stopped reading).
     *  Sockets have no write timeout, so the sweep CLOSES the socket, which makes the blocked
     *  write throw and frees its thread. Long enough for a 256 KB frame over a slow mobile
     *  link; short enough that a few stalled peers cannot hold the push pool - and every
     *  sender routed to them - hostage. */
    static final long WRITE_STALL_MS = 60_000;

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
    /** Concurrent connections per source IP. 32: a Parlons Node + several phones + a desktop
     *  behind one home NAT all count as ONE source; 16 was refusing a normal household once a
     *  single leaky client filled it. Operators: --maxpersource N / -Dmaxima.relay.maxpersource. */
    private volatile int mMaxPerSource = Integer.getInteger("maxima.relay.maxpersource", 32);
    /** An UNREGISTERED connection (no routing key) older than this is reaped even if it keeps
     *  sending frames: a real client registers within seconds, a classic P2P socket that only
     *  pings never does - and such sockets used to live forever, one per client reconnect. */
    static final long UNREGISTERED_MAX_MS = 10 * 60_000L;
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

    /** Open-pool MLS. PER-INSTANCE now, not a classloader-global: the standalone
     *  maxima-server.jar defaults ON (see {@link #poolDefault()}) so every jar relay
     *  opts INTO the public staticMLS pool (open-resolve) — a phone that pins it gets
     *  a working permanent MAX# with no central registration, every published identity
     *  resolving for anyone (DNS-style). The in-app phone relay and in-process desktop
     *  relay pass {@code false} (via RelayRuntime.setPool) so a high-churn phone is
     *  never a permanent-address anchor — it still relays traffic and holds a mailbox,
     *  it just is not a pool directory. Opt the jar OUT with -Dmaxima.mls.open=false or
     *  MAXIMA_MLS_OPEN=false. Advertised in the greeting (Greeting "pool":"true") so
     *  clients auto-discover the pool relays worth anchoring to. */
    private final boolean mPool;

    /** The standalone-jar default: pool ON unless an operator opts out via
     *  -Dmaxima.mls.open=false / MAXIMA_MLS_OPEN=false. Public so RelayRuntime can seed
     *  its own default from it; the app/desktop relay overrides to false regardless. */
    public static boolean poolDefault() {
        String sys = System.getProperty("maxima.mls.open");
        if (sys != null) {
            return Boolean.parseBoolean(sys);
        }
        String env = System.getenv("MAXIMA_MLS_OPEN");
        if (env != null && !env.isEmpty()) {
            return Boolean.parseBoolean(env);
        }
        return true;   // default: the standalone jar relay is a pool host
    }

    /**
     * Our public address, if the operator told us one.
     *
     * Empty means "say nothing in the greeting" - see Greeting.commsOnly. Only
     * worth setting when the address a client dials is NOT the address it
     * should keep using, e.g. behind a load balancer.
     */
    private volatile String mPublicHost = "";
    /** The wallet gateway our node offers (full /cmd URL + bearer), "" when none - see
     *  {@link Greeting#commsOnly(String, String, int, java.util.List, int, boolean, int, String, String)}. */
    private volatile String mGatewayUrl = "";
    private volatile String mGatewayKey = "";

    /** Advertise (or, with empty values, stop advertising) our node's wallet gateway. */
    public void setGateway(String zUrl, String zKey) {
        mGatewayUrl = zUrl == null ? "" : zUrl.trim();
        mGatewayKey = zKey == null ? "" : zKey.trim();
    }
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

    // ---- Phase-B MLS mesh: forward a resolve MISS to peer pool relays ----
    /**
     * Bootstrap fleet peers (host:port) the mesh forwards resolves to, on top of any
     * gossip-verified pool peers. Like the client's {@code RelayStore.DEFAULTS}: a starting
     * set, never a single point of failure — trust is the publisher's signed proof, so a
     * dead or hostile peer is simply skipped. Set via {@code --peers} / RelayRuntime.setPeers.
     */
    private volatile java.util.List<String> mBootstrapPeers = java.util.Collections.emptyList();
    /** Target keys currently being forwarded, so a burst of identical misses fans out once. */
    private final java.util.Set<String> mForwarding =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, RateLimit> mForwardLimit = new ConcurrentHashMap<>();
    /** Peers asked per miss (stop at the FIRST verified answer). Sized to cover the whole
     *  fleet: an entry may live on a single relay (a client attached to just one), so a miss
     *  must be able to reach every peer, not a subset — otherwise a rare entry beyond the cut
     *  is unresolvable. First-answer-wins means the common case (widely-replicated entries)
     *  still stops after one dial; the full fan-out is only paid for genuinely rare/absent keys. */
    private static final int FORWARD_FANOUT = 8;
    private static final int FORWARD_CONNECT_MS = 3000;
    private static final int FORWARD_READ_MS = 2500;
    /** Global forward budget — a miss flood must not amplify into a fan-out storm. */
    private static final int FORWARDS_PER_MIN = 240;
    /** A forwarded answer is cached this briefly, so repeats are instant and staleness is
     *  bounded regardless of the origin entry's own (longer) TTL. */
    private static final long FORWARD_CACHE_TTL_MS = 10 * 60 * 1000;

    // ---- directory REPLICATION: a SET we accept is pushed to a few random pool peers ----
    /**
     * How many peer relays receive a copy of each signed SET this pool relay accepts
     * (--replicate N / -Dmaxima.relay.replicate; 0 = never send). Before this an entry lived
     * only where its publisher happened to be attached, so an anchor outage made the user
     * unresolvable until it returned - a single point of failure per user. Copies go to RANDOM
     * verified pool peers; every receiver re-verifies the signature; a replica is never
     * re-replicated (strict 1-hop, loop-free like DIR_QUERY).
     */
    private volatile int mReplicas = Integer.getInteger("maxima.relay.replicate", 3);
    /** Global budget so a SET flood cannot be amplified across the mesh. */
    private static final int REPLICATIONS_PER_MIN = 600;
    /** Replicas ACCEPTED from one source per minute (a peer relay pushing junk is bounded). */
    private static final int PER_SOURCE_REPLICAS_PER_MIN = 120;
    private final Map<String, RateLimit> mReplicateLimit = new ConcurrentHashMap<>();
    private final Map<String, RateLimit> mReplicaInLimits = new ConcurrentHashMap<>();
    private final AtomicLong mReplicasSent = new AtomicLong();
    private final AtomicLong mReplicasStored = new AtomicLong();
    /** Resolve misses that started a mesh fan-out (diagnostics; replication should make it rare). */
    private final AtomicLong mForwards = new AtomicLong();

    public long forwardsStarted() {
        return mForwards.get();
    }
    /** Forward fan-out runs in parallel here so a miss is answered inside the client's leash. */
    private final java.util.concurrent.ExecutorService mForwardExec =
            new java.util.concurrent.ThreadPoolExecutor(0, 32, 30, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "relay-forward");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    public void setReplicas(int zCount) {
        mReplicas = Math.max(0, zCount);
    }

    public long replicasSent() {
        return mReplicasSent.get();
    }

    public long replicasStored() {
        return mReplicasStored.get();
    }

    /**
     * Bootstrap the mesh with a fleet peer list ({@code host:port}). Optional: without it the
     * mesh still forwards to any gossip-verified pool peers. Only pool relays should be given
     * a list (a non-pool relay never forwards). Safe to set at startup or any time after.
     */
    public void setPeers(java.util.List<String> zHostPorts) {
        mBootstrapPeers = zHostPorts == null ? java.util.Collections.emptyList()
                : new java.util.ArrayList<>(zHostPorts);
        considerBootstrapPeers();
    }

    /**
     * The mesh peers we were given are peers like any other: dial-back verify them so they
     * ride in the peer list we hand clients (classic shares every known peer, its
     * {@code -p2pnodes} included). Re-run each maintenance tick — a verified peer expires
     * after {@link com.eurobuddha.maxima.core.session.RelayPeers#TTL_MS} unless re-claimed,
     * and a bootstrap peer claims nothing; this is its re-claim. No-op when already verified.
     */
    private void considerBootstrapPeers() {
        String self = mPublicHost.isEmpty() ? "" : mPublicHost + ":" + mPort;
        for (String p : mBootstrapPeers) {
            mPeers.consider(p, self);
        }
    }

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
    /**
     * The push pool: every write the relay initiates on its own (mailbox drains, keep-alives)
     * runs here, never on the single maintain thread. A socket write has no timeout, so a
     * stalled peer blocks whichever thread writes to it; the maintain thread must never be
     * that thread, or one dead phone stops expiry, sweeps, flushes and every other keep-alive.
     *
     * Eight core threads that time out when idle (a ThreadPoolExecutor only grows past its core
     * size once its queue is FULL, so "core 4, max 8" would have been four workers in practice,
     * and core 0 - the old setting - ran ONE worker, serialising every drain behind the slowest
     * peer), a deep queue, and a task that still cannot be queued is COUNTED and, for a
     * keep-alive, un-flagged so the next sweep tries again. The write-stall reaper in
     * {@link #sweepConnections} bounds how long any worker can be held.
     */
    private final java.util.concurrent.ThreadPoolExecutor mDrainExec;
    /** Push tasks refused because the pool and its queue were both full. */
    private final AtomicLong mPushDiscards = new AtomicLong();
    /** Connections the sweep closed because a write to them had stalled. */
    private final AtomicLong mWriteStalls = new AtomicLong();
    /** Connections we could not admit because no thread could be spawned (the unit's
     *  TasksMax / ulimit ceiling). Non-zero here means the BOX is the limit, not the code. */
    private final AtomicLong mAcceptFailures = new AtomicLong();
    /** Times {@link #maintain} found the accept thread dead and restarted it. */
    private final AtomicLong mAcceptRestarts = new AtomicLong();
    /** Shed requests sent (a client asked to move; whether it did is its choice). */
    private final AtomicLong mSheds = new AtomicLong();
    /**
     * Soft client target: above this many registered routes the relay asks a few clients per
     * maintenance tick to move elsewhere (classic {@code TGT_NUM_NONE_P2P_LINKS} = 100). 0
     * disables shedding. Kept well under {@link #mMaxConnections} so a relay spreads load
     * before it has to refuse anyone. Operators: --shed N / -Dmaxima.relay.shed.
     */
    private volatile int mShedTarget = Integer.getInteger("maxima.relay.shed", 384);
    /** Per tick, at most this many clients are asked to move - a gentle drain, never a stampede. */
    private static final int SHED_PER_TICK = 4;
    /** A client asked to move is not asked again for this long (it may have declined). */
    private static final long SHED_REPEAT_MS = 30 * 60_000L;
    private volatile Thread mAcceptThread;
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

    /** One keep-alive write, as a named task so a rejection can un-flag its connection. */
    private final class KeepaliveTask implements Runnable {
        final Conn conn;

        KeepaliveTask(Conn zConn) {
            conn = zConn;
        }

        @Override
        public void run() {
            try {
                conn.write(Frame.singlePing());
                mKeepalives.incrementAndGet();
            } catch (Exception e) {
                log("keep-alive write failed conn=" + conn.id + " -> reap");
                cleanup(conn);
            } finally {
                conn.keepalivePending = false;
            }
        }
    }

    private final class Conn {
        final long id = mConnSeq.incrementAndGet();
        final Socket socket;
        final String sourceIp;
        final DataInputStream in;
        final DataOutputStream out;
        volatile String routingKey;
        final long opened = System.currentTimeMillis();
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
        /** Routes for which this conn PROVED possession of the private key
         *  (answered the possession probe). Only a verified route is
         *  non-displaceable and eligible for a mailbox drain - this is what
         *  stops anyone announcing a victim's public key from intercepting
         *  their ciphertext or blackholing their inbound. */
        final java.util.Set<String> verifiedKeys =
                java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
        /** cleanup() runs its body once even if two threads reach it. */
        final java.util.concurrent.atomic.AtomicBoolean cleaned =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        /** When the write in progress started; 0 when none is. The sweep reads this to find
         *  a write blocked past {@link #WRITE_STALL_MS}. */
        volatile long writeStartedAt;
        /** A keep-alive is queued on the push pool and not yet written: don't queue another. */
        volatile boolean keepalivePending;
        /** When we last asked this client to move (0 = never). */
        volatile long shedAt;

        Conn(Socket zSocket) throws Exception {
            socket = zSocket;
            sourceIp = zSocket.getInetAddress() == null
                    ? "?" : zSocket.getInetAddress().getHostAddress();
            in = new DataInputStream(zSocket.getInputStream());
            out = new DataOutputStream(zSocket.getOutputStream());
        }

        synchronized void write(byte[] zBody) throws Exception {
            writeStartedAt = System.currentTimeMillis();
            try {
                Frame.write(out, zBody);
                lastWrite = System.currentTimeMillis();
            } finally {
                writeStartedAt = 0;
            }
        }

        void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    public RelayServer(MaximaIdentity zIdentity, int zPort, String zVersion) {
        this(zIdentity, zPort, zVersion, poolDefault());
    }

    /** @param zPool whether this relay is a public open-resolve staticMLS pool host.
     *  The standalone jar passes {@link #poolDefault()}; the app/desktop relay passes
     *  false (a churny phone must not be a permanent-address anchor). */
    public RelayServer(MaximaIdentity zIdentity, int zPort, String zVersion, boolean zPool) {
        mIdentity = zIdentity;
        mPort = zPort;
        mVersion = zVersion;
        mPool = zPool;
        mDrainExec = new java.util.concurrent.ThreadPoolExecutor(8, 8, 30,
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(2048),
                r -> {
                    Thread t = new Thread(r, "relay-push");
                    t.setDaemon(true);
                    return t;
                },
                (r, ex) -> {
                    mPushDiscards.incrementAndGet();
                    if (r instanceof KeepaliveTask) {
                        // Never leave a client flagged for a keep-alive that will not happen:
                        // it would be skipped by every later sweep and dropped by the classic
                        // 10-minute read-silence rule this keep-alive exists to prevent.
                        ((KeepaliveTask) r).conn.keepalivePending = false;
                    }
                });
        mDrainExec.allowCoreThreadTimeOut(true);
        if (mPool) {
            mDirectory.setOpenResolve(true);
            System.out.println("MLS OPEN-RESOLVE: this relay is a public staticMLS pool server");
        }
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
        mPeers.setSelf(mPublicHost.isEmpty() ? "" : mPublicHost + ":" + mPort);
    }

    /**
     * Our greeting: the verified peers we share (shuffled, classic style) PLUS OURSELVES when
     * we have a public address — classic's {@code P2PGreeting} appends its own address to the
     * list it hands out when it accepts in-links, so a client that reached us through a
     * peer's list learns us by name too. Carries our current client count ({@code conns})
     * next to our capacity ({@code cap}) so peers can see our spare room.
     */
    private Greeting greeting() {
        java.util.List<String> peers = mPeers.share();
        if (!mPublicHost.isEmpty()) {
            String self = mPublicHost + ":" + mPort;
            if (!peers.contains(self)) {
                peers.add(self);
            }
        }
        return Greeting.commsOnly(mVersion, mPublicHost, mPort, peers, mMaxConnections, mPool,
                mRoutes.size(), mGatewayUrl, mGatewayKey);
    }

    /**
     * Give the mailbox durable backing so held ciphertext survives the
     * {@code Restart=always} the systemd unit runs under. The directory is
     * deliberately NOT persisted: its entries carry a 24h TTL and clients
     * republish on every refresh, so it self-heals within one cycle and
     * persisting it would only risk serving a stale address after downtime.
     */
    public void setStore(com.eurobuddha.maxima.core.store.Store zStore) {
        // Held items are one binary record each (never a whole-file rewrite); write-behind
        // only still matters for the legacy keyed collection while it is migrated away.
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

    /**
     * Set how many peers this relay will host concurrently. This is BOTH the
     * admission cap AND the capacity we advertise in our greeting - a big VPS
     * sets a large number, a phone-as-host a small one, and the network weights
     * host selection by it (merit, never node type). A phone and a jar run this
     * same code; only the number differs.
     */
    /** A connection that has never registered a route and is older than UNREGISTERED_MAX_MS is
     *  not a client, whatever it keeps sending. */
    private boolean unregisteredTooOld(Conn zConn) {
        if (zConn.routingKey != null) {
            return false;
        }
        long age = System.currentTimeMillis() - zConn.opened;
        if (age <= UNREGISTERED_MAX_MS) {
            return false;
        }
        log("reaping unregistered connection from " + zConn.sourceIp + " (" + (age / 1000)
                + "s, no route registered)");
        return true;
    }

    public void setMaxPerSource(int zMax) {
        mMaxPerSource = Math.max(1, zMax);
    }

    /** Soft client target above which clients are asked to move; 0 disables. */
    public void setShedTarget(int zTarget) {
        mShedTarget = Math.max(0, zTarget);
    }

    public long shedsSent() {
        return mSheds.get();
    }

    public void setMaxConnections(int zMaxConnections) {
        if (zMaxConnections > 0) {
            mMaxConnections = zMaxConnections;
        }
    }

    public int getMaxConnections() {
        return mMaxConnections;
    }

    public void start() throws Exception {
        mServer = new ServerSocket();
        mServer.setReuseAddress(true);
        mServer.bind(new InetSocketAddress("0.0.0.0", mPort));
        mRunning = true;
        startAcceptThread();
    }

    private void startAcceptThread() {
        Thread accept = new Thread(this::acceptLoop, "relay-accept");
        accept.setDaemon(true);
        mAcceptThread = accept;
        accept.start();
    }

    /** True while the accept loop is alive. A relay whose accept thread has died is still
     *  "active" to systemd and still serving its existing clients - it just admits nobody. */
    public boolean acceptAlive() {
        Thread a = mAcceptThread;
        return a != null && a.isAlive();
    }

    public long acceptFailures() {
        return mAcceptFailures.get();
    }

    public long pushDiscards() {
        return mPushDiscards.get();
    }

    public long writeStalls() {
        return mWriteStalls.get();
    }

    /**
     * The accept loop. NOTHING thrown inside may end it: the old loop caught {@code Exception},
     * and the one thing that actually happens under load - {@code Thread.start()} throwing
     * {@code OutOfMemoryError: unable to create native thread} when the unit's TasksMax /
     * ulimit is reached - is an Error, which escaped, killed the loop, and left a relay that
     * looked healthy and admitted no one. Admission is in its own guarded step, and
     * {@link #maintain} restarts this thread if it ever does die.
     */
    private void acceptLoop() {
        while (mRunning) {
            Socket s;
            try {
                s = mServer.accept();
            } catch (Throwable e) {
                if (!mRunning || mServer == null || mServer.isClosed()) {
                    return;
                }
                log("accept error: " + e);
                // EMFILE and friends repeat immediately; don't spin a core on them.
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            admit(s);
        }
    }

    private void admit(Socket zSocket) {
        Conn c = null;
        boolean counted = false;
        try {
            zSocket.setTcpNoDelay(true);
            zSocket.setKeepAlive(true);
            c = new Conn(zSocket);

            // Admission control BEFORE we spend a thread. Relaying is
            // free and PoW is never verified, so an unbounded accept
            // loop is a slow-loris / FD-exhaustion invitation.
            if (mConns.size() >= mMaxConnections) {
                log("refused (global cap " + mMaxConnections + ") from " + c.sourceIp);
                c.close();
                return;
            }
            int fromSource = mPerSource.merge(c.sourceIp, 1, Integer::sum);
            counted = true;
            if (fromSource > mMaxPerSource) {
                mPerSource.merge(c.sourceIp, -1, Integer::sum);
                counted = false;
                log("refused (per-source cap) from " + c.sourceIp);
                c.close();
                return;
            }

            final Conn fc = c;
            Thread t = new Thread(() -> serve(fc), "relay-conn-" + c.id);
            t.setDaemon(true);
            mConns.put(c.id, c);   // before start(), so serve()'s cleanup always finds it
            t.start();
        } catch (Throwable e) {
            long n = mAcceptFailures.incrementAndGet();
            log("could not admit connection: " + e + " (admission failures " + n
                    + " - if this is 'unable to create native thread', the box's TasksMax /"
                    + " ulimit is the ceiling)");
            if (c != null) {
                mConns.remove(c.id);
                if (counted) {
                    mPerSource.computeIfPresent(c.sourceIp, (k, v) -> v <= 1 ? null : v - 1);
                }
                c.close();
            } else {
                try {
                    zSocket.close();
                } catch (Exception ignored) {
                }
            }
        }
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
        mDrainExec.shutdownNow();
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
                    if (unregisteredTooOld(zConn)) {
                        break;
                    }
                    continue;
                }
                if (body == null || body.length < 1) {
                    if (unregisteredTooOld(zConn)) {
                        break;
                    }
                    continue;
                }
                zConn.lastSeen = System.currentTimeMillis();
                if (unregisteredTooOld(zConn)) {
                    break;
                }
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
                zConn.write(Frame.body(Frame.MSG_GREETING, greeting()));
                zConn.write(Frame.body(Frame.MSG_MAXIMA_CTRL,
                        MaximaCTRLMessage.mls(mIdentity.mxIdentity())));
                return;
            }
            case Frame.MSG_MAXIMA_CTRL: {
                MaximaCTRLMessage ctrl = MaximaCTRLMessage.fromBytes(payload);
                if (ctrl.getType().getAsInt() == CTRL_MAILBOX_ACK) {
                    handleMailboxAck(zConn, ctrl);
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
                    // A routing key is PUBLIC, so registration alone proves
                    // nothing. Refuse only to displace a holder that has PROVEN
                    // possession (the real owner) - an unverified/provisional
                    // squatter can always be taken over, so the true owner
                    // reclaims its key the moment it proves possession below.
                    Conn existing = mRoutes.get(key);
                    if (existing != null && existing != zConn
                            && !existing.socket.isClosed()
                            && existing.verifiedKeys.contains(key)) {
                        log("ignoring route claim for verified holder "
                                + safe(key) + " from " + zConn.sourceIp);
                        return;
                    }
                    zConn.routingKey = key;
                    zConn.routes.add(key);
                    mRoutes.put(key, zConn);
                    mKnownRoutes.put(key, Boolean.TRUE);
                    log("route registered (provisional) " + safe(key)
                            + " conn=" + zConn.id);
                    // Do NOT drain mail yet - draining to an unproven claimant
                    // would hand a victim's held ciphertext to an attacker.
                    // Send a possession PROBE (mailbox-info seq 0); the client
                    // holding the routing PRIVATE key answers with a signed ack
                    // and only then is the route verified + its mailbox drained.
                    sendPossessionProbe(zConn, key);
                }
                return;
            }
            case Frame.MSG_MAXIMA_TXPOW: {
                handleMaxima(zConn, payload);
                return;
            }
            case Frame.MSG_DIR_PUBLISH: {
                handleDirPublish(zConn, payload);
                return;
            }
            case Frame.MSG_DIR_QUERY: {
                // A peer pool relay asks whether we hold a signed entry for a key (Phase-B
                // mesh). We answer ONLY from our own store and never re-forward (strict
                // 1-hop). handleDirQuery guards on mPool so a non-pool relay leaks nothing.
                handleDirQuery(zConn, payload);
                return;
            }
            case Frame.MSG_SINGLE_PING: {
                // A connectivity probe - either the reference's fresh-socket
                // reachability check (NIOManager.sendPingMessage) or a peer's
                // keep-alive. Answer with a SINGLE_PONG greeting exactly as a
                // classic node does; an unanswered probe makes the prober mark
                // us unreachable. lastSeen was already stamped by serve().
                zConn.write(Frame.singlePong(greeting()));
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
        // The frame carries the package plus a small carrier, so a frame under the ceiling
        // holds a package under it too: the full re-serialise (a 256 KB copy per message, on
        // every hop) is only paid for a frame that could actually be over.
        if (zPayload.length > MaximaPackage.MAX_SIZE
                && Codec.serialise(pkg).length > MaximaPackage.MAX_SIZE) {
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
        boolean verified = dest != null && !dest.socket.isClosed()
                && dest.verifiedKeys.contains(to);
        if (!verified) {
            // Route-hijack defence WITHOUT breaking classic wire-compat:
            //  - ALWAYS mailbox for a known key (drain still needs proof), so
            //    the true owner NEVER loses a message even if a squatter holds
            //    the route right now;
            //  - ALSO deliver best-effort LIVE to whoever is present on an
            //    unverified route - a legitimate stock/classic node cannot
            //    answer our possession probe, and this keeps it receiving.
            // A squatter only ever gets undecryptable E2E ciphertext for the
            // brief window before the real owner reconnects and reclaims the
            // route (verifiedKeys); it can never drain the mailbox (held mail).
            boolean handled = false;
            if (mKnownRoutes.containsKey(to)) {
                Mailbox.Result r = mMailbox.store(to, Codec.serialise(unit));
                if (r == Mailbox.Result.STORED || r == Mailbox.Result.DUPLICATE) {
                    mStored.incrementAndGet();
                    handled = true;
                }
            }
            if (dest != null && !dest.socket.isClosed() && allow(to)) {
                try {
                    dest.write(Frame.body(Frame.MSG_MAXIMA_TXPOW, unit));
                    mRelayed.incrementAndGet();
                    handled = true;
                } catch (Exception e) {
                    cleanup(dest);
                }
            }
            zConn.write(Frame.ack(handled
                    ? Frame.RESPONSE_OK : Frame.RESPONSE_UNKNOWN));
            if (!handled) {
                mDropped.incrementAndGet();
            }
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

            // Directory SET/GET reply on the ack channel, classic style. Hand the verified
            // envelope triplet along: a SET retains it as a signed proof so this entry can be
            // forwarded to and re-verified by peer relays (the Phase-B mesh).
            MiniData reply = mMls.handleClassic(mm,
                    mi.mFrom.getBytes(), mi.mData.getBytes(), mi.mSignature.getBytes(),
                    Frame.RESPONSE_OK, Frame.RESPONSE_UNKNOWN);
            if (reply != null) {
                // A SET we just accepted (signature verified above) is pushed to a few random
                // pool peers, off this thread, so the entry outlives this box.
                byte[] rb0 = reply.getBytes();
                boolean setOk = rb0.length == 1 && (rb0[0] & 0xFF) == Frame.RESPONSE_OK
                        && MlsService.APP_SET.equals(mm.mApplication.toString());
                if (mPool && setOk && mReplicas > 0) {
                    final byte[] pf = mi.mFrom.getBytes();
                    final byte[] pp = mi.mData.getBytes();
                    final byte[] ps = mi.mSignature.getBytes();
                    mDrainExec.execute(() -> replicate(pf, pp, ps));
                }
                // Phase-B mesh: a pool relay that MISSED a resolve forwards it to peer pool
                // relays, verifies the signed answer, and returns a real hit — so a client
                // that reaches ANY pool relay resolves anything published anywhere in the pool.
                byte[] rb = reply.getBytes();
                boolean miss = rb.length == 1 && (rb[0] & 0xFF) == Frame.RESPONSE_UNKNOWN;
                if (mPool && miss && MlsService.APP_GET.equals(mm.mApplication.toString())) {
                    MiniData forwarded = forwardResolve(mm);
                    if (forwarded != null) {
                        reply = forwarded;
                    }
                }
                zConn.write(Frame.body(Frame.MSG_PING, reply));
                return;
            }
            zConn.write(Frame.ack(Frame.RESPONSE_OK));

        } catch (Exception e) {
            zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
        }
    }

    /**
     * Answer a peer relay's directory query from our OWN store only (strict 1-hop — we never
     * re-forward). Only an open pool relay answers with content: a non-pool relay's directory
     * is allow-listed and must not leak via the mesh, so it always replies "absent". We also
     * only share entries that carry a verifiable signed proof and have not expired.
     */
    private void handleDirQuery(Conn zConn, byte[] zPayload) throws Exception {
        DirQuery q;
        try {
            q = DirQuery.fromBytes(zPayload);
        } catch (Exception e) {
            return;   // malformed — say nothing
        }
        byte[] nonce = q.getNonce();
        if (mPool) {
            MlsStore.Entry e = mDirectory.peek(q.getTargetKey());
            if (e != null && e.hasProof() && System.currentTimeMillis() <= e.expiresAt) {
                zConn.write(Frame.body(Frame.MSG_DIR_ANSWER,
                        new DirAnswer(nonce, e.proofFrom, e.proofPayload, e.proofSig)));
                return;
            }
        }
        zConn.write(Frame.body(Frame.MSG_DIR_ANSWER, DirAnswer.absent(nonce)));
    }

    /**
     * A peer relay pushes a signed entry it accepted. Only a pool relay stores (a non-pool
     * directory is allow-listed and must not fill with strangers' entries); the signature and
     * the signer/from binding are re-verified exactly as for a forwarded answer; a replica is
     * NEVER pushed onward from here. Ack on the classic ack channel.
     */
    private void handleDirPublish(Conn zConn, byte[] zPayload) throws Exception {
        if (!mPool) {
            zConn.write(Frame.ack(Frame.RESPONSE_UNKNOWN));
            return;
        }
        if (!allow(mReplicaInLimits, zConn.sourceIp, PER_SOURCE_REPLICAS_PER_MIN)) {
            zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
            return;
        }
        DirPublish p;
        try {
            p = DirPublish.fromBytes(zPayload);
        } catch (Exception e) {
            zConn.write(Frame.ack(Frame.RESPONSE_FAIL));
            return;
        }
        String addr = MlsService.verifiedAddress(null, p.getProofFrom(), p.getProofPayload(), p.getProofSig());
        if (addr == null) {
            zConn.write(Frame.ack(Frame.RESPONSE_FAIL));   // unverifiable: a peer can never plant an entry
            return;
        }
        String signer = new MiniData(p.getProofFrom()).to0xString();
        boolean stored = mDirectory.putReplica(signer, addr,
                p.getProofFrom(), p.getProofPayload(), p.getProofSig(), MlsStore.DEFAULT_TTL_MS);
        if (stored) {
            mReplicasStored.incrementAndGet();
        }
        zConn.write(Frame.ack(Frame.RESPONSE_OK));   // OK also when our own live copy outranks it
    }

    /** Push an accepted SET to {@link #mReplicas} random pool peers (best effort, budgeted). */
    void replicate(byte[] zProofFrom, byte[] zProofPayload, byte[] zProofSig) {
        int want = mReplicas;
        if (want <= 0 || !allow(mReplicateLimit, "*", REPLICATIONS_PER_MIN)) {
            return;
        }
        java.util.List<String> targets = forwardTargets();
        java.util.Collections.shuffle(targets);
        DirPublish entry = new DirPublish(zProofFrom, zProofPayload, zProofSig);
        int done = 0;
        for (String hp : targets) {
            if (done >= want) {
                break;
            }
            int c = hp.lastIndexOf(':');
            if (c < 0) {
                continue;
            }
            int port;
            try {
                port = Integer.parseInt(hp.substring(c + 1).trim());
            } catch (Exception e) {
                continue;
            }
            if (RelayQueryClient.publish(hp.substring(0, c), port, entry, FORWARD_CONNECT_MS, FORWARD_READ_MS)) {
                done++;
                mReplicasSent.incrementAndGet();
            }
        }
    }

    /**
     * Forward a resolve MISS to peer pool relays and, on the first VERIFIED answer, cache it
     * briefly and return a normal {@code MLSPacketGETResp} for the original client. Returns
     * null if no peer holds a verifiable entry. Only called on a pool relay, for an APP_GET
     * that missed locally — so this is always an ORIGINAL client query, never a forward of a
     * forward (the DIR_QUERY handler above never calls this): the mesh is strictly 1-hop.
     */
    private MiniData forwardResolve(MaximaMessage zGetMsg) {
        String targetKey;
        MLSPacketGETReq req;
        try {
            req = MLSPacketGETReq.fromBytes(zGetMsg.mData.getBytes());
            targetKey = req.getPublicKey();
        } catch (Exception e) {
            return null;
        }
        // Collapse a burst of identical concurrent misses into one fan-out, and honour a
        // global forward budget so a miss flood cannot amplify.
        if (!mForwarding.add(targetKey)) {
            return null;
        }
        try {
            if (!allow(mForwardLimit, "*", FORWARDS_PER_MIN)) {
                return null;
            }
            mForwards.incrementAndGet();
            // PARALLEL fan-out, first verified answer wins: serially this was up to 8 × 5.5 s,
            // far past the client's 5 s leash, so a first resolve of an entry held elsewhere
            // always failed and only a retry (after the cache filled) succeeded.
            java.util.List<String> targets = forwardTargets();
            if (targets.size() > FORWARD_FANOUT) {
                targets = new java.util.ArrayList<>(targets.subList(0, FORWARD_FANOUT));
            }
            final String fkey = targetKey;
            java.util.concurrent.CompletionService<Object[]> cs =
                    new java.util.concurrent.ExecutorCompletionService<>(mForwardExec);
            int asked = 0;
            for (final String hp : targets) {
                int c = hp.lastIndexOf(':');
                if (c < 0) {
                    continue;
                }
                final String host = hp.substring(0, c);
                final int port;
                try {
                    port = Integer.parseInt(hp.substring(c + 1).trim());
                } catch (Exception e) {
                    continue;
                }
                asked++;
                cs.submit(() -> {
                    DirAnswer ans = RelayQueryClient.query(host, port, fkey, FORWARD_CONNECT_MS, FORWARD_READ_MS);
                    if (ans == null) {
                        return null;
                    }
                    String a = MlsService.verifiedAddress(fkey,
                            ans.getProofFrom(), ans.getProofPayload(), ans.getProofSig());
                    return a == null ? null : new Object[] {a, ans, host + ":" + port};
                });
            }
            long deadline = System.currentTimeMillis() + FORWARD_CONNECT_MS + FORWARD_READ_MS + 500;
            for (int i = 0; i < asked; i++) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                java.util.concurrent.Future<Object[]> f;
                try {
                    f = cs.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (f == null) {
                    break;
                }
                Object[] hit;
                try {
                    hit = f.get();
                } catch (Exception e) {
                    continue;
                }
                if (hit == null) {
                    continue;   // unverifiable or absent — a peer can withhold, never forge
                }
                String addr = (String) hit[0];
                DirAnswer ans = (DirAnswer) hit[1];
                // Trust is the signature. Cache briefly (bounded staleness) so repeats are
                // instant, then answer the client as if it had been a local hit.
                mDirectory.put(targetKey,
                        java.util.Collections.singletonList(addr),
                        java.util.Collections.emptyList(),
                        ans.getProofFrom(), ans.getProofPayload(), ans.getProofSig(),
                        FORWARD_CACHE_TTL_MS);
                log("mesh: resolved " + safe(targetKey) + " via " + hit[2]);
                MLSPacketGETResp resp =
                        new MLSPacketGETResp(targetKey, addr, req.getRandomUID());
                return new MiniData(Codec.serialise(resp));
            }
            return null;
        } finally {
            mForwarding.remove(targetKey);
        }
    }

    /** Forwarding targets, best-first: gossip-verified pool peers, then the bootstrap fleet,
     *  deduped and minus ourselves. */
    private java.util.List<String> forwardTargets() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(mPeers.poolPeers());
        out.addAll(mBootstrapPeers);
        if (!mPublicHost.isEmpty()) {
            out.remove(mPublicHost + ":" + mPort);
        }
        return new java.util.ArrayList<>(out);
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
    /**
     * SHED (relay->client): "I am over my client target, please move to another relay". The
     * client CHOOSES its replacement itself, at random from relays it verified - the payload
     * carries no target. Classic's DoSwap names the relay to move to, which lets a relay
     * steer its clients anywhere (to an accomplice, say); an advisory shed with the choice
     * left to the client keeps load balancing without that control. The client also never
     * leaves its preferred cape, accepts at most one shed per relay per 30 min, and only moves
     * once the replacement attach has succeeded.
     */
    static final int CTRL_SHED = 42;

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
    /** Possession probe: a mailbox-info with seq 0. The client holding the
     *  routing PRIVATE key answers with a signed ack over key+0 (its normal
     *  mailbox-ack path handles any seq), proving possession without deleting
     *  anything (seq 0 clears no mail). */
    private void sendPossessionProbe(Conn zConn, String zKey) {
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream d = new java.io.DataOutputStream(b);
            new MiniData(zKey).writeDataStream(d);
            new com.eurobuddha.maxima.core.codec.MiniNumber(0).writeDataStream(d);
            d.flush();
            MaximaCTRLMessage info = new MaximaCTRLMessage(CTRL_MAILBOX_INFO);
            info.setData(new MiniData(b.toByteArray()));
            zConn.write(Frame.body(Frame.MSG_MAXIMA_CTRL, info));
        } catch (Exception ignored) {
        }
    }

    private void handleMailboxAck(Conn zConn, MaximaCTRLMessage zCtrl) {
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
            String k = key.to0xString();
            // A valid signature over key+seq PROVES possession of the routing
            // private key, whatever the seq. Mark this conn verified for the
            // key: it is now the non-displaceable owner and eligible to drain.
            boolean firstProof = zConn.verifiedKeys.add(k);
            if (firstProof) {
                log("route VERIFIED " + safe(k) + " conn=" + zConn.id);
                // Reclaim the route from an unverified squatter that displaced
                // us during our probe window - the proven owner wins.
                Conn cur = mRoutes.get(k);
                if (cur != zConn && (cur == null || !cur.verifiedKeys.contains(k))) {
                    mRoutes.put(k, zConn);
                }
            }
            if (seq == 0) {
                // Possession probe answered - deliver held mail now that the
                // claimant is proven, then the drain sends the real deletion
                // challenge (seq = maxSeq) via the branch below on its reply.
                if (mRoutes.get(k) == zConn) {
                    drainMailbox(zConn, k);
                }
                return;
            }
            int cleared = mMailbox.acknowledge(k, seq);
            if (cleared > 0) {
                log("mailbox acked+cleared " + cleared + " item(s) for " + safe(k));
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
                    byte[] ct = item.ciphertext();   // read from disk now, not held in heap
                    if (ct == null) {
                        continue;
                    }
                    MaxTxPoW unit = MaxTxPoW.fromBytes(reWrapForDelivery(ct));
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
    /** Stored form is the ALREADY-MINED MaxTxPoW - deliver it verbatim, no
     *  re-mining (which on the shared thread was an attacker-driven CPU
     *  amplifier, worst on the Pi). Legacy items stored as a bare
     *  MaximaPackage (pre-0.4.24, aging out within the 7-day TTL) fall back to
     *  the old wrap-and-mine path. */
    private byte[] reWrapForDelivery(byte[] zStored) throws Exception {
        try {
            MaxTxPoW.fromBytes(zStored);   // parses => already a full unit
            return zStored;
        } catch (Exception legacy) {
            MaximaPackage pkg = Codec.deserialise(new MaximaPackage(), zStored);
            return Codec.serialise(MaxTxPoW.create(pkg, System.currentTimeMillis()));
        }
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
        considerBootstrapPeers();
        ensureAccepting();
        sweepConnections(now);
        shedIfOverloaded(now);
    }

    /**
     * Load shedding: over the soft target, ask a few registered clients to move - the ones
     * asked longest ago first, never the same one twice within {@link #SHED_REPEAT_MS}. The
     * message names no destination (see {@link #CTRL_SHED}); the client draws its own.
     */
    void shedIfOverloaded(long zNow) {
        int target = mShedTarget;
        if (target <= 0) {
            return;
        }
        int over = mRoutes.size() - target;
        if (over <= 0) {
            return;
        }
        java.util.List<Conn> candidates = new java.util.ArrayList<>();
        for (Conn c : mConns.values()) {
            if (c.routingKey != null && zNow - c.shedAt > SHED_REPEAT_MS) {
                candidates.add(c);
            }
        }
        java.util.Collections.shuffle(candidates);
        int n = Math.min(SHED_PER_TICK, Math.min(over, candidates.size()));
        for (int i = 0; i < n; i++) {
            Conn c = candidates.get(i);
            c.shedAt = zNow;
            mDrainExec.execute(() -> {
                try {
                    MaximaCTRLMessage shed = new MaximaCTRLMessage(CTRL_SHED);
                    shed.setData(new MiniData(new byte[] {0}));
                    c.write(Frame.body(Frame.MSG_MAXIMA_CTRL, shed));
                    mSheds.incrementAndGet();
                } catch (Exception e) {
                    cleanup(c);
                }
            });
        }
        if (n > 0) {
            log("over client target (" + mRoutes.size() + " > " + target + "): asked " + n
                    + " client(s) to move");
        }
    }

    /** Belt and braces for {@link #acceptLoop}: if the accept thread is dead while we are
     *  running and the listener is open, start a new one - a silent relay is the worst
     *  failure, and the fleet monitor only sees "active". */
    private void ensureAccepting() {
        if (!mRunning || mServer == null || mServer.isClosed()) {
            return;
        }
        Thread a = mAcceptThread;
        if (a == null || !a.isAlive()) {
            long n = mAcceptRestarts.incrementAndGet();
            log("ACCEPT THREAD DEAD - restarting it (restart " + n + ")");
            startAcceptThread();
        }
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
            // A write stuck past the stall window means nobody is reading at the far end.
            // Closing the socket is the only way to unblock the writer (a socket has no write
            // timeout); the writer's own catch then runs cleanup. Applies to unregistered
            // connections too - a stalled greeting reply holds a thread just the same.
            long ws = c.writeStartedAt;
            if (ws > 0 && zNow - ws > WRITE_STALL_MS) {
                mWriteStalls.incrementAndGet();
                log("reaping stalled writer conn=" + c.id + " from " + c.sourceIp
                        + " (write blocked " + (zNow - ws) / 1000 + "s)");
                cleanup(c);
                continue;
            }
            if (c.routingKey == null) {
                continue;   // unregistered: serve() already reaps it on idle
            }
            if (zNow - c.lastSeen > zSilenceMs) {
                log("reaping silent client conn=" + c.id
                        + " silent=" + (zNow - c.lastSeen) / 1000 + "s");
                cleanup(c);
                continue;
            }
            if (zNow - c.lastWrite > zKeepaliveMs && !c.keepalivePending) {
                // Off the maintain thread (see mDrainExec): a keep-alive to a stalled peer
                // must never stall expiry, sweeps and every OTHER client's keep-alive.
                c.keepalivePending = true;
                mDrainExec.execute(new KeepaliveTask(c));
            }
            // Periodically re-deliver held mail to a still-attached client.
            // drainMailbox only fired on a FRESH route registration, so mail
            // that arrived while the client stayed attached (its socket briefly
            // half-dead, then healthy) was never pushed - it waited for a
            // reconnect that swarm relay-switching may send elsewhere. Re-draining
            // here on a slow cadence closes that gap; the client dedups by msgid,
            // and an empty mailbox makes this a cheap no-op.
            if (zNow - c.lastDrain > DRAIN_INTERVAL_MS
                    && c.routingKey != null
                    && c.verifiedKeys.contains(c.routingKey)) {
                c.lastDrain = zNow;
                final Conn fc = c;
                final String fk = c.routingKey;
                // Off the maintain thread: a blocking write to a slow client
                // (no write timeout on a socket) must not stall keepalives to
                // everyone else.
                mDrainExec.execute(() -> drainMailbox(fc, fk));
            }
        }
    }

    /** How often to re-push held mail to an attached client (slow: mail that is
     *  already delivered is deduped, so this only matters when mail is waiting). */
    private static final long DRAIN_INTERVAL_MS = 90_000;

    /** Test hook: our identity (to build our mls address). */
    MaximaIdentity identityForTest() {
        return mIdentity;
    }

    /** The relay's peer list (package-private: tests shape and read it through here). */
    com.eurobuddha.maxima.core.session.RelayPeers peers() {
        return mPeers;
    }

    /** Test hook: the oldest in-progress write start across all connections, 0 if none. A
     *  value that stays put for longer than any loopback write could take IS a blocked writer. */
    long oldestWriteStartedAt() {
        long oldest = 0;
        for (Conn c : mConns.values()) {
            long ws = c.writeStartedAt;
            if (ws > 0 && (oldest == 0 || ws < oldest)) {
                oldest = ws;
            }
        }
        return oldest;
    }

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
        // The FULL key, always: a shortened identifier cannot be searched for or matched against a
        // device list, and a log exists to be grepped. Only control characters are neutralised.
        return zKey.replaceAll("[\\p{Cntrl}]", "?");
    }

    private void log(String zMsg) {
        System.out.println("[relay] " + zMsg);
    }
}
