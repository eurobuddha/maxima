package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Store-and-forward, end to end, through a REAL relay:
 *
 *   1. bob attaches, so the relay knows him as a route
 *   2. bob goes away
 *   3. alice sends to bob -> relay holds it in the mailbox
 *   4. bob comes back -> the relay DRAINS the mailbox to him on reconnect
 *
 * Step 4 is the part that was built but never wired at the relay (the review's
 * MAJOR). It only works against a relay running 0.1.6+.
 */
public class LiveMailboxTest {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static MaximaIdentity idFrom(int zSalt) {
        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) {
            ent[i] = (byte) (i * zSalt + zSalt);
        }
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(ent));
    }

    static Thread pump(MaximaNode zNode, String zHostPort, CountDownLatch zStop) {
        Thread t = new Thread(() -> {
            while (zStop.getCount() > 0) {
                try {
                    zNode.pump(zHostPort, 1500);
                } catch (Exception e) {
                    if (zStop.getCount() > 0) return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static boolean waitFor(java.util.function.BooleanSupplier c, int s) throws Exception {
        long until = System.currentTimeMillis() + s * 1000L;
        while (System.currentTimeMillis() < until) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(300);
        }
        return c.getAsBoolean();
    }

    public static void main(String[] args) throws Exception {
        String relay = args.length > 0 ? args[0] : "95.179.179.181:9501";
        System.out.println("=== STORE-AND-FORWARD, LIVE through " + relay + " ===\n");

        MaximaIdentity aliceId = idFrom(51);
        MaximaIdentity bobId = idFrom(57);
        MaximaNode alice = new MaximaNode(aliceId, "1.0.48", 1);
        MaximaNode bob = new MaximaNode(bobId, "1.0.48", 1);
        alice.setName("alice");
        bob.setName("bob");

        final AtomicInteger bobGot = new AtomicInteger();
        bob.setMessageListener((msg, msgid) -> bobGot.incrementAndGet());

        // Both attach so the relay learns bob as a known route, and they know
        // each other (a message needs a contact to seal to).
        alice.start(Collections.singletonList(relay), 30000);
        bob.start(Collections.singletonList(relay), 30000);
        CountDownLatch stop = new CountDownLatch(1);
        pump(alice, relay, stop);
        Thread bobPump = pump(bob, relay, stop);
        Thread.sleep(1500);

        bob.introduce(alice.myAddresses().get(0), true);
        boolean known = waitFor(() -> alice.contact(bobId.publicKeyHex()) != null, 30);
        if (known) {
            ok("bob is a known route on the relay, and alice has him as a contact");
        } else {
            bad("setup handshake failed");
            System.exit(1);
        }
        Contact bobAtAlice = alice.contact(bobId.publicKeyHex());
        // Capture bob's address WHILE he is up: after he stops, the address is
        // still the right routing key @ relay, but we hold it explicitly so the
        // send does not depend on contact bookkeeping.
        String bobAddr = bobAtAlice.primaryAddress();
        System.out.println("    bob is at " + bobAddr);

        // ---- bob goes offline ----
        System.out.println("[*] bob disconnects");
        // Stop bob's pump and connection so the relay has no live route for him.
        bob.stop();
        bobPump.interrupt();
        Thread.sleep(2000);

        // ---- alice sends; relay should HOLD it ----
        System.out.println("[*] alice sends to the now-offline bob");
        com.eurobuddha.maxima.core.MaximaSender.Result r =
                alice.sendRaw(bobAddr, "held-for-you",
                        "while you were out".getBytes());
        // The relay answers UNKNOWN (classic behaviour) but stores it.
        System.out.println("    relay replied " + r.statusName + " (UNKNOWN expected; held silently)");

        // ---- bob returns; relay should DRAIN on reconnect ----
        System.out.println("[*] bob reconnects");
        MaximaNode bob2 = new MaximaNode(bobId, "1.0.48", 1);
        final AtomicInteger bob2Got = new AtomicInteger();
        final java.util.List<String> bodies = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        bob2.setMessageListener((msg, msgid) -> {
            bob2Got.incrementAndGet();
            bodies.add(new String(msg.mData.getBytes()));
        });
        bob2.start(Collections.singletonList(relay), 30000);
        CountDownLatch stop2 = new CountDownLatch(1);
        pump(bob2, relay, stop2);

        boolean drained = waitFor(() -> bob2Got.get() > 0, 40);
        if (drained && bodies.contains("while you were out")) {
            ok("MAILBOX DRAINED ON RECONNECT: bob got the message held while offline");
        } else {
            bad("mailbox was not drained on reconnect (got " + bob2Got.get()
                    + " " + bodies + ") - needs relay 0.1.6+");
        }

        stop.countDown();
        stop2.countDown();
        alice.stop();
        bob2.stop();

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Store-and-forward works end to end.");
    }
}
