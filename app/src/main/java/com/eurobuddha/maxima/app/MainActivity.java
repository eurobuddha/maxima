package com.eurobuddha.maxima.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.eurobuddha.maxima.app.chat.ChatHub;
import com.eurobuddha.maxima.app.ui.ChatsPage;
import com.eurobuddha.maxima.app.ui.ContactsPage;
import com.eurobuddha.maxima.app.ui.NetworkPage;
import com.eurobuddha.maxima.app.ui.Page;
import com.eurobuddha.maxima.app.ui.SettingsPage;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.Group;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The app shell: a header that always says whether we are connected, and four
 * tabs.
 *
 * The split is by QUESTION, not by feature:
 *   Chats    - who am I talking to
 *   Contacts - who can I reach, and how do they reach me
 *   Network  - is this thing working, and what is it doing for others
 *   Settings - who am I, and what am I allowing
 *
 * Everything numeric lives on Network with an explanation attached, so the
 * conversational screens stay free of transport internals.
 */
public final class MainActivity extends AppCompatActivity implements ChatEngine.Listener {

    private TextView mPill;
    private ViewPager mPager;
    private final List<Page> mPages = new ArrayList<>();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            renderCurrent();
            mHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // targetSdk 35 is edge-to-edge, so the shell must inset itself or the
        // header sits under the clock and the tabs under the nav bar.
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        mPill = findViewById(R.id.status_pill);

        Sha3Provider.install();
        requestNotificationPermission();
        MaximaService.start(this);

        LayoutInflater inf = LayoutInflater.from(this);
        mPages.add(new ChatsPage(this, inf.inflate(R.layout.page_chats, null)));
        mPages.add(new ContactsPage(this, inf.inflate(R.layout.page_contacts, null)));
        mPages.add(new NetworkPage(this, inf.inflate(R.layout.page_network, null)));
        mPages.add(new SettingsPage(this, inf.inflate(R.layout.page_settings, null)));

        mPager = findViewById(R.id.pager);
        mPager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return mPages.size();
            }

            @Override
            public boolean isViewFromObject(View v, Object o) {
                return v == o;
            }

            @Override
            public Object instantiateItem(ViewGroup container, int position) {
                View v = mPages.get(position).view();
                container.addView(v);
                return v;
            }

            @Override
            public void destroyItem(ViewGroup container, int position, Object object) {
                container.removeView((View) object);
            }

            @Override
            public CharSequence getPageTitle(int position) {
                return mPages.get(position).title();
            }
        });
        // All four are cheap and hold live state; recreating them on every swipe
        // would drop scroll position and re-run every lookup.
        mPager.setOffscreenPageLimit(3);
        ((TabLayout) findViewById(R.id.tabs)).setupWithViewPager(mPager);

        mPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                renderCurrent();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChatHub.setForeground("");
        ChatHub.register(this);
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatHub.unregister(this);
        mHandler.removeCallbacks(mTick);
    }

    /** Only the visible page is rendered; the others refresh when swiped to. */
    private void renderCurrent() {
        renderPill();
        int i = mPager.getCurrentItem();
        if (i >= 0 && i < mPages.size()) {
            try {
                mPages.get(i).render();
            } catch (Exception e) {
                EventLog.add("ui render failed: " + e);
            }
        }
    }

    private void renderPill() {
        MaximaNode node = MaximaService.node();
        ChatEngine chat = MaximaService.chat();
        if (node == null) {
            mPill.setText("starting…");
            mPill.setTextColor(getResources().getColor(R.color.ux_subtext, getTheme()));
            return;
        }
        int hosts = node.pool().activeCount();
        int unread = chat == null ? 0 : chat.totalUnread();
        String text = hosts > 0
                ? "● " + hosts + " host" + (hosts == 1 ? "" : "s")
                : "○ offline";
        if (unread > 0) {
            text += "   " + unread + " new";
        }
        mPill.setText(text);
        mPill.setTextColor(getResources().getColor(
                hosts > 0 ? R.color.ux_success : R.color.ux_error, getTheme()));
    }

    public void toast(String zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_SHORT).show();
    }

    /** Jump to a tab by index, used when one screen sends you to another. */
    public void showTab(int zIndex) {
        mPager.setCurrentItem(zIndex, true);
    }

    /**
     * Android 13+ shows no chat notification at all without this, and fails
     * silently - the transport works and you simply never learn a message
     * arrived.
     */
    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ---- chat events, already on the main thread ----

    @Override
    public void onMessage(ChatEngine.Entry zEntry) {
        renderCurrent();
    }

    @Override
    public void onStateChanged(ChatEngine.Entry zEntry) {
        renderCurrent();
    }

    @Override
    public void onGroupChanged(Group zGroup) {
        renderCurrent();
    }
}
