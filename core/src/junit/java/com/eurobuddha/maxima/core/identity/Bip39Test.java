package com.eurobuddha.maxima.core.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.eurobuddha.maxima.core.codec.MiniData;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Seed derivation - FUND-CRITICAL. Minima's seed is SHA3-256 of the space-
 *  joined UPPERCASE words: NO PBKDF2, no salt, no checksum in the seed. Get this
 *  wrong by one transformation and every derived key - and every coin under it -
 *  moves to a different, unrecoverable wallet. */
public class Bip39Test {

    /** The canonical zero-entropy BIP39 vector (11x abandon + about). */
    private static final List<String> ABANDON = Arrays.asList(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about");

    @Test
    public void wordlistIs2048() {
        assertEquals(2048, Bip39.words().size());
    }

    @Test
    public void canonicalZeroEntropyVectorChecksumValid() {
        assertTrue("the standard all-abandon+about mnemonic must pass checksum",
                Bip39.checksumValid(ABANDON));
    }

    @Test
    public void oneWordChangeBreaksChecksum() {
        List<String> bad = new java.util.ArrayList<>(ABANDON);
        bad.set(11, "abandon");   // "about" carries the checksum; swap it out
        assertFalse(Bip39.checksumValid(bad));
    }

    @Test
    public void seedIsThirtyTwoBytesAndDeterministic() {
        MiniData s1 = Bip39.toSeed(ABANDON);
        MiniData s2 = Bip39.toSeed(ABANDON);
        assertEquals("SHA3-256 seed is 32 bytes", 32, s1.getLength());
        assertEquals("same phrase -> same seed", s1, s2);
    }

    @Test
    public void seedIsCaseIndependent() {
        // toSeed uppercases before hashing, so case in the input must not matter -
        // a user typing MixedCase must land on the SAME wallet.
        MiniData lower = Bip39.toSeed("abandon abandon abandon abandon abandon abandon "
                + "abandon abandon abandon abandon abandon about");
        MiniData upper = Bip39.toSeed("ABANDON ABANDON ABANDON ABANDON ABANDON ABANDON "
                + "ABANDON ABANDON ABANDON ABANDON ABANDON ABOUT");
        assertEquals("case in the phrase must not change the seed", lower, upper);
    }

    @Test
    public void differentPhraseDifferentSeed() {
        MiniData a = Bip39.toSeed(ABANDON);
        List<String> other = new java.util.ArrayList<>(ABANDON);
        other.set(0, "zoo");
        assertNotEquals(a, Bip39.toSeed(other));
    }

    @Test
    public void cleanSeedPhrasePrefixMatchesAndUppercases() {
        // 4+ char prefixes resolve to the unique full word; result is UPPERCASE.
        assertEquals("ABANDON ABILITY", Bip39.cleanSeedPhrase("aban abil"));
    }

    @Test
    public void cleanSeedPhraseRejectsUnknownAndTooShort() {
        try {
            Bip39.cleanSeedPhrase("abandon notaword");
            fail("unknown word must throw");
        } catch (IllegalArgumentException expected) { /* good */ }
        try {
            Bip39.cleanSeedPhrase("ab");
            fail("too-short token must throw");
        } catch (IllegalArgumentException expected) { /* good */ }
    }
}
