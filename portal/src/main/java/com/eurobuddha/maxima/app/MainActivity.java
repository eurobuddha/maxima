package com.eurobuddha.maxima.app;

import com.eurobuddha.maxima.app.i18n.LanguageDialog;

import com.eurobuddha.maxima.app.i18n.Language;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.eurobuddha.maxima.app.portal.CloudContactsPage;
import com.eurobuddha.maxima.app.portal.CloudChatsPage;
import com.eurobuddha.maxima.app.portal.CloudNodePage;
import com.eurobuddha.maxima.app.portal.CloudSession;
import com.eurobuddha.maxima.app.portal.CloudWalletPage;
import com.eurobuddha.maxima.app.portal.OnboardingActivity;
import com.eurobuddha.maxima.app.ui.Page;
import com.eurobuddha.maxima.cloud.ParlonsRemote;
import com.google.android.material.tabs.TabLayout;

import org.minima.utils.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parlons Cloud portal — the shell. Same chrome as Parlons (dark app bar + live status pill +
 * ViewPager + tabs), but every tab is driven by the paired CLOUD account over
 * {@link CloudSession}'s shared {@link ParlonsRemote}, not a local node. Four tabs:
 * Chats · Contacts · Wallet(watch-only) · Node(the VPS superpowers). The current Parlons :app is
 * NOT touched — this is a separate app (applicationId com.eurobuddha.parlons.cloud).
 */
public final class MainActivity extends AppCompatActivity {
    private com.eurobuddha.maxima.app.ui.TabScroll mTabScroll;

    private TextView mPill;
    private View mDot;
    private ViewPager mPager;
    private LockGate mLock;
    private final List<Page> mPages = new ArrayList<>();
    private CloudContactsPage mContacts;
    private CloudNodePage mNode;

    // pill state, refreshed off-thread
    private volatile int mReach = -1;   // -1 unknown, 0 offline, 1 online
    private volatile int mHosts = -1;   // relays attached (from node.status)
    private volatile boolean mPolling;
    private long mLastPoll;
    private android.animation.ObjectAnimator mBreathe;   // the online "breathing" dot

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        public void run() {
            renderCurrent();
            mHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Not paired yet → onboarding (connect + pair to a cloud account).
        if (!CloudSession.isPaired(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        com.eurobuddha.maxima.app.ui.MainHeader.configure(this);

        // Keep this device LIVE on the account's push channel (instant messages, ringing calls).
        com.eurobuddha.maxima.app.portal.PortalService.start(this);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 71);
        }

        // Edge-to-edge: extend the dark app bar up into the status bar, inset the rest.
        View root = findViewById(R.id.root);
        final View appbar = findViewById(R.id.main_appbar);
        final int barTop = appbar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // The KEYBOARD is an inset too. Consuming only the system bars here left the
            // content full-height under an open keyboard, so a field at the bottom of a page
            // (Contacts → "Add a contact") was typed into blind. Pad by whichever is taller
            // and the pages' ScrollViews shrink above the keyboard (same as CloudChatActivity).
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, 0, bars.right, Math.max(bars.bottom, ime.bottom));
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
        // Fit the title to whatever width the icons + status pill leave it (a foldable's cover
        // display leaves ~110dp): step the size down from 22sp until "Parlons…" fits on ONE
        // line, never let it wrap or ellipsize. Re-run on every layout pass (rotation, fold).
        final TextView title = findViewById(R.id.main_title);
        fitTitle(title);
        title.addOnLayoutChangeListener((tv, l0, t0, r0, b0, ol, ot, or, ob) -> {
            if ((r0 - l0) != (or - ol)) fitTitle(title);
        });

