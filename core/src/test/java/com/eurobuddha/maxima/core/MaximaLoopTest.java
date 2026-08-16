package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

/**
 * The periodic Maxima loop's cheap guards.
 *
 * checkStaleMls() re-resolves only contacts we have not heard from for 30 min
 * AND for whom we know an MLS server. Those guards must short-circuit BEFORE any
 * network call, or a loop over many fresh/relay-less contacts would open a
 * socket per contact every cycle. This proves the guards without a live MLS (so
 * it is fast and deterministic); the resolve+merge path is exercised live.
 */
public final class MaximaLoopTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static MaximaIdentity idFrom(int salt) {
        byte[] e = new byte[32];
        for (int i = 0; i < 32; i++) e[i] = (byte) (i * salt + salt);
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(e));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== MAXIMA LOOP GUARDS ===\n");

        MaximaNode node = new MaximaNode(idFrom(5), "1.0.48", 1);
        long now = System.currentTimeMillis();

        // fresh contact WITH an MLS server: skipped because heard-from recently
        Contact fresh = new Contact(idFrom(21).publicKeyHex());
        fresh.mls = "MxSOMEKEY@198.51.100.1:9001";
        fresh.lastSeen = now;
        node.storeContact(fresh);

        // stale contact but NO MLS server: skipped because we cannot resolve it
        Contact noMls = new Contact(idFrom(22).publicKeyHex());
        noMls.mls = "";
        noMls.lastSeen = 0;   // ancient
        node.storeContact(noMls);

        // With only skippable contacts, checkStaleMls must do ZERO network work
        // and return 0 fast.
        long t0 = System.currentTimeMillis();
        int refreshed = node.checkStaleMls();
        long took = System.currentTimeMillis() - t0;

        if (refreshed == 0) {
            ok("checkStaleMls refreshed nothing when every contact is skippable");
        } else {
            bad("checkStaleMls refreshed " + refreshed + " (expected 0)");
        }
        if (took < 500) {
            ok("checkStaleMls opened no socket for a fresh or MLS-less contact (" + took + "ms)");
        } else {
            bad("checkStaleMls was slow (" + took + "ms) - a guard let a network call through");
        }

        // maximaLoop() with no hosts/contacts of substance must not throw
        try {
            node.maximaLoop();
            ok("maximaLoop() runs without throwing on a bare node");
        } catch (Exception e) {
            bad("maximaLoop threw: " + e);
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Maxima loop guards hold.");
    }
}
