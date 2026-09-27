package com.eurobuddha.maxima.app.call;

import org.junit.Test;
import org.webrtc.IceCandidate;
import static org.junit.Assert.*;

public class CallIceTest {
    private final IceCandidate ice = CallIce.parse("0\n0\ncandidate:1 1 udp 1 192.0.2.1 4000 typ host");
    @Test public void reorderedCandidatesAreIsolatedByPeerAndCallAndExpire() {
        CallIce pending = new CallIce();
        pending.remember("PEER", "call", ice, 0);
        assertTrue(pending.take("other", "call", 1).isEmpty());
        assertTrue(pending.take("peer", "other-call", 1).isEmpty());
        assertEquals(ice, pending.take("peer", "call", 1).get(0));
        assertTrue(pending.take("peer", "call", 1).isEmpty());
        pending.remember("peer", "call", ice, 0);
        assertTrue(pending.take("peer", "call", 90000).isEmpty());
    }
    @Test public void wholeEarlyQueueIsBoundedAndCanBeCleared() {
        CallIce pending = new CallIce();
        for (int i = 0; i < 400; i++) pending.remember("peer", "call", ice, 0);
        assertEquals(256, pending.take("peer", "call", 1).size());
        for (int i = 0; i < 20; i++) pending.remember("peer", "call-" + i, ice, 0);
        assertTrue(pending.take("peer", "call-8", 1).isEmpty());
        pending.clear(); assertTrue(pending.take("peer", "call-0", 1).isEmpty());
    }
    @Test public void nullMidIsNormalisedAndOversizedInputRejected() {
        assertNull(CallIce.parse("null\n0\ncandidate:x").sdpMid);
        assertNull(CallIce.parse("0\n65536\ncandidate:x"));
        assertNull(CallIce.parse("0\n0\ncandidate:" + "x".repeat(8192)));
    }
}
