package com.eurobuddha.maxima.app;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A small in-memory ring of what just happened.
 *
 * On a phone there is no console to watch, and "it isn't working" is
 * unactionable without knowing whether we attached, whether a send was acked,
 * or whether anything arrived. This is the difference between a testable app
 * and a black box.
 */
public final class EventLog {

    private static final int MAX = 200;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.UK);

    private EventLog() {
    }

    public static synchronized void add(String zLine) {
        LINES.addFirst(FMT.format(new Date()) + "  " + zLine);
        while (LINES.size() > MAX) {
            LINES.removeLast();
        }
    }

    public static synchronized List<String> lines() {
        return new ArrayList<>(LINES);
    }

    public static synchronized String asText(int zMax) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String l : LINES) {
            sb.append(l).append('\n');
            if (++n >= zMax) {
                break;
            }
        }
        return sb.length() == 0 ? "(nothing yet)" : sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
