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
                synchronized (samples) { samples.add(peak); }
            }
        }

        long elapsedMs() { return System.currentTimeMillis() - startMs; }

        /** The most recent {@code n} input peaks, normalised 0..100 — a live meter
         *  for the record dialog (read on the EDT while the rec thread appends). */
        int[] liveBars(int n) {
            int[] out = new int[n];
            synchronized (samples) {
                int sz = samples.size();
                for (int i = 0; i < n; i++) {
                    int idx = sz - n + i;
                    int peak = idx >= 0 ? samples.get(idx) : 0;
                    out[i] = Math.min(100, (int) (peak * 100L / 32767));
                }
            }
            return out;
        }

        private boolean stopped;

        /** Stop and return the WAV bytes (null on failure). Idempotent: a second
         *  call returns null rather than re-emitting the same buffer. */
        byte[] stopToWav() {
            if (stopped) return null;
            stopped = true;
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

    /** A moving live-input meter drawn from the recorder's recent peaks. */
    private static final class LiveWave extends javax.swing.JComponent {
        static final int BARS = 28;
        private final Theme t;
        private int[] levels = new int[BARS];
        LiveWave(Theme t) {
            this.t = t;
            setPreferredSize(new Dimension(288, 44));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        }
        void update(int[] zLevels) {
            if (zLevels != null && zLevels.length == BARS) levels = zLevels;
            repaint();
        }
        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int mid = h / 2;
            float slot = w / (float) BARS;
            float bw = Math.max(2f, slot - 2f);
            for (int i = 0; i < BARS; i++) {
                int lvl = Math.max(3, Math.min(100, levels[i]));
                int bh = Math.max(3, (int) (lvl / 100f * (h - 6)));
                float x = i * slot + (slot - bw) / 2f;
                g2.setColor(t.accent);
                g2.fill(new java.awt.geom.RoundRectangle2D.Float(
                        x, mid - bh / 2f, bw, bh, bw, bw));
            }
            g2.dispose();
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
        body.add(Box.createVerticalStrut(10));

        // Live input meter: a moving waveform of the last ~2.8s of input, like
        // the phone's record view — silence is obvious, so is a dead mic.
        final LiveWave wave = new LiveWave(t);
        wave.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(wave);
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

        final boolean[] on = {true};
        // finish/cancel must be idempotent AND stop the tick timer — otherwise the
        // leaked 100 ms timer keeps firing on the disposed dialog, and once
        // wall-clock passes MAX_SECONDS the auto-stop branch re-sends the SAME WAV
        // ~10×/second forever. `done` guards it; `tickRef` lets the timer stop
        // itself from inside its own lambda.
        final javax.swing.Timer[] tickRef = new javax.swing.Timer[1];
        final boolean[] done = {false};
        final Runnable finishOnce = () -> {
            if (done[0]) return;
            done[0] = true;
            if (tickRef[0] != null) tickRef[0].stop();
            finish(rec, d, sink);
        };
        final Runnable cancelOnce = () -> {
            if (done[0]) return;
            done[0] = true;
            if (tickRef[0] != null) tickRef[0].stop();
            rec.stopToWav();
            d.dispose();
        };
        Timer tick = new Timer(100, e -> {
            long ms = rec.elapsedMs();
            timer.setText((ms / 60000) + ":" + String.format("%02d", (ms / 1000) % 60));
            wave.update(rec.liveBars(LiveWave.BARS));
            on[0] = !on[0];
            dot.setForeground(on[0] ? new Color(0xE0, 0x52, 0x4D)
                    : new Color(0xE0, 0x52, 0x4D, 60));
            if (ms >= MAX_SECONDS * 1000L) {   // auto-stop at the cap
                finishOnce.run();
            }
        });
        tickRef[0] = tick;
        tick.start();

        send.onClick(finishOnce::run);
        cancel.onClick(cancelOnce::run);
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) { cancelOnce.run(); }
        });
        d.setVisible(true);
    }

    private static void finish(Recorder rec, JDialog d, Sink sink) {
        // Stop the recorder FIRST, then read the caption — otherwise caption()
        // iterates `samples` while the rec thread is still appending to it (CME).
        byte[] wav = rec.stopToWav();
        String caption = rec.caption();
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
