package com.eurobuddha.maxima.core.board;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.security.KeyPair;
import org.junit.Test;

/** The envelope: signed by a pseudonym, verified by everyone, sealed under the board key. */
public class BoardPostTest {

    static MaximaIdentity identity() {
        return MaximaIdentity.fromPhrase(Bip39.generate(24));
    }

    @Test
    public void signVerifyRoundTrip() {
        MaximaIdentity me = identity();
        byte[] board = BoardKeys.newBoardId();
        KeyPair pseudonym = BoardKeys.pseudonym(me, board);
        byte[] key = BoardKeys.newBoardKey();
        byte[] blob = BoardKeys.seal(key, "hello board".getBytes());
        BoardPost p = new BoardPost(board, BoardKeys.newPostId(), 1234L, 1, null, blob);
        BoardPost.Signed s = p.sign(pseudonym);

        BoardPost.Signed v = BoardPost.verify(s.raw);
        assertEquals(p.boardHex(), v.post.boardHex());
        assertEquals(p.idHex(), v.post.idHex());
        assertEquals(1234L, v.post.mTs.getAsLong());
        assertEquals(1, v.post.mEpoch.getAsInt());
        assertFalse(v.post.isReply());
        assertArrayEquals(pseudonym.getPublic().getEncoded(), v.signer);
        assertEquals(s.signerFp, v.signerFp);
        assertEquals("hello board", new String(BoardKeys.open(key, v.post.mBlob.getBytes())));
    }

    @Test
    public void tamperedBytesDoNotVerify() {
        MaximaIdentity me = identity();
        byte[] board = BoardKeys.newBoardId();
        BoardPost.Signed s = new BoardPost(board, BoardKeys.newPostId(), 1L, 1, null, new byte[] {1, 2, 3})
                .sign(BoardKeys.pseudonym(me, board));
        byte[] bad = s.raw.clone();
        bad[bad.length / 2] ^= 0x01;
        try {
            BoardPost.verify(bad);
            fail("altered envelope verified");
        } catch (IllegalArgumentException expected) {
            // good
        }
    }

    @Test
    public void pseudonymIsPerBoardAndStable() {
        MaximaIdentity me = identity();
        byte[] a = BoardKeys.newBoardId();
        byte[] b = BoardKeys.newBoardId();
        assertArrayEquals(BoardKeys.pseudonym(me, a).getPublic().getEncoded(),
                BoardKeys.pseudonym(me, a).getPublic().getEncoded());
        assertFalse(java.util.Arrays.equals(BoardKeys.pseudonym(me, a).getPublic().getEncoded(),
                BoardKeys.pseudonym(me, b).getPublic().getEncoded()));
        // and never the identity key itself
        assertFalse(java.util.Arrays.equals(me.publicKey(), BoardKeys.pseudonym(me, a).getPublic().getEncoded()));
    }

    @Test
    public void wrongKeyOpensNothing() {
        byte[] k1 = BoardKeys.newBoardKey();
        byte[] k2 = BoardKeys.newBoardKey();
        byte[] blob = BoardKeys.seal(k1, "secret".getBytes());
        assertNull(BoardKeys.open(k2, blob));
        blob[blob.length - 1] ^= 0x01;
        assertNull("GCM tag catches a flipped bit", BoardKeys.open(k1, blob));
    }

    @Test
    public void linkRoundTrip() {
        byte[] board = BoardKeys.newBoardId();
        byte[] key = BoardKeys.newBoardKey();
        String admin = "0x" + "AB".repeat(81);
        BoardLink open = new BoardLink(board, "Friends & family", admin, 3, key,
                java.util.Arrays.asList("MxA@1.2.3.4:9501", "MxB@5.6.7.8:9501"));
        BoardLink back = BoardLink.parse(open.format());
        assertArrayEquals(board, back.boardId);
        assertEquals("Friends & family", back.name);
        assertEquals(admin, back.adminHex);
        assertEquals(3, back.epoch);
        assertArrayEquals(key, back.key);
        assertEquals(2, back.relays.size());
        assertTrue(back.hasKey());

        BoardLink invite = BoardLink.parse(new BoardLink(board, "x", admin, 0, null, null).format());
        assertFalse(invite.hasKey());
        assertTrue(BoardLink.isLink(invite.format()));
        assertFalse(BoardLink.isLink("https://example.org"));
    }
}
