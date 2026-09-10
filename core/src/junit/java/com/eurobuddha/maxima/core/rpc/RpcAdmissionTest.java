package com.eurobuddha.maxima.core.rpc;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.net.Frame;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real call admission and crypto, synthetic identity/attached transport, no sockets. */
public class RpcAdmissionTest {
    private static final MaximaIdentity ID = MaximaIdentity.fromSeed(new MiniData(new byte[32]));
    private static final String ADDRESS = ID.mxIdentity() + "@127.0.0.1:9501";
    private static final RpcPeer.ResponseHandler IGNORE = new RpcPeer.ResponseHandler() {
        public void onResponse(byte[] bytes) { }
        public void onError(String message) { }
    };

    private static RpcPeer peer(AtomicInteger sends) {
        RpcPeer p = new RpcPeer(ID, new ServiceRegistry());
        healthy(p, sends);
        return p;
    }
    private static void healthy(RpcPeer p, AtomicInteger sends) {
        p.setAttached((host, port, unit, msgid, readMs) -> {
            sends.incrementAndGet(); return new MaximaSender.Result(Frame.RESPONSE_OK, msgid, 0);
        });
    }
    private static String call(RpcPeer p) throws Exception { return p.call(ADDRESS, "test", new byte[0], IGNORE); }
    private static void reply(RpcPeer p, String id) {
        MaximaMessage m = new MaximaMessage();
        m.mApplication = new MiniString(RpcEnvelope.APPLICATION);
        m.mData = new MiniData(RpcEnvelope.response(id, new byte[0]).toBytes());
        assertTrue(p.onInbound(m));
    }

    @Test public void concurrentCallsShareOneBudgetAndReplyReleasesASlot() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        try (RpcPeer p = peer(sends)) {
            String first = call(p);
            for (int i = 1; i < 255; i++) call(p);
            List<Future<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(workers.submit(() -> {
                ready.countDown(); start.await();
                try { call(p); return true; }
                catch (IllegalStateException e) { assertTrue(e.getMessage().contains("capacity")); return false; }
            }));
            assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : calls) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals("one pending position remained", 1, accepted);
            assertEquals(256, p.pendingCount());
            assertEquals("refused calls never reach transport", 256, sends.get());
            reply(p, first);
            assertEquals(255, p.pendingCount());
            call(p);
            assertEquals(256, p.pendingCount()); assertEquals(257, sends.get());
            p.close(); assertEquals(0, p.pendingCount());
        } finally {
            start.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void sendFailureReleasesItsAdmission() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        try (RpcPeer p = peer(sends)) {
            for (int i = 0; i < 255; i++) call(p);
            p.setAttached((host, port, unit, msgid, readMs) -> { throw new IOException("synthetic send failure"); });
            try { call(p); fail("failed send returned a pending id"); }
            catch (IOException expected) { assertEquals("synthetic send failure", expected.getMessage()); }
            assertEquals(255, p.pendingCount());
            healthy(p, sends); call(p);
            assertEquals(256, p.pendingCount());
        }
    }

    @Test public void expiryDrainsAllCallsEvenWhenEveryCallbackThrows() throws Exception {
        AtomicInteger sends = new AtomicInteger(), errors = new AtomicInteger();
        try (RpcPeer p = peer(sends)) {
            for (int i = 0; i < 3; i++) p.call(ADDRESS, "expire", new byte[0], new RpcPeer.ResponseHandler() {
                public void onResponse(byte[] bytes) { fail("no reply supplied"); }
                public void onError(String message) {
                    assertTrue(message.startsWith("timeout")); errors.incrementAndGet();
                    throw new IllegalStateException("synthetic callback failure");
                }
            }, 0);
            assertEquals(3, p.expire());
            assertEquals(3, errors.get()); assertEquals(0, p.pendingCount());
            assertEquals(0, p.expire());
            call(p); assertEquals(1, p.pendingCount());
        }
    }
}
