package com.eurobuddha.maxima.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.rpc.Capabilities;

import org.junit.Test;

/**
 * Merit-weighted fungibility, step 1: host CAPACITY is advertised and folded
 * into selection score - WITHOUT ever demoting a classic node below where it
 * sits today. The load-bearing invariant is that the classic Maxima network
 * must keep carrying comms with none of our extensions running, so an
 * unspecified capacity (a classic host) must yield a NEUTRAL score factor, never
 * a penalty. Capacity is a number the network weights by, never a node type.
 */
public class CapacityScoreTest {

    private static final double EPS = 1e-9;

    // ----- Capabilities: capacity rides in the encoded value, classic-invisible

    @Test
    public void capacityRoundtripsAlongsideFlags() {
        Capabilities caps = Capabilities.phoneDefaults().withCapacity(16);
        Capabilities back = Capabilities.decode(caps.encode());
        assertEquals(16, back.capacity());
        assertTrue(back.has(Capabilities.MAILBOX));
        assertTrue(back.has(Capabilities.DIRECTORY));
    }

    @Test
    public void classicValueHasNoCapacityAndStaysClassic() {
        Capabilities classic = Capabilities.decode("");
        assertEquals(Capabilities.UNSPECIFIED_CAPACITY, classic.capacity());
        assertTrue(classic.isClassic());
        // A node that advertises no capacity encodes no cap: token.
        assertFalse(Capabilities.phoneDefaults().encode().contains("cap:"));
    }

    @Test
    public void malformedCapacityIsUnspecifiedNeverFatal() {
        assertEquals(0, Capabilities.decode("rpc,cap:notanumber,box").capacity());
        // …and the real flags still survive the bad token.
        assertTrue(Capabilities.decode("rpc,cap:notanumber,box").has(Capabilities.MAILBOX));
    }

    // ----- Greeting: capacity rides the extraData JSON, classic-invisible

    @Test
    public void greetingAdvertisesAndReadsCapacity() {
        Greeting g = Greeting.commsOnly("1.0.48", "65.109.31.226", 9501,
                java.util.Collections.emptyList(), 512);
        assertEquals(512, Greeting.capOf(g.getExtraData()));
    }

    @Test
    public void greetingWithoutCapacityReadsZero() {
        Greeting g = Greeting.commsOnly("1.0.48", "1.2.3.4", 9501);
        assertEquals(0, Greeting.capOf(g.getExtraData()));
        // The classic greeting must NOT contain a cap key.
        assertFalse(g.getExtraData().contains("\"cap\""));
    }

    // ----- Score: classic neutral, capacity a bounded bonus, merit over type

    private static HostPool.HostRecord rec(int successes, int failures,
                                           double uptimeHours, int capacity) {
        HostPool.HostRecord r = new HostPool.HostRecord("host:9501");
        r.successes = successes;
        r.failures = failures;
        r.totalUptimeMs = (long) (uptimeHours * 3_600_000.0);
        r.advertisedCapacity = capacity;
        return r;
    }

    @Test
    public void unspecifiedCapacityIsANeutralFactor() {
        // A classic host (capacity 0) scores exactly reliability x uptimeFactor -
        // the capacity factor is 1.0, so it is never demoted below its history.
        HostPool.HostRecord classic = rec(10, 0, 2.0, 0);
        double reliability = 1.0;
        double uptimeFactor = 1.0 + Math.log1p(2.0);
        assertEquals(reliability * uptimeFactor, classic.score(), EPS);
    }

    @Test
    public void advertisedCapacityOnlyEverAddsBonus() {
        // Same history, one advertises capacity: it ranks at least as high, never
        // lower. Classic is promoted-past, never penalised.
        HostPool.HostRecord classic = rec(10, 0, 2.0, 0);
        HostPool.HostRecord withCap = rec(10, 0, 2.0, 512);
        assertTrue(withCap.score() > classic.score());
    }

    @Test
    public void reliablePhoneBeatsFlakyVps() {
        // Merit, not type: a stable phone (small cap, great uptime/success)
        // outranks a churny VPS (huge cap, terrible success). The network leans
        // on who actually carries, regardless of device.
        HostPool.HostRecord phone = rec(50, 1, 5.0, 16);
        HostPool.HostRecord flakyVps = rec(2, 20, 0.1, 1024);
        assertTrue(phone.score() > flakyVps.score());
    }

    @Test
    public void capacityBonusIsBoundedNotALandslide() {
        // A huge-capacity VPS does not swamp an equally-reliable phone: the cap
        // factor is log-normalised, so the edge is < ~2x, preserving fan-out.
        HostPool.HostRecord phone = rec(20, 0, 3.0, 16);
        HostPool.HostRecord vps = rec(20, 0, 3.0, 1024);
        assertTrue(vps.score() < phone.score() * 2.0);
    }
}
