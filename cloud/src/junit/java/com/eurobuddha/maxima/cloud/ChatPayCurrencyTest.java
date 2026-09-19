package com.eurobuddha.maxima.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.ChatMessage;
import com.eurobuddha.maxima.core.chat.ChatPay;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;
import com.eurobuddha.maxima.core.store.FileStore;
import com.eurobuddha.maxima.core.util.SerialLanes;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

/**
 * The in-chat payment currency, at the control-channel boundary: which token id a device may ask
 * for, what the node hands its wallet, and how the bubble is labelled. Real handlers; the wallet is
 * a stub that records the token it was asked to send.
 */
public class ChatPayCurrencyTest {

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private final byte[] device = new byte[]{1, 2, 3, 4};
    private final AtomicReference<String> askedToken = new AtomicReference<>("");
    private final AtomicReference<String> askedAmount = new AtomicReference<>("");
    private MaximaNode node;
    private ChatEngine chat;
    private ServiceRegistry registry;
    private SerialLanes sends;
    private String peer;

    @Before public void setUp() throws Exception {
        DevicePairing pairing = new DevicePairing(temp.newFolder("pairing").toPath());
        assertTrue(pairing.authorizeLocal(device, "test", true));
        node = new MaximaNode(MaximaIdentity.fromSeed(new MiniData(new byte[32])), "pay-test", 1);

        // A contact who has shared a wallet address - both are preconditions of M_PAY.
        peer = "0x" + "EF".repeat(32);
        Contact c = new Contact(peer);
        c.name = "Payee";
        node.storeContact(c);
        chat = new ChatEngine(node);
        chat.setStore(new FileStore(temp.newFolder("chat")));
        chat.onInbound(inbound(peer, node.publicKeyHex(),
                ChatMessage.address("MxPAYME999").encode()));
        assertEquals("MxPAYME999", chat.walletAddress(peer));

        AccountWallet wallet = (AccountWallet) Proxy.newProxyInstance(
                AccountWallet.class.getClassLoader(), new Class<?>[]{AccountWallet.class},
                (proxy, called, args) -> {
                    switch (called.getName()) {
                        case "canBuildWithoutPublish": return false;
                        case "build":
                            askedAmount.set(String.valueOf(args[1]));
                            // 2-arg build would mean the currency was silently dropped.
                            askedToken.set(args.length > 2 ? String.valueOf(args[2]) : "DROPPED");
                            return new AccountWallet.Payment("0xTESTTX", "", "");
                        case "publish": return null;
                        default: throw new AssertionError("unexpected wallet call: " + called.getName());
                    }
                });
        ParlonsControl control = new ParlonsControl(node, chat, pairing, wallet);
        control.setPaySource(new ParlonsControl.PaySource() {
            public boolean ready() { return true; }
            public String walletError() { return ""; }
            public String myWalletAddress() { return ""; }
            public int uses() { return 0; }
            public void raiseUsesTo(int to) { throw new AssertionError("no counters here"); }
            public String walletScript() { return ""; }
            public String walletHex() { return ""; }
        });
        registry = new ServiceRegistry();
        control.registerOn(registry);
        Field f = ParlonsControl.class.getDeclaredField("mSendExec");
        f.setAccessible(true);
        sends = (SerialLanes) f.get(control);
    }

    @After public void tearDown() {
        if (sends != null) sends.shutdownNow();
        if (node != null) node.stop();
    }

    /** No tokenid at all - an older client - still pays Minima, exactly as before. */
    @Test public void noTokenidMeansMinima() throws Exception {
        JSONObject out = pay("5", null, "older-client");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals(ChatPay.TOKENID_MINIMA, out.get("tokenid"));
        drain();
        assertEquals(ChatPay.TOKENID_MINIMA, askedToken.get());
        assertEquals("5", askedAmount.get());
        assertEquals(ChatPay.NAME_MINIMA, ChatPay.tokenName(lastBody()));
        assertEquals(ChatPay.TOKENID_MINIMA, ChatPay.tokenId(lastBody()));
    }

