package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.net.Frame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;

/**
 * The asking half of the Phase-B MLS mesh: a transient outbound socket that dials a peer
 * pool relay, sends one {@link DirQuery}, and reads back its {@link DirAnswer}. Relays hold
 * no persistent connections to each other, so this opens, asks, and closes — bounded by
 * tight connect/read budgets so a slow or dead peer costs the miss path very little.
 *
 * Trust does NOT live here: the answer carries the publisher's signed proof, which the
 * caller verifies ({@link com.eurobuddha.maxima.core.directory.MlsService#verifiedAddress}).
 * This class only moves bytes.
 */
final class RelayQueryClient {

    private static final SecureRandom RAND = new SecureRandom();

    private RelayQueryClient() {
    }

    /**
     * Ask one peer relay for a signed entry for {@code zTargetKeyHex}. Returns the peer's
     * present answer, or null if it did not answer, answered "absent", or the nonce did not
     * match (a mismatched or malformed reply is treated as no answer).
     */
    /**
     * Hand one peer relay a signed directory entry (replication). True only if the peer
     * acknowledged that it verified and stored it; a refusal, a non-pool peer, a dead peer or
     * a malformed reply all count as "not replicated there" (the caller tries others).
     */
    static boolean publish(String zHost, int zPort, DirPublish zEntry, int zConnectMs, int zReadMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(zHost, zPort), zConnectMs);
            s.setSoTimeout(zReadMs);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            Frame.write(out, Frame.body(Frame.MSG_DIR_PUBLISH, zEntry));
            for (int i = 0; i < 4; i++) {
                byte[] frame = Frame.readOrSkip(in, 64 * 1024);
                if (frame == null || frame.length < 1) {
                    continue;
                }
                if (Frame.typeOf(frame) != Frame.MSG_PING) {
                    continue;   // the ack rides the classic ack channel (MSG_PING + status byte)
                }
                byte[] payload = new byte[frame.length - 1];
                System.arraycopy(frame, 1, payload, 0, payload.length);
                byte[] status = com.eurobuddha.maxima.core.codec.MiniData.readFromStream(
                        new DataInputStream(new java.io.ByteArrayInputStream(payload))).getBytes();
                return status.length == 1 && (status[0] & 0xFF) == Frame.RESPONSE_OK;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static DirAnswer query(String zHost, int zPort, String zTargetKeyHex,
                           int zConnectMs, int zReadMs) {
        byte[] nonce = new byte[16];
        RAND.nextBytes(nonce);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(zHost, zPort), zConnectMs);
            s.setSoTimeout(zReadMs);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            Frame.write(out, Frame.body(Frame.MSG_DIR_QUERY,
                    new DirQuery(zTargetKeyHex, nonce)));

            // Read a bounded number of frames: the peer answers our DIR_QUERY with a
            // DIR_ANSWER, but tolerate a stray frame or two before it. Bounded read so a
            // chatty or hostile peer cannot hold the miss path open.
            for (int i = 0; i < 4; i++) {
                byte[] frame = Frame.readOrSkip(in, 64 * 1024);
                if (frame == null || frame.length < 1) {
                    continue;
                }
                if (Frame.typeOf(frame) != Frame.MSG_DIR_ANSWER) {
                    continue;
                }
                byte[] payload = new byte[frame.length - 1];
                System.arraycopy(frame, 1, payload, 0, payload.length);
                DirAnswer ans = DirAnswer.fromBytes(payload);
                if (!java.util.Arrays.equals(nonce, ans.getNonce())) {
                    return null;   // not our answer
                }
                return ans.isPresent() ? ans : null;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
