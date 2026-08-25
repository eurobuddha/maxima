package com.eurobuddha.wallet;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Minimal async loader for token icons: handles data: URIs, http(s), and ipfs:// URLs, with a
 * byte-bounded in-memory cache. All decoding (including data: URIs) runs on a worker thread.
 *
 * Ported from {@code apks/NFTwallet ImageLoader} (its {@code loadOver} lane — the identicon-base
 * pattern), retyped from that app's {@code MainActivity} to a plain {@link Activity} so it drops into
 * the Parlons wallet. The SSRF guard is kept intact: token metadata must never point our fetch at
 * loopback/LAN (e.g. the node's own RPC).
 */
public final class ImageLoader {

    // Bounded by total bitmap bytes so a few large icons can't grow without limit.
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(6 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final int THUMB_PX  = 320;                // list rows + the detail icon
    private static final int MAX_BYTES = 8 * 1024 * 1024;    // hard cap so a hostile icon url can't OOM us

    // Small shared pool — token metadata can name dozens of icon urls; don't spawn a raw thread per icon.
    private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    private ImageLoader() {
    }

    /** Load ON TOP of whatever the ImageView already shows (an identicon): keeps it on failure, replaces on
     *  success. Matches the dapp: identicon base, real graphic wins when it loads. onLoaded fires once on a
     *  fresh successful decode (so the caller can re-render). */
    public static void loadOver(final Activity act, final String url, final ImageView iv, final Runnable onLoaded) {
        iv.setTag(url);
        if (url == null || url.isEmpty()) {
            return;                                          // keep the identicon
        }
        String key = THUMB_PX + "|" + url;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        EXEC.execute(() -> {
            final Bitmap b = decode(url, THUMB_PX);
            if (b == null) {
                return;                                      // keep the identicon on failure
            }
            CACHE.put(key, b);
            act.runOnUiThread(() -> {
                if (act.isDestroyed()) {
                    return;                                  // activity gone — don't touch its views
                }
                if (url.equals(iv.getTag())) {
                    iv.setImageBitmap(b);
                }
                if (onLoaded != null) {
                    onLoaded.run();
                }
            });
        });
    }

    /** Fetch the raw bytes then decode DOWNSAMPLED to ~reqPx, so a multi-MB icon never OOMs a thumbnail.
     *  SVG (which BitmapFactory can't handle, and which many Minima token urls use) is rasterised. */
    private static Bitmap decode(String url, int reqPx) {
        try {
            byte[] bytes = url.startsWith("data:") ? dataUriBytes(url) : fetch(url);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            if (looksLikeSvg(bytes, url)) {
                return renderSvg(bytes, reqPx);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqPx);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opt);
        } catch (Throwable t) {     // includes OutOfMemoryError — a giant icon must never crash the loader
            return null;
        }
    }

    private static boolean looksLikeSvg(byte[] bytes, String url) {
        if (url != null && url.toLowerCase().contains(".svg")) {
            return true;
        }
        int n = Math.min(bytes.length, 300);
        String head = new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase();
        return head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg")) || head.contains("<svg");
    }

    /** Rasterise SVG bytes to a bitmap ≤ reqPx (preserving aspect), via AndroidSVG. */
    private static Bitmap renderSvg(byte[] bytes, int reqPx) {
        try {
            com.caverock.androidsvg.SVG svg =
                    com.caverock.androidsvg.SVG.getFromString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            float dw = svg.getDocumentWidth(), dh = svg.getDocumentHeight();
            int w = reqPx, h = reqPx;
            if (dw > 0 && dh > 0) {                          // preserve aspect if the doc declares a size
                if (dw >= dh) {
                    w = reqPx;
                    h = Math.max(1, Math.round(reqPx * dh / dw));
                } else {
                    h = reqPx;
                    w = Math.max(1, Math.round(reqPx * dw / dh));
                }
            }
            svg.setDocumentWidth(w);
            svg.setDocumentHeight(h);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            svg.renderToCanvas(new android.graphics.Canvas(bmp));
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int sampleSize(int w, int h, int reqPx) {
        int s = 1;
        int max = Math.max(w, h);
        while (max / s > reqPx) {
            s <<= 1;
        }
        return Math.max(1, s);
    }

    private static byte[] fetch(String url) throws Exception {
        String f = url.startsWith("ipfs://") ? "https://ipfs.io/ipfs/" + url.substring("ipfs://".length()) : url;
        URL u = new URL(f);
        if (isBlockedHost(u.getHost())) {
            return null;   // token metadata must not point us at loopback/LAN (e.g. the node RPC)
        }
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(15000);
        con.setInstanceFollowRedirects(true);
        try (InputStream in = con.getInputStream(); java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    return null;    // oversized response — bail, keep the identicon
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            con.disconnect();
        }
    }

    /** True for loopback / any-local / link-local / site-local (private) hosts, or anything unresolvable. */
    static boolean isBlockedHost(String host) {
        if (host == null || host.isEmpty()) {
            return true;
        }
        try {
            for (java.net.InetAddress a : java.net.InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) {
                    return true;
                }
            }
        } catch (Exception e) {
            return true;
        }
        return false;
    }

    private static byte[] dataUriBytes(String dataUri) {
        int comma = dataUri.indexOf(',');
        if (comma < 0) {
            return null;
        }
        if (!dataUri.substring(0, comma).contains("base64")) {
            return null;
        }
        return Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
    }
}
