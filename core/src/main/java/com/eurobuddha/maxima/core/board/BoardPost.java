package com.eurobuddha.maxima.core.board;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.Streamable;
import com.eurobuddha.maxima.core.crypto.MaximumMessage;
import com.eurobuddha.maxima.core.identity.Keys;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.KeyPair;

/**
 * One post on a board: the body a member signs, and the signed envelope that travels.
 *
 * A board is "post once, read by many": the envelope is published to a few pool relays and every
 * member pulls it. The relay stores it as opaque bytes - the content is ciphertext under the board
 * key ({@link BoardKeys}) and the signer is a per-board pseudonym key, so a relay learns neither
 * who is talking nor what is said, only that a valid member-shaped key posted.
 *
 * The signed envelope is a {@link MaximumMessage} (data = this body, publickey = the pseudonym,
 * signature = SHA256withRSA over the body), the same signed-blob shape classic uses for invites, so
 * no JSON canonicalisation is needed on either side.
 *
 * <pre>
 *   MiniNumber version  (1)
 *   MiniData   board    32 random bytes - the board id
 *   MiniData   id       16 random bytes - the post id (relays dedup on board|id)
 *   MiniNumber ts       sender's clock, ms
 *   MiniNumber epoch    which board key sealed the blob; 0 = sealed to ONE key (knock / welcome)
 *   MiniData   re       16 bytes = the parent post this replies to, empty = a top-level post
 *   MiniData   blob     ciphertext (BoardKeys.seal), or a CryptoPackage when epoch == 0
 * </pre>
 */
public final class BoardPost implements Streamable {

    /** The signed envelope on the wire - generous for a rekey chunk, far below the 256K package. */
    public static final int MAX_BYTES = 64 * 1024;
    public static final int BOARD_BYTES = 32;
    public static final int ID_BYTES = 16;

    public MiniNumber mVersion = new MiniNumber(1);
    public MiniData mBoard = new MiniData(new byte[0]);
    public MiniData mId = new MiniData(new byte[0]);
    public MiniNumber mTs = new MiniNumber(0);
    public MiniNumber mEpoch = new MiniNumber(0);
    public MiniData mRe = new MiniData(new byte[0]);
    public MiniData mBlob = new MiniData(new byte[0]);

    public BoardPost() {
    }

    public BoardPost(byte[] zBoard, byte[] zId, long zTs, long zEpoch, byte[] zRe, byte[] zBlob) {
        mBoard = new MiniData(zBoard);
        mId = new MiniData(zId);
        mTs = new MiniNumber(zTs);
        mEpoch = new MiniNumber(zEpoch);
        mRe = new MiniData(zRe == null ? new byte[0] : zRe);
        mBlob = new MiniData(zBlob);
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mVersion.writeDataStream(zOut);
        mBoard.writeDataStream(zOut);
        mId.writeDataStream(zOut);
        mTs.writeDataStream(zOut);
        mEpoch.writeDataStream(zOut);
        mRe.writeDataStream(zOut);
        mBlob.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mVersion = MiniNumber.readFromStream(zIn);
        mBoard = MiniData.readFromStream(zIn);
        mId = MiniData.readFromStream(zIn);
        mTs = MiniNumber.readFromStream(zIn);
        mEpoch = MiniNumber.readFromStream(zIn);
        mRe = MiniData.readFromStream(zIn);
        mBlob = MiniData.readFromStream(zIn);
    }

    public byte[] toBytes() {
        return Codec.serialise(this);
    }

    /** Uppercase 0x hex of the board id - the form every map is keyed by. */
    public String boardHex() {
        return mBoard.to0xString();
    }

    public String idHex() {
        return mId.to0xString();
    }

    public boolean isReply() {
        return mRe.getLength() == ID_BYTES;
    }

    /** A verified envelope: the raw bytes to store or forward, and what they say. */
    public static final class Signed {
        public final byte[] raw;
        /** The pseudonym that signed it, X.509 DER. */
        public final byte[] signer;
        public final String signerHex;
        /** Short fingerprint of the signer - how a member is referred to in rosters. */
        public final String signerFp;
        public final BoardPost post;

        Signed(byte[] zRaw, byte[] zSigner, BoardPost zPost) {
            raw = zRaw;
            signer = zSigner;
            signerHex = new MiniData(zSigner).to0xString();
            signerFp = Keys.fingerprint(signerHex);
            post = zPost;
        }
    }

    /** Sign this body with a pseudonym key. The result is what goes on the wire. */
    public Signed sign(KeyPair zPseudonym) {
        byte[] body = toBytes();
        byte[] der = zPseudonym.getPublic().getEncoded();
        byte[] raw = MaximumMessage.sign(body, der, zPseudonym.getPrivate()).toBytes();
        if (raw.length > MAX_BYTES) {
            throw new IllegalArgumentException("post too large: " + raw.length);
        }
        return new Signed(raw, der, this);
    }

    /**
     * Parse and verify an envelope. Every reader does this - a relay before storing, a member
     * before trusting - so a relay can withhold a post but never forge or re-attribute one.
     *
     * @throws IllegalArgumentException when the bytes are not a valid, correctly signed post
     */
    public static Signed verify(byte[] zRaw) {
        if (zRaw == null || zRaw.length == 0 || zRaw.length > MAX_BYTES) {
            throw new IllegalArgumentException("bad size");
        }
        MaximumMessage m;
        BoardPost p;
        try {
            m = MaximumMessage.fromBytes(zRaw);
            p = Codec.deserialise(new BoardPost(), m.mData.getBytes());
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed: " + e.getMessage());
        }
        if (!m.verify()) {
            throw new IllegalArgumentException("bad signature");
        }
        if (p.mVersion.getAsInt() != 1) {
            throw new IllegalArgumentException("unknown version");
        }
        if (p.mBoard.getLength() != BOARD_BYTES || p.mId.getLength() != ID_BYTES) {
            throw new IllegalArgumentException("bad ids");
        }
        if (p.mRe.getLength() != 0 && p.mRe.getLength() != ID_BYTES) {
            throw new IllegalArgumentException("bad reply ref");
        }
        if (p.mEpoch.getAsLong() < 0 || p.mTs.getAsLong() < 0) {
            throw new IllegalArgumentException("bad numbers");
        }
        return new Signed(zRaw, m.mPublicKey.getBytes(), p);
    }
}
