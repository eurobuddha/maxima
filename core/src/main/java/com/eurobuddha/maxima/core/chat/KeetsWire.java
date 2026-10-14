package com.eurobuddha.maxima.core.chat;

import com.eurobuddha.maxima.core.util.Json;

import java.util.Map;

/**
 * The KeetChat dialect of {@code maxima_chat_v1}, translated to and from our
 * {@link ChatMessage}.
 *
 * Same application string, different shape: KeetChat (minimaCore 1.9.25+,
 * minimaCore Desktop 0.18.3+, mds/keetchat 0.2.0) sends flat JSON
 * {@code {application,id,body,name,ref,ts}} where {@code name} is the SENDER's
 * display name, plus a typed {@code state} string instead of our {@code t} int:
 * "" = text, "image" (ref = keets:// media url), "read" (ref = newest covered
 * msgid, implicitly up-to), "payment" (amt/tok/tokname/txid), "payaddr" (addr),
 * "payaddr-req". Unknown states are consumed and never stored, both sides.
 */
public final class KeetsWire {

    public static final String APPLICATION = "maxima_chat_v1";

    private KeetsWire() {
    }

    /** Inbound fields, exactly as KeetChat sent them. */
    public static final class Msg {
        public String id = "";
        public String body = "";
        /** The sender's display name ("" when they never set one). */
        public String name = "";
        public String ref = "";
        public long ts;
        public String state = "";
        public String addr = "";
        public String amt = "";
        public String tok = "";
        public String tokname = "";
        public String txid = "";
    }

    /** Parse a keets payload. Null when it is not the KeetChat dialect (the
     *  caller then treats the raw payload as legacy plain text, as KeetChat
     *  itself does). */
    public static Msg decode(String zPayload) {
        if (zPayload == null || !zPayload.contains(APPLICATION)) {
            return null;
        }
        Map<String, String> m;
        try {
            m = Json.parse(zPayload);
        } catch (Exception e) {
            return null;
        }
        if (!APPLICATION.equals(m.get("application"))) {
            return null;
        }
        Msg k = new Msg();
        k.id = m.getOrDefault("id", "");
        k.body = m.getOrDefault("body", "");
        k.name = m.getOrDefault("name", "");
        k.ref = m.getOrDefault("ref", "");
        try {
            k.ts = Long.parseLong(m.getOrDefault("ts", "0").trim());
        } catch (NumberFormatException e) {
            k.ts = 0;
        }
        k.state = m.getOrDefault("state", "");
        k.addr = m.getOrDefault("addr", "");
        k.amt = m.getOrDefault("amt", "");
        k.tok = m.getOrDefault("tok", "");
        k.tokname = m.getOrDefault("tokname", "");
        k.txid = m.getOrDefault("txid", "");
        return k;
    }

    /** An inbound keets message as one of OUR messages, ready for the normal
     *  handlers. Null = a control we do not map (payaddr-req, unknown states) -
     *  consumed, never stored. */
    public static ChatMessage toChatMessage(Msg k) {
        ChatMessage c = new ChatMessage();
        c.id = k.id;
        c.time = k.ts;
        switch (k.state) {
            case "":
                c.type = ChatMessage.TYPE_TEXT;
                c.body = k.body;
                return c;
            case "read":
                // A KeetChat read receipt covers ref and everything before it.
                c.type = ChatMessage.TYPE_RECEIPT;
                c.ref = k.ref;
                c.state = Receipt.READ;
                c.upto = true;
                return c;
            case "payment":
                c.type = ChatMessage.TYPE_PAYMENT;
                c.amount = k.amt;
                c.tokenId = k.tok;
                c.tokenName = k.tokname;
                c.txid = k.txid;
                c.memo = k.body;
                return c;
            case "payaddr":
                c.type = ChatMessage.TYPE_ADDRESS;
                c.address = k.addr;
                return c;
            default:
                return null;
        }
    }

    /**
     * KeetChat's decoder requires {@code body} to be PRESENT (a schema payload
     * without it falls back to raw-text display), treats {@code id} as the
     * dedup primary key, and KeetChat itself always sends body/name even when
     * empty - so we do exactly the same. "noname" is the engine's unset
     * display-name sentinel and never belongs on the wire.
     */
    private static Json.Writer base(String zId, String zMyName, long zTs, String zBody) {
        String id = (zId == null || zId.isEmpty())
                ? "pn-" + System.currentTimeMillis() + "-"
                        + Integer.toHexString((int) (Math.random() * 0x7fffffff))
                : zId;
        String name = (zMyName == null || "noname".equals(zMyName)) ? "" : zMyName;
        return new Json.Writer()
                .put("application", APPLICATION)
                .put("id", id)
                .put("body", zBody == null ? "" : zBody)
                .put("name", name)
                .putRaw("ts", Long.toString(zTs > 0 ? zTs : System.currentTimeMillis()));
    }

    /**
     * Outbound: one of OUR messages on the KeetChat wire. Null = this type does
     * not cross (groups/roster/call are Parlons-only; a DELIVERED receipt has no
     * keets equivalent - KeetChat's double tick is the transport result).
     * A media body must be converted by the caller first ({@link #image}).
     */
    public static String encode(ChatMessage m, String zMyName) {
        switch (m.type) {
            case ChatMessage.TYPE_TEXT:
                return base(m.id, zMyName, m.time, m.body).done();
            case ChatMessage.TYPE_RECEIPT:
                if (!Receipt.READ.equals(m.state) || m.ref.isEmpty()) {
                    return null;
                }
                return base(m.id, zMyName, m.time, "")
                        .put("state", "read").put("ref", m.ref).done();
            case ChatMessage.TYPE_PAYMENT:
                return base(m.id, zMyName, m.time, m.memo)
                        .put("state", "payment")
                        .put("amt", m.amount)
                        .put("tok", m.tokenId)
                        .put("tokname", m.tokenName)
                        .put("txid", m.txid).done();
            case ChatMessage.TYPE_ADDRESS:
                return base(m.id, zMyName, m.time, "")
                        .put("state", "payaddr").put("addr", m.address).done();
            default:
                return null;
        }
    }

    /** Outbound photo: state:image with a published keets:// ref. */
    public static String image(String zId, String zMyName, long zTs,
                               String zKeetsUrl, String zCaption) {
        return base(zId, zMyName, zTs, zCaption)
                .put("state", "image")
                .put("ref", zKeetsUrl).done();
    }

    /** Outbound voice note: state:audio, body carries the shared
     *  "M:SS|waveformhex" caption so every flavour draws the shape. */
    public static String audio(String zId, String zMyName, long zTs,
                               String zKeetsUrl, String zCaption) {
        return base(zId, zMyName, zTs, zCaption)
                .put("state", "audio")
                .put("ref", zKeetsUrl).done();
    }
}
