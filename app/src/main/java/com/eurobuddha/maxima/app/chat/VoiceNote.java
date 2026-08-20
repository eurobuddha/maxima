package com.eurobuddha.maxima.app.chat;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;

import java.io.File;

/**
 * The voice-note recorder: up to 60 seconds of speech, encoded small enough
 * that the note rides EMBEDDED in the message body like a photo does — same
 * send path, same receipts, same offline mailbox, end-to-end sealed.
 *
 * Opus in OGG at 16 kbps mono (API 29+) keeps a full 60s around 120KB raw —
 * comfortably inside the wire envelope photos already prove out daily.
 * API 28 falls back to AAC/MP4 at the same budget.
 */
public final class VoiceNote {

    public static final int MAX_SECONDS = 60;

    private final Context mCtx;
    private final boolean mOpus = Build.VERSION.SDK_INT >= 29;
    private MediaRecorder mRec;
    private File mFile;
    private long mStartedAt;
    private long mStoppedAt;

    public VoiceNote(Context zCtx) {
        mCtx = zCtx;
    }

    public void start() throws Exception {
        File dir = new File(mCtx.getCacheDir(), "maximavoice");
        dir.mkdirs();
        mFile = new File(dir, "rec_" + System.currentTimeMillis()
                + (mOpus ? ".ogg" : ".m4a"));
        mRec = new MediaRecorder();
        mRec.setAudioSource(MediaRecorder.AudioSource.MIC);
        if (mOpus) {
            mRec.setOutputFormat(MediaRecorder.OutputFormat.OGG);
            mRec.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
        } else {
            mRec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mRec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        }
        mRec.setAudioEncodingBitRate(16_000);
        mRec.setAudioSamplingRate(16_000);
        mRec.setAudioChannels(1);
        mRec.setMaxDuration(MAX_SECONDS * 1000);
        mRec.setOutputFile(mFile.getAbsolutePath());
        mRec.prepare();
        mRec.start();
        mStartedAt = System.currentTimeMillis();
        mStoppedAt = 0;
    }

    public int elapsedSeconds() {
        if (mStartedAt == 0) {
            return 0;
        }
        long end = mStoppedAt > 0 ? mStoppedAt : System.currentTimeMillis();
        return (int) ((end - mStartedAt) / 1000);
    }

    public int recordedSeconds() {
        return Math.min(MAX_SECONDS, Math.max(0, elapsedSeconds()));
    }

    /** Stop and never throw — stopping a barely-started recorder throws on
     *  some OEMs, and a failed stop must not eat the recording flow. */
    public void stopQuiet() {
        if (mRec == null) {
            return;
        }
        try {
            mRec.stop();
        } catch (Exception ignored) {
        }
        try {
            mRec.release();
        } catch (Exception ignored) {
        }
        mRec = null;
        if (mStoppedAt == 0) {
            mStoppedAt = System.currentTimeMillis();
        }
    }

    public void cancel() {
        stopQuiet();
        if (mFile != null) {
            mFile.delete();
        }
        mFile = null;
    }

    /** The finished recording; deletes the temp file. Null if nothing usable. */
    public byte[] bytes() throws Exception {
        if (mFile == null || !mFile.exists()) {
            return null;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.FileInputStream in = new java.io.FileInputStream(mFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        mFile.delete();
        mFile = null;
        return out.toByteArray();
    }

    public String mime() {
        return mOpus ? "audio/ogg" : "audio/mp4";
    }
}
