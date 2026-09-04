package com.eurobuddha.maxima.app.portal.ide;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

import com.eurobuddha.maxima.app.portal.ide.ide.ScriptsView;
import com.eurobuddha.maxima.app.portal.ide.terminal.TerminalView;
import com.eurobuddha.maxima.app.portal.ide.txn.TxnView;

public class TerminalAdapter extends PagerAdapter {

    Activity mMainActivity;

    BaseView[] mAllViews;

    public TerminalAdapter(Activity zContext) {
        mMainActivity = zContext;

        mAllViews = new BaseView[4];
        mAllViews[0] = new TerminalView(mMainActivity);
        mAllViews[1] = new ScriptsView(mMainActivity);
        mAllViews[2] = new TxnView(mMainActivity);
        mAllViews[3] = new LogsView(mMainActivity);
    }

    public void refreshPagerView(int zPosition) {
        mAllViews[zPosition].refreshView();
        mAllViews[zPosition].getMainView().invalidate();
    }

    public TerminalView getTerminalView() {
        return (TerminalView) mAllViews[0];
    }

    public ScriptsView getScriptsView() {
        return (ScriptsView) mAllViews[1];
    }

    public TxnView getTxnView() {
        return (TxnView) mAllViews[2];
    }

    public LogsView getLogsView() {
        return (LogsView) mAllViews[3];
    }

    @Override
    public Object instantiateItem(final ViewGroup container, int position) {
        container.removeView(mAllViews[position].getMainView());
        container.addView(mAllViews[position].getMainView());
        return mAllViews[position].getMainView();
    }

    @Override
    public int getCount() {
        return 4;
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return object == view;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        container.removeView((View) object);
    }

    public void refreshAllViews() {
        for (BaseView v : mAllViews) {
            if (v != null) {
                v.refreshView();
                v.getMainView().invalidate();
            }
        }
    }

    public void onDestroy() {
        for (BaseView v : mAllViews) {
            if (v != null) v.onDestroy();
        }
    }
}
