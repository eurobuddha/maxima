package com.eurobuddha.maxima.core.chat;

/**
 * A shared CONTACT rides in the message body like media and payments do:
 *   U+0001 'c' U+0001 key U+0001 name U+0001 address
 * where key is the contact's identity key (uppercase 0x hex), name is what the sender calls them
 * and address is the string a recipient pastes into "Add contact" - the permanent
 * {@code MAX#<key>#<mls>} when the sender knows the contact's directory, else their current
 * {@code Mx...@host:port}. Control characters, so a typed message can never collide.
 * Every client renders a card with an "Add contact" action; the preview is "Contact: name".
 */
public final class ChatContact {

    private static final String MARK = "\u0001c\u0001";
    private static final char SEP = '\u0001';

    private ChatContact() {
    }

    public static String wrap(String zKey, String zName, String zAddress) {
        return MARK + safe(zKey) + SEP + safe(zName) + SEP + safe(zAddress);
    }

    public static boolean isCard(String zBody) {
        return zBody != null && zBody.startsWith(MARK);
    }

    /** {key, name, address} or null when not a card. */
    public static String[] parse(String zBody) {
        if (!isCard(zBody)) {
            return null;
        }
        String rest = zBody.substring(MARK.length());
        int a = rest.indexOf(SEP);
        if (a < 0) {
            return null;
        }
        int b = rest.indexOf(SEP, a + 1);
        if (b < 0) {
            return null;
        }
        return new String[]{rest.substring(0, a), rest.substring(a + 1, b), rest.substring(b + 1)};
    }

    public static String key(String zBody) {
        String[] p = parse(zBody);
        return p == null ? "" : p[0];
    }

    public static String name(String zBody) {
        String[] p = parse(zBody);
        return p == null ? "" : p[1];
    }

    public static String address(String zBody) {
        String[] p = parse(zBody);
        return p == null ? "" : p[2];
    }

    /** The one-line preview for lists and notifications. */
    public static String preview(String zBody) {
        String[] p = parse(zBody);
        if (p == null) {
            return zBody;
        }
        return "👤 Contact: " + (p[1].isEmpty() ? "(no name)" : p[1]);
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace(String.valueOf(SEP), "");
    }
}
