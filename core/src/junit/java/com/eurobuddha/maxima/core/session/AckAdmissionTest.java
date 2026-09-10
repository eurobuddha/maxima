package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.net.HostConnection;
import com.eurobuddha.maxima.core.net.Frame;
import java.io.DataOutputStream;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Sequential abandoned writes must not retain an unlimited ordered ACK history. */
public class AckAdmissionTest {
    @Test public void cancelledSlotsExhaustTheBoundBeforeAnotherFrameIsWritten() throws Exception {
        exercise(false);
    }

    @Test public void lateAcksFreeCapacityAndKeepTheNextAnswerAligned() throws Exception {
        exercise(true);
    }

    private void exercise(boolean recover) throws Exception {
        var id = HostPoolShedTest.identity(89);
        var built = MaximaSender.build(id.publicKey(), id.keyPair().getPrivate(), id.publicKey(),
                "test", new byte[]{1}, System.currentTimeMillis());
        AtomicInteger frames = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<DataOutputStream> output = new AtomicReference<>();
        try (FakeRelay relay = new FakeRelay(Collections.emptyList());
             HostConnection c = new HostConnection("127.0.0.1", relay.port, id.keyPair(), PeerDiscoveryTest.PROTO)) {
            c.attach(3000);
            c.startReader(new HostConnection.Sink() {
                public void onInbound(HostConnection.Inbound ignored) { }
                public void onDead(String ignored) { }
            });
            Thread worker = new Thread(() -> {
                try {
                    for (int i = 0; i < 4096; i++) {
                        assertEquals(-1, c.send(built.unit, built.msgid, 3000).status);
                        assertTrue("received frame interrupts its waiting caller", Thread.interrupted());
                    }
                    assertEquals(4096, pending(c));
                    assertTrue(c.isAttached());
                    if (recover) {
                        DataOutputStream out = output.get();
                        synchronized (out) { for (int i = 0; i < 4096; i++) Frame.write(out, Frame.ack(Frame.RESPONSE_UNKNOWN)); }
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                        while (pending(c) != 0 && System.nanoTime() < deadline) Thread.sleep(5);
                        assertEquals(0, pending(c));
                        relay.onMessage = reply -> {
                            frames.incrementAndGet();
                            synchronized (reply) { Frame.write(reply, Frame.ack(Frame.RESPONSE_OK)); }
                        };
                        assertEquals(Frame.RESPONSE_OK, c.send(built.unit, built.msgid, 1000).status);
                        assertTrue(c.isAttached());
                        assertEquals(0, pending(c));
                        return;
                    }
                    assertEquals(-1, c.send(built.unit, built.msgid, 100).status);
                    Thread.interrupted();
                    assertEquals("overflow must be refused before the wire write", 4096, c.attachedSends());
                    assertFalse("exhausted ACK history retires this connection", c.isAttached());
                    assertEquals(0, pending(c));
                } catch (Throwable e) { failure.set(e); }
                finally { Thread.interrupted(); }
            }, "ack-admission-test");
            relay.onMessage = out -> { output.set(out); frames.incrementAndGet(); worker.interrupt(); };
            worker.start();
            try {
                worker.join(30_000);
                assertFalse("bounded synthetic sender completed", worker.isAlive());
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertEquals(recover ? 4097 : 4096, frames.get());
            } finally { c.close(); worker.interrupt(); worker.join(3000); }
        }
    }

    private static int pending(HostConnection c) throws Exception {
        Field f = HostConnection.class.getDeclaredField("mAckWaiters");
        f.setAccessible(true);
        ArrayDeque<?> queue = (ArrayDeque<?>) f.get(c);
        synchronized (queue) { return queue.size(); }
    }
}
