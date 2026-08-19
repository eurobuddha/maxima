package com.eurobuddha.maxima.core.chat;

import com.eurobuddha.maxima.core.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The chat wire format.
 *
 * Ported from FreezePeach (`support/freezepeach`, FpService.java:105-121 and
 * MainActivity.java:2546-2637), which already solved group messaging over a 1:1
 * sealed transport and is running code. The important decisions are its, not
 * mine:
 *
 *  - NO SHARED GROUP KEY. A group message is sealed 1:1 to every member and
 *    fanned out. Membership changes therefore need no key rotation, and a
 *    compromise of one pair does not expose the group. The cost is N sends per
 *    message, which is the right trade at conversational volumes.
 *  - ROSTER CHANGES ONLY FROM AN ADMIN. Otherwise any member could add
 *    themselves to a group they were removed from.
 *  - POSTS ONLY FROM CURRENT MEMBERS, checked against the local roster on
 *    receipt. The sender is already cryptographically authenticated by the
 *    transport, so this is an authorisation check, not authentication.
 *
 * Types, all flat JSON so the tiny parser in :core can read them:
 *
 *   TEXT     {"t":1,"id":..,"body":..}                       1:1 message
 *   GTEXT    {"t":2,"id":..,"gid":..,"body":..}              group message
 *   ROSTER   {"t":3,"gid":..,"name":..,"members":..,"admin":..}
 *   RECEIPT  {"t":4,"ref":..,"state":..}                     delivery/read
 */
public final class ChatMessage {

    /** Our application string. One app owns it via the IPC surface. */
    public static final String APPLICATION = "maxima_chat_v1";

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_GROUP_TEXT = 2;
    public static final int TYPE_ROSTER = 3;
    public static final int TYPE_RECEIPT = 4;
    /** Silent control: the sender's WALLET receive address, so a contact can be
     *  paid without pasting an address. Never shown as a message. */
    public static final int TYPE_ADDRESS = 5;
    /** A payment made to this contact, shown in-thread as a payment bubble. */
    public static final int TYPE_PAYMENT = 6;

    public int type;
    /** Message id, chosen by the sender; receipts refer back to it. */
    public String id = "";
    /**
     * The sender's SEND time (epoch ms). Carried on the wire so the receiver can
     * place the message at when it was SENT, not when it arrived - a late relay
     * delivery then slots back into its true chronological spot instead of the
     * bottom of the thread. 0 = not set (an older sender); the receiver falls
     * back to arrival time. Only the visible-message types (TEXT/GROUP_TEXT/
     * PAYMENT) set it; a roster/receipt/address never becomes a dated bubble.
     */
    public long time;
    public String groupId = "";
    public String body = "";
    public String groupName = "";
    public final List<String> members = new ArrayList<>();
    public String admin = "";
    /** For a receipt: which message it refers to. */
    public String ref = "";
    /** For a receipt: {@link Receipt}. */
    public String state = "";
    /** For TYPE_ADDRESS: the sender's Mx wallet receive address. */
    public String address = "";
    /** For TYPE_PAYMENT. */
    public String amount = "";
    public String tokenId = "";
    public String tokenName = "";
    public String memo = "";
    public String txid = "";

    public static ChatMessage text(String zId, String zBody, long zTime) {
        ChatMessage m = new ChatMessage();
        m.type = TYPE_TEXT;
        m.id = zId;
        m.body = zBody;
        m.time = zTime;
        return m;
    }

    public static ChatMessage groupText(String zId, String zGroupId, String zBody, long zTime) {
        ChatMessage m = new ChatMessage();
        m.type = TYPE_GROUP_TEXT;
        m.id = zId;
        m.groupId = zGroupId;
        m.body = zBody;
        m.time = zTime;
        return m;
    }

    public static ChatMessage roster(String zGroupId, String zName,
                                     List<String> zMembers, String zAdmin) {
        ChatMessage m = new ChatMessage();
        m.type = TYPE_ROSTER;
        m.groupId = zGroupId;
        m.groupName = zName;
        m.members.addAll(zMembers);
        m.admin = zAdmin;
        return m;
    }

    public static ChatMessage receipt(String zRef, String zState) {
        ChatMessage m = new ChatMessage();
        m.type = TYPE_RECEIPT;
        m.ref = zRef;
        m.state = zState;
        return m;
    }

    public static ChatMessage address(String zAddress) {
        ChatMessage m = new ChatMessage();
        m.type = TYPE_ADDRESS;
        m.address = zAddress;
        return m;
    }

    public static ChatMessage payment(String zId, String zAmount, String zTokenId,
                                      String zTokenName, String zMemo, String zTxid, long zTime) {
        ChatMessage m = new ChatMessage();
        m.type = TYPE_PAYMENT;
        m.id = zId;
        m.amount = zAmount;
        m.tokenId = zTokenId;
        m.tokenName = zTokenName;
        m.memo = zMemo;
        m.txid = zTxid;
        m.time = zTime;
        return m;
    }

    public String encode() {
        Json.Writer w = new Json.Writer().putRaw("t", Integer.toString(type));
        if (!id.isEmpty()) w.put("id", id);
        if (time > 0) w.putRaw("ts", Long.toString(time));
        if (!groupId.isEmpty()) w.put("gid", groupId);
        if (!body.isEmpty()) w.put("body", body);
        if (!groupName.isEmpty()) w.put("name", groupName);
        if (!members.isEmpty()) w.put("members", String.join(",", members));
        if (!admin.isEmpty()) w.put("admin", admin);
        if (!ref.isEmpty()) w.put("ref", ref);
        if (!state.isEmpty()) w.put("state", state);
        if (!address.isEmpty()) w.put("addr", address);
        if (!amount.isEmpty()) w.put("amt", amount);
        if (!tokenId.isEmpty()) w.put("tok", tokenId);
        if (!tokenName.isEmpty()) w.put("tokname", tokenName);
        if (!memo.isEmpty()) w.put("memo", memo);
        if (!txid.isEmpty()) w.put("txid", txid);
        return w.done();
    }

    public static ChatMessage decode(String zJson) {
        Map<String, String> m = Json.parse(zJson);
        ChatMessage c = new ChatMessage();
        try {
            c.type = Integer.parseInt(m.getOrDefault("t", "0").trim());
        } catch (NumberFormatException e) {
            c.type = 0;
        }
        c.id = m.getOrDefault("id", "");
        try {
            c.time = Long.parseLong(m.getOrDefault("ts", "0").trim());
        } catch (NumberFormatException e) {
            c.time = 0;
        }
        c.groupId = m.getOrDefault("gid", "");
        c.body = m.getOrDefault("body", "");
        c.groupName = m.getOrDefault("name", "");
        c.admin = m.getOrDefault("admin", "");
        c.ref = m.getOrDefault("ref", "");
        c.state = m.getOrDefault("state", "");
        c.address = m.getOrDefault("addr", "");
        c.amount = m.getOrDefault("amt", "");
        c.tokenId = m.getOrDefault("tok", "");
        c.tokenName = m.getOrDefault("tokname", "");
        c.memo = m.getOrDefault("memo", "");
        c.txid = m.getOrDefault("txid", "");
        String mem = m.getOrDefault("members", "");
        for (String s : mem.split(",")) {
            if (!s.trim().isEmpty()) {
                c.members.add(s.trim().toUpperCase());
            }
        }
        return c;
    }

    public boolean isGroup() {
        return type == TYPE_GROUP_TEXT || type == TYPE_ROSTER;
    }
}
