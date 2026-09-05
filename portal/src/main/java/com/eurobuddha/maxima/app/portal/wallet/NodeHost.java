package com.eurobuddha.maxima.app.portal.wallet;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import com.eurobuddha.maxima.app.portal.CloudSession;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import java.util.Locale;

/**
 * "Host on my node": NFT art uploaded from this phone over the paired channel and served by the
 * node's public TLS front, so the token metadata can link to it externally. Single files are
 * content-addressed; a State-NFT collection gets a folder whose URL is {@code base + index + ext}.
 */
final class NodeHost {

    /** 32 MB — the node's own ceiling per hosted file. */
    static final long MAX_BYTES = 32L * 1024 * 1024;

    interface Cb {
        void done(String url, String path, String sha256);
        void fail(String why);
        default void progress(String note) { }
    }

    private NodeHost() {}

    /** The file extension the node should store this picked image under (from its MIME type). */
    static String extOf(Context c, Uri u) {
        String mime = "";
        try { mime = String.valueOf(c.getContentResolver().getType(u)); } catch (Exception ignored) { }
        mime = mime.toLowerCase(Locale.ROOT);
        if (mime.contains("png")) return "png";
        if (mime.contains("jpeg") || mime.contains("jpg")) return "jpg";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("gif")) return "gif";
        if (mime.contains("svg")) return "svg";
        if (mime.contains("avif")) return "avif";
        String p = String.valueOf(u.getLastPathSegment()).toLowerCase(Locale.ROOT);
        int dot = p.lastIndexOf('.');
        if (dot > 0 && p.length() - dot <= 6) return p.substring(dot + 1);
        return "png";
    }

    static byte[] readAll(Context c, Uri u) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new IllegalStateException("could not open the image");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IllegalStateException("that file is over 32 MB");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Host one file. The session is only borrowed to obtain the remote; the upload itself runs on
     *  the wallet's own lane ({@link NodeApi#WALLET}), never on CloudSession's shared interactive
     *  lane (a 30 MB upload there would freeze the status pill and every page behind it).
     *  Callbacks on the main thread. */
    static void upload(final Activity act, final Uri uri, final Cb cb) {
        NodeApi.WALLET.execute(() -> {
            final byte[] bytes;
            final String ext;
            try {
                ext = extOf(act, uri);
                byte[] raw = readAll(act, uri);
                // Hosted files are served from the operator's web origin: strip scripts, foreign
                // objects and event handlers from SVG before it ever leaves the phone.
                if ("svg".equals(ext)) {
                    String clean = SvgSanitizer.sanitize(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
                    raw = clean.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                bytes = raw;
            } catch (Exception e) {
                act.runOnUiThread(() -> cb.fail(e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }
            CloudSession.connectInteractive(act, new CloudSession.Cb() {
                @Override public void ok(ParlonsRemote r) {
                    NodeApi.WALLET.execute(() -> {
                        try {
                            JSONObject o = r.nftPut(bytes, ext, "", 0, (sent, total) ->
                                    act.runOnUiThread(() -> cb.progress("Uploading to your node… "
                                            + (sent * 100 / Math.max(1, total)) + "%")));
                            if (!Boolean.TRUE.equals(o.get("ok"))) {
                                final String why = String.valueOf(o.getOrDefault("error", "the node refused the upload"));
                                act.runOnUiThread(() -> cb.fail(why));
                                return;
                            }
                            final String url = String.valueOf(o.getOrDefault("url", ""));
                            final String path = String.valueOf(o.getOrDefault("path", ""));
                            final String sha = String.valueOf(o.getOrDefault("sha256", ""));
                            act.runOnUiThread(() -> cb.done(url, path, sha));
                        } catch (Exception e) {
                            final String why = e.getMessage() == null ? e.toString() : e.getMessage();
                            act.runOnUiThread(() -> cb.fail(why));
                        }
                    });
                }
                @Override public void err(String m) {
                    act.runOnUiThread(() -> cb.fail("Can't reach your node: " + m));
                }
            });
        });
    }
}
