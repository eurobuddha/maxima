package com.eurobuddha.maxima.core.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.eurobuddha.maxima.core.codec.MiniData;

import org.junit.Test;

/** Mx-address encode/decode + the readUnsignedShort bounds fix (0.6.9). */
public class MxAddressTest {

    private static byte[] bytes(int n, int fill) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    @Test
    public void roundTripRealisticKey() {
        MiniData key = new MiniData(bytes(162, 0x30));   // ~RSA-1024 DER length
        String mx = MxAddress.make(key);
        assertTrue(mx.startsWith("Mx"));
        assertEquals(key, MxAddress.convert(mx));
    }

    @Test
    public void roundTripLeadingZero() {
        // The 0x01 guard byte exists so base32 doesn't truncate leading zeros.
        MiniData key = new MiniData(new byte[]{0x00, 0x00, 0x11, 0x22});
        assertEquals(key, MxAddress.convert(MxAddress.make(key)));
    }

    @Test
    public void malformedAddressThrowsCleanly() {
        String[] bad = {"notanaddress", "Mx", "Mx!!!!", "", "Mx123"};
        for (String s : bad) {
            try {
                MxAddress.convert(s);
                fail("expected IllegalArgumentException for: " + s);
            } catch (IllegalArgumentException expected) {
                // good - a clean typed exception, never NegativeArraySize/OOM
            }
        }
    }

    @Test
    public void validContactAddressBoundary() {
        assertTrue(MxAddress.isValidContactAddress("MxABC@1.2.3.4:9001"));
        // loose by design (matches classic) but needs Mx + @ + :
        org.junit.Assert.assertFalse(MxAddress.isValidContactAddress("MxABC"));
        org.junit.Assert.assertFalse(MxAddress.isValidContactAddress(null));
    }
}
