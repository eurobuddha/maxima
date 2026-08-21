package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.net.DatagramPacket;
import java.net.InetAddress;

import org.junit.Test;

/** The relay's built-in STUN responder. WebRTC ICE depends on it echoing the
 *  caller's public address correctly, and on it staying silent for anything
 *  that is not a well-formed binding request (so it can't be turned into a
 *  reflection/amplification vector). */
public class MiniStunTest {

    private static final int MAGIC = 0x2112A442;

    /** A minimal RFC 5389 binding request: type 0x0001, magic cookie, 12-byte
     *  transaction id. */
    private static byte[] bindingRequest(byte[] txid) {
        byte[] d = new byte[20];
        d[0] = 0x00;
        d[1] = 0x01;            // type: binding request
        d[2] = 0x00;
        d[3] = 0x00;            // length 0
        d[4] = (byte) (MAGIC >>> 24);
        d[5] = (byte) (MAGIC >>> 16);
        d[6] = (byte) (MAGIC >>> 8);
        d[7] = (byte) MAGIC;
        System.arraycopy(txid, 0, d, 8, 12);
        return d;
    }

    private static DatagramPacket packet(byte[] data, String fromIp, int fromPort)
            throws Exception {
        DatagramPacket p = new DatagramPacket(data, data.length);
        p.setAddress(InetAddress.getByName(fromIp));
        p.setPort(fromPort);
        return p;
    }

    @Test
    public void bindingRequestEchoesXorMappedAddress() throws Exception {
        byte[] txid = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        byte[] out = MiniStun.respond(packet(bindingRequest(txid), "203.0.113.7", 51234));

        assertEquals("success response is header + one 12-byte attribute", 32, out.length);
        assertEquals("binding success response type 0x0101", 0x01, out[0] & 0xFF);
        assertEquals(0x01, out[1] & 0xFF);

        // transaction id must be echoed verbatim (ICE matches responses by it).
        byte[] echoed = new byte[12];
        System.arraycopy(out, 8, echoed, 0, 12);
        assertArrayEquals(txid, echoed);

        // XOR-MAPPED-ADDRESS attribute header at offset 20.
        assertEquals(0x00, out[20] & 0xFF);
        assertEquals(0x20, out[21] & 0xFF);      // attr type 0x0020
        assertEquals(0x01, out[25] & 0xFF);      // family IPv4

        // De-XOR the port and address and confirm they are what the packet came from.
        int xport = ((out[26] & 0xFF) << 8) | (out[27] & 0xFF);
        int port = xport ^ (MAGIC >>> 16);
        assertEquals(51234, port);

        int b0 = (out[28] & 0xFF) ^ ((MAGIC >>> 24) & 0xFF);
        int b1 = (out[29] & 0xFF) ^ ((MAGIC >>> 16) & 0xFF);
        int b2 = (out[30] & 0xFF) ^ ((MAGIC >>> 8) & 0xFF);
        int b3 = (out[31] & 0xFF) ^ (MAGIC & 0xFF);
        assertEquals("203.0.113.7", b0 + "." + b1 + "." + b2 + "." + b3);
    }

    @Test
    public void shortPacketIgnored() throws Exception {
        assertNull(MiniStun.respond(packet(new byte[19], "203.0.113.7", 5000)));
    }

    @Test
    public void wrongMagicIgnored() throws Exception {
        byte[] d = bindingRequest(new byte[12]);
        d[4] = 0x00;   // corrupt the magic cookie
        assertNull("a packet without the STUN magic cookie must be ignored",
                MiniStun.respond(packet(d, "203.0.113.7", 5000)));
    }

    @Test
    public void nonBindingTypeIgnored() throws Exception {
        byte[] d = bindingRequest(new byte[12]);
        d[1] = 0x02;   // some other message type
        assertNull(MiniStun.respond(packet(d, "203.0.113.7", 5000)));
    }

    @Test
    public void ipv6SourceIgnored() throws Exception {
        // The fleet speaks IPv4; a v6 source must be dropped, not crash on the
        // 4-byte address assumption.
        assertNull(MiniStun.respond(packet(bindingRequest(new byte[12]), "::1", 5000)));
    }
}
