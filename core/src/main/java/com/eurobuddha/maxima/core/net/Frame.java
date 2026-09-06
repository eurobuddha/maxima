package com.eurobuddha.maxima.core.net;

import com.eurobuddha.maxima.core.codec.MiniByte;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Minima P2P framing - the substrate Maxima rides on.
 *
 * <pre>
 *   int32 BE length | uint8 type | payload (length-1 bytes)
 * </pre>
 *
 * There is no magic, no version and no checksum at the frame level.
 *
 * A convenient consequence of MiniData sharing this exact shape: a prebuilt
 * body can be written to a socket via MiniData.writeDataStream and it is
 * already a valid frame.
 */
public final class Frame {

    // ---- NIO message types (only those Maxima touches) ----
    public static final int MSG_GREETING = 0;
    /** Reused as the Maxima ACK channel. */
    public static final int MSG_PING = 8;
    public static final int MSG_MAXIMA_CTRL = 9;
    public static final int MSG_MAXIMA_TXPOW = 10;
    /**
     * Connectivity probe / reply (reference: NIOManager.sendPingMessage). A
     * SINGLE_PING carries one MiniData (classic sends {@code ZERO_TXPOWID}); the
     * receiver answers a SINGLE_PONG carrying a Greeting. It is our keep-alive
     * primitive: the reference handler triggers no IBD, never disconnects, and
     * tolerates a chainless sender (NIOMessage.java:1058-1107). Crucially,
     * classic stamps its per-peer read-clock on EVERY inbound frame
     * (NIOClient.java:400), and drops any peer silent &gt; 10 min - so sending
     * one of these on a quiet connection is exactly what keeps a classic peer
     * from dropping us. An unknown frame type only logs on the reference; it is
     * never a disconnect (NIOMessage.java:1371-1374).
     */
    public static final int MSG_SINGLE_PING = 11;
    public static final int MSG_SINGLE_PONG = 12;

    /**
     * Relay-plane directory forwarding (the Phase-B MLS mesh). A pool relay that misses a
     * resolve asks a peer pool relay with a DIR_QUERY; the peer answers from its OWN store
     * (strict 1-hop — it never re-forwards) with a DIR_ANSWER carrying the publisher's
     * signed proof, so the asking relay verifies the address itself. High values, well
     * clear of the classic NIO type range: only our relays send these, and an unknown
     * type is merely ignored on the reference (never a disconnect).
     */
    public static final int MSG_DIR_QUERY = 200;
    public static final int MSG_DIR_ANSWER = 201;
    /** Relay -> peer relay: "here is a signed directory entry I just accepted" (replication).
     *  Answered on the ack channel (OK stored, FAIL refused, UNKNOWN not a pool relay). */
    public static final int MSG_DIR_PUBLISH = 202;

    // ---- Liveness cadence (one place; both roles share it) ----
    /** Send a keep-alive down every held connection this often. Well under the
     *  reference's 10-min read-silence disconnect AND common NAT idle timers. */
    public static final long KEEPALIVE_INTERVAL_MS = 120_000;
    /** A held connection silent (no inbound frame) longer than this is a dead
     *  black-hole (NAT dropped the mapping, no RST) - reap it. ~4 missed
     *  keep-alives; still inside the reference's 10-min tolerance. */
    public static final long SILENCE_DROP_MS = 480_000;
    /** How often the liveness sweep runs (keep-alive + stale check). */
    public static final long LIVENESS_SWEEP_MS = 120_000;

    /** Reference: NIOClient.MAX_MESSAGE */
    public static final int MAX_FRAME = 256 * 1024 * 1024;

    // ---- Maxima ack status bytes ----
    public static final int RESPONSE_FAIL = 0x00;
    public static final int RESPONSE_OK = 0x01;
    public static final int RESPONSE_UNKNOWN = 0x02;
    public static final int RESPONSE_TOOBIG = 0x03;
    public static final int RESPONSE_WRONGHASH = 0x04;

    private Frame() {
    }

    /**
     * Build a frame body: the type byte followed by the serialised object.
     * Reference: NIOManager.createNIOMessage.
     */
    public static byte[] body(int zType, Streamable zObj) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            new MiniByte(zType).writeDataStream(dos);
            zObj.writeDataStream(dos);
            dos.flush();
            dos.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Frame body build failed", e);
        }
    }

    /**
     * A Maxima ack: MSG_PING carrying a single status byte.
     * Senders byte-compare the whole body, so this must be exact.
     */
    public static byte[] ack(int zStatus) {
        return body(MSG_PING, new MiniData(new byte[]{(byte) zStatus}));
    }

    /**
     * A connectivity keep-alive. Payload mirrors the reference exactly: a single
     * MiniData of {@code 0x00} (NIOManager.sendPingMessage sends ZERO_TXPOWID).
     */
    public static byte[] singlePing() {
        return body(MSG_SINGLE_PING, new MiniData("0x00"));
    }

    /**
     * The reply to a SINGLE_PING: a Greeting, exactly as the reference answers
     * (NIOMessage.java:1105). Pass a comms-only greeting - a chainless node
     * legitimately replies with topBlock -1 and the reference accepts it.
     */
    public static byte[] singlePong(
            com.eurobuddha.maxima.core.msg.Greeting zGreeting) {
        return body(MSG_SINGLE_PONG, zGreeting);
    }

    /** Write a complete frame (length prefix + body) to a stream. */
    public static void write(DataOutputStream zOut, byte[] zBody) throws IOException {
        zOut.writeInt(zBody.length);
        zOut.write(zBody);
        zOut.flush();
    }

    /** Read one complete frame body (length prefix consumed) from a stream. */
    public static byte[] read(DataInputStream zIn) throws IOException {
        int len = zIn.readInt();
        if (len > MAX_FRAME) {
            throw new IOException("Message too big for read: " + len);
        }
        if (len < 0) {
            throw new IOException("Negative frame length: " + len);
        }
        // Incremental read: a frame header can claim 256 MB and deliver a few
        // KB. Allocate with the bytes, not the claim. See Reads.
        return com.eurobuddha.maxima.core.codec.Reads.exact(zIn, len);
    }

    /**
     * Read one frame, but DISCARD it without allocating if it is larger than
     * {@code zMaxKeep}.
     *
     * A peer will happily push a multi-megabyte IBD at us right after the
     * greeting. We have no use for chain data, but we must still consume those
     * bytes or the stream desynchronises and every later frame is garbage. This
     * skips them in fixed-size chunks so a huge IBD costs no memory.
     *
     * @return the frame body, or null if it was skipped
     */
    public static byte[] readOrSkip(DataInputStream zIn, int zMaxKeep) throws IOException {
        int len = zIn.readInt();
        if (len > MAX_FRAME) {
            throw new IOException("Message too big for read: " + len);
        }
        if (len < 0) {
            throw new IOException("Negative frame length: " + len);
        }
        if (len <= zMaxKeep) {
            byte[] body = new byte[len];
            zIn.readFully(body);
            return body;
        }
        // Too big to care about - consume and drop.
        byte[] chunk = new byte[8192];
        int remaining = len;
        while (remaining > 0) {
            int want = Math.min(chunk.length, remaining);
            zIn.readFully(chunk, 0, want);
            remaining -= want;
        }
        return null;
    }

    /** The type byte of a frame body. */
    public static int typeOf(byte[] zBody) {
        if (zBody.length < 1) {
            throw new IllegalArgumentException("Empty frame body");
        }
        return zBody[0] & 0xFF;
    }
}
