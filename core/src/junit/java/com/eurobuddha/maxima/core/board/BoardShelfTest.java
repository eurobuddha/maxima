package com.eurobuddha.maxima.core.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.store.FileStore;

import java.io.File;
import java.security.KeyPair;
import org.junit.Test;

/** The relay's shelf: idempotent puts, cursor deltas, reset, per-board quota, reload. */
public class BoardShelfTest {

    static File tmp(String zTag) {
        return new File(System.getProperty("java.io.tmpdir"), "maxima-boards-" + zTag + "-" + System.nanoTime());
    }

    private final MaximaIdentity me = MaximaIdentity.fromPhrase(Bip39.generate(24));
    private final byte[] board = BoardKeys.newBoardId();
    private final KeyPair pseudonym = BoardKeys.pseudonym(me, board);

    private byte[] post(byte[] zBoard, int zN) {
        return new BoardPost(zBoard, BoardKeys.newPostId(), zN, 1, null, new byte[] {(byte) zN}).sign(pseudonym).raw;
    }

    @Test
    public void putsAreIdempotentAndSinceIsCursorBased() {
        BoardShelf s = new BoardShelf();
        byte[] a = post(board, 1);
        byte[] b = post(board, 2);
        assertEquals(BoardShelf.Result.STORED, s.put(a));
        assertEquals(BoardShelf.Result.DUPLICATE, s.put(a));
        assertEquals(BoardShelf.Result.STORED, s.put(b));
        assertEquals(2, s.count(board));

        BoardWire.Slice first = s.since(board, 0, BoardWire.MAX_REPLY_BYTES);
        assertEquals(2, first.raws.size());
        assertFalse("cursor 0 is a first read, nothing was dropped", first.reset);
        BoardWire.Slice none = s.since(board, first.cursor, BoardWire.MAX_REPLY_BYTES);
        assertEquals(0, none.raws.size());
        assertFalse(none.reset);
        assertEquals(first.cursor, none.cursor);

        byte[] c = post(board, 3);
        assertEquals(BoardShelf.Result.STORED, s.put(c));
        BoardWire.Slice delta = s.since(board, first.cursor, BoardWire.MAX_REPLY_BYTES);
        assertEquals(1, delta.raws.size());
        assertFalse(delta.reset);
        assertTrue(delta.cursor > first.cursor);
    }

    @Test
    public void junkIsRefused() {
        BoardShelf s = new BoardShelf();
        assertEquals(BoardShelf.Result.INVALID, s.put(new byte[] {1, 2, 3}));
        byte[] bad = post(board, 1);
        bad[bad.length / 2] ^= 1;
        assertEquals(BoardShelf.Result.INVALID, s.put(bad));
        assertEquals(BoardShelf.Result.TOOBIG, s.put(new byte[BoardPost.MAX_BYTES + 1]));
        assertEquals(0, s.boardCount());
    }

    @Test
    public void perBoardQuotaDropsOldestAndFlagsReset() {
        BoardShelf s = new BoardShelf(3, 1 << 20, 10, 1 << 24, 1000, 1L << 40);
        byte[][] p = new byte[5][];
        for (int i = 0; i < 5; i++) {
            p[i] = post(board, i + 1);
            assertEquals(BoardShelf.Result.STORED, s.put(p[i]));
        }
        assertEquals(3, s.count(board));
        BoardWire.Slice all = s.since(board, 0, BoardWire.MAX_REPLY_BYTES);
        assertEquals(3, all.raws.size());
        assertEquals("oldest kept is #3", 3, BoardPost.verify(all.raws.get(0)).post.mBlob.getBytes()[0]);
        assertFalse("a first read is not a gap", all.reset);
        // A cursor BELOW the newest dropped post has missed posts; one AT it has not (it had that
        // post, and everything after it is still here). Seqs are consecutive within a burst.
        BoardWire.Slice firstKept = s.since(board, 0, 1);   // one post: its cursor is #3's seq
        assertFalse(s.since(board, firstKept.cursor - 1, BoardWire.MAX_REPLY_BYTES).reset);   // at #2
        assertTrue(s.since(board, firstKept.cursor - 2, BoardWire.MAX_REPLY_BYTES).reset);    // at #1
    }

