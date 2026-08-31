package com.eurobuddha.maxima.app.portal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Bring the push channel back after a reboot. The portal must stay live on the account's push
 * service so messages and ringing calls arrive without the app being opened — a reboot would
 * otherwise silence the device until the user next launches the app. Only starts when a device is
 * actually paired (nothing to keep alive otherwise).
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            if (CloudSession.isPaired(context)) {
                PortalService.start(context);
            }
        }
    }
}
