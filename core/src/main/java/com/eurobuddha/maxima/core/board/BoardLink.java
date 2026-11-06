package com.eurobuddha.maxima.core.board;

import com.eurobuddha.maxima.core.codec.MiniData;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * The invite link / QR text for a board.
 *
 * <pre>
 *   parlons://board/&lt;board id hex&gt;?n=&lt;name&gt;&amp;a=&lt;admin pseudonym DER hex&gt;[&amp;e=&lt;epoch&gt;&amp;k=&lt;board key hex&gt;][&amp;r=&lt;relay,relay&gt;]
 * </pre>
 *
 * With {@code k}: an OPEN board - whoever holds the link derives a pseudonym, reads, and posts.
 * Without: INVITE-ONLY - the holder can only knock (sealed to {@code a}); an admin's approval
 * delivers the key through the board itself. The admin key is what makes roster and rekey posts
 * verifiable, so it is in every link.
 */
public final class BoardLink {
    public static final String PREFIX = "parlons://board/";

    public final byte[] boardId;
    public final String name;
    /** Admin pseudonym, X.509 DER hex (0x, upper). */
    public final String adminHex;
    /** 0 when the link carries no key. */
    public final int epoch;
    public final byte[] key;
    public final List<String> relays;

    public BoardLink(byte[] zBoardId, String zName, String zAdminHex, int zEpoch, byte[] zKey,
                     List<String> zRelays) {
        boardId = zBoardId;
        name = zName == null ? "" : zName;
        adminHex = zAdminHex;
        epoch = zEpoch;
        key = zKey;
        relays = zRelays == null ? new ArrayList<>() : new ArrayList<>(zRelays);
    }

    public boolean hasKey() {
        return key != null && key.length == BoardKeys.KEY_BYTES && epoch > 0;
    }

    public static boolean isLink(String zText) {
        return zText != null && zText.trim().startsWith(PREFIX);
    }

    public String format() {
        StringBuilder b = new StringBuilder(PREFIX);
        b.append(bare(new MiniData(boardId).to0xString()));
        b.append("?n=").append(enc(name));
        b.append("&a=").append(bare(adminHex));
        if (hasKey()) {
            b.append("&e=").append(epoch).append("&k=").append(bare(new MiniData(key).to0xString()));
        }
        if (!relays.isEmpty()) {
            b.append("&r=").append(enc(String.join(",", relays)));
        }
        return b.toString();
    }

    /** @throws IllegalArgumentException for anything that is not a well-formed board link */
    public static BoardLink parse(String zText) {
        String t = zText == null ? "" : zText.trim();
        if (!t.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a board link");
        }
        String rest = t.substring(PREFIX.length());
        int q = rest.indexOf('?');
        String idHex = q < 0 ? rest : rest.substring(0, q);
        if (!idHex.matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException("bad board id");
        }
        String name = "";
        String admin = null;
        int epoch = 0;
        byte[] key = null;
        List<String> relays = new ArrayList<>();
        if (q >= 0) {
            for (String part : rest.substring(q + 1).split("&")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = part.substring(0, eq);
                String v = dec(part.substring(eq + 1));
                switch (k) {
                    case "n": name = v; break;
                    case "a":
                        if (!v.matches("[0-9A-Fa-f]{2,}") || (v.length() % 2) != 0) {
                            throw new IllegalArgumentException("bad admin key");
                        }
                        admin = new MiniData("0x" + v).to0xString();
                        break;
                    case "e":
                        try { epoch = Integer.parseInt(v); } catch (NumberFormatException e) { throw new IllegalArgumentException("bad epoch"); }
                        break;
                    case "k":
                        if (!v.matches("[0-9A-Fa-f]{64}")) {
                            throw new IllegalArgumentException("bad key");
                        }
                        key = new MiniData("0x" + v).getBytes();
                        break;
                    case "r":
                        for (String r : v.split(",")) {
                            if (!r.trim().isEmpty()) {
                                relays.add(r.trim());
                            }
                        }
                        break;
                    default: break;
                }
            }
        }
        if (admin == null) {
            throw new IllegalArgumentException("no admin key");
        }
        if ((key == null) != (epoch == 0)) {
            throw new IllegalArgumentException("key and epoch go together");
        }
        return new BoardLink(new MiniData("0x" + idHex).getBytes(), name, admin, epoch, key, relays);
    }

    private static String bare(String z0x) {
        return z0x.startsWith("0x") || z0x.startsWith("0X") ? z0x.substring(2) : z0x;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            throw new IllegalArgumentException("bad encoding");
        }
    }
}
