package com.eurobuddha.maxima.app.portal.wallet;

import android.view.LayoutInflater;
import android.view.View;

/** A single tab page. Each tab holds an inflated root view and refreshes from wallet state. */
public abstract class BaseView {

    protected final WalletActivity act;
    protected final View root;

    public BaseView(WalletActivity a, int layoutRes) {
        act = a;
        root = LayoutInflater.from(a).inflate(layoutRes, null);
    }

    public View getRoot() {
        return root;
    }

    @SuppressWarnings("unchecked")
    protected <T extends View> T find(int id) {
        return (T) root.findViewById(id);
    }

    /** Re-render from current wallet state. Always called on the UI thread. */
    public abstract void refresh();

    /** Called when this tab becomes the visible page (tab selected). Default: no-op. */
    public void onShown() {}

    /** Called on a new chain block while the app is loaded. Default: no-op. */
    public void onNewBlock() {}
}
