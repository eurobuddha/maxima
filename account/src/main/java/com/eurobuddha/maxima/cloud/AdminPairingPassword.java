package com.eurobuddha.maxima.cloud;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** Local operator password for issuing administrative pairing invitations.
 * Reuses the existing scrypt/AES-GCM password container; never stores the password. */
public final class AdminPairingPassword {
    public static final String FILE = ".admin-pairing-password";
    private static final byte[] PURPOSE = "Parlons administrator pairing v1".getBytes(StandardCharsets.UTF_8);
    private final Path file;
    private long nextAttempt;

    public AdminPairingPassword(Path data) { file = data.resolve(FILE); }

    public void set(char[] password) throws Exception {
        if (password.length < 10 || password.length > 1024)
            throw new IllegalArgumentException("Use an admin password of 10–1024 characters");
        AccountFiles.writePrivate(file, BackupCrypto.encrypt(PURPOSE, password));
    }

    public synchronized boolean verify(char[] password) throws Exception {
        if (password.length == 0 || password.length > 1024) return false;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Set the admin pairing password on the server with: sudo parlons-pair --set-admin-password");
        if (Files.size(file) > 1024) throw new IllegalStateException("Admin pairing configuration is invalid");
        long now = System.nanoTime();
        if (now < nextAttempt) throw new IllegalStateException("Wait a few seconds before trying the admin password again");
        nextAttempt = now + 2_000_000_000L;
        byte[] plain = null;
        try {
            plain = BackupCrypto.decrypt(Files.readAllBytes(file), password);
            return MessageDigest.isEqual(PURPOSE, plain);
        } catch (javax.crypto.AEADBadTagException wrong) { return false; }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    /** Standalone operator command: never boots an account or Minima node. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !("set".equals(args[0]) || "verify".equals(args[0])))
            throw new IllegalArgumentException("Usage: AdminPairingPassword set|verify <node-data-directory>");
        java.io.Console console = System.console();
        if (console == null) { System.err.println("Use an interactive SSH terminal (ssh -t) for the admin password"); System.exit(1); return; }
        AdminPairingPassword guard = new AdminPairingPassword(Paths.get(args[1]));
        char[] password = console.readPassword("Admin password (Enter for a guest invitation): ");
        if (password == null || password.length == 0) { System.exit(2); return; }
        int result = 0;
        try {
            if ("set".equals(args[0])) {
                char[] confirmation = console.readPassword("Confirm admin password: ");
                try {
                    if (!Arrays.equals(password, confirmation)) throw new IllegalArgumentException("Passwords do not match");
                    guard.set(password); System.out.println("Admin pairing password saved.");
                } finally { if (confirmation != null) Arrays.fill(confirmation, '\0'); }
            } else if (!guard.verify(password)) { System.err.println("Incorrect admin password"); result = 1; }
            else {
                DevicePairing pairing = new DevicePairing(Paths.get(args[1]));
                if (!pairing.hasBootstrapCode()) pairing.newBootstrapCode();
            }
        } finally { Arrays.fill(password, '\0'); }
        System.exit(result);
    }
}
