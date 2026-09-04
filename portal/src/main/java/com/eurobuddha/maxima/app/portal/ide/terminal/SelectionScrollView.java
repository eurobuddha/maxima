package com.eurobuddha.maxima.app.portal.ide.terminal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.ScrollView;

/**
 * The terminal output scroller.
 *
 * The terminal lives inside a ViewPager, which claims every horizontal drag as a tab
 * swipe. That is exactly the gesture used to drag a text-selection handle, so native
 * selection is unusable in place. While selection mode is on we hold the pager off
 * for the whole gesture; vertical scrolling of the output is unaffected.
 */
public class SelectionScrollView extends ScrollView {

    private boolean mLockHorizontal = false;

    public SelectionScrollView(Context context) {
        super(context);
    }

    public SelectionScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SelectionScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** True while the user is selecting text: the pager must not steal the drag. */
    public void setLockHorizontal(boolean zLock) {
        mLockHorizontal = zLock;
        ViewParent parent = getParent();
        if (parent != null && !zLock) {
            parent.requestDisallowInterceptTouchEvent(false);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // The parent clears its disallow flag on ACTION_DOWN before dispatching to us,
        // so setting it here holds for the rest of the gesture.
        if (mLockHorizontal && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        }
        return super.onInterceptTouchEvent(ev);
    }
}
