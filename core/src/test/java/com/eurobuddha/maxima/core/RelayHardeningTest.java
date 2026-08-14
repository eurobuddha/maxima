package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.directory.MlsStore;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.CryptoPackage;
import com.eurobuddha.maxima.core.portmap.PortMapper;
import com.eurobuddha.maxima.core.store.FileStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Regressions for the relay security-hardening pass (0.1.8).
 *
 * These lock in the fixes from the adversarial review so they cannot silently
 * regress: the constant-behaviour decrypt, the isPublic egress gate, the
 * directory cap, and the write-behind store that killed the disk-amplification
 * DoS. The connection-level admission fixes (rate limits, route caps, reaper)
 * live in :server and are exercised by the live suite; the reusable predicates
 * they rely on are pinned here.
 */
public class RelayHardeningTest {

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

    public static void main(String[] args) throws Exception {
        System.out.println("=== RELAY HARDENING REGRESSIONS ===\n");

        // ---- constant-behaviour decrypt still round-trips a real message ----
        MaximaIdentity to = idFrom(201);
        byte[] plain = "a real sealed payload".getBytes();
        CryptoPackage cp = MaximaCrypto.encrypt(plain, to.publicKey());
        byte[] out = MaximaCrypto.decrypt(cp, to.keyPair().getPrivate().getEncoded());
        if (Arrays.equals(out, plain)) {
            ok("constant-behaviour decrypt still recovers a genuine plaintext");
        } else {
            bad("decrypt broke round-trip");
        }
        // the PrivateKey overload matches the DER overload
        byte[] out2 = MaximaCrypto.decrypt(cp, to.keyPair().getPrivate());
        if (Arrays.equals(out2, plain)) {
            ok("the pre-parsed-key decrypt overload agrees with the DER one");
        } else {
            bad("PrivateKey overload diverged");
        }

        // a GARBAGE ciphertext (bad RSA padding) must FAIL - the same failure as
        // a genuine decrypt error, never a distinguishable padding signal.
        CryptoPackage evil = MaximaCrypto.encrypt(plain, idFrom(203).publicKey()); // sealed to someone else
        boolean threwSame = false;
        try {
            MaximaCrypto.decrypt(evil, to.keyPair().getPrivate());
        } catch (IllegalStateException e) {
            threwSame = "Maxima decrypt failed".equals(e.getMessage());
        }
        if (threwSame) {
            ok("a ciphertext we cannot unwrap fails as a plain decrypt error (no padding oracle)");
        } else {
            bad("decrypt failure was distinguishable");
        }

        // ---- isPublic egress gate: metadata + all private/test ranges blocked ----
        String[] blocked = {"169.254.169.254", "127.0.0.1", "10.0.0.1", "192.168.1.1",
                "172.16.0.1", "100.64.0.1", "0.0.0.0", "192.0.2.5", "198.51.100.9",
                "203.0.113.4", "198.18.0.1", "192.88.99.1", "192.0.0.1", "224.0.0.1",
                "fe80::1", "::1"};
        String[] allowed = {"81.2.3.4", "8.8.8.8", "1.1.1.1", "203.0.114.1", "198.20.0.1"};
        boolean gate = true;
        for (String ip : blocked) {
            if (PortMapper.isPublic(ip)) {
                bad("isPublic wrongly ALLOWED " + ip);
                gate = false;
            }
        }
        for (String ip : allowed) {
            if (!PortMapper.isPublic(ip)) {
                bad("isPublic wrongly BLOCKED " + ip);
                gate = false;
            }
        }
        if (gate) {
            ok("egress gate blocks metadata (169.254.169.254), RFC1918, CGNAT, TEST-NETs, "
                    + "6to4, v6; allows real public");
        }

        // ---- directory (MLS) cap: unbounded SET flood is bounded ----
        MlsStore dir = new MlsStore();
        for (int i = 0; i < MlsStore.DEFAULT_MAX_ENTRIES + 5000; i++) {
            dir.put("0xKEY" + i, Collections.singletonList("Mx" + i + "@1.2.3.4:9501"),
                    new ArrayList<>());
        }
        if (dir.size() <= MlsStore.DEFAULT_MAX_ENTRIES) {
            ok("directory held to its cap under a SET flood (" + dir.size() + " <= "
                    + MlsStore.DEFAULT_MAX_ENTRIES + ")");
        } else {
            bad("directory grew past the cap: " + dir.size());
        }
        // per-entry address list is bounded
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 50000; i++) {
            many.add("Mx" + i + "@1.2.3.4:9501");
        }
        dir.put("0xBIG", many, new ArrayList<>());
        MlsStore.Entry e = dir.peek("0xBIG");
        if (e != null && e.addresses.size() <= MlsStore.MAX_ADDRESSES) {
            ok("a SET carrying 50k addresses is capped to " + MlsStore.MAX_ADDRESSES);
        } else {
            bad("per-entry address cap not enforced: "
                    + (e == null ? "null" : e.addresses.size()));
        }

        // ---- write-behind store: puts don't hit disk until flush ----
        File d = new File(System.getProperty("java.io.tmpdir"), "maxima-wb-test");
        if (d.exists()) {
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
        }
        FileStore fsStore = new FileStore(d);
        fsStore.setWriteBehind(true);
        for (int i = 0; i < 100; i++) {
            fsStore.put("mailbox", "k" + i, "v" + i);
        }
        File tsv = new File(d, "mailbox.tsv");
        boolean notYet = !tsv.exists();
        fsStore.flush();
        boolean afterFlush = tsv.exists();
        // reload proves the flush actually persisted every deferred put
        FileStore reopened = new FileStore(d);
        boolean allThere = reopened.all("mailbox").size() == 100;
        if (notYet && afterFlush && allThere) {
            ok("write-behind: 100 puts wrote the file ONCE at flush, not per put (no fsync storm)");
        } else {
            bad("write-behind wrong: beforeFlush=" + notYet + " afterFlush=" + afterFlush
                    + " persisted=" + allThere);
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("Relay hardening holds.");
    }
}
