package com.eurobuddha.maxima.desktop.ui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Desktop counterpart of the phone's readScaledJpeg + applyExifOrientation
 * (ChatActivity): decode a picked image, ROTATE it to match the source's EXIF
 * orientation (Swing/ImageIO never does this automatically, so a phone photo
 * ships sideways otherwise), downscale the long edge, and re-encode a modest
 * JPEG. Best-effort throughout: any failure returns the original bytes so the
 * send falls back exactly as before.
 */
final class DesktopImagePrep {

    /** Long-edge bound, matching the phone's fitClassicInline ceiling. */
    static final int MAX_DIM = 900;
    /** JPEG re-encode quality, matching the phone's 0.82. */
    static final float QUALITY = 0.82f;

    private DesktopImagePrep() { }

    /**
     * Normalise a picked photo for sending. Only touches raster images we can
     * decode; anything else (or any failure) returns {@code zBytes} unchanged.
     * Always emits JPEG on success — the caller must send it as image/jpeg.
     *
     * @return the prepared bytes, and whether they are now JPEG.
     */
    static Result prepare(byte[] zBytes, String zMime) {
        try {
            if (zMime == null || !zMime.startsWith("image/")) return new Result(zBytes, false);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(zBytes));
            if (img == null) return new Result(zBytes, false);

            int orientation = readExifOrientation(zBytes);   // 1..8, 1 == normal
            img = applyOrientation(img, orientation);

            int w = img.getWidth();
            int h = img.getHeight();
            int big = Math.max(w, h);
            if (big > MAX_DIM) {
                double s = (double) MAX_DIM / big;
                int nw = Math.max(1, (int) Math.round(w * s));
                int nh = Math.max(1, (int) Math.round(h * s));
                BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(img, 0, 0, nw, nh, null);
                g.dispose();
                img = scaled;
            } else if (img.getType() == BufferedImage.TYPE_CUSTOM
                    || img.getColorModel().hasAlpha()) {
                // JPEG can't carry alpha; flatten onto white so PNGs survive.
                BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, w, h);
                g.drawImage(img, 0, 0, null);
                g.dispose();
                img = rgb;
            }

            byte[] jpeg = encodeJpeg(img, QUALITY);
            return new Result(jpeg, true);
        } catch (Throwable t) {
            return new Result(zBytes, false);
        }
    }

    static final class Result {
        final byte[] bytes;
        final boolean jpeg;
        Result(byte[] zBytes, boolean zJpeg) { bytes = zBytes; jpeg = zJpeg; }
    }

    private static byte[] encodeJpeg(BufferedImage zImg, float zQuality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        if (p.canWriteCompressed()) {
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(zQuality);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(zImg, null, null), p);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    /** Rotate/flip the decoded image to match the EXIF orientation code (1..8).
     *  Uses the canonical AffineTransform recipe; codes 5..8 swap the dims. */
    private static BufferedImage applyOrientation(BufferedImage zImg, int zOrientation) {
        if (zOrientation <= 1) return zImg;
        int w = zImg.getWidth();
        int h = zImg.getHeight();
        AffineTransform t;
        int nw = w;
        int nh = h;
        switch (zOrientation) {
            case 2:  t = new AffineTransform(-1, 0, 0, 1, w, 0); break;          // flip X
            case 3:  t = new AffineTransform(-1, 0, 0, -1, w, h); break;         // rotate 180
            case 4:  t = new AffineTransform(1, 0, 0, -1, 0, h); break;          // flip Y
            case 5:  t = new AffineTransform(0, 1, 1, 0, 0, 0); nw = h; nh = w; break;        // transpose
            case 6:  t = new AffineTransform(0, 1, -1, 0, h, 0); nw = h; nh = w; break;       // rotate 90 CW
            case 7:  t = new AffineTransform(0, -1, -1, 0, h, w); nw = h; nh = w; break;      // transverse
            case 8:  t = new AffineTransform(0, -1, 1, 0, 0, w); nw = h; nh = w; break;       // rotate 90 CCW
            default: return zImg;
        }
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(zImg, t, null);
        g.dispose();
        return out;
    }

    /**
     * Parse the EXIF orientation tag (0x0112) straight out of a JPEG's APP1
     * segment. Returns 1 (normal) when there is no EXIF or on any malformed
     * input — never throws.
     */
    private static int readExifOrientation(byte[] b) {
        try {
            if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return 1;
            int i = 2;
            while (i + 4 < b.length) {
                if ((b[i] & 0xFF) != 0xFF) { i++; continue; }
                int marker = b[i + 1] & 0xFF;
                if (marker == 0xD9 || marker == 0xDA) break;   // EOI / start of scan
                int segLen = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
                if (segLen < 2) break;
                if (marker == 0xE1 && i + 4 + 6 <= b.length
                        && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i'
                        && b[i + 7] == 'f' && b[i + 8] == 0 && b[i + 9] == 0) {
                    return parseTiffOrientation(b, i + 10);
                }
                i += 2 + segLen;
            }
        } catch (Exception ignored) { }
        return 1;
    }

    /** TIFF block starting at {@code off}: byte order, magic, IFD0, tag 0x0112. */
    private static int parseTiffOrientation(byte[] b, int off) {
        if (off + 8 > b.length) return 1;
        boolean little;
        int b0 = b[off] & 0xFF, b1 = b[off + 1] & 0xFF;
        if (b0 == 0x49 && b1 == 0x49) little = true;          // "II"
        else if (b0 == 0x4D && b1 == 0x4D) little = false;    // "MM"
        else return 1;
        int ifdOff = readInt(b, off + 4, little);
        int ifd = off + ifdOff;
        // ifdOff is attacker-controlled: guard against negative/overflow so we
        // never index with a negative offset (the outer catch would swallow the
        // AIOOBE, but be explicit).
        if (ifdOff < 0 || ifd < off || ifd + 2 > b.length) return 1;
        int count = readShort(b, ifd, little);
        int entry = ifd + 2;
        for (int e = 0; e < count && entry + 12 <= b.length; e++, entry += 12) {
            int tag = readShort(b, entry, little);
            if (tag == 0x0112) {
                int val = readShort(b, entry + 8, little);   // SHORT value, left-aligned
                return (val >= 1 && val <= 8) ? val : 1;
            }
        }
        return 1;
    }

    private static int readShort(byte[] b, int o, boolean little) {
        int a = b[o] & 0xFF, c = b[o + 1] & 0xFF;
        return little ? (c << 8) | a : (a << 8) | c;
    }

    private static int readInt(byte[] b, int o, boolean little) {
        int a = b[o] & 0xFF, c = b[o + 1] & 0xFF, d = b[o + 2] & 0xFF, e = b[o + 3] & 0xFF;
        return little ? (e << 24) | (d << 16) | (c << 8) | a
                      : (a << 24) | (c << 16) | (d << 8) | e;
    }
}
