package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.chat.ChatFile;
import com.eurobuddha.maxima.core.chat.KeetsTransport;
import com.eurobuddha.maxima.core.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** The existing AES-GCM file format on Keets' existing Hyperdrive media API.
 * Bounded blocks also fit the Android broadcast command limit. Only the sealed chat
 * offer contains the key, filename and plaintext digest. The public drive holds ciphertext.
 * No file block is sent as a chat message or put into a fleet mailbox. */
public final class KeetsFiles {
    public static final int BLOCK = 112 * 1024;
    public static final int MANIFEST_LIMIT = 64 * 1024;
    private static final String HASH = "[a-f0-9]{64}";

    public static void validate(ChatFile f) throws IOException {
        if (f.size > ChatFile.TRANSFER_MAX_BYTES || !f.torrent.matches(
                "keets://" + HASH + "/private/" + f.id + "/manifest\\?hash=" + HASH))
            throw new IOException("Invalid Keets file offer; update both apps");
    }

    /** Publish an already-encrypted payload. A retry uses the same paths and keys. */
    public static ChatFile publish(KeetsTransport transport, Path payload, ChatFile f,
            BooleanSupplier cancelled, LongConsumer progress) throws Exception {
        if (f.size > ChatFile.TRANSFER_MAX_BYTES || Files.size(payload) != FileCrypto.cipherSize(f.size))
            throw new IOException("Invalid encrypted file size");
        String prefix = "/private/" + f.id + "/", base = null;
        StringBuilder hashes = new StringBuilder();
        long done = 0;
        try (InputStream in = Files.newInputStream(payload)) {
            byte[] buffer = new byte[BLOCK]; int index = 0, n;
            while ((n = readBlock(in, buffer)) > 0) {
                check(cancelled);
                byte[] block = Arrays.copyOf(buffer, n);
                String hash = sha(block), path = prefix + index++;
                String url = transport.publish(path, block);
                String suffix = path + "?hash=" + hash;
                if (url == null || !url.matches("keets://" + HASH + java.util.regex.Pattern.quote(suffix)))
                    throw new IOException("Keets did not confirm the encrypted block");
                String drive = url.substring(0, "keets://".length() + 64);
                if (base == null) base = drive + prefix;
                if (!(drive + prefix).equals(base)) throw new IOException("Keets node changed during upload");
                if (hashes.length() > 0) hashes.append('\n');
                hashes.append(hash); done += n; progress.accept(Math.min(f.size, done));
            }
        }
        check(cancelled);
        byte[] manifest = new Json.Writer().put("v", "1").put("id", f.id)
                .put("size", Long.toString(FileCrypto.cipherSize(f.size))).put("block", Integer.toString(BLOCK))
                .put("hashes", hashes.toString()).done().getBytes(StandardCharsets.UTF_8);
        if (manifest.length > MANIFEST_LIMIT) throw new IOException("File manifest too large");
        String url = transport.publish(prefix + "manifest", manifest);
        if (!(base + "manifest?hash=" + sha(manifest)).equals(url))
            throw new IOException("Keets did not confirm the file manifest");
        return new ChatFile(f.id, f.name, f.mime, f.size, f.key, f.nonce, f.digest, url);
    }

    /** Resume only hash-verified blocks. Plaintext appears only after complete GCM + SHA verification. */
    public static void download(KeetsTransport transport, ChatFile f, Path directory,
            BooleanSupplier cancelled, LongConsumer progress) throws Exception {
        validate(f); check(cancelled);
        byte[] manifest = checkedFetch(transport, f.torrent, MANIFEST_LIMIT);
        Map<String,String> m = Json.parse(new String(manifest, StandardCharsets.UTF_8));
        long total = FileCrypto.cipherSize(f.size);
        String[] hashes = m.getOrDefault("hashes", "").split("\n", -1);
        if (!"1".equals(m.get("v")) || !f.id.equals(m.get("id"))
                || !Long.toString(total).equals(m.get("size")) || !Integer.toString(BLOCK).equals(m.get("block"))
                || hashes.length != (total + BLOCK - 1) / BLOCK)
            throw new IOException("Invalid Keets file manifest");
        for (String hash : hashes) if (!hash.matches(HASH)) throw new IOException("Invalid file block hash");
        String base = f.torrent.substring(0, f.torrent.indexOf("/manifest?")) + "/";
        Files.createDirectories(directory);
        Path payload = directory.resolve("payload.bin");
        try (RandomAccessFile file = new RandomAccessFile(payload.toFile(), "rw")) {
            if (file.length() > total) file.setLength(total);
            long offset = 0;
            for (int i = 0; i < hashes.length; i++) {
                check(cancelled);
                int count = (int)Math.min(BLOCK, total - offset);
                byte[] data = new byte[count]; boolean cached = false;
                if (file.length() >= offset + count) {
                    file.seek(offset); file.readFully(data); cached = sha(data).equals(hashes[i]);
                }
                if (!cached) {
                    data = checkedFetch(transport, base + i + "?hash=" + hashes[i], count);
                    if (data.length != count) throw new IOException("Incomplete file block");
                    check(cancelled); file.seek(offset); file.write(data);
                }
                offset += count; progress.accept(Math.min(f.size, offset));
            }
            file.setLength(total);
        }
        FileCrypto.decrypt(payload, directory.resolve("verified.bin"), f, cancelled);
    }

    private static byte[] checkedFetch(KeetsTransport transport, String url, int max) throws Exception {
        byte[] bytes = transport.fetch(url, max);
        if (bytes == null || bytes.length > max || !url.endsWith("?hash=" + sha(bytes)))
            throw new IOException("File block verification failed");
        return bytes;
    }
    private static int readBlock(InputStream in, byte[] bytes) throws IOException {
        int n = 0, r;
        while (n < bytes.length && (r = in.read(bytes, n, bytes.length - n)) != -1) n += r;
        return n;
    }
    private static String sha(byte[] bytes) throws Exception {
        return FileCrypto.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transfer paused");
    }
    private KeetsFiles() { }
}
