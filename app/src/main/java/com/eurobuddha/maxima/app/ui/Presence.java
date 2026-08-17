package com.eurobuddha.maxima.app.ui;

/**
 * Human presence from a contact's {@code lastSeen} — the same signal the
 * Contacts screen shows as a dot, formatted for a chat header. "online" while
 * we've heard from them within {@link #ONLINE_MS}, else "last seen …", or empty
 * when we've never heard from them.
 */
public final class Presence {

    private Presence() {
    }

    /** Reachable peers re-announce on the ~20-min Maxima loop, so 30 min with
     *  margin is "online" — matching the Contacts dot. */
    public static final long ONLINE_MS = 30 * 60 * 1000L;

    public static boolean online(long zLastSeen) {
        return zLastSeen > 0 && System.currentTimeMillis() - zLastSeen < ONLINE_MS;
    }

    /** "online" / "last seen 3h ago" / "" (never heard from them). */
    public static String of(long zLastSeen) {
        if (zLastSeen <= 0) {
            return "";
        }
        long d = System.currentTimeMillis() - zLastSeen;
        if (d < ONLINE_MS) {
            return "online";
        }
        long m = d / 60000L;
        if (m < 60) {
            return "last seen " + m + "m ago";
        }
        long h = m / 60;
        if (h < 24) {
            return "last seen " + h + "h ago";
        }
        return "last seen " + (h / 24) + "d ago";
    }
}
