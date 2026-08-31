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

    /** Background lane: pill polls, heartbeats, non-foreground tabs. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "parlons-portal-io");
        t.setDaemon(true);
        return t;
    });

    /** Interactive lane: the FOREGROUND screen's fetches and sends. A slow Node-tab status call
     *  must never make the open chat feel sticky — that was the single-lane serialization. */
    private static final ExecutorService INTERACTIVE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "parlons-portal-fg");
        t.setDaemon(true);
        return t;
    });

    /** Last moment the push channel proved itself (event received / register acked). */
    private static volatile long sPushAliveMs;

    public static void notePushAlive() {
        sPushAliveMs = System.currentTimeMillis();
    }

    /** Push confirmed working recently → screens can relax their fallback polls. */
    public static boolean pushHealthy() {
        return System.currentTimeMillis() - sPushAliveMs < 2 * 60_000L;
    }

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

    /** Last-known page payload (raw JSON string) — tabs paint this instantly on a cold start
     *  instead of sitting blank for the 10–20s fleet-attach, then refresh live behind it. */
    public static String cached(Context c, String key) {
        return prefs(c).getString("cache_" + key, "");
    }

    public static void cache(Context c, String key, String json) {
        prefs(c).edit().putString("cache_" + key, json == null ? "" : json).apply();
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
        connectOn(IO, c, cb);
    }

    /** The foreground screen's lane — same connection, its own queue. */
    public static void connectInteractive(Context c, Cb cb) {
        connectOn(INTERACTIVE, c, cb);
    }

    private static void connectOn(ExecutorService lane, Context c, Cb cb) {
        final Context app = c.getApplicationContext();
        lane.execute(() -> {
            try {
                cb.ok(ensureRemote(app));
            } catch (Exception e) {
                cb.err(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    /** Bumped by reset(): an in-flight connect must not publish a remote for a DEAD session. */
    private static volatile int sGen;

    /** The warm-address cache key — scoped PER ACCOUNT. A global key let a stale address from
     *  a previous account pass the warm probe and silently drive the WRONG account. */
    private static String liveKey(Context c) {
        return "livemx_" + Integer.toHexString(account(c).hashCode());
    }

    /** Create-once (both lanes race here): connect with the WARM fast path — the last resolved
     *  live address is probed first, skipping the MLS resolve ladder on the happy path. */
    private static synchronized ParlonsRemote ensureRemote(Context app) throws Exception {
        ParlonsRemote r = sRemote;
        if (r != null) {
            return r;
        }
        final int gen = sGen;
        r = new ParlonsRemote(deviceId(app));
        try {
            r.connect(account(app), cached(app, liveKey(app)));
            // The push channel is part of a connection: install BEFORE publishing the remote, so
            // a failure here discards the remote instead of caching a deaf-but-heartbeating one.
            installPush(app, r);
        } catch (Exception e) {
            try { r.close(); } catch (Exception ignored) { }   // no leaked node threads/sockets
            throw e;
        }
        if (gen != sGen) {
            // reset() ran while we were connecting (re-pair, unpair) — this remote belongs to
            // a dead session and must not resurrect it.
            try { r.close(); } catch (Exception ignored) { }
            throw new IllegalStateException("connection was reset");
        }
        cache(app, liveKey(app), r.liveAddress());   // next cold start reconnects warm
        sRemote = r;
        return r;
    }

    /** Refresh the warm-address cache if the account moved mid-session (heartbeat calls this). */
    public static void noteLiveAddress(Context c, String zLive) {
        if (zLive != null && !zLive.isEmpty() && !zLive.equals(cached(c, liveKey(c)))) {
            cache(c, liveKey(c), zLive);
        }
    }

    public static ParlonsRemote remoteOrNull() {
        return sRemote;
    }

    private static volatile com.eurobuddha.maxima.core.media.MediaService sMedia;

    /**
     * The device-side media service: received photos / voice notes are fetched CHUNK BY CHUNK
     * over MediaWire from the manifest's sources (the always-on cloud node + its replica relays)
     * and cached in this device's own BlobStore — exactly how the phone app fetches media. Null
     * until the remote is connected.
     */
    public static com.eurobuddha.maxima.core.media.MediaService media(Context c) {
        ParlonsRemote r = sRemote;
        if (r == null) {
            return null;
        }
        if (sMedia == null) {
            synchronized (CloudSession.class) {
                if (sMedia == null) {
                    com.eurobuddha.maxima.core.store.BlobStore blobs =
                            new com.eurobuddha.maxima.core.store.BlobStore(
                                    new File(c.getFilesDir(), "media"), 256L * 1024 * 1024);
                    sMedia = new com.eurobuddha.maxima.core.media.MediaService(r.node(), blobs);
                }
            }
        }
        return sMedia;
    }

    /**
     * Install the device-side push handler on a remote: cloud events land here and fan out —
     * screens via {@link PortalHub}, notifications via {@link PortalNotifier}, call signals via
     * {@link PortalCalls}. Called for every NEW remote (from {@link #connect}); registering on
     * the remote's own node is per-connection, so no once-per-process flag.
     */
    public static void installPush(Context appCtx, ParlonsRemote r) {
        final Context app = appCtx.getApplicationContext();
        r.setPushListener(ev -> {
            notePushAlive();
            String type = String.valueOf(ev.get("type"));
            if ("message".equals(type)) {
                PortalNotifier.onPushedMessage(app, ev);
            } else if ("call".equals(type)) {
                PortalCalls.onPushedSignal(app, ev);
            } else if ("walletsent".equals(type) || "walletfail".equals(type)) {
                PortalNotifier.onWalletEvent(app, ev);
            }
            PortalHub.dispatch(ev);
        });
        IO.execute(() -> {
            try { r.registerPush(); } catch (Exception ignored) { }
        });
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

    /** Drop the connection AND everything cached from it (re-pair / unpair / account switch).
     *  Clearing the cache_* keys matters twice over: a stale warm address must never probe the
     *  old account, and a new account must never instant-paint the previous account's chats. */
    public static void reset(Context c) {
        sGen++;                       // an in-flight connect must not publish after this
        ParlonsRemote r = sRemote;
        sRemote = null;
        sMedia = null;                // bound to the OLD node — a new connection builds fresh
        if (r != null) {
            try { r.close(); } catch (Exception ignored) { }
        }
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        for (String key : p.getAll().keySet()) {
            if (key.startsWith("cache_")) {
                e.remove(key);
            }
        }
        e.apply();
    }
}
