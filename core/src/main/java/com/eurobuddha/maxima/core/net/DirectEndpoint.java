package com.eurobuddha.maxima.core.net;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.msg.MaxTxPoW;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tier 2: accept a connection DIRECTLY, when this device has a public port.
 *
 * The whole of the rest of the system dials out; this is the one place a
 * node listens. A sender that holds our direct address (Mx&lt;identity&gt;@ip:port)
 * connects straight here and hands us a message with no relay in the path -
 * one hop removed, one fewer party who can see the routing key.
 *
 * A phone is NOT a relay. This endpoint accepts ONLY messages addressed to our
 * own identity and forwards for nobody; anything else gets FAIL. That is an
 * architectural cap, not a config default.
 *
 * It faces the open internet from a handset, so it carries the same admission
 * control the relay learned the hard way: a hard connection cap, a per-source
 * cap, an idle timeout that reaps a socket which never sends, and the frame
 * reader's bounded allocation. Everything past decryption reuses
 * {@link HostConnection#unwrap} so the direct path and the relay path can never
 * verify differently.
 */
public final class DirectEndpoint {

    /** A handset is not a server. These are deliberately small. */
    private static final int MAX_CONNECTIONS = 16;
    private static final int MAX_PER_SOURCE = 4;
    private static final int IDLE_TIMEOUT_MS = 60_000;
    private static final int MAX_KEEP = 512 * 1024;

    /** What to do with a verified inbound message. */
    public interface Sink {
        void onInbound(HostConnection.Inbound zInbound);
    }

    private final MaximaIdentity mIdentity;
    private final String mVersion;
    private final Sink mSink;
    /** Our OWN hosted blobs, so a same-LAN peer can pull our profile/media
     *  directly (no relay). GET/HAS are served; PUT is refused. Null = not wired. */
    private final com.eurobuddha.maxima.core.store.BlobStore mBlobs;

    private volatile ServerSocket mServer;
    private volatile int mPort;
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final Map<String, Integer> mPerSource = new ConcurrentHashMap<>();
    private final AtomicInteger mConns = new AtomicInteger();

    public DirectEndpoint(MaximaIdentity zIdentity, String zVersion, Sink zSink) {
        this(zIdentity, zVersion, zSink, null);
    }

    public DirectEndpoint(MaximaIdentity zIdentity, String zVersion, Sink zSink,
                          com.eurobuddha.maxima.core.store.BlobStore zBlobs) {
        mIdentity = zIdentity;
        mVersion = zVersion;
        mSink = zSink;
        mBlobs = zBlobs;
    }

    /**
     * Start listening on zPort (0 = any free port). Idempotent per instance.
     *
     * @return the bound port, or -1 on failure
     */
    public synchronized int start(int zPort) {
        if (mRunning.get()) {
            return mPort;
        }
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("0.0.0.0", zPort));
            mServer = ss;
            mPort = ss.getLocalPort();
            mRunning.set(true);
            Thread t = new Thread(this::acceptLoop, "maxima-direct-accept");
            t.setDaemon(true);
            t.start();
            return mPort;
        } catch (Exception e) {
            return -1;
        }
    }

    public int port() {
        return mPort;
    }

    public boolean isRunning() {
        return mRunning.get();
    }

    public synchronized void stop() {
        mRunning.set(false);
        try {
            if (mServer != null) {
                mServer.close();
            }
        } catch (Exception ignored) {
        }
        mServer = null;
    }

    // ---------------------------------------------------------------

    private void acceptLoop() {
        while (mRunning.get()) {
            try {
                Socket s = mServer.accept();
                s.setTcpNoDelay(true);
                String src = s.getInetAddress() == null
                        ? "?" : s.getInetAddress().getHostAddress();

                if (mConns.get() >= MAX_CONNECTIONS) {
                    close(s);
                    continue;
                }
                int fromSource = mPerSource.merge(src, 1, Integer::sum);
                if (fromSource > MAX_PER_SOURCE) {
                    mPerSource.merge(src, -1, Integer::sum);
                    close(s);
                    continue;
                }
                mConns.incrementAndGet();
                try {
                    Thread t = new Thread(() -> serve(s, src), "maxima-direct-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (Throwable startFailed) {
                    // If the thread cannot start (OOM / limit), reverse BOTH
                    // counters we just took, or the endpoint slowly wedges shut.
                    mConns.decrementAndGet();
                    mPerSource.computeIfPresent(src, (k, v) -> v <= 1 ? null : v - 1);
                    close(s);
                }
            } catch (Exception e) {
                if (!mRunning.get()) {
                    return;
                }
            }
        }
    }

    private void serve(Socket zSocket, String zSource) {
        try {
            zSocket.setSoTimeout(IDLE_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(zSocket.getInputStream());
            DataOutputStream out = new DataOutputStream(zSocket.getOutputStream());

            while (mRunning.get() && !zSocket.isClosed()) {
                byte[] frame;
                try {
                    frame = Frame.readOrSkip(in, MAX_KEEP);
                } catch (java.net.SocketTimeoutException te) {
                    // A direct sender delivers and leaves; a socket that opens
                    // and says nothing is reaped rather than held.
                    break;
                }
                if (frame == null || frame.length < 1) {
                    continue;
                }
                int type = Frame.typeOf(frame);
                if (type == Frame.MSG_GREETING) {
                    // We are the endpoint - advertise no host, exactly as the
                    // relay greeting does. The sender already dialled us.
                    writeFrame(out, Frame.body(Frame.MSG_GREETING,
                            Greeting.commsOnly(mVersion, "", mPort)));
                    continue;
                }
                if (type != Frame.MSG_MAXIMA_TXPOW) {
                    // No control channel here. A phone is not a relay and has
                    // no directory or route table to talk to.
                    continue;
                }

                byte[] payload = new byte[frame.length - 1];
                System.arraycopy(frame, 1, payload, 0, payload.length);

                int status;
                HostConnection.Inbound[] holder = new HostConnection.Inbound[1];
                try {
                    MaxTxPoW unit = MaxTxPoW.fromBytes(payload);
                    status = HostConnection.unwrap(unit,
                            mIdentity.publicKey(),
                            mIdentity.keyPair().getPrivate().getEncoded(),
                            holder);
                } catch (Exception e) {
                    status = Frame.RESPONSE_FAIL;
                }
                if (status == Frame.RESPONSE_OK && holder[0] != null
                        && holder[0].message != null && holder[0].message.mApplication != null
                        && com.eurobuddha.maxima.core.media.MediaWire.isMediaApp(
                                holder[0].message.mApplication.toString())) {
                    // A blob request over the direct link: answer from OUR store with
                    // the chunk (or UNKNOWN) on this socket — do NOT hand it to the
                    // chat sink. This is what lets a same-LAN peer pull our profile
                    // directly, no relay.
                    serveBlob(out, holder[0].message.mApplication.toString(),
                            holder[0].message.mData == null ? new byte[0]
                                    : holder[0].message.mData.getBytes());
                    continue;
                }
                writeFrame(out, Frame.ack(status));
                if (status == Frame.RESPONSE_OK && holder[0] != null) {
                    // Dedup, replay window, chat, services all live behind the
                    // sink's handle() - identical treatment to a relayed message.
                    try {
                        mSink.onInbound(holder[0]);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            // Normal on disconnect.
        } finally {
            mPerSource.computeIfPresent(zSource, (k, v) -> v <= 1 ? null : v - 1);
            mConns.decrementAndGet();
            close(zSocket);
        }
    }

    /** Answer a direct blob request from our OWN store. GET returns the chunk (or
     *  UNKNOWN); HAS acks presence; PUT is refused — a phone is not a shelf for
     *  others. Bytes are ciphertext and the id is only learnable from a sealed
     *  manifest, the same open-GET trust model relays use. */
    private void serveBlob(DataOutputStream zOut, String zApp, byte[] zIdBytes) throws Exception {
        if (mBlobs == null || com.eurobuddha.maxima.core.media.MediaWire.APP_PUT.equals(zApp)) {
            writeFrame(zOut, Frame.ack(Frame.RESPONSE_UNKNOWN));
            return;
        }
        String id = new String(zIdBytes, java.nio.charset.StandardCharsets.UTF_8);
        if (com.eurobuddha.maxima.core.media.MediaWire.APP_HAS.equals(zApp)) {
            boolean has;
            try { has = mBlobs.has(id); } catch (Exception e) { has = false; }
            writeFrame(zOut, Frame.ack(has ? Frame.RESPONSE_OK : Frame.RESPONSE_UNKNOWN));
            return;
        }
        byte[] chunk;
        try { chunk = mBlobs.get(id); } catch (Exception e) { chunk = null; }
        if (chunk == null) {
            writeFrame(zOut, Frame.ack(Frame.RESPONSE_UNKNOWN));
        } else {
            writeFrame(zOut, Frame.body(Frame.MSG_PING,
                    new com.eurobuddha.maxima.core.codec.MiniData(chunk)));
        }
    }

    private static void writeFrame(DataOutputStream zOut, byte[] zBody) throws Exception {
        Frame.write(zOut, zBody);
    }

    private static void close(Socket zSocket) {
        try {
            zSocket.close();
        } catch (Exception ignored) {
        }
    }
}
