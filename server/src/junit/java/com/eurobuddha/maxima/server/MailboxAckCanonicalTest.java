package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

/** The exact bytes both sides sign/verify to prove possession of a routing key
 *  (the route-hijack fix). If this layout ever drifts between client and relay,
 *  every possession probe fails and mail stops draining - so pin it hard. */
public class MailboxAckCanonicalTest {

    private static byte[] key(int fill) {
        byte[] k = new byte[162];   // ~RSA-1024 DER length
        Arrays.fill(k, (byte) fill);
        return k;
    }

    @Test
    public void layoutIsDomainTagThenKeyThenBigEndianSeq() throws Exception {
        byte[] k = key(0x30);
        long seq = 0x0102030405060708L;
        byte[] c = RelayServer.mailboxAckCanonical(k, seq);

        assertEquals("6-byte tag + key + 8-byte seq", 6 + k.length + 8, c.length);

        byte[] tag = Arrays.copyOfRange(c, 0, 6);
        assertArrayEquals("maxack".getBytes(StandardCharsets.US_ASCII), tag);

        byte[] embeddedKey = Arrays.copyOfRange(c, 6, 6 + k.length);
        assertArrayEquals(k, embeddedKey);

        // seq is a big-endian long in the trailing 8 bytes.
        byte[] seqBytes = Arrays.copyOfRange(c, 6 + k.length, c.length);
        long back = 0;
        for (byte b : seqBytes) {
            back = (back << 8) | (b & 0xFF);
        }
        assertEquals(seq, back);
    }

    @Test
    public void differentSeqProducesDifferentBytes() throws Exception {
        byte[] k = key(0x30);
        // The seq is what makes each probe unique - the domain-separated bytes
        // for seq 0 must not equal those for seq 1, or a replayed signature
        // would verify against the wrong probe.
        assertFalse(Arrays.equals(
                RelayServer.mailboxAckCanonical(k, 0),
                RelayServer.mailboxAckCanonical(k, 1)));
    }

    @Test
    public void differentKeyProducesDifferentBytes() throws Exception {
        assertFalse(Arrays.equals(
                RelayServer.mailboxAckCanonical(key(0x30), 5),
                RelayServer.mailboxAckCanonical(key(0x31), 5)));
    }

    @Test
    public void isDeterministic() throws Exception {
        byte[] k = key(0x30);
        assertArrayEquals(
                RelayServer.mailboxAckCanonical(k, 42),
                RelayServer.mailboxAckCanonical(k, 42));
    }
}
