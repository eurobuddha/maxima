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
 *
 * PINNED chunks (the owner's own published media, {@link #put(byte[], boolean)}) live in
 * a {@code pinned/} subfolder that eviction never touches: before this, a phone's own
 * photos shared the LRU with everything it had merely viewed, and at ~50 views a day a
 * published photo silently vanished from its own source of truth within weeks.
 */
public final class BlobStore {

    /** Node-ish default; relays/desktops configure much larger (--blobstore). */
    public static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

    /** One chunk may never exceed this (wire ceiling is 256KiB with envelope). */
    public static final int MAX_CHUNK_BYTES = 256 * 1024;

    private final File mDir;
    private final File mPinned;
    private final long mMaxBytes;
    /** Every chunk, pinned included. */
    private final AtomicLong mBytes = new AtomicLong();
    private final AtomicLong mPinnedBytes = new AtomicLong();

    public BlobStore(File zDir) {
        this(zDir, DEFAULT_MAX_BYTES);
    }

    public BlobStore(File zDir, long zMaxBytes) {
        mDir = zDir;
        mPinned = new File(zDir, "pinned");
        mMaxBytes = zMaxBytes;
        //noinspection ResultOfMethodCallIgnored
        mPinned.mkdirs();
        long total = 0;
        long pinned = 0;
        File[] files = mDir.listFiles(File::isFile);
        if (files != null) {
            for (File f : files) {
                total += f.length();
            }
        }
        File[] pins = mPinned.listFiles(File::isFile);
        if (pins != null) {
            for (File f : pins) {
                pinned += f.length();
            }
        }
        mBytes.set(total + pinned);
        mPinnedBytes.set(pinned);
    }

    /**
     * Store a chunk; returns its id (uppercase 0x hex of SHA3-256(content)).
     * Idempotent. Evicts least-recently-fetched chunks when over the cap.
     */
    public synchronized String put(byte[] zContent) throws IOException {
        return put(zContent, false);
    }

    /**
     * Store a chunk, PINNED when {@code zPin}: the owner's own published media, which
     * eviction never removes. A pinned chunk may use the whole cap (evicting every unpinned
     * one first); if even that is not enough the put fails loudly rather than dropping
     * someone's photo. Re-putting an unpinned chunk as pinned moves it under the pin.
     */
    public synchronized String put(byte[] zContent, boolean zPin) throws IOException {
        if (zContent == null || zContent.length == 0) {
            throw new IllegalArgumentException("empty chunk");
        }
        if (zContent.length > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("chunk too large: " + zContent.length);
        }
        String id = idOf(zContent);
        File pinned = new File(mPinned, id.substring(2));
        if (pinned.exists()) {
            return id;   // already pinned: nothing to do, whichever way it was asked for
        }
        File loose = new File(mDir, id.substring(2));
        if (loose.exists()) {
            if (zPin) {
                if (!loose.renameTo(pinned)) {
                    throw new IOException("could not pin chunk");
                }
                mPinnedBytes.addAndGet(zContent.length);
            } else {
                //noinspection ResultOfMethodCallIgnored
                loose.setLastModified(System.currentTimeMillis());   // re-put refreshes
            }
            return id;
        }
        // Make room BEFORE writing, so the cap is never exceeded on disk.
        evictUntilRoomFor(zContent.length);
        if (mBytes.get() + zContent.length > mMaxBytes) {
            // Only pinned chunks are left and they fill the shelf.
            throw new IOException("media shelf full: " + (mPinnedBytes.get() >> 20)
                    + " MB of your own published media already fills its " + (mMaxBytes >> 20) + " MB");
        }
        File target = zPin ? pinned : loose;
        File tmp = new File(target.getParentFile(), id.substring(2) + ".tmp");
        Files.write(tmp.toPath(), zContent);
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not persist chunk");
        }
        mBytes.addAndGet(zContent.length);
        if (zPin) {
            mPinnedBytes.addAndGet(zContent.length);
        }
        return id;
    }

    /** Bytes held for the owner's own published media (never evicted). */
    public long pinnedBytes() {
        return mPinnedBytes.get();
    }

    /** True if this chunk is held pinned. */
    public synchronized boolean isPinned(String zId) {
        return new File(mPinned, norm(zId).substring(2)).exists();
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
        File[] l = mDir.listFiles(f -> f.isFile() && !f.getName().endsWith(".tmp"));
        File[] p = mPinned.listFiles(f -> f.isFile() && !f.getName().endsWith(".tmp"));
        return (l == null ? 0 : l.length) + (p == null ? 0 : p.length);
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
        // Only LOOSE chunks are candidates: the pinned/ folder is never evicted.
        File[] files = mDir.listFiles(f -> f.isFile() && !f.getName().endsWith(".tmp"));
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
        File pinned = new File(mPinned, zId.substring(2));
        return pinned.exists() ? pinned : new File(mDir, zId.substring(2));
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
