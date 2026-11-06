package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.board.BoardEngine;
import com.eurobuddha.maxima.core.board.BoardKeys;
import com.eurobuddha.maxima.core.board.BoardPost;
import com.eurobuddha.maxima.core.board.BoardShelf;
import com.eurobuddha.maxima.core.board.BoardWire;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.store.Store;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Boards over the relay port, end to end: two real pool relays, members that never hold each
 * other as contacts, posts that cost one publish regardless of how many read them.
 */
public class BoardRelayTest {
    private static final String PROTO = MeshReplicateTest.PROTO;

    private static MaximaIdentity identity() {
        return MaximaIdentity.fromPhrase(Bip39.generate(24));
    }

    /** Every relay alive in this test, so the wait helper can lift their per-source budgets. */
    private static final java.util.List<Relay> LIVE = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** A pool relay with a memory-only board shelf, as IndependentRelaysTest hosts them. */
    private static final class Relay implements AutoCloseable {
        final MaximaIdentity identity = identity();
        final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final String host = "127.0.0.1:" + listener.getLocalPort();
        final String mls = identity.mxIdentity() + "@" + host;
        final RelayServer server = new RelayServer(identity, listener.getLocalPort(), PROTO, true);
        final BoardShelf shelf = new BoardShelf();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final Thread accept;

        Relay() throws Exception {
            server.setShared(true);
            server.setPublicHost("127.0.0.1");
            server.setBoardShelf(shelf);
            server.start();
            accept = new Thread(() -> {
                try {
                    while (!listener.isClosed()) server.admit(listener.accept(), null, null);
                } catch (Exception e) {
                    if (!listener.isClosed()) failure.set(e);
                }
            }, "board-relay-test");
            accept.setDaemon(true);
            accept.start();
            LIVE.add(this);
        }

        /** The test ticks far faster than a heartbeat and every member shares 127.0.0.1: lift the
         *  per-source "addressed to us" and board-put budgets so the harness is not what is
         *  being measured (RelayRateLimitTest covers those on their own). */
        void unthrottle() throws Exception {
            for (String name : new String[] {"mTousLimits", "mBoardPutLimits"}) {
                java.lang.reflect.Field f = RelayServer.class.getDeclaredField(name);
                f.setAccessible(true);
                ((java.util.Map<?, ?>) f.get(server)).clear();
            }
        }

        @Override public void close() throws Exception {
            LIVE.remove(this);
            listener.close();
            accept.join(5000);
            server.stop();
            assertNull("listener failed", failure.get());
        }
    }

    /** A member: a node attached to the relays, its own board engine, memory store. */
    private static final class Member implements AutoCloseable {
        final MaximaIdentity identity = identity();
        final MaximaNode node = new MaximaNode(identity, PROTO, 2);
        final BoardEngine boards = new BoardEngine(node);

        Member(List<String> zRelays) {
            node.setName("member-" + identity.fingerprint());
            node.start(zRelays, 5000);
            boards.setStore(Store.MEMORY_ONLY);
        }

        @Override public void close() {
            boards.stop();
            node.stop();
        }
    }

