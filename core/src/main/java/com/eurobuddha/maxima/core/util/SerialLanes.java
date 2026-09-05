package com.eurobuddha.maxima.core.util;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A bounded pool where tasks that share a KEY run one after another, in order, while tasks
 * with different keys run in parallel. The account's chat/wallet/contact sends used to share
 * ONE thread: a send to an offline peer blocked on its socket timeouts and every payment,
 * receipt and balance refresh behind it waited. Keyed by peer (or "wallet", "mls", ...) the
 * per-peer order that chat relies on is kept and one dead peer holds up only its own lane.
 */
public final class SerialLanes {

    private final ExecutorService mPool;
    private final Map<String, ArrayDeque<Runnable>> mLanes = new ConcurrentHashMap<>();

    public SerialLanes(String zName, int zThreads) {
        mPool = new ThreadPoolExecutor(zThreads, zThreads, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, zName);
                    t.setDaemon(true);
                    return t;
                });
        ((ThreadPoolExecutor) mPool).allowCoreThreadTimeOut(true);
    }

    /** Run {@code zTask} after every earlier task queued under {@code zKey}. */
    public void execute(String zKey, Runnable zTask) {
        String key = zKey == null ? "" : zKey;
        boolean start;
        synchronized (mLanes) {
            ArrayDeque<Runnable> lane = mLanes.get(key);
            if (lane == null) {
                lane = new ArrayDeque<>();
                mLanes.put(key, lane);
                start = true;      // nothing running for this key: this task drives the lane
            } else {
                start = false;     // a runner owns the lane: it will pick this up
            }
            lane.add(zTask);
        }
        if (start) {
            mPool.execute(() -> drain(key));
        }
    }

    /** Backwards-compatible: no key = its own lane per call (fully parallel). */
    public void execute(Runnable zTask) {
        execute("#" + System.nanoTime() + "#" + Thread.currentThread().getId(), zTask);
    }

    private void drain(String zKey) {
        while (true) {
            Runnable next;
            synchronized (mLanes) {
                ArrayDeque<Runnable> lane = mLanes.get(zKey);
                next = lane == null ? null : lane.poll();
                if (next == null) {
                    mLanes.remove(zKey);   // lane empty: release it; the next execute() restarts
                    return;
                }
            }
            try {
                next.run();
            } catch (Throwable t) {
                // one bad task must not end the lane
            }
        }
    }

    public void shutdownNow() {
        mPool.shutdownNow();
    }
}
