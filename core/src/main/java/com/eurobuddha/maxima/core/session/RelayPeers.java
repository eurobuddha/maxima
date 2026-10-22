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

    /** A verified peer that keeps re-claiming is re-dialled at most this often, so what its greeting
     *  ADVERTISES (pool bit, dex URL) stays current. Before this, the details were captured once at the
     *  first verification and a re-claim only refreshed the TTL: a peer that turned its DEX relay on later
     *  was never listed (seen live 2026-10-22: eurobuddha.com never learned sally's or megammr's dex URL). */
    public static final long REDIAL_MS = 10 * 60 * 1000;

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

    /** verified "ip:port" -> the DEX relay base URL it advertised ({@code "dex"}), captured at the
     *  verification dial like the pool bit. Only peers that advertise one have an entry. */
    private final Map<String, String> mDex = new ConcurrentHashMap<>();

    /** "ip:port" -> when we last dialled it to read its greeting (verification or refresh). */
    private final Map<String, Long> mDialed = new ConcurrentHashMap<>();
    private volatile long mRedialMs = REDIAL_MS;

    /** Tests shorten the refresh window. */
    public void setRedialMs(long zMs) {
        mRedialMs = zMs;
    }

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
            long now = System.currentTimeMillis();
            mVerified.put(hp, now);   // re-claim = TTL refresh
            Long dialed = mDialed.get(hp);
            if ((dialed == null || now - dialed >= mRedialMs) && !mPending.contains(hp)) {
                mPending.offer(hp);   // and, at most every REDIAL_MS, re-read what it advertises
            }
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

    /** The DEX relays among our verified peers: base URL -> when that peer was last verified or
     *  re-claimed (epoch ms). The {@code dex-relay} records this host knows from the mesh. */
    public Map<String, Long> dexRelays() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : mDex.entrySet()) {
            Long seen = mVerified.get(e.getKey());
            if (seen != null) {
                out.merge(e.getValue(), seen, Math::max);
            }
        }
        return out;
    }

    /** Drop verified peers that have not re-claimed within the TTL. */
    public void expire() {
        long now = System.currentTimeMillis();
        mVerified.entrySet().removeIf(e -> now - e.getValue() > TTL_MS);
        mPool.keySet().retainAll(mVerified.keySet());
        mDex.keySet().retainAll(mVerified.keySet());
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
            boolean known = mVerified.containsKey(hp);
            Long dialed = mDialed.get(hp);
            if (known && dialed != null && System.currentTimeMillis() - dialed < mRedialMs) {
                continue;   // verified and read recently
            }
            if (!known && mVerified.size() >= MAX_PEERS) {
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
            mDialed.put(hp, System.currentTimeMillis());
            com.eurobuddha.maxima.core.msg.Greeting g =
                    Probe.dialGreeting(host, port, CONNECT_MS, READ_MS, mProtocol, selfHost, selfPort);
            if (g != null) {
                mVerified.put(hp, System.currentTimeMillis());
                mPool.put(hp, com.eurobuddha.maxima.core.msg.Greeting.poolOf(g.getExtraData()));
                String dex = com.eurobuddha.maxima.core.msg.Greeting.dexOf(g.getExtraData());
                if (dex.isEmpty()) {
                    mDex.remove(hp);
                } else {
                    mDex.put(hp, dex);
                }
                // Its greeting lists the peers IT verified: the fleet learns itself.
                for (String peer : com.eurobuddha.maxima.core.msg.Greeting.peersOf(g.getExtraData())) {
                    consider(peer, mSelfHostPort);
                }
            }
        }
    }
}
