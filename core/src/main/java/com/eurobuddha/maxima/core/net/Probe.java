package com.eurobuddha.maxima.core.net;

import com.eurobuddha.maxima.core.msg.Greeting;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Tier 2 reachability proof: "is my mapped port actually reachable from the
 * outside?"
 *
 * Hairpin NAT makes a phone testing its own port worthless (we measured this on
 * the Pi), so proof must come from a THIRD PARTY. A relay does it: the phone
 * asks its relay to dial it back, and the relay — which sees the phone's real
 * public source IP — attempts a greeting handshake to that IP at the requested
 * port. If a {@link DirectEndpoint} answers, the port is genuinely reachable.
 *
 * The security of the service lives in one rule enforced by the relay, NOT
 * here: the target IP is the SOURCE IP of the asking connection. A caller can
 * only ever prove its OWN reachability, so the service cannot be turned into a
 * port scanner. This class holds the wire constant and the dial mechanism both
 * sides share.
 */
public final class Probe {

    /**
     * The application string a probe request carries. Reserved: the IPC layer
     * refuses it to external apps, like the other transport-owned strings.
     */
    public static final String APPLICATION = "**maxima_probe**";

    /** Ports at or below this are refused - a probe must target a high port. */
    public static final int MIN_PORT = 1024;

    private Probe() {
    }

    /** The request payload is just the port, as text. */
    public static byte[] request(int zPort) {
        return Integer.toString(zPort).getBytes(StandardCharsets.UTF_8);
    }

    /** Parse a request payload back to a port, or -1 if malformed. */
    public static int portOf(byte[] zData) {
        try {
            int p = Integer.parseInt(new String(zData, StandardCharsets.UTF_8).trim());
            return (p > 0 && p <= 65535) ? p : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Dial zHost:zPort and attempt a greeting handshake.
     *
     * Reachable = a TCP connection is accepted AND a greeting frame comes back,
     * which confirms it is a Maxima endpoint answering and not merely an open
     * port on some unrelated service. We send only a greeting and read only a
     * greeting; nothing else crosses, and the socket is closed immediately.
     *
     * @return true if the target answered as a Maxima endpoint
     */
    public static boolean dial(String zHost, int zPort, int zTimeoutMs, String zVersion) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(zHost, zPort), zTimeoutMs);
            s.setSoTimeout(zTimeoutMs);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            out.write(intFrame(Frame.body(Frame.MSG_GREETING,
                    Greeting.commsOnly(zVersion, "", 0))));
            out.flush();

            // Read one frame; a real endpoint greets back. Bounded read - we do
            // not trust the far side just because we dialled it.
            byte[] frame = Frame.readOrSkip(in, 64 * 1024);
            return frame != null && frame.length >= 1
                    && Frame.typeOf(frame) == Frame.MSG_GREETING;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] intFrame(byte[] zBody) {
        byte[] out = new byte[4 + zBody.length];
        out[0] = (byte) (zBody.length >>> 24);
        out[1] = (byte) (zBody.length >>> 16);
        out[2] = (byte) (zBody.length >>> 8);
        out[3] = (byte) zBody.length;
        System.arraycopy(zBody, 0, out, 4, zBody.length);
        return out;
    }
}
