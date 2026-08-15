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
    private static final String PENDING = "pending";
    private static final String IPC_CHANNEL = "maxima_ipc";
    private static final int NOTIF_ID_PENDING = 4210;

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

        // A BroadcastReceiver gets NO trustworthy sender identity, so the caller
        // hands us its own package name and we cannot, at this layer, prove the
        // sender is who it claims. Two things close that gap:
        //
        //  1. The receiver is guarded by a SIGNATURE-level permission in the
        //     manifest, so only an app signed with OUR key can send here at all.
        //  2. We additionally require the CLAIMED package to be installed and
        //     signed by the same certificate as us. A claim naming a non-family
        //     app is rejected outright.
        //
        // Within the family (all apps share one release key) this reduces the
        // residual to one family app impersonating another - a trust boundary we
        // already accept. Opening the surface to arbitrary third-party apps
        // later needs a bound Service reading Binder.getCallingUid(); noted in
        // the IPC design doc.
        if (!sameSignature(context, pkg)) {
            Log.w(TAG, "rejecting IPC: " + pkg + " is not signed by our key");
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
            case MaximaApiMessages.ACTION_CONTACTS:
                handleContacts(context, intent, pkg, cls, reqId);
                return;
            case MaximaApiMessages.ACTION_MEDIA:
                handleMedia(context, intent, pkg, cls, reqId);
                return;
            default:
        }
    }

    // ---------------------------------------------------------------

    private void handleRegister(Context ctx, String pkg, String cls, String reqId) {
        // Registration records the caller; APPROVAL is a user action in the UI.
        // Auto-approving here would let any installed app send as us.
        boolean approved = isApproved(ctx, pkg);

        // Record the ask so the Settings screen can render an Approve button,
        // and tell the user something asked. Without this, approval was
        // unreachable: approve() existed but nothing ever surfaced a candidate.
        if (!approved && !pendingPackages(ctx).contains(pkg)) {
            Set<String> p = new HashSet<>(prefs(ctx).getStringSet(PENDING, new HashSet<>()));
            p.add(pkg);
            prefs(ctx).edit().putStringSet(PENDING, p).apply();
            notifyPendingRequest(ctx, pkg);
        }

        // A register from a family app is also the "wake the transport" signal —
        // a client can do nothing while node() is null, and it has no other way
        // to start us (the service is deliberately unexported).
        boolean starting = false;
        if (MaximaService.node() == null) {
            try {
                MaximaService.start(ctx);
                starting = true;
            } catch (Exception e) {
                Log.w(TAG, "could not start transport for " + pkg + ": " + e);
            }
        }

        Intent r = reply(pkg, cls, reqId);
        r.putExtra(MaximaApiMessages.EXTRA_ENABLED, approved);
        r.putExtra(MaximaApiMessages.EXTRA_RESULT,
                approved ? (starting ? "approved - transport starting" : "approved")
                         : "pending - approve in the Maxima app");
        ctx.sendBroadcast(r);
    }

    /** A heads-up that an app wants to use Maxima, tapping into the app. */
    private void notifyPendingRequest(Context ctx, String pkg) {
        try {
            android.app.NotificationManager nm =
                    ctx.getSystemService(android.app.NotificationManager.class);
            if (nm == null) {
                return;
            }
            if (nm.getNotificationChannel(IPC_CHANNEL) == null) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        IPC_CHANNEL, "App connections",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT));
            }
            CharSequence label = pkg;
            try {
                android.content.pm.ApplicationInfo ai =
                        ctx.getPackageManager().getApplicationInfo(pkg, 0);
                label = ctx.getPackageManager().getApplicationLabel(ai);
            } catch (Exception ignored) {
            }
            Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 0,
                    open, android.app.PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTIF_ID_PENDING, new android.app.Notification.Builder(ctx, IPC_CHANNEL)
                    .setSmallIcon(com.eurobuddha.maxima.app.R.drawable.ic_stat_maxima)
                    .setContentTitle(label + " wants to use Maxima")
                    .setContentText("Approve or deny in Settings → Connected apps")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception e) {
            Log.w(TAG, "pending notification failed: " + e);
        }
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
        // The transport's own subsystems are off limits to external callers -
        // otherwise an approved app could forge contacts, chat or MLS as the user.
        if (isReserved(application)) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR,
                    "application string is reserved by the transport");
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
        //
        // EXTRA_TO accepts three shapes, in decreasing order of resilience:
        //   0x<identity pubkey>  a CONTACT's key -> sendToContact (multi-address
        //                        failover, LAN-first) or, with EXTRA_RELIABLE,
        //                        sendReliable (outbox + retry across heartbeats)
        //   MAX#pub#mls          a permanent address -> resolved via MLS first
        //   Mx...@host:port      a raw routing address -> one shot, one address
        //
        // Result contract: statusName UNKNOWN means the relay HELD the message
        // for an offline peer (classic wire behaviour) - clients must render it
        // as sent-pending, never as a failure.
        final byte[] data = payload;
        final boolean reliable = intent.getBooleanExtra(MaximaApiMessages.EXTRA_RELIABLE, false);
        new Thread(() -> {
            Intent out = reply(pkg, cls, reqId);
            try {
                String dest = to.trim();

                // A bare identity key: use the contact book's addresses.
                if (dest.startsWith("0x") && dest.indexOf('@') < 0) {
                    com.eurobuddha.maxima.core.contacts.Contact c = node.contact(dest);
                    if (c == null) {
                        out.putExtra(MaximaApiMessages.EXTRA_ERROR, "not a contact: " + dest);
                        ctx.sendBroadcast(out);
                        return;
                    }
                    if (reliable) {
                        String msgid = node.sendReliable(c, application, data);
                        out.putExtra(MaximaApiMessages.EXTRA_RESULT, "queued");
                        out.putExtra(MaximaApiMessages.EXTRA_MSGID, msgid);
                        ctx.sendBroadcast(out);
                        return;
                    }
                    com.eurobuddha.maxima.core.MaximaSender.Result res =
                            node.sendToContact(c, application, data);
                    out.putExtra(MaximaApiMessages.EXTRA_RESULT, res.statusName);
                    if (!res.isOk()) {
                        out.putExtra(MaximaApiMessages.EXTRA_ERROR, res.statusName);
                    }
                    ctx.sendBroadcast(out);
                    return;
                }

                // A permanent MAX# address: resolve through the MLS first.
                if (dest.startsWith("MAX#")) {
                    dest = node.resolvePermanent(dest);
                }

                com.eurobuddha.maxima.core.MaximaSender.Result res =
                        node.sendRaw(dest, application, data);
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
        } else if (isReserved(application)) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR,
                    "application string is reserved by the transport");
        } else if (!isApproved(ctx, pkg)) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR, "not approved");
        } else if (!claimApplication(ctx, pkg, application)) {
            // First claim wins and is permanent; a later app cannot steal a
            // string another already owns and intercept its inbound traffic.
            r.putExtra(MaximaApiMessages.EXTRA_ERROR,
                    "application string already claimed by another app");
        } else {
            r.putExtra(MaximaApiMessages.EXTRA_RESULT, "subscribed");
        }
        ctx.sendBroadcast(r);
    }

    /**
     * Contact operations, so a client app does not need its own contact list.
     * There is exactly one contact book per identity, and it lives here.
     *
     * op = list | add | remove
     */
    private void handleContacts(Context ctx, Intent intent, String pkg, String cls, String reqId) {
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

        String op = intent.getStringExtra(MaximaApiMessages.EXTRA_OP);
        op = op == null ? "list" : op;

        try {
            switch (op) {
                case "list": {
                    // publickey \u001f name \u001f address, records separated by \u001e
                    StringBuilder sb = new StringBuilder();
                    for (com.eurobuddha.maxima.core.contacts.Contact c : node.contacts()) {
                        if (sb.length() > 0) {
                            sb.append('\u001e');
                        }
                        sb.append(c.publicKey).append('\u001f')
                                .append(c.name).append('\u001f')
                                .append(c.primaryAddress() == null ? "" : c.primaryAddress());
                    }
                    r.putExtra(MaximaApiMessages.EXTRA_CONTACTS, sb.toString());
                    r.putExtra(MaximaApiMessages.EXTRA_RESULT, "ok");
                    break;
                }
                case "add": {
                    String addr = intent.getStringExtra(MaximaApiMessages.EXTRA_TO);
                    if (addr == null || addr.isEmpty()) {
                        r.putExtra(MaximaApiMessages.EXTRA_ERROR, "missing address");
                        break;
                    }
                    final String a = addr;
                    new Thread(() -> {
                        Intent out = reply(pkg, cls, reqId);
                        try {
                            node.introduce(a, true);
                            out.putExtra(MaximaApiMessages.EXTRA_RESULT, "introduced");
                        } catch (Exception e) {
                            out.putExtra(MaximaApiMessages.EXTRA_ERROR, String.valueOf(e.getMessage()));
                        }
                        ctx.sendBroadcast(out);
                    }, "ipc-add-contact").start();
                    return;
                }
                case "remove": {
                    String key = intent.getStringExtra(MaximaApiMessages.EXTRA_PUBLICKEY);
                    if (key == null) {
                        r.putExtra(MaximaApiMessages.EXTRA_ERROR, "missing publickey");
                        break;
                    }
                    final String k = key;
                    new Thread(() -> {
                        Intent out = reply(pkg, cls, reqId);
                        out.putExtra(MaximaApiMessages.EXTRA_RESULT,
                                node.removeContact(k) ? "removed" : "not found");
                        ctx.sendBroadcast(out);
                    }, "ipc-remove-contact").start();
                    return;
                }
                default:
                    r.putExtra(MaximaApiMessages.EXTRA_ERROR, "unknown op: " + op);
            }
        } catch (Exception e) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR, String.valueOf(e.getMessage()));
        }
        ctx.sendBroadcast(r);
    }

    /**
     * Self-hosted media, so a client app never has to run its own relay or hold
     * the whole file in a broadcast.
     *
     *   op=put : EXTRA_DATA_URI -> encrypt+chunk+publish (own store + relay
     *            replicas) -> the manifest JSON in EXTRA_RESULT.
     *   op=get : EXTRA_DATA the manifest JSON -> fetch+verify+decrypt -> the
     *            bytes handed back as a content:// grant in EXTRA_DATA_URI.
     *
     * Encryption, chunking, replication and integrity are the transport's; the
     * app only ever sees its own plaintext bytes and an opaque manifest string.
     */
    private void handleMedia(Context ctx, Intent intent, String pkg, String cls, String reqId) {
        Intent r = reply(pkg, cls, reqId);
        if (!isApproved(ctx, pkg)) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR, "not approved");
            ctx.sendBroadcast(r);
            return;
        }
        final com.eurobuddha.maxima.core.media.MediaService media = MaximaService.media();
        if (media == null) {
            r.putExtra(MaximaApiMessages.EXTRA_ERROR, "transport not running");
            ctx.sendBroadcast(r);
            return;
        }
        final String op = intent.getStringExtra(MaximaApiMessages.EXTRA_OP);
        final String manifestJson = intent.getStringExtra(MaximaApiMessages.EXTRA_DATA);
        final android.net.Uri inUri = intent.getParcelableExtra(MaximaApiMessages.EXTRA_DATA_URI);
        final String mime = intent.getType() != null ? intent.getType()
                : "application/octet-stream";

        new Thread(() -> {
            Intent out = reply(pkg, cls, reqId);
            try {
                if ("get".equals(op)) {
                    com.eurobuddha.maxima.core.media.MediaManifest mf =
                            com.eurobuddha.maxima.core.media.MediaManifest.decode(manifestJson);
                    if (mf == null) {
                        out.putExtra(MaximaApiMessages.EXTRA_ERROR, "bad manifest");
                        ctx.sendBroadcast(out);
                        return;
                    }
                    byte[] bytes = media.fetch(mf);   // verified end to end
                    android.net.Uri uri = MaximaApiDelivery.stageFile(ctx, bytes,
                            "media-" + reqId);
                    ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    out.putExtra(MaximaApiMessages.EXTRA_DATA_URI, uri);
                    out.putExtra(MaximaApiMessages.EXTRA_DATA_LEN, bytes.length);
                    out.putExtra(MaximaApiMessages.EXTRA_RESULT, "ok");
                } else {   // put
                    byte[] plain = readUri(ctx, inUri);
                    if (plain == null) {
                        out.putExtra(MaximaApiMessages.EXTRA_ERROR, "no input");
                        ctx.sendBroadcast(out);
                        return;
                    }
                    com.eurobuddha.maxima.core.media.MediaManifest mf =
                            media.publish(plain, mime);
                    out.putExtra(MaximaApiMessages.EXTRA_RESULT, mf.encode());
                }
            } catch (Exception e) {
                out.putExtra(MaximaApiMessages.EXTRA_ERROR, String.valueOf(e.getMessage()));
            }
            ctx.sendBroadcast(out);
        }, "maxima-ipc-media").start();
    }

    private byte[] readUri(Context ctx, android.net.Uri uri) {
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
            return null;
        }
    }

    /** Every package the user has approved. */
    public static java.util.Set<String> approvedPackages(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(APPROVED, new HashSet<>()));
    }

    /**
     * Is zPkg installed AND signed by the same certificate as us?
     *
     * checkSignatures is the one call that works back to minSdk 28 and answers
     * exactly the question that matters: is this a member of our app family.
     */
    private boolean sameSignature(Context ctx, String zPkg) {
        if (zPkg.equals(ctx.getPackageName())) {
            return true;
        }
        try {
            return ctx.getPackageManager().checkSignatures(ctx.getPackageName(), zPkg)
                    == android.content.pm.PackageManager.SIGNATURE_MATCH;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Application strings the transport OWNS. An external app may never send or
     * subscribe on these: they are built from the node's own identity, so
     * letting a client use them would let it forge contact introductions, chat
     * messages or MLS entries AS THE USER.
     */
    static boolean isReserved(String zApplication) {
        if (zApplication == null) {
            return true;
        }
        String a = zApplication.trim();
        return a.equals(com.eurobuddha.maxima.core.contacts.ContactCtrl.APPLICATION)
                || a.equals(com.eurobuddha.maxima.core.chat.ChatMessage.APPLICATION)
                || a.equals(com.eurobuddha.maxima.core.rpc.RpcEnvelope.APPLICATION)
                || a.equals(com.eurobuddha.maxima.core.directory.MlsService.APP_SET)
                || a.equals(com.eurobuddha.maxima.core.directory.MlsService.APP_GET)
                || a.equals(com.eurobuddha.maxima.core.net.Probe.APPLICATION)
                // the media blob shelf is transport-internal; apps use ACTION_MEDIA
                || com.eurobuddha.maxima.core.media.MediaWire.isMediaApp(a);
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

    /** Packages that have asked to connect and await the user's verdict. */
    public static java.util.Set<String> pendingPackages(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(PENDING, new HashSet<>()));
    }

    public static void approve(Context ctx, String pkg) {
        Set<String> s = new HashSet<>(prefs(ctx).getStringSet(APPROVED, new HashSet<>()));
        s.add(pkg);
        Set<String> p = new HashSet<>(prefs(ctx).getStringSet(PENDING, new HashSet<>()));
        p.remove(pkg);
        prefs(ctx).edit().putStringSet(APPROVED, s).putStringSet(PENDING, p).apply();
    }

    /** Refuse a pending request (the app may ask again; it will re-appear). */
    public static void deny(Context ctx, String pkg) {
        Set<String> p = new HashSet<>(prefs(ctx).getStringSet(PENDING, new HashSet<>()));
        p.remove(pkg);
        prefs(ctx).edit().putStringSet(PENDING, p).apply();
    }

    public static void revoke(Context ctx, String pkg) {
        Set<String> s = new HashSet<>(prefs(ctx).getStringSet(APPROVED, new HashSet<>()));
        s.remove(pkg);
        prefs(ctx).edit().putStringSet(APPROVED, s).apply();
    }

    /** @return false if another app already owns the string. */
    private static boolean claimApplication(Context ctx, String pkg, String application) {
        String owner = prefs(ctx).getString("app:" + application, null);
        if (owner != null && !owner.equals(pkg)) {
            return false;
        }
        prefs(ctx).edit().putString("app:" + application, pkg).apply();
        return true;
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
