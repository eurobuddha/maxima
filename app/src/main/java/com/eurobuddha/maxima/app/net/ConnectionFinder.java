package com.eurobuddha.maxima.app.net;

import android.app.Activity;
import android.content.Context;

import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.app.RelayStore;
import com.eurobuddha.maxima.app.SwarmStore;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.net.Probe;
import com.eurobuddha.maxima.core.session.Bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The "Auto-connect" engine: find a Maxima relay this phone can actually reach
 * and attach to it — LIVE, with no service restart.
 *
 * Draws candidates from the union of what the app already knows (currently-
 * attached hosts, the user's {@code RelayStore}, remembered {@code SwarmStore}
 * relays, and the {@code Bootstrap} floor), probes them concurrently with
 * {@link Probe#dial} (a TCP connect + Maxima greeting, so "reachable relay" not
 * merely "open port"), then attaches the reachable ones through the live pool
 * ({@code addCandidate} + {@code attachOne}). Reports plain-language steps on the
 * UI thread. One run at a time.
 */
public final class ConnectionFinder {

    /** Matches the version string MaximaService hands the node. */
    private static final String VERSION = "1.0.48";
    private static final int PROBE_CONNECT_MS = 4000;
    private static final int PROBE_READ_MS = 2000;
    private static final int ATTACH_MS = 15000;
    private static final int MAX_ATTACH = 4;
    private static final int MAX_PROBE_THREADS = 6;

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    public interface Listener {
        /** A plain-language progress line, on the UI thread. */
        void onStep(String zPlain);

        /** Done: how many hosts are now connected, and whether any relay answered. */
        void onDone(int zConnectedHosts, boolean zAnyReachable);
    }

    private ConnectionFinder() {
    }

    public static boolean isRunning() {
        return BUSY.get();
    }

    /** Run once. No-op if a run is already in progress. */
    public static void run(final Context zCtx, final Activity zUi, final Listener zCb) {
        if (!BUSY.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            // onDone MUST fire exactly once on every path (including a thrown
            // exception), or the UI's Auto-connect button stays disabled forever.
            final AtomicBoolean done = new AtomicBoolean(false);
            try {
                MaximaNode node = MaximaService.node();
                if (node == null) {
                    finish(zUi, zCb, done, 0, false);
                    return;
                }
                post(zUi, () -> zCb.onStep("Checking your network…"));

                LinkedHashSet<String> cands = new LinkedHashSet<>();
                cands.addAll(node.pool().activeHosts());
                cands.addAll(RelayStore.get(zCtx));
                cands.addAll(SwarmStore.recent(zCtx));
                // (the compiled-in list is already inside RelayStore.get() when it is switched on)

                post(zUi, () -> zCb.onStep("Finding the best relays…"));
                List<String> reachable = probe(new ArrayList<>(cands));

                post(zUi, () -> zCb.onStep("Connecting…"));
                int attached = 0;
                for (String hp : reachable) {
                    RelayStore.add(zCtx, hp);
                    node.pool().addCandidate(hp);
                    try {
                        if (node.pool().attachOne(hp, ATTACH_MS)) {
                            SwarmStore.seen(zCtx, hp);
                            attached++;
                        }
                    } catch (Exception ignored) {
                    }
                    if (attached >= MAX_ATTACH) {
                        break;
                    }
                }
                finish(zUi, zCb, done, node.pool().activeCount(), !reachable.isEmpty());
            } catch (Throwable t) {
                // Best-effort: report whatever is currently attached so the UI recovers.
                int total = 0;
                try {
                    MaximaNode n = MaximaService.node();
                    if (n != null) {
                        total = n.pool().activeCount();
                    }
                } catch (Throwable ignored) {
                }
                finish(zUi, zCb, done, total, false);
            } finally {
                BUSY.set(false);
            }
        }, "connection-finder").start();
    }

    /** Probe candidates concurrently; return the reachable ones. */
    private static List<String> probe(List<String> zHosts) {
        int threads = Math.min(MAX_PROBE_THREADS, Math.max(1, zHosts.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, daemon());
        List<Future<String>> futs = new ArrayList<>();
        for (final String hp : zHosts) {
            futs.add(pool.submit(() -> {
                int c = hp.lastIndexOf(':');
                if (c <= 0 || c == hp.length() - 1) {
                    return null;
                }
                String host = hp.substring(0, c);
                int port;
                try {
                    port = Integer.parseInt(hp.substring(c + 1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                return Probe.dial(host, port, PROBE_CONNECT_MS, PROBE_READ_MS, VERSION) ? hp : null;
            }));
        }
        List<String> ok = new ArrayList<>();
        for (Future<String> f : futs) {
            try {
                String r = f.get(8, TimeUnit.SECONDS);
                if (r != null) {
                    ok.add(r);
                }
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        return ok;
    }

    /** Post onDone at most once, guarded by {@code zDone}. */
    private static void finish(Activity zUi, Listener zCb, AtomicBoolean zDone,
                               int zTotal, boolean zAny) {
        if (zDone.compareAndSet(false, true)) {
            post(zUi, () -> zCb.onDone(zTotal, zAny));
        }
    }

    private static void post(Activity zUi, Runnable zR) {
        if (zUi != null) {
            zUi.runOnUiThread(zR);
        }
    }

    private static ThreadFactory daemon() {
        return r -> {
            Thread t = new Thread(r, "cf-probe");
            t.setDaemon(true);
            return t;
        };
    }
}
