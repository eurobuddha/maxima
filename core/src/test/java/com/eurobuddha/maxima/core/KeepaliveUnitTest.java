package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * The keep-alive frame primitive.
 *
 * A quiet Maxima connection is torn down by the reference after 10 min of
 * read-silence (NIOClient stamps its read-clock on every inbound frame). Our
 * keep-alive is a SINGLE_PING that the far side answers with a SINGLE_PONG,
 * keeping that clock fresh. These frames must match the reference on the wire:
 * a SINGLE_PING carries {@code ZERO_TXPOWID} (0x00), a SINGLE_PONG carries a
 * Greeting. ParityTest checks the codec bytes; this checks the frame shapes and
 * their round trip.
 */
public class KeepaliveUnitTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    public static void main(String[] args) throws Exception {
        System.out.println("=== KEEP-ALIVE FRAMES ===\n");

        // ---- SINGLE_PING ----
        byte[] ping = Frame.singlePing();
        if (Frame.typeOf(ping) == Frame.MSG_SINGLE_PING && Frame.MSG_SINGLE_PING == 11) {
            ok("singlePing type byte is MSG_SINGLE_PING (11)");
        } else {
            bad("singlePing type = " + Frame.typeOf(ping));
        }
        // payload after the type byte must be the reference's ZERO_TXPOWID
        byte[] pingPayload = new byte[ping.length - 1];
        System.arraycopy(ping, 1, pingPayload, 0, pingPayload.length);
        MiniData pingData = com.eurobuddha.maxima.core.codec.Codec
                .deserialise(new MiniData(), pingPayload);
        if (pingData.to0xString().equals(new MiniData("0x00").to0xString())) {
            ok("singlePing payload is ZERO_TXPOWID (0x00), byte-parity with the reference");
        } else {
            bad("singlePing payload = " + pingData.to0xString());
        }

        // ---- SINGLE_PONG ----
        Greeting g = Greeting.commsOnly("1.0.48", null, 9001);
        byte[] pong = Frame.singlePong(g);
        if (Frame.typeOf(pong) == Frame.MSG_SINGLE_PONG && Frame.MSG_SINGLE_PONG == 12) {
            ok("singlePong type byte is MSG_SINGLE_PONG (12)");
        } else {
            bad("singlePong type = " + Frame.typeOf(pong));
        }
        byte[] pongPayload = new byte[pong.length - 1];
        System.arraycopy(pong, 1, pongPayload, 0, pongPayload.length);
        Greeting back = Greeting.fromBytes(pongPayload);
        if (back.getExtraData().contains("\"welcome\":\"Maxima\"")
                && back.getTopBlock().getAsLong() == -1) {
            ok("singlePong carries a comms-only Greeting (welcome:Maxima, topBlock -1)");
        } else {
            bad("singlePong greeting = " + back.getExtraData()
                    + " topBlock=" + back.getTopBlock().getAsLong());
        }

        // ---- full frame round trip through the length-prefixed wire form ----
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        Frame.write(out, ping);
        Frame.write(out, pong);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        byte[] rp = Frame.read(in);
        byte[] rq = Frame.read(in);
        if (Frame.typeOf(rp) == Frame.MSG_SINGLE_PING && Frame.typeOf(rq) == Frame.MSG_SINGLE_PONG) {
            ok("both frames round-trip through Frame.write / Frame.read in order");
        } else {
            bad("round-trip types: " + Frame.typeOf(rp) + ", " + Frame.typeOf(rq));
        }

        // ---- cadence constants are sane relative to the reference's 10-min drop ----
        if (Frame.KEEPALIVE_INTERVAL_MS < 600_000
                && Frame.SILENCE_DROP_MS < 600_000
                && Frame.KEEPALIVE_INTERVAL_MS < Frame.SILENCE_DROP_MS) {
            ok("keep-alive interval < silence-drop < the reference's 10-min disconnect");
        } else {
            bad("cadence: keepalive=" + Frame.KEEPALIVE_INTERVAL_MS
                    + " drop=" + Frame.SILENCE_DROP_MS);
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Keep-alive frames hold.");
    }
}
