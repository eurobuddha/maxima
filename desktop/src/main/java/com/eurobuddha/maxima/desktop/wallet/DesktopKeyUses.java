package com.eurobuddha.maxima.desktop.wallet;

import com.eurobuddha.wallet.KeyUses;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The desktop's durable one-time-signature use counter — the file-backed counterpart
 * of the phone's {@code PrefsKeyUses}. Implements the SAFETY CONTRACT of
 * {@link KeyUses} exactly: reserve-before-sign with a synchronous, fsync'd write to
 * TWO mirror files before a leaf index is ever returned, and read = MAX across both
 * mirrors. Signing the same Winternitz leaf twice leaks the key, so this is
 * fund-critical: a crash after the +1 is on disk only wastes a leaf, never reuses one.
 */
public final class DesktopKeyUses implements KeyUses {

    private final File mirrorA;
    private final File mirrorB;
    private final String prefix;
    private final Object lock = new Object();

    public DesktopKeyUses(File walletDir, String namespace) {
        walletDir.mkdirs();
        String ns = (namespace == null || namespace.isEmpty()) ? "" : namespace + "_";
        prefix = "uses_" + ns;
        mirrorA = new File(walletDir, "keyuses_a.properties");
        mirrorB = new File(walletDir, "keyuses_b.properties");
    }

    public int currentUses(int keyIndex) {
        synchronized (lock) {
            int a = read(mirrorA, keyIndex);
            int b = read(mirrorB, keyIndex);
            return Math.max(a, b);
        }
    }

    public int reserveNextUse(int keyIndex) {
        synchronized (lock) {
            int n = currentUses(keyIndex);
            int next = n + 1;
            // Persist n+1 to BOTH mirrors, fsync'd, BEFORE returning n. Throw on any
            // failure so a signature is never emitted against an unpersisted advance.
            write(mirrorA, keyIndex, next);
            write(mirrorB, keyIndex, next);
            return n;
        }
    }

    public void recordExternalUses(int keyIndex, int uses) {
        synchronized (lock) {
            int merged = Math.max(currentUses(keyIndex), uses);
            write(mirrorA, keyIndex, merged);
            write(mirrorB, keyIndex, merged);
        }
    }

    public Map<Integer, Integer> snapshotAllUses() {
        synchronized (lock) {
            Map<Integer, Integer> out = new HashMap<>();
            for (File f : new File[]{mirrorA, mirrorB}) {
                Properties p = load(f);
                for (String k : p.stringPropertyNames()) {
                    if (k.startsWith(prefix)) {
                        try {
                            int idx = Integer.parseInt(k.substring(prefix.length()));
                            int v = Integer.parseInt(p.getProperty(k, "0"));
                            out.merge(idx, v, Math::max);
                        } catch (NumberFormatException ignored) {
                            // another namespace, or not a bare-integer suffix
                        }
                    }
                }
            }
            return out;
        }
    }

    // ---- durable file ops ----

    private String key(int keyIndex) { return prefix + keyIndex; }

    private int read(File f, int keyIndex) {
        try {
            return Integer.parseInt(load(f).getProperty(key(keyIndex), "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    private Properties load(File f) {
        Properties p = new Properties();
        if (f.exists()) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                p.load(in);
            } catch (Exception ignored) {
            }
        }
        return p;
    }

    /** Write the value and fsync; throw (fund-safety) if the durable write fails. */
    private void write(File f, int keyIndex, int value) {
        Properties p = load(f);
        p.setProperty(key(keyIndex), Integer.toString(value));
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, "maxima desktop wallet key-uses — DO NOT EDIT (one-time-signature counter)");
            out.flush();
            out.getFD().sync();   // fsync: the +1 must be on disk before we return a leaf
        } catch (IOException e) {
            throw new IllegalStateException("KeyUses: failed to persist use for key " + keyIndex
                    + " to " + f + " — refusing to sign (would risk key reuse)", e);
        }
    }
}
