package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.Group;
import com.eurobuddha.maxima.core.chat.Receipt;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

/**
 * CHAT END TO END, LIVE, THROUGH A REAL RELAY.
 *
 * Two nodes behind NAT, on real sockets, exchanging real messages: 1:1, the
 * second tick, a group, and a read receipt.
 *
 * The second tick is the part that cannot be faked in a unit test. It requires
 * the recipient's device to send a NEW message back, which is only possible
 * because replies here are fresh outbound messages rather than socket acks -
 * the change that lets a NAT'd phone answer at all. Verifying it in-process
 * would prove nothing about that.
 */
public class LiveChatTest {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("    ok  " + m); }
    static void bad(String m) { fail++; System.out.println("    XX  " + m); }

    static MaximaIdentity idFrom(int zSalt) {
        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) {
            ent[i] = (byte) (i * zSalt + zSalt);
        }
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(ent));
    }

    static Thread pump(String zLabel, MaximaNode zNode, String zHostPort, CountDownLatch zStop) {
        Thread t = new Thread(() -> {
            while (zStop.getCount() > 0) {
                try {
                    zNode.pump(zHostPort, 2000);
                } catch (Exception e) {
                    if (zStop.getCount() > 0) {
                        return;
                    }
                }
            }
        }, "pump-" + zLabel);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Poll rather than sleep a fixed time - a slow relay should not fail us. */
    static boolean waitFor(java.util.function.BooleanSupplier zCond, int zSeconds)
            throws InterruptedException {
        long until = System.currentTimeMillis() + zSeconds * 1000L;
        while (System.currentTimeMillis() < until) {
            if (zCond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(250);
        }
        return zCond.getAsBoolean();
    }

    public static void main(String[] args) throws Exception {
        String relayA = args.length > 0 ? args[0] : "31.125.188.214:8001";
        String relayB = args.length > 1 ? args[1] : "31.125.188.214:8001";

        System.out.println("=== CHAT, LIVE THROUGH A RELAY ===");
        System.out.println("alice " + relayA + " | bob " + relayB + "\n");

        MaximaNode alice = new MaximaNode(idFrom(21), "1.0.48", 1);
        MaximaNode bob = new MaximaNode(idFrom(29), "1.0.48", 1);
        alice.setName("alice");
        bob.setName("bob");

        ChatEngine aChat = new ChatEngine(alice);
        ChatEngine bChat = new ChatEngine(bob);
        // Bob answers read receipts; alice does not. Asymmetric on purpose -
        // the setting must be the RECIPIENT's choice, not the sender's.
        bChat.setSendReadReceipts(true);

        alice.setMessageListener((msg, msgid) -> aChat.onInbound(msg));
        bob.setMessageListener((msg, msgid) -> bChat.onInbound(msg));

        System.out.println("[1] attaching");
        int na = alice.start(Collections.singletonList(relayA), 30000);
        int nb = bob.start(Collections.singletonList(relayB), 30000);
        if (na >= 1 && nb >= 1) {
            ok("both attached (alice " + na + ", bob " + nb + ")");
        } else {
            bad("attach failed");
            System.exit(1);
        }

        CountDownLatch stop = new CountDownLatch(1);
        pump("alice", alice, relayA, stop);
        pump("bob", bob, relayB, stop);
        Thread.sleep(1500);

        System.out.println("\n[2] contact handshake");
        bob.introduce(alice.myAddresses().get(0), true);
        boolean known = waitFor(() ->
                alice.contact(bob.identity().publicKeyHex()) != null
                        && bob.contact(alice.identity().publicKeyHex()) != null, 30);
        if (known) {
            ok("alice and bob know each other");
        } else {
            bad("handshake did not complete");
            System.exit(1);
        }

        Contact bobAtAlice = alice.contact(bob.identity().publicKeyHex());
        Contact aliceAtBob = bob.contact(alice.identity().publicKeyHex());

        // ---------------- 1:1 and the second tick ----------------
        System.out.println("\n[3] alice -> bob, and the second tick");
        ChatEngine.Entry sent = aChat.send(bobAtAlice, "hello bob, over a real relay");
        if (Receipt.isSent(sent.state)) {
            ok("one tick immediately - a host took the bytes");
        } else {
            bad("send failed: " + sent.state);
        }

        boolean arrived = waitFor(() -> !bChat.conversation(
                alice.identity().publicKeyHex()).isEmpty(), 30);
        if (arrived) {
            ok("bob received it: \"" + bChat.conversation(
                    alice.identity().publicKeyHex()).get(0).body + "\"");
        } else {
            bad("bob never received it");
        }

        boolean delivered = waitFor(() -> Receipt.DELIVERED.equals(sent.state)
                || Receipt.READ.equals(sent.state), 30);
        if (delivered) {
            ok("SECOND TICK: bob's device confirmed delivery (" + sent.state + ")");
        } else {
            bad("no delivery receipt came back, state=" + sent.state);
        }

        // ---------------- read receipt ----------------
        System.out.println("\n[4] read receipt (bob has them enabled)");
        bChat.markRead(alice.identity().publicKeyHex());
        boolean read = waitFor(() -> Receipt.READ.equals(sent.state), 30);
        if (read) {
            ok("blue ticks: bob's device reported READ");
        } else {
            bad("read receipt did not arrive, state=" + sent.state);
        }

        // ---------------- unread bookkeeping ----------------
        if (bChat.unread(alice.identity().publicKeyHex()) == 0
                && aChat.unread(bob.identity().publicKeyHex()) == 0) {
            ok("unread counts are right on both sides after reading");
        } else {
            bad("unread wrong: bob=" + bChat.unread(alice.identity().publicKeyHex())
                    + " alice=" + aChat.unread(bob.identity().publicKeyHex()));
        }

        // ---------------- group ----------------
        System.out.println("\n[5] a group of two, over the wire");
        Group g = aChat.createGroup("Live test",
                Collections.singletonList(bob.identity().publicKeyHex()));
        boolean rostered = waitFor(() -> {
            Group bg = bChat.group(g.id);
            return bg != null && bg.isMember(bob.identity().publicKeyHex())
                    && bg.isAdmin(alice.identity().publicKeyHex());
        }, 30);
        if (rostered) {
            ok("bob received the roster and has alice as admin");
        } else {
            bad("roster never arrived");
        }

        ChatEngine.Entry gm = aChat.sendGroup(g.id, "group message over a relay");
        boolean gotGroup = waitFor(() -> !bChat.conversation(g.id).isEmpty(), 30);
        if (gotGroup) {
            ok("bob received the group message");
        } else {
            bad("group message never arrived");
        }

        boolean groupDelivered = waitFor(() -> Receipt.isDelivered(gm.state), 30);
        if (groupDelivered) {
            ok("group second tick: every current member confirmed");
        } else {
            bad("no group delivery confirmation, state=" + gm.state);
        }

        // A non-member must not be able to post into the group. Bob is a
        // member here, so the check is the reverse: alice is NOT in a group bob
        // invents, and her engine must ignore his message rather than join.
        System.out.println("\n[6] a group we were never invited to is ignored");
        Group ghost = new Group("0xGHOSTGROUP");
        ghost.addAdmin(bob.identity().publicKeyHex());
        ghost.addMember(alice.identity().publicKeyHex());
        bChat.loadGroup(ghost);
        bChat.sendGroup(ghost.id, "you should never see this");
        Thread.sleep(6000);
        if (aChat.group(ghost.id) == null && aChat.conversation(ghost.id).isEmpty()) {
            ok("alice ignored a group message for a group she never joined");
        } else {
            bad("alice auto-joined a group she was never invited to");
        }

        stop.countDown();
        alice.stop();
        bob.stop();

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("Chat works end to end over a live relay.");
    }
}
