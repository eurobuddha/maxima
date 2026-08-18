package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.session.Bootstrap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The desktop's configured relay list — the counterpart of the phone's {@code RelayStore}.
 * Persisted one host:port per line under the data dir, seeded from the {@link Bootstrap}
 * floor so a fresh install always has somewhere to attach. Used by the Network tab's host
 * list and by Auto-connect's candidate union.
 */
public final class DesktopRelayStore {

    private final File file;

    public DesktopRelayStore(File dataDir) {
        file = new File(dataDir, "relays.txt");
    }

    public synchronized List<String> get() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            if (file.exists()) {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    String h = line.trim();
                    if (isValid(h)) out.add(h);
                }
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) {
            out.addAll(Bootstrap.RELAYS);   // floor
        }
        return new ArrayList<>(out);
    }

    public synchronized void add(String hostPort) {
        if (!isValid(hostPort)) return;
        Set<String> cur = new LinkedHashSet<>(get());
        cur.add(hostPort.trim());
        save(cur);
    }

    public synchronized void remove(String hostPort) {
        Set<String> cur = new LinkedHashSet<>(get());
        cur.remove(hostPort);
        save(cur);
    }

    public synchronized void reset() {
        try { Files.deleteIfExists(file.toPath()); } catch (Exception ignored) { }
    }

    private void save(Set<String> hosts) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String h : hosts) sb.append(h).append('\n');
            Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** host:port with a plausible host and a 1–65535 port. */
    public static boolean isValid(String hp) {
        if (hp == null) return false;
        int c = hp.lastIndexOf(':');
        if (c <= 0 || c == hp.length() - 1) return false;
        String host = hp.substring(0, c).trim();
        if (host.isEmpty()) return false;
        try {
            int p = Integer.parseInt(hp.substring(c + 1).trim());
            return p > 0 && p < 65536;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
