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
        final DataInputStream in;
        final DataOutputStream out;
        volatile String routingKey;
        volatile long lastSeen = System.currentTimeMillis();

        Conn(Socket zSocket) throws Exception {
            socket = zSocket;
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
            zConn.socket.setSoTimeout(0);
            while (mRunning && !zConn.socket.isClosed()) {
                byte[] body = Frame.readOrSkip(zConn.in, MAX_KEEP);
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
                    zConn.routingKey = key;
                    mRoutes.put(key, zConn);
                    log("route registered " + key.substring(0, 22) + "... conn=" + zConn.id);
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
            // recipient has a mailbox with us, hold it instead of dropping.
            Mailbox.Result r = mMailbox.store(to, Codec.serialise(pkg));
            if (r == Mailbox.Result.STORED || r == Mailbox.Result.DUPLICATE) {
                mStored.incrementAndGet();
                // Still UNKNOWN on the wire: a classic sender must see classic
                // behaviour, and it has no idea what a mailbox is.
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

    /** Simple per-destination rate limit. */
    private boolean allow(String zKey) {
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
