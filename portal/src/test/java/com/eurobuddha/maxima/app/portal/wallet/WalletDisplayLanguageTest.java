package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.i18n.Language;
import java.lang.reflect.Method;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WalletDisplayLanguageTest {
    @Test public void literalTokenNameIsDistinctFromMissingMetadata() throws Exception {
        Method display = Util.class.getDeclaredMethod("displayName", TokenBalance.class);
        display.setAccessible(true);
        TokenBalance balance = new TokenBalance();
        for (String name : new String[]{"Token", "Send", "中文 Русский {count} $1"}) {
            balance.meta = TokenMeta.parse(name, "0x01"); balance.name = balance.meta.name;
            assertTrue(balance.meta.nameProvided);
            assertEquals(name, display.invoke(null, balance));
        }
        balance.meta = TokenMeta.parse(null, "0x01"); balance.name = balance.meta.name;
        assertFalse(balance.meta.nameProvided);
        assertTrue(display.invoke(null, balance) instanceof Language.Message);
        assertTrue(TokenMeta.parse(null, "0x00").nameProvided);
    }

    @Test public void onlyKnownDirectionValuesBecomeLocalizedLabels() throws Exception {
        Method display = HistoryView.class.getDeclaredMethod("directionLabel", String.class);
        display.setAccessible(true);
        for (String direction : new String[]{"sent", "received", "self"})
            assertTrue(display.invoke(null, direction) instanceof Language.Message);
        assertEquals("unknown 中文 Русский", display.invoke(null, "unknown 中文 Русский"));
    }

    @Test public void amountDisplayKeepsProtocolDecimalPrecision() throws Exception {
        Method locked = BalancesView.class.getDeclaredMethod("lockedAmount", TokenBalance.class);
        locked.setAccessible(true);
        BalancesView view = mock(BalancesView.class, CALLS_REAL_METHODS);
        TokenBalance balance = new TokenBalance();
        balance.confirmed = "2.234567890123456790"; balance.sendable = "1.234567890123456789";
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ru"));
            assertEquals("1.000000000000000001", locked.invoke(view, balance));
            assertEquals("1.234567890123456789", Format.trim(balance.sendable, Format.FULL));
            assertEquals("1.2345…", Format.trim(balance.sendable, 4));
            balance.confirmed = "0";
            assertEquals("0", locked.invoke(view, balance));
        } finally { Locale.setDefault(before); }
    }
}
