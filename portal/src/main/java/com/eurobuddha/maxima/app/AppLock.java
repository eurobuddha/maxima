package com.eurobuddha.maxima.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

/**
 * The app lock for Parlons Cloud: require a fingerprint / face / device PIN to open the portal.
 *
 * Ported verbatim from the phone Parlons {@code AppLock} (screen-share pref folded in here so the
 * portal needs no ChatPrefs). A pure "is it you" gate — no CryptoObject, no stored secret — that
 * also accepts the device credential (PIN), so PIN-only phones work and a changed/removed biometric
 * can never lock the user out. The real secret (the account seed) lives on the VPS, not here, so
 * this is a convenience gate over a device that merely DRIVES the account, not the vault.
 */
public final class AppLock {

    private static final String PREFS = "parlons_cloud_applock";
    private static final String K_ENABLED = "enabled";
    private static final String K_ALLOW_SCREENSHARE = "allow_screenshare";

    /** Set once per process when the user has unlocked; reset when re-locking. */
    private static volatile boolean sUnlocked;
    /** When the app went to background, for the re-lock grace period. */
    private static volatile long sBackgroundedAt;
    /** Re-lock only after this long continuously in the background (5 min) — glancing at another
     *  app, sitting in a chat, or the screen briefly sleeping does NOT force a re-auth. Switching
     *  tabs never backgrounds the app, so it never prompts. */
    private static final long RELOCK_AFTER_MS = 300_000;

    public interface Callback {
        void onSuccess();

        void onError(String zMessage);

        void onCancelled();
    }

    private AppLock() {
    }

    private static int authenticators() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        }
        // Pre-30: device credential can't be combined here, so accept any enrolled biometric; the
        // OS PIN screen still backs face/fingerprint.
        return BiometricManager.Authenticators.BIOMETRIC_WEAK;
    }

    /** True when the device can actually authenticate the user somehow. */
    public static boolean isAvailable(Context zCtx) {
        return BiometricManager.from(zCtx).canAuthenticate(authenticators())
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static boolean isEnabled(Context zCtx) {
        return prefs(zCtx).getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(Context zCtx, boolean zOn) {
        prefs(zCtx).edit().putBoolean(K_ENABLED, zOn).apply();
        if (!zOn) {
            sUnlocked = true;   // lock off → treat as open
        }
    }

    /** Screen sharing allowed = FLAG_SECURE off even while the lock is on (default on so demos and
     *  screenshots work out of the box). */
    public static boolean allowScreenShare(Context zCtx) {
        return prefs(zCtx).getBoolean(K_ALLOW_SCREENSHARE, true);
    }

    public static void setAllowScreenShare(Context zCtx, boolean zAllow) {
        prefs(zCtx).edit().putBoolean(K_ALLOW_SCREENSHARE, zAllow).apply();
    }

    /** Whether the app must show its lock screen right now. */
    public static boolean mustUnlock(Context zCtx) {
        return isEnabled(zCtx) && !sUnlocked;
    }

    public static void markUnlocked() {
        sUnlocked = true;
    }

    /** Call from onStop: start the re-lock grace timer. */
    public static void onBackground() {
        sBackgroundedAt = android.os.SystemClock.elapsedRealtime();
    }

    /** Call from onResume: re-lock if we were away longer than the grace period. */
    public static void onForeground(Context zCtx) {
        if (isEnabled(zCtx) && sUnlocked && sBackgroundedAt > 0
                && android.os.SystemClock.elapsedRealtime() - sBackgroundedAt > RELOCK_AFTER_MS) {
            sUnlocked = false;
        }
    }

    /** Prompt the user to authenticate. On success the app is marked unlocked. */
    public static void authenticate(FragmentActivity zActivity, String zTitle, String zSubtitle,
                                    Callback zCallback) {
        BiometricPrompt.PromptInfo.Builder b = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(zTitle)
                .setSubtitle(zSubtitle)
                .setAllowedAuthenticators(authenticators());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // A negative button is required when DEVICE_CREDENTIAL isn't allowed.
            b.setNegativeButtonText("Cancel");
        }
        BiometricPrompt.PromptInfo info = b.build();

        Executor exec = ContextCompat.getMainExecutor(zActivity);
        BiometricPrompt bp = new BiometricPrompt(zActivity, exec,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        sUnlocked = true;
                        zCallback.onSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        if (isCancel(errorCode)) {
                            zCallback.onCancelled();
                        } else {
                            zCallback.onError(errString.toString());
                        }
                    }
                });
        try {
            bp.authenticate(info);
        } catch (Exception e) {
            zCallback.onError("Lock prompt failed: " + e.getMessage());
        }
    }

    private static boolean isCancel(int zErrorCode) {
        return zErrorCode == BiometricPrompt.ERROR_USER_CANCELED
                || zErrorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                || zErrorCode == BiometricPrompt.ERROR_CANCELED;
    }

    private static SharedPreferences prefs(Context zCtx) {
        return zCtx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