    static boolean waitFor(java.util.function.BooleanSupplier zCond, int zSeconds) throws Exception {
        long until = System.currentTimeMillis() + zSeconds * 1000L;
        while (System.currentTimeMillis() < until) {
            if (zCond.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return zCond.getAsBoolean();
    }

    /** Keep ticking the member (its pulls are asynchronous) until the condition holds. */
    static boolean sync(Member zM, java.util.function.BooleanSupplier zCond, int zSeconds) throws Exception {
        long until = System.currentTimeMillis() + zSeconds * 1000L;
        while (System.currentTimeMillis() < until) {
            if (zCond.getAsBoolean()) return true;
            for (Relay r : LIVE) r.unthrottle();
            zM.boards.tick();
            Thread.sleep(400);
        }
        return zCond.getAsBoolean();
    }

    @Test public void wirePutAndSinceAgainstARealRelay() throws Exception {
        try (Relay r = new Relay()) {
            MaximaIdentity me = identity();
            MaximaNode node = new MaximaNode(me, PROTO, 2);
            try {
                byte[] board = BoardKeys.newBoardId();
                KeyPair pseudonym = BoardKeys.pseudonym(me, board);
                byte[] raw = new BoardPost(board, BoardKeys.newPostId(), 1, 1, null, new byte[] {7}).sign(pseudonym).raw;
                assertTrue(BoardWire.put(node, r.mls, pseudonym, raw));
                assertTrue("idempotent", BoardWire.put(node, r.mls, pseudonym, raw));
                assertEquals(1, r.shelf.count(board));

                BoardWire.SinceReply reply = BoardWire.since(node, r.mls, new BoardWire.SinceRequest().add(board, 0));
                assertNotNull(reply);
                assertEquals(1, reply.slices.size());
                assertEquals(1, reply.slices.get(0).raws.size());
                assertEquals(BoardPost.verify(raw).post.idHex(), BoardPost.verify(reply.slices.get(0).raws.get(0)).post.idHex());
                long cursor = reply.slices.get(0).cursor;
                BoardWire.SinceReply none = BoardWire.since(node, r.mls, new BoardWire.SinceRequest().add(board, cursor));
                assertNotNull(none);
                assertEquals(0, none.slices.get(0).raws.size());

                // a non-pool relay says "not offered"
                r.server.setBoardShelf(null);
                assertFalse(BoardWire.put(node, r.mls, pseudonym, raw));
                assertNull(BoardWire.since(node, r.mls, new BoardWire.SinceRequest().add(board, 0)));
            } finally {
                node.stop();
            }
        }
    }

    @Test public void openBoardJoinedByLinkReadsAndReplies() throws Exception {
        try (Relay a = new Relay(); Relay b = new Relay()) {
            List<String> seeds = java.util.Arrays.asList(a.host, b.host);
            try (Member alice = new Member(seeds); Member bob = new Member(seeds)) {
                assertTrue(waitFor(() -> alice.node.poolMlsAddresses().size() == 2, 10));
                assertTrue(waitFor(() -> bob.node.poolMlsAddresses().size() == 2, 10));

                BoardEngine.Board ab = alice.boards.create("Allotment", true);
                String link = alice.boards.link(ab.id);
                assertTrue(link.contains("&k="));
                BoardEngine.Entry first = alice.boards.post(ab.id, "First post", null);
                // one publish per relay the board knows, nothing per member
                assertTrue(waitFor(() -> a.shelf.count(ab.idBytes) >= 3 && b.shelf.count(ab.idBytes) >= 3, 10));

                BoardEngine.Board bb = bob.boards.join(link);
                assertTrue(bb.open);
                assertTrue(bb.canPost());
                                assertTrue(sync(bob, () -> bob.boards.posts(bb.id, 0).size() == 1, 10));
                BoardEngine.Entry seen = bob.boards.posts(bb.id, 0).get(0);
                assertEquals("First post", seen.body());
                assertEquals(first.id, seen.id);
                assertFalse(seen.mine);
                assertEquals("Allotment", bob.boards.board(bb.id).name);
                assertEquals(2, bb.memberCount());

                bob.boards.post(bb.id, "Welcome aboard", first.id);
                                assertTrue(sync(alice, () -> alice.boards.posts(ab.id, 0).size() == 2, 10));
                BoardEngine.Entry reply = alice.boards.posts(ab.id, 0).get(1);
                assertEquals("Welcome aboard", reply.body());
                assertEquals(first.id, reply.re);
                assertTrue(alice.boards.board(ab.id).nameOf(reply.from).startsWith("member-"));
                assertEquals(1, alice.boards.unread(alice.boards.board(ab.id)));

                // a relay that forgot everything is re-seeded by a member
                a.shelf.setStore(Store.MEMORY_ONLY);
                assertEquals(0, a.shelf.count(ab.idBytes));
                                boolean reseeded = sync(alice, () -> a.shelf.count(ab.idBytes) >= 4, 10);
                assertTrue("reseed: relay has " + a.shelf.count(ab.idBytes) + " alice entries " + alice.boards.posts(ab.id, 0).size()
                        + " pending " + alice.boards.board(ab.id).pending + " relays " + alice.boards.board(ab.id).relays
                        + " cursors " + alice.boards.board(ab.id).cursors, reseeded);
            }
        }
    }

    @Test public void inviteOnlyBoardKnockApproveRemove() throws Exception {
        try (Relay a = new Relay()) {
            List<String> seeds = Collections.singletonList(a.host);
            try (Member admin = new Member(seeds); Member carol = new Member(seeds); Member dave = new Member(seeds)) {
                assertTrue(waitFor(() -> admin.node.poolMlsAddresses().size() == 1
                        && carol.node.poolMlsAddresses().size() == 1 && dave.node.poolMlsAddresses().size() == 1, 10));
                BoardEngine.Board ab = admin.boards.create("Committee", false);
                String link = admin.boards.link(ab.id);
                assertFalse("invite-only links carry no key", link.contains("&k="));
                admin.boards.post(ab.id, "Agenda", null);

                BoardEngine.Board cb = carol.boards.join(link);
                assertTrue(cb.joining);
                assertFalse(cb.canPost());
                assertTrue(waitFor(() -> a.shelf.count(ab.idBytes) >= 4, 10));   // roster, hello, agenda, knock

                                assertTrue(sync(admin, () -> !admin.boards.knocks(ab.id).isEmpty(), 10));
                BoardEngine.Knock k = admin.boards.knocks(ab.id).get(0);
                assertTrue(k.name.startsWith("member-"));
                admin.boards.approve(ab.id, k.fp);

                                assertTrue(sync(carol, () -> carol.boards.board(cb.id).canPost(), 10));
                assertTrue(sync(carol, () -> carol.boards.posts(cb.id, 0).size() == 1, 10));
                assertEquals("Agenda", carol.boards.posts(cb.id, 0).get(0).body());
                assertTrue(admin.boards.knocks(ab.id).isEmpty());

                carol.boards.post(cb.id, "Present", null);
                                assertTrue(sync(admin, () -> admin.boards.posts(ab.id, 0).size() == 2, 10));

                // Dave holds the link but was never let in: he can read nothing
                BoardEngine.Board db = dave.boards.join(link);
                assertFalse(sync(dave, () -> !dave.boards.posts(db.id, 0).isEmpty(), 3));
                assertEquals(0, dave.boards.posts(db.id, 0).size());

                // Remove Carol: a new epoch she cannot open
                int before = admin.boards.board(ab.id).epoch;
                admin.boards.remove(ab.id, k.fp);
                assertEquals(before + 1, admin.boards.board(ab.id).epoch);
                admin.boards.post(ab.id, "After removal", null);
                assertTrue("carol learns she is off the roster (under the old key)",
                        sync(carol, () -> !carol.boards.board(cb.id).rosterView().contains(k.fp), 10));
                assertFalse("and nothing sealed under the new key opens", sync(carol, () -> carol.boards.posts(cb.id, 0).size() != 1, 3));
                assertEquals("Carol sees nothing sealed under the new key", 1,
                        carol.boards.posts(cb.id, 0).size());
                assertEquals(before, carol.boards.board(cb.id).epoch);
            }
        }
    }
}
