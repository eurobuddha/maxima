package com.eurobuddha.maxima.app.portal.wallet;

import android.util.Base64;

import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the utxoWallet dapp's resolveTokenIcon (index.html) — turns a raw token url/icon string into
 * something loadable (a data: URI or an http(s) URL), matching the canonical Minima wallet shape:
 *  1. {@code <artimage>BASE64</artimage>} wrapper → data:image/jpeg;base64  (the PRIMARY real-token format)
 *  2. data:image/* URI
 *  3. http(s):// URL
 *  4. inline &lt;svg&gt;…&lt;/svg&gt; → data:image/svg+xml;base64 (note: native BitmapFactory can't decode SVG,
 *     so these fall back to the identicon)
 *  5. raw base64 → data:image/png;base64
 * Returns null when nothing image-shaped is present (caller draws the identicon).
 */
public final class IconResolver {

    private static final Pattern PCT      = Pattern.compile("%[0-9A-Fa-f]{2}");
    private static final Pattern ARTIMAGE = Pattern.compile("<artimage[^>]*>([\\s\\S]*?)</artimage>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTIMAGE_OPEN = Pattern.compile("^<artimage[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_IMG = Pattern.compile("^data:image/(png|jpe?g|gif|webp|svg\\+xml|x-icon|bmp);", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP     = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern B64      = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");

    private IconResolver() {}

    public static String resolve(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (PCT.matcher(t).find()) {
            try { t = URLDecoder.decode(t, "UTF-8").trim(); } catch (Exception ignore) {}
        }

        if (t.regionMatches(true, 0, "<artimage", 0, 9)) {
            Matcher m = ARTIMAGE.matcher(t);
            if (m.find()) {
                String b64 = m.group(1).replaceAll("\\s+", "");
                if (b64.length() > 16) return ImageTools.dataUri(b64);
            }
            // Tolerate an UNTERMINATED <artimage> prefix: earlier builds of this wallet wrote the
            // opening tag without a closing one, and those tokens are sealed on-chain forever — so
            // the reader has to forgive it or their art never shows.
            Matcher open = ARTIMAGE_OPEN.matcher(t);
            if (open.find()) {
                String b64 = t.substring(open.end()).replaceAll("\\s+", "");
                if (b64.length() > 16 && B64.matcher(b64).matches()) return ImageTools.dataUri(b64);
            }
        }
        if (DATA_IMG.matcher(t).find()) return t;
        if (HTTP.matcher(t).find()) return t;
        if (t.regionMatches(true, 0, "<svg", 0, 4) && t.toLowerCase().endsWith("</svg>")) {
            try { return "data:image/svg+xml;base64," + Base64.encodeToString(t.getBytes("UTF-8"), Base64.NO_WRAP); }
            catch (Exception e) { return null; }
        }
        if (t.length() >= 100 && B64.matcher(t).matches()) return "data:image/png;base64," + t;
        return null;
    }
}
