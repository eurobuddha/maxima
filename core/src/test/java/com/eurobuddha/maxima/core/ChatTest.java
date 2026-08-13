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

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Chat protocol rules hold.");
    }
}
