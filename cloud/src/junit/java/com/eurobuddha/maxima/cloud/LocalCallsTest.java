package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.ChatMessage;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Actual authenticated call handlers with synthetic identities; transport executor held off-network. */
public class LocalCallsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private MaximaNode node;
    private ParlonsControl control;
    private DevicePairing pairing;
    private final byte[] local = {1,2,3,4};
    private final byte[] phone = {5,6,7,8};
    private final List<JSONObject> pushed = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private ServiceRegistry registry;
    @Before public void up() throws Exception {
        pairing = new DevicePairing(temp.getRoot().toPath());
        pairing.authorizeLocal(local,"desktop",true); pairing.authorizeLocal(phone,"other",true);
        // The other authorized key is a paired remote device, not another local browser.
        pairing.device(new MiniData(phone).to0xString()).local = false;
        node = new MaximaNode(MaximaIdentity.fromSeed(new MiniData(new byte[32])),"call-test",1);
        node.storeContact(new Contact("0x1234")); node.storeContact(new Contact("0x5678"));
        control = new ParlonsControl(node,null,pairing,null);
        registry = new ServiceRegistry(); control.registerOn(registry); control.setLocalSink(pushed::add);
        Field f=ParlonsControl.class.getDeclaredField("mCallExec"); f.setAccessible(true);
        CountDownLatch started=new CountDownLatch(1);
        ((ExecutorService)f.get(control)).execute(()->{started.countDown();try{release.await();}catch(InterruptedException ignored){}});
        assertTrue(started.await(5,TimeUnit.SECONDS));
    }
    @After public void down() { if(control!=null)control.close();release.countDown();if(node!=null)node.stop(); }
    private JSONObject call(byte[] device,String client,String peer,String kind) throws Exception {
        JSONObject in=new JSONObject();in.put("peer",peer);in.put("id","synthetic-call");in.put("kind",kind);in.put("localClient",client);in.put("payload","");
        return (JSONObject)new JSONParser().parse(new String(registry.dispatchLocal(new ServiceRegistry.Request(
            ParlonsControl.M_CALL_SIGNAL,in.toString().getBytes(StandardCharsets.UTF_8),device,Collections.emptyList())),StandardCharsets.UTF_8));
    }
    @Test public void firstBrowserAnswerWinsAndLoserCannotHangUpWinner() throws Exception {
        assertEquals(true,call(local,"tab-a","0x1234","answer").get("ok"));
        assertEquals("tab-a",pushed.get(0).get("exceptClient"));
        assertEquals(false,call(local,"tab-b","0x1234","answer").get("ok"));
        assertEquals(false,call(local,"tab-b","0x1234","bye").get("ok"));
        assertEquals(false,call(phone,"forged","0x1234","bye").get("ok"));
        assertEquals(true,call(local,"tab-a","0x1234","bye").get("ok"));
    }
    @Test public void phoneAnswerStopsLocalAnswerAndCannotForgeLocalExclusion() throws Exception {
        assertEquals(true,call(phone,"tab-a","0x1234","answer").get("ok"));
        assertFalse(pushed.get(0).containsKey("exceptClient"));
        assertEquals(false,call(local,"tab-a","0x1234","answer").get("ok"));
    }
    @Test public void declineWinsAtomicallyAndSeparatePeersDoNotShareClaims() throws Exception {
        assertEquals(true,call(local,"tab-a","0x1234","bye").get("ok"));
        assertEquals(false,call(local,"tab-b","0x1234","answer").get("ok"));
        assertEquals(true,call(local,"tab-b","0x5678","answer").get("ok"));
    }
    @Test public void unknownContactCannotClaimOrRingAndLocalPresenceCounts() throws Exception {
        assertEquals(false,call(local,"tab-a","0x9999","answer").get("ok"));assertTrue(pushed.isEmpty());
        Field f=ParlonsControl.class.getDeclaredField("mLocalLive");f.setAccessible(true);
        java.lang.reflect.Method live=ParlonsControl.class.getDeclaredMethod("anyLive");live.setAccessible(true);
        assertEquals(false,live.invoke(control));control.setLocalLive(()->true);assertEquals(true,live.invoke(control));
    }
}
