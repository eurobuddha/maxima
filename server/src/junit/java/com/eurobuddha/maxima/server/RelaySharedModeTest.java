package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.Test;

/**
 * One public port: in shared mode the relay binds nothing and is handed connections another
 * listener accepted - together with the greeting frame that listener already consumed. The
 * relay must answer exactly as if it had read that greeting itself (its greeting, then the MLS
 * offer), and every greeting our side sends must carry the "parlons" key the host keys on.
 */
public class RelaySharedModeTest {

    private static final String PROTO = "1.0.48";

    @Test
    public void ourGreetingsCarryTheParlonsMarker() {
        Greeting g = Greeting.commsOnly(PROTO, "1.2.3.4", 9501);
        assertTrue(g.getExtraData(), g.getExtraData().contains("\"parlons\":\"1\""));
        assertEquals("1.2.3.4", Greeting.hostOf(g.getExtraData()));
        assertEquals(9501, Greeting.portOf(g.getExtraData()));
    }

    @Test
    public void aHandedOverConnectionIsServedFromItsGreeting() throws Exception {
        RelayServer relay = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), 12001, PROTO, true);
        relay.setShared(true);
        relay.setPublicHost("5.6.7.8");
        relay.start();
        assertTrue("shared mode reports itself alive without an accept thread", relay.acceptAlive());
        // the "host's listener": a plain server socket that reads the client's greeting frame itself
        try (ServerSocket host = new ServerSocket(0)) {
            Socket client = new Socket("127.0.0.1", host.getLocalPort());
            Socket accepted = host.accept();
            DataOutputStream cout = new DataOutputStream(client.getOutputStream());
            Frame.write(cout, Frame.body(Frame.MSG_GREETING, Greeting.commsOnly(PROTO, "", 0)));
            byte[] greetingFrame = Frame.readOrSkip(new DataInputStream(accepted.getInputStream()), 64 * 1024);
            assertNotNull(greetingFrame);
            assertEquals(Frame.MSG_GREETING, Frame.typeOf(greetingFrame));
            // hand it over, greeting included, no leftover
            relay.admit(accepted, greetingFrame, new byte[0]);
            DataInputStream cin = new DataInputStream(client.getInputStream());
            client.setSoTimeout(5000);
            byte[] reply = Frame.readOrSkip(cin, 64 * 1024);
            assertEquals("the relay answers the handed-over greeting with its own", Frame.MSG_GREETING, Frame.typeOf(reply));
            Greeting theirs = Greeting.fromBytes(java.util.Arrays.copyOfRange(reply, 1, reply.length));
            assertEquals("named by the shared P2P port", 12001, Greeting.portOf(theirs.getExtraData()));
            assertEquals("5.6.7.8", Greeting.hostOf(theirs.getExtraData()));
            byte[] offer = Frame.readOrSkip(cin, 64 * 1024);
            assertEquals("then the directory offer", Frame.MSG_MAXIMA_CTRL, Frame.typeOf(offer));
            client.close();
        } finally {
            relay.stop();
        }
    }
}
