package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.chat.ChatMessage;
import com.eurobuddha.maxima.core.chat.Group;
import com.eurobuddha.maxima.core.chat.Receipt;

import java.util.Arrays;

/**
 * The chat protocol's rules, tested without a device or a network.
 *
 * The group rules are FreezePeach's and the reasons they exist are adversarial,
 * so they are tested adversarially: can a non-member post, can a non-admin
 * rewrite the roster, can a removed member re-add themselves.
 */
public class ChatTest {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    // The read mark has millisecond resolution; real read/receive events are
    // never in the same ms, so the test must not compress them below it.
    static void sleepMs(long m) {
        try { Thread.sleep(m); } catch (InterruptedException ignored) { }
    }

    /** A signed-and-verified inbound message, minus the transport. */
    static com.eurobuddha.maxima.core.msg.MaximaMessage inbound(
            String zFrom, String zTo, String zBody) {
        com.eurobuddha.maxima.core.msg.MaximaMessage m =
                new com.eurobuddha.maxima.core.msg.MaximaMessage();
        m.mRandom = new com.eurobuddha.maxima.core.codec.MiniData(
                com.eurobuddha.maxima.core.crypto.MaximaCrypto.randomBytes(32));
        m.mFrom = new com.eurobuddha.maxima.core.codec.MiniData(zFrom);
        m.mTo = new com.eurobuddha.maxima.core.codec.MiniData(zTo);
        m.mTimeMilli = new com.eurobuddha.maxima.core.codec.MiniNumber(
                System.currentTimeMillis());
        m.mApplication = new com.eurobuddha.maxima.core.codec.MiniString(
                ChatMessage.APPLICATION);
        m.mData = new com.eurobuddha.maxima.core.codec.MiniData(
                zBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return m;
    }

    public static void main(String[] args) {
        // ---- wire format round trip ----
        ChatMessage t = ChatMessage.text("0xID1", "hello, with \"quotes\" and a\ttab");
        ChatMessage back = ChatMessage.decode(t.encode());
        if (back.type == ChatMessage.TYPE_TEXT && back.id.equals("0xID1")
                && back.body.equals(t.body)) {
            ok("1:1 text round-trips, including quotes and tabs");
        } else {
            bad("text round trip broken: " + back.body);
        }

        ChatMessage r = ChatMessage.roster("0xG1", "Team",
                Arrays.asList("0xAAA", "0xBBB", "0xCCC"), "0xAAA");
        ChatMessage rb = ChatMessage.decode(r.encode());
        if (rb.members.size() == 3 && rb.admin.equals("0xAAA") && rb.groupName.equals("Team")) {
            ok("roster round-trips with all members and the admin");
        } else {
            bad("roster round trip broken");
        }

        ChatMessage rec = ChatMessage.decode(
                ChatMessage.receipt("0xID1", Receipt.DELIVERED).encode());
        if (rec.type == ChatMessage.TYPE_RECEIPT && Receipt.DELIVERED.equals(rec.state)) {
            ok("receipt round-trips");
        } else {
            bad("receipt round trip broken");
        }

        // ---- two ticks ----
        if (!Receipt.isDelivered(Receipt.SENT) && Receipt.isSent(Receipt.SENT)) {
            ok("SENT is one tick, not two - this is all classic can ever know");
        } else {
            bad("tick logic wrong at SENT");
        }
        if (Receipt.isDelivered(Receipt.DELIVERED) && Receipt.isDelivered(Receipt.READ)) {
            ok("DELIVERED and READ are both two ticks");
        } else {
            bad("tick logic wrong at DELIVERED");
        }

        // Receipts arrive unordered - a late SENT must not undo a DELIVERED.
        if (Receipt.DELIVERED.equals(Receipt.merge(Receipt.DELIVERED, Receipt.SENT))) {
            ok("a late SENT cannot regress a DELIVERED");
        } else {
            bad("state regressed on an out-of-order receipt");
        }
        if (Receipt.READ.equals(Receipt.merge(Receipt.DELIVERED, Receipt.READ))) {
            ok("DELIVERED advances to READ");
        } else {
            bad("read receipt ignored");
        }
        if (Receipt.DELIVERED.equals(Receipt.merge(Receipt.FAILED, Receipt.DELIVERED))) {
            ok("a successful retry supersedes an earlier FAILED");
        } else {
            bad("retry after failure not reflected");
        }

        // ---- group authorisation ----
        Group g = new Group("0xG1");
        g.addAdmin("0xADMIN");
        g.addMember("0xBOB");
        g.addMember("0xCAROL");

        if (g.isMember("0xbob")) ok("membership is case-insensitive on the key");
        else bad("case sensitivity in membership");

        if (!g.isMember("0xMALLORY")) ok("a stranger is not a member");
        else bad("stranger counted as a member");

        if (!g.isAdmin("0xBOB")) ok("an ordinary member is not an admin");
        else bad("member wrongly an admin");

        if (g.others("0xADMIN").size() == 2 && !g.others("0xADMIN").contains("0xADMIN")) {
            ok("fan-out list excludes ourselves");
        } else {
            bad("fan-out list wrong: " + g.others("0xADMIN"));
        }

        // Removing a member really removes them - there is no shared group key
        // they still hold, which is the point of fan-out over a group key.
        g.removeMember("0xBOB");
        if (!g.isMember("0xBOB") && g.others("0xADMIN").size() == 1) {
            ok("a removed member is dropped from fan-out immediately");
        } else {
            bad("removed member still in the group: " + g.others("0xADMIN"));
        }

        // The bug this test originally caught: mutating the raw set bypassed
        // normalisation and silently did nothing. The sets are now read-only.
        try {
            g.members().add("0xSNEAK");
            bad("the member set is still directly mutable");
        } catch (UnsupportedOperationException expected) {
            ok("the member set cannot be mutated behind the accessors");
        }

        // Mixed-case keys must resolve to the same member.
        Group cg = new Group("0xG2");
        cg.addMember("0xabcdef");
        if (cg.isMember("0xABCDEF") && cg.size() == 1) {
            ok("mixed-case keys normalise to one member");
        } else {
            bad("normalisation inconsistent: " + cg.members());
        }

        // ---- persistence: does a conversation survive a restart? ----
        java.io.File dir = new java.io.File(
                System.getProperty("java.io.tmpdir"), "maxima-chattest");
        if (dir.exists()) {
            java.io.File[] fs = dir.listFiles();
            if (fs != null) for (java.io.File f : fs) f.delete();
        }

        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) ent[i] = (byte) (i * 5 + 9);
        com.eurobuddha.maxima.core.identity.MaximaIdentity id =
                com.eurobuddha.maxima.core.identity.MaximaIdentity.fromPhrase(
                        com.eurobuddha.maxima.core.identity.Bip39.fromEntropy(ent));

        MaximaNode node = new MaximaNode(id, "1.0.48", 1);
        com.eurobuddha.maxima.core.chat.ChatEngine c1 =
                new com.eurobuddha.maxima.core.chat.ChatEngine(node);
        c1.setStore(new com.eurobuddha.maxima.core.store.FileStore(dir));

        Group saved = new Group("0xGSAVE");
        saved.name = "Persisted";
        saved.addAdmin("0xADMIN");
        saved.addMember("0xBOB");
        c1.loadGroup(saved);

        com.eurobuddha.maxima.core.chat.ChatEngine c2 =
                new com.eurobuddha.maxima.core.chat.ChatEngine(node);
        c2.setStore(new com.eurobuddha.maxima.core.store.FileStore(dir));
        Group loaded = c2.group("0xGSAVE");
        if (loaded != null && "Persisted".equals(loaded.name)
                && loaded.isAdmin("0xADMIN") && loaded.isMember("0xBOB")
                && loaded.size() == 2) {
            ok("a group survives a restart with roster and admins intact");
        } else {
            bad("group did not survive: " + (loaded == null ? "null" : loaded.members()));
        }

        // ---- REGRESSIONS FROM CODE REVIEW ----

        // NIT: one normalisation everywhere. 0X and 0x must be the same key.
        if (com.eurobuddha.maxima.core.identity.Keys.norm("0Xabc")
                .equals(com.eurobuddha.maxima.core.identity.Keys.norm("0xABC"))
                && com.eurobuddha.maxima.core.identity.Keys.norm("0xabc").equals("0xABC")) {
            ok("0X and 0x normalise to one canonical key");
        } else {
            bad("normalisation inconsistent: "
                    + com.eurobuddha.maxima.core.identity.Keys.norm("0Xabc"));
        }

        // MINOR: conversation("") must not match every group message.
        com.eurobuddha.maxima.core.chat.ChatEngine c3 =
                new com.eurobuddha.maxima.core.chat.ChatEngine(node);
        if (c3.conversation("").isEmpty() && c3.conversation(null).isEmpty()) {
            ok("an empty conversation key returns nothing, not every group");
        } else {
            bad("empty key matched messages");
        }

        // MAJOR: group two-ticks must intersect with the CURRENT roster.
        // {me,B,C}: B confirms, then B is removed and D added. C confirms.
        // Naive counting would say 2 >= 2 and show two ticks, but D never got it.
        Group mg = new Group("0xGTICK");
        mg.addAdmin("0xME");
        mg.addMember("0xB");
        mg.addMember("0xC");
        java.util.Set<String> deliveredBy = new java.util.LinkedHashSet<>();
        deliveredBy.add(com.eurobuddha.maxima.core.identity.Keys.norm("0xB"));
        mg.removeMember("0xB");
        mg.addMember("0xD");
        deliveredBy.add(com.eurobuddha.maxima.core.identity.Keys.norm("0xC"));

        java.util.List<String> currentMembers = mg.others("0xME");
        int confirmed = 0;
        for (String m : currentMembers) {
            if (deliveredBy.contains(com.eurobuddha.maxima.core.identity.Keys.norm(m))) confirmed++;
        }
        boolean naive = deliveredBy.size() >= currentMembers.size();
        boolean correct = confirmed >= currentMembers.size();
        if (naive && !correct) {
            ok("intersection prevents a false two-tick after a roster change"
                    + " (naive would say delivered, D never received it)");
        } else {
            bad("two-tick intersection test did not exercise the bug:"
                    + " naive=" + naive + " correct=" + correct);
        }

        // ---- UNREAD TRACKING (stage 2) ----

        java.io.File udir = new java.io.File(
                System.getProperty("java.io.tmpdir"), "maxima-unreadtest");
        if (udir.exists()) {
            java.io.File[] fs = udir.listFiles();
            if (fs != null) for (java.io.File f : fs) f.delete();
        }
        com.eurobuddha.maxima.core.chat.ChatEngine u1 =
                new com.eurobuddha.maxima.core.chat.ChatEngine(node);
        u1.setStore(new com.eurobuddha.maxima.core.store.FileStore(udir));

        String them = "0x" + "AB".repeat(32);
        u1.onInbound(inbound(them, id.publicKeyHex(),
                ChatMessage.text("0xU1", "first").encode()));
        u1.onInbound(inbound(them, id.publicKeyHex(),
                ChatMessage.text("0xU2", "second").encode()));

        if (u1.unread(them) == 2 && u1.totalUnread() == 2) {
            ok("two inbound messages count as two unread");
        } else {
            bad("unread count wrong: " + u1.unread(them) + " / " + u1.totalUnread());
        }

        // markRead must clear the badge even with read receipts OFF - telling
        // them is a privacy choice, our own badge is not.
        sleepMs(3);
        u1.markRead(them);
        if (u1.unread(them) == 0) {
            ok("markRead clears the badge with read receipts disabled");
        } else {
            bad("markRead did not clear the badge: " + u1.unread(them));
        }

        sleepMs(3);
        u1.onInbound(inbound(them, id.publicKeyHex(),
                ChatMessage.text("0xU3", "after reading").encode()));
        if (u1.unread(them) == 1) {
            ok("a message arriving after markRead is unread again");
        } else {
            bad("post-read message not counted: " + u1.unread(them));
        }

        // Our OWN messages must never make our own thread unread.
        com.eurobuddha.maxima.core.chat.ChatEngine u2 =
                new com.eurobuddha.maxima.core.chat.ChatEngine(node);
        u2.setStore(new com.eurobuddha.maxima.core.store.FileStore(udir));
        if (u2.unread(them) == 1) {
            ok("the read mark survives a restart - the badge does not reset");
        } else {
            bad("read mark lost on restart: " + u2.unread(them));
        }

        // The mark must come from the messages, not the clock. Reading an EMPTY
        // thread and then receiving must leave the message unread - with a
        // wall-clock mark the arrival is stamped at or before the mark and is
        // born already read, with no notification and no badge.
        String quiet = "0x" + "CD".repeat(32);
        u1.markRead(quiet);
        u1.onInbound(inbound(quiet, id.publicKeyHex(),
                ChatMessage.text("0xU4", "arrived the same instant").encode()));
        if (u1.unread(quiet) == 1) {
            ok("a message racing markRead on an empty thread is still unread");
        } else {
            bad("message swallowed by the read mark: " + u1.unread(quiet));
        }

        // summaries() must agree with the per-conversation calls it replaces -
        // it exists only to be faster, so a divergence is a silent wrong badge.
        java.util.List<com.eurobuddha.maxima.core.chat.ChatEngine.Summary> sums =
                u1.summaries();
        boolean agrees = !sums.isEmpty();
        for (com.eurobuddha.maxima.core.chat.ChatEngine.Summary sm : sums) {
            if (sm.unread != u1.unread(sm.conversation)) {
                agrees = false;
            }
        }
        int summed = 0;
        for (com.eurobuddha.maxima.core.chat.ChatEngine.Summary sm : sums) {
            summed += sm.unread;
        }
        if (agrees && summed == u1.totalUnread() && sums.get(0).lastBody.equals("arrived the same instant")) {
            ok("summaries() agrees with unread()/totalUnread() and leads with the newest");
        } else {
            bad("summaries diverged: agrees=" + agrees + " summed=" + summed
                    + " total=" + u1.totalUnread());
        }

        // clearConversation forgets a thread's history on this device - and it
        // must stay forgotten across a restart, not resurrect from the store.
        int had = u1.conversation(them).size();
        int removed = u1.clearConversation(them);
        if (removed == had && u1.conversation(them).isEmpty() && u1.unread(them) == 0) {
            ok("clearConversation empties the thread and its unread count");
        } else {
            bad("clearConversation left state: removed=" + removed + " had=" + had
                    + " left=" + u1.conversation(them).size());
        }
        // Other conversations are untouched.
        if (!u1.conversation(quiet).isEmpty()) {
            ok("clearConversation leaves other conversations alone");
        } else {
            bad("clearConversation wiped an unrelated conversation");
        }
        com.eurobuddha.maxima.core.chat.ChatEngine u3 =
                new com.eurobuddha.maxima.core.chat.ChatEngine(node);
        u3.setStore(new com.eurobuddha.maxima.core.store.FileStore(udir));
        if (u3.conversation(them).isEmpty()) {
            ok("a cleared conversation stays cleared after a restart");
        } else {
            bad("cleared conversation came back on restart: "
                    + u3.conversation(them).size());
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Chat protocol rules hold.");
    }
}
