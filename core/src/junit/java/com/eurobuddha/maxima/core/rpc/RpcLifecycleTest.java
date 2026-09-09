package com.eurobuddha.maxima.core.rpc;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.net.Frame;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Lifecycle through the actual node owner. Synthetic identity, no sockets or external relays. */
public class RpcLifecycleTest {
    private static final MaximaIdentity ID = MaximaIdentity.fromSeed(new MiniData(new byte[32]));
    private static final String ADDRESS = ID.mxIdentity() + "@127.0.0.1:9501";

    private static MaximaNode node() {
        MaximaNode node = new MaximaNode(ID, "lifecycle-test", 1);
        node.rpc().setAttached((host, port, unit, msgid, readMs) ->
                new MaximaSender.Result(Frame.RESPONSE_OK, msgid, 0));
        return node;
    }

    @Test public void stoppingTheNodeFailsEveryPendingCallOnceEvenIfAHandlerThrows() throws Exception {
        MaximaNode node = node();
        RpcPeer peer = node.rpc();
        AtomicInteger errors = new AtomicInteger();
        try {
            for (int i = 0; i < 3; i++) peer.call(ADDRESS, "waiting", new byte[0], new RpcPeer.ResponseHandler() {
                public void onResponse(byte[] data) { fail("no response was sent"); }
                public void onError(String message) {
                    assertTrue(message.contains("closed"));
                    errors.incrementAndGet();
                    throw new IllegalStateException("a caller's cleanup failed");
                }
            });
            assertEquals(3, peer.pendingCount());
            node.stop();
            assertEquals(3, errors.get());
            assertEquals(0, peer.pendingCount());
            node.stop();
            assertEquals(3, errors.get());
        } finally { node.stop(); }
    }

    @Test public void stoppedNodeRejectsCallsAndDoesNotDispatchNewInboundRequests() throws Exception {
        MaximaNode node = node();
        AtomicInteger handled = new AtomicInteger(), sent = new AtomicInteger();
        node.services().register("touch", request -> { handled.incrementAndGet(); return new byte[0]; });
        try {
            node.stop();
            // Even an embedder retaining a reference cannot revive the closed peer.
            node.rpc().setAttached((host, port, unit, msgid, readMs) -> {
                sent.incrementAndGet(); return new MaximaSender.Result(Frame.RESPONSE_OK, msgid, 0);
            });
            try {
                node.rpc().call(ADDRESS, "touch", new byte[0], new RpcPeer.ResponseHandler() {
                    public void onResponse(byte[] data) { fail(); }
                    public void onError(String message) { fail("rejected call must not register a handler"); }
                });
                fail("call accepted after stop");
            } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("closed")); }
            assertEquals(0, sent.get());
            assertEquals(0, node.rpc().pendingCount());
            assertTrue(node.rpc().onInbound(request(Collections.emptyList())));
            assertEquals(0, handled.get());
        } finally { node.stop(); }
    }

    @Test public void stopInterruptsReplyWorkersAndDiscardsQueuedReplies() throws Exception {
        MaximaNode node = node();
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();
        ExecutorService replies = replyExecutor(node.rpc());
        node.services().register("touch", request -> new byte[0]);
        node.rpc().setAttached((host, port, unit, msgid, readMs) -> {
            sent.incrementAndGet(); started.countDown();
            try { release.await(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            return new MaximaSender.Result(Frame.RESPONSE_OK, msgid, 0);
        });
        try {
            for (int i = 0; i < 3; i++) node.rpc().onInbound(request(Collections.singletonList(ADDRESS)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            node.stop();
            assertTrue("reply executor belongs to the node lifecycle", replies.isShutdown());
            assertTrue(replies.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("only the two in-flight writes may finish", 2, sent.get());
        } finally { release.countDown(); replies.shutdownNow(); node.stop(); }
    }

    private static MaximaMessage request(java.util.List<String> addresses) throws Exception {
        MaximaMessage message = new MaximaMessage();
        message.mApplication = new MiniString(RpcEnvelope.APPLICATION);
        message.mFrom = new MiniData(ID.publicKey());
        message.mData = new MiniData(RpcEnvelope.request(RpcPeer.newCorrelationId(), "touch", addresses, new byte[0]).toBytes());
        return message;
    }

    private static ExecutorService replyExecutor(RpcPeer peer) throws Exception {
        Field field = RpcPeer.class.getDeclaredField("mReplyExec");
        field.setAccessible(true);
        return (ExecutorService) field.get(peer);
    }
}
