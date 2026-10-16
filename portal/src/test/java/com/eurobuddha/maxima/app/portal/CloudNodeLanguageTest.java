package com.eurobuddha.maxima.app.portal;

import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;
import com.eurobuddha.maxima.app.i18n.LanguageDialog;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import java.lang.reflect.Method;
import org.junit.Test;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** User-controlled account names must never be used as translation keys. */
public class CloudNodeLanguageTest {
    @Test public void structuredInvitationFieldsOverrideLegacyEnglishNote() throws Exception {
        String code = "ABCD-{error}-$1-中文", invite = "MAX#literal#relay?code=" + code;
        java.lang.reflect.Field resource = Language.Message.class.getDeclaredField("resource"); resource.setAccessible(true);
        java.lang.reflect.Field arguments = Language.Message.class.getDeclaredField("arguments"); arguments.setAccessible(true);
        CharSequence full = CloudNodePage.pairingMessage(code, invite, "Legacy English instructions");
        assertTrue(full instanceof Language.Message);
        assertEquals(R.string.l10n_pairing_code_invite_message, resource.getInt(full));
        assertArrayEquals(new Object[]{code, invite}, (Object[]) arguments.get(full));
        CharSequence manual = CloudNodePage.pairingMessage(code, "", "Legacy English instructions");
        assertEquals(R.string.l10n_pairing_code_manual_message, resource.getInt(manual));
        assertArrayEquals(new Object[]{code}, (Object[]) arguments.get(manual));
        String legacy = "Legacy code ABCD-1234 with literal {error}";
        assertEquals(legacy, CloudNodePage.pairingMessage("", "", legacy));
        assertEquals(R.string.l10n_native_node_no_code, resource.getInt(CloudNodePage.pairingMessage(null, null, null)));
    }

    @Test public void hostedAccountNameStaysLiteralWhileItsStateIsTranslated() throws Exception {
        MainActivity activity = mock(MainActivity.class);
        Resources resources = mock(Resources.class);
        when(activity.getResources()).thenReturn(resources);
        when(resources.getConfiguration()).thenReturn(new Configuration());
        when(activity.createConfigurationContext(any(Configuration.class))).thenReturn(activity);
        when(resources.getString(anyInt())).thenReturn("translated label");
        when(resources.getString(R.string.l10n_hosting_state_running)).thenReturn("работает");
        CloudNodePage page = new CloudNodePage(activity, mock(View.class));
        JSONObject row = new JSONObject();
        row.put("name", "running"); row.put("state", "running"); row.put("paused", false);
        JSONArray rows = new JSONArray(); rows.add(row);
        JSONObject reply = new JSONObject(); reply.put("accounts", rows); reply.put("online", true); reply.put("limit", 5);
        try (var dialogs = mockConstruction(LanguageDialog.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            Method show = CloudNodePage.class.getDeclaredMethod("showHostedAccounts", JSONObject.class);
            show.setAccessible(true); show.invoke(page, reply);
            ArgumentCaptor<CharSequence[]> labels = ArgumentCaptor.forClass(CharSequence[].class);
            verify(dialogs.constructed().get(0)).setItems(labels.capture(), any(DialogInterface.OnClickListener.class));
            assertEquals(1, labels.getValue().length);
            assertTrue(labels.getValue()[0] instanceof Language.Message);
            java.lang.reflect.Field args = Language.Message.class.getDeclaredField("arguments"); args.setAccessible(true);
            Object[] parts = (Object[]) args.get(labels.getValue()[0]);
            assertEquals("running",parts[0]); assertEquals(" — ",parts[1]);
            assertTrue(parts[2] instanceof Language.Message);
            java.lang.reflect.Field resource = Language.Message.class.getDeclaredField("resource"); resource.setAccessible(true);
            assertEquals(R.string.l10n_hosting_state_running,resource.getInt(parts[2]));
            // The original wire values also remain untouched.
            assertEquals("running", row.get("name")); assertEquals("running", row.get("state"));
        }
    }
}
