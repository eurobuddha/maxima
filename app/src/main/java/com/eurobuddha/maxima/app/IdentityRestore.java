package com.eurobuddha.maxima.app;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import java.io.File;

/**
 * The shared "become a restored identity" teardown: stop the transport, wait for
 * it to finish flushing, wipe the on-device node + chat state, and restart on the
 * new seed. Used by both the Settings "Restore a seed phrase" flow and the
 * encrypted-backup restore, so the fund-critical ordering lives in exactly one
 * place.
 *
 * FUND-CRITICAL ORDERING: onDestroy flushes node + chat state to the very dirs we
 * clear, so wiping on a fixed delay lets the OLD identity be re-persisted on top
 * of the restore. We wait for the service to publish {@code node()==null} (its
 * last teardown act), then wipe + restart. Hard-capped so it never hangs.
 */
public final class IdentityRestore {

    private IdentityRestore() {
    }

    /** Warning shown on any WORDS-ONLY restore. The Winternitz key-use count is
     *  device state and cannot be recovered from the phrase alone; a full
     *  encrypted backup carries the real count, a words-only restore cannot. */
    public static final String KEYUSE_WARNING =
            "\n\nThe wallet key-use count does not travel with your words. If this "
            + "seed's key #1000 has ever signed on another wallet or node, don't "
            + "send funds until you reconcile — reusing a one-time signing key can "
            + "expose it and lose money. (A full encrypted backup preserves the "
            + "real count; a words-only restore cannot.)";

    /** Called on the main thread when the wipe + restart has been issued.
     *  {@code forced} is true if we hit the teardown-wait cap instead of a clean
     *  node()==null. */
    public interface Done {
        void run(boolean forced);
    }

    /**
     * Stop the running transport, wait for a clean teardown, then wipe node + chat
     * and restart on whatever seed {@link SeedStore} now holds.
     *
     * @param zPostTarget any live View, used only for the retry postDelayed loop.
     */
    public static void applyWithTeardown(Context zCtx, View zPostTarget, Done zDone) {
        applyWithTeardown(zCtx, zPostTarget, null, zDone);
    }

    /**
     * As above, but runs {@code zPostWipe} on the main thread AFTER the wipe and
     * BEFORE the service restarts — the only safe window to seed fresh node state
     * (e.g. restore contacts + the key-use counter from a backup) without the
     * old-then-new race or a live service caching over the write.
     */
    public static void applyWithTeardown(Context zCtx, View zPostTarget, Runnable zPostWipe, Done zDone) {
        zCtx.stopService(new Intent(zCtx, MaximaService.class));
        waitThenWipe(zCtx, zPostTarget, 0, zPostWipe, zDone);
    }

    private static void waitThenWipe(Context zCtx, View zView, int zAttempt, Runnable zPostWipe, Done zDone) {
        boolean down = MaximaService.node() == null;
        if (down || zAttempt >= 40) {   // hard cap ~10s so we never hang
            wipeDir(new File(zCtx.getFilesDir(), "node"));
            wipeDir(new File(zCtx.getFilesDir(), "chat"));
            if (zPostWipe != null) {
                zPostWipe.run();
            }
            MaximaService.start(zCtx);
            if (zDone != null) {
                zDone.run(!down);
            }
            return;
        }
        zView.postDelayed(() -> waitThenWipe(zCtx, zView, zAttempt + 1, zPostWipe, zDone), 250);
    }

    /** Recursively delete a directory's contents and the directory itself. */
    public static void wipeDir(File zDir) {
        if (zDir == null || !zDir.exists()) {
            return;
        }
        File[] fs = zDir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory()) {
                    wipeDir(f);
                } else {
                    f.delete();
                }
            }
        }
        zDir.delete();
    }
}
