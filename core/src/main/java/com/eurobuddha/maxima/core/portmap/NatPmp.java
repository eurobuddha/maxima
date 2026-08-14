package com.eurobuddha.maxima.core.portmap;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * NAT-PMP client (RFC 6886) - the Apple-era port mapping protocol.
 *
 * Tried before UPnP because it is one UDP round trip with a fixed binary
 * format: nothing to parse, nothing to get wrong, and a router that supports
 * it answers in single-digit milliseconds. The catch is that it must be
 * spoken TO THE GATEWAY, whose address the JVM cannot portably discover -
 * callers supply candidates (see {@link PortMapper}).
 *
 * Wire format, all big-endian:
 *
 *   external address request : version(0) opcode(0)
 *   response                 : version(0) opcode(128) result(u16) epoch(u32) ip(4)
 *
 *   map request              : version(0) opcode(2=TCP) reserved(u16)
 *                              internalPort(u16) suggestedExternal(u16)
 *                              lifetime(u32)
 *   response                 : version(0) opcode(130) result(u16) epoch(u32)
 *                              internalPort(u16) mappedExternal(u16)
 *                              grantedLifetime(u32)
 *
 * A lifetime of 0 in a map request DELETES the mapping - that is the official
 * release mechanism, not a separate opcode.
 */
public final class NatPmp {

    /** RFC 6886: the protocol runs on UDP 5351 at the gateway. */
    public static final int PORT = 5351;

    private static final int RESULT_SUCCESS = 0;

    private final InetAddress mGateway;
    private final int mTimeoutMs;

    public NatPmp(InetAddress zGateway, int zTimeoutMs) {
        mGateway = zGateway;
        mTimeoutMs = zTimeoutMs;
    }

    /** The router's idea of its public address, or null if it will not say. */
    public InetAddress externalAddress() {
        byte[] resp = roundTrip(new byte[]{0, 0}, 12);
        if (resp == null || (resp[1] & 0xFF) != 128 || u16(resp, 2) != RESULT_SUCCESS) {
            return null;
        }
        try {
            return InetAddress.getByAddress(new byte[]{
                    resp[8], resp[9], resp[10], resp[11]});
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Map (or, with zero lifetime, unmap) a TCP port.
     *
     * @return the granted mapping, or null on refusal/timeout. The router may
     *         grant a DIFFERENT external port than suggested - always read it
     *         from the result rather than assuming.
     */
    public Result mapTcp(int zInternalPort, int zSuggestedExternal, long zLifetimeSeconds) {
        byte[] req = new byte[12];
        req[0] = 0;                       // version
        req[1] = 2;                       // opcode: TCP map
        put16(req, 4, zInternalPort);
        put16(req, 6, zSuggestedExternal);
        put32(req, 8, zLifetimeSeconds);

        byte[] resp = roundTrip(req, 16);
        if (resp == null || (resp[1] & 0xFF) != 130 || u16(resp, 2) != RESULT_SUCCESS) {
            return null;
        }
        return new Result(u16(resp, 8), u16(resp, 10), u32(resp, 12));
    }

    public static final class Result {
        public final int internalPort;
        public final int externalPort;
        public final long lifetimeSeconds;

        Result(int zInternal, int zExternal, long zLifetime) {
            internalPort = zInternal;
            externalPort = zExternal;
            lifetimeSeconds = zLifetime;
        }
    }

    // ---------------------------------------------------------------

    /**
     * One request, one response. RFC 6886 wants exponential retransmission;
     * we keep a single try per candidate gateway because the caller is racing
     * several candidates and a genuine NAT-PMP router answers instantly.
     */
    private byte[] roundTrip(byte[] zRequest, int zExpectLen) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(mTimeoutMs);
            s.send(new DatagramPacket(zRequest, zRequest.length,
                    new InetSocketAddress(mGateway, PORT)));
            byte[] buf = new byte[64];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            if (p.getLength() < zExpectLen) {
                return null;
            }
            byte[] out = new byte[p.getLength()];
            System.arraycopy(buf, 0, out, 0, p.getLength());
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    private static void put32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
