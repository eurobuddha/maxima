package com.eurobuddha.maxima.app.portal;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A background contact refresh must preserve both live input widgets, including IME state. */
public class CloudContactsDraftTest {
    @Test public void refreshingAddressDoesNotRecreateOrWriteToInputs() throws Exception {
        MainActivity activity = mock(MainActivity.class);
        Resources resources = mock(Resources.class);
        when(activity.getResources()).thenReturn(resources);
        when(resources.getConfiguration()).thenReturn(new Configuration());
        when(activity.createConfigurationContext(any(Configuration.class))).thenReturn(activity);
        when(resources.getString(eq(R.string.l10n_native_contacts_list_count), any())).thenReturn("Contacts · 0");
        View root = mock(View.class);
        LinearLayout container = mock(LinearLayout.class);
        when(root.findViewById(R.id.contacts_root)).thenReturn(container);
        CloudContactsPage page = new CloudContactsPage(activity, root);
        EditText addressInput = mock(EditText.class), searchInput = mock(EditText.class);
        when(searchInput.length()).thenReturn(8);
        TextView address = mock(TextView.class), count = mock(TextView.class);
        when(count.getContext()).thenReturn(activity);
        set(page, "mAddressValue", address); set(page, "mContactCount", count);
        set(page, "mAddField", addressInput); set(page, "mSearchField", searchInput);
        set(page, "mPermanent", "MAX#literal-address");
        page.languageChanged();
        verify(address).setText("MAX#literal-address");
        verify(container, never()).removeAllViews();
        verify(addressInput, never()).setText(any(CharSequence.class));
        verify(searchInput, never()).setText(any(CharSequence.class));
        assertSame(addressInput, get(page, "mAddField"));
        assertSame(searchInput, get(page, "mSearchField"));
        verify(searchInput).setVisibility(View.VISIBLE);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
}
