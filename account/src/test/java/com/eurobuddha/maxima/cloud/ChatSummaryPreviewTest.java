package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.chat.ChatContact;
import com.eurobuddha.maxima.core.chat.ChatMedia;
import com.eurobuddha.maxima.core.chat.ChatPay;
import org.minima.utils.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatSummaryPreviewTest {
    private JSONObject summary(String body) {
        JSONObject result = new JSONObject();
        ParlonsControl.putSummaryPreview(result, body);
        return result;
    }
    private void check(String body, String kind, String text, String legacy) {
        JSONObject result = summary(body), preview = (JSONObject) result.get("lastPreview");
        assertEquals(kind, preview.get("kind")); assertEquals(text, preview.get("text"));
        assertEquals(legacy, result.get("last")); assertEquals(2, preview.size());
        assertFalse(result.toString().contains("SECRET"));
    }
    @Test public void parsedTypesContainOnlyDisplayDataAndRetainLegacyPreviews() {
        for (String mime : new String[]{"image/jpeg", "video/mp4", "audio/webm"}) {
            String body = ChatMedia.wrap(mime, "mx1:SECRET-KEY-MANIFEST", "中文 $1 {caption}");
            check(body, mime.startsWith("image") ? "photo" : mime.startsWith("video") ? "video" : "voice",
                    "中文 $1 {caption}", ChatMedia.preview(body));
        }
        String voice = ChatMedia.wrap("audio/webm", "mx1:SECRET", "0:42|SECRET-WAVEFORM");
        check(voice, "voice", "0:42", "🎤 Voice note  0:42");
        String file = ChatMedia.wrap("image/png", "pf1:SECRET", "Photo 中文.png");
        check(file, "file", "Photo 中文.png", "📎 Photo 中文.png");
        String contact = ChatContact.wrap("SECRET-KEY", "Photo", "SECRET-ADDRESS");
        check(contact, "contact", "Photo", "👤 Contact: Photo");
        check(ChatContact.wrap("SECRET", "", "SECRET"), "contact", "", "👤 Contact: (no name)");
    }
    @Test public void typedEnglishAndMalformedBodiesAreNeverGuessed() {
        for (String raw : new String[]{"📷 Photo", "👤 Contact: Photo", "Photo", "\u0001m\u0001broken", "\u0001c\u0001broken", ""}) {
            JSONObject result = summary(raw);
            assertEquals(raw, result.get("last")); assertFalse(result.containsKey("lastPreview"));
        }
        assertEquals("", summary(null).get("last"));
    }
    @Test public void allPreviewFieldsAreBoundedWithoutSplittingSupplementaryCharacters() {
        String large = "😀".repeat(5000);
        for (String body : new String[]{large, ChatMedia.wrap("image/png", "mx1:SECRET", large), ChatContact.wrap("SECRET", large, "SECRET")}) {
            JSONObject result = summary(body);
            String legacy = (String) result.get("last");
            assertTrue(legacy.codePointCount(0, legacy.length()) <= 200);
            assertFalse(Character.isHighSurrogate(legacy.charAt(legacy.length()-1)));
            JSONObject preview = (JSONObject) result.get("lastPreview");
            if (preview != null) {
                String text = (String) preview.get("text");
                assertEquals(200, text.codePointCount(0, text.length()));
                assertFalse(Character.isHighSurrogate(text.charAt(text.length()-1)));
            }
            assertTrue(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 2000);
        }
    }
    @Test public void paymentDisplayKeepsExactAmountAndLiteralMemo() {
        String raw = ChatPay.wrap("1.234567890123456789", "Token 中文", "0xTXID", "Photo Русский");
        JSONObject result = summary(raw), preview = (JSONObject) result.get("lastPreview");
        assertEquals(raw, result.get("last"));
        assertEquals("payment", preview.get("kind"));
        assertEquals("💸 1.234567890123456789 Token 中文  Photo Русский", preview.get("text"));
    }
    @Test public void byteBoundedPagesReturnEveryRowOnceWithoutDroppingLongUnicodePreviews() {
        java.util.List<JSONObject> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 503; i++) {
            JSONObject row = summary(ChatMedia.wrap("image/png", "mx1:SECRET", "\u0000😀".repeat(200)));
            row.put("peer", "0x" + String.format("%064d", i)); row.put("name", "Имя 中文".repeat(40));
            rows.add(row);
        }
        int offset = 0, pages = 0;
        java.util.List<Object> actual = new java.util.ArrayList<>();
        do {
            JSONObject page = ParlonsControl.summaryPage(rows.size(), offset, 200, rows::get);
            assertTrue(page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= com.eurobuddha.maxima.core.rpc.ServiceRegistry.MAX_REPLY_BYTES);
            actual.addAll((org.minima.utils.json.JSONArray) page.get("summaries"));
            int next = (Integer) page.get("next"); assertTrue(next > offset); offset = next; pages++;
            if (!Boolean.TRUE.equals(page.get("more"))) break;
        } while (pages < 20);
        assertEquals(rows, actual); assertEquals(rows.size(), offset); assertTrue(pages > 3);
        assertEquals(0, ((org.minima.utils.json.JSONArray) ParlonsControl.summaryPage(rows.size(), Integer.MAX_VALUE, 200, rows::get).get("summaries")).size());
        assertEquals(1, ((Integer) ParlonsControl.summaryPage(rows.size(), -1, 1, rows::get).get("next")).intValue());
    }
    @Test public void oversizedSingleRowFailsExplicitlyInsteadOfReturningANonAdvancingPage() {
        JSONObject row = new JSONObject(); row.put("peer", "x".repeat(com.eurobuddha.maxima.core.rpc.ServiceRegistry.MAX_REPLY_BYTES));
        try { ParlonsControl.summaryPage(1, 0, 200, i -> row); fail("Oversized row accepted"); }
        catch (IllegalArgumentException expected) { assertEquals("summary row too large", expected.getMessage()); }
    }

}
