package com.eurobuddha.maxima.desktop.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * Desktop voice notes — codec-free and phone-interoperable. Records 16 kHz mono
 * PCM and sends it as audio/wav (Android's MediaPlayer plays WAV natively), with
 * the same "M:SS|<32 hex>" waveform caption the phone uses. Plays a received WAV
 * inline (with a moving waveform); hands a received Opus/OGG or AAC/M4A note to
 * the OS media app.
 */
final class VoiceNotes {

    static final int MAX_SECONDS = 60;
    private static final AudioFormat FORMAT =
            new AudioFormat(16000f, 16, 1, true, false);   // 16 kHz mono, signed LE PCM

    private VoiceNotes() { }

    interface Sink { void onRecorded(byte[] wav, String caption); }

    // ------------------------------------------------------------------ record

    private static final class Recorder {
        private TargetDataLine line;
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private final List<Integer> samples = new ArrayList<>();
        private volatile boolean running;
        private Thread thread;
        private long startMs;

        boolean start() {
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
                if (!AudioSystem.isLineSupported(info)) return false;
                line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(FORMAT);
                line.start();
                running = true;
                startMs = System.currentTimeMillis();
                thread = new Thread(this::loop, "voice-rec");
                thread.start();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private void loop() {
            byte[] buf = new byte[3200];   // ~100 ms at 16 kHz * 2 bytes
            while (running) {
                int r = line.read(buf, 0, buf.length);
                if (r <= 0) continue;
                pcm.write(buf, 0, r);
                int peak = 0;
                for (int i = 0; i + 1 < r; i += 2) {
                    int s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                    peak = Math.max(peak, Math.abs(s));
                }
                samples.add(peak);
            }
        }

        long elapsedMs() { return System.currentTimeMillis() - startMs; }

        /** Stop and return the WAV bytes (null on failure). */
        byte[] stopToWav() {
            running = false;
            try { if (thread != null) thread.join(500); } catch (InterruptedException ignored) { }
            try { if (line != null) { line.stop(); line.close(); } } catch (Exception ignored) { }
            try {
                byte[] raw = pcm.toByteArray();
                long frames = raw.length / FORMAT.getFrameSize();
                AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(raw), FORMAT, frames);
                ByteArrayOutputStream wav = new ByteArrayOutputStream();
                AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, wav);
                return wav.toByteArray();
            } catch (Exception e) {
                return null;
            }
        }

        String caption() {
            long ms = elapsedMs();
            String dur = (ms / 60000) + ":" + String.format("%02d", (ms / 1000) % 60);
            return dur + "|" + Waveform.encode(Waveform.summarise(samples));
        }
    }

    /** Open the record dialog; on Send, hand back the WAV bytes + caption. */
    static void record(Window owner, Theme t, Sink sink) {
        Recorder rec = new Recorder();
        if (!rec.start()) {
            javax.swing.JOptionPane.showMessageDialog(owner,
                    "No microphone available.");
            return;
        }
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new javax.swing.border.EmptyBorder(16, 16, 16, 16));

        JLabel dot = new JLabel("●  Recording");
        dot.setForeground(new Color(0xE0, 0x52, 0x4D));
        dot.setFont(t.semibold(13f));
        dot.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel timer = new JLabel("0:00");
        timer.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.BOLD, 26));
        timer.setForeground(t.text);
        timer.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(dot);
        body.add(Box.createVerticalStrut(8));
        body.add(timer);
        body.add(Box.createVerticalStrut(14));

        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        DKit k = new DKit(t);
        DKit.HoverButton send = k.primaryButton("Send");
        DKit.HoverButton cancel = k.ghostButton("Cancel");
        row.add(send);
        row.add(Box.createRigidArea(new Dimension(8, 0)));
        row.add(cancel);
        row.add(Box.createHorizontalGlue());
        body.add(row);

        JDialog d = new JDialog(owner, "Voice note", JDialog.ModalityType.MODELESS);
        d.setContentPane(body);
        d.setSize(320, 200);
        d.setLocationRelativeTo(owner);

        final Timer blink = new Timer(500, null);
        final boolean[] on = {true};
        Timer tick = new Timer(200, e -> {
            long ms = rec.elapsedMs();
            timer.setText((ms / 60000) + ":" + String.format("%02d", (ms / 1000) % 60));
            on[0] = !on[0];
            dot.setForeground(on[0] ? new Color(0xE0, 0x52, 0x4D)
                    : new Color(0xE0, 0x52, 0x4D, 60));
            if (ms >= MAX_SECONDS * 1000L) {   // auto-stop at the cap
                finish(rec, d, sink);
            }
        });
        tick.start();

        send.onClick(() -> finish(rec, d, sink));
        cancel.onClick(() -> { rec.stopToWav(); tick.stop(); d.dispose(); });
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) { rec.stopToWav(); tick.stop(); }
        });
        d.setVisible(true);
    }

    private static void finish(Recorder rec, JDialog d, Sink sink) {
        String caption = rec.caption();
        byte[] wav = rec.stopToWav();
        d.dispose();
        if (wav != null && wav.length > 0) {
            sink.onRecorded(wav, caption);
        }
    }

    // -------------------------------------------------------------------- play

    private static Clip sActive;
    private static Timer sTicker;

    /** Play a received note. WAV plays inline (moving waveform); other formats go
     *  to the OS media app. Returns true if it started inline playback. */
    static synchronized boolean play(byte[] bytes, String mime, Waveform.Bars bars) {
        stop();
        boolean wav = mime != null && mime.toLowerCase().contains("wav");
        if (wav) {
            try {
                AudioInputStream in = AudioSystem.getAudioInputStream(
                        new java.io.BufferedInputStream(new ByteArrayInputStream(bytes)));
                Clip c = AudioSystem.getClip();
                c.open(in);
                c.start();
                sActive = c;
                final long len = c.getMicrosecondLength();
                sTicker = new Timer(60, e -> {
                    if (bars != null && len > 0) {
                        bars.setProgress(c.getMicrosecondPosition() / (double) len);
                    }
                    if (!c.isRunning() && c.getMicrosecondPosition() >= len) {
                        stop();
                        if (bars != null) bars.setProgress(0);
                    }
                });
                sTicker.start();
                return true;
            } catch (Exception ignored) {
                // fall through to external
            }
        }
        openExternally(bytes, mime);
        return false;
    }

    static synchronized void stop() {
        if (sTicker != null) { sTicker.stop(); sTicker = null; }
        if (sActive != null) { try { sActive.stop(); sActive.close(); } catch (Exception ignored) { } sActive = null; }
    }

    static synchronized boolean isPlaying() {
        return sActive != null && sActive.isRunning();
    }

    private static void openExternally(byte[] bytes, String mime) {
        try {
            String ext = mime != null && mime.contains("mp4") ? ".m4a"
                    : mime != null && mime.contains("ogg") ? ".ogg" : ".audio";
            File tmp = File.createTempFile("parlons-voice", ext);
            tmp.deleteOnExit();
            java.nio.file.Files.write(tmp.toPath(), bytes);
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(tmp);
            }
        } catch (Exception ignored) {
        }
    }
}