        // Search runs server-side over the account. Theme cycles day/night. More = account menu.
        findViewById(R.id.btn_main_search).setOnClickListener(v ->
                startActivity(new Intent(this,
                        com.eurobuddha.maxima.app.portal.CloudSearchActivity.class)));
        findViewById(R.id.btn_main_theme).setOnClickListener(v -> cycleTheme());
        findViewById(R.id.btn_main_more).setOnClickListener(v -> {
            android.widget.PopupMenu m = new android.widget.PopupMenu(this, v);
            m.getMenu().add(0, 1, 1, Language.text(this, R.string.l10n_nav_settings));
            m.getMenu().add(0, 2, 2, Language.text(this, R.string.l10n_account_set_name));
            m.getMenu().add(0, 3, 3, Language.text(this, R.string.l10n_account_copy_address));
            m.getMenu().add(0, 4, 4, Language.text(this, R.string.l10n_account_node_status));
            m.getMenu().add(0, 5, 5, Language.text(this, R.string.l10n_account_unpair));
            m.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    startActivity(new Intent(this,
                            com.eurobuddha.maxima.app.portal.CloudSettingsActivity.class));
                } else if (item.getItemId() == 2) {
                    showTab(3);
                    if (mNode != null) {
                        mNode.promptSetName();
                    }
                } else if (item.getItemId() == 3) {
                    copyAccount();
                } else if (item.getItemId() == 4) {
                    showTab(3);
                } else if (item.getItemId() == 5) {
                    confirmUnpair();
                }
                return true;
            });
            m.show();
        });

        LayoutInflater inf = LayoutInflater.from(this);
        mContacts = new CloudContactsPage(this, inf.inflate(R.layout.page_contacts, null));
        mPages.add(new CloudChatsPage(this, inf.inflate(R.layout.page_chats, null)));
        mPages.add(mContacts);
        mPages.add(new CloudWalletPage(this, inf.inflate(R.layout.page_wallet, null)));
        mNode = new CloudNodePage(this, inf.inflate(R.layout.page_network, null));
        mPages.add(mNode);

        for (com.eurobuddha.maxima.app.ui.Page page : mPages) Language.apply(page.view());
        mPager = findViewById(R.id.pager);
        mPager.setAdapter(new PagerAdapter() {
            public int getCount() { return mPages.size(); }
            public boolean isViewFromObject(View v, Object o) { return v == o; }
            public Object instantiateItem(ViewGroup container, int position) {
                View v = mPages.get(position).view();
                container.addView(v);
                return v;
            }
            public void destroyItem(ViewGroup container, int position, Object object) {
                container.removeView((View) object);
            }
            public CharSequence getPageTitle(int position) {
                return mPages.get(position).title();
            }
        });
        mPager.setOffscreenPageLimit(4);
        TabLayout tabs = findViewById(R.id.tabs);
        com.eurobuddha.maxima.app.ui.TabScroll.configureShell(tabs);
        tabs.setupWithViewPager(mPager);
        mTabScroll = com.eurobuddha.maxima.app.ui.TabScroll.attach(tabs, mPager,
                findViewById(R.id.tabScrollLeft), findViewById(R.id.tabScrollRight));
        mPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            public void onPageSelected(int position) {
                renderCurrent();
            }
        });

        mLock = new LockGate(this);
        mLock.onCreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mLock != null) {
            mLock.onResume();
        }
        // The connection outlives this activity (PortalService holds it): start from the last
        // known state so re-entering the app shows "online" at once, not "connecting…" for a
        // round-trip. A reconnect or a stale reading falls back to the honest unknown.
        int known = CloudSession.lastReach();
        if (known >= 0) {
            mReach = known;
            if (CloudSession.lastHosts() >= 0) mHosts = CloudSession.lastHosts();
        }
        // A reconnect (network change / dead heartbeat) flips the pill to "connecting…" at once
        // and re-polls immediately when it finishes, instead of waiting out the 4s cadence.
        CloudSession.setStateListener(() -> mHandler.post(() -> {
            mReach = -1;
            mLastPoll = 0;
            mPolling = false;
            renderPill();
        }));
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CloudSession.setStateListener(null);
        mHandler.removeCallbacks(mTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopBreathing();
        if (mLock != null) {
            mLock.onStop();
        }
    }

    private void renderCurrent() {
        renderPill();
        int i = mPager == null ? -1 : mPager.getCurrentItem();
        if (i >= 0 && i < mPages.size()) {
            try {
                mPages.get(i).render();
            } catch (Exception ignored) {
            }
        }
    }

    /** The pill reflects reachability of the cloud account, polled off-thread (a network RPC). */
    private void renderPill() {
        int reach = mReach;
        if (reach == 1) {
            int unread = CloudChatsPage.unreadTotal();
            int hosts = mHosts;
            String text;
            if (unread > 0) {
                text = Language.quantity(this, R.plurals.l10n_native_shell_unread, unread, unread);   // most useful thing to surface when it's non-zero
            } else if (hosts > 0) {
                text = Language.quantity(this, R.plurals.l10n_native_shell_online_hosts, hosts, hosts);
            } else {
                text = Language.text(this, R.string.l10n_presence_online);
            }
            Language.setText(mPill, text);
            mPill.setTextColor(getColor(R.color.ux_success));
            setDot(R.color.ux_success);
            startBreathing();
        } else if (reach == 0) {
            Language.setText(mPill, Language.msg(R.string.l10n_native_shell_unreachable));
            mPill.setTextColor(getColor(R.color.ux_error));
            setDot(R.color.ux_error);
            stopBreathing();
        } else {
            Language.setText(mPill, Language.msg(R.string.l10n_connection_connecting));
            mPill.setTextColor(getColor(R.color.ux_on_header));
            setDot(R.color.ux_subtext);
            stopBreathing();
        }
        long now = System.currentTimeMillis();
        if (!mPolling && now - mLastPoll > 4000) {
            mPolling = true;
            // node.status is one round-trip that gives BOTH reachability and the attached-host
            // count for the pill (ping only told us up/down). INTERACTIVE lane: the background
            // lane carries the pages' bulk fetches, and a pill queued behind them read as
            // "reconnecting" for 20-30s on every re-entry.
            CloudSession.connectInteractive(this, new CloudSession.Cb() {
                public void ok(ParlonsRemote r) {
                    int res, hosts = -1;
                    try {
                        JSONObject p = r.nodeStatus();
                        Object ok = p.get("ok");
                        res = (ok instanceof Boolean && (Boolean) ok) ? 1 : 0;
                        Object h = p.get("hosts");
                        if (h instanceof Number) {
                            hosts = ((Number) h).intValue();
                        }
                    } catch (Exception e) {
                        res = 0;
                    }
                    final int fres = res, fhosts = hosts;
                    CloudSession.noteReach(fres, fhosts);
                    runOnUiThread(() -> {
                        mReach = fres;
                        if (fhosts >= 0) {
                            mHosts = fhosts;
                        }
                        mLastPoll = System.currentTimeMillis();
                        mPolling = false;
                    });
                }
                public void err(String m) {
                    runOnUiThread(() -> {
                        mReach = 0;
                        mLastPoll = System.currentTimeMillis();
                        mPolling = false;
                    });
                }
            });
        }
    }

    /** A slow alpha pulse on the status dot while online — a subtle sign of life. */
    private void startBreathing() {
        if (mDot == null || (mBreathe != null && mBreathe.isRunning())) {
            return;
        }
        mBreathe = android.animation.ObjectAnimator.ofFloat(mDot, "alpha", 1f, 0.35f);
        mBreathe.setDuration(1400);
        mBreathe.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        mBreathe.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        mBreathe.start();
    }

    private void stopBreathing() {
        if (mBreathe != null) {
            mBreathe.cancel();
            mBreathe = null;
        }
        if (mDot != null) {
            mDot.setAlpha(1f);
        }
    }

    private void setDot(int colorRes) {
        if (mDot != null && mDot.getBackground() != null) {
            mDot.getBackground().mutate().setTint(getColor(colorRes));
        }
    }

    private void cycleTheme() {
        int cur = CloudSession.prefs(this).getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        int next = cur == AppCompatDelegate.MODE_NIGHT_NO
                ? AppCompatDelegate.MODE_NIGHT_YES
                : (cur == AppCompatDelegate.MODE_NIGHT_YES
                ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : AppCompatDelegate.MODE_NIGHT_NO);
        CloudSession.prefs(this).edit().putInt("night", next).apply();
        AppCompatDelegate.setDefaultNightMode(next);
        toast(Language.text(this, R.string.l10n_native_shell_theme, Language.text(this,
                next == AppCompatDelegate.MODE_NIGHT_NO ? R.string.l10n_theme_light_label
                : next == AppCompatDelegate.MODE_NIGHT_YES ? R.string.l10n_theme_dark_label : R.string.l10n_theme_system_label)));
    }

    private void copyAccount() {
        String a = CloudSession.account(this);
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("account", a));   // full, never truncated
        toast(Language.text(this, R.string.l10n_native_shell_address_copied));
    }

    private void confirmUnpair() {
        new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_native_shell_unpair_title))
                .setMessage(Language.msg(R.string.l10n_native_shell_unpair_help))
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_native_shell_unpair), (d, w) -> {
                    CloudSession.setPaired(this, false);
                    CloudSession.reset(this);
                    startActivity(new Intent(this, OnboardingActivity.class));
                    finish();
                })
                .show();
    }

    public void toast(String zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_SHORT).show();
    }

    public void showTab(int zIndex) {
        if (mPager != null) {
            mPager.setCurrentItem(zIndex, true);
        }
    }

    /** Launch the QR scanner; deliver the result to the Contacts add-field. */
    public void scanIntoContacts() {
        scan(Language.text(this, R.string.l10n_native_shell_scan_contact), null);
    }

    /** Launch the QR scanner and deliver the scanned text to a one-shot sink (e.g. the wallet
     *  send-to-address field). A null sink routes to the Contacts add-field. */
    public void scanTo(java.util.function.Consumer<String> sink) {
        scan(Language.text(this, R.string.l10n_native_shell_scan_address), sink);
    }

    private java.util.function.Consumer<String> mScanSink;

    private void scan(String prompt, java.util.function.Consumer<String> sink) {
        mScanSink = sink;
        com.journeyapps.barcodescanner.ScanOptions o = new com.journeyapps.barcodescanner.ScanOptions();
        o.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE);
        o.setPrompt(prompt);
        o.setBeepEnabled(false);
        o.setOrientationLocked(true);
        mScanLauncher.launch(o);
    }

    private final androidx.activity.result.ActivityResultLauncher<
            com.journeyapps.barcodescanner.ScanOptions> mScanLauncher =
            registerForActivityResult(new com.journeyapps.barcodescanner.ScanContract(), result -> {
                java.util.function.Consumer<String> sink = mScanSink;
                mScanSink = null;
                if (result == null || result.getContents() == null) {
                    return;
                }
                if (sink != null) {
                    sink.accept(result.getContents());
                } else if (mContacts != null) {
                    showTab(1);
                    mContacts.onScanned(result.getContents());
                }
            });

    /** Shrink the app-bar title (22sp → 9sp) until its single line fits the width it was given. */
    private static void fitTitle(final TextView zTitle) {
        zTitle.post(() -> {
            int avail = zTitle.getWidth() - zTitle.getPaddingLeft() - zTitle.getPaddingRight();
            if (avail <= 0) return;
            float density = zTitle.getResources().getDisplayMetrics().scaledDensity;
            CharSequence text = zTitle.getText();
            for (float sp = 22f; sp >= 9f; sp -= 1f) {
                zTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
                if (zTitle.getPaint().measureText(text, 0, text.length()) <= avail) break;
            }
        });
    }
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        com.eurobuddha.maxima.app.i18n.Language.configurationChanged(this);
        if (mPill != null) renderPill();
        TabLayout tabs = findViewById(R.id.tabs);
        if (tabs != null) for (int i = 0; i < mPages.size(); i++) {
            TabLayout.Tab tab = tabs.getTabAt(i);
            if (tab != null) tab.setText(mPages.get(i).title());
            mPages.get(i).languageChanged();
        }

        if (mTabScroll != null) mTabScroll.keepSelectedVisible();

    }

}
