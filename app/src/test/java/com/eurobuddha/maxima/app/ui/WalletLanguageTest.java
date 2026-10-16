package com.eurobuddha.maxima.app.ui;

import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.wallet.CoinAggregator;
import java.lang.reflect.Method;
import org.junit.Test;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WalletLanguageTest {
    @Test public void tokenNamesAreLiteralIncludingNamesThatMatchTranslatedLabels() throws Exception {
        WalletPage page = mock(WalletPage.class, CALLS_REAL_METHODS);
        Method display = WalletPage.class.getDeclaredMethod("tokenDisplayName", CoinAggregator.Agg.class);
        display.setAccessible(true);
        for (String name : new String[]{"Token", "Send", "Wallet", "中文 Русский {count} $1"}) {
            JSONObject metadata = new JSONObject(); metadata.put("name", name);
            assertEquals(name, display.invoke(page, aggregate(metadata)));
            JSONObject nested = new JSONObject(); nested.put("name", metadata);
            assertEquals(name, display.invoke(page, aggregate(nested)));
        }
        assertTrue(display.invoke(page, aggregate(new JSONObject())) instanceof Language.Message);
    }
    @Test public void heldBalanceKeepsPrecisionUnderCommaDecimalLocale() throws Exception {
        Method held = WalletPage.class.getDeclaredMethod("held", String.class, String.class);
        held.setAccessible(true);
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru"));
            assertEquals("1.23456789012345679", held.invoke(null, "1.234567890123456789", "0.000000000000000001"));
            assertEquals("0.000000000123456789", held.invoke(null, "0.000000000123456788", "0.000000000000000001"));
            assertEquals("unknown", held.invoke(null, "unknown", "0"));
        } finally { java.util.Locale.setDefault(previous); }
    }
    private static CoinAggregator.Agg aggregate(JSONObject metadata) {
        JSONObject coin = new JSONObject(); coin.put("tokenid", "0x01");coin.put("amount", "1");coin.put("token",metadata);
        JSONArray coins = new JSONArray(); coins.add(coin);
        return CoinAggregator.aggregate(coins).get(0);
    }
}
