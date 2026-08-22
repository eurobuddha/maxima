package com.eurobuddha.maxima.core.session;

import static org.junit.Assert.assertEquals;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.util.List;

import org.junit.Test;

/**
 * Merit-weighted fungibility, step 2: MLS-anchor and directory selection walk
 * hosts in {@code score()} order (via {@code activeHostsByScore}), so the perm
 * -address anchor and the directories we query are chosen by merit - reliability
 * x uptime x capacity, never node type. This locks the ORDERING the selection
 * relies on: {@code knownByScore} and {@code activeHostsByScore} share the exact
 * comparator, so ranking known records by score proves the order selection uses.
 */
public class MeritOrderTest {

    private static HostPool pool() {
        byte[] seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) (i + 1);
        }
        return new HostPool(MaximaIdentity.fromSeed(new MiniData(seed)), "1.0.48");
    }

    /** knownByScore returns the LIVE records, so we can shape their history. */
    private static HostPool.HostRecord recordFor(HostPool zPool, String zHostPort) {
        zPool.addCandidate(zHostPort);
        for (HostPool.HostRecord r : zPool.knownByScore()) {
            if (r.hostPort.equals(zHostPort)) {
                return r;
            }
        }
        throw new IllegalStateException("candidate not registered: " + zHostPort);
    }

    private static void shape(HostPool.HostRecord r, int successes, int failures,
                             double uptimeHours, int capacity) {
        r.successes = successes;
        r.failures = failures;
        r.totalUptimeMs = (long) (uptimeHours * 3_600_000.0);
        r.advertisedCapacity = capacity;
    }

    @Test
    public void anchorSelectionRanksByMeritNotType() {
        HostPool pool = pool();
        // A reliable, high-capacity VPS; a reliable CLASSIC host (no capacity);
        // and a flaky high-capacity VPS. Merit order must put the two reliable
        // hosts ahead of the flaky one, and the capacity bonus edges the reliable
        // VPS ahead of the equally-reliable classic host - which is promoted-past,
        // never demoted below its own reliability.
        shape(recordFor(pool, "reliable-vps:9501"), 40, 0, 6.0, 1024);
        shape(recordFor(pool, "reliable-classic:9501"), 40, 0, 6.0, 0);
        shape(recordFor(pool, "flaky-vps:9501"), 2, 30, 0.2, 1024);

        List<HostPool.HostRecord> ranked = pool.knownByScore();
        assertEquals("reliable-vps:9501", ranked.get(0).hostPort);
        assertEquals("reliable-classic:9501", ranked.get(1).hostPort);
        assertEquals("flaky-vps:9501", ranked.get(2).hostPort);
    }

    @Test
    public void reliablePhoneOutranksFlakyVpsForAnchor() {
        HostPool pool = pool();
        // The fungibility headline: a stable phone (small cap) is a better anchor
        // than a churny VPS (huge cap). Selection leans on who actually carries.
        shape(recordFor(pool, "phone:9535"), 60, 1, 8.0, 16);
        shape(recordFor(pool, "flaky-vps:9501"), 3, 40, 0.1, 4096);

        assertEquals("phone:9535", pool.knownByScore().get(0).hostPort);
    }
}
