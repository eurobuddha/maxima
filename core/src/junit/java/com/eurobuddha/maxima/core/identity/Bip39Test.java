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

    /** A node stores a custom (-seed / -anyseed) phrase verbatim and hashes THAT; a BIP39 phrase is
     *  stored cleaned + uppercase. Verified live on a minimaCore 1.1.2.3 vault (2026-09-07). */
    @Test
    public void nodeSeedRuleMatchesTheNodeForBothKindsOfPhrase() {
        String bip = String.join(" ", ABANDON);
        assertEquals(Bip39.toSeed(bip), Bip39.toNodeSeed(bip));
        assertEquals("lower/upper BIP39 are one phrase", Bip39.toNodeSeed(bip), Bip39.toNodeSeed(bip.toUpperCase()));
        String custom = "amsterdamdreams478720264787";
        assertFalse(Bip39.isBip39(custom));
        assertTrue(Bip39.isBip39(bip));
        MiniData raw = new MiniData(com.eurobuddha.maxima.core.crypto.Hashes.sha3(
                new com.eurobuddha.maxima.core.codec.MiniString(custom).getData()));
        assertEquals("custom phrase: SHA3 of the text verbatim", raw, Bip39.toNodeSeed(custom));
        assertNotEquals("NOT of its uppercase", Bip39.toNodeSeed(custom), Bip39.toNodeSeed(custom.toUpperCase()));
        // an identity file may hold the seed hex itself
        MaximaIdentity fromHex = MaximaIdentity.fromNodeSecret(raw.to0xString());
        assertEquals(MaximaIdentity.fromNodePhrase(custom).seed(), fromHex.seed());
        assertTrue(MaximaIdentity.isSeedHex(raw.to0xString()));
        assertFalse(MaximaIdentity.isSeedHex(custom));
        assertEquals(MaximaIdentity.fromPhrase(bip).seed(), MaximaIdentity.fromNodeSecret(bip).seed());
    }

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
