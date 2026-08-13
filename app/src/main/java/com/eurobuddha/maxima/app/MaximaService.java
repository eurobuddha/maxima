package com.eurobuddha.maxima.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The always-on transport.
 *
 * Every choice here is dictated by what actually survives on Android, learned
 * the hard way elsewhere in this fleet:
 *
 *  - foregroundServiceType=specialUse, because dataSync is time-capped on
 *    Android 14/15 and dies overnight
 *  - work is driven by an exact allow-while-idle alarm, because
 *    Handler.postDelayed does not survive Doze
 *  - WorkManager is a resurrection belt, not the primary mechanism
 *  - startForeground can be refused; bail cleanly and let the belts retry
 *  - a ConnectivityManager callback re-dials on network change, because a
 *    cellular NAT will silently half-close an idle flow
 *
 * NOTE: this is the one part of the project that cannot be proven from a
 * laptop. Multi-hour behaviour under Doze, on a real handset, on a real
 * carrier, is untested.
 */
public final class MaximaService extends Service {

    public static final String TAG = "MaximaService";
    private static final String CHANNEL_ID = "maxima_transport";
    private static final int NOTIF_ID = 4242;

    public static final String ACTION_TICK = "com.eurobuddha.maxima.app.TICK";

    /** Relays to try. Deliberately several operators - never depend on one. */
    private static final List<String> DEFAULT_RELAYS = Arrays.asList(
            "eurobuddha.com:9001",
            "eurobuddha.com:8001",
            "34.105.180.174:9001",
            "168.138.15.32:9001");

    private static volatile MaximaNode sNode;
    private final AtomicBoolean mPumping = new AtomicBoolean(false);
    private Thread mPumpThread;
    private ConnectivityManager.NetworkCallback mNetCallback;

    public static MaximaNode node() {
        return sNode;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Sha3Provider.install();
        createChannel();

        MaximaIdentity id = SeedStore.loadOrCreateIdentity(this);
        sNode = new MaximaNode(id, "1.0.48", 3);
        sNode.setName(SeedStore.displayName(this));

        registerNetworkCallback();
        Log.i(TAG, "created, identity " + id.mxIdentity().substring(0, 24) + "...");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, buildNotification("Connecting..."));
        } catch (Exception e) {
            // Android can refuse the FGS (residual time budget, background
            // start restrictions). Do not crash - the alarm and WorkManager
            // will bring us back.
            Log.w(TAG, "startForeground refused: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Re-arm the heartbeat FIRST, so a crash below still leaves us scheduled.
        HeartbeatReceiver.schedule(this);
        MaximaWorker.enqueue(this);

        startPumping();
        return START_STICKY;
    }

    private void startPumping() {
        if (!mPumping.compareAndSet(false, true)) {
            return;
        }
        mPumpThread = new Thread(() -> {
            MaximaNode node = sNode;
            try {
                int attached = node.start(DEFAULT_RELAYS, 30000);
                updateNotification(attached + " relay(s) connected");
                Log.i(TAG, "attached to " + attached + " relays: " + node.myAddresses());

                long lastMaintain = System.currentTimeMillis();
                while (mPumping.get()) {
                    boolean any = false;
                    for (String hp : node.pool().activeHosts()) {
                        try {
                            any |= node.pump(hp, 1500);
                        } catch (Exception e) {
                            Log.w(TAG, "pump error on " + hp + ": " + e);
                        }
                    }
                    if (System.currentTimeMillis() - lastMaintain > 60_000) {
                        node.maintain(20000);
                        lastMaintain = System.currentTimeMillis();
                        updateNotification(node.pool().activeCount() + " relay(s) connected");
                    }
                    if (!any) {
                        Thread.sleep(200);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "pump loop died", e);
            }
        }, "maxima-pump");
        mPumpThread.setDaemon(true);
        mPumpThread.start();
    }

    /**
     * A mobile socket dies silently on handover. Re-dial rather than waiting for
     * a read timeout that may never come.
     */
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm == null) {
                return;
            }
            mNetCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "network available - reconciling relays");
                    MaximaNode n = sNode;
                    if (n != null) {
                        new Thread(() -> n.maintain(20000), "maxima-renet").start();
                    }
                }

                @Override
                public void onLost(Network network) {
                    Log.i(TAG, "network lost");
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(), mNetCallback);
        } catch (Exception e) {
            Log.w(TAG, "network callback unavailable: " + e);
        }
    }

    @Override
    public void onDestroy() {
        mPumping.set(false);
        if (mPumpThread != null) {
            mPumpThread.interrupt();
        }
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null && mNetCallback != null) {
                cm.unregisterNetworkCallback(mNetCallback);
            }
        } catch (Exception ignored) {
        }
        MaximaNode n = sNode;
        if (n != null) {
            n.stop();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Maxima transport",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String zText) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Maxima")
                .setContentText(zText)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String zText) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(zText));
            }
        } catch (Exception ignored) {
        }
    }

    public static void start(Context zContext) {
        Intent i = new Intent(zContext, MaximaService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            zContext.startForegroundService(i);
        } else {
            zContext.startService(i);
        }
    }
}
