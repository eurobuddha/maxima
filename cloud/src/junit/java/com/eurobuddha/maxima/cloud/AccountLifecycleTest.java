package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;
import com.eurobuddha.maxima.core.util.SerialLanes;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** The real account owner with synthetic identity and no network start or wallet implementation. */
public class AccountLifecycleTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private ParlonsCore core;
    private final byte[] device = {1, 2, 3, 4};
    private final AtomicInteger walletCalls = new AtomicInteger();
    private volatile boolean blockWalletOpen;
    private final CountDownLatch walletOpening = new CountDownLatch(1), releaseWallet = new CountDownLatch(1);

    @Before public void setUp() throws Exception {
        ParlonsCore.Config config = new ParlonsCore.Config();
        config.builtInRelays = false; config.relayPort = 0; config.directPort = 0;
        AccountWallet wallet = (AccountWallet) Proxy.newProxyInstance(AccountWallet.class.getClassLoader(),
                new Class<?>[] {AccountWallet.class}, (proxy, method, args) -> {
                    walletCalls.incrementAndGet();
                    if (blockWalletOpen && method.getName().equals("open")) {
                        walletOpening.countDown();
                        boolean done = false;
                        while (!done) {
                            try { releaseWallet.await(); done = true; } catch (InterruptedException ignored) { }
                        }
                        return null;
                    }
                    throw new AssertionError("no wallet operation allowed: " + method.getName());
                });
        core = new ParlonsCore(MaximaIdentity.fromSeed(new MiniData(new byte[32])),
                temp.getRoot().toPath(), config, wallet, null);
        assertTrue(core.pairing().authorizeLocal(device, "test", true));
    }

    @After public void tearDown() { releaseWallet.countDown(); if (core != null) core.shutdown(); }

    @Test public void shutdownClosesEveryOwnedExecutor() throws Exception {
        core.shutdown();
        for (String name : new String[] {"mWalletExec", "mHostExec"}) {
            assertTrue(name, ((ExecutorService) field(core, name)).isShutdown());
        }
        Object control = core.control();
        for (String name : new String[] {"mCallExec", "mMediaExec", "mPushPool", "mStateFlusher", "mConsoleExec"}) {
            assertTrue(name, ((ExecutorService) field(control, name)).isShutdown());
        }
        assertTrue("send lanes", ((ExecutorService) field(field(control, "mSendExec"), "mPool")).isShutdown());
        assertTrue("optional wake worker", ((ExecutorService) field(core.control().wakeProxy(), "mExec")).isShutdown());
    }

    @Test public void stoppedAccountRefusesLocalRequestsAndPairing() throws Exception {
        ServiceRegistry registry = core.node().services();
        registry.dispatchLocal(request(ParlonsControl.M_SETTINGS_GET));
        core.shutdown();
        for (String method : new String[] {ParlonsControl.M_SETTINGS_GET, ParlonsControl.M_PAIR}) {
            try { registry.dispatchLocal(request(method)); fail("stopped account accepted " + method); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("closed")); }
        }
        assertFalse(core.control().wakeProxy().wake("test", "https://wake.example/v1/wake", "ab12", "prod", "message"));
    }

    @Test public void aQueuedPaymentCannotStartAfterItsAccountStops() throws Exception {
        core.control().setPaySource(new ParlonsControl.PaySource() {
            public String myWalletAddress() { return ""; }
            public boolean ready() { return true; }
            public String walletError() { return ""; }
            public int uses() { return 0; }
            public void raiseUsesTo(int to) { fail("no counter adjustment expected"); }
            public String walletScript() { return ""; }
            public String walletHex() { return ""; }
        });
        SerialLanes lanes = (SerialLanes) field(core.control(), "mSendExec");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            lanes.execute("wallet", () -> {
                started.countDown();
                boolean done = false;
                while (!done) {
                    try { release.await(); done = true; } catch (InterruptedException ignored) { }
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            String json = "{\"to\":\"0x" + "12".repeat(32) + "\",\"amount\":\"1\",\"pid\":\"queued\"}";
            byte[] reply = core.node().services().dispatchLocal(new ServiceRegistry.Request(
                    ParlonsControl.M_WALLET_SEND, json.getBytes(StandardCharsets.UTF_8), device, Collections.emptyList()));
            assertTrue(new String(reply, StandardCharsets.UTF_8).contains("\"building\""));
            core.shutdown();
            release.countDown();
            assertTrue(((ExecutorService) field(lanes, "mPool")).awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("no signing or broadcasting call was made", 0, walletCalls.get());
        } finally { release.countDown(); lanes.shutdownNow(); }
    }

    @Test public void aLateWalletOpenCannotPublishReadinessOrStartUpkeepAfterStop() throws Exception {
        blockWalletOpen = true;
        Field running = ParlonsCore.class.getDeclaredField("mRunning"); running.setAccessible(true);
        running.setBoolean(core, true); // exercise wallet startup without starting the network
        java.lang.reflect.Method open = ParlonsCore.class.getDeclaredMethod("openAccountWallet");
        open.setAccessible(true); open.invoke(core);
        assertTrue(walletOpening.await(5, TimeUnit.SECONDS));
        core.shutdown();
        releaseWallet.countDown();
        assertTrue(((ExecutorService) field(core, "mWalletExec")).awaitTermination(5, TimeUnit.SECONDS));
        assertEquals("opening may finish, but no follow-up wallet operation is allowed", 1, walletCalls.get());
        assertEquals(Boolean.FALSE, field(core, "mWalletOpen"));
    }

    @Test public void aStoppedRuntimeCannotBeStartedOrAdoptAnotherRelay() {
        core.shutdown();
        try { core.start(); fail("closed runtime restarted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("closed")); }
        assertFalse(core.adoptOwnRelay("127.0.0.1:9501"));
        assertEquals(0, walletCalls.get());
    }

    private ServiceRegistry.Request request(String method) {
        return new ServiceRegistry.Request(method, "{}".getBytes(StandardCharsets.UTF_8), device, Collections.emptyList());
    }

    static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
}
