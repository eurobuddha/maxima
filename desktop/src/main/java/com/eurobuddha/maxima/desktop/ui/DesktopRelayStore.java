package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.session.SeedRelays;

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
    /** Your own seeds, one host:port per line. (Older files also held the compiled-in entries:
     *  those are filtered out on read so they stay governed by the switch.) */
    private final File file;
    /** Compiled-in entries you dropped, one per line. */
    private final File excludedFile;
    /** Present with the text "off" when the compiled-in list is not to be used. */
    private final File builtInFile;

    public DesktopRelayStore(File dataDir) {
        file = new File(dataDir, "relays.txt");
        excludedFile = new File(dataDir, "relays-excluded.txt");
        builtInFile = new File(dataDir, "relays-builtin.txt");
    }

    private static Set<String> lines(File f) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            if (f.exists()) {
                for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    String h = line.trim();
                    if (isValid(h)) out.add(h);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Relays YOU added. */
    public synchronized List<String> userSeeds() {
        List<String> out = new ArrayList<>();
        for (String h : lines(file)) {
            if (!SeedRelays.isBuiltIn(h)) out.add(h);
        }
        return out;
    }

    public synchronized Set<String> excluded() {
        return lines(excludedFile);
    }

    public static boolean isBuiltIn(String hostPort) {
        return SeedRelays.isBuiltIn(hostPort);
    }

    /** Whether the compiled-in list is used as a seed source at all (default: yes). */
    public synchronized boolean builtInEnabled() {
        try {
            return !builtInFile.exists()
                    || !"off".equalsIgnoreCase(new String(Files.readAllBytes(builtInFile.toPath()),
                            StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return true;
        }
    }

    /** Refuses to switch OFF with no relay of your own configured. */
    public synchronized boolean setBuiltInEnabled(boolean on) {
        if (!on && userSeeds().isEmpty()) return false;
        try {
            if (on) {
                Files.deleteIfExists(builtInFile.toPath());
            } else {
                Files.write(builtInFile.toPath(), "off".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
        return true;
    }

    /** The seeds this desktop starts from: yours first, then the compiled-in list if on. */
    public synchronized List<String> get() {
        return SeedRelays.compose(userSeeds(), null, builtInEnabled(), excluded());
    }

    public synchronized void add(String hostPort) {
        if (!isValid(hostPort)) return;
        String hp = hostPort.trim();
        if (isBuiltIn(hp)) {
            Set<String> ex = excluded();
            ex.remove(hp);
            save(excludedFile, ex);
            return;
        }
        Set<String> cur = new LinkedHashSet<>(userSeeds());
        cur.add(hp);
        save(file, cur);
    }

    /** Drop a relay. Returns true when that was the last seed while the compiled-in list was
     *  off - the list is then switched back on (never leave this machine with nowhere to start). */
    public synchronized SeedRelays.Drop remove(String hostPort) {
        String hp = hostPort == null ? "" : hostPort.trim();
        if (isBuiltIn(hp)) {
            Set<String> ex = excluded();
            if (SeedRelays.droppingBuiltInLeavesNothing(userSeeds(), null, ex, hp)) {
                return SeedRelays.Drop.REFUSED_LAST_SEED;
            }
            ex.add(hp);
            save(excludedFile, ex);
            return SeedRelays.Drop.DROPPED;
        }
        Set<String> cur = new LinkedHashSet<>(userSeeds());
        cur.remove(hp);
        save(file, cur);
        if (SeedRelays.builtInMustReturn(cur, null, builtInEnabled())) {
            try { Files.deleteIfExists(builtInFile.toPath()); } catch (Exception ignored) { }
            return SeedRelays.Drop.DROPPED_BUILTIN_BACK_ON;
        }
        return SeedRelays.Drop.DROPPED;
    }

    /** Back to the compiled-in list only. */
    public synchronized void reset() {
        try { Files.deleteIfExists(file.toPath()); } catch (Exception ignored) { }
        try { Files.deleteIfExists(excludedFile.toPath()); } catch (Exception ignored) { }
        try { Files.deleteIfExists(builtInFile.toPath()); } catch (Exception ignored) { }
    }

    private static void save(File f, Set<String> hosts) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String h : hosts) sb.append(h).append('\n');
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    public static boolean isValid(String hp) {
        return SeedRelays.isValid(hp);
    }
}
