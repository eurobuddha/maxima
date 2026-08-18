package com.eurobuddha.maxima.desktop.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Render a string to a QR image — the desktop counterpart of the phone's {@code Qr}
 * (same zxing encoder, BufferedImage output). A Maxima address is ~273 chars, so a
 * roomy quiet zone + medium ECC keeps it scannable from a phone camera.
 */
final class DesktopQr {

    private DesktopQr() { }

    static BufferedImage encode(String zText, int zSizePx, int zDark, int zLight) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(zText, BarcodeFormat.QR_CODE,
                    zSizePx, zSizePx, hints);
            int w = m.getWidth(), h = m.getHeight();
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    img.setRGB(x, y, m.get(x, y) ? zDark : zLight);
                }
            }
            return img;
        } catch (Exception e) {
            return null;
        }
    }
}
