package com.eurobuddha.maxima.core.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Key canonicalization — the bug this class exists to prevent is two casings
 *  of the same key being treated as different peers (unremovable group member,
 *  missed contact match). */
public class KeysTest {

    @Test
    public void normCanonicalizesPrefixCaseAndHexCase() {
        // Keys always carry a 0x/0X prefix on the wire (DER to0xString emits
        // "0x"). norm folds the prefix to lowercase 0x and the hex to uppercase,
        // so the two casings of one key collapse to one canonical string.
        String a = Keys.norm("0x30819f");
        String b = Keys.norm("0X30819F");
        assertEquals(a, b);
        assertEquals("0x30819F", a);
        assertTrue("normalized key keeps a 0x prefix", a.startsWith("0x"));
    }

    @Test
    public void sameIsCaseInsensitiveAcrossPrefixCasing() {
        assertTrue(Keys.same("0x30819F", "0X30819f"));
        assertFalse(Keys.same("0x30819f", "0x30819e"));
    }

    @Test
    public void normNullSafe() {
        // Must not throw on null/empty (inbound paths pass raw values).
        Keys.norm(null);
        Keys.norm("");
    }

    @Test
    public void fingerprintIsDeterministicAndCaseIndependent() {
        String hex = "0x30819F300D06092A";
        String f1 = Keys.fingerprint(hex);
        String f2 = Keys.fingerprint(hex.toLowerCase());
        assertEquals("two casings of one key must fingerprint identically", f1, f2);
        assertFalse(f1.isEmpty());
    }
}
