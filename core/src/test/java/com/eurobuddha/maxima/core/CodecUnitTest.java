package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.Hex;
import com.eurobuddha.maxima.core.codec.MiniByte;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Reads;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Every codec primitive, round-tripped and edge-cased.
 *
 * The codec is the floor the whole protocol stands on: if a MiniNumber or a
 * MiniData does not serialise identically to the reference, we are silently
 * invisible on the wire. ParityTest checks the bytes against the reference jar;
 * this checks the Java behaviour - round trips, boundaries, and the failures
 * that must fail.
 */
public class CodecUnitTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static byte[] ser(com.eurobuddha.maxima.core.codec.Streamable s) {
        return Codec.serialise(s);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== CODEC PRIMITIVES ===\n");

        // ---- Hex ----
        if (Hex.encode(new byte[]{0x00, (byte) 0xAB, (byte) 0xFF}).equals("0x00ABFF")) {
            ok("Hex.encode is uppercase 0x-prefixed");
        } else {
            bad("Hex.encode: " + Hex.encode(new byte[]{0x00, (byte) 0xAB, (byte) 0xFF}));
        }
        if (Arrays.equals(Hex.decode("0x00ABFF"), new byte[]{0x00, (byte) 0xAB, (byte) 0xFF})
                && Arrays.equals(Hex.decode("00abff"), new byte[]{0x00, (byte) 0xAB, (byte) 0xFF})) {
            ok("Hex.decode accepts 0x-prefixed and bare, upper and lower");
        } else {
            bad("Hex.decode round trip");
        }
        if (Hex.encode(new byte[0]).equals("") && Arrays.equals(Hex.decode(""), new byte[0])) {
            ok("Hex handles empty as \"\"");
        } else {
            bad("Hex empty: '" + Hex.encode(new byte[0]) + "'");
        }

        // ---- MiniData ----
        byte[] raw = "hello é \t\n".getBytes("UTF-8");
        MiniData md = new MiniData(raw);
        MiniData md2 = Codec.deserialise(new MiniData(), ser(md));
        if (Arrays.equals(md2.getBytes(), raw) && md2.getLength() == raw.length) {
            ok("MiniData round-trips arbitrary bytes incl. control chars");
        } else {
            bad("MiniData round trip");
        }
        MiniData empty = Codec.deserialise(new MiniData(), ser(new MiniData(new byte[0])));
        if (empty.getLength() == 0) {
            ok("MiniData round-trips empty");
        } else {
            bad("MiniData empty");
        }
        // hex construction
        if (new MiniData("0xDEADBEEF").to0xString().equals("0xDEADBEEF")) {
            ok("MiniData(hex).to0xString round-trips");
        } else {
            bad("MiniData hex: " + new MiniData("0xDEADBEEF").to0xString());
        }
        // equality + hashCode
        if (new MiniData("0xAB").equals(new MiniData(new byte[]{(byte) 0xAB}))
                && new MiniData("0xAB").hashCode() == new MiniData("0xAB").hashCode()) {
            ok("MiniData equals/hashCode by content");
        } else {
            bad("MiniData equality");
        }

        // ---- MiniString ----
        for (String s : new String[]{"", "maxima_chat_v1", "a\tb\nc", "é中文"}) {
            MiniString ms = new MiniString(s);
            MiniString back = Codec.deserialise(new MiniString(""), ser(ms));
            if (!back.toString().equals(s)) {
                bad("MiniString round trip failed for: " + s);
            }
        }
        ok("MiniString round-trips empty, ascii, tabs/newlines and unicode");

        // ---- MiniByte ----
        for (int v : new int[]{0, 1, 8, 10, 127, 255}) {
            MiniByte b = Codec.deserialise(new MiniByte(), ser(new MiniByte(v)));
            if (b.getAsInt() != v) {
                bad("MiniByte round trip " + v + " -> " + b.getAsInt());
            }
        }
        if (new MiniByte(true).getAsInt() == 1 && new MiniByte(false).getAsInt() == 0) {
            ok("MiniByte round-trips 0..255 and boolean");
        } else {
            bad("MiniByte boolean");
        }

        // ---- MiniNumber ----
        long[] longs = {0, 1, -1, 42, 1_000_000, Long.MAX_VALUE, Long.MIN_VALUE + 1,
                1755000000000L};
        for (long l : longs) {
            MiniNumber n = Codec.deserialise(new MiniNumber(), ser(new MiniNumber(l)));
            if (n.getAsLong() != l) {
                bad("MiniNumber long round trip " + l + " -> " + n.getAsLong());
            }
        }
        ok("MiniNumber round-trips 0, +/-, and epoch-millis-scale longs");
        // decimals + BigInteger
        MiniNumber dec = Codec.deserialise(new MiniNumber(),
                ser(new MiniNumber(new java.math.BigDecimal("123.456"))));
        if (dec.toString().equals("123.456")) {
            ok("MiniNumber round-trips a decimal");
        } else {
            bad("MiniNumber decimal: " + dec);
        }
        // largest allowed magnitude is 2^64; round-trip a big value just under it
        MiniNumber big = Codec.deserialise(new MiniNumber(),
                ser(new MiniNumber(new BigInteger("12345678901234567890"))));
        if (big.toString().equals("12345678901234567890")) {
            ok("MiniNumber round-trips a 20-digit BigInteger below the 2^64 ceiling");
        } else {
            bad("MiniNumber big: " + big);
        }
        // and anything above 2^64 must be refused, not silently wrapped
        try {
            new MiniNumber(new BigInteger("99999999999999999999999"));
            bad("MiniNumber accepted a value above the 2^64 ceiling");
        } catch (NumberFormatException e) {
            ok("MiniNumber rejects a value above the 2^64 ceiling");
        }

        // ---- Reads.exact: the allocation-amplification guard ----
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(new byte[]{1, 2, 3, 4, 5});
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        if (Arrays.equals(Reads.exact(in, 5), new byte[]{1, 2, 3, 4, 5})) {
            ok("Reads.exact reads exactly N bytes");
        } else {
            bad("Reads.exact basic");
        }
        // a claim larger than the stream must throw EOF, not allocate the claim
        DataInputStream shortIn = new DataInputStream(new ByteArrayInputStream(new byte[16]));
        boolean threw = false;
        try {
            Reads.exact(shortIn, 512 * 1024 * 1024);
        } catch (java.io.EOFException e) {
            threw = true;
        }
        if (threw) {
            ok("Reads.exact fails EOF on an over-large claim (no 512MB allocation)");
        } else {
            bad("Reads.exact did not fail on over-large claim");
        }
        try {
            Reads.exact(new DataInputStream(new ByteArrayInputStream(new byte[0])), -1);
            bad("Reads.exact accepted a negative length");
        } catch (java.io.IOException e) {
            ok("Reads.exact rejects a negative length");
        }

        // ---- MiniData length guards ----
        // negative length prefix must be rejected
        ByteArrayOutputStream neg = new ByteArrayOutputStream();
        new DataOutputStream(neg).writeInt(-5);
        try {
            Codec.deserialise(new MiniData(), neg.toByteArray());
            bad("MiniData accepted a negative length prefix");
        } catch (java.io.IOException e) {
            ok("MiniData rejects a negative length prefix");
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Codec primitives hold.");
    }
}
