package com.eurobuddha.maxima.server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * A minimal RFC 5389 STUN binding responder on UDP, sharing the relay's port
 * number. Its only job: tell a phone what public address its call packets
 * come from, so WebRTC ICE can hole-punch — replacing the third-party STUN
 * server that would otherwise see every caller's IP. Answers binding requests
 * with XOR-MAPPED-ADDRESS and ignores everything else.
 */
public final class MiniStun implements Runnable {

    private static final int MAGIC = 0x2112A442;

    private final int mPort;

    public MiniStun(int zPort) {
        mPort = zPort;
    }

    @Override
    public void run() {
        try (DatagramSocket sock = new DatagramSocket(mPort)) {
            byte[] buf = new byte[1500];
            System.out.println("STUN responder on udp/" + mPort);
            while (true) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                sock.receive(in);
                byte[] resp = respond(in);
                if (resp != null) {
                    sock.send(new DatagramPacket(resp, resp.length,
                            in.getAddress(), in.getPort()));
                }
            }
        } catch (Exception e) {
            System.err.println("STUN responder stopped: " + e);
        }
    }

    /** Binding request -> success response with XOR-MAPPED-ADDRESS, else null. */
    static byte[] respond(DatagramPacket zIn) {
        byte[] d = zIn.getData();
        int len = zIn.getLength();
        if (len < 20) {
            return null;
        }
        int type = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
        int magic = ((d[4] & 0xFF) << 24) | ((d[5] & 0xFF) << 16)
                | ((d[6] & 0xFF) << 8) | (d[7] & 0xFF);
        if (type != 0x0001 || magic != MAGIC) {
            return null;   // not a binding request
        }
        InetAddress addr = zIn.getAddress();
        if (!(addr instanceof Inet4Address)) {
            return null;   // v4 only - the fleet speaks v4
        }
        byte[] ip = addr.getAddress();
        int port = zIn.getPort();

        byte[] out = new byte[20 + 12];
        out[0] = 0x01;                     // binding success response 0x0101
        out[1] = 0x01;
        out[2] = 0x00;                     // message length: one 12-byte attr
        out[3] = 12;
        out[4] = (byte) (MAGIC >>> 24);
        out[5] = (byte) (MAGIC >>> 16);
        out[6] = (byte) (MAGIC >>> 8);
        out[7] = (byte) MAGIC;
        System.arraycopy(d, 8, out, 8, 12);   // transaction id echoed

        int a = 20;
        out[a] = 0x00;                     // XOR-MAPPED-ADDRESS 0x0020
        out[a + 1] = 0x20;
        out[a + 2] = 0x00;                 // attr length 8
        out[a + 3] = 0x08;
        out[a + 4] = 0x00;                 // reserved
        out[a + 5] = 0x01;                 // family: IPv4
        int xport = port ^ (MAGIC >>> 16);
        out[a + 6] = (byte) (xport >>> 8);
        out[a + 7] = (byte) xport;
        out[a + 8] = (byte) (ip[0] ^ (byte) (MAGIC >>> 24));
        out[a + 9] = (byte) (ip[1] ^ (byte) (MAGIC >>> 16));
        out[a + 10] = (byte) (ip[2] ^ (byte) (MAGIC >>> 8));
        out[a + 11] = (byte) (ip[3] ^ (byte) MAGIC);
        return out;
    }
}
