package com.eurobuddha.maxima.app;

import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

/**
 * The app-lock gate, shared by EVERY activity that shows private content —
 * MainActivity and ChatActivity both host one. Covers the window with a lock
 * overlay and prompts to authenticate, and (while the lock is enabled) sets
 * FLAG_SECURE so the app never leaks through screenshots or the recent-apps
 * thumbnail. Without this being on every launchable activity, a chat opened
 * straight from a notification would bypass the lock entirely.
 */
public final class LockGate {

    private final FragmentActivity mAct;
    private View mOverlay;
    private boolean mPrompting;

    public LockGate(FragmentActivity zActivity) {
        mAct = zActivity;
    }

    /** Call at the end of onCreate: apply FLAG_SECURE and cover if locked. */
    public void onCreate() {
        applySecure();
        if (AppLock.mustUnlock(mAct)) {
            show();
        }
    }

    /** Call in onResume: re-apply secure, re-lock if the grace elapsed, prompt. */
    public void onResume() {
        applySecure();
        AppLock.onForeground(mAct);
        if (AppLock.mustUnlock(mAct)) {
            show();
            prompt();
        } else {
            hide();
        }
    }

    /** Call in onStop: start the re-lock grace timer. */
    public void onStop() {
        AppLock.onBackground();
    }

    private void applySecure() {
        // FLAG_SECURE blacks out the app under screen mirroring/casting. Apply it only
        // when App Lock is on AND the user hasn't allowed screen sharing (Settings →
        // Privacy → "Allow screen sharing", default on so demos work out of the box).
        boolean secure = AppLock.isEnabled(mAct) && !ChatPrefs.allowScreenShare(mAct);
        if (secure) {
            mAct.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            mAct.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void prompt() {
        if (mPrompting) {
            return;
        }
        mPrompting = true;
        AppLock.authenticate(mAct, "Unlock Parlons", "Fingerprint or device PIN",
                new AppLock.Callback() {
                    public void onSuccess() {
                        mPrompting = false;
                        hide();
                    }

                    public void onError(String zMessage) {
                        mPrompting = false;
                        Toast.makeText(mAct, zMessage, Toast.LENGTH_SHORT).show();
                    }

                    public void onCancelled() {
                        mPrompting = false;
                    }
                });
    }

    private void show() {
        if (mOverlay != null) {
            return;
        }
        mOverlay = buildOverlay();
        mAct.getWindow().addContentView(mOverlay,
                new android.view.ViewGroup.LayoutParams(-1, -1));
    }

    private void hide() {
        if (mOverlay == null) {
            return;
        }
        android.view.ViewParent p = mOverlay.getParent();
        if (p instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) p).removeView(mOverlay);
        }
        mOverlay = null;
    }

    private View buildOverlay() {
        float d = mAct.getResources().getDisplayMetrics().density;
        android.widget.FrameLayout fl = new android.widget.FrameLayout(mAct);
        fl.setBackgroundColor(mAct.getColor(R.color.ux_bg));
        fl.setClickable(true);
        fl.setFocusable(true);
        android.widget.LinearLayout col = new android.widget.LinearLayout(mAct);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER);
        android.widget.ImageView icon = new android.widget.ImageView(mAct);
        icon.setImageResource(R.drawable.ic_lock);
        icon.setColorFilter(mAct.getColor(R.color.ux_subtext));
        col.addView(icon, new android.widget.LinearLayout.LayoutParams((int) (46 * d), (int) (46 * d)));
        android.widget.TextView t = new android.widget.TextView(mAct);
        t.setText("Parlons is locked");
        t.setTextSize(16);
        t.setTextColor(mAct.getColor(R.color.ux_text));
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, (int) (14 * d), 0, 0);
        col.addView(t);
        android.widget.TextView btn = new android.widget.TextView(mAct);
        btn.setText("Unlock");
        btn.setTextSize(14);
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setTextColor(mAct.getColor(R.color.ux_on_accent));
        btn.setBackgroundResource(R.drawable.btn_primary);
        btn.setPadding((int) (40 * d), (int) (12 * d), (int) (40 * d), (int) (12 * d));
        btn.setClickable(true);
        btn.setOnClickListener(v -> prompt());
        android.widget.LinearLayout.LayoutParams blp =
                new android.widget.LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = (int) (22 * d);
        col.addView(btn, blp);
        android.widget.FrameLayout.LayoutParams clp =
                new android.widget.FrameLayout.LayoutParams(-2, -2);
        clp.gravity = android.view.Gravity.CENTER;
        fl.addView(col, clp);
        return fl;
    }
}
