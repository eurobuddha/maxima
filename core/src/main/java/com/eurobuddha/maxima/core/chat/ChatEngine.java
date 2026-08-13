package com.eurobuddha.maxima.core.chat;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.msg.MaximaMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat over Maxima: 1:1, groups, and two-tick delivery.
 *
 * The group rules are FreezePeach's, which is running code rather than a design
 * I invented (see ChatMessage). The receipt layer is new, and only possible
 * because a reply here can be a fresh outbound message rather than a
 * socket-level ack - classic cannot express "the recipient actually got it".
 *
 * This lives in :core so it can be tested with no device, and so the Android
 * app is only UI over it.
 */
public final class ChatEngine {

    /** One sent or received message, with its delivery state. */
    public static final class Entry {
        public final String id;
        public final String peer;        // 1:1 identity, or "" for a group
        public final String groupId;     // "" for 1:1
        public final String sender;      // who wrote it
        public final String body;
        public final long time;
        public final boolean mine;
        public volatile String state;

        /** For a group: who has confirmed delivery so far. */
        public final java.util.Set<String> deliveredBy =
                java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

        Entry(String zId, String zPeer, String zGroupId, String zSender,
              String zBody, long zTime, boolean zMine, String zState) {
            id = zId;
            peer = zPeer;
            groupId = zGroupId;
            sender = zSender;
            body = zBody;
            time = zTime;
            mine = zMine;
            state = zState;
        }

        public boolean isGroup() {
            return !groupId.isEmpty();
        }
    }

    /** UI hook. */
    public interface Listener {
        void onMessage(Entry zEntry);

        void onStateChanged(Entry zEntry);

        void onGroupChanged(Group zGroup);
    }

    private final MaximaNode mNode;
    private final Map<String, Group> mGroups = new ConcurrentHashMap<>();
    private final Map<String, Entry> mMessages = new LinkedHashMap<>();
    private volatile Listener mListener;

    /** Off by default. Read receipts are a privacy choice, not a default. */
    private volatile boolean mSendReadReceipts = false;

    public ChatEngine(MaximaNode zNode) {
        mNode = zNode;
    }

    public void setListener(Listener zListener) {
        mListener = zListener;
    }

    public void setSendReadReceipts(boolean zSend) {
        mSendReadReceipts = zSend;
    }

    public List<Group> groups() {
        return new ArrayList<>(mGroups.values());
    }

    public Group group(String zId) {
        return mGroups.get(zId);
    }

