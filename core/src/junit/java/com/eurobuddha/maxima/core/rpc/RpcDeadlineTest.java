package com.eurobuddha.maxima.core.rpc;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.Frame;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RpcDeadlineTest {
    private static final MaximaIdentity ID = MaximaIdentity.fromSeed(new MiniData(new byte[32]));

    @Test public void largestTimeoutDoesNotExpireImmediately() throws Exception {
        AtomicInteger expired = new AtomicInteger();
        try (RpcPeer p = new RpcPeer(ID, new ServiceRegistry())) {
            p.setAttached((h, port, unit, id, ms) -> new MaximaSender.Result(Frame.RESPONSE_OK, id, 0));
            p.call(ID.mxIdentity() + "@127.0.0.1:9501", "test", new byte[0], new RpcPeer.ResponseHandler() {
                public void onResponse(byte[] bytes) { fail("no reply supplied"); }
                public void onError(String message) { if (message.startsWith("timeout")) expired.incrementAndGet(); }
            }, Long.MAX_VALUE);
            assertEquals(0, p.expire()); assertEquals(0, expired.get()); assertEquals(1, p.pendingCount());
            p.close(); assertEquals(0, p.pendingCount());
        }
    }

    @Test public void closedPeerDoesNotRetainNewSenderOrAddresses() throws Exception {
        try (RpcPeer p = new RpcPeer(ID, new ServiceRegistry())) {
            p.close();
            p.setAttached((h, port, unit, id, ms) -> { throw new AssertionError("must not send"); });
            p.setMyAddresses(Collections.singletonList("synthetic-address"));
            assertTrue(p.myAddresses().isEmpty());
            Field f = RpcPeer.class.getDeclaredField("mAttached"); f.setAccessible(true);
            assertNull("closed peers cannot retain a new sender capture", f.get(p));
        }
    }

    @Test public void addressSnapshotsCannotMutateThePeer() {
        try (RpcPeer p = new RpcPeer(ID, new ServiceRegistry())) {
            List<String> input = new ArrayList<>(Collections.singletonList("synthetic-address"));
            p.setMyAddresses(input); input.clear();
            List<String> snapshot = p.myAddresses(); snapshot.clear();
            assertEquals(Collections.singletonList("synthetic-address"), p.myAddresses());
        }
    }
}
