package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import static org.junit.Assert.*;

/** Exercise the actual private admission paths without starting a relay or dialing peers. */
public class RelayRateLimitTest {
    private static final MaximaIdentity ID = MaximaIdentity.fromPhrase(Bip39.generate(24));
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> limits(RelayServer relay, String name) throws Exception {
        return (Map<String, Object>) field(RelayServer.class, name).get(relay);
    }
    private static boolean allow(RelayServer relay, Map<String, Object> map, String key, int rate) throws Exception {
        Method m = RelayServer.class.getDeclaredMethod("allow", Map.class, String.class, int.class);
        m.setAccessible(true); return (boolean) m.invoke(relay, map, key, rate);
    }
    private static boolean probe(RelayServer relay, String key) throws Exception {
        Method m = RelayServer.class.getDeclaredMethod("allowProbe", String.class);
        m.setAccessible(true); return (boolean) m.invoke(relay, key);
    }

    @Test public void activeTableHasAHardCapAndKeepsExistingAllowances() throws Exception {
        RelayServer relay = new RelayServer(ID, 0, "1.0.48");
        try {
            Map<String, Object> map = limits(relay, "mLimits");
            for (int i = 0; i < 50_000; i++) assertTrue(allow(relay, map, "key" + i, 2));
            for (int i = 0; i < 100; i++) assertFalse("new keys at capacity", allow(relay, map, "extra" + i, 2));
            assertEquals(50_000, map.size());
            assertTrue(allow(relay, map, "key0", 2));
            assertFalse(allow(relay, map, "key0", 2));
        } finally { relay.stop(); }
    }

    @Test public void probesShareTheHardCap() throws Exception {
        RelayServer relay = new RelayServer(ID, 0, "1.0.48");
        try {
            for (int i = 0; i < 50_000; i++) assertTrue(probe(relay, "ip" + i));
            assertFalse(probe(relay, "extra"));
            for (int i = 1; i < 12; i++) assertTrue(probe(relay, "ip0"));
            assertFalse(probe(relay, "ip0"));
        } finally { relay.stop(); }
    }

    @Test public void refusedRequestsCannotOverflowTheCounter() throws Exception {
        RelayServer relay = new RelayServer(ID, 0, "1.0.48");
        try {
            Map<String, Object> map = limits(relay, "mLimits");
            assertTrue(allow(relay, map, "key", 2));
            Object state = map.get("key");
            field(state.getClass(), "count").setInt(state, Integer.MAX_VALUE);
            assertFalse(allow(relay, map, "key", 2));
        } finally { relay.stop(); }
    }

    @Test public void maintenanceReclaimsExpiredReplicaSourcesButRetainsActiveWindows() throws Exception {
        RelayServer relay = new RelayServer(ID, 0, "1.0.48");
        try {
            Map<String, Object> map = limits(relay, "mReplicaInLimits");
            assertTrue(allow(relay, map, "old", 1));
            assertTrue(allow(relay, map, "active", 1));
            Object old = map.get("old");
            field(old.getClass(), "windowStart").setLong(old, System.currentTimeMillis() - 180_000);
            relay.maintain();
            assertFalse("replica sources participate in cleanup", map.containsKey("old"));
            assertFalse(allow(relay, map, "active", 1));
            assertTrue(allow(relay, map, "old", 1));
        } finally { relay.stop(); }
    }
    @Test public void fullTableRecoversOnMaintenanceAfterTheMinuteExpires() throws Exception {
        RelayServer relay = new RelayServer(ID, 0, "1.0.48");
        try {
            Map<String, Object> map = limits(relay, "mLimits");
            for (int i = 0; i < 50_000; i++) assertTrue(allow(relay, map, "key" + i, 1));
            assertFalse(allow(relay, map, "new", 1));
            for (Object state : map.values())
                field(state.getClass(), "windowStart").setLong(state, System.currentTimeMillis() - 61_000);
            // A future timestamp (wall-clock rollback) must not be reclaimed.
            Object retained = map.get("key0");
            field(retained.getClass(), "windowStart").setLong(retained, System.currentTimeMillis() + 60_000);
            relay.maintain();
            assertEquals(1, map.size());
            assertFalse(allow(relay, map, "key0", 1));
            assertTrue(allow(relay, map, "new", 1));
        } finally { relay.stop(); }
    }

    @Test public void concurrentNewKeysCannotOverfillTheLastSlot() throws Exception {
        RelayServer relay = new RelayServer(ID, 0, "1.0.48");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            Map<String, Object> map = limits(relay, "mLimits");
            for (int i = 0; i < 49_999; i++) assertTrue(allow(relay, map, "key" + i, 1));
            java.util.List<java.util.concurrent.Future<Boolean>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 64; i++) {
                String key = "new" + i;
                calls.add(pool.submit(() -> { start.await(); return allow(relay, map, key, 1); }));
            }
            start.countDown();
            int accepted = 0;
            for (java.util.concurrent.Future<Boolean> call : calls)
                if (call.get(10, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            assertEquals(50_000, map.size());
        } finally { start.countDown(); pool.shutdownNow(); relay.stop(); }
    }

}
