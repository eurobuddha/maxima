package com.eurobuddha.maxima.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class SeedRelaysTest {

    @Test
    public void theQrTextRoundTripsAndIsUnmistakableForAnAddress() {
        String qr = SeedRelays.share(Arrays.asList("1.2.3.4:9501", "relay.example.org:8001"));
        assertEquals("parlons-relay:1.2.3.4:9501,relay.example.org:8001", qr);
        assertEquals(Arrays.asList("1.2.3.4:9501", "relay.example.org:8001"), SeedRelays.parse(qr));
        assertFalse(qr.startsWith("Mx"));
        assertFalse(qr.startsWith("MAX#"));
    }

    @Test
    public void parseAcceptsTypedBareFormsAndSkipsJunk() {
        assertEquals(Collections.singletonList("1.2.3.4:9501"), SeedRelays.parse("  1.2.3.4:9501 "));
        assertEquals(Arrays.asList("a.b:1", "c.d:2"), SeedRelays.parse("a.b:1, c.d:2\n"));
        assertEquals(Arrays.asList("a.b:1", "c.d:2"), SeedRelays.parse("PARLONS-RELAY://a.b:1,c.d:2"));
        assertEquals(Collections.singletonList("a.b:1"), SeedRelays.parse("a.b:1,a.b:1,nonsense,:9,x:70000"));
        assertTrue(SeedRelays.parse("MAX#0xABC#Mx…@1.2.3.4:9501").isEmpty()
                || !SeedRelays.parse("MAX#0xABC#Mx…@1.2.3.4:9501").contains("MAX#0xABC#Mx…@1.2.3.4"));
        assertTrue(SeedRelays.parse(null).isEmpty());
        assertTrue(SeedRelays.parse("").isEmpty());
    }

    @Test
    public void composeOrdersUserSeedsFirstAndHonoursTheBuiltInSwitchAndDrops() {
        List<String> all = SeedRelays.compose(Arrays.asList("mine:1"), Arrays.asList("seen:2"), true,
                Collections.singletonList(Bootstrap.RELAYS.get(0)));
        assertEquals("mine:1", all.get(0));
        assertEquals("seen:2", all.get(1));
        assertFalse("a dropped built-in stays out", all.contains(Bootstrap.RELAYS.get(0)));
        assertTrue(all.contains(Bootstrap.RELAYS.get(1)));
        assertEquals(2 + Bootstrap.RELAYS.size() - 1, all.size());

        List<String> mineOnly = SeedRelays.compose(Arrays.asList("mine:1"), null, false, null);
        assertEquals(Collections.singletonList("mine:1"), mineOnly);
        for (String b : Bootstrap.RELAYS) {
            assertFalse("the compiled-in list is one source among several, not a requirement",
                    mineOnly.contains(b));
        }
        assertTrue(SeedRelays.compose(null, null, false, null).isEmpty());
    }

    @Test
    public void aClientIsNeverLeftWithoutASeed() {
        assertFalse("list on: nothing to restore", SeedRelays.builtInMustReturn(null, null, true));
        assertFalse("list off but an own relay remains", SeedRelays.builtInMustReturn(
                Collections.singletonList("mine:1"), null, false));
        assertFalse("list off but a remembered relay remains", SeedRelays.builtInMustReturn(
                null, Collections.singletonList("seen:2"), false));
        assertTrue("list off and the last seed gone: it must come back",
                SeedRelays.builtInMustReturn(Collections.emptyList(), Collections.emptyList(), false));
        assertTrue("junk entries do not count as seeds",
                SeedRelays.builtInMustReturn(Collections.singletonList("not a host"), null, false));
    }

    @Test
    public void theLastCompiledInRelayCannotBeDroppedIntoNothing() {
        List<String> allButOne = new java.util.ArrayList<>(Bootstrap.RELAYS.subList(1, Bootstrap.RELAYS.size()));
        String last = Bootstrap.RELAYS.get(0);
        assertTrue("no own relay, nothing remembered, every other built-in dropped: refuse",
                SeedRelays.droppingBuiltInLeavesNothing(null, null, allButOne, last));
        assertFalse("an own relay remains", SeedRelays.droppingBuiltInLeavesNothing(
                Collections.singletonList("mine:1"), null, allButOne, last));
        assertFalse("another built-in remains", SeedRelays.droppingBuiltInLeavesNothing(
                null, null, Collections.emptyList(), last));
        assertFalse("a remembered relay remains", SeedRelays.droppingBuiltInLeavesNothing(
                null, Collections.singletonList("seen:2"), allButOne, last));
    }

    @Test
    public void builtInMembershipIsExact() {
        assertTrue(SeedRelays.isBuiltIn(Bootstrap.RELAYS.get(2)));
        assertTrue(SeedRelays.isBuiltIn(" " + Bootstrap.RELAYS.get(2) + " "));
        assertFalse(SeedRelays.isBuiltIn("1.2.3.4:9501"));
    }
}
