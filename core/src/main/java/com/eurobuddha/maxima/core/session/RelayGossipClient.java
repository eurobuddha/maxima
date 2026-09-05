package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.HostConnection;

import java.util.List;

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

    /** The discovery of the last node ticked — where learned relays actually live now. */
    private volatile PeerDiscovery mDiscovery;

    /** Our proven endpoint, when we are a reachable relay; null otherwise. */
    private volatile String mSelfEndpoint;

    /** @param zMaxLearned ignored since the classic port: the verified list is bounded by
     *  {@link PeerDiscovery#MAX_VERIFIED_PEERS} (250) with classic's admission rule, not by a
     *  small per-client cap — a cap of 8 was measured to pin every phone to the bootstrap set. */
    public RelayGossipClient(MaximaIdentity zSelf, String zProtocol, int zMaxLearned) {
        mSelf = zSelf;
        mProtocol = zProtocol;
    }

    /** Set (or clear, with null) our proven endpoint — wired to reachability. */
    public void setSelfEndpoint(String zHostPort) {
        mSelfEndpoint = (zHostPort == null || zHostPort.isEmpty()) ? null : zHostPort;
        PeerDiscovery d = mDiscovery;
        if (d != null && mSelfEndpoint != null) {
            d.addSelf(mSelfEndpoint);
        }
    }

    /** Verified relays learned from the network (classic's known peers), floor excluded. */
    public int learnedCount() {
        PeerDiscovery d = mDiscovery;
        if (d == null) {
            return 0;
        }
        int n = 0;
        for (String hp : d.verified()) {
            if (!Bootstrap.RELAYS.contains(hp)) {
                n++;
            }
        }
        return n;
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

    /**
     * One discovery round: the node's {@link PeerDiscovery} owns the work now (classic's
     * peers checker — every attached relay's greeting was already fed to it on attach; this
     * runs the deferred rechecks and the periodic save). Kept so callers stay unchanged.
     */
    public void tick(MaximaNode zNode) {
        PeerDiscovery d = zNode.discovery();
        mDiscovery = d;
        if (mSelfEndpoint != null) {
            d.addSelf(mSelfEndpoint);
        }
        for (String hp : zNode.pool().activeHosts()) {
            HostConnection c = zNode.pool().connection(hp);
            if (c != null && c.getTheirGreeting() != null) {
                d.onGreeting(hp, c.getTheirGreeting());
            }
        }
        d.tick();
    }
}
