package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.chat.ChatFile;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** MediaCodec's AES-256/GCM primitive applied to bounded records, never a whole-file heap buffer.
 * Each record authenticates the transfer id, total length and its index. No unauthenticated
 * plaintext is published, including when a transfer is cancelled or truncated. */
public final class FileCrypto {
    public static final int PIECE = 1024 * 1024;
    public static final int PLAIN_PIECE = PIECE - 16;
    public static long cipherSize(long size) { return size + Math.max(1, (size + PLAIN_PIECE - 1) / PLAIN_PIECE) * 16; }
    public static String randomHex(int bytes) { return hex(MaximaCrypto.randomBytes(bytes)); }
    public static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte v : bytes) b.append(String.format(java.util.Locale.ROOT, "%02x", v & 255));
        return b.toString();
    }
    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length()/2];
        for (int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);
        return b;
    }
    private static Cipher cipher(int mode, String id, long size, String key, String nonce, int index) throws Exception {
        byte[] iv = ByteBuffer.allocate(12).put(unhex(nonce)).putInt(index).array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(unhex(key), "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(ByteBuffer.allocate(28).put(unhex(id)).putLong(size).putInt(index).array());
        return c;
    }
    public static String encrypt(InputStream source, Path target, String id, long size,
                                 String key, String nonce, BooleanSupplier cancelled, LongConsumer progress) throws Exception {
        if (size < 0 || size > ChatFile.MAX_BYTES) throw new IOException("Files may be up to 512 MB");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        boolean success = false;
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[PLAIN_PIECE];
            long done = 0; int index = 0;
            do {
                check(cancelled);
                int count = (int)Math.min(PLAIN_PIECE, size-done);
                readFully(source, buffer, count);
                digest.update(buffer,0,count);
                out.write(cipher(Cipher.ENCRYPT_MODE,id,size,key,nonce,index++).doFinal(buffer,0,count));
                done += count; progress.accept(done);
            } while (done < size);
            if (source.read() != -1) throw new IOException("File changed while preparing it");
            success = true;
        } finally { if (!success) Files.deleteIfExists(target); }
        return hex(digest.digest());
    }
    public static void decrypt(Path source, Path target, ChatFile f, BooleanSupplier cancelled) throws Exception {
        if (Files.size(source) != cipherSize(f.size)) throw new IOException("Incomplete file");
        Path tmp = Files.createTempFile(target.getParent(), ".verifying-", ".tmp");
        boolean success = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buffer = new byte[PIECE]; long done = 0; int index = 0;
                do {
                    check(cancelled);
                    int n = (int)Math.min(PLAIN_PIECE,f.size-done);
                    readFully(in,buffer,n+16);
                    byte[] plain = cipher(Cipher.DECRYPT_MODE,f.id,f.size,f.key,f.nonce,index++).doFinal(buffer,0,n+16);
                    digest.update(plain); out.write(plain); done += plain.length;
                } while (done < f.size);
                if (in.read() != -1 || !MessageDigest.isEqual(digest.digest(), unhex(f.digest)))
                    throw new IOException("File verification failed");
            }
            check(cancelled);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            success = true;
        } finally { if (!success) Files.deleteIfExists(tmp); }
    }
    private static void readFully(InputStream in, byte[] b, int size) throws IOException {
        int off=0;
        while(off<size) { int n=in.read(b,off,size-off); if(n<0)throw new EOFException("Incomplete file"); if(n==0)continue; off+=n; }
    }
    private static void check(BooleanSupplier cancelled) throws IOException {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transfer paused");
    }
    private FileCrypto() { }
}
