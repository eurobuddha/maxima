package com.eurobuddha.maxima.app.portal.wallet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Turns a picked image into base64 small enough to live on-chain.
 *
 * Ported from Atelier (mds/statenft-suite) so both apps seal plates of the same quality. Nothing
 * here is contract-affecting: the payload is still a single base64 blob in state port 1 wrapped in
 * {@code [...]}, exactly as the token script's {@code SAMESTATE(0 1)} expects.
 *
 * Two rules this must get right, because what it produces is sealed into a locked-edition coin and
 * can never be corrected:
 *
 *  1. ORIENTATION. Decoding goes through {@link ImageDecoder}, which applies JPEG EXIF and HEIF
 *     orientation exactly once. The old {@code BitmapFactory} path ignored the tag entirely and
 *     stamped portrait photos sideways; the manual rotation that fixed it is gone, because doing
 *     both would rotate twice.
 *  2. QUALITY. A resolution-first WebP search: WebP buys roughly 30% quality per byte over JPEG,
 *     and for photographs a larger plate at moderate quality reads better than a small plate at
 *     high quality — so the first fit at the LARGEST dimension wins.
 */
public final class ImageTools {

    /**
     * Budgets in base64 chars.
     *
     * STATE_IMG rides TWICE in a transfer (input-coin proof + recreated output state) against the
     * 64 KB TxPoW cap, which is what bounds it — 16000 is 32 KB on the wire, half the cap.
     * Adopted from Atelier, whose {@code mint/image-budget-spike.sh} records a stamp plus
     * double-carry transfer confirmed on-chain 2026-08-05 with state intact. That run logged no
     * token id, so re-prove it with a real transfer before trusting it further.
     */
    public static final int STATE_IMG_BUDGET = 16000;
    /** 1-of-1 NFT art carried in token metadata. */
    public static final int ARTIMAGE_BUDGET = 9000;
    /** Collection/token icon — rides in every copy of the token record, so keep it cheap. */
    public static final int ICON_BUDGET = 6000;

    /** Absurd for flat art, and a guard against decompression bombs. */
    private static final int MAX_SVG_SOURCE = 256 * 1024;

    private ImageTools() {}

    // ===================== SVG lane =====================

