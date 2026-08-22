package com.eurobuddha.maxima.core.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Merit-weighted fungibility, step 3: a node advertises the reachability-gated
 * SERVER roles (directory/mailbox/storage) + host capacity only while it is
 * actually directly reachable. A NAT'd node keeps its client-side roles - so it
 * stays NON-classic and its feature gating is unaffected - but never promises a
 * service it cannot serve. Reachability, a measured property, is the gate; never
 * device type. This is what makes fungibility SAFE: a phone claims host/MLS/
 * mailbox roles exactly when it can honour them, and drops them the instant it
 * cannot - identical treatment to a jar in the same state.
 */
public class ReachabilityGateTest {

    @Test
    public void reachableAdvertisesEverything() {
        Capabilities full = Capabilities.phoneDefaults().withCapacity(16);
        Capabilities out = full.gateForReachability(true);
        assertTrue(out.has(Capabilities.DIRECTORY));
        assertTrue(out.has(Capabilities.MAILBOX));
        assertTrue(out.has(Capabilities.STORAGE));
        assertEquals(16, out.capacity());
    }

    @Test
    public void unreachableDropsServerRolesAndCapacity() {
        Capabilities out = Capabilities.phoneDefaults().withCapacity(16)
                .gateForReachability(false);
        // Server roles gone - we never promise what we cannot serve.
        assertFalse(out.has(Capabilities.DIRECTORY));
        assertFalse(out.has(Capabilities.MAILBOX));
        assertFalse(out.has(Capabilities.STORAGE));
        // Host capacity gone - a NAT'd node hosts nothing.
        assertEquals(0, out.capacity());
    }

    @Test
    public void unreachableKeepsClientRolesAndStaysNonClassic() {
        Capabilities out = Capabilities.phoneDefaults().gateForReachability(false);
        // Client-side roles survive - they work behind NAT.
        assertTrue(out.has(Capabilities.RPC));
        assertTrue(out.has(Capabilities.RELIABLE));
        assertTrue(out.has(Capabilities.GOSSIP));
        assertTrue(out.has(Capabilities.WITNESS));
        // Crucially, still NON-classic: a NAT'd Parlons node must not be mistaken
        // for a stock minima.jar, or its feature gating (voice notes, etc.) dies.
        assertFalse(out.isClassic());
    }

    @Test
    public void classicStaysClassicEitherWay() {
        Capabilities classic = Capabilities.none();
        assertTrue(classic.gateForReachability(true).isClassic());
        assertTrue(classic.gateForReachability(false).isClassic());
    }

    @Test
    public void gatedSetEncodesWithoutServerRolesOrCapacity() {
        // Round-trip through the wire encoding and assert on the DECODED contract,
        // not on substrings of the string (a cap code is a substring of longer
        // words - "dir"/"box" - so contains() checks are fragile).
        Capabilities back = Capabilities.decode(
                Capabilities.phoneDefaults().withCapacity(16)
                        .gateForReachability(false).encode());
        assertFalse(back.has(Capabilities.DIRECTORY));
        assertFalse(back.has(Capabilities.MAILBOX));
        assertFalse(back.has(Capabilities.STORAGE));
        assertEquals(0, back.capacity());
        assertTrue(back.has(Capabilities.RPC));
        assertFalse(back.isClassic());
    }
}
