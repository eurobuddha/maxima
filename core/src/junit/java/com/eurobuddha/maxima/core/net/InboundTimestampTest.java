package com.eurobuddha.maxima.core.net;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.codec.*;
import com.eurobuddha.maxima.core.identity.*;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import java.math.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class InboundTimestampTest {
    @Test public void fractionalAndWrappedWireTimesCannotDispatchOrEnterDedup() throws Exception {
        MaximaIdentity identity = MaximaIdentity.fromPhrase(Bip39.generate(24));
        MaximaNode node = new MaximaNode(identity, "test", 0);
        AtomicInteger deliveries = new AtomicInteger(); CountDownLatch fresh = new CountDownLatch(1);
        node.setMessageListener((message, id) -> {
            deliveries.incrementAndGet();
            if (message.mData.getBytes()[0] == 2) fresh.countDown();
        });
        try {
            long now = System.currentTimeMillis();
            MiniNumber[] times = {
                new MiniNumber(new BigDecimal(now).add(new BigDecimal("0.5"))),
                new MiniNumber(BigInteger.valueOf(now).subtract(BigInteger.ONE.shiftLeft(64))),
                new MiniNumber(now)
            };
            for (int i = 0; i < times.length; i++) {
                MaximaMessage msg = new MaximaMessage();
                msg.mRandom = new MiniData(new byte[32]); msg.mFrom = new MiniData(identity.publicKey());
                msg.mTo = msg.mFrom; msg.mTimeMilli = times[i]; msg.mApplication = new MiniString("timestamp-test");
                msg.mData = new MiniData(new byte[]{(byte) i});
                MaximaMessage decoded = MaximaMessage.fromBytes(Codec.serialise(msg));
                node.handle(new HostConnection.Inbound(decoded, decoded.msgid(), true));
            }
            assertTrue("fresh request drains the preceding input lane", fresh.await(3, TimeUnit.SECONDS));
            assertEquals("only exact in-range timestamps dispatch", 1, deliveries.get());
            assertEquals("invalid timestamps consume no replay budget", 1, node.dedup().size());
        } finally { node.stop(); }
    }
}
