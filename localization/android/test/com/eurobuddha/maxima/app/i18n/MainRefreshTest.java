package com.eurobuddha.maxima.app.i18n;

import android.content.res.Resources;
import android.widget.TextView;
import androidx.viewpager.widget.ViewPager;
import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.ui.Page;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;

/** Normal ticks must still refresh data after adding the separate locale callback. */
public class MainRefreshTest {
    @Test public void normalRefreshRendersTheVisiblePage() throws Exception {
        MainActivity activity = mock(MainActivity.class, CALLS_REAL_METHODS);
        ViewPager pager = mock(ViewPager.class);
        when(pager.getCurrentItem()).thenReturn(1);
        Page hidden = mock(Page.class), visible = mock(Page.class);
        set(activity, "mPager", pager);
        set(activity, "mPages", Arrays.asList(hidden, visible));
        set(activity, "mPill", mock(TextView.class));
        doReturn(mock(Resources.class)).when(activity).getResources();
        MockedStatic<?> service = null;
        try (MockedStatic<Language> labels = mockStatic(Language.class)) {
            try {
                set(activity, "mLastPoll", System.currentTimeMillis()); // Portal: no RPC during test.
                set(activity, "mReach", -1);
            } catch (NoSuchFieldException standalone) {
                service = mockStatic(Class.forName("com.eurobuddha.maxima.app.MaximaService"));
            }
            Method render = MainActivity.class.getDeclaredMethod("renderCurrent");
            render.setAccessible(true);
            render.invoke(activity);
            verify(visible).render();
            verify(visible, never()).languageChanged();
            verifyNoInteractions(hidden);
        } finally { if (service != null) service.close(); }
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
