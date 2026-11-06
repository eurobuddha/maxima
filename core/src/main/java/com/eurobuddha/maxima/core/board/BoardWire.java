package com.eurobuddha.maxima.core.board;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.Streamable;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The board service's wire vocabulary + client sends - how posts park on and return from a pool
 * relay's {@link BoardShelf}.
 *
 * Two application strings, answered by a relay for messages addressed to it (the probe / MLS /
 * blob-shelf pattern - no new frame types, no new port: it rides the relay port every client is
 * already attached to):
 *
 *   put     payload = one signed envelope ({@link BoardPost#sign}). The relay re-verifies the
 *           envelope's own signature (the message's {@code from} may differ: members re-seed each
 *           other's posts), stores it idempotently by board|id, and acks
 *           OK / FAIL (quota, rate) / TOOBIG / UNKNOWN (not a pool relay, or boards disabled).
 *   since   payload = {@link SinceRequest}: up to {@link #MAX_BOARDS_PER_CALL} (board, cursor)
 *           pairs. Reply: {@link SinceReply} in the ping body - per board the posts after the
 *           cursor, the new cursor, and {@code reset} when the cursor predates what is retained.
 *           Unauthenticated like blob GET: a board id is only ever learned from an invite, and
 *           the bytes are ciphertext.
 *
 * Both travel signed by a PSEUDONYM (the board's for puts, the device's reader key for pulls) on
 * a FRESH socket, never down the attached link the relay already knows as our identity: a relay
 * learns that some key posts to or reads a board, not which MAX# does. (The source IP is the
 * residual correlation, as for every relay-addressed call.)
 */
public final class BoardWire {
    public static final String APP_PUT = "**maxima_board_put**";
    public static final String APP_SINCE = "**maxima_board_since**";

    public static final int MAX_BOARDS_PER_CALL = 32;
    /** Reply budget - under the package ceiling with envelope room; the client pages by cursor. */
    public static final int MAX_REPLY_BYTES = 192 * 1024;

    /** Short leashes, as the blob shelf: a relay is redundancy, a slow one must cost seconds. */
    public static final int CONNECT_TIMEOUT_MS = 8000;
    public static final int READ_TIMEOUT_MS = 8000;

    private BoardWire() {
    }

    public static boolean isBoardApp(String zApplication) {
        return APP_PUT.equals(zApplication) || APP_SINCE.equals(zApplication);
    }

    // ---- since request / reply ----

    /** (board, cursor) pairs. */
    public static final class SinceRequest implements Streamable {
        public final List<MiniData> boards = new ArrayList<>();
        public final List<Long> cursors = new ArrayList<>();

        public SinceRequest add(byte[] zBoard, long zCursor) {
            if (boards.size() >= MAX_BOARDS_PER_CALL) {
                throw new IllegalArgumentException("too many boards");
            }
            boards.add(new MiniData(zBoard));
            cursors.add(zCursor);
            return this;
        }

        public int size() {
            return boards.size();
        }

        @Override
        public void writeDataStream(DataOutputStream zOut) throws IOException {
            MiniNumber.writeToStream(zOut, boards.size());
            for (int i = 0; i < boards.size(); i++) {
                boards.get(i).writeDataStream(zOut);
                new MiniNumber(cursors.get(i)).writeDataStream(zOut);
            }
        }

        @Override
        public void readDataStream(DataInputStream zIn) throws IOException {
            boards.clear();
            cursors.clear();
            int n = MiniNumber.readFromStream(zIn).getAsInt();
            if (n < 0 || n > MAX_BOARDS_PER_CALL) {
                throw new IOException("bad board count " + n);
            }
            for (int i = 0; i < n; i++) {
                MiniData b = MiniData.readFromStream(zIn);
                if (b.getLength() != BoardPost.BOARD_BYTES) {
                    throw new IOException("bad board id");
                }
                boards.add(b);
                cursors.add(MiniNumber.readFromStream(zIn).getAsLong());
            }
        }

        public static SinceRequest fromBytes(byte[] zData) throws IOException {
            return Codec.deserialise(new SinceRequest(), zData);
        }

        public byte[] toBytes() {
            return Codec.serialise(this);
        }
    }

    /** One board's slice of a reply. */
    public static final class Slice {
        public final MiniData board;
        public final long cursor;
        /** The cursor asked for predates what the relay retains: this is the earliest it has. */
        public final boolean reset;
        /** Signed envelopes in sequence order. */
        public final List<byte[]> raws;

        public Slice(MiniData zBoard, long zCursor, boolean zReset, List<byte[]> zRaws) {
            board = zBoard;
            cursor = zCursor;
            reset = zReset;
            raws = zRaws;
        }
    }

    public static final class SinceReply implements Streamable {
        public final List<Slice> slices = new ArrayList<>();

        @Override
        public void writeDataStream(DataOutputStream zOut) throws IOException {
            MiniNumber.writeToStream(zOut, slices.size());
            for (Slice s : slices) {
                s.board.writeDataStream(zOut);
                new MiniNumber(s.cursor).writeDataStream(zOut);
                MiniNumber.writeToStream(zOut, s.reset ? 1 : 0);
                MiniNumber.writeToStream(zOut, s.raws.size());
                for (byte[] r : s.raws) {
                    new MiniData(r).writeDataStream(zOut);
                }
            }
        }

        @Override
        public void readDataStream(DataInputStream zIn) throws IOException {
            slices.clear();
            int n = MiniNumber.readFromStream(zIn).getAsInt();
            if (n < 0 || n > MAX_BOARDS_PER_CALL) {
                throw new IOException("bad slice count " + n);
            }
            for (int i = 0; i < n; i++) {
                MiniData board = MiniData.readFromStream(zIn);
                long cursor = MiniNumber.readFromStream(zIn).getAsLong();
                boolean reset = MiniNumber.readFromStream(zIn).getAsInt() == 1;
                int m = MiniNumber.readFromStream(zIn).getAsInt();
                if (m < 0 || m > MAX_REPLY_BYTES) {
                    throw new IOException("bad post count " + m);
                }
                List<byte[]> raws = new ArrayList<>(m);
                for (int j = 0; j < m; j++) {
                    MiniData r = MiniData.readFromStream(zIn);
                    if (r.getLength() > BoardPost.MAX_BYTES) {
                        throw new IOException("post too large");
                    }
                    raws.add(r.getBytes());
                }
                slices.add(new Slice(board, cursor, reset, raws));
            }
        }

        public static SinceReply fromBytes(byte[] zData) throws IOException {
            return Codec.deserialise(new SinceReply(), zData);
        }

        public byte[] toBytes() {
            return Codec.serialise(this);
        }
    }

    // ---- client sends ----

    /**
     * Publish one signed envelope to a relay, addressed from the pseudonym that signed it.
     * True when the relay accepted it (a duplicate counts: publish is idempotent).
     */
    public static boolean put(MaximaNode zNode, String zRelayAddr, java.security.KeyPair zPseudonym,
                              byte[] zRaw) {
        try {
            MaximaSender.Result r = zNode.sendRawAs(zPseudonym, zRelayAddr, APP_PUT, zRaw,
                    CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, true);
            return r.isOk();
        } catch (Exception e) {
            return false;
        }
    }

    /** Ask a relay for everything after the cursors; null when it did not answer. */
    public static SinceReply since(MaximaNode zNode, String zRelayAddr, java.security.KeyPair zReader,
                                   SinceRequest zReq) {
        try {
            MaximaSender.Result r = zNode.sendRawAs(zReader, zRelayAddr, APP_SINCE, zReq.toBytes(),
                    CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, true);
            if (r.hasPayload()) {
                return SinceReply.fromBytes(r.replyData.getBytes());
            }
            // A bare status: UNKNOWN = not offered here; anything else is a miss too.
            if (r.status == Frame.RESPONSE_UNKNOWN) {
                return null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
