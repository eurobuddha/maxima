import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

/**
 * SEED PARITY GATE.
 *
 * Identity portability rests entirely on our seed derivation matching Minima's
 * exactly. If it drifts, a user restores their node with the correct phrase,
 * gets a DIFFERENT Maxima identity, and nothing anywhere reports an error -
 * their contacts simply stop reaching them.
 *
 * So we do not trust our reading of BIP39.java. We run both implementations
 * side by side on the same phrases and compare bytes.
 *
 * Needs build/classes and minima.jar on the classpath.
 */
public class Bip39Check {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    public static void main(String[] args) throws Exception {

        System.out.println("Seed derivation parity: OUR Bip39 vs org.minima.utils.BIP39\n");

        // ---- the wordlists must be identical ----
        List<String> ours = Bip39.words();
        String[] theirs = org.minima.utils.BIP39.WORD_LIST;
        if (ours.size() == theirs.length && Arrays.equals(ours.toArray(new String[0]), theirs)) {
            ok("wordlists byte-identical (" + ours.size() + " words)");
        } else {
            bad("wordlist mismatch: ours=" + ours.size() + " theirs=" + theirs.length);
        }

        // ---- phrases to compare ----
        List<String[]> cases = new ArrayList<>();

        // 1. a phrase the REFERENCE generated (24 independent picks, no checksum)
        cases.add(org.minima.utils.BIP39.getNewWordList());

        // 2. a phrase WE generated (standard BIP39, checksummed)
        List<String> mine = Bip39.generate(24);
        cases.add(mine.toArray(new String[0]));

        // 3. shorter standard lengths
        cases.add(Bip39.generate(12).toArray(new String[0]));

        // 4. a hand-built phrase using SHORT words (<4 chars need exact match)
        cases.add(new String[]{"act", "add", "age", "aim", "air", "all", "any", "arm",
                "art", "ask", "bag", "bar", "bid", "box", "boy", "bus",
                "can", "car", "cat", "cry", "cup", "dad", "day", "dog"});

        // 5. mixed case + extra whitespace, to exercise normalisation
        cases.add(new String[]{"AbAnDoN", "ability", "ABLE", "about", "above", "absent",
                "absorb", "abstract", "absurd", "abuse", "access", "accident",
                "account", "accuse", "achieve", "acid", "acoustic", "acquire",
                "across", "act", "action", "actor", "actress", "actual"});

        String[] labels = {
                "reference-generated (no checksum)",
                "our checksummed 24-word",
                "our checksummed 12-word",
                "all short words (<4 chars)",
                "mixed case",
        };

        for (int i = 0; i < cases.size(); i++) {
            String[] words = cases.get(i);
            String joined = String.join(" ", words);

            // --- cleanSeedPhrase parity ---
            String ourClean, refClean;
            try {
                ourClean = Bip39.cleanSeedPhrase(joined);
            } catch (Exception e) {
                bad(labels[i] + ": our cleanSeedPhrase threw " + e.getMessage());
                continue;
            }
            try {
                refClean = org.minima.utils.BIP39.cleanSeedPhrase(joined);
            } catch (Exception e) {
                bad(labels[i] + ": reference cleanSeedPhrase threw " + e.getMessage());
                continue;
            }
            if (!ourClean.equals(refClean)) {
                bad(labels[i] + ": normalisation differs");
                System.out.println("      ours : " + ourClean);
                System.out.println("      ref  : " + refClean);
                continue;
            }

            // --- seed parity, both routes the reference offers ---
            String ourSeed = Bip39.toSeed(joined).to0xString();
            String refFromList = org.minima.utils.BIP39
                    .convertWordListToSeed(refClean.split(" ")).to0xString();
            String refFromString = org.minima.utils.BIP39
                    .convertStringToSeed(refClean).to0xString();

            boolean m1 = ourSeed.equals(refFromList);
            boolean m2 = ourSeed.equals(refFromString);

            if (m1 && m2) {
                ok(labels[i] + " -> identical seed " + ourSeed.substring(0, 22) + "...");
            } else {
                bad(labels[i] + ": SEED MISMATCH");
                System.out.println("      ours            : " + ourSeed);
                System.out.println("      convertWordList : " + refFromList);
                System.out.println("      convertString   : " + refFromString);
            }
        }

        // ---- a prefix-abbreviated phrase must resolve to the same seed ----
        // This is what makes 4-char entry safe, and it must match the reference.
        String[] full = Bip39.generate(24).toArray(new String[0]);
        StringBuilder abbrev = new StringBuilder();
        for (String w : full) {
            abbrev.append(w.length() >= 4 ? w.substring(0, 4) : w).append(' ');
        }
        String ourAb = Bip39.toSeed(abbrev.toString().trim()).to0xString();
        String ourFull = Bip39.toSeed(String.join(" ", full)).to0xString();
        String refAb = org.minima.utils.BIP39.convertStringToSeed(
                org.minima.utils.BIP39.cleanSeedPhrase(abbrev.toString().trim())).to0xString();
        if (ourAb.equals(ourFull) && ourAb.equals(refAb)) {
            ok("4-char abbreviated phrase yields the SAME seed as the full phrase");
        } else {
            bad("abbreviated phrase diverges: ours=" + ourAb.substring(0, 18)
                    + " full=" + ourFull.substring(0, 18) + " ref=" + refAb.substring(0, 18));
        }

        // ---- checksum behaviour ----
        if (Bip39.checksumValid(Bip39.generate(24))) {
            ok("our generated phrases pass the standard BIP39 checksum");
        } else {
            bad("our generated phrase fails its own checksum");
        }
        int refPass = 0;
        for (int i = 0; i < 20; i++) {
            if (Bip39.checksumValid(Arrays.asList(org.minima.utils.BIP39.getNewWordList()))) {
                refPass++;
            }
        }
        System.out.println("     reference-generated phrases passing a checksum: "
                + refPass + "/20 (expected ~0 - it has no checksum)");
        if (refPass <= 2) {
            ok("confirms Minima's own generator is unchecksummed -> import must WARN, not reject");
        } else {
            bad("unexpected: reference phrases are checksummed after all");
        }

        // ---- identity end to end ----
        MaximaIdentity id = MaximaIdentity.fromPhrase(String.join(" ", full));
        MaximaIdentity id2 = MaximaIdentity.fromPhrase(abbrev.toString().trim());
        if (Arrays.equals(id.publicKey(), id2.publicKey())) {
            ok("identity from full phrase == identity from abbreviated phrase");
        } else {
            bad("abbreviated phrase produced a different identity");
        }
        if (id.publicKey().length == 162) {
            ok("identity public key is 162 bytes");
        } else {
            bad("identity key is " + id.publicKey().length + " bytes");
        }

        String hp = "eurobuddha.com:9001";
        if (!Arrays.equals(id.publicKey(), id.hostKey(hp).getPublic().getEncoded())) {
            ok("per-host routing key differs from the identity key");
        } else {
            bad("per-host key equals identity key - relays could correlate you");
        }
        if (Arrays.equals(id.hostKey(hp).getPublic().getEncoded(),
                id2.hostKey(hp).getPublic().getEncoded())) {
            ok("per-host key is stable for the same seed + host");
        } else {
            bad("per-host key is not deterministic");
        }

        System.out.println("\n=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.out.println("SEED PARITY FAILED - identity portability is broken.");
            System.exit(1);
        }
        System.out.println("Our seed derivation matches Minima exactly. Identity is portable.");
    }
}
