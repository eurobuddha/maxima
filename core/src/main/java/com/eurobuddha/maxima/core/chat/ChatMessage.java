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

    public int type;
    /** Message id, chosen by the sender; receipts refer back to it. */
    public String id = "";
    public String groupId = "";
    public String body = "";
    public String groupName = "";
    public final List<String> members = new ArrayList<>();
    public String admin = "";
    /** For a receipt: which message it refers to. */
    public String ref = "";
    /** For a receipt: {@link Receipt}. */
    public String state = "";

    public static ChatMessage text(String zId, String zBody) {
        ChatMessage m = new ChatMessage();
        m.type = TYPE_TEXT;
        m.id = zId;
        m.body = zBody;
        return m;
    }

    public static ChatMessage groupText(String zId, String zGroupId, String zBody) {
        ChatMessage m = new ChatMessage();
        m.type = TYPE_GROUP_TEXT;
        m.id = zId;
        m.groupId = zGroupId;
        m.body = zBody;
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

    public String encode() {
        Json.Writer w = new Json.Writer().putRaw("t", Integer.toString(type));
        if (!id.isEmpty()) w.put("id", id);
        if (!groupId.isEmpty()) w.put("gid", groupId);
        if (!body.isEmpty()) w.put("body", body);
        if (!groupName.isEmpty()) w.put("name", groupName);
        if (!members.isEmpty()) w.put("members", String.join(",", members));
        if (!admin.isEmpty()) w.put("admin", admin);
        if (!ref.isEmpty()) w.put("ref", ref);
        if (!state.isEmpty()) w.put("state", state);
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
        c.groupId = m.getOrDefault("gid", "");
        c.body = m.getOrDefault("body", "");
        c.groupName = m.getOrDefault("name", "");
        c.admin = m.getOrDefault("admin", "");
        c.ref = m.getOrDefault("ref", "");
        c.state = m.getOrDefault("state", "");
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
