package com.eurobuddha.maxima.app;

import com.eurobuddha.maxima.core.crypto.Hashes;

import java.security.MessageDigest;
import java.security.Security;

/**
 * Android below API 29 has no SHA3-256 in its default provider, and SHA3 is
 * load-bearing here (msgid, the carrier binding, the Mx checksum). :core takes
 * a digest supplier rather than a hard dependency, so we inject Bouncy Castle.
 *
 * Any JVM library on Android needs on-device verification, not just a desktop
 * test run - see apks/base/H2_VERIFYERROR_FIX.md, where a Samsung bytecode
 * verifier rejected H2 outright and killed the node at boot.
 */
public final class Sha3Provider {

    private static boolean sInstalled;

    private Sha3Provider() {
    }

    public static synchronized void install() {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            MessageDigest.getInstance("SHA3-256");
            return; // platform already has it (API 29+)
        } catch (Exception ignored) {
            // fall through
        }
        try {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            Hashes.setSha3Supplier(() -> {
                try {
                    return MessageDigest.getInstance("SHA3-256", "BC");
                } catch (Exception e) {
                    throw new IllegalStateException("BC SHA3-256 unavailable", e);
                }
            });
        } catch (Throwable t) {
            throw new IllegalStateException("No SHA3-256 available on this device", t);
        }
    }
}
