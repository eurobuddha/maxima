package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.HostConnection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MULTI-HOMING - attach to several relays at once and publish all of them.
 *
 * Classic publishes ONE address from ONE randomly chosen connected host, and
 * purges a host after 7 days. That is fine for a server and wrong for a phone,
 * where relays come and go constantly.
 *
 * Here we hold N attachments, publish every resulting address in the contact
 * metadata, and let senders race or fail over. It costs nothing on the wire -
 * it is just more contact metadata - and it structurally removes dependence on
 * any single relay operator, which is the decentralisation goal.
 *
 * Host selection is CHURN-NATIVE by design: relays are scored on observed
 * uptime, expected to vanish, and never depended on individually.
 */
public final class HostPool {

    /** Attach to this many relays by default. */
    public static final int DEFAULT_TARGET = 3;

    /**
     * How long a host proven dead (repeated pump failures → {@link #detachDead})
     * is barred from re-adoption. Without this, {@code fill}/{@code reconcile}
     * re-attach a just-dropped host on the very next cycle, so a broken endpoint
     * (a classic Minima node that greets but holds no mailbox, a stale
     * direct-forward) flaps in and out every heartbeat — and any message routed
     * to it strands until a 60s resend.
     *
     * Kept SHORT (90s, ~1.5 heartbeats) on purpose: on a weak Wi-Fi, pump reads
     * time out against perfectly healthy relays, and a long cooldown benches a
     * GOOD relay — shrinking the receiver's live set and delaying mailbox drains
     * (observed live: two fleet relays cooled for 5 min on a one-bar link while
     * messages stranded). 90s still stops the every-tick flap on a truly dead
     * host, at the cost of one cheap retry connect per window.
     */
    public static final long COOLDOWN_MS = 90 * 1000;

    /** Scored record of one relay we know about. */
    public static final class HostRecord {
        public final String hostPort;
        public volatile long attachedAt;
        public volatile long lastSeen;
        /** Successful attachments. */
        public volatile int successes;
        /** Failed attach attempts or drops. */
        public volatile int failures;
        public volatile long totalUptimeMs;

        HostRecord(String zHostPort) {
            hostPort = zHostPort;
        }

        /**
         * Higher is better. Rewards observed uptime, penalises failures, and
         * deliberately does NOT trust a relay just because it is currently up -
         * a relay that has never dropped but has only been seen for a minute
         * should not outrank one with hours behind it.
         */
        public double score() {
            double attempts = successes + failures;
            double reliability = attempts == 0 ? 0.5 : (successes / attempts);
            double uptimeHours = totalUptimeMs / 3_600_000.0;
            // Diminishing returns on uptime so one long-lived relay cannot
            // dominate the pool forever.
            return reliability * (1.0 + Math.log1p(uptimeHours));
        }

        @Override
        public String toString() {
            return String.format("%s score=%.2f up=%.1fh ok=%d fail=%d",
                    hostPort, score(), totalUptimeMs / 3_600_000.0, successes, failures);
        }
    }

    private final MaximaIdentity mIdentity;
    private final String mVersion;
    private final int mTarget;

    private final Map<String, HostRecord> mKnown = new ConcurrentHashMap<>();
    private final Map<String, HostConnection> mActive = new ConcurrentHashMap<>();
    /** hostPort -> epoch-ms until which a proven-dead host is barred from re-adoption. */
    private final Map<String, Long> mCooldown = new ConcurrentHashMap<>();

    /** Delivered every inbound message from every attached host's reader thread.
     *  Set ONCE (by the node) before the first attach. */
    private volatile HostConnection.Sink mSink;

    public void setSink(HostConnection.Sink zSink) {
        mSink = zSink;
    }

    /** Our proven public endpoint to claim in greetings, or null (see HostConnection). */
    private volatile String mAdvertisedEndpoint;

