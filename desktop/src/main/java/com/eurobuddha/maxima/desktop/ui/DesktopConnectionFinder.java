package com.eurobuddha.maxima.desktop.ui;

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
 * The desktop's "Auto-connect" engine — the counterpart of the phone's
 * {@code ConnectionFinder}. Gathers candidate relays (currently-attached ∪ the
 * configured {@link DesktopRelayStore} ∪ the {@link Bootstrap} floor), probes them
 * concurrently with {@link Probe#dial} (a real greeting, not just an open port), and
 * attaches the reachable ones LIVE through the pool — no reconnect. Reports plain
 * steps on the Swing thread; one run at a time; onDone always fires.
 */
public final class DesktopConnectionFinder {

    private static final String VERSION = "1.0.48";
    private static final int PROBE_CONNECT_MS = 4000;
    private static final int PROBE_READ_MS = 2000;
    private static final int ATTACH_MS = 15000;
    private static final int MAX_ATTACH = 4;
    private static final int MAX_PROBE_THREADS = 6;

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    public interface Listener {
        void onStep(String plain);
        void onDone(int connectedHosts, boolean anyReachable);
    }

    private DesktopConnectionFinder() { }

    public static boolean isRunning() { return BUSY.get(); }

    public static void run(final DesktopNode dnode, final DesktopRelayStore store, final Listener cb) {
        if (!BUSY.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            final AtomicBoolean done = new AtomicBoolean(false);
            try {
                MaximaNode node = dnode.node();
                if (node == null) { finish(cb, done, 0, false); return; }
                step(cb, "Checking your network…");
                LinkedHashSet<String> cands = new LinkedHashSet<>();
                cands.addAll(node.pool().activeHosts());
                cands.addAll(store.get());
                cands.addAll(Bootstrap.RELAYS);
                step(cb, "Finding the best relays…");
                List<String> reachable = probe(new ArrayList<>(cands));
                step(cb, "Connecting…");
                int attached = 0;
                for (String hp : reachable) {
                    store.add(hp);
                    node.pool().addCandidate(hp);
                    try {
                        if (node.pool().attachOne(hp, ATTACH_MS)) {
                            attached++;
                        }
                    } catch (Exception ignored) { }
                    if (attached >= MAX_ATTACH) break;
                }
                DesktopEventLog.add("auto-connect: " + reachable.size() + " reachable, "
                        + node.pool().activeCount() + " attached");
                finish(cb, done, node.pool().activeCount(), !reachable.isEmpty());
            } catch (Throwable t) {
                int total = 0;
                try { total = dnode.connectedCount(); } catch (Throwable ignored) { }
                finish(cb, done, total, false);
            } finally {
                BUSY.set(false);
            }
        }, "desktop-connection-finder").start();
    }

    private static List<String> probe(List<String> hosts) {
        int threads = Math.min(MAX_PROBE_THREADS, Math.max(1, hosts.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, daemon());
        List<Future<String>> futs = new ArrayList<>();
        for (final String hp : hosts) {
            futs.add(pool.submit(() -> {
                int c = hp.lastIndexOf(':');
                if (c <= 0 || c == hp.length() - 1) return null;
                String host = hp.substring(0, c);
                int port;
                try { port = Integer.parseInt(hp.substring(c + 1).trim()); }
                catch (NumberFormatException e) { return null; }
                return Probe.dial(host, port, PROBE_CONNECT_MS, PROBE_READ_MS, VERSION) ? hp : null;
            }));
        }
        List<String> ok = new ArrayList<>();
        for (Future<String> f : futs) {
            try {
                String r = f.get(8, TimeUnit.SECONDS);
                if (r != null) ok.add(r);
            } catch (Exception ignored) { }
        }
        pool.shutdownNow();
        return ok;
    }

    private static void step(Listener cb, String s) {
        javax.swing.SwingUtilities.invokeLater(() -> cb.onStep(s));
    }

    private static void finish(Listener cb, AtomicBoolean done, int total, boolean any) {
        if (done.compareAndSet(false, true)) {
            javax.swing.SwingUtilities.invokeLater(() -> cb.onDone(total, any));
        }
    }

    private static ThreadFactory daemon() {
        return r -> { Thread t = new Thread(r, "df-probe"); t.setDaemon(true); return t; };
    }
}
