package com.eurobuddha.maxima.app.ui;

import android.view.View;
import android.widget.TextView;
import androidx.viewpager.widget.ViewPager;
import com.google.android.material.tabs.TabLayout;

/** Shared wallet tab-scroll affordances; scrolling never selects or rebuilds a page. */
public final class TabScroll {
    private TabScroll() {}

    private TabLayout tabs;
    private boolean keepSelected = true;

    public static TabScroll attach(TabLayout tabs, ViewPager pager, TextView left, TextView right) {
        TabScroll controller = new TabScroll();
        controller.tabs = tabs;
        controller.wire(tabs, pager, left, right);
        return controller;
    }

    /** Keep four shell labels readable on narrow screens and at larger font sizes. */
    public static void configureShell(TabLayout tabs) {
        android.content.res.Configuration config = tabs.getResources().getConfiguration();
        boolean scroll = config.fontScale > 1.0f || config.screenWidthDp < 400;
        tabs.setTabMode(scroll ? TabLayout.MODE_SCROLLABLE : TabLayout.MODE_FIXED);
        tabs.setTabGravity(scroll ? TabLayout.GRAVITY_START : TabLayout.GRAVITY_FILL);
    }

    /** Follow the selected page through label/arrow reflow until the user scrolls the strip. */
    public void keepSelectedVisible() {
        keepSelected = true;
        tabs.requestLayout();
    }

    private void centerSelected() {
        int selected = tabs.getSelectedTabPosition();
        if (selected < 0) return;
        View strip = tabs.getChildAt(0);
        if (strip instanceof android.view.ViewGroup) {
            View tab = ((android.view.ViewGroup)strip).getChildAt(selected);
            // Standalone Settings remains in the pager but has a zero-width tab.
            if (tab == null || tab.getWidth() == 0) return;
        }
        tabs.setScrollPosition(selected, 0f, true);
    }

    private void wire(final TabLayout tabs, final ViewPager viewPager, final TextView left, final TextView right) {

        View.OnClickListener scroll = v -> {
            keepSelected = false;
            int by = Math.max(1, (int) (tabs.getWidth() * 0.7f));
            tabs.smoothScrollBy(v == left ? -by : by, 0);
            // Re-check once the fling settles; TabLayout gives us no scroll-end callback.
            tabs.postDelayed(() -> updateTabArrows(tabs, left, right), 350);
        };
        left.setOnClickListener(scroll);
        right.setOnClickListener(scroll);

        // Arrows can change the viewport width after labels have been measured. Follow every
        // layout in that reflow, without selecting the tab again or disturbing manual scrolling.
        tabs.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (keepSelected) centerSelected();
        });
        tabs.setOnTouchListener((view, event) -> {
            keepSelected = false;
            return false;
        });
        tabs.getViewTreeObserver().addOnScrollChangedListener(() -> updateTabArrows(tabs, left, right));
        tabs.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> updateTabArrows(tabs, left, right));
        // Selecting a tab scrolls the strip, which changes what's still off-screen.
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int position) {
                keepSelectedVisible();
                tabs.postDelayed(() -> updateTabArrows(tabs, left, right), 250);
            }
        });
        // The strip's inner width isn't known until it has been measured AND populated, and the
        // exact frame that happens on varies by device — so re-check a few times as it settles.
        for (int delay : new int[]{0, 150, 500, 1200}) {
            tabs.postDelayed(() -> updateTabArrows(tabs, left, right), delay);
        }
    }

    /**
     * Measure the overflow directly rather than asking {@code canScrollHorizontally}, which answers
     * from a scroll range that isn't populated until the strip has been laid out — so it reported
     * "nothing to scroll" and the chevrons never appeared at all.
     */
    private int lastLeftVis = -1, lastRightVis = -1;

    private void updateTabArrows(TabLayout tabs, View left, View right) {
        if (tabs == null || left == null || right == null) return;
        View strip = tabs.getChildCount() > 0 ? tabs.getChildAt(0) : null;
        if (strip == null) return;
        int slack = (int) (2 * tabs.getResources().getDisplayMetrics().density);
        int overflow = strip.getWidth() - tabs.getWidth();
        int x = tabs.getScrollX();
        final int wantLeft = x > slack ? View.VISIBLE : View.GONE;
        final int wantRight = overflow > slack && x < overflow - slack ? View.VISIBLE : View.GONE;
        if (wantLeft == lastLeftVis && wantRight == lastRightVis) return;
        lastLeftVis = wantLeft;
        lastRightVis = wantRight;
        left.setVisibility(wantLeft);
        right.setVisibility(wantRight);
        // These flips happen from a layout-change listener, i.e. DURING a layout pass, where
        // requestLayout() is swallowed — the chevrons went VISIBLE but were never measured, so they
        // sat at 0x0 and looked like they had never appeared. Re-request outside the pass.
        final View row = (View) right.getParent();
        if (row != null) row.post(row::requestLayout);
    }
}
