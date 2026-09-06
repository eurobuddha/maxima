package com.eurobuddha.maxima.core.rpc;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.identity.MxAddress;
import com.eurobuddha.maxima.core.msg.MaximaMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request/response over Maxima, where every reply is a fresh outbound message.
 *
 * Both halves work from behind NAT:
 *   - a request goes out on a short-lived socket (no greeting needed)
 *   - the peer's reply also goes out on ITS own short-lived socket
 * Neither side ever needs to accept an inbound connection; inbound arrives via
 * each side's relay.
 *
 * Pending requests are tracked by correlation id and time out, because a reply
 * that never arrives is indistinguishable from a peer that went offline
 * mid-conversation - which on a mobile network is routine, not exceptional.
 */
public final class RpcPeer {

    /** How long to keep a pending request before giving up on it. */
    public static final long DEFAULT_TIMEOUT_MS = 60_000;

    public interface ResponseHandler {
        void onResponse(byte[] zPayload);

        void onError(String zMessage);
    }

    private static final class Pending {
        final ResponseHandler handler;
        final long deadline;
        final String method;

        Pending(ResponseHandler h, long d, String m) {
            handler = h;
            deadline = d;
            method = m;
        }
    }

    private final MaximaIdentity mIdentity;
    private final ServiceRegistry mServices;
    private final Map<String, Pending> mPending = new ConcurrentHashMap<>();

    /** Addresses we can be reached on - all of them, when multi-homed. */
    private volatile List<String> mMyAddresses = new ArrayList<>();

    /** Optional: send over an attached relay link instead of a fresh socket. */
    private volatile MaximaSender.Attached mAttached;

    public RpcPeer(MaximaIdentity zIdentity, ServiceRegistry zServices) {
        mIdentity = zIdentity;
        mServices = zServices;
    }

    public void setAttached(MaximaSender.Attached zVia) {
        mAttached = zVia;
    }

    public ServiceRegistry services() {
        return mServices;
    }

    public void setMyAddresses(List<String> zAddresses) {
        mMyAddresses = new ArrayList<>(zAddresses);
    }

    public List<String> myAddresses() {
        return mMyAddresses;
    }

    public int pendingCount() {
        return mPending.size();
    }

    /** A fresh correlation id. 16 random bytes is ample and never collides in practice. */
    public static String newCorrelationId() {
        return new MiniData(MaximaCrypto.randomBytes(16)).to0xString();
    }

    /**
     * Send a request to a peer address and register a handler for the reply.
     *
     * @param zPeerAddress their contact address, {@code Mx...@host:port}
     * @return the correlation id
     */
    public String call(String zPeerAddress, String zMethod, byte[] zPayload,
                       ResponseHandler zHandler, long zTimeoutMs) throws Exception {
        return call(zPeerAddress, zMethod, zPayload, zHandler, zTimeoutMs,
                MaximaSender.CONNECT_TIMEOUT_MS, MaximaSender.READ_TIMEOUT_MS);
    }

    /** As above with the SOCKET leashes for the request's own send - a push to a device that
     *  vanished behind NAT should fail in seconds, not the default 20 s + 20 s. */
    public String call(String zPeerAddress, String zMethod, byte[] zPayload,
                       ResponseHandler zHandler, long zTimeoutMs, int zConnectMs, int zReadMs)
            throws Exception {

        String id = newCorrelationId();
        RpcEnvelope env = RpcEnvelope.request(id, zMethod, mMyAddresses, zPayload);

        mPending.put(id, new Pending(zHandler, System.currentTimeMillis() + zTimeoutMs, zMethod));

        try {
            sendTo(zPeerAddress, env, zConnectMs, zReadMs);
        } catch (Exception e) {
            mPending.remove(id);
            throw e;
        }
        return id;
    }

