package com.eurobuddha.maxima.core.store;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.Hashes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A disk-backed, content-addressed chunk store — the shelf space of the
 * self-hosted media network.
 *
 * id = SHA3-256(content), which buys three things for free: writes are
 * idempotent (same bytes, same file), integrity is verifiable by anyone
 * holding the id, and there is nothing to name or coordinate. Contents are
 * ciphertext chunks sealed by their owners; this store can read none of it.
 *
 * Bounded by a byte cap with least-recently-FETCHED eviction (a get refreshes
 * mtime), so a relay's shelf self-cleans: media someone still watches stays,
 * media nobody asks for ages out. The OWNER's own node keeps the source of
 * truth for its published media — a relay copy is redundancy, never custody.
 */
public final class BlobStore {

    /** Node-ish default; relays/desktops configure much larger (--blobstore). */
    public static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

    /** One chunk may never exceed this (wire ceiling is 256KiB with envelope). */
    public static final int MAX_CHUNK_BYTES = 256 * 1024;

    private final File mDir;
    private final long mMaxBytes;
    private final AtomicLong mBytes = new AtomicLong();

    public BlobStore(File zDir) {
        this(zDir, DEFAULT_MAX_BYTES);
    }

    public BlobStore(File zDir, long zMaxBytes) {
        mDir = zDir;
        mMaxBytes = zMaxBytes;
        //noinspection ResultOfMethodCallIgnored
        mDir.mkdirs();
        long total = 0;
        File[] files = mDir.listFiles();
        if (files != null) {
            for (File f : files) {
                total += f.length();
            }
        }
        mBytes.set(total);
    }

    /**
     * Store a chunk; returns its id (uppercase 0x hex of SHA3-256(content)).
     * Idempotent. Evicts least-recently-fetched chunks when over the cap.
     */
    public synchronized String put(byte[] zContent) throws IOException {
        if (zContent == null || zContent.length == 0) {
            throw new IllegalArgumentException("empty chunk");
        }
        if (zContent.length > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("chunk too large: " + zContent.length);
        }
        String id = idOf(zContent);
        File f = fileFor(id);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());   // re-put refreshes
            return id;
        }
        // Make room BEFORE writing, so the cap is never exceeded on disk.
        evictUntilRoomFor(zContent.length);
        File tmp = new File(mDir, id.substring(2) + ".tmp");
        Files.write(tmp.toPath(), zContent);
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not persist chunk");
        }
        mBytes.addAndGet(zContent.length);
        return id;
    }

    /** The chunk, or null. A hit refreshes its LRU standing. */
    public synchronized byte[] get(String zId) {
        File f = fileFor(norm(zId));
        if (!f.exists()) {
            return null;
        }
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    public synchronized boolean has(String zId) {
        return fileFor(norm(zId)).exists();
    }

    public long bytes() {
        return mBytes.get();
    }

    public synchronized int count() {
        String[] l = mDir.list((d, n) -> !n.endsWith(".tmp"));
        return l == null ? 0 : l.length;
    }

    public long maxBytes() {
        return mMaxBytes;
    }

    /** The canonical chunk id for content. */
    public static String idOf(byte[] zContent) {
        return new MiniData(Hashes.sha3(zContent)).to0xString();
    }

    // ---------------------------------------------------------------

    private void evictUntilRoomFor(int zIncoming) {
        if (mBytes.get() + zIncoming <= mMaxBytes) {
            return;
        }
        File[] files = mDir.listFiles((d, n) -> !n.endsWith(".tmp"));
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (mBytes.get() + zIncoming <= mMaxBytes) {
                return;
            }
            long len = f.length();
            if (f.delete()) {
                mBytes.addAndGet(-len);
            }
        }
    }

    private File fileFor(String zId) {
        // id is validated hex, so the filename cannot traverse anywhere.
        return new File(mDir, zId.substring(2));
    }

    private static String norm(String zId) {
        String id = zId == null ? "" : zId.trim().toUpperCase().replace("0X", "0x");
        if (!id.startsWith("0x")) {
            id = "0x" + id;
        }
        if (!id.matches("0x[0-9A-F]{64}")) {
            throw new IllegalArgumentException("bad chunk id");
        }
        return id;
    }
}
