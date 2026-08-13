package com.eurobuddha.maxima.app.ipc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.app.MaximaService;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Serves the outward IPC surface: other apps ask us to send and receive Maxima
 * traffic on their behalf.
 *
 * This is what makes the app "the comms layer for Minima Core" rather than just
 * another messenger.
 */
public final class MaximaApiReceiver extends BroadcastReceiver {

    private static final String TAG = "MaximaApi";
    private static final String PREFS = "maxima_api_clients";
    private static final String APPROVED = "approved";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        String pkg = intent.getStringExtra(MaximaApiMessages.EXTRA_PACKAGE);
        String cls = intent.getStringExtra(MaximaApiMessages.EXTRA_CLASS);
        String reqId = intent.getStringExtra(MaximaApiMessages.EXTRA_REQUEST_ID);

        if (pkg == null || cls == null) {
            return;
        }

        switch (action) {
            case MaximaApiMessages.ACTION_REGISTER:
                handleRegister(context, pkg, cls, reqId);
                return;
            case MaximaApiMessages.ACTION_IDENTITY:
                handleIdentity(context, pkg, cls, reqId);
                return;
            case MaximaApiMessages.ACTION_SEND:
                handleSend(context, intent, pkg, cls, reqId);
                return;
            case MaximaApiMessages.ACTION_SUBSCRIBE:
                handleSubscribe(context, intent, pkg, cls, reqId);
                return;
            default:
        }
    }

    // ---------------------------------------------------------------

    private void handleRegister(Context ctx, String pkg, String cls, String reqId) {
        // Registration records the caller; APPROVAL is a user action in the UI.
        // Auto-approving here would let any installed app send as us.
        boolean approved = isApproved(ctx, pkg);
        Intent r = reply(pkg, cls, reqId);
        r.putExtra(MaximaApiMessages.EXTRA_ENABLED, approved);
        r.putExtra(MaximaApiMessages.EXTRA_RESULT,
                approved ? "approved" : "pending - approve in the Maxima app");
        ctx.sendBroadcast(r);
    }

    private void handleIdentity(Context ctx, String pkg, String cls, String reqId) {
        Intent r = reply(pkg, cls, reqId);
        MaximaNode node = MaximaService.node();
        if (!isApproved(ctx, pkg) || node == null) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR,
                    node == null ? "transport not running" : "not approved");
        } else {
            r.putExtra(MaximaApiMessages.EXTRA_PUBLICKEY, node.identity().publicKeyHex());
            r.putExtra(MaximaApiMessages.EXTRA_ADDRESSES,
                    String.join(",", node.myAddresses()));
        }
        ctx.sendBroadcast(r);
    }

    private void handleSend(Context ctx, Intent intent, String pkg, String cls, String reqId) {
        Intent r = reply(pkg, cls, reqId);
        MaximaNode node = MaximaService.node();

        if (!isApproved(ctx, pkg)) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR, "not approved");
            ctx.sendBroadcast(r);
            return;
        }
        if (node == null) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR, "transport not running");
            ctx.sendBroadcast(r);
            return;
        }

        String to = intent.getStringExtra(MaximaApiMessages.EXTRA_TO);
        String application = intent.getStringExtra(MaximaApiMessages.EXTRA_APPLICATION);
        if (to == null || application == null) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR, "missing to/application");
            ctx.sendBroadcast(r);
            return;
        }
        // Namespacing: an app may only send on application strings it owns.
        if (!ownsApplication(ctx, pkg, application)) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR,
                    "application string not registered to " + pkg);
            ctx.sendBroadcast(r);
            return;
        }

        byte[] payload = readPayload(ctx, intent);
        if (payload == null) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR, "no payload");
            ctx.sendBroadcast(r);
            return;
        }

        // Off the broadcast thread - a send opens a socket.
        final byte[] data = payload;
        new Thread(() -> {
            Intent out = reply(pkg, cls, reqId);
            try {
                com.eurobuddha.maxima.core.MaximaSender.Result res =
                        node.sendRaw(to, application, data);
                out.putExtra(MaximaApiMessages.EXTRA_RESULT, res.statusName);
                if (!res.isOk()) {
                    out.putExtra(MaximaApiMessages.EXTRA_ERROR, res.statusName);
                }
            } catch (Exception e) {
                out.putExtra(MaximaApiMessages.EXTRA_ERROR, String.valueOf(e.getMessage()));
            }
            ctx.sendBroadcast(out);
        }, "maxima-ipc-send").start();
    }

    private void handleSubscribe(Context ctx, Intent intent, String pkg, String cls, String reqId) {
        Intent r = reply(pkg, cls, reqId);
        String application = intent.getStringExtra(MaximaApiMessages.EXTRA_APPLICATION);
        if (application == null || application.isEmpty()) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR, "missing application");
        } else if (!isApproved(ctx, pkg)) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR, "not approved");
        } else {
            claimApplication(ctx, pkg, application);
            r.putExtra(MaximaApiMessages.EXTRA_RESULT, "subscribed");
        }
        ctx.sendBroadcast(r);
    }

    // ---------------------------------------------------------------

    private byte[] readPayload(Context ctx, Intent intent) {
        String inline = intent.getStringExtra(MaximaApiMessages.EXTRA_DATA);
        if (inline != null) {
            return inline.startsWith("0x")
                    ? new MiniData(inline).getBytes()
                    : inline.getBytes(StandardCharsets.UTF_8);
        }
        android.net.Uri uri = intent.getParcelableExtra(MaximaApiMessages.EXTRA_DATA_URI);
        if (uri == null) {
            return null;
        }
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "could not read payload uri: " + e);
            return null;
        }
    }

    private static Intent reply(String pkg, String cls, String reqId) {
        Intent i = new Intent(MaximaApiMessages.ACTION_RESPONSE);
        i.setClassName(pkg, cls);
        i.setPackage(pkg);
        i.putExtra(MaximaApiMessages.EXTRA_REQUEST_ID, reqId);
        return i;
    }

    // ---- approval + namespacing ----

    public static boolean isApproved(Context ctx, String pkg) {
        return prefs(ctx).getStringSet(APPROVED, new HashSet<>()).contains(pkg);
    }

    public static void approve(Context ctx, String pkg) {
        Set<String> s = new HashSet<>(prefs(ctx).getStringSet(APPROVED, new HashSet<>()));
        s.add(pkg);
        prefs(ctx).edit().putStringSet(APPROVED, s).apply();
    }

    public static void revoke(Context ctx, String pkg) {
        Set<String> s = new HashSet<>(prefs(ctx).getStringSet(APPROVED, new HashSet<>()));
        s.remove(pkg);
        prefs(ctx).edit().putStringSet(APPROVED, s).apply();
    }

    private static void claimApplication(Context ctx, String pkg, String application) {
        prefs(ctx).edit().putString("app:" + application, pkg).apply();
    }

    private static boolean ownsApplication(Context ctx, String pkg, String application) {
        String owner = prefs(ctx).getString("app:" + application, null);
        return owner == null || owner.equals(pkg);
    }

    /** Who should receive an inbound message on this application string. */
    public static String ownerOf(Context ctx, String application) {
        return prefs(ctx).getString("app:" + application, null);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
