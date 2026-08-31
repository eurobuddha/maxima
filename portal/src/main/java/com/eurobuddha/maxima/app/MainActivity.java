package com.eurobuddha.maxima.app;

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

    private TextView mPill;
    private View mDot;
    private ViewPager mPager;
    private final List<Page> mPages = new ArrayList<>();
    private CloudContactsPage mContacts;
    private CloudNodePage mNode;

    // pill state, refreshed off-thread
    private volatile int mReach = -1;   // -1 unknown, 0 offline, 1 online
    private volatile boolean mPolling;
    private long mLastPoll;

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

        // Search runs server-side over the account. Theme cycles day/night. More = account menu.
        findViewById(R.id.btn_main_search).setOnClickListener(v ->
                startActivity(new Intent(this,
                        com.eurobuddha.maxima.app.portal.CloudSearchActivity.class)));
        findViewById(R.id.btn_main_theme).setOnClickListener(v -> cycleTheme());
        findViewById(R.id.btn_main_more).setOnClickListener(v -> {
            android.widget.PopupMenu m = new android.widget.PopupMenu(this, v);
            m.getMenu().add("Settings");
            m.getMenu().add("Set account name");
            m.getMenu().add("Copy account address");
            m.getMenu().add("Node status");
            m.getMenu().add("Unpair this device");
            m.setOnMenuItemClickListener(item -> {
                CharSequence t = item.getTitle();
                if ("Settings".contentEquals(t)) {
                    startActivity(new Intent(this,
                            com.eurobuddha.maxima.app.portal.CloudSettingsActivity.class));
                } else if ("Set account name".contentEquals(t)) {
                    showTab(3);
                    if (mNode != null) {
                        mNode.promptSetName();
                    }
                } else if ("Copy account address".contentEquals(t)) {
                    copyAccount();
                } else if ("Node status".contentEquals(t)) {
                    showTab(3);
                } else {
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
        tabs.setupWithViewPager(mPager);
        mPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            public void onPageSelected(int position) {
                renderCurrent();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
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
            mPill.setText("online");
            mPill.setTextColor(getColor(R.color.ux_success));
            setDot(R.color.ux_success);
        } else if (reach == 0) {
            mPill.setText("can't reach");
            mPill.setTextColor(getColor(R.color.ux_error));
            setDot(R.color.ux_error);
        } else {
            mPill.setText("connecting…");
            mPill.setTextColor(getColor(R.color.ux_on_header));
            setDot(R.color.ux_subtext);
        }
        long now = System.currentTimeMillis();
        if (!mPolling && now - mLastPoll > 4000) {
            mPolling = true;
            CloudSession.connect(this, new CloudSession.Cb() {
                public void ok(ParlonsRemote r) {
                    int res;
                    try {
                        JSONObject p = r.ping();
                        Object ok = p.get("ok");
                        res = (ok instanceof Boolean && (Boolean) ok) ? 1 : 0;
                    } catch (Exception e) {
                        res = 0;
                    }
                    final int fres = res;
                    runOnUiThread(() -> {
                        mReach = fres;
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
        toast(next == AppCompatDelegate.MODE_NIGHT_NO ? "Theme: Light"
                : next == AppCompatDelegate.MODE_NIGHT_YES ? "Theme: Dark" : "Theme: System");
    }

    private void copyAccount() {
        String a = CloudSession.account(this);
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("account", a));   // full, never truncated
        toast("Account address copied");
    }

    private void confirmUnpair() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Unpair this device?")
                .setMessage("This phone will stop driving your cloud account. Your account and its "
                        + "identity stay on the node — other devices keep working, and you can pair "
                        + "this one again with a fresh code.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Unpair", (d, w) -> {
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
        com.journeyapps.barcodescanner.ScanOptions o = new com.journeyapps.barcodescanner.ScanOptions();
        o.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE);
        o.setPrompt("Scan a contact's address QR");
        o.setBeepEnabled(false);
        o.setOrientationLocked(true);
        mScanLauncher.launch(o);
    }

    private final androidx.activity.result.ActivityResultLauncher<
            com.journeyapps.barcodescanner.ScanOptions> mScanLauncher =
            registerForActivityResult(new com.journeyapps.barcodescanner.ScanContract(), result -> {
                if (result == null || result.getContents() == null) {
                    return;
                }
                if (mContacts != null) {
                    showTab(1);
                    mContacts.onScanned(result.getContents());
                }
            });
}
