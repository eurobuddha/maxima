package com.eurobuddha.maxima.core.identity;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.crypto.Hashes;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * Seed phrases, Minima-compatible.
 *
 * TWO DIFFERENT THINGS LIVE HERE, and conflating them silently breaks identity:
 *
 *   1. STANDARD BIP39 - the 2048-word list, 11 bits per word, and a real
 *      SHA-256 checksum. We use this to GENERATE and to typo-check.
 *
 *   2. MINIMA'S SEED DERIVATION - which is NOT BIP39 despite the name of its
 *      class. It borrows only the wordlist:
 *
 *          seed = SHA3-256( UTF-8 of the space-joined UPPERCASE words )
 *
 *      No PBKDF2, no "mnemonic" salt, no iterations, no checksum.
 *      (core/Minima .../utils/BIP39.java:42-54)
 *
 * We deliberately generate a CHECKSUMMED phrase and then derive the Minima way.
 * Minima never validates a checksum - it only hashes the words - so a
 * checksummed phrase is simply a valid wordlist phrase that restores on a node
 * perfectly, while giving the user typo protection that Minima's own generator
 * lacks (it picks 24 words independently, so a mistyped phrase silently yields
 * a different identity).
 *
 * Wordlist and checksum logic ported from apks/entropy (Bip39.java,
 * LastWordView.java); the file is byte-identical to core/Minima's WORD_LIST.
 */
public final class Bip39 {

    public static final int WORDLIST_SIZE = 2048;
    public static final int BITS_PER_WORD = 11;

    private static List<String> WORDS;
    private static Map<String, Integer> INDEX;

    private Bip39() {
    }

