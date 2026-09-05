package com.eurobuddha.maxima.core.net;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.MxAddress;
import com.eurobuddha.maxima.core.msg.CryptoPackage;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.msg.MaxTxPoW;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.msg.MaximaInternal;
import com.eurobuddha.maxima.core.msg.MaximaMessage;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyPair;

/**
 * A long-lived OUTGOING connection to a public Maxima host.
 *
 * This is the whole NAT-traversal story. A device behind CGNAT can never accept
 * an inbound socket, so instead it dials out and holds the connection open;
 * the host then relays anything addressed to this device back down that same
 * socket.
 *
 * Attachment sequence:
 * <ol>
 *   <li>send our Greeting (required - the reference only raises
 *       MAXIMA_CONNECTED from its greeting handler)</li>
 *   <li>read their Greeting</li>
 *   <li>send CTRL/TYPE_ID carrying our PER-HOST public key, which the host
 *       stores against this socket as the routing key</li>
 * </ol>
 *
 * Our published contact address is then {@code Mx(perHostKey)@host:port} - the
 * Mx half is a key only we hold the private part of, and the host half is where
 * to deliver. That is why a relay can route for us without being able to read
 * anything.
 *
 * A fresh keypair per host is deliberate: it stops two hosts correlating us.
 */
public final class HostConnection implements Closeable {

    /** Anything bigger than this on the wire is chain data we do not want. */
    private static final int MAX_KEEP_BYTES = 1024 * 1024;

    private final String mHost;
    private final int mPort;
    private final KeyPair mPerHostKey;
    private final String mVersion;

    private Socket mSocket;
    private DataOutputStream mOut;
    private DataInputStream mIn;

    private Greeting mTheirGreeting;
    private String mTheirMlsAddress;
    /** Host capacity this relay advertised in its greeting (peers it will host),
     *  or 0 if unspecified (classic host). A MERIT input for host selection. */
    private volatile int mTheirCapacity;
    /** True if this relay advertised OPEN staticMLS pool membership (greeting
     *  "pool":"true"). We prefer pinning a pool relay as our MLS anchor so our
     *  permanent MAX# resolves for strangers. Absent (classic/old relay) = false. */
    private volatile boolean mTheirPool;
    private boolean mAttached;

    /** When we last read ANY frame from this host. Mirrors the reference's
     *  per-peer read-clock (NIOClient.mLastMessageRead): a host that has gone
     *  silent past {@link Frame#SILENCE_DROP_MS} is a dead black-hole even
     *  though the socket still looks open, and must be dropped so the pool
     *  re-attaches a live relay. Stamped on every completed read in
     *  {@link #receive}. */
    private volatile long mLastInbound = System.currentTimeMillis();

    /** When we last WROTE anything (ack or keep-alive) down this socket. The
     *  host drops us if IT reads nothing from us for 10 min, and it reads only
     *  when we write - so on a quiet link we must send a keep-alive on this
     *  cadence. Updated by {@link #writeFrame}. */
    private volatile long mLastWrite = System.currentTimeMillis();

    /**
     * A VERIFIED public endpoint of our own to claim in the greeting, or null
     * for the long-standing default (claim the dialled host:port, which is what
     * live classic nodes were measured to expect). Only a node that has PROVEN
     * its inbound port open sets this — it is the announce half of relay-gossip
     * discovery: the relay we greet sees the claim, checks the host matches our
     * source IP, dials us back, and only then shares us onward.
     */
    private volatile String mAdvertisedEndpoint;

    public void setAdvertisedEndpoint(String zHostPort) {
        mAdvertisedEndpoint = (zHostPort == null || zHostPort.isEmpty()) ? null : zHostPort;
    }

    public HostConnection(String zHost, int zPort, KeyPair zPerHostKey, String zVersion) {
        mHost = zHost;
        mPort = zPort;
        mPerHostKey = zPerHostKey;
        mVersion = zVersion;
    }

    public boolean isAttached() {
        return mAttached;
    }

    public Greeting getTheirGreeting() {
        return mTheirGreeting;
    }

    /** The MLS server this host offered us, if any (bare key + observed address). */
    public String getTheirMlsAddress() {
        return mTheirMlsAddress;
    }

