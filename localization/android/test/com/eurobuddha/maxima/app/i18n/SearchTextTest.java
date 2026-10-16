package com.eurobuddha.maxima.app.i18n;

import org.junit.Test;
import static org.junit.Assert.*;

public class SearchTextTest {
    @Test public void highlightOffsetsAlwaysReferToOriginalText() {
        String text = "İ Привет 中文";
        for (String query : new String[]{"i", "привет", "中文"}) {
            int at = SearchText.indexOf(text, query);
            assertTrue(at >= 0);
            assertTrue(text.substring(at, at + query.length()).equalsIgnoreCase(query));
        }
        assertEquals(-1, SearchText.indexOf(text, "missing"));
    }
}
