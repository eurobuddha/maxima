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

    /** At most this many verified peers are held (classic {@code MAX_VERIFIED_PEERS}). */
    public static final int MAX_PEERS = 250;

    /** How many peers we include in a greeting (classic {@code P2PParams.PEERS_LIST_SIZE}). */
    public static final int SHARE_LIMIT = 50;

    /** A verified peer must re-claim within this window or it expires. */
    public static final long TTL_MS = 2 * 60 * 60 * 1000;

    /** Dial budget for a verification. */
    private static final int CONNECT_MS = 4000;
    private static final int READ_MS = 2000;

    private final String mProtocol;

    /** verified "ip:port" -> last time it was verified or re-claimed. */
    private final Map<String, Long> mVerified = new ConcurrentHashMap<>();

    /** verified "ip:port" -> whether it advertised the open-resolve staticMLS pool bit
     *  in its greeting. Captured at the verification dial; the mesh forwards resolves
     *  only to pool peers (a non-pool relay would answer "unknown" anyway). */
    private final Map<String, Boolean> mPool = new ConcurrentHashMap<>();

    /** claims waiting for a dial-back check. */
    private final LinkedBlockingQueue<String> mPending = new LinkedBlockingQueue<>(64);

    private volatile boolean mRunning = true;
    /** Our own host:port, so the fleet's lists never make us dial ourselves. */
    private volatile String mSelfHostPort = "";
    /** Classic {@code -allowallip}: consider private/loopback peers too (tests, LAN fleets). */
    private volatile boolean mAllowAllIp;

    public void setAllowAllIp(boolean zAllow) {
        mAllowAllIp = zAllow;
    }

    public void setSelf(String zHostPort) {
        mSelfHostPort = zHostPort == null ? "" : zHostPort;
    }

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

    /**
     * Verified peers to hand a client, SHUFFLED and capped at {@link #SHARE_LIMIT} — exactly
     * classic's {@code P2PGreeting} ({@code Collections.shuffle(knownPeers)}). Freshest-first
     * handed every client the same head of the list; a shuffle hands each one a different
     * slice, which is what lets a population spread over the whole fleet.
     */
    public List<String> share() {
        List<String> all = new ArrayList<>(mVerified.keySet());
        java.util.Collections.shuffle(all);
        return all.size() > SHARE_LIMIT ? new ArrayList<>(all.subList(0, SHARE_LIMIT)) : all;
    }

    /** Learn a peer some OTHER relay vouched for (its greeting's peer list, seen when we
     *  dialled it): unverified until our own dial-back succeeds, like any claim. */
    public void consider(String zHostPort, String zSelfHostPort) {
        if (zHostPort == null || zHostPort.equals(zSelfHostPort)
                || mVerified.containsKey(zHostPort) || mVerified.size() >= MAX_PEERS) {
            return;
        }
        int c = zHostPort.lastIndexOf(':');
        if (c <= 0 || zHostPort.indexOf(':') != c) {
            return;
        }
        try {
            int port = Integer.parseInt(zHostPort.substring(c + 1));
            if (port < 1 || port > 65535) {
                return;
            }
        } catch (NumberFormatException e) {
            return;
        }
        if (!mAllowAllIp
                && !com.eurobuddha.maxima.core.portmap.PortMapper.isPublic(zHostPort.substring(0, c))) {
            return;
        }
        mPending.offer(zHostPort);
    }

    public int size() {
        return mVerified.size();
    }

    /** Verified peers that advertised the open-resolve pool bit, freshest first — the
     *  mesh's forwarding targets. Capped at {@link #SHARE_LIMIT}. */
    public List<String> poolPeers() {
        List<Map.Entry<String, Long>> all = new ArrayList<>(mVerified.entrySet());
        all.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : all) {
            if (Boolean.TRUE.equals(mPool.get(e.getKey()))) {
                out.add(e.getKey());
                if (out.size() >= SHARE_LIMIT) {
                    break;
                }
            }
        }
        return out;
    }

    /** Drop verified peers that have not re-claimed within the TTL. */
    public void expire() {
        long now = System.currentTimeMillis();
        mVerified.entrySet().removeIf(e -> now - e.getValue() > TTL_MS);
        mPool.keySet().retainAll(mVerified.keySet());
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
            // The dial-back IS the admission test: a greeting must come home. We keep
            // the greeting to record whether the peer is an open staticMLS pool host.
            // Our greeting CLAIMS our own endpoint when we know it, so the peer we are
            // verifying learns us by the same dial (it checks the claim against our
            // source IP and dials us back) - a relay with no account announces itself
            // exactly like a client does, and ends up in the peer's shared list.
            String self = mSelfHostPort;
            int sc = self.lastIndexOf(':');
            String selfHost = sc > 0 ? self.substring(0, sc) : null;
            int selfPort = 0;
            if (sc > 0) {
                try {
                    selfPort = Integer.parseInt(self.substring(sc + 1));
                } catch (NumberFormatException ignored) {
                }
            }
            com.eurobuddha.maxima.core.msg.Greeting g =
                    Probe.dialGreeting(host, port, CONNECT_MS, READ_MS, mProtocol, selfHost, selfPort);
            if (g != null) {
                mVerified.put(hp, System.currentTimeMillis());
                mPool.put(hp, com.eurobuddha.maxima.core.msg.Greeting.poolOf(g.getExtraData()));
                // Its greeting lists the peers IT verified: the fleet learns itself.
                for (String peer : com.eurobuddha.maxima.core.msg.Greeting.peersOf(g.getExtraData())) {
                    consider(peer, mSelfHostPort);
                }
            }
        }
    }
}
