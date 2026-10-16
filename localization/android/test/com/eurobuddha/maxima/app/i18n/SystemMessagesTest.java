package com.eurobuddha.maxima.app.i18n;

import com.eurobuddha.maxima.app.R;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercise the compiled lookup against the complete shared catalog in both Android apps. */
public class SystemMessagesTest {
    @Test public void everyFixedCatalogMessageResolvesToItsResource() throws Exception {
        Path catalog=Path.of("../localization/catalog.json");
        if (!Files.exists(catalog)) catalog=Path.of("localization/catalog.json");
        JSONObject source=new JSONObject(new String(Files.readAllBytes(catalog), java.nio.charset.StandardCharsets.UTF_8));
        // Source order defines the existing duplicate-English policy. JSONObject iteration
        // is unordered, so read the generator's ordered mapping for those duplicate cases.
        Path mapping=catalog.toAbsolutePath().normalize().getParent().getParent().resolve("desktop/src/main/resources/i18n/system-keys.json");
        JSONObject keys=new JSONObject(new String(Files.readAllBytes(mapping), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(keys.length()>256);
        for (Iterator<String> it=keys.keys();it.hasNext();) {
            String english=it.next(), key=keys.getString(english);
            assertEquals(english,source.getJSONObject(key).getString("en"));
            String resource="l10n_"+key.replaceAll("([a-z0-9])([A-Z])","$1_$2").replace('.','_').toLowerCase(java.util.Locale.ROOT);
            CharSequence value=SystemMessages.message(english);
            assertTrue(key,value instanceof Language.Message);
            assertEquals(key,R.string.class.getField(resource).getInt(null),((Language.Message)value).resource);
        }
    }
    @Test public void unknownTextAndTemplatesStayLiteral() {
        for(String value:new String[]{"Saved 中文 Русский {field} $1","FB","Ea","{name} copy","Script is {length} chars"})
            assertEquals(value,SystemMessages.message(value));
        assertEquals("",SystemMessages.message(null));
    }
    @Test public void dynamicDiagnosticsKeepImmutableLiteralArgumentsAndBoundRecursion() {
        String literal = "Copy 中文 $1 {error} <img>";
        Language.Message outer = (Language.Message) SystemMessages.message("node error: hosting failed: no such contact");
        assertEquals(R.string.l10n_server_error_node_failure, outer.resource);
        Language.Message inner = (Language.Message) outer.arguments[0];
        assertEquals(R.string.l10n_server_error_host_failure, inner.resource);
        assertEquals(R.string.l10n_server_error_no_contact, ((Language.Message) inner.arguments[0]).resource);
        for (String[] value : new String[][]{{"node error: " + literal, literal}, {"node error: Copy", "Copy"},
                {"unknown contact " + literal, literal}, {"chunk unavailable: " + literal, literal}}) {
            Language.Message descriptor = (Language.Message) SystemMessages.message(value[0]);
            assertEquals(value[1], descriptor.arguments[0]);
        }
        String number = "00012345678901234567890";
        Language.Message counter = (Language.Message) SystemMessages.message("can only RAISE above the current " + number);
        assertEquals(R.string.l10n_server_error_counter_above_current, counter.resource);
        assertEquals(number, counter.arguments[0]);
        Language.Message group = (Language.Message) SystemMessages.message("a group holds at most 128 members (129 asked)");
        assertEquals(R.string.l10n_server_error_group_limit, group.resource);
        assertArrayEquals(new Object[]{"128", "129"}, group.arguments);
        for (String raw : new String[]{"can only RAISE above the current -1", "can only RAISE above the current 1\n", "above the key's maximum 1e9", "a group holds at most 128 members (129 asked)\n"})
            assertEquals(raw, SystemMessages.message(raw));
        String prefix = "node error: ", tail = prefix.repeat(4) + literal;
        Object descriptor = SystemMessages.message(prefix.repeat(8) + tail);
        for (int depth = 0; depth < 8; depth++) descriptor = ((Language.Message) descriptor).arguments[0];
        assertEquals(tail, descriptor);
    }
    @Test public void rpcFutureWrapperTranslatesOnlyKnownFailures() {
        String known = new java.util.concurrent.ExecutionException(new RuntimeException("no such contact")).getMessage();
        Language.Message descriptor = (Language.Message) SystemMessages.message(known);
        assertEquals(R.string.l10n_server_error_no_contact, descriptor.resource);
        for (String detail : new String[]{"Copy", "unknown failure 中文 {error} $1", ""}) {
            String unknown = new java.util.concurrent.ExecutionException(new RuntimeException(detail)).getMessage();
            assertEquals(unknown, SystemMessages.message(unknown));
        }
    }
}
