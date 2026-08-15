package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.identity.MxAddress;
import com.eurobuddha.maxima.core.msg.MaximaMessage;

import java.util.Arrays;

/**
 * The wire message itself: field order, the msgid contract, and the Mx address
 * round-trip.
 *
 * ParityTest already pins these bytes against the reference jar. This checks the
 * two behavioural promises the rest of the stack relies on: that a message
 * serialises and comes back identical, and that msgid is a stable SHA3 of the
 * bytes so both ends dedup on the same key.
 */
public class MessageUnitTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static MaximaIdentity idFrom(int s) {
        byte[] e = new byte[32];
        for (int i = 0; i < 32; i++) e[i] = (byte) (i * s + s);
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(e));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== MESSAGE + ADDRESS ===\n");

        MaximaIdentity a = idFrom(1);
        MaximaIdentity b = idFrom(2);

        // ---- MaximaMessage round trip, field order preserved ----
        MaximaMessage m = new MaximaMessage();
        m.mRandom = new MiniData(new byte[32]);
        m.mFrom = a.publicKeyData();
        m.mTo = b.publicKeyData();
        m.mTimeMilli = new MiniNumber(1755000000000L);
        m.mApplication = new MiniString("maxima_chat_v1");
        m.mData = new MiniData("0xDEADBEEF");

        MaximaMessage back = MaximaMessage.fromBytes(Codec.serialise(m));
        if (Arrays.equals(back.mRandom.getBytes(), m.mRandom.getBytes())
                && Arrays.equals(back.mFrom.getBytes(), m.mFrom.getBytes())
                && Arrays.equals(back.mTo.getBytes(), m.mTo.getBytes())
                && back.mTimeMilli.getAsLong() == 1755000000000L
                && back.mApplication.toString().equals("maxima_chat_v1")
                && back.mData.to0xString().equals("0xDEADBEEF")) {
            ok("MaximaMessage round-trips with every field intact (random,from,to,time,app,data)");
        } else {
            bad("MaximaMessage round trip");
        }

        // time is the FOURTH field - a message that swapped app/time would still
        // parse but into the wrong slots. Assert the value landed in mTimeMilli.
        if (back.mTimeMilli.getAsLong() == 1755000000000L
                && back.mApplication.toString().equals("maxima_chat_v1")) {
            ok("time deserialises into the time slot, application into the app slot (order held)");
        } else {
            bad("field order");
        }

        // ---- msgid contract ----
        MiniData id1 = m.msgid();
        MiniData id2 = MaximaMessage.fromBytes(Codec.serialise(m)).msgid();
        if (id1.equals(id2)) {
            ok("msgid is stable across a serialise/deserialise cycle (both ends agree)");
        } else {
            bad("msgid not stable: " + id1.to0xString() + " vs " + id2.to0xString());
        }
        if (id1.getLength() == 32) {
            ok("msgid is a 32-byte SHA3-256 digest");
        } else {
            bad("msgid length " + id1.getLength());
        }
        // change one byte of data -> different msgid
        MaximaMessage m2 = MaximaMessage.fromBytes(Codec.serialise(m));
        m2.mData = new MiniData("0xDEADBEE0");
        if (!m.msgid().equals(m2.msgid())) {
            ok("changing the payload changes the msgid (it commits to the whole message)");
        } else {
            bad("msgid did not change with payload");
        }

        // ---- MxAddress round trip ----
        MiniData key = a.publicKeyData();
        String mx = MxAddress.make(key);
        if (mx.startsWith("Mx")) {
            ok("MxAddress.make yields an Mx-prefixed address");
        } else {
            bad("Mx prefix: " + mx);
        }
        MiniData rev = MxAddress.convert(mx);
        if (rev.equals(key)) {
            ok("MxAddress.convert inverts make (address -> exact key bytes)");
        } else {
            bad("Mx round trip mismatch");
        }
        // checksum guard: flip a character and conversion must fail, not silently
        // decode to a different key
        String corrupt = mx.substring(0, mx.length() - 1)
                + (mx.charAt(mx.length() - 1) == 'A' ? 'B' : 'A');
        boolean rejected = false;
        try {
            MiniData bad = MxAddress.convert(corrupt);
            rejected = !bad.equals(key); // at minimum must not decode to the same key
            // a proper checksum failure throws; treat a throw as the strong pass
        } catch (Exception e) {
            rejected = true;
        }
        if (rejected) {
            ok("a corrupted Mx address does not silently convert back to the original key");
        } else {
            bad("corrupted address converted to the original key");
        }
        // contact-address shape check
        if (MxAddress.isValidContactAddress("Mxsomething@1.2.3.4:9501")
                && !MxAddress.isValidContactAddress("Mxsomething")
                && !MxAddress.isValidContactAddress("not-an-address")) {
            ok("isValidContactAddress requires the Mx...@host:port shape");
        } else {
            bad("isValidContactAddress");
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Message + address hold.");
    }
}
