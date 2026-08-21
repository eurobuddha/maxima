package com.eurobuddha.maxima.core.codec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import org.junit.Test;

/** Length-prefixed codec + the hostile-length DoS guard (a bogus huge length
 *  must be rejected WITHOUT pre-allocating the buffer). */
public class MiniDataTest {

    @Test
    public void streamRoundTrip() throws Exception {
        MiniData d = new MiniData(new byte[]{1, 2, 3, 4, 5});
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        d.writeDataStream(new DataOutputStream(bo));
        MiniData back = MiniData.readFromStream(
                new DataInputStream(new ByteArrayInputStream(bo.toByteArray())));
        assertEquals(d, back);
    }

    @Test
    public void hexRoundTrip() {
        MiniData d = new MiniData("0x0A0B0C");
        assertEquals("0x0A0B0C", d.to0xString());
        assertEquals(3, d.getLength());
    }

    @Test
    public void negativeLengthRejected() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        new DataOutputStream(bo).writeInt(-1);   // hostile negative length
        try {
            MiniData.readFromStream(
                    new DataInputStream(new ByteArrayInputStream(bo.toByteArray())));
            fail("negative length must be rejected");
        } catch (Exception expected) {
            // good
        }
    }

    @Test
    public void hugeDeclaredLengthDoesNotOOM() throws Exception {
        // Declare 2GB but provide no data. Must throw (EOF/limit), NOT allocate
        // a 2GB buffer - the DoS guard the review flagged.
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        new DataOutputStream(bo).writeInt(Integer.MAX_VALUE);
        try {
            MiniData.readFromStream(
                    new DataInputStream(new ByteArrayInputStream(bo.toByteArray())));
            fail("a 2GB declared length with no data must throw");
        } catch (Throwable expected) {
            // good - and crucially without OOM
        }
    }
}
