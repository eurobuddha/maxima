package com.eurobuddha.maxima.app.call;

import org.webrtc.IceCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The browser call engine's validated, bounded ICE queue, shared by both Android clients. */
public final class CallIce {
    public static final int LIMIT = 256;
    private static final long TTL_MS = 90_000;
    private final Map<String, Pending> early = new LinkedHashMap<>();
    private static final class Pending {
        final long at;
        final List<IceCandidate> candidates = new ArrayList<>();
        Pending(long now) { at = now; }
    }

    public static IceCandidate parse(String payload) {
        if (payload == null || payload.length() > 8192) return null;
        String[] p = payload.split("\n", 3);
        if (p.length != 3 || p[0].length() > 256 || !p[1].matches("[0-9]{1,5}")) return null;
        int line = Integer.parseInt(p[1]); // bounded decimal, cannot overflow
        String sdp = p[2].trim();
        if (line > 65535 || !sdp.startsWith("candidate:") || sdp.length() <= 10
                || sdp.indexOf('\n') >= 0 || sdp.indexOf('\r') >= 0) return null;
        return new IceCandidate("null".equals(p[0]) ? null : p[0], line, sdp);
    }

    /** Call only after authenticating the sender; no media or UI is created by early ICE. */
    public void remember(String peer, String call, IceCandidate candidate, long now) {
        expire(now);
        if (peer == null || peer.isEmpty() || call == null || call.isEmpty() || call.length() > 256) return;
        String key = key(peer, call);
        int count = 0;
        for (Pending p : early.values()) count += p.candidates.size();
        if (count >= LIMIT || (!early.containsKey(key) && early.size() >= 8)) return;
        early.computeIfAbsent(key, k -> new Pending(now)).candidates.add(candidate);
    }

    public List<IceCandidate> take(String peer, String call, long now) {
        expire(now);
        Pending p = early.remove(key(peer, call));
        return p == null ? new ArrayList<>() : p.candidates;
    }

    public void clear() { early.clear(); }
    private void expire(long now) { early.values().removeIf(p -> now - p.at >= TTL_MS); }
    private static String key(String peer, String call) { return peer.toLowerCase(Locale.ROOT) + "\n" + call; }
}
