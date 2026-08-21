package com.eurobuddha.maxima.core.codec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import org.junit.Test;

/** The amount/quantity type. MiniNumber is used as a MAP KEY and set member in
 *  the mailbox/sequence logic, so its equals/hashCode must agree AND ignore
 *  trailing zeros (1 and 1.0 are the same number); a mismatch there silently
 *  loses entries from a HashMap. */
public class MiniNumberTest {

    @Test
    public void trailingZerosAreEqual() {
        assertEquals(new MiniNumber("1"), new MiniNumber("1.0"));
        assertEquals(new MiniNumber("1.500"), new MiniNumber("1.5"));
    }

    @Test
    public void equalNumbersShareHashCode() {
        // Required for correct HashMap/HashSet behaviour.
        assertEquals(new MiniNumber("1.0").hashCode(), new MiniNumber("1").hashCode());
        assertEquals(new MiniNumber(0).hashCode(), new MiniNumber("0.00").hashCode());
    }

    @Test
    public void distinctNumbersNotEqual() {
        assertNotEquals(new MiniNumber("1"), new MiniNumber("2"));
        assertNotEquals(new MiniNumber("0.1"), new MiniNumber("0.2"));
    }

    @Test
    public void constructorsAgree() {
        assertEquals(new MiniNumber(42), new MiniNumber("42"));
        assertEquals(new MiniNumber(0), new MiniNumber("0"));
    }

    @Test
    public void streamRoundTrip() throws Exception {
        for (String v : new String[]{"0", "1", "255", "1000000", "0.00000001"}) {
            MiniNumber n = new MiniNumber(v);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            n.writeDataStream(new DataOutputStream(bo));
            MiniNumber back = MiniNumber.readFromStream(
                    new DataInputStream(new ByteArrayInputStream(bo.toByteArray())));
            assertEquals("round-trip failed for " + v, n, back);
        }
    }

    @Test
    public void writeToStreamMatchesConstructedValue() throws Exception {
        // The bare-length helper the reference uses must encode the same bytes
        // as constructing the MiniNumber directly.
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        MiniNumber.writeToStream(new DataOutputStream(a), 7);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        new MiniNumber(7).writeDataStream(new DataOutputStream(b));
        assertTrue(java.util.Arrays.equals(a.toByteArray(), b.toByteArray()));
    }
}
