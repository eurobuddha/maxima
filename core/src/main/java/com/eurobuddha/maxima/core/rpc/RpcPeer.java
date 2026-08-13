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

    public RpcPeer(MaximaIdentity zIdentity, ServiceRegistry zServices) {
        mIdentity = zIdentity;
        mServices = zServices;
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

        String id = newCorrelationId();
        RpcEnvelope env = RpcEnvelope.request(id, zMethod, mMyAddresses, zPayload);

        mPending.put(id, new Pending(zHandler, System.currentTimeMillis() + zTimeoutMs, zMethod));

        try {
            sendTo(zPeerAddress, env);
        } catch (Exception e) {
            mPending.remove(id);
            throw e;
        }
        return id;
    }

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

            // Answer by dialling OUT. This is the whole trick.
            for (String addr : env.getReplyTo()) {
                try {
                    sendTo(addr, reply);
                    break;
                } catch (Exception ignored) {
                    // try the next address - multi-homing earns its keep here
                }
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

    /** Send an envelope to an {@code Mx...@host:port} address. */
    private void sendTo(String zAddress, RpcEnvelope zEnvelope) throws Exception {
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

        MaximaSender.Result res = MaximaSender.send(host, port, built.unit, built.msgid);
        if (!res.isOk()) {
            throw new IllegalStateException("send failed: " + res.statusName);
        }
    }
}