    /** Claim (or stop claiming, with null) a proven endpoint on all FUTURE attaches. */
    public void setAdvertisedEndpoint(String zHostPort) {
        mAdvertisedEndpoint = (zHostPort == null || zHostPort.isEmpty()) ? null : zHostPort;
        // Existing connections greet once at attach; new claims travel on the
        // next (re)attach, which the reconcile cycle produces naturally.
        for (HostConnection c : mActive.values()) {
            c.setAdvertisedEndpoint(mAdvertisedEndpoint);
        }
    }

    public HostPool(MaximaIdentity zIdentity, String zVersion, int zTarget) {
        mIdentity = zIdentity;
        mVersion = zVersion;
        mTarget = zTarget;
    }

    public HostPool(MaximaIdentity zIdentity, String zVersion) {
        this(zIdentity, zVersion, DEFAULT_TARGET);
    }

    /** Tell the pool a relay exists. Does not connect. */
    public void addCandidate(String zHostPort) {
        mKnown.computeIfAbsent(zHostPort, HostRecord::new);
    }

    public void addCandidates(List<String> zHostPorts) {
        for (String h : zHostPorts) {
            addCandidate(h);
        }
    }

    public List<HostRecord> knownByScore() {
        List<HostRecord> all = new ArrayList<>(mKnown.values());
        all.sort(Comparator.comparingDouble(HostRecord::score).reversed());
        return all;
    }

    public int activeCount() {
        return mActive.size();
    }

    public List<String> activeHosts() {
        return new ArrayList<>(mActive.keySet());
    }

