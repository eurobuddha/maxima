package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.i18n.Language;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GalleryLanguageTest {
    @Test public void explicitCollectionNameIsNeverTreatedAsFallback() throws Exception {
        for (String name : new String[]{"Collection", "Token", "中文 Русский {count} $1"}) {
            StateNft.Meta meta = StateNft.parseMeta("0x01", new JSONObject().put("name", name).put("total", "12"));
            assertTrue(meta.nameProvided);
            assertEquals(name, meta.name);
            assertEquals(12, meta.size);
            Object item = item(meta, true, -1, name);
            assertEquals(name, display(item));
            assertEquals(name, field(item, "name"));
        }
        StateNft.Meta missing = StateNft.parseMeta("0x01", new JSONObject());
        assertFalse(missing.nameProvided);
        assertEquals("Collection", missing.name);
        assertTrue(display(item(missing, true, -1, missing.name)) instanceof Language.Message);
    }

    @Test public void envelopeAndNestedMetadataRetainOriginalValues() throws Exception {
        JSONObject metadata = new JSONObject().put("name", "Collection").put("mode", "embed")
                .put("url", "<artimage>AAAA</artimage>");
        StateNft.Meta meta = StateNft.parseMeta("0x01", new JSONObject()
                .put("token", new JSONObject().put("name", metadata).put("totalamount", "17"))
                .put("script", "RETURN TRUE"));
        assertTrue(meta.nameProvided);
        assertEquals("Collection", meta.name);
        assertEquals("embed", meta.mode);
        assertEquals(17, meta.size);
        assertEquals("<artimage>AAAA</artimage>", meta.icon);
        assertTrue(StateNft.parseMeta("0x01", "Collection").nameProvided);
        assertFalse(StateNft.parseMeta("0x01", "").nameProvided);
        assertFalse(StateNft.parseMeta("0x01", null).nameProvided);
        JSONObject unnamed = new JSONObject().put("mode","url").put("size",22);
        StateNft.Meta missing = StateNft.parseMeta("0x01",new JSONObject().put("name",unnamed));
        assertFalse(missing.nameProvided);
        // Preserve the old parser's raw value for callers, but never show the metadata object as a name.
        assertEquals(unnamed.toString(),missing.name);
        assertTrue(display(item(missing,true,-1,missing.name)) instanceof Language.Message);
    }

    @Test public void itemDisplayDoesNotChangeActionOrSearchName() throws Exception {
        StateNft.Meta meta = StateNft.parseMeta("0x01", "中文 Русский {count} $1");
        for (int index : new int[]{-1, 7}) {
            String raw = meta.name + (index < 0 ? " (unstamped)" : " #7");
            Object item = item(meta, false, index, raw);
            assertTrue(display(item) instanceof Language.Message);
            assertEquals(raw, field(item,"name"));
            assertEquals("中文 Русский {count} $1", meta.name);
        }
        String raw=meta.name+" #007";
        Object padded=item(meta,false,7,raw); set(padded,"stampedIndex","007");
        assertEquals(raw,display(padded).toString());
        assertEquals(raw,field(padded,"name"));
    }

    @Test public void searchFoldingDoesNotChangeWithSelectedLocale() throws Exception {
        Method blob = GalleryView.class.getDeclaredMethod("blob", String[].class);
        blob.setAccessible(true);
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertEquals("title 中文 русский 0xabcdef ", blob.invoke(null, (Object)new String[]{"TITLE 中文 Русский",null,"0xABCDEF"}));
        } finally { Locale.setDefault(before); }
    }

    private static Object item(StateNft.Meta meta, boolean collection, int index, String name) throws Exception {
        Class<?> type = Class.forName(GalleryView.class.getName()+"$GItem");
        Constructor<?> constructor=type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object item=constructor.newInstance();
        set(item,"stateNft",true); set(item,"collection",collection); set(item,"meta",meta);
        set(item,"idx",index); set(item,"stampedIndex",index < 0 ? null : String.valueOf(index)); set(item,"name",name);
        return item;
    }
    private static Object display(Object item) throws Exception {
        Method display=GalleryView.class.getDeclaredMethod("displayName", item.getClass()); display.setAccessible(true);
        return display.invoke(null,item);
    }
    private static Object field(Object item,String name) throws Exception {
        Field field=item.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(item);
    }
    private static void set(Object item,String name,Object value) throws Exception {
        Field field=item.getClass().getDeclaredField(name); field.setAccessible(true); field.set(item,value);
    }
}
