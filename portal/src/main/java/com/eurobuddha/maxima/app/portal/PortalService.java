package com.eurobuddha.maxima.app.portal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The portal's foreground service — keeps this device LIVE on the cloud account's push channel
 * while the app is away from the screen, so messages notify instantly and incoming calls RING.
 * Mirrors the app's MaximaService role, but it holds no engine: just the connected
 * {@link ParlonsRemote}, its push listener, and a heartbeat so the node keeps pushing to us.
 */
public final class PortalService extends Service {

    private static final String CHANNEL = "parlons_cloud_svc";
    private ScheduledExecutorService mBeat;

    public static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, PortalService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    "Cloud connection", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Keeps you connected to your cloud account");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_send)
                .setContentTitle("Parlons Cloud")
                .setContentText("Connected to your cloud account")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        startForeground(1, n);

        // Connect (reuses the shared remote — the push listener installs with every new
        // connection inside CloudSession.connect), then heartbeat so the node keeps this
        // device's live addresses fresh (LIVE window is 3 min server-side).
        CloudSession.connect(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
            }
            public void err(String m) {
            }
        });
        mBeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "portal-heartbeat");
            t.setDaemon(true);
            return t;
        });
        mBeat.scheduleWithFixedDelay(() -> {
            try {
                ParlonsRemote r = CloudSession.remoteOrNull();
                if (r != null) {
                    r.registerPush();
                    CloudSession.notePushAlive();   // acked → screens can relax their polls
                }
            } catch (Exception ignored) {
            }
        }, 10, 60, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15 caps some FGS types; a service that ignores the timeout is ANR'd — a
        // visible crash. Stop cleanly; opening the app (or a MainActivity resume) restarts us.
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (mBeat != null) {
            mBeat.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
