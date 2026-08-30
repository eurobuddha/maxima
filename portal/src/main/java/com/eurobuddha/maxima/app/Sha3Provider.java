package com.eurobuddha.maxima.app;

import android.util.Log;

import com.eurobuddha.maxima.core.crypto.Hashes;

import org.bouncycastle.crypto.digests.SHA3Digest;

/**
 * Supplies SHA3-256 on Android — copied verbatim from the Parlons app. Android has no SHA3-256 in
 * its default providers (still true at API 36), and registering BouncyCastle under the JCE name
 * "BC" silently fails because Android already occupies that name. So we skip JCE and hand Minima's
 * {@link Hashes} the lightweight SHA3Digest directly. {@link Hashes#setSha3} known-answer-tests it,
 * so a bad impl fails loudly at startup rather than minting a wrong identity.
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
