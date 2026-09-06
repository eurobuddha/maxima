package com.eurobuddha.maxima.core.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Where a client's FIRST relays come from - and the rule that no single source is required.
 *
 * Three sources, composed in this order:
 * <ol>
 *   <li><b>the user's own seeds</b> - typed, pasted, or scanned from a relay's QR;</li>
 *   <li><b>relays remembered from earlier runs</b> - the verified peer list discovery saved
 *       (classic P2PDB style) and the swarm the client last attached to;</li>
 *   <li><b>the compiled-in list</b> ({@link Bootstrap#RELAYS}) - ON by default, and the user may
 *       switch it off or drop single entries from it.</li>
 * </ol>
 * Everything a client learns at runtime (gossip, greetings) is layered on top by the pool.
 *
 * The compiled-in list is one operator's choice. Making it optional and replaceable is what
 * keeps that operator from being a point of control: a community can hand out its own relays
 * as a QR, a user can run on those alone, and the app keeps working when the compiled-in
 * boxes are gone.
 *
 * <h3>The relay QR / share text</h3>
 * {@code parlons-relay:HOST:PORT[,HOST:PORT...]} - a URI-ish prefix so it can never be
 * mistaken for a contact address ({@code Mx…}/{@code MAX#…}) or a wallet address, then one
 * or more {@code host:port} entries. {@link #parse} also accepts the bare form so a typed
 * {@code host:port} (or a comma list) works everywhere the QR text does.
 */
public final class SeedRelays {

    public static final String QR_PREFIX = "parlons-relay:";

    private SeedRelays() {
    }

    /** The text to put in a QR (or to paste) for these relays. */
    public static String share(Collection<String> zHostPorts) {
        StringBuilder sb = new StringBuilder(QR_PREFIX);
        boolean first = true;
        for (String hp : zHostPorts) {
            if (!isValid(hp)) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            sb.append(hp.trim());
            first = false;
        }
        return sb.toString();
    }

    /**
     * Relays named by scanned / pasted / typed text: the QR form (prefix, any case, with or
     * without a leading {@code //}), or bare {@code host:port} entries separated by commas,
     * whitespace or newlines. Invalid entries are skipped; the result is deduplicated and in
     * the given order. Empty when the text names no relay at all.
     */
    public static List<String> parse(String zText) {
        List<String> out = new ArrayList<>();
        if (zText == null) {
            return out;
        }
        String s = zText.trim();
        if (s.regionMatches(true, 0, QR_PREFIX, 0, QR_PREFIX.length())) {
            s = s.substring(QR_PREFIX.length());
            while (s.startsWith("/")) {
                s = s.substring(1);
            }
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String part : s.split("[,\\s]+")) {
            String hp = part.trim();
            if (isValid(hp) && seen.add(hp)) {
                out.add(hp);
            }
        }
        return out;
    }

    /** {@code host:port} with a sane port and a non-empty host made of address characters. */
    public static boolean isValid(String zHostPort) {
        if (zHostPort == null) {
            return false;
        }
        String hp = zHostPort.trim();
        int c = hp.lastIndexOf(':');
        if (c <= 0 || c == hp.length() - 1) {
            return false;
        }
        String host = hp.substring(0, c);
        if (host.isEmpty() || !host.matches("[0-9A-Za-z.\\-\\[\\]:]+")) {
            return false;
        }
        try {
            int p = Integer.parseInt(hp.substring(c + 1));
            return p > 0 && p < 65536;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** True if this relay is on the compiled-in list. */
    public static boolean isBuiltIn(String zHostPort) {
        return zHostPort != null && Bootstrap.RELAYS.contains(zHostPort.trim());
    }

    /**
     * The seed list a client starts from: the user's seeds first, then remembered relays,
     * then the compiled-in list (when enabled, minus the entries the user dropped). Order is
     * only a hint - the pool draws at random above a floor - but it is stable and readable.
     */
    public static List<String> compose(Collection<String> zUserSeeds,
                                       Collection<String> zRemembered,
                                       boolean zBuiltInEnabled,
                                       Collection<String> zExcludedBuiltIn) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (zUserSeeds != null) {
            for (String hp : zUserSeeds) {
                if (isValid(hp)) {
                    out.add(hp.trim());
                }
            }
        }
        if (zRemembered != null) {
            for (String hp : zRemembered) {
                if (isValid(hp)) {
                    out.add(hp.trim());
                }
            }
        }
        if (zBuiltInEnabled) {
            for (String hp : Bootstrap.RELAYS) {
                if (zExcludedBuiltIn == null || !zExcludedBuiltIn.contains(hp)) {
                    out.add(hp);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Lower-cased, trimmed key for comparing user input against stored entries. */
    public static String norm(String zHostPort) {
        return zHostPort == null ? "" : zHostPort.trim().toLowerCase(Locale.ROOT);
    }
}
