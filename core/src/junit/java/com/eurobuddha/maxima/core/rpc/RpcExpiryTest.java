package com.eurobuddha.maxima.core.rpc;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.net.Frame;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class RpcExpiryTest {
    private static final MaximaIdentity ID = MaximaIdentity.fromSeed(new MiniData(new byte[32]));

    private static RpcPeer peer() {
        RpcPeer p = new RpcPeer(ID, new ServiceRegistry());
        // Exercise the real call path without opening a socket or sending a message.
        p.setAttached((host, port, unit, msgid, readMs) -> new MaximaSender.Result(Frame.RESPONSE_OK, msgid, 0));
        return p;
    }

    @Test public void expiryReportsTheRequestedTimeoutAndOnlyOnce() throws Exception {
        RpcPeer p = peer();
        AtomicReference<String> error = new AtomicReference<>();
        p.call(ID.mxIdentity() + "@127.0.0.1:9501", "test", new byte[0], new RpcPeer.ResponseHandler() {
            public void onResponse(byte[] payload) { fail("no reply was delivered"); }
            public void onError(String message) { assertNull(error.getAndSet(message)); }
        }, 0);
        assertEquals(1, p.expire());
        assertEquals("timeout after 0ms waiting for test", error.get());
        assertEquals(0, p.expire());
    }

    @Test public void replyWinningAfterExpirySnapshotIsNeverFollowedByAnError() throws Exception {
        RpcPeer p = peer();
        AtomicInteger replies = new AtomicInteger(), errors = new AtomicInteger();
        String id = p.call(ID.mxIdentity() + "@127.0.0.1:9501", "test", new byte[0], new RpcPeer.ResponseHandler() {
            public void onResponse(byte[] payload) { replies.incrementAndGet(); }
            public void onError(String message) { errors.incrementAndGet(); }
        }, 0);
        MaximaMessage reply = new MaximaMessage();
        reply.mApplication = new MiniString(RpcEnvelope.APPLICATION);
        reply.mData = new MiniData(RpcEnvelope.response(id, new byte[0]).toBytes());

        // A ConcurrentHashMap iterator may retain an entry that another thread removes.
        // Reproduce that exact interleaving deterministically, without sleeps or a race lottery.
        Field field = RpcPeer.class.getDeclaredField("mPending");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> original = (Map<String, Object>) field.get(p);
        Map<String, Object> racing = new ConcurrentHashMap<String, Object>(original) {
            @Override public Set<Map.Entry<String, Object>> entrySet() {
                Set<Map.Entry<String, Object>> snapshot = new LinkedHashMap<>(super.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))).entrySet();
                p.onInbound(reply);
                return snapshot;
            }
        };
        field.set(p, racing);
        assertEquals(0, p.expire());
        assertEquals(1, replies.get());
        assertEquals(0, errors.get());
        assertEquals(0, p.pendingCount());
    }
}
