package com.eurobuddha.maxima.app.ipc;

/**
 * THE OUTWARD IPC SURFACE - milestone 13.
 *
 * How Minima Core and other companion apps use this app as their Maxima
 * transport. Deliberately mirrors the minimaapi broadcast-Intent pattern the
 * fleet already speaks, so anything that already talks to the node needs no new
 * concepts.
 *
 * Design notes that matter:
 *
 *  - LARGE PAYLOADS go via a content:// URI, never inline. A Maxima message can
 *    be 256KB, and the broadcast parcel dies somewhere around that size taking
 *    the receiving app with it (proven live in this fleet, 2026-08-09). Anything
 *    over {@link #MAX_INLINE} is handed over as a file.
 *  - Every caller must REGISTER and be approved by the user, exactly like the
 *    node's own receiver gate. A transport that any installed app can send
 *    from is a spam cannon wearing your identity.
 *  - Each caller is namespaced to its own `application` strings, so one app
 *    cannot read or spoof another's traffic.
 */
public final class MaximaApiMessages {

    private MaximaApiMessages() {
    }

    private static final String PKG = "com.eurobuddha.maxima.app";

    // ---- app -> us ----
    public static final String ACTION_REGISTER = PKG + ".REGISTER";
    public static final String ACTION_SEND = PKG + ".SEND";
    public static final String ACTION_SUBSCRIBE = PKG + ".SUBSCRIBE";
    public static final String ACTION_IDENTITY = PKG + ".IDENTITY";
    /** Contact operations: list, add, remove. */
    public static final String ACTION_CONTACTS = PKG + ".CONTACTS";
    /** Self-hosted media: EXTRA_OP "put" (datauri in -> manifest in EXTRA_DATA)
     *  or "get" (manifest in EXTRA_DATA -> bytes out via datauri). */
    public static final String ACTION_MEDIA = PKG + ".MEDIA";

    // ---- us -> app ----
    public static final String ACTION_RESPONSE = PKG + ".RESPONSE";
    /** An inbound Maxima message for an application string you subscribed to. */
    public static final String ACTION_DELIVER = PKG + ".DELIVER";
    /** Contacts changed, or a host attached/dropped. Classic's MAXIMACONTACTS/MAXIMAHOSTS. */
    public static final String ACTION_EVENT = PKG + ".EVENT";

    // ---- extras ----
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_CLASS = "class";
    public static final String EXTRA_REQUEST_ID = "requestid";

    /** The Maxima `application` string this app owns. */
    public static final String EXTRA_APPLICATION = "application";
    /** Recipient contact address, Mx...@host:port */
    public static final String EXTRA_TO = "to";

    /** Inline payload, only when small. */
    public static final String EXTRA_DATA = "data";
    /** content:// URI for anything larger, plus its length. */
    public static final String EXTRA_DATA_URI = "datauri";
    public static final String EXTRA_DATA_LEN = "datalen";

    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_ENABLED = "enabled";
    public static final String EXTRA_FROM = "from";
    public static final String EXTRA_MSGID = "msgid";
    public static final String EXTRA_ADDRESSES = "addresses";
    public static final String EXTRA_PUBLICKEY = "publickey";
    public static final String EXTRA_OP = "op";
    public static final String EXTRA_CONTACTS = "contacts";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_TIME = "timemilli";
    public static final String EXTRA_NAME = "name";
    /** SEND option: route through the outbox (retry with backoff across
     *  heartbeats) instead of a one-shot send. Requires EXTRA_TO to be a
     *  contact's identity key. The reply carries the outbox EXTRA_MSGID and
     *  EXTRA_RESULT "queued". */
    public static final String EXTRA_RELIABLE = "reliable";

    // event names, matching classic
    public static final String EVENT_CONTACTS = "MAXIMACONTACTS";
    public static final String EVENT_HOSTS = "MAXIMAHOSTS";

    /**
     * Above this many bytes we hand over a content:// URI instead.
     *
     * Well under the parcel ceiling on purpose: String.length() counts UTF-16
     * chars while the parcel serialises roughly two bytes per char, so the
     * effective limit is about half what it looks like.
     */
    public static final int MAX_INLINE = 64 * 1024;

    public static final String AUTHORITY = PKG + ".payloads";
}