    /** Host capacity this relay advertised (peers it will host), or 0 if the
     *  host said nothing (classic). Used to weight host selection by merit. */
    public int getTheirCapacity() {
        return mTheirCapacity;
    }

    /** True if this relay advertised open staticMLS pool membership. */
    public boolean getTheirPool() {
        return mTheirPool;
    }

    public byte[] routingKey() {
        return mPerHostKey.getPublic().getEncoded();
    }

    /** The address to publish to contacts so they can reach us through this host. */
    public String contactAddress() {
        return MxAddress.make(new MiniData(routingKey())) + "@" + mHost + ":" + mPort;
    }

    /**
     * Connect, greet, and announce our routing key.
     *
     * @param zTimeoutMs overall budget for reaching the attached state
     */
    public void attach(int zTimeoutMs) throws Exception {
        mSocket = new Socket();
        mSocket.connect(new InetSocketAddress(mHost, mPort), zTimeoutMs);
        mSocket.setSoTimeout(zTimeoutMs);
        mSocket.setTcpNoDelay(true);
        mSocket.setKeepAlive(true);

        mOut = new DataOutputStream(mSocket.getOutputStream());
        mIn = new DataInputStream(mSocket.getInputStream());

        // 1. Greet first.
        Frame.write(mOut, Frame.body(Frame.MSG_GREETING, myGreeting()));

        // 2. Read until we see theirs, discarding chain data.
        long deadline = System.currentTimeMillis() + zTimeoutMs;
        while (mTheirGreeting == null && System.currentTimeMillis() < deadline) {
            byte[] rx = Frame.readOrSkip(mIn, MAX_KEEP_BYTES);
            if (rx == null || rx.length < 1) {
                continue;
            }
            handleControlFrame(rx);
        }
        if (mTheirGreeting == null) {
            throw new IllegalStateException("No greeting from " + mHost + ":" + mPort);
        }

        // 3. Announce our per-host routing key.
        MaximaCTRLMessage id = MaximaCTRLMessage.id(new MiniData(routingKey()));
        Frame.write(mOut, Frame.body(Frame.MSG_MAXIMA_CTRL, id));

        mAttached = true;
        mLastInbound = System.currentTimeMillis();
    }

    /**
     * Send a keep-alive down this host connection. A quiet NAT'd link is dropped
     * by the far side after 10 min of read-silence (and by stateful NATs far
     * sooner), so a caller drives this every {@link Frame#KEEPALIVE_INTERVAL_MS}.
     * The host answers a SINGLE_PONG, which our {@link #receive} loop reads and
     * stamps - giving us positive liveness. A write that throws means the socket
     * is already dead; the caller detaches and re-attaches.
     */
    public void keepalive() throws Exception {
        writeFrame(Frame.singlePing());
    }

    /** All post-attach writes go through here: keep-alive runs on the maintain
     *  thread while acks run on the pump thread, so writes to the one socket
     *  must be serialised or two frames interleave into garbage. */
    /** Relay CTRL types for the mailbox/possession handshake - keep in lockstep
     *  with RelayServer.CTRL_MAILBOX_* and the jar client's SocketTransport. */
    private static final int CTRL_MAILBOX_INFO = 40;
    private static final int CTRL_MAILBOX_ACK = 41;

    /** Runs before a mailbox ack is signed (the node flushes its stores here). */
    private volatile Runnable mBeforeAck;

    public void setBeforeAck(Runnable zHook) {
        mBeforeAck = zHook;
    }

