package com.eurobuddha.maxima.app.portal.ide.terminal;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;

/**
 * Clipboard / file / share plumbing for terminal output.
 *
 * Everything here is sized against the Android Binder: clipboard writes and Intent
 * extras both cross it, and a terminal session (300 blocks of pretty-printed JSON)
 * comfortably exceeds the ~1MB transaction cap. Anything over MAX_INLINE_BYTES is
 * routed through a real file instead of an in-band extra.
 */
public final class SessionExport {

    /** Matches the app-side 256KB IPC ceiling — well under the Binder cap, with headroom. */
    public static final int MAX_INLINE_BYTES = 256 * 1024;

    /** Cached share files older than this are pruned on the next share. */
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    private SessionExport() {}

    // ---------------- naming ----------------

    /** e.g. minima-terminal-20260801-142233.txt */
    public static String timestampedName(String prefix) {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%s-%04d%02d%02d-%02d%02d%02d.txt", prefix,
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    public static int utf8Len(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    // ---------------- clipboard ----------------

    /**
     * Copy to the clipboard as plain text (spans stripped). Returns false, with a
     * toast explaining the alternative, when the text is too big to survive the Binder.
     */
    public static boolean copy(Context ctx, CharSequence text, String label) {
        String plain = text == null ? "" : text.toString();
        if (plain.trim().isEmpty()) {
            Toast.makeText(ctx, "Nothing to copy", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (utf8Len(plain) > MAX_INLINE_BYTES) {
            Toast.makeText(ctx, "Too big for the clipboard — use Export session → Save to file",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        ClipboardManager cb = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb == null) {
            Toast.makeText(ctx, "No clipboard available", Toast.LENGTH_SHORT).show();
            return false;
        }
        try {
            cb.setPrimaryClip(ClipData.newPlainText(label, plain));
        } catch (RuntimeException e) {
            Toast.makeText(ctx, "Copy failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
        // Android 13+ shows its own clipboard confirmation; don't double up.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(ctx, "Copied " + plain.length() + " chars", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    // ---------------- save to file (SAF) ----------------

    /** Intent for the system file picker — no storage permission needed. */
    public static Intent createDocumentIntent(String filename) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, filename);
        return i;
    }

    /** Write text to a SAF-provided uri, truncating anything already there. */
    public static void writeTo(Context ctx, Uri uri, String text) throws IOException {
        OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt");
        if (os == null) throw new IOException("could not open the chosen file");
        try {
            os.write(text.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } finally {
            try { os.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------- share ----------------

    /**
     * Share the text. Small payloads go inline as EXTRA_TEXT; large ones are written
     * to the cache and shared as a FileProvider attachment, because an oversized
     * EXTRA_TEXT kills the app with TransactionTooLargeException.
     */
    public static void share(Activity act, String text, String filename, String chooserTitle) {
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(act, "Nothing to share", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send;
        if (utf8Len(text) <= MAX_INLINE_BYTES) {
            send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            send.putExtra(Intent.EXTRA_SUBJECT, filename);
        } else {
            try {
                send = fileShareIntent(act, text, filename);
            } catch (IOException e) {
                Toast.makeText(act, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
        }
        try {
            act.startActivity(Intent.createChooser(send, chooserTitle));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(act, "No app available to share with", Toast.LENGTH_LONG).show();
        }
    }

    private static Intent fileShareIntent(Context ctx, String text, String filename) throws IOException {
        File dir = new File(ctx.getCacheDir(), "export");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create the export folder");
        prune(dir);

        File out = new File(dir, filename);
        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } finally {
            try { fos.close(); } catch (IOException ignored) {}
        }

        Uri uri = FileProvider.getUriForFile(ctx, "com.eurobuddha.parlons.cloud.payloads", out);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, filename);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return send;
    }

    /** Drop yesterday's exports so the cache can't grow without bound. */
    private static void prune(File dir) {
        File[] old = dir.listFiles();
        if (old == null) return;
        long cutoff = System.currentTimeMillis() - CACHE_TTL_MS;
        for (File f : old) {
            if (f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