    private static synchronized void load() {
        if (WORDS != null) {
            return;
        }
        List<String> words = new ArrayList<>(WORDLIST_SIZE);
        try (InputStream in = Bip39.class.getResourceAsStream("/bip39_english.txt")) {
            if (in == null) {
                throw new IllegalStateException("bip39_english.txt missing from resources");
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                String w = line.trim();
                if (!w.isEmpty()) {
                    words.add(w);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("BIP39 wordlist unreadable", e);
        }
        if (words.size() != WORDLIST_SIZE) {
            throw new IllegalStateException("wordlist has " + words.size() + " words, expected " + WORDLIST_SIZE);
        }
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < words.size(); i++) {
            idx.put(words.get(i), i);
        }
        WORDS = Collections.unmodifiableList(words);
        INDEX = Collections.unmodifiableMap(idx);
    }

    public static List<String> words() {
        load();
        return WORDS;
    }

    public static int wordIndex(String zWord) {
        load();
        Integer i = INDEX.get(zWord);
        return i == null ? -1 : i;
    }

    // ---------------------------------------------------------------
    // 1. Standard BIP39 - generation and checksum
    // ---------------------------------------------------------------

    /**
     * Generate a standard checksummed phrase.
     *
     * @param zWords 12, 15, 18, 21 or 24
     */
    public static List<String> generate(int zWords) {
        load();
        int ent = entropyBits(zWords);
        if (ent < 0) {
            throw new IllegalArgumentException("Bad word count: " + zWords);
        }
        byte[] entropy = new byte[ent / 8];
        new SecureRandom().nextBytes(entropy);
        return fromEntropy(entropy);
    }

    /** Entropy bits for a word count, or -1 if the count is invalid. */
    public static int entropyBits(int zWords) {
        switch (zWords) {
            case 12: return 128;
            case 15: return 160;
            case 18: return 192;
            case 21: return 224;
            case 24: return 256;
            default: return -1;
        }
    }

    /** entropy -> checksummed word list (standard BIP39). */
    public static List<String> fromEntropy(byte[] zEntropy) {
        load();
        int entBits = zEntropy.length * 8;
        if (entropyBitsToWordCount(entBits) < 0) {
            throw new IllegalArgumentException("Bad entropy length: " + zEntropy.length);
        }
        int csBits = entBits / 32;
        String bits = bytesToBits(zEntropy, entBits)
                + bytesToBits(sha256(zEntropy), csBits);

        List<String> out = new ArrayList<>();
        for (int i = 0; i < bits.length() / BITS_PER_WORD; i++) {
            int ix = Integer.parseInt(bits.substring(i * BITS_PER_WORD, (i + 1) * BITS_PER_WORD), 2);
            out.add(WORDS.get(ix));
        }
        return out;
    }

    private static int entropyBitsToWordCount(int zEntBits) {
        switch (zEntBits) {
            case 128: return 12;
            case 160: return 15;
            case 192: return 18;
            case 224: return 21;
            case 256: return 24;
            default: return -1;
        }
    }

    /**
     * Standard BIP39 checksum validation.
     *
     * NOTE: a phrase generated by Minima's own {@code getNewWordList()} picks 24
     * words independently and will almost always FAIL this. Treat a failure as a
     * warning on import, never a hard rejection.
     */
    public static boolean checksumValid(List<String> zPhrase) {
        load();
        int n = zPhrase.size();
        if (entropyBits(n) < 0) {
            return false;
        }
        StringBuilder bits = new StringBuilder(n * BITS_PER_WORD);
        for (String w : zPhrase) {
            int ix = wordIndex(w.toLowerCase());
            if (ix < 0) {
                return false;
            }
            String b = Integer.toBinaryString(ix);
            for (int i = b.length(); i < BITS_PER_WORD; i++) {
                bits.append('0');
            }
            bits.append(b);
        }
        int ent = n * BITS_PER_WORD * 32 / 33;
        String entBits = bits.substring(0, ent);
        String claimed = bits.substring(ent);
        String actual = bytesToBits(sha256(bitsToBytes(entBits)), n * BITS_PER_WORD - ent);
        return claimed.equals(actual);
    }

    // ---------------------------------------------------------------
    // 2. Minima's normalisation and seed derivation
    // ---------------------------------------------------------------

    /**
     * Minima's {@code cleanSeedPhrase}, reproduced exactly.
     *
     * Lowercase each token; reject anything under 3 characters; words of 4+
     * characters match a list entry by {@code startsWith} (BIP39 words are
     * unique after 4 letters - verified: zero ambiguous 4-char prefixes);
     * shorter tokens must match exactly. Result is space-joined and UPPERCASED.
     *
     * @throws IllegalArgumentException on any unknown or too-short word
     */
    public static String cleanSeedPhrase(String zPhrase) {
        load();
        StringBuilder out = new StringBuilder();
        StringTokenizer tok = new StringTokenizer(zPhrase.trim(), " ");
        while (tok.hasMoreTokens()) {
            String t = tok.nextToken().toLowerCase();
            if (t.length() < 3) {
                throw new IllegalArgumentException("Word too short: " + t);
            }
            String found = null;
            if (t.length() < 4) {
                for (String w : WORDS) {
                    if (t.equals(w)) {
                        found = w;
                        break;
                    }
                }
            } else {
                for (String w : WORDS) {
                    if (w.startsWith(t)) {
                        found = w;
                        break;
                    }
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("Unknown BIP39 word: " + t);
            }
            out.append(found).append(' ');
        }
        return out.toString().trim().toUpperCase();
    }

    /**
     * THE seed a Minima node derives from this phrase.
     * {@code SHA3-256(UTF-8 of the space-joined UPPERCASE words)}.
     */
    public static MiniData toSeed(String zPhrase) {
        String clean = cleanSeedPhrase(zPhrase);
        return new MiniData(Hashes.sha3(new MiniString(clean).getData()));
    }

    public static MiniData toSeed(List<String> zWords) {
        return toSeed(String.join(" ", zWords));
    }

    /** The canonical UPPERCASE space-joined form a node would store. */
    public static String canonical(List<String> zWords) {
        return cleanSeedPhrase(String.join(" ", zWords));
    }

    // ---------------------------------------------------------------
    // 3. Autocomplete (ported from entropy LastWordView.suggestionsFor)
    // ---------------------------------------------------------------

    /** Up to {@code zMax} words starting with the prefix. */
    public static List<String> suggestions(String zPrefix, int zMax) {
        load();
        List<String> out = new ArrayList<>();
        if (zPrefix == null || zPrefix.isEmpty()) {
            return out;
        }
        String p = zPrefix.toLowerCase();
        for (String w : WORDS) {
            if (w.startsWith(p)) {
                out.add(w);
                if (out.size() >= zMax) {
                    break;
                }
            }
        }
        return out;
    }

    public static List<String> suggestions(String zPrefix) {
        return suggestions(zPrefix, 8);
    }

    /**
     * The word this prefix unambiguously resolves to, or null.
     * Drives auto-commit in the UI: every BIP39 word is unique after 4 letters.
     */
    public static String unambiguous(String zPrefix) {
        List<String> s = suggestions(zPrefix, 2);
        return s.size() == 1 ? s.get(0) : null;
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    static byte[] sha256(byte[] zData) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(zData);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String bytesToBits(byte[] zBytes, int zBitCount) {
        StringBuilder sb = new StringBuilder(zBitCount);
        for (byte b : zBytes) {
            for (int i = 7; i >= 0; i--) {
                if (sb.length() >= zBitCount) {
                    return sb.toString();
                }
                sb.append((b >> i) & 1);
            }
        }
        return sb.toString();
    }

    private static byte[] bitsToBytes(String zBits) {
        byte[] out = new byte[zBits.length() / 8];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(zBits.substring(i * 8, i * 8 + 8), 2);
        }
        return out;
    }
}
