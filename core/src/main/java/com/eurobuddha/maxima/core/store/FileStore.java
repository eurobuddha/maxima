package com.eurobuddha.maxima.core.store;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link Store}. No dependencies, no database.
 *
 * One file per collection, one record per line, tab-separated key and value.
 * The value is escaped so a newline or tab in a payload cannot corrupt the
 * file - the obvious bug in every hand-rolled line format.
 *
 * Writes are ATOMIC: a full rewrite goes to a temp file and is renamed over the
 * original. A relay killed mid-write must not come back with a half-written
 * mailbox, and rename is the only cheap way to guarantee that.
 *
 * Keyed collections are cached in memory and rewritten on change, which is fine
 * at this scale (thousands of small records) and keeps reads free. Append logs
 * are appended to directly.
 */
public final class FileStore implements Store {

    private final File mDir;
    private final Map<String, Map<String, String>> mCache = new ConcurrentHashMap<>();

    /**
     * Write-behind mode.
     *
     * A keyed collection is fully rewritten and fsync'd on every put/remove.
     * That is fine for the app (a handful of contacts, rare changes), but on the
     * relay's mailbox it is O(size) disk I/O per stored item and O(size^2) to
     * fill a box - a remote disk-amplification DoS, brutal on an SD card. With
     * write-behind, put/remove only mark the collection dirty; {@link #flush}
     * writes each dirty collection ONCE. The relay flushes on its maintenance
     * tick and on shutdown, so the worst a crash costs is a few seconds of held
     * mail - acceptable for best-effort store-and-forward.
     */
    private volatile boolean mWriteBehind;
    private final java.util.Set<String> mDirty =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    public FileStore(File zDir) {
        mDir = zDir;
        if (!mDir.exists() && !mDir.mkdirs()) {
            throw new IllegalStateException("Cannot create data directory: " + mDir);
        }
        // Owner-only: the mailbox holds ciphertext + who-has-mail metadata, and
        // a sibling seed.txt is wallet-grade. Not group/world readable.
        try {
            mDir.setReadable(false, false);
            mDir.setReadable(true, true);
            mDir.setExecutable(false, false);
            mDir.setExecutable(true, true);
        } catch (Exception ignored) {
        }
    }

    /** Enable write-behind (relay mailbox). Off by default. */
    public void setWriteBehind(boolean zOn) {
        mWriteBehind = zOn;
    }

    /**
     * Coalescing write-behind: a dirty collection is written {@code zDelayMs} after the first
     * change that dirtied it, whatever else changes meanwhile - one rewrite per burst instead
     * of one per record. A chat store used to rewrite and fsync its whole message file for
     * EVERY inbound message and every tick; a phone with 30 000 messages spent seconds per
     * message doing it, on the inbound path. {@link #flush()} still forces everything out at
     * once (the client calls it before it acknowledges held mail, so nothing acknowledged is
     * ever only in memory). 0 disables the timer (plain write-behind, flushed by the caller).
     */
    public void setFlushDelay(long zDelayMs) {
        mFlushDelayMs = Math.max(0, zDelayMs);
        if (mFlushDelayMs > 0) {
            mWriteBehind = true;
        }
    }

    /** A store that coalesces writes {@code zDelayMs} after the first change (see
     *  {@link #setFlushDelay}). */
    public static FileStore coalescing(File zDir, long zDelayMs) {
        FileStore s = new FileStore(zDir);
        s.setFlushDelay(zDelayMs);
        return s;
    }

