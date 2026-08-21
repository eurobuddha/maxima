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
     * REGRESSION GUARD (0.6.16 -> 0.6.20): media rides inside a TYPE_TEXT body.
     * If any inbound-text guard prefixes/mangles the body, isMedia() breaks and
     * the receiver renders a photo/voice note as a wall of base64 text. This
     * asserts a freshly-wrapped media body still reads as media - the exact
     * property the deMarker regression violated.
     */
    @Test
    public void mediaBodySurvivesAsMedia() {
        String body = ChatMedia.wrap("image/jpeg", "data:image/jpeg;base64,/9j/4AAQ", "");
        assertTrue("a media body must always parse as media", ChatMedia.isMedia(body));
        // And a payment marker must NOT be mistaken for media (different 2nd char).
        String pay = ChatPay.wrap("100", "MINIMA", "0xtx", "");
        assertFalse(ChatMedia.isMedia(pay));
    }
}
