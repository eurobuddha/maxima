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
 *
 * Text read I/O and format failures throw {@link java.io.UncheckedIOException}. Only definite
 * absence is treated as a missing path. Failed reads never enter the cache.
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
        // Retain the cached mutation until its replacement actually reaches disk, including
        // immediate writes: callers may recover from an I/O failure and explicitly flush.
        mDirty.add(zCollection);
        if (mWriteBehind) {
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
            mDirty.remove(zCollection);
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
            if (java.nio.file.Files.notExists(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return m;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8.newDecoder()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    int t = line.indexOf('\t');
                    if (t < 0) {
                        throw new IOException("Missing record separator");
                    }
                    m.put(unescape(line.substring(0, t)), unescape(line.substring(t + 1)));
                }
            } catch (IOException e) {
                // computeIfAbsent must not cache a partial/empty read: a later mutation
                // would replace the complete on-disk collection with that failed snapshot.
                throw new java.io.UncheckedIOException("Could not read " + f, e);
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
        if (java.nio.file.Files.notExists(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8.newDecoder()))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.add(unescape(line));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not read " + f, e);
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
        // Immediate writes that failed also remain dirty. In write-behind mode,
        // persist each dirty collection exactly once here (one rewrite+fsync per
        // collection per flush, not per item).
        if (mDirty.isEmpty()) {
            return;
        }
        List<String> collections = new ArrayList<>(mDirty);
        for (String c : collections) {
            persist(c);
            mDirty.remove(c);
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
            String name = com.eurobuddha.maxima.core.codec.Hex.encode(h)
                    .substring(2).toLowerCase(java.util.Locale.ROOT);
            return new File(binDir(zCollection), name);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized boolean putBytes(String zCollection, String zKey, byte[] zValue) {
        File target = binFile(zCollection, zKey);
        byte[] key = zKey.getBytes(StandardCharsets.UTF_8);
        try {
            writeAtomic(target, fos -> {
                java.io.DataOutputStream d = new java.io.DataOutputStream(
                        new java.io.BufferedOutputStream(fos, 65536));
                d.writeInt(key.length);
                d.write(key);
                d.write(zValue);
                d.flush();
            });
        } catch (java.io.UncheckedIOException e) {
            System.err.println("[store] write failed on " + target + ": " + e);
            return false;
        }
        return true;
    }

    @Override
    public synchronized byte[] getBytes(String zCollection, String zKey) {
        return getBytes(zCollection, zKey, Integer.MAX_VALUE);
    }

    @Override
    public synchronized byte[] getBytes(String zCollection, String zKey, int zMaxBytes) {
        if (zMaxBytes < 0) throw new IllegalArgumentException("negative byte budget");
        File f = binFile(zCollection, zKey);
        if (!f.exists()) {
            return null;
        }
        byte[] expectedKey = zKey.getBytes(StandardCharsets.UTF_8);
        try (FileInputStream in = new FileInputStream(f);
             java.io.DataInputStream d = new java.io.DataInputStream(
                     new java.io.BufferedInputStream(in, 65536))) {
            long length = in.getChannel().size(); // size of this opened record, even if its path is replaced
            int klen = d.readInt();
            if (klen != expectedKey.length) {
                return null;
            }
            long valueLength = length - 4L - klen;
            if (valueLength < 0 || valueLength > zMaxBytes) return null;
            byte[] key = new byte[klen];
            d.readFully(key);
            if (!java.util.Arrays.equals(expectedKey, key)) return null;
            byte[] v = new byte[(int) valueLength];
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
            try (FileInputStream in = new FileInputStream(f);
                 java.io.DataInputStream d = new java.io.DataInputStream(in)) {
                long length = in.getChannel().size();
                int klen = d.readInt();
                if (klen < 0 || klen > 4096 || klen > length - 4) {
                    continue;   // not one of ours
                }
                long valueLength = length - 4L - klen;
                if (valueLength > Integer.MAX_VALUE) continue;
                byte[] key = new byte[klen];
                d.readFully(key);
                String decoded = StandardCharsets.UTF_8.newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(key)).toString();
                if (!f.getName().equals(binFile(zCollection, decoded).getName())) continue;
                out.put(decoded, (int) valueLength);
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
        writeAtomic(zTarget, fos -> {
            BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(fos, StandardCharsets.UTF_8));
            for (String l : zLines) {
                w.write(l);
                w.newLine();
            }
            w.flush();
        });
    }

    /** The body flushes its buffers; the shared writer owns sync, close and replacement. */
    @FunctionalInterface
    private interface WriteBody {
        void write(FileOutputStream zStream) throws IOException;
    }

    private void writeAtomic(File zTarget, WriteBody zBody) {
        // Same private-temp / replacement sequence as AccountFiles.writePrivate. A failed
        // replacement must never delete the previous snapshot or masquerade as durability.
        java.nio.file.Path target = zTarget.toPath().toAbsolutePath();
        java.nio.file.Path tmp = null;
        try {
            try {
                tmp = java.nio.file.Files.createTempFile(target.getParent(), ".parlons-store-", ".tmp",
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException nonPosix) {
                tmp = java.nio.file.Files.createTempFile(target.getParent(), ".parlons-store-", ".tmp");
            }
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
                zBody.write(fos);
                // force the bytes to disk BEFORE the rename. Rename gives atomicity
                // of visibility, not durability: on some filesystems a crash right
                // after rename can expose the new name with unflushed (empty)
                // contents - the exact data-loss this class exists to prevent.
                fos.getFD().sync();
            }
            try {
                java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException nonAtomic) {
                java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not persist " + zTarget, e);
        } finally {
            if (tmp != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tmp);
                } catch (IOException cleanup) {
                    System.err.println("[store] temporary file cleanup failed on " + tmp + ": " + cleanup);
                }
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

    static String unescape(String zValue) throws IOException {
        StringBuilder sb = new StringBuilder(zValue.length());
        for (int i = 0; i < zValue.length(); i++) {
            char c = zValue.charAt(i);
            if (c == '\\') {
                if (i + 1 == zValue.length()) {
                    throw new IOException("Unfinished record escape");
                }
                char n = zValue.charAt(++i);
                switch (n) {
                    case 't': sb.append('\t'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case '\\': sb.append('\\'); break;
                    default: throw new IOException("Invalid record escape");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
