package com.eurobuddha.maxima.app.board;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import com.eurobuddha.maxima.core.media.MediaManifest;
import com.eurobuddha.maxima.core.media.MediaService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Image plumbing for board posts, shared by the app and the portal. A board is read by many
 * and kept lean: the SENDING device shrinks a photo to {@link #MAX_EDGE_PX} on the long edge
 * and JPEG {@link #QUALITY} before it is published, so one upload stays small for every reader
 * and every relay. (The chat screens carry their own copies of these steps at 1400 px.)
 */
public final class BoardImages {

    /** Long edge after shrinking. Plenty for a phone screen, a few hundred KB at most. */
    public static final int MAX_EDGE_PX = 1280;
    public static final int QUALITY = 80;

    private BoardImages() {
    }

    /** Decode, shrink to the long-edge bound, fix EXIF orientation, re-encode as JPEG. */
    public static byte[] readScaledJpeg(ContentResolver zResolver, Uri zUri, int zMaxPx) throws Exception {
        try (InputStream in = zResolver.openInputStream(zUri)) {
            if (in == null) {
                throw new Exception("could not open image");
            }
            byte[] all = readAll(in);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(all, 0, all.length, bounds);
            int sample = 1;
            int big = Math.max(bounds.outWidth, bounds.outHeight);
            while (big / sample > zMaxPx * 2) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(all, 0, all.length, o);
            if (bmp == null) {
                throw new Exception("could not read image");
            }
            bmp = applyExifOrientation(bmp, all);
            int w = bmp.getWidth(), h = bmp.getHeight();
            int edge = Math.max(w, h);
            if (edge > zMaxPx) {   // inSampleSize only halves: finish with an exact scale
                float s = (float) zMaxPx / edge;
                bmp = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, bos);
            return bos.toByteArray();
        }
    }

    private static Bitmap applyExifOrientation(Bitmap zBmp, byte[] zJpeg) {
        try {
            androidx.exifinterface.media.ExifInterface exif =
                    new androidx.exifinterface.media.ExifInterface(new ByteArrayInputStream(zJpeg));
            int o = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            Matrix m = new Matrix();
            switch (o) {
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1f, 1f); break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1f, -1f); break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1f, 1f); break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1f, 1f); break;
                default: return zBmp;
            }
            return Bitmap.createBitmap(zBmp, 0, 0, zBmp.getWidth(), zBmp.getHeight(), m, true);
        } catch (Exception e) {
            return zBmp;
        }
    }

    /** Decode within a ~1280 px bound so one photo cannot blow the heap. */
    public static Bitmap decodeBounded(byte[] zRaw) {
        if (zRaw == null || zRaw.length == 0) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(zRaw, 0, zRaw.length, o);
        int sample = 1;
        int max = Math.max(o.outWidth, o.outHeight);
        while (max / sample > 1280) {
            sample *= 2;
        }
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(zRaw, 0, zRaw.length, d);
    }

    /** The bytes behind a media ref: inline data: URL, or an mx1: manifest fetched from the mesh. */
    public static byte[] fetchMediaBytes(MediaService zMedia, String zRef) throws Exception {
        if (zRef.startsWith("data:")) {
            int comma = zRef.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("Invalid photo");
            }
            return android.util.Base64.decode(zRef.substring(comma + 1), android.util.Base64.DEFAULT);
        }
        if (zMedia == null || !zRef.startsWith("mx1:")) {
            throw new IllegalStateException("Media unavailable");
        }
        MediaManifest mf = MediaManifest.decode(new String(
                android.util.Base64.decode(zRef.substring(4), android.util.Base64.URL_SAFE), StandardCharsets.UTF_8));
        return zMedia.fetch(mf);
    }

    private static byte[] readAll(InputStream zIn) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = zIn.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
