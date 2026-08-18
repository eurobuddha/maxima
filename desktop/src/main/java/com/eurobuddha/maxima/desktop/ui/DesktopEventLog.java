package com.eurobuddha.maxima.desktop.ui;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A tiny in-memory event log — the desktop counterpart of the phone's {@code EventLog}.
 * Bounded ring buffer, newest last, timestamped. The Network tab renders the last N
 * lines; the reachability/relay/connection subsystems write to it so nothing happens
 * silently.
 */
public final class DesktopEventLog {

    private static final int CAP = 200;
    private static final Deque<String> LINES = new ArrayDeque<>();

    private DesktopEventLog() { }

    public static synchronized void add(String zLine) {
        LINES.addLast("[" + LocalTime.now().withNano(0) + "] " + zLine);
        while (LINES.size() > CAP) {
            LINES.removeFirst();
        }
    }

    public static synchronized List<String> lines() {
        return new ArrayList<>(LINES);
    }

    public static synchronized String asText(int zMax) {
        List<String> all = new ArrayList<>(LINES);
        int from = Math.max(0, all.size() - zMax);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(all.get(i));
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
