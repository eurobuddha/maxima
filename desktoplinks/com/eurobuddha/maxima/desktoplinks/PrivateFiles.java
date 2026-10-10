package com.eurobuddha.maxima.desktoplinks;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Atomic owner-only writes, shared from AccountFiles. */
public final class PrivateFiles {
    private PrivateFiles() {}
    public static void write(Path zFile, byte[] zBytes) throws Exception {
        // FileStore/CloudKeyUses' write-before-rename rule, with owner-only mode from
        // creation. Keep the old file readable until its complete replacement is ready.
        Path target = zFile.toAbsolutePath();
        Path tmp;
        try {
            tmp = Files.createTempFile(target.getParent(), ".parlons-private-", ".tmp", PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            tmp = Files.createTempFile(target.getParent(), ".parlons-private-", ".tmp");
        }
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp.toFile())) {
                out.write(zBytes);
                out.getFD().sync();
            }
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException nonAtomic) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
