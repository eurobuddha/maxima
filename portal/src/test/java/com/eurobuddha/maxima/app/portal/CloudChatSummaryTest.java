package com.eurobuddha.maxima.app.portal;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.JSONArray;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class CloudChatSummaryTest {
    @Test public void cachedStructuredRowsRefreshInPlaceWithoutTouchingSearchDraft() throws Exception {
        MainActivity activity = mock(MainActivity.class);
        Resources resources = mock(Resources.class);
        when(activity.getResources()).thenReturn(resources);
        when(resources.getConfiguration()).thenReturn(new Configuration());
        when(activity.createConfigurationContext(any(Configuration.class))).thenReturn(activity);
        View root = mock(View.class);
        ListView list = mock(ListView.class); EditText search = mock(EditText.class); TextView empty = mock(TextView.class);
        when(root.findViewById(R.id.conversations)).thenReturn(list);
        when(root.findViewById(R.id.chats_search)).thenReturn(search);
        when(root.findViewById(R.id.chats_empty)).thenReturn(empty);
        when(resources.getString(eq(R.string.l10n_media_photo_caption), any())).thenAnswer(c -> Language.code() + ": " + c.getArgument(1));
        CloudChatsPage page = new CloudChatsPage(activity, root);
        set(page, "mBusy", true); // no account/RPC while exercising actual parse and refresh methods
        JSONObject row = new JSONObject(), metadata = new JSONObject(), response = new JSONObject();
        row.put("peer", "literal-key"); row.put("name", "Photo"); row.put("last", "📷 Photo  中文 $1");
        metadata.put("kind", "photo"); metadata.put("text", "中文 $1"); row.put("lastPreview", metadata);
        JSONArray entries = new JSONArray(); entries.add(row); response.put("summaries", entries);
        String original = response.toString();
        Method parse = CloudChatsPage.class.getDeclaredMethod("parseRows", JSONObject.class); parse.setAccessible(true);
        List<?> parsed = (List<?>) parse.invoke(page, response);
        List<Object> all = (List<Object>) field(page, "mAll"); all.addAll(parsed);
        Object parsedRow = parsed.get(0);
        AtomicReference<String> code = new AtomicReference<>("en");
        try (MockedStatic<Language> labels = mockStatic(Language.class, CALLS_REAL_METHODS)) {
            labels.when(Language::code).thenAnswer(c -> code.get());
            for (String next : new String[]{"ru", "zh-Hans", "en"}) {
                code.set(next); page.languageChanged();
                assertSame(parsedRow, all.get(0));
                assertEquals(next + ": 中文 $1", field(parsedRow, "preview"));
                assertEquals("Photo", field(parsedRow, "name"));
                assertEquals(original, response.toString());
            }
        }
        verify(search, never()).setText(any(CharSequence.class));
        assertSame(search, field(page, "mSearch"));
    }
    @Test public void previewClippingDoesNotSplitSupplementaryCharacters() throws Exception {
        Method clip = CloudChatsPage.class.getDeclaredMethod("oneLine", String.class); clip.setAccessible(true);
        String display = (String) clip.invoke(null, "😀".repeat(71));
        assertEquals("😀".repeat(70) + "…", display);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
}
