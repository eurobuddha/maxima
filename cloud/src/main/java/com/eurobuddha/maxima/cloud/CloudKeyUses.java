package com.eurobuddha.maxima.cloud;

import com.eurobuddha.wallet.KeyUses;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;

/**
 * The desktop's durable one-time-signature use counter — the file-backed counterpart
 * of the phone's {@code PrefsKeyUses}. Implements the SAFETY CONTRACT of
 * {@link KeyUses} exactly: reserve-before-sign with a synchronous, fsync'd write to
 * TWO mirror files before a leaf index is ever returned, and read = MAX across both
 * mirrors. Signing the same Winternitz leaf twice leaks the key, so this is
 * fund-critical: a crash after the +1 is on disk only wastes a leaf, never reuses one.
 *
 * <p>The reserve read-modify-write is serialised at THREE levels so the same leaf can
 * never be handed out twice: a JVM-wide monitor keyed by the canonical wallet dir
 * (so even two CloudKeyUses instances over the same files exclude each other), and
 * an OS advisory {@link FileLock} on a dedicated lock file (so two app PROCESSES
 * exclude each other). Without both, a second wallet instance or a second launched
 * copy of the app could double-reserve a leaf.
 */
public final class CloudKeyUses implements KeyUses {

    /** One monitor per canonical wallet-dir, shared across instances in this JVM. */
    private static final ConcurrentHashMap<String, Object> DIR_LOCKS = new ConcurrentHashMap<>();

    private final File mirrorA;
    private final File mirrorB;
    private final File lockFile;
    private final String prefix;
    private final Object lock;

    public CloudKeyUses(File walletDir, String namespace) {
        walletDir.mkdirs();
        String ns = (namespace == null || namespace.isEmpty()) ? "" : namespace + "_";
        prefix = "uses_" + ns;
        mirrorA = new File(walletDir, "keyuses_a.properties");
        mirrorB = new File(walletDir, "keyuses_b.properties");
        lockFile = new File(walletDir, "keyuses.lock");
        String canon;
        try { canon = walletDir.getCanonicalPath(); } catch (IOException e) { canon = walletDir.getAbsolutePath(); }
        lock = DIR_LOCKS.computeIfAbsent(canon, k -> new Object());
    }

    /**
     * Run a critical section holding BOTH the JVM monitor and an OS advisory file
     * lock, so the read-modify-write is exclusive across threads AND processes. The
     * monitor is taken first (a process can't hold two overlapping FileLocks on one
     * file — the monitor serialises this JVM's threads so only one reaches lock()).
     */
    private <T> T underLocks(Callable<T> body) {
        synchronized (lock) {
            try (FileChannel ch = FileChannel.open(lockFile.toPath(),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock fl = ch.lock()) {
                return body.call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new IllegalStateException(
                        "KeyUses: cross-process lock failed — refusing to sign (would risk key reuse)", e);
            }
        }
    }

    public int currentUses(int keyIndex) {
        synchronized (lock) {
            int a = read(mirrorA, keyIndex);
            int b = read(mirrorB, keyIndex);
            return Math.max(a, b);
        }
    }

    public int reserveNextUse(int keyIndex) {
        return underLocks(() -> {
            // Read INSIDE the file lock so no other process can read the same n.
            int n = Math.max(read(mirrorA, keyIndex), read(mirrorB, keyIndex));
            int next = n + 1;
            // Persist n+1 to BOTH mirrors, fsync'd, BEFORE returning n. Throw on any
            // failure so a signature is never emitted against an unpersisted advance.
            write(mirrorA, keyIndex, next);
            write(mirrorB, keyIndex, next);
            return n;
        });
    }

    public void recordExternalUses(int keyIndex, int uses) {
        underLocks(() -> {
            int merged = Math.max(Math.max(read(mirrorA, keyIndex), read(mirrorB, keyIndex)), uses);
            write(mirrorA, keyIndex, merged);
            write(mirrorB, keyIndex, merged);
            return null;
        });
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

    // ---- portable backup surface (raw entry names, all namespaces) ----

    /** Every key-use counter across namespaces as raw entry names ("uses_v3_1000" → count),
     *  max-merged over both mirrors — the exact shape the phone's PrefsKeyUses exports, so a
     *  bundle moves between phone and cloud with no translation. */
    public static Map<String, Integer> exportAll(File zWalletDir) {
        CloudKeyUses k = new CloudKeyUses(zWalletDir, "");
        synchronized (k.lock) {
            Map<String, Integer> out = new HashMap<>();
            for (File f : new File[]{k.mirrorA, k.mirrorB}) {
                Properties p = k.load(f);
                for (String key : p.stringPropertyNames()) {
                    if (!key.startsWith("uses_")) {
                        continue;
                    }
                    try {
                        out.merge(key, Integer.parseInt(p.getProperty(key, "0")), Math::max);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return out;
        }
    }

    /** Restore counters RAISE-ONLY: each incoming entry is written as max(current, incoming)
     *  to BOTH mirrors, fsync'd; throws on any failed write. Can never lower a counter —
     *  lowering would let a Winternitz leaf sign twice (key disclosure). FUND-CRITICAL. */
    public static void importRaiseOnly(File zWalletDir, Map<String, Integer> zIncoming) {
        CloudKeyUses k = new CloudKeyUses(zWalletDir, "");
        k.underLocks(() -> {
            for (Map.Entry<String, Integer> e : zIncoming.entrySet()) {
                String key = e.getKey();
                if (!key.startsWith("uses_") || e.getValue() == null) {
                    continue;
                }
                int incoming = e.getValue();
                int current = Math.max(k.readRaw(k.mirrorA, key), k.readRaw(k.mirrorB, key));
                int merged = Math.max(current, incoming);
                k.writeRaw(k.mirrorA, key, merged);
                k.writeRaw(k.mirrorB, key, merged);
            }
            return null;
        });
    }

    private int readRaw(File f, String zKey) {
        try {
            return Integer.parseInt(load(f).getProperty(zKey, "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    private void writeRaw(File f, String zKey, int zValue) {
        Properties p = load(f);
        p.setProperty(zKey, Integer.toString(zValue));
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, "parlons cloud wallet key-uses — DO NOT EDIT (one-time-signature counter)");
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            throw new IllegalStateException("KeyUses: failed to persist " + zKey
                    + " to " + f + " — refusing (would risk key reuse)", e);
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
            p.store(out, "parlons cloud wallet key-uses — DO NOT EDIT (one-time-signature counter)");
            out.flush();
            out.getFD().sync();   // fsync: the +1 must be on disk before we return a leaf
        } catch (IOException e) {
            throw new IllegalStateException("KeyUses: failed to persist use for key " + keyIndex
                    + " to " + f + " — refusing to sign (would risk key reuse)", e);
        }
    }
}
