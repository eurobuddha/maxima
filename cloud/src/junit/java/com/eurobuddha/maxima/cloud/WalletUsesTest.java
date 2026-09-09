package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.rpc.ServiceRegistry;
import com.eurobuddha.maxima.core.util.SerialLanes;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Authenticated counter API with synthetic state; no wallet keys or transactions. */
public class WalletUsesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final byte[] device = {1, 2, 3, 4};
    private final AtomicInteger uses = new AtomicInteger(3), writes = new AtomicInteger();
    private volatile CountDownLatch release = new CountDownLatch(0);
    private volatile boolean failWrite;
    private volatile boolean rejectReads;
    private final ServiceRegistry registry = new ServiceRegistry();
    private SerialLanes sends;

    @Before public void setUp() throws Exception {
        DevicePairing pairing = new DevicePairing(temp.getRoot().toPath());
        assertTrue(pairing.authorizeLocal(device, "test", true));
        AccountWallet wallet = (AccountWallet) Proxy.newProxyInstance(
                AccountWallet.class.getClassLoader(), new Class<?>[] {AccountWallet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("maxUses")) return 128;
                    throw new AssertionError("unexpected wallet operation: " + method.getName());
                });
        ParlonsControl control = new ParlonsControl(null, null, pairing, wallet);
        control.setPaySource(new ParlonsControl.PaySource() {
            public boolean ready() { return true; }
            public String walletError() { return ""; }
            public String myWalletAddress() { return ""; }
            public int uses() {
                if (rejectReads) throw new IllegalStateException("counter file is locked by the writer");
                return uses.get();
            }
            public void raiseUsesTo(int to) {
                writes.incrementAndGet();
                try { release.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted", e);
                }
                if (failWrite) throw new IllegalStateException("synthetic disk failure");
                uses.accumulateAndGet(to, Math::max);
            }
            public String walletScript() { return ""; }
            public String walletHex() { return ""; }
        });
        control.registerOn(registry);
        Field field = ParlonsControl.class.getDeclaredField("mSendExec");
        field.setAccessible(true);
        sends = (SerialLanes) field.get(control);
    }

    @After public void tearDown() throws Exception {
        rejectReads = false;
        release.countDown();
        if (sends != null) { drain(); sends.shutdownNow(); }
    }

    @Test public void successMeansTheCounterWasWritten() throws Exception {
        JSONObject out = raise(10);
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals(10, uses.get());
        assertEquals(10, ((Number) out.get("uses")).intValue());
        assertEquals(1, writes.get());
        assertEquals(Boolean.TRUE, raise(10).get("ok")); // a lost success reply is safe to retry
        assertEquals(1, writes.get());
    }

    @Test public void aWriteFailureIsReturnedToTheCaller() throws Exception {
        failWrite = true;
        JSONObject out = raise(10);
        assertEquals(Boolean.FALSE, out.get("ok"));
        assertTrue(out.toString(), String.valueOf(out.get("error")).contains("synthetic disk failure"));
        assertEquals(3, uses.get());
        failWrite = false;
        assertEquals(Boolean.TRUE, raise(10).get("ok")); // a repaired disk can retry the same target
    }

    @Test public void slowWritesArePendingAndRetriesDoNotQueueAnotherWrite() throws Exception {
        release = new CountDownLatch(1);
        JSONObject pending = raise(10);
        assertEquals("old clients must not show success", Boolean.FALSE, pending.get("ok"));
        assertEquals(Boolean.TRUE, pending.get("pending"));
        assertNotNull(pending.get("key"));
        rejectReads = true;
        JSONObject retry = raise(10);
        assertEquals(pending.get("key"), retry.get("key"));
        assertEquals(Boolean.FALSE, raise(11).get("ok"));
        assertEquals(1, writes.get());
        assertEquals(3, uses.get());
        rejectReads = false;
        release.countDown();
        drain();
        JSONObject poll = new JSONObject(); poll.put("key", pending.get("key"));
        JSONObject done = call(poll);
        assertEquals(Boolean.TRUE, done.get("ok"));
        assertEquals(10, ((Number) done.get("uses")).intValue());
        assertEquals(1, writes.get());
    }

    @Test public void fractionalAndOverflowingCountersAreRejectedBeforeAdmission() throws Exception {
        for (Object to : new Object[] {10.5, 4294967306L, "10", -1, 129}) {
            assertEquals(String.valueOf(to), Boolean.FALSE, raise(to).get("ok"));
        }
        drain();
        assertEquals(0, writes.get());
    }

    private JSONObject raise(Object to) throws Exception {
        JSONObject in = new JSONObject(); in.put("raiseTo", to); return call(in);
    }

    private JSONObject call(JSONObject in) throws Exception {
        byte[] out = registry.dispatchLocal(new ServiceRegistry.Request(ParlonsControl.M_WALLET_USES,
                in.toString().getBytes(StandardCharsets.UTF_8), device, Collections.emptyList()));
        return (JSONObject) new JSONParser().parse(new String(out, StandardCharsets.UTF_8));
    }

    private void drain() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        sends.execute("wallet", done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
}