    /** True when the picked content is an SVG document (declared type, extension, or sniff). */
    public static boolean isSvgUri(Context c, Uri uri) {
        try {
            if ("image/svg+xml".equals(c.getContentResolver().getType(uri))) return true;
            if (uri.toString().toLowerCase().endsWith(".svg")) return true;
            // The picker asks for image/*, so most SVGs arrive with a vague type — sniff the head.
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) return false;
                byte[] head = new byte[256];
                int n = in.read(head);
                if (n <= 0) return false;
                return SvgSanitizer.isSvg(new String(head, 0, n, StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Read, sanitize and base64 an SVG. Returns "" when it isn't valid SVG, can't be made inert,
     * or exceeds the budget — vector is accepted whole or refused, never silently degraded.
     */
    public static String svgBase64FromUri(Context c, Uri uri, int budget) {
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (in == null) return "";
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_SVG_SOURCE) return "";
                bos.write(buf, 0, n);
            }
            String clean = SvgSanitizer.sanitize(bos.toString("UTF-8"));
            if (clean == null || clean.isEmpty()) return "";
            String b64 = Base64.encodeToString(clean.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            return b64.length() <= budget ? b64 : "";
        } catch (Throwable t) {
            return "";
        }
    }

    // ===================== raster lane =====================

    /** Decode via ImageDecoder — the platform applies EXIF/HEIF orientation exactly once. */
    public static String compressUri(Context c, Uri uri, int budget) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), uri);
        Bitmap bmp = ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(false);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            int max = Math.max(w, h);
            if (max > 2048) {   // pre-cap: a 108MP original would OOM before we ever scale it
                float s = 2048f / max;
                decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
            }
        });
        if (bmp == null) return "";
        return compressBitmap(bmp, budget);
    }

    /** Re-encode an already-sealed payload to fit a (smaller) budget. */
    public static String recompressBase64(String b64, int budget) {
        if (b64 == null || b64.isEmpty()) return "";
        if (b64.length() <= budget) return b64;
        return rotateBase64(b64, 0, budget);
    }

    /** Rotate an already-encoded plate. Its bytes carry no EXIF, so this is the only way to turn it. */
    public static String rotateBase64(String b64, int degrees, int budget) {
        if (b64 == null || b64.isEmpty()) return "";
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (src == null) return "";
            Bitmap out = src;
            if (degrees != 0) {
                Matrix m = new Matrix();
                m.postRotate(degrees);
                out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
                if (out != src) src.recycle();
            }
            return compressBitmap(out, budget);
        } catch (Throwable t) {
            return "";
        }
    }

    /* The ladder MUST reach the floor: a 300px/q60 stop refused budgets a
     * smaller rung fits easily (Atelier, 2026-08-10/11). Raster always fits. */
    private static final int[] DIMS = {1080, 900, 720, 560, 420, 300, 240, 180, 140};
    private static final int[] QUALITIES = {88, 78, 68, 60, 50, 42};

    private static String compressBitmap(Bitmap src, int budget) {
        for (int dim : DIMS) {
            float scale = Math.min(1f, dim / (float) Math.max(src.getWidth(), src.getHeight()));
            Bitmap scaled = Bitmap.createScaledBitmap(src,
                    Math.max(1, Math.round(src.getWidth() * scale)),
                    Math.max(1, Math.round(src.getHeight() * scale)), true);
            for (int quality : QUALITIES) {
                String b64 = encode(scaled, quality);
                if (!b64.isEmpty() && b64.length() <= budget) return b64;
            }
            if (scaled != src) scaled.recycle();
        }
        // last resort: halve dimensions at floor quality until it fits —
        // every fixed slim target in the app relies on this never failing
        for (int dim = 128; dim >= 16; dim /= 2) {
            float scale = Math.min(1f, dim / (float) Math.max(src.getWidth(), src.getHeight()));
            Bitmap scaled = Bitmap.createScaledBitmap(src,
                    Math.max(1, Math.round(src.getWidth() * scale)),
                    Math.max(1, Math.round(src.getHeight() * scale)), true);
            String b64 = encode(scaled, 40);
            if (!b64.isEmpty() && b64.length() <= budget) return b64;
        }
        return "";
    }

    @SuppressWarnings("deprecation")
    private static String encode(Bitmap bmp, int quality) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Bitmap.CompressFormat fmt = android.os.Build.VERSION.SDK_INT >= 30
                    ? Bitmap.CompressFormat.WEBP_LOSSY
                    : Bitmap.CompressFormat.WEBP;
            if (!bmp.compress(fmt, quality, out)) {
                out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
            }
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    // ===================== reading a sealed payload back =====================

    /**
     * The correct data URI for a sealed payload, sniffed from its magic bytes rather than assumed.
     * Plates are WebP now, were JPEG before, and may be SVG — a hardcoded label is simply wrong,
     * and any consumer that trusts it breaks.
     */
    public static String dataUri(String b64) {
        if (b64 == null || b64.isEmpty()) return "";
        return "data:" + mimeOf(b64) + ";base64," + b64;
    }

    public static String mimeOf(String b64) {
        try {
            int take = Math.min(b64.length(), 32);
            take -= take % 4;   // only decode a whole number of base64 quanta
            byte[] head = java.util.Base64.getMimeDecoder().decode(b64.substring(0, take));
            if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                return "image/webp";
            }
            if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) return "image/jpeg";
            if (head.length >= 4 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return "image/png";
            }
            String text = new String(head, StandardCharsets.UTF_8).trim().toLowerCase();
            if (text.startsWith("<svg") || text.startsWith("<?xml")) return "image/svg+xml";
        } catch (Throwable ignored) {}
        return "image/jpeg";
    }
}
