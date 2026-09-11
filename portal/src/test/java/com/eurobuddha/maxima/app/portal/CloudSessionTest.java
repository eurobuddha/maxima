package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import android.content.SharedPreferences;
import com.eurobuddha.maxima.cloud.ParlonsRemote;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the actual Android session manager, with only Android storage and the wire mocked.
 * Latches follow HostPoolLifecycleTest: no sleeps, live credentials, relay or account required. */
public class CloudSessionTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private Context app;
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private ExecutorService worker;

    @Before public void setUp() throws Exception {
        app = mock(Context.class);
        when(app.getApplicationContext()).thenReturn(app);
        when(app.getFilesDir()).thenReturn(files.getRoot());
        SharedPreferences prefs = mock(SharedPreferences.class);
        when(app.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.getString(anyString(), anyString())).thenAnswer(i -> values.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> values.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(prefs.getAll()).thenAnswer(i -> new HashMap<>(values));
        when(prefs.edit()).thenAnswer(i -> {
            SharedPreferences.Editor edit = mock(SharedPreferences.Editor.class, RETURNS_SELF);
            Map<String, Object> writes = new HashMap<>();
            Set<String> removes = new HashSet<>();
            when(edit.putString(anyString(), anyString())).thenAnswer(a -> { writes.put(a.getArgument(0), a.getArgument(1)); return edit; });
            when(edit.putBoolean(anyString(), anyBoolean())).thenAnswer(a -> { writes.put(a.getArgument(0), a.getArgument(1)); return edit; });
            when(edit.remove(anyString())).thenAnswer(a -> { removes.add(a.getArgument(0)); return edit; });
            doAnswer(a -> { removes.forEach(values::remove); values.putAll(writes); return null; }).when(edit).apply();
            return edit;
        });
        Field identity = CloudSession.class.getDeclaredField("sDeviceId");
        identity.setAccessible(true);
        identity.set(null, mock(MaximaIdentity.class));
        CloudSession.setAccount(app, "MAX#account-A");
        worker = Executors.newSingleThreadExecutor();
    }

    @After public void tearDown() throws Exception {
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        CloudSession.io().submit(() -> {}).get(5, TimeUnit.SECONDS);
        CloudSession.reset(app);
    }

    @Test public void resetDuringFinalAddressReadCannotResurrectRemoteOrCache() throws Exception {
        retireDuringPublication(false);
    }

    @Test public void accountSwitchDuringPublicationCannotCacheOldAddressUnderNewAccount() throws Exception {
        retireDuringPublication(true);
    }

    private void retireDuringPublication(boolean switchAccount) throws Exception {
        CountDownLatch atPublication = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<ParlonsRemote> candidate = new AtomicReference<>();
        Future<Throwable> result = worker.submit(() -> {
            try (MockedConstruction<ParlonsRemote> ignored = mockConstruction(ParlonsRemote.class, (remote, context) -> {
                candidate.set(remote);
                when(remote.liveAddress()).thenAnswer(i -> {
                    atPublication.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return "live-A";
                });
            })) {
                try { ensure(); return null; } catch (Exception e) { return e; }
            }
        });
        try {
            assertTrue(atPublication.await(5, TimeUnit.SECONDS));
            // Must return while the worker is still blocked in the old connection.
            if (switchAccount) CloudSession.setAccount(app, "MAX#account-B");
            else CloudSession.reset(app);
            assertNull(CloudSession.remoteOrNull());
        } finally { release.countDown(); }
        assertTrue("retired connect must fail", result.get(5, TimeUnit.SECONDS) instanceof IllegalStateException);
        assertNull(CloudSession.remoteOrNull());
        assertFalse(values.keySet().stream().anyMatch(k -> k.startsWith("cache_")));
        verify(candidate.get()).close();
        if (switchAccount) assertEquals("MAX#account-B", CloudSession.account(app));
        // Retirement must not poison the replacement session.
        try (MockedConstruction<ParlonsRemote> constructed = mockConstruction(ParlonsRemote.class,
                (remote, context) -> when(remote.liveAddress()).thenReturn("live-new"))) {
            ParlonsRemote fresh = ensure();
            assertSame(fresh, CloudSession.remoteOrNull());
            verify(fresh).connect(eq(CloudSession.account(app)), eq(""));
            assertTrue(values.containsValue("live-new"));
        }
    }

    @Test public void bothLanesReuseThePublishedRemoteAndWarmCache() throws Exception {
        try (MockedConstruction<ParlonsRemote> constructed = mockConstruction(ParlonsRemote.class,
                (remote, context) -> when(remote.liveAddress()).thenReturn("live-A"))) {
            ParlonsRemote first = ensure();
            assertSame(first, ensure());
            assertSame(first, worker.submit(this::ensure).get(5, TimeUnit.SECONDS));
            assertEquals(1, constructed.constructed().size());
            verify(first).connect("MAX#account-A", "");
            assertEquals("live-A", CloudSession.cached(app, "livemx_" + Integer.toHexString("MAX#account-A".hashCode())));
        }
    }

    @Test public void pushSetupFailureClosesCandidateWithoutPublishing() throws Exception {
        try (MockedConstruction<ParlonsRemote> constructed = mockConstruction(ParlonsRemote.class, (remote, context) -> {
            when(remote.liveAddress()).thenReturn("live-A");
            doThrow(new IllegalStateException("push setup failed")).when(remote).setPushListener(any());
        })) {
            try { ensure(); fail("expected setup failure"); }
            catch (IllegalStateException expected) { assertEquals("push setup failed", expected.getMessage()); }
            assertNull(CloudSession.remoteOrNull());
            assertFalse(values.containsValue("live-A"));
            verify(constructed.constructed().get(0)).close();
        }
    }

    @Test public void queuedConnectCannotBecomeARequestAgainstAnotherAccount() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        worker.submit(() -> { blocked.countDown(); await(release); });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        CloudSession.Cb callback = mock(CloudSession.Cb.class);
        Method connectOn = CloudSession.class.getDeclaredMethod("connectOn", ExecutorService.class, Context.class, CloudSession.Cb.class);
        connectOn.setAccessible(true);
        try {
            connectOn.invoke(null, worker, app, callback);
            CloudSession.setAccount(app, "MAX#account-B");
        } finally { release.countDown(); }
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        verify(callback).err("connection was reset");
        verify(callback, never()).ok(any());
        assertNull(CloudSession.remoteOrNull());
    }

    @Test public void queuedReconnectCannotRetireAReplacementSession() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        CloudSession.io().submit(() -> { blocked.countDown(); await(release); });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        try (MockedConstruction<ParlonsRemote> constructed = mockConstruction(ParlonsRemote.class,
                (remote, context) -> when(remote.liveAddress()).thenReturn("live-B"))) {
            try {
                CloudSession.reconnect(app, "test old network callback");
                CloudSession.setAccount(app, "MAX#account-B");
                ParlonsRemote replacement = ensure();
                release.countDown();
                CloudSession.io().submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertSame(replacement, CloudSession.remoteOrNull());
                verify(replacement, never()).close();
            } finally { release.countDown(); }
        }
    }

    @Test public void oldHeartbeatCannotWriteNewAccountsWarmAddress() throws Exception {
        try (MockedConstruction<ParlonsRemote> constructed = mockConstruction(ParlonsRemote.class,
                (remote, context) -> when(remote.liveAddress()).thenReturn("live-A"))) {
            ParlonsRemote old = ensure();
            CloudSession.setAccount(app, "MAX#account-B");
            CloudSession.noteLiveAddress(app, old, "late-live-A");
            assertFalse(values.containsValue("late-live-A"));
            assertNull(CloudSession.remoteOrNull());
        }
    }

    @Test public void delayedPairingReplyCannotPairANewAccount() throws Exception {
        try (MockedConstruction<ParlonsRemote> constructed = mockConstruction(ParlonsRemote.class,
                (remote, context) -> when(remote.liveAddress()).thenReturn("live"))) {
            ParlonsRemote old = ensure();
            assertTrue(CloudSession.setPaired(app, old));
            assertTrue(CloudSession.isPaired(app));
            CloudSession.setAccount(app, "MAX#account-B");
            assertFalse(CloudSession.setPaired(app, old));
            assertFalse(CloudSession.isPaired(app));
            ParlonsRemote fresh = ensure();
            assertTrue(CloudSession.setPaired(app, fresh));
            assertTrue(CloudSession.isPaired(app));
        }
    }

    private ParlonsRemote ensure() throws Exception {
        Field generation = CloudSession.class.getDeclaredField("sGen");
        generation.setAccessible(true);
        Method method = CloudSession.class.getDeclaredMethod("ensureRemote", Context.class, int.class);
        method.setAccessible(true);
        try { return (ParlonsRemote) method.invoke(null, app, generation.getInt(null)); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
