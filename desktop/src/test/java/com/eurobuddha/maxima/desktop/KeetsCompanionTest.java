package com.eurobuddha.maxima.desktop;

import com.eurobuddha.maxima.core.ChatPort;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.store.FileStore;
import com.eurobuddha.maxima.desktop.keets.KeetsCompanionServer;

import org.junit.Test;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Leg B of the desktop keets proof: a KeetChat-dialect event POSTed to the
 * companion server (the shape minimaCore Desktop 0.18.5 fans out) lands in the
 * REAL ChatEngine's conversation. Also covers register(): the token on the
 * wire is read back from the registration file, exactly as the node app does.
 */
public class KeetsCompanionTest {

    private static final class FakePort implements ChatPort {
        public String publicKeyHex() { return "0xABCD"; }
        public String name() { return "test"; }
        public Contact contact(String zKey) { return null; }
        public List<Contact> contacts() { return Collections.emptyList(); }
        public MaximaSender.Result sendToContact(Contact zC, String zApp, byte[] zData) {
            return MaximaSender.Result.of(0);
        }
        public void introduce(String zAddr, boolean zIntro) { }
        public boolean removeContact(String zKey) { return false; }
        public List<String> myAddresses() { return Collections.emptyList(); }
        public void log(String zLine) { }
    }

    @Test
    public void postedEventReachesTheEngine() throws Exception {
        File tmp = Files.createTempDirectory("keets-companion-test").toFile();
        ChatEngine chat = new ChatEngine(new FakePort());
        chat.setStore(new FileStore(new File(tmp, "chat")));

        KeetsCompanionServer srv = new KeetsCompanionServer(chat);
        String url = srv.start();
        srv.register(tmp.getAbsolutePath(), url);

        // the node app reads the token from the registration file - do the same
        String reg = new String(Files.readAllBytes(new File(tmp, "keets-companions.json").toPath()),
                StandardCharsets.UTF_8);
        String token = reg.replaceAll(".*\"token\":\"([0-9a-f]+)\".*", "$1");
        assertTrue("token in registration", token.matches("[0-9a-f]{48}"));

        String from = "0x5bd185d2148a8620de56d0fbe5588f260b839f0a71104b373b155c837326dd16";
        String wire = "{\"application\":\"maxima_chat_v1\",\"id\":\"kc-1-t\",\"body\":\"leg B proof\","
                + "\"name\":\"Rig\",\"ts\":1791940000000}";
        String event = "{\"event\":\"KEETS\",\"data\":{\"id\":\"kc-1-t\",\"from\":\"" + from + "\","
                + "\"ts\":1791940000000,\"payload\":\""
                + Base64.getEncoder().encodeToString(wire.getBytes(StandardCharsets.UTF_8))
                + "\",\"message\":\"leg B proof\",\"name\":\"Rig\",\"ref\":\"\",\"state\":\"\",\"via\":\"direct\"}}";

        // wrong token refused
        assertEquals(403, post(url, "nope", event));
        // right token accepted
        assertEquals(204, post(url, token, event));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<ChatEngine.Entry> conv = chat.conversation(from);
            if (conv.stream().anyMatch(e -> "leg B proof".equals(e.body))) {
                srv.stop();
                return;
            }
            Thread.sleep(200);
        }
        srv.stop();
        throw new AssertionError("message never reached the engine: " + chat.conversation(from));
    }

    private static int post(String zUrl, String zToken, String zBody) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(zUrl).openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("x-keets-token", zToken);
        con.setDoOutput(true);
        con.getOutputStream().write(zBody.getBytes(StandardCharsets.UTF_8));
        int code = con.getResponseCode();
        con.disconnect();
        return code;
    }
}
