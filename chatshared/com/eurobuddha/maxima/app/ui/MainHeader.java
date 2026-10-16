package com.eurobuddha.maxima.app.ui;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.eurobuddha.maxima.app.R;

/** Reflows the existing header views using the wallet's title column and stacked Network rows. */
public final class MainHeader {
    private MainHeader() {}

    public static boolean compact(Context context) {
        android.content.res.Configuration config = context.getResources().getConfiguration();
        return config.screenWidthDp < 400 || config.fontScale > 1.0f;
    }

    /** Called once after inflation; all view IDs, bindings and click listeners remain the same. */
    public static void configure(Activity activity) {
        if (!compact(activity)) return;
        LinearLayout bar = activity.findViewById(R.id.main_appbar);
        View title = activity.findViewById(R.id.main_title);
        LinearLayout brand = (LinearLayout) title.getParent().getParent();
        TextView powered = activity.findViewById(R.id.main_powered);
        TextView version = activity.findViewById(R.id.main_version);
        TextView status = activity.findViewById(R.id.status_pill);
        View pill = (View) status.getParent();
        View theme = activity.findViewById(R.id.btn_main_theme);
        View search = activity.findViewById(R.id.btn_main_search);
        View more = activity.findViewById(R.id.btn_main_more);
        for (View view : new View[]{brand, powered, version, pill, theme, search, more})
            ((ViewGroup)view.getParent()).removeView(view);

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(theme); top.addView(search); top.addView(more);
        LinearLayout info = new LinearLayout(activity);
        info.setGravity(Gravity.CENTER_VERTICAL);
        powered.setSingleLine(false);
        powered.setEllipsize(null);
        info.addView(powered, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(-2, -2);
        versionParams.setMarginStart(dp(activity, 8));
        info.addView(version, versionParams);

        status.setMaxWidth(Integer.MAX_VALUE);
        status.setSingleLine(false);
        status.setMaxLines(Integer.MAX_VALUE);
        status.setEllipsize(null);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.START);
        bar.addView(top, new LinearLayout.LayoutParams(-1, -2));
        bar.addView(info, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(-2, -2);
        pillParams.topMargin = dp(activity, 6);
        bar.addView(pill, pillParams);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
