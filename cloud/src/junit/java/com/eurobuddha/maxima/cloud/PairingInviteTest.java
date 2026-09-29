package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.rpc.RpcEnvelope;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.*;

/** Exercise the real owner RPC response without a network or access to server files on a client. */
public class PairingInviteTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final byte[] owner = {1, 2, 3}, newcomer = {4, 5, 6}, stranger = {7, 8, 9};
    private DevicePairing pairing;
    private MaximaNode node;
    private ParlonsControl control;
    private ServiceRegistry registry;

    @Before public void setUp() throws Exception {
        pairing = new DevicePairing(temp.getRoot().toPath());
        assertEquals(DevicePairing.Result.AUTHORIZED,
                pairing.requestPair(owner, "paired phone", pairing.newBootstrapCode()));
        node = new MaximaNode(MaximaIdentity.fromSeed(new MiniData(new byte[32])), "invite-test", 1);
        node.setStaticMls("MxTEST@127.0.0.1:9501");
        control = new ParlonsControl(node, null, pairing, null);
        registry = new ServiceRegistry();
        control.registerOn(registry);
    }

    @After public void tearDown() {
        if (control != null) control.close();
        if (node != null) node.stop();
    }

    @Test public void pairedDeviceReceivesPersistedCodeAndUsableInvite() throws Exception {
        JSONObject result = mint();
        String code = (String) result.get("code");
        assertTrue(code.matches("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){2}"));
        assertEquals(code, Files.readString(pairing.codeFile()).trim());
        assertEquals(node.permanentAddress() + "?code=" + code, result.get("invite"));
        assertTrue("older iOS and Portal clients display note", ((String) result.get("note")).contains(code));
        assertTrue(((String) result.get("note")).contains((String) result.get("invite")));
        assertEquals(DevicePairing.Result.AUTHORIZED, pairing.requestPair(newcomer, "new phone", code));
        assertEquals(DevicePairing.Result.PENDING, pairing.requestPair(stranger, "another phone", code));
        assertFalse(pairing.hasBootstrapCode());
    }

    @Test public void nextMintReplacesThePreviouslyReturnedCode() throws Exception {
        String oldCode = (String) mint().get("code");
        JSONObject next = mint();
        String code = (String) next.get("code");
        assertNotEquals(oldCode, code);
        assertEquals(DevicePairing.Result.PENDING, pairing.requestPair(newcomer, "new phone", oldCode));
        assertEquals(DevicePairing.Result.AUTHORIZED, pairing.requestPair(newcomer, "new phone", code));
    }

    @Test public void unpairedPendingAndRevokedDevicesCannotMintOrReadACode() throws Exception {
        String code = (String) mint().get("code");
        assertTrue(dispatch(stranger).isError());
        pairing.requestPair(stranger, "pending phone", "");
        assertTrue(dispatch(stranger).isError());
        assertTrue(pairing.revoke(owner, new MiniData(owner).to0xString()));
        RpcEnvelope denied = dispatch(owner);
        assertTrue(denied.isError());
        assertFalse(new String(denied.getPayload(), StandardCharsets.UTF_8).contains(code));
        assertEquals("denied requests must not rotate the code", code, Files.readString(pairing.codeFile()).trim());
    }

    @Test public void codeIsReturnedEvenBeforeThePermanentAddressIsReady() throws Exception {
        node.setStaticMls("");
        JSONObject result = mint();
        assertEquals("", result.get("invite"));
        String code = (String) result.get("code");
        assertTrue(((String) result.get("note")).contains(code));
        assertEquals(DevicePairing.Result.AUTHORIZED, pairing.requestPair(newcomer, "new phone", code));
    }

    @Test public void failedPersistenceReturnsAnErrorInsteadOfAnUnusableInvite() throws Exception {
        Files.createDirectory(pairing.codeFile());
        Files.write(pairing.codeFile().resolve("blocker"), new byte[]{1});
        RpcEnvelope result = dispatch(owner);
        assertTrue(result.isError());
        assertTrue(new String(result.getPayload(), StandardCharsets.UTF_8).contains("could not write pairing code"));
    }

    @Test public void combinedInviteParsesForAndroidLikeIos() {
        assertArrayEquals(new String[]{"MAX#key#MxHOST@1.2.3.4:8001", "ABCD-EFGH-JKLM"},
                AccountFiles.parseInvite("  MAX#key#MxHOST@1.2.3.4:8001?code=ABCD-EFGH-JKLM\n"));
        assertArrayEquals(new String[]{"MAX#key#host", ""}, AccountFiles.parseInvite("MAX#key#host"));
        assertArrayEquals(new String[]{"", ""}, AccountFiles.parseInvite(null));
    }

    @Test public void importRpcRejectsUnpairedAndUnsupportedHosts() throws Exception {
        RpcEnvelope denied = registry.dispatch("import", new ServiceRegistry.Request(ParlonsControl.M_IDENTITY_IMPORT,
                "{}".getBytes(StandardCharsets.UTF_8), stranger, Collections.emptyList()));
        assertTrue(denied.isError());
        RpcEnvelope unsupported = registry.dispatch("import", new ServiceRegistry.Request(ParlonsControl.M_IDENTITY_IMPORT,
                "{}".getBytes(StandardCharsets.UTF_8), owner, Collections.emptyList()));
        JSONObject reply = (JSONObject) new JSONParser().parse(new String(unsupported.getPayload(), StandardCharsets.UTF_8));
        assertEquals(Boolean.FALSE, reply.get("ok"));
    }

    private JSONObject mint() throws Exception {
        RpcEnvelope reply = dispatch(owner);
        assertTrue(reply.isResponse());
        JSONObject result = (JSONObject) new JSONParser().parse(new String(reply.getPayload(), StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE, result.get("ok"));
        return result;
    }

    private RpcEnvelope dispatch(byte[] caller) {
        return registry.dispatch("invite-request", new ServiceRegistry.Request(ParlonsControl.M_PAIR_NEWCODE,
                new byte[0], caller, Collections.emptyList()));
    }
}
