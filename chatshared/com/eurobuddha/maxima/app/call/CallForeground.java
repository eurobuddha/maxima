package com.eurobuddha.maxima.app.call;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;

/** Adds media types to the existing transport service only for a user-started call.
 * All methods run on the main thread, including promotion before media capture. */
public final class CallForeground {
    private final Service service;
    private final int notificationId;
    private boolean active, video;

    public CallForeground(Service service, int notificationId) {
        this.service = service;
        this.notificationId = notificationId;
    }

    public void start(boolean video, Notification notification) {
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || (video && service.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException("microphone or camera permission missing");
        }
        promote(notification, true, video);
        this.active = true;
        this.video = video;
    }

    public void stop(Notification notification) {
        promote(notification, false, false);
        active = false;
        video = false;
    }

    public void refresh(Notification notification) { promote(notification, active, video); }
    public boolean active() { return active; }

    static int types(int sdk, boolean active, boolean video) {
        int types = sdk >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        if (sdk >= 30 && active) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (video) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        return types;
    }

    private void promote(Notification notification, boolean active, boolean video) {
        if (Build.VERSION.SDK_INT >= 29) {
            service.startForeground(notificationId, notification, types(Build.VERSION.SDK_INT, active, video));
        } else {
            service.startForeground(notificationId, notification);
        }
    }
}
