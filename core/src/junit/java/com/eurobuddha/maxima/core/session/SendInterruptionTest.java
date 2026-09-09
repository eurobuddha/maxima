package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.Frame;
import com.eurobuddha.maxima.core.net.HostConnection;
import org.junit.Test;
import java.io.DataOutputStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class SendInterruptionTest {
    private static final MaximaIdentity ID = HostPoolShedTest.identity(67);
    private static MaximaSender.Built message() throws Exception {
        return MaximaSender.build(ID.publicKey(), ID.keyPair().getPrivate(), ID.publicKey(),
                "test", new byte[]{1}, System.currentTimeMillis());
    }
    private static HostConnection attach(FakeRelay relay) throws Exception {
        HostConnection c = new HostConnection("127.0.0.1", relay.port, ID.keyPair(), PeerDiscoveryTest.PROTO);
        c.attach(3000);
        c.startReader(new HostConnection.Sink() {
            public void onInbound(HostConnection.Inbound ignored) { }
            public void onDead(String ignored) { }
        });
        return c;
    }

    @Test public void interruptedAckWaitPreservesFlagAndDoesNotStealTheNextAck() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        AtomicReference<DataOutputStream> output = new AtomicReference<>();
        AtomicInteger messages = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<MaximaSender.Result> result = new AtomicReference<>();
        try (FakeRelay relay = new FakeRelay(Collections.emptyList()); HostConnection c = attach(relay)) {
            relay.onMessage = out -> {
                output.set(out);
                if (messages.incrementAndGet() == 1) first.countDown();
                else synchronized (out) { Frame.write(out, Frame.ack(Frame.RESPONSE_OK)); }
            };
            MaximaSender.Built built = message();
            Thread sender = new Thread(() -> {
                result.set(c.send(built.unit, built.msgid, 3000));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            sender.start();
            try {
                assertTrue(first.await(3, TimeUnit.SECONDS));
                sender.interrupt();
                sender.join(1000);
                assertFalse("interruption must release the ACK wait", sender.isAlive());
                assertNotNull(result.get());
                assertEquals(-1, result.get().status);
                assertTrue("interrupt must remain visible to the owning worker", interrupted.get());
                DataOutputStream out = output.get();
                synchronized (out) { Frame.write(out, Frame.ack(Frame.RESPONSE_UNKNOWN)); }
                assertEquals(Frame.RESPONSE_OK, c.send(built.unit, built.msgid, 1000).status);
                assertTrue(c.isAttached());
            } finally { c.close(); sender.interrupt(); sender.join(3000); }
        }
    }

    @Test public void alreadyInterruptedAttachedSendWritesNoFrame() throws Exception {
        try (FakeRelay relay = new FakeRelay(Collections.emptyList()); HostConnection c = attach(relay)) {
            MaximaSender.Built built = message();
            Thread.currentThread().interrupt();
            try {
                assertEquals(-1, c.send(built.unit, built.msgid, 1000).status);
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(0, c.attachedSends());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void alreadyInterruptedSendDoesNotCallTheAttachedTransport() throws Exception {
        AtomicInteger called = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            try {
                MaximaSender.send("127.0.0.1", 1, null, new MiniData(), 100, 100, (h, p, u, id, ms) -> {
                    called.incrementAndGet(); return new MaximaSender.Result(Frame.RESPONSE_OK, id, 0);
                });
                fail("cancelled send was admitted");
            } catch (InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
            assertEquals(0, called.get());
        } finally { Thread.interrupted(); }
    }

    @Test public void interruptedAttachedFallbackDoesNotOpenAFreshSocket() throws Exception {
        long before = MaximaSender.FRESH_SOCKETS.get();
        try {
            try {
                MaximaSender.send("127.0.0.1", 1, null, new MiniData(), 100, 100, (h, p, u, id, ms) -> {
                    Thread.currentThread().interrupt(); return null;
                });
                fail("cancelled fallback was admitted");
            } catch (InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
            assertEquals(before, MaximaSender.FRESH_SOCKETS.get());
        } finally { Thread.interrupted(); }
    }
}
