package com.eurobuddha.maxima.app.portal.wallet;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

/** Hosts the wallet's tab pages (each a {@link BaseView}). */
public class MainPager extends PagerAdapter {

    private final BaseView[] views;
    private final String[] titles;

    public MainPager(BaseView[] views, String[] titles) {
        this.views = views;
        this.titles = titles;
    }

    public BaseView viewAt(int pos) {
        return views[pos];
    }

    @Override
    public int getCount() {
        return views.length;
    }

    @Override
    public CharSequence getPageTitle(int position) {
        return titles[position];
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        View v = views[position].getRoot();
        container.removeView(v);
        container.addView(v);
        return v;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        container.removeView((View) object);
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return view == object;
    }
}
