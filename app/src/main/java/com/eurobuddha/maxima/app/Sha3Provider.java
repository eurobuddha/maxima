package com.eurobuddha.maxima.app;

import android.util.Log;

import com.eurobuddha.maxima.core.crypto.Hashes;

import org.bouncycastle.crypto.digests.SHA3Digest;

/**
 * Supplies SHA3-256 on Android.
 *
 * Android has no SHA3-256 in its default providers - still true at API 36, as
 * two Samsung devices demonstrated by crashing. The obvious fix,
 * {@code Security.addProvider(new BouncyCastleProvider())} then
 * {@code getInstance("SHA3-256","BC")}, DOES NOT WORK: Android already ships a
 * cut-down provider registered under the name "BC", and
 * {@code addProvider} silently does nothing when the name is taken. The lookup
 * then resolves to Android's version, which has no SHA3, and throws
 * {@code no such algorithm: SHA3-256 for provider BC}.
 *
 * So we skip JCE entirely and use Bouncy Castle's lightweight API directly -
 * which is exactly what Minima's own {@code Crypto.hashData()} does with
 * {@code new SHA3Digest(256)}.
 *
 * {@link Hashes#setSha3} known-answer-tests whatever we hand it, so a bad
 * implementation fails loudly here at startup rather than silently producing a
 * wrong identity.
 */
public final class Sha3Provider {

    private static final String TAG = "Sha3Provider";
    private static boolean sInstalled;

    private Sha3Provider() {
    }

    public static synchronized void install() {
        if (sInstalled) {
            return;
        }

        // Bouncy Castle lightweight API - no provider registration involved.
        Hashes.setSha3(data -> {
            SHA3Digest d = new SHA3Digest(256);
            d.update(data, 0, data.length);
            byte[] out = new byte[d.getDigestSize()];
            d.doFinal(out, 0);
            return out;
        });

        sInstalled = true;
        Log.i(TAG, "SHA3-256 installed via Bouncy Castle lightweight API (known-answer verified)");
    }
}