    private void answerMailboxChallenge(MaximaCTRLMessage zCtrl) {
        // Everything delivered on this connection so far must be durable BEFORE we sign the
        // ack that lets the relay delete its copy. (seq 0 is the possession probe; the flush
        // is then a no-op on a clean store.)
        Runnable before = mBeforeAck;
        if (before != null) {
            try {
                before.run();
            } catch (Exception ignored) {
                // a failed flush must not stop the ack: the relay keeps mail until the TTL anyway
            }
        }
        try {
            java.io.DataInputStream d = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(zCtrl.getData().getBytes()));
            MiniData key = MiniData.readFromStream(d);
            long seq = com.eurobuddha.maxima.core.codec.MiniNumber
                    .readFromStream(d).getAsLong();
            // Only answer for the routing key we actually hold on this host.
            if (!key.to0xString().equalsIgnoreCase(
                    new MiniData(routingKey()).to0xString())) {
                return;
            }
            // canonical: "maxack" + key DER + 8-byte big-endian seq (relay mirrors)
            java.io.ByteArrayOutputStream cb = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream cd = new java.io.DataOutputStream(cb);
            cd.write("maxack".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            cd.write(key.getBytes());
            cd.writeLong(seq);
            cd.flush();
            byte[] sig = com.eurobuddha.maxima.core.crypto.MaximaCrypto.sign(
                    mPerHostKey.getPrivate(), cb.toByteArray());
            java.io.ByteArrayOutputStream ab = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream ad = new java.io.DataOutputStream(ab);
            key.writeDataStream(ad);
            new com.eurobuddha.maxima.core.codec.MiniNumber(seq).writeDataStream(ad);
            new MiniData(sig).writeDataStream(ad);
            ad.flush();
            MaximaCTRLMessage ack = new MaximaCTRLMessage(CTRL_MAILBOX_ACK);
            ack.setData(new MiniData(ab.toByteArray()));
            writeFrame(Frame.body(Frame.MSG_MAXIMA_CTRL, ack));
        } catch (Exception ignored) {
        }
    }

    private synchronized void writeFrame(byte[] zBody) throws Exception {
        Frame.write(mOut, zBody);
        mLastWrite = System.currentTimeMillis();
    }

    /** True if we have not written to this host for {@code zIntervalMs} and
     *  should send a keep-alive to keep it reading from us. */
    public boolean needsKeepalive(long zIntervalMs) {
        return mAttached && System.currentTimeMillis() - mLastWrite > zIntervalMs;
    }

    /**
     * True if this host has sent us nothing for longer than {@code zMaxSilenceMs}
     * despite keep-alives - a black-hole socket that must be dropped. Only
     * meaningful while attached.
     */
    public boolean isStale(long zMaxSilenceMs) {
        return mAttached
                && System.currentTimeMillis() - mLastInbound > zMaxSilenceMs;
    }

    /** Millis since we last read any frame from this host. */
    public long silentFor() {
        return System.currentTimeMillis() - mLastInbound;
    }

    /** Decode greeting / CTRL frames. Returns true if it was one of those. */
    private boolean handleControlFrame(byte[] zBody) throws Exception {
        int type = Frame.typeOf(zBody);
        byte[] payload = new byte[zBody.length - 1];
        System.arraycopy(zBody, 1, payload, 0, payload.length);

        if (type == Frame.MSG_GREETING) {
            mTheirGreeting = Greeting.fromBytes(payload);
            mTheirCapacity = Greeting.capOf(mTheirGreeting.getExtraData());
            mTheirPool = Greeting.poolOf(mTheirGreeting.getExtraData());
            return true;
        }
        if (type == Frame.MSG_MAXIMA_CTRL) {
            MaximaCTRLMessage ctrl = MaximaCTRLMessage.fromBytes(payload);
            if (ctrl.getType().getAsInt() == MaximaCTRLMessage.TYPE_MLS) {
                mTheirMlsAddress = MaximaCTRLMessage.mlsAddressFrom(ctrl, mHost + ":" + mPort);
            } else if (ctrl.getType().getAsInt() == CTRL_MAILBOX_INFO) {
                // The relay probes us to prove we hold this route's private key
                // (route hijack defence) and to authorize deleting delivered
                // mail. Sign "maxack"+keyDER+seq with our per-host key - the
                // relay verifies against the routing PUBLIC key it has. Only
                // the holder of the private key can answer, so a squatter who
                // announced our public key can neither be served nor drain us.
                answerMailboxChallenge(ctrl);
            }
            return true;
        }
        // A host may probe US with a SINGLE_PING (the reference's reachability
        // check). Answer it exactly as a classic node does, or the prober marks
        // us unreachable. The reply is a comms-only greeting (no host claim; the
        // peer already knows the address it dialled).
        if (type == Frame.MSG_SINGLE_PING) {
            writeFrame(Frame.singlePong(Greeting.commsOnly(mVersion, null, mPort)));
            return true;
        }
        // Our own keep-alive's answer. Nothing to do - reading it already
        // stamped mLastInbound, which is the whole point.
        if (type == Frame.MSG_SINGLE_PONG) {
            return true;
        }
        return false;
    }