    @Test
    public void relayFullEvictsLeastRecentlyReadBoard() {
        BoardShelf s = new BoardShelf(100, 1 << 20, 2, 1 << 24, 1000, 1L << 40);
        byte[] b1 = BoardKeys.newBoardId();
        byte[] b2 = BoardKeys.newBoardId();
        byte[] b3 = BoardKeys.newBoardId();
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(b1, BoardKeys.newPostId(), 1, 1, null, new byte[1]).sign(pseudonym).raw));
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(b2, BoardKeys.newPostId(), 2, 1, null, new byte[1]).sign(pseudonym).raw));
        s.since(b1, 0, 1 << 20);   // b1 read now, b2 is the LRU
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(b3, BoardKeys.newPostId(), 3, 1, null, new byte[1]).sign(pseudonym).raw));
        assertEquals(2, s.boardCount());
        assertEquals(1, s.count(b1));
        assertEquals(0, s.count(b2));
        assertEquals(1, s.count(b3));
    }

    @Test
    public void relayFullShedsOldestPostsOfLeastReadBoardsNotWholeBoards() {
        BoardShelf s = new BoardShelf(100, 1 << 20, 10, 1L << 40, 3, 1L << 40);   // 3 posts in all
        byte[] b1 = BoardKeys.newBoardId();
        byte[] b2 = BoardKeys.newBoardId();
        byte[] b3 = BoardKeys.newBoardId();
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(b1, BoardKeys.newPostId(), 1, 1, null, new byte[1]).sign(pseudonym).raw));
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(b1, BoardKeys.newPostId(), 2, 1, null, new byte[1]).sign(pseudonym).raw));
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(b2, BoardKeys.newPostId(), 3, 1, null, new byte[1]).sign(pseudonym).raw));
        s.since(b1, 0, 1 << 20);   // b1 read now: b2 is the least-recently-read
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(b3, BoardKeys.newPostId(), 4, 1, null, new byte[1]).sign(pseudonym).raw));
        assertEquals("b2 shed its only post and left", 0, s.count(b2));
        assertEquals(2, s.count(b1));
        assertEquals(1, s.count(b3));
        // Next in line is b1 (b3 was just posted to): it loses its OLDEST post, keeps the newer.
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(b3, BoardKeys.newPostId(), 5, 1, null, new byte[1]).sign(pseudonym).raw));
        assertEquals(1, s.count(b1));
        assertEquals(2, s.count(b3));
        assertEquals(2, s.boardCount());
    }

    @Test
    public void resetOnlyWhenTheCursorPredatesSomethingDropped() {
        BoardShelf s = new BoardShelf(2, 1 << 20, 10, 1L << 40, 1000, 1L << 40);   // 2 posts per board
        byte[] b = BoardKeys.newBoardId();
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(b, BoardKeys.newPostId(), 1, 1, null, new byte[1]).sign(pseudonym).raw));
        long first = s.since(b, 0, 1 << 20).cursor;
        assertFalse("a fresh board has dropped nothing", s.since(b, 0, 1 << 20).reset);
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(b, BoardKeys.newPostId(), 2, 1, null, new byte[1]).sign(pseudonym).raw));
        long second = s.since(b, first, 1 << 20).cursor;
        assertFalse(s.since(b, first, 1 << 20).reset);
        // third post: the per-board cap drops the first
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(b, BoardKeys.newPostId(), 3, 1, null, new byte[1]).sign(pseudonym).raw));
        assertTrue("cursor at the dropped post may have missed something", s.since(b, first - 1, 1 << 20).reset);
        assertFalse("cursor at a retained post is exact", s.since(b, second, 1 << 20).reset);
        assertFalse("cursor 0 is a first read, not a gap", s.since(b, 0, 1 << 20).reset);
    }

    @Test
    public void survivesRestartOnDisk() {
        File dir = tmp("reload");
        BoardShelf s = new BoardShelf();
        s.setStore(new FileStore(dir));
        byte[] a = post(board, 1);
        byte[] b = post(board, 2);
        assertEquals(BoardShelf.Result.STORED, s.put(a));
        assertEquals(BoardShelf.Result.STORED, s.put(b));
        long cursor = s.since(board, 0, 1 << 20).cursor;
        s.flush();

        BoardShelf again = new BoardShelf();
        again.setStore(new FileStore(dir));
        assertEquals(2, again.count(board));
        assertEquals(BoardShelf.Result.DUPLICATE, again.put(a));
        BoardWire.Slice all = again.since(board, 0, 1 << 20);
        assertEquals(2, all.raws.size());
        assertEquals(cursor, all.cursor);
        byte[] c = post(board, 3);
        assertEquals(BoardShelf.Result.STORED, again.put(c));
        assertTrue("sequence keeps climbing after a restart", again.since(board, cursor, 1 << 20).cursor > cursor);
    }

    @Test
    public void answerBatchesBoardsWithinBudget() {
        BoardShelf s = new BoardShelf();
        byte[] other = BoardKeys.newBoardId();
        assertEquals(BoardShelf.Result.STORED, s.put(post(board, 1)));
        assertEquals(BoardShelf.Result.STORED, s.put(new BoardPost(other, BoardKeys.newPostId(), 1, 1, null, new byte[1]).sign(pseudonym).raw));
        BoardWire.SinceRequest req = new BoardWire.SinceRequest().add(board, 0).add(other, 0).add(BoardKeys.newBoardId(), 0);
        BoardWire.SinceReply reply = s.answer(req);
        assertEquals(3, reply.slices.size());
        assertEquals(1, reply.slices.get(0).raws.size());
        assertEquals(1, reply.slices.get(1).raws.size());
        assertEquals(0, reply.slices.get(2).raws.size());
        assertTrue("unknown board reads as reset", reply.slices.get(2).reset);
        // wire round trip
        BoardWire.SinceReply back;
        try {
            back = BoardWire.SinceReply.fromBytes(reply.toBytes());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(3, back.slices.size());
        assertEquals(reply.slices.get(0).cursor, back.slices.get(0).cursor);
    }
}
