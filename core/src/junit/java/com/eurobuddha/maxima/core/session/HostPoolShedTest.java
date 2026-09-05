package com.eurobuddha.maxima.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * A shed is advisory and the CLIENT decides: it moves to a replacement IT chose at random and
 * had already attached, never off its preferred cape, and honours a relay at most once per
 * {@link HostPool#SHED_ACCEPT_MS}. A relay can spread its load; it can never steer a client.
 */
public class HostPoolShedTest {

    static MaximaIdentity identity(int salt) {
        byte[] seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) (i + salt);
        }
        return MaximaIdentity.fromSeed(new MiniData(seed));
    }

    @Test
    public void aShedMovesToAReplacementTheClientChoseAfterItAttached() throws Exception {
        List<FakeRelay> relays = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            relays.add(new FakeRelay(new ArrayList<>()));
        }
        try {
            HostPool pool = new HostPool(identity(1), PeerDiscoveryTest.PROTO, 1);
            for (FakeRelay r : relays) {
                pool.addCandidate(r.hostPort());
            }
            assertEquals(1, pool.fill(10000));
            String before = pool.activeHosts().get(0);
            assertTrue(pool.shed(before, 10000));
            assertEquals(1, pool.activeCount());
            String after = pool.activeHosts().get(0);
            assertFalse("moved off the relay that asked", before.equals(after));
            // the same relay asking again within the window is ignored
            pool.addCandidate(before);
            assertFalse(pool.shed(after, 10000) && pool.shed(after, 10000));
            pool.closeAll();
        } finally {
            for (FakeRelay r : relays) {
                r.close();
            }
        }
    }

    @Test
    public void thePreferredCapeIsNeverLeftAndNoAlternativeMeansStay() throws Exception {
        try (FakeRelay cape = new FakeRelay(new ArrayList<>())) {
            HostPool pool = new HostPool(identity(2), PeerDiscoveryTest.PROTO, 1);
            pool.setPreferred(cape.hostPort());
            assertEquals(1, pool.fill(10000));
            assertFalse("preferred cape: shed ignored", pool.shed(cape.hostPort(), 10000));
            assertEquals(cape.hostPort(), pool.activeHosts().get(0));

            HostPool lone = new HostPool(identity(3), PeerDiscoveryTest.PROTO, 1);
            lone.addCandidate(cape.hostPort());
            assertEquals(1, lone.fill(10000));
            assertFalse("no other relay known: stay", lone.shed(cape.hostPort(), 3000));
            assertEquals(1, lone.activeCount());
            pool.closeAll();
            lone.closeAll();
        }
    }
}