    /** A message that arrived for us, already decrypted and verified. */
    public static final class Inbound {
        public final MaximaMessage message;
        public final MiniData msgid;
        public final boolean signatureValid;

        Inbound(MaximaMessage zMsg, MiniData zMsgid, boolean zSigValid) {
            message = zMsg;
            msgid = zMsgid;
            signatureValid = zSigValid;
        }
    }

    /**
     * Decrypt and verify a TXPOW carrier addressed to us, from ANY inbound path
     * - a relay's pump loop or the direct endpoint. Factored out so the two can
     * never drift: a divergence here is a signature check that only runs on one
     * of them.
     *
     * @param zUnit          the carrier
     * @param zExpectedKey   the routing key it must be addressed to (a relay's
     *                       per-host key, or our identity key on a direct link)
     * @param zPrivateDer    the matching private key, DER-encoded
     * @return an ack status; on OK the out-param carries the Inbound
     */
    public static int unwrap(MaxTxPoW zUnit, byte[] zExpectedKey, byte[] zPrivateDer,
                             Inbound[] zOut) {
        try {
            if (!zUnit.checkValidTxPoW()) {
                return Frame.RESPONSE_WRONGHASH;
            }
            // One size ceiling for BOTH inbound paths. The relay checked this
            // separately; folding it here means the direct endpoint cannot
            // silently accept a larger package than the relay would.
            if (Codec.serialise(zUnit.mMaxima).length
                    > com.eurobuddha.maxima.core.msg.MaximaPackage.MAX_SIZE) {
                return Frame.RESPONSE_TOOBIG;
            }
            if (!new MiniData(zExpectedKey).equals(zUnit.mMaxima.mTo)) {
                // Not for us. On a direct endpoint this means someone tried to
                // use a phone as a relay - refused, we forward for nobody.
                return Frame.RESPONSE_UNKNOWN;
            }
            CryptoPackage cp = CryptoPackage.fromBytes(zUnit.mMaxima.mData.getBytes());
            byte[] plain = MaximaCrypto.decrypt(cp, zPrivateDer);
            MaximaInternal mi = MaximaInternal.fromBytes(plain);
            boolean sigOk = MaximaCrypto.verify(
                    mi.mFrom.getBytes(), mi.mData.getBytes(), mi.mSignature.getBytes());
            MaximaMessage mm = MaximaMessage.fromBytes(mi.mData.getBytes());
            // The receiver's bind check: the signer must be the claimed sender.
            if (!mm.mFrom.equals(mi.mFrom) || !sigOk) {
                return Frame.RESPONSE_FAIL;
            }
            MiniData msgid = new MiniData(
                    com.eurobuddha.maxima.core.crypto.Hashes.sha3(mi.mData.getBytes()));
            zOut[0] = new Inbound(mm, msgid, true);
            return Frame.RESPONSE_OK;
        } catch (Exception e) {
            return Frame.RESPONSE_FAIL;
        }
    }

