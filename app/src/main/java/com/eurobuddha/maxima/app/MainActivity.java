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
    private View mDot;
    private android.animation.ObjectAnimator mPulse;
    private boolean mPulsing;
    private ViewPager mPager;
    private final List<Page> mPages = new ArrayList<>();

    private final LockGate mLock = new LockGate(this);

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

        // First run: no identity yet. Route to onboarding (create new vs restore
        // from seed) instead of silently minting one the user never sees. The
        // service is gated to match, so nothing starts until a seed is chosen.
        if (!SeedStore.hasIdentity(this)) {
            startActivity(new android.content.Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        // DEV-ONLY intent hooks below. MainActivity is the exported launcher,
        // so ANY installed app could otherwise start it with these extras and
        // silently rewrite our transport prefs (redirect classic hosts, flip
        // the engine). Gate the whole block on a debuggable build - stripped
        // entirely from release, where these are never needed.
        if (BuildConfig.DEBUG) {
        // DEV-ONLY classic-only experiment hook (no UI): set/clear via adb -
        //   am start -n .../.MainActivity --es classic_only_hosts "h1:9001,h2:9001"
        //   am start -n .../.MainActivity --es classic_only_hosts clear
        // Force-stop + relaunch afterwards so the service reseeds.
        String classicHosts = getIntent().getStringExtra("classic_only_hosts");
        if (classicHosts != null) {
            getSharedPreferences("maxima_relays", MODE_PRIVATE).edit()
                    .putString("classic_only_hosts",
                            "clear".equalsIgnoreCase(classicHosts.trim()) ? "" : classicHosts.trim())
                    .apply();
            android.widget.Toast.makeText(this, "classic_only_hosts = "
                    + ("clear".equalsIgnoreCase(classicHosts.trim()) ? "(cleared)" : classicHosts),
                    android.widget.Toast.LENGTH_LONG).show();
        }

        // DEV-ONLY jar-engine toggle (no UI): Parlons rides maxima.jar -
        //   am start -n .../.MainActivity --es engine_jar on|off
        // Force-stop + relaunch afterwards so the service reboots on it.
        String jarFlag = getIntent().getStringExtra("engine_jar");
        if (jarFlag != null) {
            boolean on = "on".equalsIgnoreCase(jarFlag.trim());
            getSharedPreferences("maxima_relays", MODE_PRIVATE).edit()
                    .putBoolean("engine_jar", on).apply();
            android.widget.Toast.makeText(this, "engine_jar = " + (on ? "ON (classic)" : "off"),
                    android.widget.Toast.LENGTH_LONG).show();
        }
        }   // end BuildConfig.DEBUG dev hooks

        // targetSdk 35 is edge-to-edge. Extend the dark app bar UP into the
        // status bar (so the header colour fills the top, WhatsApp-style, not a
        // pale strip), and inset the sides + bottom on the shell.
        View root = findViewById(R.id.root);
        final View appbar = findViewById(R.id.main_appbar);
        final int barTop = appbar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, 0, bars.right, bars.bottom);
            appbar.setPadding(appbar.getPaddingLeft(), barTop + bars.top,
                    appbar.getPaddingRight(), appbar.getPaddingBottom());
            return WindowInsetsCompat.CONSUMED;
        });
        getWindow().setStatusBarColor(getColor(R.color.ux_header));
        androidx.core.view.WindowInsetsControllerCompat wic =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), root);
        if (wic != null) {
            wic.setAppearanceLightStatusBars(false);
        }

        mPill = findViewById(R.id.status_pill);
        mDot = findViewById(R.id.status_dot);
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.main_version)).setText("v" + v);
        } catch (Exception ignored) {
        }
        findViewById(R.id.btn_main_search).setOnClickListener(v ->
                startActivity(new android.content.Intent(this,
                        com.eurobuddha.maxima.app.ui.SearchActivity.class)));
        // A three-dot icon promises a MENU - give it one instead of a tab jump.
        findViewById(R.id.btn_main_more).setOnClickListener(v -> {
            android.widget.PopupMenu m = new android.widget.PopupMenu(this, v);
            m.getMenu().add("New group");
            m.getMenu().add("Scan QR");
            m.getMenu().add("Search");
            m.getMenu().add("Settings");
            m.setOnMenuItemClickListener(item -> {
                CharSequence t = item.getTitle();
                if ("New group".contentEquals(t)) {
                    showTab(0);
                    for (Page pg : mPages) {
                        if (pg instanceof com.eurobuddha.maxima.app.ui.ChatsPage) {
                            ((com.eurobuddha.maxima.app.ui.ChatsPage) pg).newGroup();
                        }
                    }
                } else if ("Scan QR".contentEquals(t)) {
                    scanQr();
                } else if ("Search".contentEquals(t)) {
                    startActivity(new android.content.Intent(this,
                            com.eurobuddha.maxima.app.ui.SearchActivity.class));
                } else {
                    showTab(4);
                }
                return true;
            });
            m.show();
        });
        android.widget.ImageButton themeBtn = findViewById(R.id.btn_main_theme);
        themeBtn.setImageResource(ChatPrefs.appearanceIcon(this));
        themeBtn.setOnClickListener(v -> {
            ChatPrefs.cycleAppearance(this);
            toast("Theme: " + ChatPrefs.appearanceLabel(this));
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(ChatPrefs.nightMode(this));
        });

        Sha3Provider.install();
        requestNotificationPermission();
        MaximaService.start(this);

        LayoutInflater inf = LayoutInflater.from(this);
        mPages.add(new ChatsPage(this, inf.inflate(R.layout.page_chats, null)));
        mPages.add(new ContactsPage(this, inf.inflate(R.layout.page_contacts, null)));
        mPages.add(new com.eurobuddha.maxima.app.ui.WalletPage(this,
                inf.inflate(R.layout.page_wallet, null)));
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
        mPager.setOffscreenPageLimit(4);
        TabLayout tabs = findViewById(R.id.tabs);
        tabs.setupWithViewPager(mPager);
        // Settings lives in the overflow menu now - four tabs read better than
        // five. The page stays in the pager (menu + edge-swipe reach it), only
        // its tab is hidden. TabLayout REBUILDS its tab views whenever it
        // re-syncs with the pager (selecting a page does it), resurrecting the
        // hidden one - so the hide re-applies on every hierarchy change.
        final android.view.ViewGroup strip = (android.view.ViewGroup) tabs.getChildAt(0);
        final Runnable hideSettingsTab = () -> {
            if (strip.getChildCount() > 4) {
                View t = strip.getChildAt(4);
                if (t != null) {
                    // NOT setVisibility: TabView.update() (runs on selection)
                    // resets visibility internally and resurrects the tab.
                    // Layout params are ours alone - a zero-width, zero-weight
                    // tab is gone for good, and the other four fill the strip.
                    android.widget.LinearLayout.LayoutParams lp =
                            (android.widget.LinearLayout.LayoutParams) t.getLayoutParams();
                    if (lp.width != 0 || lp.weight != 0f) {
                        lp.width = 0;
                        lp.weight = 0f;
                        t.setLayoutParams(lp);
                        t.setMinimumWidth(0);
                        t.setPadding(0, 0, 0, 0);
                    }
                }
            }
        };
        hideSettingsTab.run();
        strip.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                strip.post(hideSettingsTab);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });

        mPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                renderCurrent();
            }
        });

        // If the app lock is on, cover the content until the user authenticates
        // (the prompt itself fires from onResume).
        mLock.onCreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChatHub.setForeground("");
        ChatHub.register(this);
        mHandler.post(mTick);
        mLock.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatHub.unregister(this);
        mHandler.removeCallbacks(mTick);
        stopPulse();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mLock.onStop();
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
        com.eurobuddha.maxima.app.jar.JarEngine jar = MaximaService.jar();
        ChatEngine chat = MaximaService.chat();
        if (node == null && jar == null) {
            setDotColour(R.color.ux_subtext);
            stopPulse();
            mPill.setText("starting…");
            mPill.setTextColor(getResources().getColor(R.color.ux_subtext, getTheme()));
            return;
        }
        int hosts = node != null ? node.pool().activeCount()
                : jar.connectedHosts().size();
        int unread = chat == null ? 0 : chat.totalUnread();
        // The dot carries the online/offline state now (and breathes when live),
        // so the label no longer needs a glyph.
        String text = hosts > 0
                ? hosts + " host" + (hosts == 1 ? "" : "s")
                : "offline";
        if (unread > 0) {
            text += "   " + unread + " new";
        }
        mPill.setText(text);
        mPill.setTextColor(getResources().getColor(
                hosts > 0 ? R.color.ux_success : R.color.ux_error, getTheme()));
        if (hosts > 0) {
            setDotColour(R.color.ux_success);
            startPulse();
        } else {
            setDotColour(R.color.ux_error);
            stopPulse();
        }
    }

    private void setDotColour(int zColorRes) {
        if (mDot == null || mDot.getBackground() == null) {
            return;
        }
        mDot.getBackground().mutate().setTint(getResources().getColor(zColorRes, getTheme()));
    }

    /**
     * A slow, subtle alpha "breath" on the status dot while connected - the
     * classic live-heartbeat cue. Guarded so the 2s render tick does not restart
     * (and stutter) an already-running animation.
     */
    private void startPulse() {
        if (mPulsing || mDot == null) {
            return;
        }
        if (mPulse == null) {
            mPulse = android.animation.ObjectAnimator.ofFloat(mDot, "alpha", 1f, 0.3f);
            mPulse.setDuration(950);
            mPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            mPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            mPulse.setInterpolator(
                    new android.view.animation.AccelerateDecelerateInterpolator());
        }
        mPulse.start();
        mPulsing = true;
    }

    private void stopPulse() {
        if (mPulse != null) {
            mPulse.cancel();
        }
        mPulsing = false;
        if (mDot != null) {
            mDot.setAlpha(1f);
        }
    }

    public void toast(String zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_SHORT).show();
    }

    /** A one-shot receiver for the next QR scan (e.g. the wallet Send sheet). */
    public interface ScanSink {
        void onScan(String zContents);
    }

    private ScanSink mScanSink;

    /** Launch the QR scanner; the result routes to ContactsPage.onScanned. */
    public void scanQr() {
        launchScanner();
    }

    /** Launch the scanner and deliver the NEXT result to {@code zSink} instead of
     *  ContactsPage — used by the wallet Send sheet to fill its address field. */
    public void scanQr(ScanSink zSink) {
        mScanSink = zSink;
        launchScanner();
    }

    private void launchScanner() {
        com.journeyapps.barcodescanner.ScanOptions o =
                new com.journeyapps.barcodescanner.ScanOptions();
        o.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE);
        o.setPrompt("Scan a Parlons QR");
        o.setBeepEnabled(false);
        o.setOrientationLocked(true);
        mScanLauncher.launch(o);
    }

    private final androidx.activity.result.ActivityResultLauncher<
            com.journeyapps.barcodescanner.ScanOptions> mScanLauncher =
            registerForActivityResult(new com.journeyapps.barcodescanner.ScanContract(), result -> {
                if (result == null || result.getContents() == null) {
                    mScanSink = null;
                    return;
                }
                if (mScanSink != null) {
                    ScanSink s = mScanSink;
                    mScanSink = null;
                    s.onScan(result.getContents());
                    return;
                }
                for (Page p : mPages) {
                    if (p instanceof com.eurobuddha.maxima.app.ui.ContactsPage) {
                        ((com.eurobuddha.maxima.app.ui.ContactsPage) p)
                                .onScanned(result.getContents());
                    }
                }
            });

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
