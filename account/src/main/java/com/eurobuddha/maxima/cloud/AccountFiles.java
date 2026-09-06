package com.eurobuddha.maxima.cloud;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Supplier;

/**
 * The two files a person (or an installer) reads to pair a device, kept beside the account's data
 * by every account host - parlons-cloud, a tenants host and a Parlons Node alike:
 * <ul>
 *   <li>{@code account.txt} - the permanent {@code MAX#…} address, once the account has attached;</li>
 *   <li>{@code invite.txt} - {@code MAX#…?code=XXXX-XXXX-XXXX}: the address plus the one-time
 *       pairing code from {@code pair-code.txt}, rewritten whenever a fresh code is minted. One QR
 *       of it pairs a phone. Owner-only mode, like the code file.</li>
 * </ul>
 */
public final class AccountFiles {

    public static final String ACCOUNT_FILE = "account.txt";
    public static final String INVITE_FILE = "invite.txt";
    public static final String CODE_FILE = "pair-code.txt";

    private AccountFiles() {
    }

    /** {@code MAX#…?code=…}; null until both halves exist. A bare Mx address is not permanent. */
    public static String invite(String zPermanent, String zCode) {
        if (zPermanent == null || !zPermanent.trim().startsWith("MAX#")
                || zCode == null || zCode.trim().isEmpty()) {
            return null;
        }
        return zPermanent.trim() + "?code=" + zCode.trim();
    }

    /**
     * Bring the two files up to date for one account dir.
     * @return true when a file was (re)written
     */
    public static boolean refresh(Path zDir, String zPermanent) throws Exception {
        if (zPermanent == null || !zPermanent.startsWith("MAX#")) {
            return false;
        }
        boolean wrote = false;
        Path account = zDir.resolve(ACCOUNT_FILE);
        if (!Files.isRegularFile(account)
                || !zPermanent.equals(new String(Files.readAllBytes(account), StandardCharsets.UTF_8).trim())) {
            Files.write(account, (zPermanent + "\n").getBytes(StandardCharsets.UTF_8));
            wrote = true;
        }
        Path codeFile = zDir.resolve(CODE_FILE);
        Path invite = zDir.resolve(INVITE_FILE);
        if (Files.isRegularFile(codeFile)) {
            String code = new String(Files.readAllBytes(codeFile), StandardCharsets.UTF_8).trim();
            String inv = invite(zPermanent, code);
            if (inv != null) {
                boolean stale = !Files.isRegularFile(invite)
                        || Files.getLastModifiedTime(codeFile).toMillis() > Files.getLastModifiedTime(invite).toMillis()
                        || !inv.equals(new String(Files.readAllBytes(invite), StandardCharsets.UTF_8).trim());
                if (stale) {
                    writePrivate(invite, (inv + "\n").getBytes(StandardCharsets.UTF_8));
                    wrote = true;
                }
            }
        } else if (Files.isRegularFile(invite)) {
            Files.delete(invite);   // the code was consumed: no outstanding invite
            wrote = true;
        }
        return wrote;
    }

    /** A daemon thread that keeps the files current every few seconds for the life of the process. */
    public static Thread startRefresher(Path zDir, Supplier<String> zPermanent, long zEveryMs) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(zEveryMs);
                    refresh(zDir, zPermanent.get());
                } catch (InterruptedException ie) {
                    return;
                } catch (Exception ignored) {
                    // transient (address not known yet); tried again next tick
                }
            }
        }, "parlons-account-files");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void writePrivate(Path zFile, byte[] zBytes) throws Exception {
        Files.deleteIfExists(zFile);
        try {
            Files.createFile(zFile, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            // plain create below
        }
        Files.write(zFile, zBytes);
    }
}
