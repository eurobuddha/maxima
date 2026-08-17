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
 * The app lock: require a fingerprint / face / device PIN to open Maxima.
 *
 * Adapted from FreezePeach's {@code BiometricUnlock}, but tuned for GATING the
 * app rather than protecting a passphrase — so it's a pure "is it you" check
 * (no CryptoObject, no stored secret) that accepts the device credential (PIN)
 * as well as a strong biometric. That means PIN-only phones can use it, and a
 * changed/removed biometric can never lock the user out of their own app: the
 * real secret (the seed) is already Keystore-encrypted, so this is a convenience
 * gate, not the vault.
 */
public final class AppLock {

    private static final String PREFS = "maxima_applock";
    private static final String K_ENABLED = "enabled";

    /** Set once per process when the user has unlocked; reset when re-locking. */
    private static volatile boolean sUnlocked;
    /** When the app went to background, for the re-lock grace period. */
    private static volatile long sBackgroundedAt;
    /** Re-lock after this long in the background. */
    private static final long RELOCK_AFTER_MS = 30_000;

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
        // Pre-30: device credential can't be combined here, so accept any
        // enrolled biometric; the OS PIN screen still backs face/fingerprint.
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