    private volatile long mFlushDelayMs;
    private final java.util.concurrent.atomic.AtomicBoolean mFlushScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** One daemon timer for every coalescing store in the process. */
    private static final java.util.concurrent.ScheduledExecutorService FLUSHER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "store-flush");
                t.setDaemon(true);
                return t;
            });

    // ---------------------------------------------------------------
    // keyed records
    // ---------------------------------------------------------------

    @Override
    public synchronized void put(String zCollection, String zKey, String zValue) {
        load(zCollection).put(zKey, zValue);
        markOrPersist(zCollection);
    }

    @Override
    public synchronized String get(String zCollection, String zKey) {
        return load(zCollection).get(zKey);
    }

    @Override
    public synchronized void remove(String zCollection, String zKey) {
        if (load(zCollection).remove(zKey) != null) {
            markOrPersist(zCollection);
        }
    }

    /** Write now, or mark dirty for the next flush, per the write-behind mode. */
    private void markOrPersist(String zCollection) {
        if (mWriteBehind) {
            mDirty.add(zCollection);
            long delay = mFlushDelayMs;
            if (delay > 0 && mFlushScheduled.compareAndSet(false, true)) {
                FLUSHER.schedule(() -> {
                    mFlushScheduled.set(false);   // a change during the flush schedules the next
                    try {
                        flush();
                    } catch (Exception e) {
                        System.err.println("[store] scheduled flush failed: " + e);
                    }
                }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } else {
            persist(zCollection);
        }
    }

    @Override
    public synchronized Map<String, String> all(String zCollection) {
        return new LinkedHashMap<>(load(zCollection));
    }

    private Map<String, String> load(String zCollection) {
        return mCache.computeIfAbsent(zCollection, c -> {
            Map<String, String> m = new LinkedHashMap<>();
            File f = file(c + ".tsv");
            if (!f.exists()) {
                return m;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.indexOf('\t');
                    if (t > 0) {
                        m.put(unescape(line.substring(0, t)), unescape(line.substring(t + 1)));
                    }
                }
            } catch (IOException e) {
                // A corrupt collection must not take the process down. Losing
                // one file is recoverable; refusing to start is not.
                System.err.println("[store] could not read " + f + ": " + e);
            }
            return m;
        });
    }

    private void persist(String zCollection) {
        Map<String, String> m = mCache.get(zCollection);
        if (m == null) {
            return;
        }
        List<String> lines = new ArrayList<>(m.size());
        for (Map.Entry<String, String> e : m.entrySet()) {
            lines.add(escape(e.getKey()) + "\t" + escape(e.getValue()));
        }
        writeAtomic(file(zCollection + ".tsv"), lines);
    }

    // ---------------------------------------------------------------
    // append logs
    // ---------------------------------------------------------------

    @Override
    public synchronized void append(String zLog, String zLine) {
        File f = file(zLog + ".log");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(f, true), StandardCharsets.UTF_8))) {
            w.write(escape(zLine));
            w.newLine();
        } catch (IOException e) {
            System.err.println("[store] append failed on " + f + ": " + e);
        }
    }

    @Override
    public synchronized List<String> read(String zLog) {
        List<String> out = new ArrayList<>();
        File f = file(zLog + ".log");
        if (!f.exists()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    out.add(unescape(line));
                }
            }
        } catch (IOException e) {
            System.err.println("[store] read failed on " + f + ": " + e);
        }
        return out;
    }

    @Override
    public synchronized void rewrite(String zLog, List<String> zLines) {
        List<String> esc = new ArrayList<>(zLines.size());
        for (String l : zLines) {
            esc.add(escape(l));
        }
        writeAtomic(file(zLog + ".log"), esc);
    }

    @Override
    public synchronized void flush() {
        // In immediate mode every write already landed. In write-behind mode,
        // persist each dirty collection exactly once here (one rewrite+fsync per
        // collection per flush, not per item).
        if (mDirty.isEmpty()) {
            return;
        }
        List<String> collections = new ArrayList<>(mDirty);
        mDirty.clear();
        for (String c : collections) {
            persist(c);
        }
    }

    // ---------------------------------------------------------------
    // binary records: <dir>/<collection>.d/<sha256(key)>, content = [keyLen][key][value]
    // ---------------------------------------------------------------

    private File binDir(String zCollection) {
        File d = new File(mDir, zCollection.replaceAll("[^A-Za-z0-9._-]", "_") + ".d");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    private File binFile(String zCollection, String zKey) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(zKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return new File(binDir(zCollection), sb.toString());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized boolean putBytes(String zCollection, String zKey, byte[] zValue) {
        File target = binFile(zCollection, zKey);
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        byte[] key = zKey.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            java.io.DataOutputStream d = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(fos, 65536));
            d.writeInt(key.length);
            d.write(key);
            d.write(zValue);
            d.flush();
            fos.getFD().sync();   // durable before it becomes visible (see writeAtomic)
        } catch (IOException e) {
            System.err.println("[store] write failed on " + tmp + ": " + e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(target)) {
            if (!target.delete() || !tmp.renameTo(target)) {
                System.err.println("[store] could not replace " + target);
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized byte[] getBytes(String zCollection, String zKey) {
        File f = binFile(zCollection, zKey);
        if (!f.exists()) {
            return null;
        }
        try (java.io.DataInputStream d = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new FileInputStream(f), 65536))) {
            int klen = d.readInt();
            if (klen < 0 || klen > f.length() - 4) {
                return null;
            }
            d.readFully(new byte[klen]);   // skipBytes may skip fewer; the key must be consumed whole
            byte[] v = new byte[(int) (f.length() - 4 - klen)];
            d.readFully(v);
            return v;
        } catch (IOException e) {
            System.err.println("[store] could not read " + f + ": " + e);
            return null;
        }
    }

    @Override
    public synchronized void removeBytes(String zCollection, String zKey) {
        //noinspection ResultOfMethodCallIgnored
        binFile(zCollection, zKey).delete();
    }

    @Override
    public synchronized Map<String, Integer> listBytes(String zCollection) {
        Map<String, Integer> out = new LinkedHashMap<>();
        File[] files = binDir(zCollection).listFiles((d, n) -> !n.endsWith(".tmp"));
        if (files == null) {
            return out;
        }
        for (File f : files) {
            try (java.io.DataInputStream d = new java.io.DataInputStream(new FileInputStream(f))) {
                int klen = d.readInt();
                if (klen <= 0 || klen > 4096 || klen > f.length() - 4) {
                    continue;   // not one of ours
                }
                byte[] key = new byte[klen];
                d.readFully(key);
                out.put(new String(key, StandardCharsets.UTF_8), (int) (f.length() - 4 - klen));
            } catch (IOException e) {
                System.err.println("[store] could not read " + f + ": " + e);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------

    private File file(String zName) {
        // Never let a collection name escape the data directory.
        return new File(mDir, zName.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** temp + rename, so an interrupted write cannot leave a half file. */
    private void writeAtomic(File zTarget, List<String> zLines) {
        File tmp = new File(zTarget.getParentFile(), zTarget.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(fos, StandardCharsets.UTF_8));
            for (String l : zLines) {
                w.write(l);
                w.newLine();
            }
            w.flush();
            // force the bytes to disk BEFORE the rename. Rename gives atomicity
            // of visibility, not durability: on some filesystems a crash right
            // after rename can expose the new name with unflushed (empty)
            // contents - the exact data-loss this class exists to prevent.
            fos.getFD().sync();
        } catch (IOException e) {
            System.err.println("[store] write failed on " + tmp + ": " + e);
            return;
        }
        if (!tmp.renameTo(zTarget)) {
            // Windows and some Android filesystems refuse rename-over.
            if (!zTarget.delete() || !tmp.renameTo(zTarget)) {
                System.err.println("[store] could not replace " + zTarget);
            }
        }
    }

    /** Keeps tabs and newlines out of the line format. */
    static String escape(String zValue) {
        return zValue.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    static String unescape(String zValue) {
        StringBuilder sb = new StringBuilder(zValue.length());
        for (int i = 0; i < zValue.length(); i++) {
            char c = zValue.charAt(i);
            if (c == '\\' && i + 1 < zValue.length()) {
                char n = zValue.charAt(++i);
                switch (n) {
                    case 't': sb.append('\t'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