    /** MxUSD reaches the wallet as MxUSD, and the bubble says so. */
    @Test public void mxusdIsPassedToTheWalletAndLabelled() throws Exception {
        JSONObject out = pay("25", ChatPay.TOKENID_MXUSD, "mxusd-pay");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("the reply echoes the currency the node understood",
                ChatPay.TOKENID_MXUSD, out.get("tokenid"));
        drain();
        assertEquals(ChatPay.TOKENID_MXUSD, askedToken.get());
        assertEquals("the DISPLAYED amount goes to the wallet - the node scales it", "25",
                askedAmount.get());
        assertEquals(ChatPay.NAME_MXUSD, ChatPay.tokenName(lastBody()));
        assertEquals(ChatPay.TOKENID_MXUSD, ChatPay.tokenId(lastBody()));
    }

    /** Any other token is refused outright - never quietly downgraded to Minima. */
    @Test public void anUnknownTokenIsRefusedNotDowngraded() throws Exception {
        String other = "0x" + "AB".repeat(32);
        JSONObject out = pay("25", other, "other-token");
        assertEquals(Boolean.FALSE, out.get("ok"));
        assertTrue(String.valueOf(out.get("error")).contains(other));   // full id, not truncated
        drain();
        assertEquals("the wallet was never asked to send anything", "", askedToken.get());
        assertTrue("no bubble for a refused payment", chat.conversation(peer).isEmpty());
    }

    /** A tokenid that would rewrite the node's own command string is refused at the boundary. */
    @Test public void anInjectingTokenidIsRefused() throws Exception {
        JSONObject out = pay("25", ChatPay.TOKENID_MXUSD + " address:0xEVIL", "inject");
        assertEquals(Boolean.FALSE, out.get("ok"));
        drain();
        assertEquals("", askedToken.get());
    }

    /** The capability flag a client checks before offering MxUSD against an older node. */
    @Test public void pingAdvertisesMxusdSupport() throws Exception {
        JSONObject out = call(ParlonsControl.M_PING, new JSONObject());
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals(Boolean.TRUE, out.get("mxusd"));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private JSONObject pay(String zAmount, String zTokenId, String zPid) throws Exception {
        JSONObject in = new JSONObject();
        in.put("peer", peer);
        in.put("amount", zAmount);
        in.put("memo", "");
        in.put("pid", zPid);
        if (zTokenId != null) {
            in.put("tokenid", zTokenId);
        }
        return call(ParlonsControl.M_PAY, in);
    }

    private JSONObject call(String zMethod, JSONObject zIn) throws Exception {
        byte[] response = registry.dispatchLocal(new ServiceRegistry.Request(zMethod,
                zIn.toString().getBytes(StandardCharsets.UTF_8), device, Collections.emptyList()));
        return (JSONObject) new JSONParser().parse(new String(response, StandardCharsets.UTF_8));
    }

    private String lastBody() {
        List<ChatEngine.Entry> conv = chat.conversation(peer);
        assertFalse("a payment bubble was recorded", conv.isEmpty());
        return conv.get(conv.size() - 1).body;
    }

    /** A signed-and-verified inbound message, minus the transport (mirrors core's ChatTest). */
    private static com.eurobuddha.maxima.core.msg.MaximaMessage inbound(
            String zFrom, String zTo, String zBody) {
        com.eurobuddha.maxima.core.msg.MaximaMessage m =
                new com.eurobuddha.maxima.core.msg.MaximaMessage();
        m.mRandom = new MiniData(
                com.eurobuddha.maxima.core.crypto.MaximaCrypto.randomBytes(32));
        m.mFrom = new MiniData(zFrom);
        m.mTo = new MiniData(zTo);
        m.mTimeMilli = new com.eurobuddha.maxima.core.codec.MiniNumber(
                System.currentTimeMillis());
        m.mApplication = new com.eurobuddha.maxima.core.codec.MiniString(
                ChatMessage.APPLICATION);
        m.mData = new MiniData(zBody.getBytes(StandardCharsets.UTF_8));
        return m;
    }

    private void drain() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        sends.execute("wallet", done::countDown);
        assertTrue("wallet lane drained", done.await(5, TimeUnit.SECONDS));
    }
}
