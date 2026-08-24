package com.eurobuddha.maxima.app.backup;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.Toast;

import com.eurobuddha.maxima.app.IdentityRestore;
import com.eurobuddha.maxima.app.MlsStore;
import com.eurobuddha.maxima.app.SeedStore;
import com.eurobuddha.maxima.core.store.FileStore;
import com.eurobuddha.wallet.PrefsKeyUses;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Orchestrates the encrypted identity backup: collect → encrypt (export), and
 * decrypt → apply (restore). Restore is REPLACE, not merge — atomically "become
 * this backup" — reusing the same fund-critical teardown/wipe path as a
 * seed-only restore, but writing contacts + the key-use counter into the fresh
 * node state in the post-wipe / pre-start window.
 */
public final class BackupManager {

    /** Contact collection name in the node FileStore (MaximaNode.C_CONTACTS). */
    private static final String CONTACTS = "contacts";

    private BackupManager() {
    }

    public static byte[] export(Context zCtx, char[] zPassword) throws Exception {
        String phrase = SeedStore.revealPhrase(zCtx);
        if (phrase == null || phrase.isEmpty()) {
            throw new IllegalStateException("No identity to back up");
        }
        BackupBundle b = new BackupBundle();
        b.phrase = phrase;
        b.displayName = SeedStore.displayName(zCtx);
        b.mls = MlsStore.get(zCtx);
        b.contacts = new FileStore(new File(zCtx.getFilesDir(), "node")).all(CONTACTS);
        b.keyUses = PrefsKeyUses.exportAll(zCtx);
        byte[] json = b.toJson().getBytes(StandardCharsets.UTF_8);
        return BackupCrypto.encrypt(json, zPassword);
    }

    public static BackupBundle read(byte[] zBlob, char[] zPassword) throws Exception {
        byte[] json = BackupCrypto.decrypt(zBlob, zPassword);
        BackupBundle b = BackupBundle.fromJson(new String(json, StandardCharsets.UTF_8));
        if (b.version > BackupBundle.CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "This backup was made by a newer Parlons — update the app first.");
        }
        if (b.phrase == null || b.phrase.isEmpty()) {
            throw new IllegalArgumentException("Backup has no seed phrase");
        }
        return b;
    }

    /** Become this backup: swap seed/name/MLS, wipe, then seed the fresh node with
     *  contacts + the real key-use counter before the transport restarts. */
    public static void apply(Activity zAct, BackupBundle zBundle) {
        SeedStore.importPhrase(zAct, zBundle.phrase);
        SeedStore.setDisplayName(zAct, zBundle.displayName);
        MlsStore.save(zAct, zBundle.mls);
        View post = zAct.getWindow().getDecorView();
        IdentityRestore.applyWithTeardown(zAct, post,
                () -> {
                    // post-wipe, pre-start: safe to write fresh node state.
                    FileStore store = new FileStore(new File(zAct.getFilesDir(), "node"));
                    for (Map.Entry<String, String> e : zBundle.contacts.entrySet()) {
                        store.put(CONTACTS, e.getKey(), e.getValue());
                    }
                    store.flush();
                    PrefsKeyUses.importRaiseOnly(zAct, zBundle.keyUses);
                },
                forced -> Toast.makeText(zAct,
                        "Backup restored — reconnecting", Toast.LENGTH_LONG).show());
    }
}
