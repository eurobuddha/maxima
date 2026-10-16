package com.eurobuddha.maxima.app.portal;

import com.eurobuddha.maxima.app.i18n.Language;
import java.lang.reflect.Method;
import org.junit.Test;
import org.minima.utils.json.JSONObject;
import static org.junit.Assert.*;

public class CloudWalletLanguageTest {
    @Test public void tokenAndCounterpartyNamesStayLiteral() throws Exception {
        Method token = CloudWalletPage.class.getDeclaredMethod("tokenName", JSONObject.class);
        Method who = CloudWalletPage.class.getDeclaredMethod("shortWho", String.class);
        token.setAccessible(true); who.setAccessible(true);
        for (String name : new String[]{"Wallet", "Send", "Token", "中文 Русский {count} $1"}) {
            JSONObject named = new JSONObject(); named.put("name", name);
            JSONObject row = new JSONObject(); row.put("token", named);
            assertEquals(name, token.invoke(null, row));
            row.put("token", name);
            assertEquals(name, token.invoke(null, row));
            assertEquals(name, who.invoke(null, name));
        }
        assertTrue(token.invoke(null, new JSONObject()) instanceof Language.Message);
        assertTrue(who.invoke(null, "") instanceof Language.Message);
    }

    @Test public void displayArithmeticRetainsAllFinancialDigits() {
        assertEquals("0.000000000123456789", CloudWalletPage.tidy("0.000000000123456789000"));
        assertEquals("1.000000000000000001", CloudWalletPage.locked("2.234567890123456790", "1.234567890123456789"));
        assertEquals("0", CloudWalletPage.locked("1", "2"));
        assertEquals("—", CloudWalletPage.locked("unknown", "1"));
        // Wallet arithmetic remains protocol decimal, even for comma-decimal OS locales.
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru"));
            assertEquals("1.234567890123456789", CloudWalletPage.tidy("1.2345678901234567890"));
        } finally { java.util.Locale.setDefault(old); }
    }
}