    public synchronized List<Entry> conversation(String zPeerOrGroup) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : mMessages.values()) {
            if (zPeerOrGroup.equalsIgnoreCase(e.peer) || zPeerOrGroup.equals(e.groupId)) {
                out.add(e);
            }
        }
        return out;
    }

    public Entry message(String zId) {
        return mMessages.get(zId);
    }

    // ---------------------------------------------------------------
    // sending
    // ---------------------------------------------------------------

    private static String newId() {
        return new MiniData(MaximaCrypto.randomBytes(12)).to0xString();
    }

    /** Send a 1:1 message. Returns the local entry immediately, state QUEUED. */
    public Entry send(Contact zTo, String zBody) {
        String id = newId();
        Entry e = new Entry(id, zTo.publicKey.toUpperCase(), "",
                mNode.identity().publicKeyHex(), zBody,
                System.currentTimeMillis(), true, Receipt.QUEUED);
        record(e);

        ChatMessage cm = ChatMessage.text(id, zBody);
        boolean ok = deliver(zTo, cm);
        setState(e, ok ? Receipt.SENT : Receipt.FAILED);
        return e;
    }

    /**
     * Send to a group by fanning out a separate sealed copy to every member.
     *
     * No shared group key, so removing someone actually removes them - there is
     * no key they still hold.
     */
    public Entry sendGroup(String zGroupId, String zBody) {
        Group g = mGroups.get(zGroupId);
        if (g == null) {
            throw new IllegalArgumentException("unknown group " + zGroupId);
        }
        String me = mNode.identity().publicKeyHex();
        String id = newId();
        Entry e = new Entry(id, "", zGroupId, me, zBody,
                System.currentTimeMillis(), true, Receipt.QUEUED);
        record(e);

        ChatMessage cm = ChatMessage.groupText(id, zGroupId, zBody);
        int sent = 0;
        for (String member : g.others(me)) {
            Contact c = mNode.contact(member);
            if (c != null && deliver(c, cm)) {
                sent++;
            }
        }
        setState(e, sent > 0 ? Receipt.SENT : Receipt.FAILED);
        return e;
    }

    /** Create a group and push the roster to every member. */
    public Group createGroup(String zName, List<String> zMemberKeys) {
        String me = mNode.identity().publicKeyHex();
        Group g = new Group(newId());
        g.name = zName;
        g.addAdmin(me);
        for (String k : zMemberKeys) {
            g.addMember(k);
        }
        mGroups.put(g.id, g);
        pushRoster(g);
        fireGroup(g);
        return g;
    }

    /** Change membership. Only an admin may, and only an admin is obeyed. */
    public void updateGroup(Group zGroup) {
        String me = mNode.identity().publicKeyHex();
        if (!zGroup.isAdmin(me)) {
            throw new IllegalStateException("only an admin can change the roster");
        }
        mGroups.put(zGroup.id, zGroup);
        pushRoster(zGroup);
        fireGroup(zGroup);
    }

    private void pushRoster(Group zGroup) {
        String me = mNode.identity().publicKeyHex();
        ChatMessage roster = ChatMessage.roster(zGroup.id, zGroup.name,
                new ArrayList<>(zGroup.members()), zGroup.adminsCsv());
        for (String member : zGroup.others(me)) {
            Contact c = mNode.contact(member);
            if (c != null) {
                deliver(c, roster);
            }
        }
    }

    private boolean deliver(Contact zTo, ChatMessage zMsg) {
        try {
            MaximaSender.Result r = mNode.sendToContact(zTo, ChatMessage.APPLICATION,
                    zMsg.encode().getBytes(StandardCharsets.UTF_8));
            return r.isOk();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------
    // receiving
    // ---------------------------------------------------------------

    /**
     * Feed every inbound chat message here. The transport has already verified
     * the signature, so `from` is authentic - what remains is AUTHORISATION.
     *
     * @return true if it was ours
     */
    public boolean onInbound(MaximaMessage zMsg) {
        if (!ChatMessage.APPLICATION.equals(zMsg.mApplication.toString())) {
            return false;
        }
        String from = zMsg.mFrom.to0xString().toUpperCase();
        ChatMessage cm;
        try {
            cm = ChatMessage.decode(
                    new String(zMsg.mData.getBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return true;
        }

        switch (cm.type) {
            case ChatMessage.TYPE_TEXT:
                handleText(from, cm);
                return true;
            case ChatMessage.TYPE_GROUP_TEXT:
                handleGroupText(from, cm);
                return true;
            case ChatMessage.TYPE_ROSTER:
                handleRoster(from, cm);
                return true;
            case ChatMessage.TYPE_RECEIPT:
                handleReceipt(from, cm);
                return true;
            default:
                return true;
        }
    }

    private void handleText(String zFrom, ChatMessage zMsg) {
        Entry e = new Entry(zMsg.id, zFrom, "", zFrom, zMsg.body,
                System.currentTimeMillis(), false, Receipt.DELIVERED);
        if (record(e)) {
            fire(e);
            sendReceipt(zFrom, zMsg.id, Receipt.DELIVERED);
        }
    }

    private void handleGroupText(String zFrom, ChatMessage zMsg) {
        Group g = mGroups.get(zMsg.groupId);
        if (g == null) {
            // A group we know nothing about. Ignore rather than auto-join:
            // otherwise anyone could drop you into a group unasked.
            return;
        }
        // FreezePeach's rule - only current members may post.
        if (!g.isMember(zFrom)) {
            return;
        }
        Entry e = new Entry(zMsg.id, "", zMsg.groupId, zFrom, zMsg.body,
                System.currentTimeMillis(), false, Receipt.DELIVERED);
        if (record(e)) {
            fire(e);
            sendReceipt(zFrom, zMsg.id, Receipt.DELIVERED);
        }
    }

    private void handleRoster(String zFrom, ChatMessage zMsg) {
        Group existing = mGroups.get(zMsg.groupId);

        if (existing == null) {
            // First time we hear of it: the sender must name themselves as an
            // admin, which is what an invitation looks like.
            Group g = new Group(zMsg.groupId);
            g.name = zMsg.groupName;
            g.setAdmins(Group.parseCsv(zMsg.admin));
            g.setMembers(zMsg.members);
            // An admin is implicitly a member.
            for (String a : Group.parseCsv(zMsg.admin)) {
                g.addMember(a);
            }
            if (!g.isAdmin(zFrom)) {
                return;
            }
            mGroups.put(g.id, g);
            fireGroup(g);
            return;
        }

        // Otherwise only an EXISTING admin may rewrite it. Without this any
        // member could re-add themselves after being removed.
        if (!existing.isAdmin(zFrom)) {
            return;
        }
        existing.name = zMsg.groupName.isEmpty() ? existing.name : zMsg.groupName;
        existing.setMembers(zMsg.members);
        existing.setAdmins(Group.parseCsv(zMsg.admin));
        for (String a : Group.parseCsv(zMsg.admin)) {
            existing.addMember(a);
        }
        fireGroup(existing);
    }

    private void handleReceipt(String zFrom, ChatMessage zMsg) {
        Entry e = mMessages.get(zMsg.ref);
        if (e == null || !e.mine) {
            return;
        }
        if (e.isGroup()) {
            Group g = mGroups.get(e.groupId);
            if (g == null || !g.isMember(zFrom)) {
                return;
            }
            e.deliveredBy.add(zFrom);
            // Two ticks only when EVERY current member has confirmed. Showing
            // them earlier would make the second tick meaningless.
            int need = g.others(mNode.identity().publicKeyHex()).size();
            if (e.deliveredBy.size() >= need && need > 0) {
                setState(e, Receipt.DELIVERED);
            } else {
                fireState(e);
            }
            return;
        }
        if (zFrom.equalsIgnoreCase(e.peer)) {
            setState(e, zMsg.state);
        }
    }

    /** Tell the sender we got it. This is the second tick. */
    private void sendReceipt(String zTo, String zRef, String zState) {
        Contact c = mNode.contact(zTo);
        if (c == null) {
            return;
        }
        final ChatMessage r = ChatMessage.receipt(zRef, zState);
        // Never block inbound handling on an outbound socket.
        new Thread(() -> deliver(c, r), "chat-receipt").start();
    }

    /** Mark a conversation read, and tell them only if the user allows it. */
    public void markRead(String zPeerOrGroup) {
        if (!mSendReadReceipts) {
            return;
        }
        for (Entry e : conversation(zPeerOrGroup)) {
            if (!e.mine) {
                sendReceipt(e.sender, e.id, Receipt.READ);
            }
        }
    }

    // ---------------------------------------------------------------

    private synchronized boolean record(Entry zEntry) {
        if (mMessages.containsKey(zEntry.id)) {
            return false;
        }
        mMessages.put(zEntry.id, zEntry);
        return true;
    }

    private void setState(Entry zEntry, String zState) {
        String merged = Receipt.merge(zEntry.state, zState);
        if (!merged.equals(zEntry.state)) {
            zEntry.state = merged;
            fireState(zEntry);
        }
    }

    private void fire(Entry e) {
        Listener l = mListener;
        if (l != null) {
            try {
                l.onMessage(e);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireState(Entry e) {
        Listener l = mListener;
        if (l != null) {
            try {
                l.onStateChanged(e);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireGroup(Group g) {
        Listener l = mListener;
        if (l != null) {
            try {
                l.onGroupChanged(g);
            } catch (Exception ignored) {
            }
        }
    }

    /** Restore a group from storage. */
    public void loadGroup(Group zGroup) {
        mGroups.put(zGroup.id, zGroup);
    }
}
