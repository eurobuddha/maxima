package org.minima.maxjar;
import org.minima.system.Main;
import org.minima.utils.json.JSONObject;
import org.junit.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
public class NotificationDurabilityTest {
    @After public void down() { Main.clear(); }
    @Test public void failedWritesWithholdAcknowledgementUntilRetrySucceeds() {
        AtomicBoolean broken = new AtomicBoolean(true); AtomicInteger saved = new AtomicInteger();
        Main main = Main.init(null, (event, data) -> { if (broken.get()) throw new IllegalStateException("disk"); saved.incrementAndGet(); });
        JSONObject item = new JSONObject(); item.put("msgid", "one"); main.PostNotifyEvent("MAXIMA", item);
        assertFalse(main.flushNotifications()); assertEquals(0, saved.get());
        broken.set(false); assertTrue(main.flushNotifications()); assertEquals(1, saved.get());
        assertTrue(main.flushNotifications()); assertEquals(1, saved.get());
    }
    @Test public void overflowNeverCertifiesLostNotifications() {
        Main main = Main.init(null, (event, data) -> { throw new IllegalStateException("disk"); });
        for (int i=0; i<501; i++) { JSONObject item = new JSONObject(); item.put("msgid", "m" + i); main.PostNotifyEvent("MAXIMA", item); }
        assertFalse(main.flushNotifications());
    }
}
