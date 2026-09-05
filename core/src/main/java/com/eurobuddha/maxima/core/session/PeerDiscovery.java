package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.Probe;
import com.eurobuddha.maxima.core.store.Store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Relay discovery, classic Minima's way — a port of {@code P2PPeersChecker} plus the
 * discovery half of {@code P2PManager} (the same code every classic node runs to find
 * peers "out in the wild" with nothing but a bootstrap list).
 *
 * The rules, each with its classic origin:
 * <ul>
 *   <li><b>Verify before you adopt.</b> A peer learned from a greeting goes on the
 *       UNVERIFIED list and is dialled on a fresh socket; only a live Maxima greeting
 *       promotes it to VERIFIED ({@code PEERS_CHECKPEERS}). We are never told who is
 *       alive — we check.</li>
 *   <li><b>A bounded list, kept fresh.</b> At most {@link #MAX_VERIFIED_PEERS} verified
 *       peers. Once full, a newcomer is admitted with a 10% chance and a RANDOM verified
 *       peer makes room for it ({@code checkUnverifiedPeer} + {@code removeRandomItem}),
 *       so the list keeps turning over instead of freezing on whoever came first.</li>
 *   <li><b>Failures are forgiven once.</b> A verified peer that stops answering is moved
 *       back to unverified and rechecked in 30 minutes; if it is still down it is dropped.
 *       A never-verified peer that fails is simply forgotten.</li>
 *   <li><b>Everything is rechecked.</b> Every 6 hours the whole verified list is dialled
 *       again ({@code PEERS_LOOP}); a check made while we have no connection at all is
 *       deferred 60 seconds (no network — not the peer's fault).</li>
 *   <li><b>Persisted.</b> The list is saved every 10 minutes ({@code P2P_SAVE_DATA}) and on
 *       shutdown — but only when it is still at least half the size it was loaded at, so an
 *       outage that empties the list cannot overwrite a good one ({@code updateP2PPeersList}).</li>
 *   <li><b>Connect at RANDOM.</b> Classic never ranks peers: it connects to a random known
 *       peer ({@code P2P_RANDOM_CONNECT}, {@code processLoop}), which is what spreads a
 *       growing population evenly over a growing fleet. {@link HostPool#fill} does the same.</li>
 *   <li><b>Three strikes.</b> A peer that cannot be connected to three times running is
 *       removed ({@code NIOManager.RECONNECT_ATTEMPTS} → {@code P2P_NOCONNECT}).</li>
 * </ul>
 *
 * What a peer list can hold is only public {@code host:port} strings; a hostile relay can
 * therefore waste at most one verification dial per bogus entry, and can never make a
 * client attach anywhere that does not itself answer as a Maxima relay.
 */
public final class PeerDiscovery {

    /** Classic {@code P2PPeersChecker.MAX_VERIFIED_PEERS}. */
    public static final int MAX_VERIFIED_PEERS = 250;
    /** Classic: a failed VERIFIED peer is rechecked after 30 minutes before it is dropped. */
    public static final long RECHECK_FAILED_MS = 30L * 60_000;
    /** Classic: a check attempted with no network is retried in 60 seconds. */
    public static final long RECHECK_OFFLINE_MS = 60_000;
    /** Classic {@code PEERS_LOOP_TIMER}: the whole verified list is rechecked every 6 hours. */
    public static final long FULL_RECHECK_MS = 6L * 3_600_000;
    /** Classic {@code P2PParams.SAVE_DATA_DELAY}: the list is saved every 10 minutes. */
    public static final long SAVE_MS = 10L * 60_000;
    /** Classic {@code NIOManager.RECONNECT_ATTEMPTS}. */
    public static final int NOCONNECT_ATTEMPTS = 3;

    /** Verification dial budget (classic uses its normal greeting exchange). */
    private static final int CONNECT_MS = 4000;
    private static final int READ_MS = 2000;

    /** Store collection: host:port -> epoch ms it was last verified. */
    static final String C_PEERS = "peers";

    private final String mProtocol;
    private final Random mRand = new Random();

    /** verified host:port -> last verified ms (classic {@code verifiedPeers} / {@code knownPeers}). */
    private final Map<String, Long> mVerified = new ConcurrentHashMap<>();
    /** unverified host:port -> true while a check is owed (classic {@code unverifiedPeers}). */
    private final Map<String, Boolean> mUnverified = new ConcurrentHashMap<>();
    /** host:port -> epoch ms a deferred recheck falls due (the 30-min / 60-s timers). */
    private final Map<String, Long> mDue = new ConcurrentHashMap<>();
    /** One queued check. */
    private static final class Check {
        final String hostPort;
        final boolean force;

        Check(String zHostPort, boolean zForce) {
            hostPort = zHostPort;
            force = zForce;
        }
    }

    /** Checks waiting for the single checker thread (classic's PEERS_CHECKER processor). */
    private final LinkedBlockingQueue<Check> mQueue = new LinkedBlockingQueue<>(1024);
    /**
     * Negative cache: a never-verified peer that failed its check -> epoch ms before which it
     * is not considered again. Without it, one relay listing fifty dead addresses would make
     * every attached phone dial all fifty again on every heartbeat (the greeting is re-read
     * each tick and a failed unverified peer is otherwise simply forgotten) - about five
     * minutes of socket work per minute, on a phone, for as long as it stays attached.
     * Held for classic's own recheck interval. Bounded; swept on the tick.
     */
    private final Map<String, Long> mFailedUntil = new ConcurrentHashMap<>();
    private static final int MAX_FAILED = 4096;

    private volatile Store mStore = Store.MEMORY_ONLY;
    private volatile int mLoadedCount;
    private volatile boolean mDirty;
    private volatile long mLastSave = System.currentTimeMillis();
    private volatile long mLastFullRecheck = System.currentTimeMillis();
    private volatile boolean mRunning = true;
    private volatile Thread mChecker;
    /** Classic {@code -allowallip}: accept private/loopback peers (tests, LAN fleets). */
    private volatile boolean mAllowAllIp;
    /** Our own endpoint(s) — never adopted as a peer (classic removes myMinimaAddress). */
    private final java.util.Set<String> mSelf = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Who to tell when the verified list changes (the pool adopts / forgets candidates). */
    public interface Listener {
        void onVerified(String zHostPort);
        void onRemoved(String zHostPort);
    }

    private volatile Listener mListener;
    /** True while at least one relay is attached — a check with no network is deferred. */
    private volatile java.util.function.BooleanSupplier mConnected = () -> true;

    public PeerDiscovery(String zProtocol) {
        mProtocol = zProtocol;
    }

    public void setListener(Listener zListener) {
        mListener = zListener;
    }

    public void setConnectedSupplier(java.util.function.BooleanSupplier zConnected) {
        mConnected = zConnected == null ? () -> true : zConnected;
    }

    public void setAllowAllIp(boolean zAllow) {
        mAllowAllIp = zAllow;
    }

    /** An endpoint of ours: never a peer of ours. */
    public void addSelf(String zHostPort) {
        if (zHostPort != null && !zHostPort.isEmpty()) {
            mSelf.add(zHostPort);
            mVerified.remove(zHostPort);
            mUnverified.remove(zHostPort);
        }
    }

    // ---------------------------------------------------------------
    // persistence (classic P2PDB)
    // ---------------------------------------------------------------

    /**
     * Attach durable storage and reload the saved list; every saved peer is re-queued for a
     * check, as classic re-checks its saved peers at startup. NOT forced, unlike classic: a
     * forced check runs with no network, so a phone booting in airplane mode would fail all
     * 250 saved peers, demote every one to a 30-minute recheck and - still offline then -
     * forget the lot, leaving only the bootstrap floor until the next process start. Unforced,
     * the checks defer 60 s at a time until the first relay attaches, then run as before.
     */
    public void setStore(Store zStore) {
        mStore = zStore == null ? Store.MEMORY_ONLY : zStore;
        int n = 0;
        for (Map.Entry<String, String> e : mStore.all(C_PEERS).entrySet()) {
            String hp = e.getKey();
            if (!valid(hp) || mSelf.contains(hp)) {
                continue;
            }
            long seen;
            try {
                seen = Long.parseLong(e.getValue());
            } catch (NumberFormatException ex) {
                seen = 0;
            }
            mVerified.put(hp, seen);
            n++;
            Listener l = mListener;
            if (l != null) {
                l.onVerified(hp);
            }
            queue(hp, false);
        }
        mLoadedCount = n;
    }

    /** Classic {@code updateP2PPeersList}: save only when the list is still at least half
     *  the size it was loaded at — a transient outage must not persist an emptied list. */
    /** Stored timestamps are coarse (hours), so a re-verification does not rewrite the file:
     *  a keyed FileStore put rewrites and fsyncs the whole collection, and 250 of them after
     *  the 6-hour recheck was 250 rewrites for no new information. */
    private static final long SAVE_GRAIN_MS = 3_600_000L;

    public void save() {
        int size = mVerified.size();
        if (size > 0 && size >= mLoadedCount / 2) {
            Map<String, String> old = mStore.all(C_PEERS);
            for (String k : old.keySet()) {
                if (!mVerified.containsKey(k)) {
                    mStore.remove(C_PEERS, k);
                }
            }
            for (Map.Entry<String, Long> e : mVerified.entrySet()) {
                String v = Long.toString((e.getValue() / SAVE_GRAIN_MS) * SAVE_GRAIN_MS);
                if (!v.equals(old.get(e.getKey()))) {
                    mStore.put(C_PEERS, e.getKey(), v);   // only what actually changed
                }
            }
            mStore.flush();
        }
        mDirty = false;
        mLastSave = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------
    // intake (classic PEERS_ADDPEERS)
    // ---------------------------------------------------------------

    /**
     * A greeting arrived from {@code zFromHostPort}: consider every peer it lists, and the
     * sender's own host claim (classic adds {@code client.getHost():greeting.myMinimaPort}).
     */
    public void onGreeting(String zFromHostPort, Greeting zGreeting) {
        if (zGreeting == null) {
            return;
        }
        String extra = zGreeting.getExtraData();
        for (String peer : Greeting.peersOf(extra)) {
            addPeer(peer);
        }
        String host = Greeting.hostOf(extra);
        int port = Greeting.portOf(extra);
        if (!host.isEmpty() && port > 0) {
            addPeer(host + ":" + port);
        }
        if (zFromHostPort != null && !zFromHostPort.isEmpty()) {
            // The relay we are actually talking to is a peer by definition: keep it fresh.
            if (mVerified.containsKey(zFromHostPort)) {
                mVerified.put(zFromHostPort, System.currentTimeMillis());
            } else {
                addPeer(zFromHostPort);
            }
        }
    }

    /**
     * Classic {@code checkUnverifiedPeer}: unknown → unverified (bounded) → queued for a
     * check. When the verified list is full, only one newcomer in ten is even considered.
     */
    public void addPeer(String zHostPort) {
        if (!valid(zHostPort) || mSelf.contains(zHostPort)) {
            return;
        }
        if (!mAllowAllIp && !com.eurobuddha.maxima.core.portmap.PortMapper.isPublic(hostOf(zHostPort))) {
            return;   // classic: never adopt a localhost / private address from a peer
        }
        if (mVerified.containsKey(zHostPort) || mUnverified.containsKey(zHostPort)) {
            return;
        }
        Long until = mFailedUntil.get(zHostPort);
        if (until != null) {
            if (System.currentTimeMillis() < until) {
                return;   // failed recently: not again until classic's recheck interval passes
            }
            mFailedUntil.remove(zHostPort);
        }
        if (mUnverified.size() >= MAX_VERIFIED_PEERS) {
            return;   // classic: MAX reached
        }
        if (mVerified.size() >= MAX_VERIFIED_PEERS && mRand.nextInt(100) < 90) {
            return;   // classic: full list — 10% chance a newcomer gets a look
        }
        mUnverified.put(zHostPort, Boolean.TRUE);
        queue(zHostPort, false);
    }

    /** Classic {@code P2P_NOCONNECT}: after {@link #NOCONNECT_ATTEMPTS} failed connects the
     *  peer is removed from the known list. */
    public void noConnect(String zHostPort) {
        boolean was = mVerified.remove(zHostPort) != null;
        mUnverified.remove(zHostPort);
        mDue.remove(zHostPort);
        if (was) {
            mDirty = true;
            Listener l = mListener;
            if (l != null) {
                l.onRemoved(zHostPort);
            }
        }
    }

    // ---------------------------------------------------------------
    // the loop (classic PEERS_LOOP / P2P_SAVE_DATA / deferred checks)
    // ---------------------------------------------------------------

    /** Drive from the node's maintenance heartbeat. Cheap: it only schedules. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (mFailedUntil.size() > MAX_FAILED) {
            mFailedUntil.entrySet().removeIf(e -> now >= e.getValue());
            if (mFailedUntil.size() > MAX_FAILED) {
                mFailedUntil.clear();   // still flooded: forgetting is cheaper than growing
            }
        }
        if (!mDue.isEmpty()) {
            for (Map.Entry<String, Long> e : new ArrayList<>(mDue.entrySet())) {
                if (now >= e.getValue()) {
                    mDue.remove(e.getKey());
                    queue(e.getKey(), false);
                }
            }
        }
        if (now - mLastFullRecheck > FULL_RECHECK_MS) {
            mLastFullRecheck = now;
            for (String hp : mVerified.keySet()) {
                queue(hp, false);
            }
        }
        if (mDirty && now - mLastSave > SAVE_MS) {
            save();
        }
    }

    public void stop() {
        mRunning = false;
        Thread t = mChecker;
        if (t != null) {
            t.interrupt();
        }
        if (mDirty) {
            save();
        }
    }

    // ---------------------------------------------------------------
    // views
    // ---------------------------------------------------------------

    /** The verified peers (classic {@code knownPeers}), a copy. */
    public List<String> verified() {
        return new ArrayList<>(mVerified.keySet());
    }

    public int verifiedCount() {
        return mVerified.size();
    }

    public int unverifiedCount() {
        return mUnverified.size();
    }

    /** Classic {@code P2P_RANDOM_CONNECT}: one verified peer at random, or null. */
    public String randomPeer() {
        List<String> all = verified();
        return all.isEmpty() ? null : all.get(mRand.nextInt(all.size()));
    }

    // ---------------------------------------------------------------
    // the checker (classic PEERS_CHECKPEERS on its own thread)
    // ---------------------------------------------------------------

    private void queue(String zHostPort, boolean zForce) {
        ensureChecker();
        mQueue.offer(new Check(zHostPort, zForce));
    }

    private synchronized void ensureChecker() {
        if (mChecker != null && mChecker.isAlive()) {
            return;
        }
        Thread t = new Thread(this::checkLoop, "maxima-peers-check");
        t.setDaemon(true);
        mChecker = t;
        t.start();
    }

    private void checkLoop() {
        while (mRunning) {
            Check item;
            try {
                item = mQueue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                check(item.hostPort, item.force);
            } catch (Exception e) {
                // one bad peer must never stop the checker
            }
        }
    }

    /** Package-private so a test can drive a check synchronously. */
    void check(String zHostPort, boolean zForce) {
        if (mSelf.contains(zHostPort)) {
            return;
        }
        if (!zForce && !mConnected.getAsBoolean()) {
            // Classic: not connected to the internet — try again in 60 seconds.
            mDue.put(zHostPort, System.currentTimeMillis() + RECHECK_OFFLINE_MS);
            return;
        }
        int c = zHostPort.lastIndexOf(':');
        String host = zHostPort.substring(0, c);
        int port = Integer.parseInt(zHostPort.substring(c + 1));
        Greeting g = Probe.dialGreeting(host, port, CONNECT_MS, READ_MS, mProtocol);
        // Classic checks the greeting's version/chain; ours must answer as one of OUR relays
        // (the "welcome":"Maxima" extra data) — a stock node greets too but relays nothing
        // for us (no mailbox, directory or blob service), so it is not a valid peer.
        boolean valid = g != null && g.getExtraData() != null
                && g.getExtraData().contains("\"welcome\":\"Maxima\"");
        if (valid) {
            mUnverified.remove(zHostPort);
            mDue.remove(zHostPort);
            boolean fresh = !mVerified.containsKey(zHostPort);
            if (fresh && mVerified.size() >= MAX_VERIFIED_PEERS) {
                String victim = removeRandom();
                if (victim != null) {
                    Listener l = mListener;
                    if (l != null) {
                        l.onRemoved(victim);
                    }
                }
            }
            mVerified.put(zHostPort, System.currentTimeMillis());
            mDirty = true;
            Listener l = mListener;
            if (l != null) {
                l.onVerified(zHostPort);
            }
            // A verified relay's greeting lists ITS verified peers: the list grows itself.
            for (String peer : Greeting.peersOf(g.getExtraData())) {
                addPeer(peer);
            }
        } else {
            if (mVerified.remove(zHostPort) != null) {
                // Classic: a verified peer that went quiet gets ONE more look in 30 minutes.
                mUnverified.put(zHostPort, Boolean.TRUE);
                mDue.put(zHostPort, System.currentTimeMillis() + RECHECK_FAILED_MS);
                mDirty = true;
                Listener l = mListener;
                if (l != null) {
                    l.onRemoved(zHostPort);
                }
            } else {
                // Never verified and does not answer: forgotten - and not re-tried for the
                // recheck interval however many greetings keep listing it (negative cache).
                mUnverified.remove(zHostPort);
                mDue.remove(zHostPort);
                mFailedUntil.put(zHostPort, System.currentTimeMillis() + RECHECK_FAILED_MS);
            }
        }
    }

    /** Classic {@code removeRandomItem} on the verified set. */
    private String removeRandom() {
        List<String> all = verified();
        if (all.isEmpty()) {
            return null;
        }
        String victim = all.get(mRand.nextInt(all.size()));
        mVerified.remove(victim);
        return victim;
    }

    private static boolean valid(String zHostPort) {
        if (zHostPort == null) {
            return false;
        }
        int c = zHostPort.lastIndexOf(':');
        if (c <= 0 || c == zHostPort.length() - 1 || zHostPort.length() > 64) {
            return false;
        }
        // Classic refuses IPv6 peers (more than one colon).
        if (zHostPort.indexOf(':') != c) {
            return false;
        }
        try {
            int port = Integer.parseInt(zHostPort.substring(c + 1));
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String hostOf(String zHostPort) {
        return zHostPort.substring(0, zHostPort.lastIndexOf(':'));
    }
}
