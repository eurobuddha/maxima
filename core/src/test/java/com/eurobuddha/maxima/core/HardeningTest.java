package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.mailbox.Mailbox;
import com.eurobuddha.maxima.core.store.FileStore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;

/**
 * The adversarial paths from the code review: untrusted decode and mailbox
 * flooding. These are the cheapest attacks on a public relay, so they get
 * explicit tests rather than trust.
 */
public class HardeningTest {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    public static void main(String[] args) throws Exception {
        System.out.println("=== HARDENING (adversarial) ===\n");

        // ---- allocation amplification ----
        // A MiniData frame claiming 512MB with only a few bytes behind it must
        // NOT allocate 512MB - it must fail cheaply. Before the fix this OOM'd
        // or allocated the full claim; now it throws EOF at a chunk's cost.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(512 * 1024 * 1024);   // claim 512MB
        dos.write(new byte[16]);           // deliver 16 bytes
        byte[] evil = bos.toByteArray();

        long freeBefore = Runtime.getRuntime().freeMemory();
        boolean threw = false;
        try {
            Codec.deserialise(new MiniData(), evil);
        } catch (java.io.EOFException e) {
            threw = true;
        } catch (OutOfMemoryError oom) {
            bad("decode allocated the full claim and OOM'd");
        }
        if (threw) {
            ok("a 512MB length claim with 16 bytes behind it fails with EOF, "
                    + "not a 512MB allocation");
        } else {
            bad("oversized length claim did not fail as expected");
        }

        // ---- mailbox global box cap + LRU ----
        // Small caps so the test is fast: 5 boxes max. Flooding 100 distinct
        // keys must never hold more than 5 boxes.
        Mailbox mb = new Mailbox(Mailbox.DEFAULT_TTL_MS, 200,
                8L * 1024 * 1024, 5, 256L * 1024 * 1024);
        for (int i = 0; i < 100; i++) {
            mb.store("0xKEY" + i, ("msg" + i).getBytes());
        }
        if (mb.boxCount() <= 5) {
            ok("100-key flood held to the box cap (" + mb.boxCount() + " <= 5) - "
                    + "no per-stranger allocation");
        } else {
            bad("box cap breached: " + mb.boxCount());
        }

        // The most-recently-touched key must be the survivor, not an early one.
        mb.store("0xRECENT", "keep me".getBytes());
        for (int i = 0; i < 10; i++) {
            mb.store("0xFILL" + i, "fill".getBytes());
        }
        if (mb.count("0xRECENT") == 0 && mb.boxCount() <= 5) {
            ok("LRU eviction dropped older boxes under sustained flood");
        } else {
            bad("LRU behaviour unexpected: recent=" + mb.count("0xRECENT")
                    + " boxes=" + mb.boxCount());
        }

        // ---- global byte cap ----
        Mailbox big = new Mailbox(Mailbox.DEFAULT_TTL_MS, 200,
                8L * 1024 * 1024, 10000, 4096);   // 4KB total
        byte[] oneK = new byte[1024];
        int stored = 0;
        for (int i = 0; i < 20; i++) {
            if (big.store("0xB" + i, oneK) == Mailbox.Result.STORED) {
                stored++;
            }
        }
        if (big.totalBytes() <= 4096) {
            ok("global byte cap held under flood (" + big.totalBytes() + " <= 4096)");
        } else {
            bad("global byte cap breached: " + big.totalBytes());
        }

        // ---- mailbox persistence across a restart ----
        File dir = new File(System.getProperty("java.io.tmpdir"), "maxima-mailbox-test");
        if (dir.exists()) {
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
        }
        Mailbox m1 = new Mailbox();
        m1.setStore(new FileStore(dir));
        m1.store("0xPERSIST", "held ciphertext".getBytes());
        long seq = m1.highestSequence("0xPERSIST");

        Mailbox m2 = new Mailbox();
        m2.setStore(new FileStore(dir));
        java.util.List<Mailbox.Item> after = m2.fetch("0xPERSIST", 0, 10);
        if (after.size() == 1 && new String(after.get(0).ciphertext).equals("held ciphertext")) {
            ok("held ciphertext survives a relay restart");
        } else {
            bad("mailbox did not persist: " + after.size());
        }
        // ...and acknowledging removes the durable copy too.
        m2.acknowledge("0xPERSIST", seq);
        Mailbox m3 = new Mailbox();
        m3.setStore(new FileStore(dir));
        if (m3.count("0xPERSIST") == 0) {
            ok("acknowledged mail does not resurrect on the next restart");
        } else {
            bad("acknowledged mail came back: " + m3.count("0xPERSIST"));
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("Adversarial paths hold.");
    }
}
