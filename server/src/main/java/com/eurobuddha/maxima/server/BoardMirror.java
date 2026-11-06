package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.board.BoardShelf;
import com.eurobuddha.maxima.core.board.BoardWire;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.identity.MxAddress;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pulling half of board mirroring between pool relays. A relay asks a peer, as any member
 * would, "{@code **maxima_board_since**} these boards from these cursors" and shelves what comes
 * back; the shelf verifies every envelope's signature, so a peer can withhold but never forge.
 *
 * The peer is addressed like a relay a client attaches to: its routing key is learned from the
 * {@code MSG_MAXIMA_CTRL} / TYPE_MLS it sends after a greeting, then cached per host:port.
 * Cursors are per peer and per board, in memory only: a restart costs one bounded re-pull per
 * board (the reply is capped at {@link BoardWire#MAX_REPLY_BYTES} a call, duplicates are free).
 */
final class BoardMirror {

    /** Boards asked about per call (the wire's cap). */
    static final int BOARDS_PER_CALL = BoardWire.MAX_BOARDS_PER_CALL;
    /** Calls to one peer per pull: twice a minute this is 48/min, under its 120/min per-source budget. */
    static final int CALLS_PER_PULL = 24;
    static final int CONNECT_MS = 4000;
    static final int READ_MS = 8000;
    /** How long a learned peer address is trusted before the greeting is redone. */
    static final long ADDRESS_TTL_MS = 60 * 60 * 1000L;

    private final MaximaIdentity mIdentity;
    private final BoardShelf mShelf;
    private final String mVersion;
    /** "host:port|0xboard" to the newest cursor that peer gave us. */
    private final Map<String, Long> mCursors = new ConcurrentHashMap<>();
    /** host:port to {Mx…@host:port, learnedAt}. */
    private final Map<String, Object[]> mAddresses = new ConcurrentHashMap<>();
    /** Round-robin start into the board list, so a relay with more boards than one pull covers
     *  still gets to every board within a few ticks. */
    private int mOffset;

    BoardMirror(MaximaIdentity zIdentity, BoardShelf zShelf, String zVersion) {
        mIdentity = zIdentity;
        mShelf = zShelf;
        mVersion = zVersion;
    }

    /** Pull from one peer. Returns the number of posts newly shelved (0 on any failure). */
    int pull(String zHostPort) {
        List<byte[]> boards = mShelf.boards();
        if (boards.isEmpty()) {
            return 0;
        }
        int c = zHostPort.lastIndexOf(':');
        if (c <= 0) {
            return 0;
        }
        String host = zHostPort.substring(0, c);
        int port;
        try {
            port = Integer.parseInt(zHostPort.substring(c + 1).trim());
        } catch (Exception e) {
            return 0;
        }
        String addr = address(host, port);
        if (addr == null) {
            return 0;
        }
        byte[] routing;
        try {
            routing = MxAddress.convert(addr.substring(0, addr.indexOf('@'))).getBytes();
        } catch (Exception e) {
            mAddresses.remove(zHostPort);
            return 0;
        }
        int start = Math.floorMod(mOffset, boards.size());
        mOffset = start + Math.min(boards.size(), CALLS_PER_PULL * BOARDS_PER_CALL);
        int stored = 0;
        for (int call = 0; call < CALLS_PER_PULL; call++) {
            int from = start + call * BOARDS_PER_CALL;
            if (from >= boards.size()) {
                break;
            }
            BoardWire.SinceRequest req = new BoardWire.SinceRequest();
            for (int i = from; i < Math.min(boards.size(), from + BOARDS_PER_CALL); i++) {
                byte[] b = boards.get(i);
                Long cur = mCursors.get(zHostPort + "|" + new MiniData(b).to0xString());
                req.add(b, cur == null ? 0L : cur);
            }
            BoardWire.SinceReply reply;
            try {
                MaximaSender.Built built = MaximaSender.build(
                        mIdentity.keyPair().getPublic().getEncoded(), mIdentity.keyPair().getPrivate(),
                        routing, BoardWire.APP_SINCE, req.toBytes(), System.currentTimeMillis());
                MaximaSender.Result r = MaximaSender.send(host, port, built.unit, built.msgid,
                        CONNECT_MS, READ_MS, null);
                if (!r.hasPayload()) {
                    if (r.status == Frame.RESPONSE_UNKNOWN) {
                        mAddresses.remove(zHostPort);   // no board shelf there: ask again after a re-greet
                    }
                    return stored;
                }
                reply = BoardWire.SinceReply.fromBytes(r.replyData.getBytes());
            } catch (Exception e) {
                return stored;
            }
            for (BoardWire.Slice slice : reply.slices) {
                for (byte[] raw : slice.raws) {
                    if (mShelf.put(raw) == BoardShelf.Result.STORED) {
                        stored++;
                    }
                }
                mCursors.put(zHostPort + "|" + slice.board.to0xString(), slice.cursor);
            }
        }
        return stored;
    }

    /** The peer's {@code Mx…@host:port}, learned from its greeting reply and cached. */
    private String address(String zHost, int zPort) {
        String hp = zHost + ":" + zPort;
        Object[] known = mAddresses.get(hp);
        if (known != null && System.currentTimeMillis() - (Long) known[1] < ADDRESS_TTL_MS) {
            return (String) known[0];
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(zHost, zPort), CONNECT_MS);
            s.setSoTimeout(READ_MS);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            Frame.write(out, Frame.body(Frame.MSG_GREETING, Greeting.commsOnly(mVersion, "", 0)));
            for (int i = 0; i < 6; i++) {
                byte[] frame = Frame.readOrSkip(in, MaximaCTRLMessage.MAX_FRAME_SIZE);
                if (frame == null || frame.length < 1 || Frame.typeOf(frame) != Frame.MSG_MAXIMA_CTRL) {
                    continue;
                }
                byte[] payload = new byte[frame.length - 1];
                System.arraycopy(frame, 1, payload, 0, payload.length);
                MaximaCTRLMessage ctrl = MaximaCTRLMessage.fromBytes(payload);
                if (ctrl.getType().getAsInt() != MaximaCTRLMessage.TYPE_MLS) {
                    continue;
                }
                String addr = MaximaCTRLMessage.mlsAddressFrom(ctrl, hp);
                if (addr == null || addr.indexOf('@') <= 0) {
                    return null;
                }
                mAddresses.put(hp, new Object[] {addr, System.currentTimeMillis()});
                return addr;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** For tests and diagnostics: every cursor, "host:port|0xboard" to cursor. */
    Map<String, Long> cursors() {
        return new java.util.HashMap<>(mCursors);
    }
}
