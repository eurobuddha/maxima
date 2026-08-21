package com.eurobuddha.maxima.core.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The media envelope carried inside a chat body. Includes the regression that
 *  would have caught the 0.6.16 deMarker bug (media rendered as base64 text). */
public class ChatMediaTest {

    @Test
    public void roundTripPhoto() {
        String body = ChatMedia.wrap("image/jpeg", "mx1:abc", "a caption");
        assertTrue(ChatMedia.isMedia(body));
        assertEquals("image/jpeg", ChatMedia.mime(body));
        assertEquals("mx1:abc", ChatMedia.ref(body));
        assertEquals("a caption", ChatMedia.caption(body));
    }

    @Test
    public void roundTripAudioAndVideo() {
        String audio = ChatMedia.wrap("audio/ogg", "data:audio/ogg;base64,AAAA", "0:12|8a3f");
        assertTrue(ChatMedia.isMedia(audio));
        assertEquals("audio/ogg", ChatMedia.mime(audio));
        // The caption is the whole rest of the string, incl. the waveform tail.
        assertEquals("0:12|8a3f", ChatMedia.caption(audio));

        String video = ChatMedia.wrap("video/mp4", "mx1:v", "");
        assertEquals("video/mp4", ChatMedia.mime(video));
        assertEquals("", ChatMedia.caption(video));
    }

    @Test
    public void plainTextIsNotMedia() {
        assertFalse(ChatMedia.isMedia("hello"));
        assertFalse(ChatMedia.isMedia("m starts with m but no marker"));
        assertFalse(ChatMedia.isMedia(null));
        assertFalse(ChatMedia.isMedia(""));
        assertNull(ChatMedia.parse("hello"));
    }

    @Test
    public void previewShowsOnlyDurationForVoiceNotes() {
        // Voice-note caption carries "duration|waveformhex" - preview must show
        // ONLY the human-facing duration, never the hex.
        String vn = ChatMedia.wrap("audio/ogg", "data:...", "0:12|8a3f2c");
        String preview = ChatMedia.preview(vn);
        assertTrue("preview should contain duration: " + preview, preview.contains("0:12"));
        assertFalse("preview must not leak waveform hex: " + preview, preview.contains("8a3f2c"));
    }

    @Test
    public void mimeInjectionCannotShiftFieldBoundaries() {
        // A crafted mime containing the SEP control char must be sanitized so it
        // can't inject an extra field boundary and corrupt ref/caption.
        String body = ChatMedia.wrap("image/jpegevil", "mx1:real", "cap");
        assertEquals("mx1:real", ChatMedia.ref(body));
        assertEquals("cap", ChatMedia.caption(body));
    }

    /**
     * REGRESSION GUARD (0.6.16 -> 0.6.20): media rides inside a TYPE_TEXT body,
     * so every inbound text passes through ChatEngine.deMarker BEFORE render.
     * 0.6.16 made deMarker neutralize any body that isMedia||isPayment, which
     * prefixed a replacement char to every legit photo/voice note -> isMedia()
     * then failed -> the receiver showed a wall of base64 text.
     *
     * This drives the ACTUAL fix site (deMarker), not ChatMedia in isolation:
     * with the bug present this test goes red; with the fix it stays green.
     */
    @Test
    public void mediaBodySurvivesDeMarker() {
        String body = ChatMedia.wrap("image/jpeg", "data:image/jpeg;base64,/9j/4AAQ", "");
        String afterInbound = ChatEngine.deMarker(body);
        assertEquals("deMarker must not touch a media body", body, afterInbound);
        assertTrue("a media body must still parse as media after the inbound path",
                ChatMedia.isMedia(afterInbound));
    }

    /**
     * The other half of the deMarker contract: a payment marker inside a plain
     * TYPE_TEXT body IS a forgery (real payments arrive as TYPE_PAYMENT), so it
     * must be neutralized - no longer parse as a payment after deMarker.
     */
    @Test
    public void forgedPaymentInTextIsNeutralized() {
        String forged = ChatPay.wrap("1000000", "MINIMA", "0xdeadbeef", "gotcha");
        assertTrue("precondition: the crafted body looks like a payment",
                ChatPay.isPayment(forged));
        String afterInbound = ChatEngine.deMarker(forged);
        assertFalse("a payment marker smuggled in text must be neutralized",
                ChatPay.isPayment(afterInbound));
    }
}
