package com.eurobuddha.maxima.core.codec;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Safe length-prefixed reads.
 *
 * The trap this closes: {@code new byte[len]} where {@code len} is an attacker-
 * declared length. A 1 KB frame can contain a nested field claiming 512 MB;
 * the naive reader allocates 512 MB and only THEN discovers the stream is
 * short. A handful of such fields across connections exhausts the heap of a
 * small relay - allocation amplification, no large payload required.
 *
 * The fix is to never pre-allocate more than a chunk. Memory grows with the
 * bytes that ACTUALLY arrive, and the read fails (EOFException) the moment the
 * stream runs dry, so a lie about length costs the liar, not us.
 */
public final class Reads {

    private static final int CHUNK = 64 * 1024;

    private Reads() {
    }

    /**
     * Read exactly {@code zLen} bytes, allocating incrementally.
     *
     * @throws java.io.EOFException if the stream ends first - which is exactly
     *         what a bogus over-large length produces, at a cost of one chunk
     *         rather than the whole claim.
     */
    public static byte[] exact(DataInputStream zIn, int zLen) throws IOException {
        if (zLen < 0) {
            throw new IOException("negative length: " + zLen);
        }
        if (zLen <= CHUNK) {
            byte[] b = new byte[zLen];
            zIn.readFully(b);
            return b;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(CHUNK);
        byte[] buf = new byte[CHUNK];
        int remaining = zLen;
        while (remaining > 0) {
            int want = Math.min(remaining, CHUNK);
            zIn.readFully(buf, 0, want);
            bos.write(buf, 0, want);
            remaining -= want;
        }
        return bos.toByteArray();
    }
}
