package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.net.Probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The relay-side half of gossip discovery — classic Minima's model, verbatim in
 * spirit: peers are claimed in greetings, VERIFIED by dialling them back, and
 * only verified peers are ever shared onward (classic's {@code P2PPeersChecker}
 * unverified→verified promotion, its caps included).
 *
 * A claim is accepted for checking only when the claimed host EQUALS the source
 * IP of the connection that made it — the same anti-spoof rule as the
 * reachability probe: you can only ever nominate YOURSELF, so gossip cannot be
 * used to make relays scan or advertise third parties. Verification is a
 * {@link Probe#dial} (a greeting must come back, proving a live Maxima
 * endpoint), run on one background thread so the accept path never blocks.
 *
 * Verified peers expire unless re-claimed — a relay that goes away stops being
 * shared within {@link #TTL_MS}, keeping the gossip pool self-cleaning.
 */
public final class RelayPeers {

    /** At most this many verified peers are held and shared. */
    public static final int MAX_PEERS = 128;

    /** How many peers we include in a greeting. */
    public static final int SHARE_LIMIT = 32;

    /** A verified peer must re-claim within this window or it expires. */
    public static final long TTL_MS = 2 * 60 * 60 * 1000;

    /** Dial budget for a verification. */
    private static final int CONNECT_MS = 4000;
    private static final int READ_MS = 2000;

    private final String mProtocol;

    /** verified "ip:port" -> last time it was verified or re-claimed. */
    private final Map<String, Long> mVerified = new ConcurrentHashMap<>();

    /** claims waiting for a dial-back check. */
    private final LinkedBlockingQueue<String> mPending = new LinkedBlockingQueue<>(64);

    private volatile boolean mRunning = true;

    public RelayPeers() {
        this("1.0.48");
    }

    public RelayPeers(String zProtocol) {
        mProtocol = zProtocol;
        Thread t = new Thread(this::checkLoop, "relay-peers-check");
        t.setDaemon(true);
        t.start();
    }

    /**
     * A greeting on an inbound connection claimed {@code host:port}. Accept it
     * for verification ONLY if the claimed host is exactly the source IP of the
     * connection — self-nomination only.
     *
     * @return true if queued (or already verified — re-claim refreshes the TTL)
     */
    public boolean claim(String zSourceIp, String zClaimedHost, int zClaimedPort,
                         String zSelfHostPort) {
        if (zClaimedHost == null || zClaimedHost.isEmpty()
                || !zClaimedHost.equals(zSourceIp)) {
            return false;
        }
        if (zClaimedPort < 1 || zClaimedPort > 65535) {
            return false;
        }
        String hp = zClaimedHost + ":" + zClaimedPort;
        if (hp.equals(zSelfHostPort)) {
            return false;   // we do not gossip ourselves via our own listener
        }
        Long seen = mVerified.get(hp);
        if (seen != null) {
            mVerified.put(hp, System.currentTimeMillis());   // re-claim = TTL refresh
            return true;
        }
        if (mVerified.size() >= MAX_PEERS) {
            return false;
        }
        return mPending.offer(hp);
    }

    /** Verified peers, freshest first, capped at {@link #SHARE_LIMIT}. */
    public List<String> share() {
        List<Map.Entry<String, Long>> all = new ArrayList<>(mVerified.entrySet());
        all.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : all) {
            out.add(e.getKey());
            if (out.size() >= SHARE_LIMIT) {
                break;
            }
        }
        return out;
    }

    public int size() {
        return mVerified.size();
    }

    /** Drop verified peers that have not re-claimed within the TTL. */
    public void expire() {
        long now = System.currentTimeMillis();
        mVerified.entrySet().removeIf(e -> now - e.getValue() > TTL_MS);
    }

    public void stop() {
        mRunning = false;
    }

    private void checkLoop() {
        while (mRunning) {
            String hp;
            try {
                hp = mPending.take();
            } catch (InterruptedException e) {
                return;
            }
            if (mVerified.containsKey(hp) || mVerified.size() >= MAX_PEERS) {
                continue;
            }
            int c = hp.lastIndexOf(':');
            String host = hp.substring(0, c);
            int port = Integer.parseInt(hp.substring(c + 1));
            // The dial-back IS the admission test: a greeting must come home.
            if (Probe.dial(host, port, CONNECT_MS, READ_MS, mProtocol)) {
                mVerified.put(hp, System.currentTimeMillis());
            }
        }
    }
}