    /**
     * EVERY address we can currently be reached on.
     * This is what goes into contact metadata, and what an RPC request lists as
     * its reply-to set.
     */
    public List<String> contactAddresses() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, HostConnection> e : mActive.entrySet()) {
            if (e.getValue().isAttached()) {
                out.add(e.getValue().contactAddress());
            }
        }
        Collections.sort(out);
        return out;
    }

    public HostConnection connection(String zHostPort) {
        return mActive.get(zHostPort);
    }

    /**
     * Attach to one relay and record the outcome.
     *
     * @return true if it attached
     */
    public boolean attachOne(String zHostPort, int zTimeoutMs) {
        HostRecord rec = mKnown.computeIfAbsent(zHostPort, HostRecord::new);
        if (mActive.containsKey(zHostPort)) {
            return true;
        }
        // Bar a proven-dead host until its cooldown expires, so we do not
        // re-adopt a broken endpoint on the next tick (see COOLDOWN_MS).
        Long until = mCooldown.get(zHostPort);
        if (until != null) {
            if (System.currentTimeMillis() < until) {
                return false;
            }
            mCooldown.remove(zHostPort);
        }
        HostConnection conn = new HostConnection(
                zHostPort.substring(0, zHostPort.lastIndexOf(':')),
                Integer.parseInt(zHostPort.substring(zHostPort.lastIndexOf(':') + 1)),
                mIdentity.hostKey(zHostPort),
                mVersion);
        conn.setAdvertisedEndpoint(mAdvertisedEndpoint);
        try {
            conn.attach(zTimeoutMs);
            mActive.put(zHostPort, conn);
            rec.successes++;
            rec.attachedAt = System.currentTimeMillis();
            rec.lastSeen = rec.attachedAt;
            // Push receive: the reader owns this socket from here - inbound is
            // handled the instant the relay pushes it, and the 25s NAT
            // keep-alive stops the mapping being reaped.
            if (mSink != null) {
                conn.startReader(mSink);
            }
            return true;
        } catch (Exception e) {
            rec.failures++;
            conn.close();
            return false;
        }
    }

    /**
     * Bring the pool up to its target, best-scoring candidates first.
     *
     * @return how many are attached afterwards
     */
    public int fill(int zTimeoutMs) {
        for (HostRecord rec : knownByScore()) {
            if (mActive.size() >= mTarget) {
                break;
            }
            if (!mActive.containsKey(rec.hostPort)) {
                attachOne(rec.hostPort, zTimeoutMs);
            }
        }
        return mActive.size();
    }

    /** Drop one relay, banking its uptime so the score reflects reality. */
    public void detach(String zHostPort) {
        HostConnection conn = mActive.remove(zHostPort);
        if (conn == null) {
            return;
        }
        HostRecord rec = mKnown.get(zHostPort);
        if (rec != null && rec.attachedAt > 0) {
            rec.totalUptimeMs += System.currentTimeMillis() - rec.attachedAt;
            rec.lastSeen = System.currentTimeMillis();
            rec.attachedAt = 0;
        }
        conn.close();
    }

    /**
     * Drop a relay that has PROVEN dead (repeated pump failures), and put it in a
     * {@link #COOLDOWN_MS} cooldown so {@code fill}/{@code reconcile} do not
     * re-adopt it on the next cycle. This is the fix for the 60s relay flap: a
     * classic node that greets but holds no mailbox, or a stale direct forward,
     * is tried at most once per cooldown window instead of every heartbeat.
     */
    public void detachDead(String zHostPort) {
        detach(zHostPort);
        mCooldown.put(zHostPort, System.currentTimeMillis() + COOLDOWN_MS);
    }

    /**
     * Keep every attachment alive, drop the dead ones, then refill. Call on a
     * heartbeat. This is the client half of connection stickiness:
     *
     *  - a connection cleanly closed ({@code !isAttached}) is detached;
     *  - a connection that has gone SILENT past {@link Frame#SILENCE_DROP_MS} is
     *    a black hole (NAT dropped the mapping, no RST) even though the socket
     *    still looks attached - detach it so a live relay replaces it. Before
     *    this, {@code reconcile} only checked {@code isAttached()} (which flips
     *    only on explicit close), so a black-holed host was never swapped out;
     *  - a healthy but quiet connection gets a keep-alive so the HOST keeps
     *    reading from us and does not drop us on its own 10-min read-silence.
     *
     * A refill after detaching is immediate (no waiting for the next tick).
     */
    public int reconcile(int zTimeoutMs) {
        for (String h : new ArrayList<>(mActive.keySet())) {
            HostConnection c = mActive.get(h);
            if (c == null || !c.isAttached()
                    || c.isStale(com.eurobuddha.maxima.core.net.Frame.SILENCE_DROP_MS)) {
                detach(h);
                continue;
            }
            if (c.needsKeepalive(com.eurobuddha.maxima.core.net.Frame.KEEPALIVE_INTERVAL_MS)) {
                try {
                    c.keepalive();
                } catch (Exception e) {
                    detach(h);   // the socket is already gone
                }
            }
        }
        return fill(zTimeoutMs);
    }

    public void closeAll() {
        for (String h : new ArrayList<>(mActive.keySet())) {
            detach(h);
        }
    }

    /** The reference deletes a Maxima host not seen for 7 days. */
    private static final long HOST_PURGE_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Forget known relays we have not successfully used for 7 days — the
     * reference's deleteOldHosts. We SCORE relays rather than purge them (a churn
     * -native design), but an unbounded score map is a slow leak and keeps
     * re-trying a relay that has been gone for a week. Never drops an attached
     * host, nor a never-tried candidate (lastSeen 0) - only a host we actually
     * used and then lost long ago.
     *
     * @return how many records were purged
     */
    public int purgeOldHosts() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (String h : new ArrayList<>(mKnown.keySet())) {
            if (mActive.containsKey(h)) {
                continue;   // never purge a live host
            }
            HostRecord r = mKnown.get(h);
            if (r == null) {
                continue;
            }
            long seen = Math.max(r.lastSeen, r.attachedAt);
            if (seen > 0 && now - seen > HOST_PURGE_MS) {
                mKnown.remove(h);
                removed++;
            }
        }
        return removed;
    }
}