    /** Replies dial out on a BOUNDED pool: a thread per reply address per request had no
     *  ceiling under a request flood. Falls back to the dispatching thread when saturated. */
    private final java.util.concurrent.ExecutorService mReplyExec =
            new java.util.concurrent.ThreadPoolExecutor(2, 8, 30, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r, "rpc-reply");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    public String call(String zPeerAddress, String zMethod, byte[] zPayload,
                       ResponseHandler zHandler) throws Exception {
        return call(zPeerAddress, zMethod, zPayload, zHandler, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Feed an inbound, already-decrypted and signature-verified Maxima message
     * in here. Returns true if it was ours.
     *
     * A REQUEST is dispatched and answered by dialling out to the reply-to
     * address - trying each in turn, since a multi-homed peer may have lost a
     * relay since it sent.
     */
    public boolean onInbound(MaximaMessage zMsg) {
        if (!RpcEnvelope.APPLICATION.equals(zMsg.mApplication.toString())) {
            return false;
        }

        RpcEnvelope env;
        try {
            env = RpcEnvelope.fromBytes(zMsg.mData.getBytes());
        } catch (IOException e) {
            return false;
        }

        if (env.isRequest()) {
            ServiceRegistry.Request req = new ServiceRegistry.Request(
                    env.getMethod(), env.getPayload(),
                    zMsg.mFrom.getBytes(), env.getReplyTo());

            RpcEnvelope reply = mServices.dispatch(env.getId(), req);

            // Answer by dialling OUT. This is the whole trick. To EVERY address the caller
            // advertised, in PARALLEL, with a short leash, off this (reader) thread:
            //  - sequentially trying addr[0] first cost a full 20s socket timeout whenever the
            //    caller's best-scoring relay was slow (a phone behind the same NAT as the Pi
            //    relay scores it first; the Pi is a busy 32-bit box) — every RPC took 20s;
            //  - the caller removes its pending id on the first copy, so the second copy is
            //    dropped as unsolicited (no duplicate delivery to the app);
            //  - this ran on the inbound reader thread inside the node's synchronized handle():
            //    a stalled reply deafened the whole node for the duration.
            final List<String> targets = new ArrayList<>(env.getReplyTo());
            for (final String addr : targets) {
                mReplyExec.execute(() -> {
                    try {
                        sendTo(addr, reply, REPLY_CONNECT_TIMEOUT_MS, REPLY_READ_TIMEOUT_MS);
                    } catch (Exception e) {
                        // another address may carry it; a lost reply times out at the caller.
                        // Logged (stdout = the node journal): an oversize reply that can never
                        // be sent used to vanish here with no trace at either end.
                        System.out.println("[rpc] reply to " + addr + " failed: "
                                + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    }
                });
            }
            return true;
        }

        Pending p = mPending.remove(env.getId());
        if (p == null) {
            // Unsolicited or already timed out. Dropping is correct: an
            // attacker must otherwise be able to forge a correlation id.
            return true;
        }
        if (env.isError()) {
            p.handler.onError(env.getPayloadAsString());
        } else {
            p.handler.onResponse(env.getPayload());
        }
        return true;
    }

    /** Expire pending requests past their deadline. Call periodically. */
    public int expire() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Map.Entry<String, Pending> e : mPending.entrySet()) {
            if (e.getValue().deadline <= now) {
                mPending.remove(e.getKey());
                e.getValue().handler.onError("timeout after "
                        + DEFAULT_TIMEOUT_MS + "ms waiting for " + e.getValue().method);
                n++;
            }
        }
        return n;
    }

    /** Reply leash: a relay that cannot take the bytes in this long is not the one carrying
     *  this reply — the parallel copy to the caller's other relay is. */
    static final int REPLY_CONNECT_TIMEOUT_MS = 6_000;
    static final int REPLY_READ_TIMEOUT_MS = 8_000;

    /** Send an envelope to an {@code Mx...@host:port} address (sender's default timeouts). */
    private void sendTo(String zAddress, RpcEnvelope zEnvelope) throws Exception {
        sendTo(zAddress, zEnvelope, MaximaSender.CONNECT_TIMEOUT_MS, MaximaSender.READ_TIMEOUT_MS);
    }

    private void sendTo(String zAddress, RpcEnvelope zEnvelope, int zConnectMs, int zReadMs) throws Exception {
        if (!MxAddress.isValidContactAddress(zAddress)) {
            throw new IllegalArgumentException("Bad peer address: " + zAddress);
        }
        // Classic parses on the FIRST '@' and the FIRST ':'.
        int at = zAddress.indexOf('@');
        int colon = zAddress.indexOf(':');
        MiniData routingKey = MxAddress.convert(zAddress.substring(0, at));
        String host = zAddress.substring(at + 1, colon);
        int port = Integer.parseInt(zAddress.substring(colon + 1));

        MaximaSender.Built built = MaximaSender.build(
                mIdentity.publicKey(),
                mIdentity.keyPair().getPrivate(),
                routingKey.getBytes(),
                RpcEnvelope.APPLICATION,
                zEnvelope.toBytes(),
                System.currentTimeMillis());

        MaximaSender.Result res = MaximaSender.send(host, port, built.unit, built.msgid, zConnectMs, zReadMs, mAttached);
        if (!res.isOk()) {
            throw new IllegalStateException("send failed: " + res.statusName);
        }
    }
}
