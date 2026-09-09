package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;
import com.eurobuddha.maxima.core.util.SerialLanes;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real authenticated handlers; fake wallet has no keys, network or signing implementation. */
@RunWith(Parameterized.class)
public class PaymentRetryTest {
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<Object[]> methods() {
        return Arrays.asList(new Object[][] {
                {ParlonsControl.M_WALLET_SEND}, {ParlonsControl.M_WALLET_BUILDSEND}
        });
    }

    @Parameterized.Parameter public String method;
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final byte[] device = new byte[] {1, 2, 3, 4};
    private final AtomicInteger builds = new AtomicInteger(), publishes = new AtomicInteger();
    private MaximaNode node;
    private ServiceRegistry registry;
    private SerialLanes sends;

    @Before public void setUp() throws Exception {
        DevicePairing pairing = new DevicePairing(temp.getRoot().toPath());
        assertTrue(pairing.authorizeLocal(device, "test", true));
        node = new MaximaNode(MaximaIdentity.fromSeed(new MiniData(new byte[32])), "retry-test", 1);
        AccountWallet wallet = (AccountWallet) Proxy.newProxyInstance(
                AccountWallet.class.getClassLoader(), new Class<?>[] {AccountWallet.class},
                (proxy, called, args) -> {
                    switch (called.getName()) {
                        case "canBuildWithoutPublish": return true;
                        case "build":
                            builds.incrementAndGet();
                            return new AccountWallet.Payment("test-only", "", "");
                        case "publish": publishes.incrementAndGet(); return null;
                        default: throw new AssertionError("unexpected wallet operation: " + called.getName());
                    }
                });
        ParlonsControl control = new ParlonsControl(node, null, pairing, wallet);
        control.setPaySource(new ParlonsControl.PaySource() {
            public boolean ready() { return true; }
            public String walletError() { return ""; }
            public String myWalletAddress() { return ""; }
            public int uses() { return 0; }
            public void raiseUsesTo(int to) { throw new AssertionError("no signing counters in this test"); }
            public String walletScript() { return ""; }
            public String walletHex() { return ""; }
        });
        registry = new ServiceRegistry();
        control.registerOn(registry);
        Field field = ParlonsControl.class.getDeclaredField("mSendExec");
        field.setAccessible(true);
        sends = (SerialLanes) field.get(control);
    }

    @After public void tearDown() {
        if (sends != null) sends.shutdownNow();
        if (node != null) node.stop();
    }

    @Test public void repeatingARejectedAmountNeverReportsBuilding() throws Exception {
        for (String amount : new String[] {"0", "0.000"}) {
            String pid = "rejected-" + amount;
            assertEquals(Boolean.FALSE, call(amount, pid).get("ok"));
            assertEquals("rejected request must stay rejected", Boolean.FALSE, call(amount, pid).get("ok"));
        }
        drain();
        assertEquals(0, builds.get());
        assertEquals(0, publishes.get());
    }

    @Test public void aCorrectedRequestWithTheSameIdExecutesExactlyOnce() throws Exception {
        assertEquals(Boolean.FALSE, call("0", "retry").get("ok"));
        assertEquals(Boolean.TRUE, call("1", "retry").get("ok"));
        assertEquals(Boolean.TRUE, call("1", "retry").get("ok"));
        drain();
        assertEquals("corrected request must reach the wallet once", 1, builds.get());
        assertEquals(ParlonsControl.M_WALLET_SEND.equals(method) ? 1 : 0, publishes.get());
    }

    private JSONObject call(String amount, String pid) throws Exception {
        JSONObject in = new JSONObject();
        in.put("to", "0x" + "12".repeat(32));
        in.put("amount", amount);
        in.put("pid", pid);
        byte[] response = registry.dispatchLocal(new ServiceRegistry.Request(method,
                in.toString().getBytes(StandardCharsets.UTF_8), device, Collections.emptyList()));
        return (JSONObject) new JSONParser().parse(new String(response, StandardCharsets.UTF_8));
    }

    private void drain() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        sends.execute("wallet", done::countDown);
        assertTrue("wallet lane drained", done.await(5, TimeUnit.SECONDS));
    }
}
