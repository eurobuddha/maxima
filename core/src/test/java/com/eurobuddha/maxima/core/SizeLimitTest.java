package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.MaximaPackage;

/**
 * THE ACTUAL PAYLOAD CEILING, measured rather than estimated.
 *
 * Classic drops anything whose serialised MaximaPackage exceeds 262144 bytes
 * and replies TOOBIG (NIOMessage.java:1016-1020). That is the ceiling on the
 * WRAPPER, not on your data: signature, two 162-byte public keys, an RSA-
 * wrapped AES key, an IV and CBC padding all live inside it.
 *
 * So "how big a file can I send" has an exact answer, and the only honest way
 * to get it is to build real messages and binary-search the boundary.
 */
public class SizeLimitTest {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static MaximaIdentity idFrom(int zSalt) {
        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) {
            ent[i] = (byte) (i * zSalt + zSalt);
        }
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(ent));
    }

    /** Serialised size of a real sealed package carrying zPayload bytes. */
    static int wireSize(MaximaIdentity zFrom, MaximaIdentity zTo,
                        String zApplication, int zPayload) throws Exception {
        byte[] data = new byte[zPayload];
        // build() refuses oversize, which is the behaviour we want in
        // production and an obstacle to measuring - report "over" instead.
        try {
            MaximaSender.Built b = MaximaSender.build(
                    zFrom.publicKey(), zFrom.keyPair().getPrivate(),
                    zTo.publicKey(), zApplication, data, 1755000000000L);
            return com.eurobuddha.maxima.core.codec.Codec.serialise(b.unit.mMaxima).length;
        } catch (IllegalArgumentException tooBig) {
            // "MaximaPackage too big: N > LIMIT" - recover the real N so the
            // boundary either side of the limit is still exact.
            String m = tooBig.getMessage();
            java.util.regex.Matcher mm =
                    java.util.regex.Pattern.compile("(\\d+) > ").matcher(m == null ? "" : m);
            return mm.find() ? Integer.parseInt(mm.group(1)) : Integer.MAX_VALUE;
        }
    }

    private static int unused(MaximaIdentity zFrom, MaximaIdentity zTo,
                              String zApplication, byte[] data) throws Exception {
        MaximaSender.Built built = MaximaSender.build(
                zFrom.publicKey(), zFrom.keyPair().getPrivate(),
                zTo.publicKey(), zApplication, data, 1755000000000L);
        return com.eurobuddha.maxima.core.codec.Codec.serialise(built.unit.mMaxima).length;
    }

    public static void main(String[] args) throws Exception {
        MaximaIdentity me = idFrom(41);
        MaximaIdentity them = idFrom(43);

        System.out.println("=== MAXIMA PAYLOAD CEILING ===");
        System.out.println("wrapper limit: " + MaximaPackage.MAX_SIZE + " bytes"
                + " (classic replies TOOBIG above this)\n");

        // Overhead is not constant: the application string is inside the
        // signed message, and AES-CBC pads to a 16-byte boundary.
        for (String app : new String[]{"", "maxima_chat_v1", "maxima_contact_ctrl"}) {
            int empty = wireSize(me, them, app, 0);
            System.out.printf("  application \"%s\"%n", app);
            System.out.printf("      overhead with no payload : %d bytes%n", empty);

            // Binary search the largest payload that still fits.
            int lo = 0;
            int hi = MaximaPackage.MAX_SIZE;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (wireSize(me, them, app, mid) <= MaximaPackage.MAX_SIZE) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            int max = lo;
            int atMax = wireSize(me, them, app, max);
            int justOver = wireSize(me, them, app, max + 1);

            System.out.printf("      LARGEST PAYLOAD          : %d bytes (%.1f KB)%n",
                    max, max / 1024.0);
            System.out.printf("      wire at that size        : %d  (limit %d)%n",
                    atMax, MaximaPackage.MAX_SIZE);
            System.out.printf("      one byte more            : %d  -> TOOBIG%n%n", justOver);

            if (atMax <= MaximaPackage.MAX_SIZE && justOver > MaximaPackage.MAX_SIZE) {
                ok("boundary is exact for \"" + app + "\": " + max + " bytes");
            } else {
                bad("boundary wrong for \"" + app + "\"");
            }
        }

        // The sender must refuse rather than let a peer answer TOOBIG - a
        // rejection you discover locally is worth far more than one you
        // discover after a round trip.
        try {
            MaximaSender.build(me.publicKey(), me.keyPair().getPrivate(),
                    them.publicKey(), "maxima_chat_v1",
                    new byte[MaximaPackage.MAX_SIZE], 1755000000000L);
            bad("an oversized message was built instead of refused");
        } catch (Exception e) {
            ok("oversized is refused locally: " + e.getMessage());
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
