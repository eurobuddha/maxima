package com.eurobuddha.maxima.app.i18n;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.viewpager.widget.ViewPager;
import com.eurobuddha.maxima.app.R;
import com.google.android.material.tabs.TabLayout;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only measurements of the actual shell; never sends or connects anything. */
public final class LanguageTabProbe {
    private final Activity activity;
    private final TabLayout tabs;
    private final ViewPager pager;
    private int expected, selections, reselections;

    public LanguageTabProbe(Activity activity, ViewPager pager) {
        this.activity = activity;
        this.pager = pager;
        tabs = activity.findViewById(R.id.tabs);
        expected = pager.getCurrentItem();
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            public void onTabSelected(TabLayout.Tab tab) { selections++; }
            public void onTabUnselected(TabLayout.Tab tab) {}
            public void onTabReselected(TabLayout.Tab tab) { reselections++; }
        });
    }

    public void showSettings() {
        if (tabs.getTabCount() != 5) throw new IllegalStateException("Standalone shell required");
        pager.setCurrentItem(4, false);
        expected = 4;
        selections = reselections = 0;
    }

    public void scroll(String direction) {
        View arrow = activity.findViewById("left".equals(direction) ? R.id.tabScrollLeft : R.id.tabScrollRight);
        if (arrow.getVisibility() == View.VISIBLE) arrow.performClick();
    }

    public JSONObject snapshot() throws Exception {
        ViewGroup strip = (ViewGroup) tabs.getChildAt(0);
        JSONArray labels = new JSONArray();
        for (int i = 0; i < 4; i++) {
            String label = tabs.getTabAt(i).getText().toString();
            View tab = strip.getChildAt(i);
            TextView text = findText(tab, label);
            boolean readable = text != null && text.getLayout() != null && text.getWidth() > 0
                    && text.getLayout().getLineCount() == 1 && text.getLayout().getEllipsisCount(0) == 0;
            labels.put(new JSONObject().put("text", label).put("readable", readable).put("width", tab.getWidth()));
        }
        View selected = strip.getChildAt(expected);
        Rect visible = new Rect();
        TextView selectedLabel = findText(selected, tabs.getTabAt(expected).getText().toString());
        boolean selectedVisible = selectedLabel != null && selectedLabel.getGlobalVisibleRect(visible)
                && visible.width() == selectedLabel.getWidth();
        android.content.res.Configuration config = tabs.getResources().getConfiguration();
        JSONArray header = new JSONArray();
        for (int id : new int[]{R.id.main_title, R.id.main_powered, R.id.main_version, R.id.status_pill})
            header.put(measureText(activity.findViewById(id)));
        TextView networkHeading = findText(activity.getWindow().getDecorView(),
                Language.text(activity, R.string.l10n_native_network_connection));
        return new JSONObject().put("header", header)
                .put("network_heading", networkHeading == null ? JSONObject.NULL : measureText(networkHeading))
                .put("labels", labels).put("count", tabs.getTabCount())
                .put("page", pager.getCurrentItem()).put("selected", tabs.getSelectedTabPosition())
                .put("expected", expected).put("selections", selections).put("reselections", reselections)
                .put("selected_visible", selectedVisible)
                .put("settings_hidden", tabs.getTabCount() != 5 || strip.getChildAt(4).getWidth() == 0)
                .put("left", arrow(R.id.tabScrollLeft)).put("right", arrow(R.id.tabScrollRight))
                .put("scroll_x", tabs.getScrollX()).put("overflow", strip.getWidth() - tabs.getWidth())
                .put("mode", tabs.getTabMode()).put("font_scale", config.fontScale).put("width_dp", config.screenWidthDp);
    }

    private static JSONObject measureText(TextView text) throws Exception {
        android.text.Layout layout = text.getLayout();
        int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        boolean fits = layout != null && width > 0;
        if (layout != null) for (int i = 0; i < layout.getLineCount(); i++)
            fits &= layout.getEllipsisCount(i) == 0 && layout.getLineWidth(i) <= width + 1;
        Rect visible = new Rect();
        boolean onScreen = text.getGlobalVisibleRect(visible) && visible.width() == text.getWidth()
                && visible.height() == text.getHeight();
        return new JSONObject().put("text", text.getText().toString()).put("fits", fits)
                .put("visible", onScreen).put("lines", layout == null ? 0 : layout.getLineCount())
                .put("width", width);
    }

    private JSONObject arrow(int id) throws Exception {
        View view = activity.findViewById(id);
        return new JSONObject().put("visible", view.getVisibility() == View.VISIBLE)
                .put("description", view.getContentDescription());
    }

    private static TextView findText(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView)view).getText())) return (TextView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup)view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
}
