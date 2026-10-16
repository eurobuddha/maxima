package com.eurobuddha.maxima.app.i18n;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JVM checks for binding/state behavior. Device resource and lifecycle checks run separately. */
public class LanguageTest {
    private final Context context = mock(Context.class);
    private final Resources resources = mock(Resources.class);
    private final Map<String, Object> saved = new HashMap<>();
    public LanguageTest() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putString(anyString(), anyString())).thenAnswer(call -> { saved.put(call.getArgument(0), call.getArgument(1)); return editor; });
        when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(call -> { saved.put(call.getArgument(0), call.getArgument(1)); return editor; });
        when(preferences.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> saved.getOrDefault(call.getArgument(0), call.getArgument(1)));
        when(context.getResources()).thenReturn(resources);
        when(resources.getConfiguration()).thenReturn(new Configuration());
        when(context.createConfigurationContext(any(Configuration.class))).thenReturn(context);
        when(resources.getString(1)).thenAnswer(call -> Language.code() + " label");
        when(resources.getString(2)).thenAnswer(call -> Language.code() + " hint");
    }
    @After public void reset() { Language.select(context, "en"); }
    @Test public void selectionIsExplicitAndPersists() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ru"));
            assertFalse(Language.hasChoice(context));
            Language.select(context, "zh-Hans");
            assertTrue(Language.hasChoice(context));
            assertEquals("zh-Hans", saved.get("code"));
            assertEquals("zh-Hans", Language.code());
            assertThrows(IllegalArgumentException.class, () -> Language.select(context, "auto"));
            assertEquals("zh-Hans", Language.code());
        } finally { Locale.setDefault(previous); }
    }
    @Test public void onlyBoundLabelsChangeAndDraftTextRemainsUntouched() {
        TextView label = view(new AtomicReference<>(""));
        AtomicReference<CharSequence> draftValue = new AtomicReference<>("Chats 中文 unsent");
        TextView draft = view(draftValue);
        Language.setText(label, Language.msg(1));
        Language.hint(draft, Language.msg(2));
        Language.select(context, "ru");
        assertEquals("ru label", label.getText());
        assertEquals("ru hint", draft.getHint());
        assertEquals("Chats 中文 unsent", draft.getText());
        verify(draft, never()).setText(any(CharSequence.class));
    }
    @Test public void clearedOrReplacedLabelsCannotReturn() {
        TextView label = view(new AtomicReference<>(""));
        Language.setText(label, Language.msg(1));
        Language.setText(label, "");
        Language.select(context, "ru");
        assertEquals("", label.getText());
        Language.setText(label, Language.msg(1));
        label.setText("Updated by an existing event handler");
        Language.select(context, "zh-Hans");
        assertEquals("Updated by an existing event handler", label.getText());
    }
    @Test public void composedLabelsTranslateWhileNamesAndValuesRemainLiteral() {
        when(resources.getString(eq(3), any())).thenAnswer(call -> Language.code() + " [" + call.getArgument(1) + "]");
        TextView label = view(new AtomicReference<>(""));
        Language.TextBuilder builder = new Language.TextBuilder()
                .append(Language.msg(3, Language.msg(1))).append("\nCopy {count} $1 中文").append(22);
        Language.Message message = builder.build();
        builder.append("must not enter the snapshot");
        Language.setText(label, message);
        for (String code : new String[]{"ru", "zh-Hans", "en"}) {
            Language.select(context, code);
            assertEquals(code + " [" + code + " label]\nCopy {count} $1 中文22", label.getText());
        }
        assertThrows(IllegalArgumentException.class, () -> Language.join(new StringBuilder("mutable")));
    }
    @Test public void pluralMessagesKeepLiteralArgumentsAndRebind() {
        when(resources.getQuantityString(eq(4),eq(2),any(),any())).thenAnswer(call ->
                Language.code()+" "+call.getArgument(2)+" / "+call.getArgument(3));
        Object[] arguments={"host中文Русский{count}$1:9501",2};
        Language.Message message=Language.count(4,2,arguments);
        arguments[0]="must not replace the snapshot";
        TextView label=view(new AtomicReference<>(""));
        Language.setText(label,message);
        for(String code:new String[]{"ru","zh-Hans","en"}) {
            Language.select(context,code);
            assertEquals(code+" host中文Русский{count}$1:9501 / 2",label.getText());
        }
    }
    @Test public void calendarLabelsHandleNewYearAndDaylightSaving() {
        java.util.TimeZone zone = java.util.TimeZone.getTimeZone("Europe/London");
        assertEquals(1, Language.recentDay(java.time.Instant.parse("2025-12-31T23:30:00Z").toEpochMilli(),
                java.time.Instant.parse("2026-01-01T00:30:00Z").toEpochMilli(), zone));
        assertEquals(1, Language.recentDay(java.time.Instant.parse("2026-03-29T00:30:00Z").toEpochMilli(),
                java.time.Instant.parse("2026-03-29T23:30:00Z").toEpochMilli(), zone));
        assertEquals(0, Language.recentDay(java.time.Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(),
                java.time.Instant.parse("2026-10-25T01:30:00Z").toEpochMilli(), zone));
        assertEquals(-1, Language.recentDay(java.time.Instant.parse("2026-01-02T00:00:00Z").toEpochMilli(),
                java.time.Instant.parse("2026-01-01T23:59:00Z").toEpochMilli(), zone));
    }
    private TextView view(AtomicReference<CharSequence> value) {
        TextView view = mock(TextView.class);
        AtomicReference<CharSequence> hint = new AtomicReference<>("");
        when(view.getContext()).thenReturn(context);
        when(view.getText()).thenAnswer(call -> value.get());
        when(view.getHint()).thenAnswer(call -> hint.get());
        doAnswer(call -> { value.set(call.getArgument(0)); return null; }).when(view).setText(any(CharSequence.class));
        doAnswer(call -> { hint.set(call.getArgument(0)); return null; }).when(view).setHint(any(CharSequence.class));
        return view;
    }
}
