package com.eurobuddha.maxima.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.minima.objects.base.MiniNumber;

/** A running Minima node's loopback RPC as the account wallet: the node signs, we ask. */
public class RpcAccountWalletTest {

    private HttpServer node;
    private final List<String> commands = new ArrayList<>();
    private final List<String> auths = new ArrayList<>();
    private volatile boolean refuseSend;
    private volatile int getaddressCalls;
    private volatile boolean forgetPinned;

    @Before
    public void up() throws Exception {
        node = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        node.createContext("/", ex -> {
            String cmd = URLDecoder.decode(ex.getRequestURI().getRawPath().substring(1), "UTF-8");
            commands.add(cmd);
            auths.add(ex.getRequestHeaders().getFirst("Authorization"));
            String body;
            if (cmd.equals("getaddress")) {
                // a classic node rotates through its default addresses: first call MxAAAA, then others
                String a = getaddressCalls++ == 0 ? "AAAA" : "ROT" + getaddressCalls;
                body = "{\"command\":\"getaddress\",\"status\":true,\"response\":{\"script\":\"RETURN SIGNEDBY(0xPUB1)\","
                        + "\"address\":\"0x" + a + "\",\"miniaddress\":\"Mx" + a + "\"}}";
            } else if (cmd.startsWith("scripts address:")) {
                String a = cmd.substring("scripts address:".length());
                body = a.equals("MxAAAA") && !forgetPinned
                        ? "{\"status\":true,\"response\":{\"script\":\"RETURN SIGNEDBY(0xPUB1)\",\"address\":\"0xAAAA\","
                          + "\"miniaddress\":\"MxAAAA\",\"default\":true,\"publickey\":\"0xPUB1\"}}"
                        : "{\"status\":false,\"error\":\"unknown\"}";
            } else if (cmd.equals("keys")) {
                body = "{\"status\":true,\"response\":{\"keys\":[{\"publickey\":\"0xOTHER\",\"uses\":9},"
                        + "{\"publickey\":\"0xPUB1\",\"uses\":42}]}}";
            } else if (cmd.startsWith("send ")) {
                body = refuseSend ? "{\"status\":false,\"error\":\"Insufficient funds\"}"
                        : "{\"status\":true,\"response\":{\"txpowid\":\"0xTX123\",\"istransaction\":false}}";
            } else {
                body = "{\"status\":true,\"response\":[{\"tokenid\":\"0x00\",\"confirmed\":\"5\"}]}";
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        node.start();
    }

    @After
    public void down() {
        node.stop(0);
    }

    private RpcAccountWallet wallet(String secretFileContents) throws Exception {
        return wallet(secretFileContents, Files.createTempDirectory("parlons-rpcwallet"));
    }

    private RpcAccountWallet wallet(String secretFileContents, Path dir) throws Exception {
        String spec = "rpc:" + node.getAddress().getPort();
        if (secretFileContents != null) {
            Path f = dir.resolve("rpc-secret.txt");
            Files.write(f, secretFileContents.getBytes(StandardCharsets.UTF_8));
            spec += ":" + f;
        }
        return RpcAccountWallet.fromSpec(spec, dir);
    }

    @Test
    public void specParsingAndBasicAuth() throws Exception {
        assertNull(RpcAccountWallet.fromSpec(null, Files.createTempDirectory("x")));
        assertNull(RpcAccountWallet.fromSpec("gateway", Files.createTempDirectory("x")));
        RpcAccountWallet w = wallet("s3cret\n");
        w.open();
        assertEquals("getaddress", commands.get(0));
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("minima:s3cret".getBytes(StandardCharsets.UTF_8)), auths.get(0));
        RpcAccountWallet open = wallet(null);
        open.open();
        assertNull("no secret file: no Authorization header", auths.get(auths.size() - 1));
    }

    @Test
    public void theNodeIsTheWallet() throws Exception {
        RpcAccountWallet w = wallet(null);
        assertFalse(w.isOpen());
        w.open();
        assertTrue(w.isOpen());
        assertEquals("MxAAAA", w.mxAddress());
        assertEquals("0xAAAA", w.hexAddress());
        assertEquals("RETURN SIGNEDBY(0xPUB1)", w.script());
        assertEquals("the default key's uses, matched through the script", 42, w.uses());
        assertFalse(w.canBuildWithoutPublish());
        assertFalse(w.canResync());
        AccountWallet.Payment p = w.build("MxBBBB", new MiniNumber("1.5"));
        assertEquals("0xTX123", p.txid);
        assertEquals("", p.postCmd);
        assertTrue(commands.get(commands.size() - 1), commands.contains("send address:MxBBBB amount:1.5"));
        w.publish(p);   // no-op
        try {
            w.raiseUsesTo(100);
            fail("the node owns its counter");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void theFirstDefaultAddressIsPinnedAcrossOpens() throws Exception {
        Path dir = Files.createTempDirectory("parlons-rpcwallet");
        RpcAccountWallet w = wallet(null, dir);
        w.open();
        assertEquals("MxAAAA", w.mxAddress());
        assertEquals("MxAAAA", new String(Files.readAllBytes(dir.resolve(RpcAccountWallet.ADDRESS_FILE)), StandardCharsets.UTF_8).trim());
        RpcAccountWallet again = wallet(null, dir);
        again.open();
        assertEquals("the pin wins over a rotating getaddress", "MxAAAA", again.mxAddress());
        assertEquals("0xAAAA", again.hexAddress());
        assertTrue(commands.get(commands.size() - 1).startsWith("scripts address:MxAAAA"));
        assertEquals("getaddress asked once only", 1, getaddressCalls);
        // the node no longer owns the pinned address (resynced): a fresh one is taken and pinned
        forgetPinned = true;
        RpcAccountWallet fresh = wallet(null, dir);
        fresh.open();
        assertEquals("MxROT2", fresh.mxAddress());
        assertEquals("MxROT2", new String(Files.readAllBytes(dir.resolve(RpcAccountWallet.ADDRESS_FILE)), StandardCharsets.UTF_8).trim());
    }

    @Test
    public void aNodeRefusalIsASafeRejection() throws Exception {
        RpcAccountWallet w = wallet(null);
        w.open();
        refuseSend = true;
        try {
            w.build("MxBBBB", new MiniNumber("1"));
            fail();
        } catch (AccountWallet.Rejected r) {
            assertTrue(r.getMessage(), r.getMessage().contains("Insufficient funds"));
        }
        try {
            w.build("MxBBBB; send address:MxEVIL amount:9", new MiniNumber("1"));
            fail();
        } catch (AccountWallet.Rejected r) {
            assertTrue(r.getMessage().contains("malformed address"));
        }
        assertFalse("the injected command never reached the node", commands.stream().anyMatch(c -> c.contains("MxEVIL")));
    }

    @Test
    public void commandsAreUrlEncodedWhole() throws Exception {
        RpcAccountWallet w = wallet(null);
        w.open();
        w.cmd("balance megammr:true address:MxAAAA");
        assertEquals("balance megammr:true address:MxAAAA", commands.get(commands.size() - 1));
        Path dir = Files.createTempDirectory("parlons-rpcwallet");
        w.setWatchAddress("0x" + "AB".repeat(32));
        assertEquals("0x" + "AB".repeat(32), w.watchAddress());
    }
}
