package com.eurobuddha.maxima.core.chat;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The untrusted-ref gate: an inbound keets media ref is interpolated into a
 * node command whose tokenizer is whitespace-split and last-key-wins, so any
 * ref carrying whitespace could rewrite the command. validRef is the single
 * rule every fetch path enforces.
 */
public class KeetsRefGuardTest {

    @Test
    public void acceptsNormalRefs() {
        assertTrue(KeetsTransport.validRef("keets://abc123/chat/vn-1760000000000.ogg"));
        assertTrue(KeetsTransport.validRef("keets://0a1b2c3d/img-1.jpg?x=1"));
    }

    @Test
    public void rejectsCommandRewriteShapes() {
        // the live exploit shape: a space lets the ref append winning keys
        assertFalse(KeetsTransport.validRef(
                "keets://x action:publish path:/loot file:/data/data/n/files/keys"));
        assertFalse(KeetsTransport.validRef("keets://x\naction:publish"));
        assertFalse(KeetsTransport.validRef("keets://x\taction:publish"));
    }

    @Test
    public void rejectsMalformed() {
        assertFalse(KeetsTransport.validRef(null));
        assertFalse(KeetsTransport.validRef(""));
        assertFalse(KeetsTransport.validRef("keets://"));
        assertFalse(KeetsTransport.validRef("http://evil/keets://x"));
        assertFalse(KeetsTransport.validRef(" keets://x"));
    }
}