    /**
     * Block until a relayed Maxima message arrives for us, or the budget runs
     * out. Chain traffic and control frames are consumed and ignored.
     *
     * @return the decrypted message, or null on timeout
     */
    public Inbound receive(int zTimeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + zTimeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int remaining = (int) Math.max(1000, deadline - System.currentTimeMillis());
            mSocket.setSoTimeout(remaining);

            byte[] rx;
            try {
                rx = Frame.readOrSkip(mIn, MAX_KEEP_BYTES);
            } catch (java.net.SocketTimeoutException e) {
                return null;
            }
            // Any completed read - even a skipped oversize frame - proves the
            // host is alive. Stamp the read-clock exactly as the reference does.
            mLastInbound = System.currentTimeMillis();
            if (rx == null || rx.length < 1) {
                continue;
            }

            int type = Frame.typeOf(rx);
            if (type != Frame.MSG_MAXIMA_TXPOW) {
                handleControlFrame(rx);
                continue;
            }

            byte[] payload = new byte[rx.length - 1];
            System.arraycopy(rx, 1, payload, 0, payload.length);

            MaxTxPoW unit = MaxTxPoW.fromBytes(payload);

            // ONE unwrap for both inbound paths. This used to be an inline copy
            // that could drift from the direct endpoint's; now there is a single
            // verification, addressed to our per-host key and decrypted with the
            // per-host private key.
            Inbound[] holder = new Inbound[1];
            int status = unwrap(unit, routingKey(),
                    mPerHostKey.getPrivate().getEncoded(), holder);
            ack(status);
            if (status == Frame.RESPONSE_OK && holder[0] != null) {
                return holder[0];
            }
        }
        return null;
    }

    private void ack(int zStatus) throws Exception {
        writeFrame(Frame.ack(zStatus));
    }

    /** Serialised size of our CTRL/TYPE_ID announcement, for diagnostics. */
    public int idFrameSize() {
        return Frame.body(Frame.MSG_MAXIMA_CTRL,
                MaximaCTRLMessage.id(new MiniData(routingKey()))).length;
    }

    public byte[] serialisedGreeting() {
        return Codec.serialise(myGreeting());
    }

    /** Our greeting: the advertised endpoint when we have a proven one, else the
     *  measured-safe default of the dialled host:port. */
    private Greeting myGreeting() {
        String adv = mAdvertisedEndpoint;
        if (adv != null) {
            int c = adv.lastIndexOf(':');
            if (c > 0) {
                try {
                    return Greeting.commsOnly(mVersion, adv.substring(0, c),
                            Integer.parseInt(adv.substring(c + 1)));
                } catch (NumberFormatException ignored) {
                    // fall through to the default
                }
            }
        }
        return Greeting.commsOnly(mVersion, mHost, mPort);
    }

    @Override
    public void close() {
        mReaderRun = false;   // a blocked reader unblocks via the socket close
        try {
            if (mSocket != null) mSocket.close();
        } catch (Exception ignored) {
        }
        mAttached = false;
    }

    // ---------------------------------------------------------------
    // push reader
    // ---------------------------------------------------------------

    /**
     * Keep the NAT mapping alive by writing at least this often. Consumer
     * routers reap idle TCP mappings after ~30-60s; the old 120s cadence let
     * the mapping die, the socket black-holed, and the connection churned -
     * the direct cause of stranded mailbox mail. Same ~25s cadence the big
     * messengers use, for the same reason.
     */
    public static final long NAT_KEEPALIVE_MS = 25_000;

    /** Where the reader thread delivers inbound messages and reports death. */
    public interface Sink {
        void onInbound(Inbound zIn);

        /** The socket died (reap, RST, ...). The connection is already closed. */
        void onDead(String zHostPort);
    }

    private volatile Thread mReader;
    private volatile boolean mReaderRun;

    /**
     * Start the dedicated reader for this connection: block on {@link #receive}
     * so a pushed message is handled the instant it arrives (the relay PUSHES;
     * the old round-robin polling added seconds of latency per pass), and send
     * a keep-alive whenever the link has been write-idle past
     * {@link #NAT_KEEPALIVE_MS}. On any socket error the reader closes the
     * connection and reports {@link Sink#onDead}; the caller's maintain loop
     * re-attaches. Idempotent while a reader is running.
     */
    public synchronized void startReader(Sink zSink) {
        if (mReader != null || !mAttached || zSink == null) {
            return;
        }
        mReaderRun = true;
        final String hp = mHost + ":" + mPort;
        Thread t = new Thread(() -> {
            try {
                while (mReaderRun && mAttached) {
                    if (needsKeepalive(NAT_KEEPALIVE_MS)) {
                        keepalive();
                    }
                    // 10s slice so the keep-alive check runs often enough;
                    // receive returns null on a clean timeout.
                    Inbound in = receive(10_000);
                    if (in != null) {
                        try {
                            zSink.onInbound(in);
                        } catch (Exception ignored) {
                            // a bad handler must not kill the transport
                        }
                    }
                }
            } catch (Exception e) {
                if (mReaderRun) {
                    close();
                    zSink.onDead(hp);
                }
            } finally {
                mReader = null;
            }
        }, "maxima-reader-" + hp);
        t.setDaemon(true);
        mReader = t;
        t.start();
    }
}
