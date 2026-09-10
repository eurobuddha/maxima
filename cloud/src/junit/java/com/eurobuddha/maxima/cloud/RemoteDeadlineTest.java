package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.net.Frame;
import com.eurobuddha.maxima.core.rpc.RpcEnvelope;
import com.eurobuddha.maxima.core.rpc.RpcPeer;
import org.minima.utils.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class RemoteDeadlineTest {
    @Test public void largestTimeoutStillWaitsForTheAccountReply() throws Exception {
        MaximaIdentity id = MaximaIdentity.fromSeed(new MiniData(new byte[32]));
        ParlonsRemote remote = new ParlonsRemote(id);
        Field live = ParlonsRemote.class.getDeclaredField("mCloudLive"); live.setAccessible(true);
        live.set(remote, id.mxIdentity() + "@127.0.0.1:9501");
        Method call = ParlonsRemote.class.getDeclaredMethod("callOnce", String.class, JSONObject.class, long.class);
        call.setAccessible(true);
        CountDownLatch sent = new CountDownLatch(1);
        remote.node().rpc().setAttached((h, port, unit, msgid, ms) -> {
            sent.countDown(); return new MaximaSender.Result(Frame.RESPONSE_OK, msgid, 0);
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<JSONObject> result = worker.submit(() -> (JSONObject) call.invoke(remote, "test", new JSONObject(), Long.MAX_VALUE));
        try {
            assertTrue(sent.await(3, TimeUnit.SECONDS));
            try { result.get(100, TimeUnit.MILLISECONDS); fail("reply not supplied yet"); }
            catch (TimeoutException expected) { /* the positive wait must remain pending */ }
            Field pending = RpcPeer.class.getDeclaredField("mPending"); pending.setAccessible(true);
            Map<?, ?> calls = (Map<?, ?>) pending.get(remote.node().rpc());
            String correlation = (String) calls.keySet().iterator().next();
            MaximaMessage reply = new MaximaMessage();
            reply.mApplication = new MiniString(RpcEnvelope.APPLICATION);
            reply.mData = new MiniData(RpcEnvelope.response(correlation, "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toBytes());
            remote.node().rpc().onInbound(reply);
            assertEquals(Boolean.TRUE, result.get(3, TimeUnit.SECONDS).get("ok"));
            assertEquals(0, remote.node().rpc().pendingCount());
        } finally {
            result.cancel(true); remote.close(); worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
