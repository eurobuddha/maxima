package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import android.content.SharedPreferences;

import com.eurobuddha.maxima.cloud.ParlonsRemote;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.server.RelayRuntime;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The portal's session: this device's key + the account it drives + one shared
 * {@link ParlonsRemote}. Every screen reads/writes through here — the portal holds NO chat
 * identity of its own; the account lives on the VPS node. Mirrors the role {@code MaximaService}
 * plays in the local app, but backed by a remote cloud account instead of a local node.
 */
public final class CloudSession {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "parlons-portal-io");
        t.setDaemon(true);
        return t;
    });

    private static volatile MaximaIdentity sDeviceId;
    private static volatile ParlonsRemote sRemote;

    private CloudSession() {
    }

    public static ExecutorService io() {
        return IO;
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("parlons_cloud", Context.MODE_PRIVATE);
    }

    public static String account(Context c) {
        return prefs(c).getString("account", "");
    }

    public static boolean isConfigured(Context c) {
        return !account(c).isEmpty();
    }

    public static boolean isPaired(Context c) {
        return prefs(c).getBoolean("paired", false);
    }

    public static void setAccount(Context c, String a) {
        prefs(c).edit().putString("account", a == null ? "" : a.trim()).apply();
    }

    public static void setPaired(Context c, boolean p) {
        prefs(c).edit().putBoolean("paired", p).apply();
    }

    /** This device's own identity (a key distinct from any account), persisted 0600. */
    public static MaximaIdentity deviceId(Context c) throws Exception {
        if (sDeviceId == null) {
            synchronized (CloudSession.class) {
                if (sDeviceId == null) {
                    RelayRuntime.Seed s = RelayRuntime.loadOrCreateSeed(
                            new File(c.getFilesDir(), "cloud").toPath());
                    sDeviceId = MaximaIdentity.fromPhrase(s.phrase);
                }
            }
        }
        return sDeviceId;
    }

    public interface Cb {
        void ok(ParlonsRemote r);
        void err(String message);
    }

    /**
     * Ensure a connected {@link ParlonsRemote} (attach to the fleet + resolve the account),
     * reusing the existing one. Runs off the UI thread; the callback fires on the IO thread, so
     * marshal UI updates with {@code runOnUiThread}.
     */
    public static void connect(Context c, Cb cb) {
        IO.execute(() -> {
            try {
                ParlonsRemote r = sRemote;
                if (r == null) {
                    r = new ParlonsRemote(deviceId(c));
                    r.connect(account(c));
                    sRemote = r;
                }
                cb.ok(r);
            } catch (Exception e) {
                cb.err(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    public static ParlonsRemote remoteOrNull() {
        return sRemote;
    }

    /** This device's pairing key (0x-hex, as the node stores it), or "" if not yet connected. */
    public static String deviceKeyOrEmpty(Context c) {
        ParlonsRemote r = sRemote;
        try {
            return r == null ? "" : r.deviceKey();
        } catch (Exception e) {
            return "";
        }
    }

    /** Drop the connection (e.g. on disconnect / re-pair). */
    public static void reset() {
        ParlonsRemote r = sRemote;
        sRemote = null;
        if (r != null) {
            try { r.close(); } catch (Exception ignored) { }
        }
    }
}
