package com.eurobuddha.maxima.core.rpc;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The services this node offers to peers.
 *
 * Everything a phone contributes in Tier 1 - mailbox, directory replica,
 * address gossip, content availability, witness receipts - is registered here
 * and answered by dialling out. None of it needs inbound reachability.
 */
public final class ServiceRegistry {

    /** Handles one request and returns the response payload. */
    public interface Handler {
        byte[] handle(Request zRequest) throws Exception;
    }

    /** A request plus who asked. */
    public static final class Request {
        public final String method;
        public final byte[] payload;
        /** Sender's Maxima identity public key (DER), already signature-verified. */
        public final byte[] fromPublicKey;
        /** Every address the sender says it can be reached on. */
        public final java.util.List<String> replyTo;

        public Request(String zMethod, byte[] zPayload, byte[] zFrom,
                       java.util.List<String> zReplyTo) {
            method = zMethod;
            payload = zPayload;
            fromPublicKey = zFrom;
            replyTo = zReplyTo;
        }

        public String payloadAsString() {
            return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private final Map<String, Handler> mHandlers = new ConcurrentHashMap<>();

    public ServiceRegistry register(String zMethod, Handler zHandler) {
        mHandlers.put(zMethod, zHandler);
        return this;
    }

    public void unregister(String zMethod) {
        mHandlers.remove(zMethod);
    }

    public boolean has(String zMethod) {
        return mHandlers.containsKey(zMethod);
    }

    public Set<String> methods() {
        return mHandlers.keySet();
    }

    /**
     * Dispatch. Never throws - a handler failure becomes an ERROR envelope so
     * the caller is not left waiting for a reply that will never come.
     */
    public RpcEnvelope dispatch(String zId, Request zRequest) {
        Handler h = mHandlers.get(zRequest.method);
        if (h == null) {
            return RpcEnvelope.error(zId, "no such method: " + zRequest.method);
        }
        try {
            byte[] out = h.handle(zRequest);
            return RpcEnvelope.response(zId, out == null ? new byte[0] : out);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return RpcEnvelope.error(zId, msg);
        }
    }
}
