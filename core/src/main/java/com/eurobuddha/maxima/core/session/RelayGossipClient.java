package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.HostConnection;
import com.eurobuddha.maxima.core.net.Probe;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client half of relay-gossip discovery, with the anti-Sybil adoption gate.
 *
 * Discovery travels in the greeting — classic Minima's own vocabulary. Every
 * relay we attach to hands us its VERIFIED peers list in its greeting reply
 * ({@code extraData.peers}, flat "ip:port" strings); on each {@link #tick} we
 * read those lists and consider adopting relays we don't have. Announcing is
 * the mirror image: a node that has PROVEN its own inbound port claims it in
 * the greetings it sends ({@link HostPool#setAdvertisedEndpoint}), and
 * {@link #announceNow} pushes that claim immediately with short-lived greets.
 *
 * The user chose AUTOMATIC gossip — the most decentralised option and the one
 * that most needs defending. Endpoints are free to claim, so the list a relay
 * hands us is treated as HEARSAY. The boundary is here:
 *
 *   1. PROVE before adopting — {@link Probe#dial} confirms a real Maxima relay
 *      answers at the address; a dead or fake entry is never adopted. (The
 *      relay already dial-back-verified it too — {@link RelayPeers} — but we
 *      do not have to trust the relay's word for it.)
 *   2. BOUNDED minority — at most {@code maxLearned} gossip-learned relays are
 *      adopted, and the trusted bootstrap set is never evicted, so a flood
 *      cannot crowd the honest relays out.
 *   3. EARN trust — an adopted relay is only a CANDIDATE; {@code HostPool}'s
 *      uptime/success scoring means a fresh unknown starts low.
 *
 * What a relay ever sees is per-key routing metadata — content is end-to-end
 * encrypted and clients multi-home — so even an adopted rogue learns little.
 */
public final class RelayGossipClient {

    private final MaximaIdentity mSelf;
    private final String mProtocol;
    private final int mMaxLearned;

    /** Endpoints we have already adopted via gossip. */
    private final Set<String> mLearned = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Our proven endpoint, when we are a reachable relay; null otherwise. */
    private volatile String mSelfEndpoint;

    public RelayGossipClient(MaximaIdentity zSelf, String zProtocol, int zMaxLearned) {
        mSelf = zSelf;
        mProtocol = zProtocol;
        mMaxLearned = zMaxLearned;
    }

    /** Set (or clear, with null) our proven endpoint — wired to reachability. */
    public void setSelfEndpoint(String zHostPort) {
        mSelfEndpoint = (zHostPort == null || zHostPort.isEmpty()) ? null : zHostPort;
    }

    public int learnedCount() {
        return mLearned.size();
    }

    /**
     * Announce our proven endpoint to these relays RIGHT NOW with short-lived
     * greet-and-close connections (the pool's own attaches will keep claiming it
     * from then on). Each target sees our claim, checks it against our source
     * IP, dials us back, and only then shares us onward.
     *
     * @return how many targets accepted a greeting exchange
     */
    public int announceNow(List<String> zRelays) {
        String self = mSelfEndpoint;
        if (self == null) {
            return 0;
        }
        int done = 0;
        for (String hp : zRelays) {
            int c = hp.lastIndexOf(':');
            if (c <= 0) {
                continue;
            }
            try (HostConnection conn = new HostConnection(hp.substring(0, c),
                    Integer.parseInt(hp.substring(c + 1)), mSelf.hostKey(hp), mProtocol)) {
                conn.setAdvertisedEndpoint(self);
                conn.attach(5000);
                done++;
            } catch (Exception ignored) {
                // that relay is down or slow; the others still hear us
            }
        }
        return done;
    }

    /** One discovery round over the node's live connections. Cheap; heartbeat-driven. */
    public void tick(MaximaNode zNode) {
        for (String hp : zNode.pool().activeHosts()) {
            if (mLearned.size() >= mMaxLearned) {
                return;
            }
            HostConnection c = zNode.pool().connection(hp);
            if (c == null || c.getTheirGreeting() == null) {
                continue;
            }
            List<String> active = zNode.pool().activeHosts();
            for (String peer : Greeting.peersOf(c.getTheirGreeting().getExtraData())) {
                if (mLearned.size() >= mMaxLearned) {
                    return;
                }
                consider(zNode, peer, active);
            }
        }
    }

    private void consider(MaximaNode zNode, String zHostPort, List<String> zActive) {
        // already ours / known / self?
        if (Bootstrap.RELAYS.contains(zHostPort) || mLearned.contains(zHostPort)
                || zActive.contains(zHostPort) || zHostPort.equals(mSelfEndpoint)) {
            return;
        }
        int c = zHostPort.lastIndexOf(':');
        if (c <= 0 || c == zHostPort.length() - 1) {
            return;
        }
        int port;
        try {
            port = Integer.parseInt(zHostPort.substring(c + 1));
        } catch (NumberFormatException e) {
            return;
        }
        if (port < 1 || port > 65535) {
            return;
        }
        // PROVE it is a live relay before adopting — the crux of the defence.
        if (!Probe.dial(zHostPort.substring(0, c), port, 4000, 2000, mProtocol)) {
            return;
        }
        zNode.pool().addCandidate(zHostPort);
        mLearned.add(zHostPort);
    }
}
