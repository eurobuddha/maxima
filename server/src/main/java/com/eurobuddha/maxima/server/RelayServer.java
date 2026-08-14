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
    private final Map<String, Boolean> mKnownRoutes = new ConcurrentHashMap<>();

    /** Concurrent connections we will hold. Beyond this, new ones are refused. */
    private volatile int mMaxConnections = 2048;
    /** Concurrent connections from one source IP. */
    private volatile int mMaxPerSource = 64;
    /** Idle seconds before a connection that never became a client is reaped. */
    private static final int IDLE_TIMEOUT_MS = 120_000;
    /** Cap on the per-destination rate-limit map, so it cannot grow unbounded. */
    private static final int MAX_RATE_ENTRIES = 50_000;

    private final Map<String, Integer> mPerSource = new ConcurrentHashMap<>();

    private final MlsStore mDirectory = new MlsStore();

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

    private final AtomicLong mConnSeq = new AtomicLong();
    private final AtomicLong mRelayed = new AtomicLong();
    private final AtomicLong mDropped = new AtomicLong();
    private final AtomicLong mStored = new AtomicLong();

    private volatile boolean mRunning;
    private ServerSocket mServer;

    /** Per-identity rate limit. PoW is never verified, so this must be real. */
    private final Map<String, RateLimit> mLimits = new ConcurrentHashMap<>();
    private volatile int mMaxPerMinute = 600;

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

        Conn(Socket zSocket) throws Exception {
            socket = zSocket;
            sourceIp = zSocket.getInetAddress() == null
                    ? "?" : zSocket.getInetAddress().getHostAddress();
            in = new DataInputStream(zSocket.getInputStream());
            out = new DataOutputStream(zSocket.getOutputStream());
        }

        synchronized void write(byte[] zBody) throws Exception {
            Frame.write(out, zBody);
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
                handleFrame(zConn, body);
            }
        } catch (Exception e) {
            // Normal on disconnect.
        } finally {
            cleanup(zConn);
        }
    }

    private void cleanup(Conn zConn) {
        mConns.remove(zConn.id);
        mPerSource.computeIfPresent(zConn.sourceIp, (k, v) -> v <= 1 ? null : v - 1);
        if (zConn.routingKey != null) {
            mRoutes.remove(zConn.routingKey, zConn);
        }
        zConn.close();
    }

    private void handleFrame(Conn zConn, byte[] zBody) throws Exception {
        int type = Frame.typeOf(zBody);
        byte[] payload = new byte[zBody.length - 1];
        System.arraycopy(zBody, 1, payload, 0, payload.length);

        switch (type) {
            case Frame.MSG_GREETING: {
                // Reply with ours, then offer ourselves as a directory, exactly
                // as a classic node does to an incoming peer.
                zConn.write(Frame.body(Frame.MSG_GREETING,
                        Greeting.commsOnly(mVersion, mPublicHost, mPort)));
                zConn.write(Frame.body(Frame.MSG_MAXIMA_CTRL,
                        MaximaCTRLMessage.mls(mIdentity.mxIdentity())));
                return;
            }
            case Frame.MSG_MAXIMA_CTRL: {
                MaximaCTRLMessage ctrl = MaximaCTRLMessage.fromBytes(payload);
                if (ctrl.getType().getAsInt() == MaximaCTRLMessage.TYPE_ID) {
                    String key = ctrl.getData().to0xString();
                    // Do NOT displace a live binding for the same key. A routing
                    // key is public (it is in every user's contact address), so
                    // without this any client could announce someone else's key
                    // and hijack or blackhole their inbound traffic. The first
                    // live holder keeps the route until it actually drops.
                    Conn existing = mRoutes.get(key);
                    if (existing != null && existing != zConn
                            && !existing.socket.isClosed()) {
                        log("ignoring duplicate route claim for "
                                + key.substring(0, 22) + "... from " + zConn.sourceIp);
                        return;
                    }
                    zConn.routingKey = key;
                    mRoutes.put(key, zConn);
                    mKnownRoutes.put(key, Boolean.TRUE);
                    log("route registered " + key.substring(0, 22) + "... conn=" + zConn.id);
                    // Deliver anything held while they were away - the whole
                    // point of the mailbox, previously never wired up.
                    drainMailbox(zConn, key);
                }
                return;
            }
            case Frame.MSG_MAXIMA_TXPOW: {
                handleMaxima(zConn, payload);
                return;
            }
            default:
                // PING and everything else: ignore, as a classic node does.
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

        MaximaPackage pkg = unit.mMaxima;
        int size = Codec.serialise(pkg).length;
        if (size > MaximaPackage.MAX_SIZE) {
            zConn.write(Frame.ack(Frame.RESPONSE_TOOBIG));
            return;
        }

        String to = pkg.mTo.to0xString();

        // Addressed to us -> we are the endpoint (directory, mailbox, ...).
        if (to.equalsIgnoreCase(new MiniData(mIdentity.publicKey()).to0xString())) {
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
                    // Still UNKNOWN on the wire: a classic sender must see
                    // classic behaviour, and knows nothing of a mailbox.
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

    /** A message addressed to the relay itself - directory or mailbox traffic. */
    private void handleForUs(Conn zConn, MaximaPackage zPkg) throws Exception {
        try {
            CryptoPackage cp = CryptoPackage.fromBytes(zPkg.mData.getBytes());
            byte[] plain = MaximaCrypto.decrypt(cp,
                    mIdentity.keyPair().getPrivate().getEncoded());
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
     * Push everything held for a key down its freshly-registered connection.
     *
     * Cursor-then-acknowledge: we fetch, write each item as a normal TXPOW
     * frame, and only acknowledge on a clean write, so a socket that dies
     * mid-drain leaves the mail in place for next time rather than losing it.
     */
    private void drainMailbox(Conn zConn, String zKey) {
        try {
            java.util.List<Mailbox.Item> held = mMailbox.fetch(zKey, 0, 100);
            long acked = 0;
            for (Mailbox.Item item : held) {
                try {
                    MaxTxPoW unit = MaxTxPoW.fromBytes(
                            reWrapForDelivery(item.ciphertext));
                    zConn.write(Frame.body(Frame.MSG_MAXIMA_TXPOW, unit));
                    acked = item.sequence;
                } catch (Exception e) {
                    break;
                }
            }
            if (acked > 0) {
                mMailbox.acknowledge(zKey, acked);
                log("delivered " + held.size() + " held item(s) to "
                        + zKey.substring(0, 22) + "...");
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

    /** Simple per-destination rate limit. */
    private boolean allow(String zKey) {
        // Bound the map: a flood to endless distinct destinations must not grow
        // a RateLimit per key forever. Over the cap, sweep entries whose window
        // has expired; if still full, this destination is simply allowed (the
        // per-connection and global caps are the real backstop).
        if (mLimits.size() > MAX_RATE_ENTRIES) {
            long now = System.currentTimeMillis();
            mLimits.entrySet().removeIf(e -> now - e.getValue().windowStart > 60_000);
        }
        RateLimit rl = mLimits.computeIfAbsent(zKey, k -> new RateLimit());
        synchronized (rl) {
            long now = System.currentTimeMillis();
            if (now - rl.windowStart > 60_000) {
                rl.windowStart = now;
                rl.count = 0;
            }
            rl.count++;
            return rl.count <= mMaxPerMinute;
        }
    }

    private void log(String zMsg) {
        System.out.println("[relay] " + zMsg);
    }
}
