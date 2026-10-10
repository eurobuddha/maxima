package com.eurobuddha.maxima.desktoplinks;

import com.eurobuddha.maxima.core.*;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.codec.*;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class MinimaDocsLinkTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static final String KEY = "0x" + "ab".repeat(32), PEER = "0x" + "cd".repeat(32);
    static final String LINE = "MN2.Friend|Mx12345678@host:9001|AQ==|Ag==|Document|r|document|doc-1";
    final AtomicLong now = new AtomicLong(System.currentTimeMillis());
    final Port port = new Port(); MinimaDocsLink link; String token;
    static class Port implements ChatPort {
        List<Contact> contacts = new ArrayList<>(); int sends, adds; String application, sent; boolean fail;
        Port() { Contact c = new Contact(PEER); c.name = "Friend"; c.addresses.add("Mx123@host:9"); contacts.add(c); }
        public String publicKeyHex() { return KEY; } public String name() { return "My account"; }
        public Contact contact(String key) { return contacts.stream().filter(c -> c.publicKey.equalsIgnoreCase(key)).findFirst().orElse(null); }
        public List<Contact> contacts() { return contacts; }
        public MaximaSender.Result sendToContact(Contact c, String app, byte[] bytes) throws Exception { sends++; application = app; sent = new String(bytes, StandardCharsets.UTF_8); if (fail) throw new Exception("sensitive error"); return MaximaSender.Result.of(1); }
        public void introduce(String address, boolean intro) { adds++; }
        public boolean removeContact(String key) { return contacts.removeIf(c -> c.publicKey.equalsIgnoreCase(key)); }
        public List<String> myAddresses() { return Collections.emptyList(); } public void log(String s) { }
    }
    @Before public void up() throws Exception { link = new MinimaDocsLink(port, temp.getRoot().toPath(), "desktop", now::get); link.start(); }
    @After public void down() { link.close(); }
    String code() { String url = link.approve(); return url.substring(url.indexOf("&code=") + 6); }
    JSONObject call(String op, JSONObject input) throws Exception { return call(op, input.toString(), null, null, "POST", 200); }
    JSONObject call(String op, String body, String origin, String host, String method, int expected) throws Exception {
        // Raw socket permits exact Host/Origin overrides (HttpURLConnection strips them).
        try (Socket socket = new Socket("127.0.0.1", link.port())) {
            socket.setSoTimeout(5000); byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = method + " /minimadocs/v1/" + op + " HTTP/1.1\r\nHost: " + (host == null ? "127.0.0.1:" + link.port() : host)
                + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n"
                + (token == null ? "" : "Authorization: Bearer " + token + "\r\n") + (origin == null ? "" : "Origin: " + origin + "\r\n") + "\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().write(bytes);
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response, response.startsWith("HTTP/1.1 " + expected + " "));
            return new JSONObject(response.substring(response.indexOf("\r\n\r\n") + 4));
        }
    }
    void connect() throws Exception { token = call("connect", new JSONObject().put("code", code())).getString("token"); }
    MaximaMessage message(String line) { MaximaMessage m = new MaximaMessage(); m.mFrom = new MiniData(PEER); m.mApplication = new MiniString(MinimaDocsLink.APPLICATION); m.mData = new MiniData(line.getBytes(StandardCharsets.UTF_8)); m.mTimeMilli = new MiniNumber(now.get()); return m; }
    @Test public void linksAreSingleUseAndExpire() throws Exception {
        String code = code(); token = call("connect", new JSONObject().put("code", code)).getString("token");
        assertEquals(64, token.length()); call("connect", "{\"code\":\"" + code + "\"}", null, null, "POST", 403);
        code = code(); now.addAndGet(120001); call("connect", "{\"code\":\"" + code + "\"}", null, null, "POST", 403);
    }
    @Test public void browserHostAndMalformedRequestsFailClosed() throws Exception {
        connect();
        for (String origin : new String[]{"null", "http://127.0.0.1:" + link.port(), "https://evil.test"}) call("contacts", "{}", origin, null, "POST", 403);
        call("contacts", "{}", null, "evil.test:" + link.port(), "POST", 403);
        call("contacts", "{}", null, null, "GET", 405);
        call("contacts", "[1]", null, null, "POST", 400);
        call("contacts", "{} trailing", null, null, "POST", 400);
        call("contacts", "x".repeat(32769), null, null, "POST", 413);
        call("contacts", "{\"command\":\"seed\"}", null, null, "POST", 400);
        call("seed", "{}", null, null, "POST", 404);
        token = "00".repeat(32); call("contacts", "{}", null, null, "POST", 403);
    }
    @Test public void portApprovalsAndInboxSurviveRestartAndRevocation() throws Exception {
        connect(); int bound = link.port(); assertTrue(link.receive(message(LINE)));
        link.close(); link = new MinimaDocsLink(port, temp.getRoot().toPath(), "desktop", now::get); link.start();
        assertEquals(bound, link.port()); assertEquals(1, call("invitations", new JSONObject()).getJSONArray("invitations").length());
        link.revoke(); call("contacts", "{}", null, null, "POST", 403);
        link.close(); link = new MinimaDocsLink(port, temp.getRoot().toPath(), "desktop", now::get); link.start();
        call("contacts", "{}", null, null, "POST", 403);
    }
    @Test public void requestsAreIdempotentAndPayloadBound() throws Exception {
        connect(); JSONObject send = new JSONObject().put("to", PEER).put("line", LINE).put("requestId", UUID.randomUUID().toString());
        assertEquals("sent", call("invite", send).getString("state")); call("invite", send); assertEquals(1, port.sends);
        assertEquals(MinimaDocsLink.APPLICATION, port.application); assertEquals(LINE, port.sent);
        send.put("line", LINE.replace("Document", "Other")); call("invite", send.toString(), null, null, "POST", 409);
    }
    @Test public void uncertainSendNeverSilentlyRepeatsOrExposesError() throws Exception {
        connect(); port.fail = true; JSONObject send = new JSONObject().put("to", PEER).put("line", LINE).put("requestId", UUID.randomUUID().toString());
        assertFalse(call("invite", send.toString(), null, null, "POST", 503).toString().contains("sensitive"));
        port.fail = false; call("invite", send.toString(), null, null, "POST", 409); assertEquals(1, port.sends);
    }
    @Test public void inboxDedupDismissalExpiryAndWireCompatibility() throws Exception {
        connect(); assertTrue(link.receive(message(LINE))); link.receive(message(LINE));
        JSONArray inbox = call("invitations", new JSONObject()).getJSONArray("invitations"); assertEquals(1, inbox.length());
        call("dismiss", new JSONObject().put("id", inbox.getJSONObject(0).getString("id"))); link.receive(message(LINE));
        assertEquals(0, call("invitations", new JSONObject()).getJSONArray("invitations").length());
        String old = "MN1." + Base64.getUrlEncoder().encodeToString("Friend\nMx12345678@host:9001\nAQ==\nAg==".getBytes(StandardCharsets.UTF_8));
        link.receive(message(old)); assertEquals(1, call("invitations", new JSONObject()).getJSONArray("invitations").length());
        now.addAndGet(7L * 24 * 3600_000 + 1); assertEquals(0, call("invitations", new JSONObject()).getJSONArray("invitations").length());
    }
    @Test public void malformedInvitationsAndOtherApplicationsNeverEnterInbox() throws Exception {
        connect();
        for (String bad : new String[]{"bad", "MN2.X||AQ==|Ag==", "MN2.X|Mx123@h:1|!!|Ag==", "x".repeat(4097)}) link.receive(message(bad));
        MaximaMessage other = message(LINE); other.mApplication = new MiniString("maxima_chat_v1"); assertFalse(link.receive(other));
        assertEquals(0, call("invitations", new JSONObject()).getJSONArray("invitations").length());
    }
    @Test public void inboxAndResponsesAreBounded() throws Exception {
        connect(); for (int i=0; i<70; i++) link.receive(message(LINE + i));
        assertEquals(64, call("invitations", new JSONObject()).getJSONArray("invitations").length());
        for (int i=0; i<500; i++) { Contact c = new Contact(PEER); c.name = "x".repeat(1000); port.contacts.add(c); }
        JSONObject r = call("contacts", "{}", null, null, "POST", 413); assertFalse(r.has("contacts"));
    }
    @Test public void diskFailurePropagatesAndRetryIsNotDeduplicatedAway() throws Exception {
        connect(); Path file = Files.list(temp.getRoot().toPath()).filter(p -> p.toString().endsWith(".json")).findFirst().get();
        Path saved = file.resolveSibling("saved"); Files.move(file, saved); Files.createDirectory(file); Files.writeString(file.resolve("block"), "block");
        assertThrows(IllegalStateException.class, () -> link.receive(message(LINE)));
        Files.delete(file.resolve("block")); Files.delete(file); Files.move(saved, file);
        link.receive(message(LINE)); assertEquals(1, call("invitations", new JSONObject()).getJSONArray("invitations").length());
    }
    @Test public void tokensAreHashedAndStateIsPrivate() throws Exception {
        connect(); Path file = Files.list(temp.getRoot().toPath()).filter(p -> p.toString().endsWith(".json")).findFirst().get();
        assertFalse(Files.readString(file).contains(token));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
    @Test public void twoHostsDoNotShareApprovalAndContactsUseOwnerPort() throws Exception {
        connect(); try (MinimaDocsLink other = new MinimaDocsLink(port, temp.getRoot().toPath(), "core")) {
            other.start(); assertNotEquals(other.port(), link.port()); assertEquals(0, other.approvals());
        }
        assertEquals(1, call("contacts", new JSONObject()).getJSONArray("contacts").length());
        call("contact/add", new JSONObject().put("address", "Mx123@host:9")); assertEquals(1, port.adds);
        call("contact/remove", new JSONObject().put("key", PEER)); assertEquals(0, call("contacts", new JSONObject()).getJSONArray("contacts").length());
        call("disconnect", new JSONObject()); call("contacts", "{}", null, null, "POST", 403);
    }
    @Test public void delayedInvitationPassesTheRealNodeFreshnessGateButCommandsDoNot() throws Exception {
        connect();
        MaximaNode node = new MaximaNode(com.eurobuddha.maxima.core.identity.MaximaIdentity.fromPhrase(
            com.eurobuddha.maxima.core.identity.Bip39.generate(24)), "test", 0);
        java.util.concurrent.CountDownLatch received = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        node.setRetainedMessageValidator(MinimaDocsLink::retainedInvitation);
        node.setMessageListener((m, id) -> { count.incrementAndGet(); link.receive(m); received.countDown(); });
        try {
            MaximaMessage m = message(LINE); m.mTimeMilli = new MiniNumber(now.get() - 2L * 24 * 3600_000);
            java.lang.reflect.Constructor<com.eurobuddha.maxima.core.net.HostConnection.Inbound> ctor =
                com.eurobuddha.maxima.core.net.HostConnection.Inbound.class.getDeclaredConstructor(MaximaMessage.class, MiniData.class, boolean.class);
            ctor.setAccessible(true);
            node.handle(ctor.newInstance(m, new MiniData(new byte[]{1}), true));
            assertTrue(received.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, call("invitations", new JSONObject()).getJSONArray("invitations").length());
            m.mApplication = new MiniString("arbitrary-command");
            node.handle(ctor.newInstance(m, new MiniData(new byte[]{2}), true));
            assertEquals(1, count.get());
        } finally { node.stop(); }
    }

}
