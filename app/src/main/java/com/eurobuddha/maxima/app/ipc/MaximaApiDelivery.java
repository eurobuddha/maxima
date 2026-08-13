package com.eurobuddha.maxima.app.ipc;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.msg.MaximaMessage;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Pushes inbound traffic OUT to the apps that asked for it.
 *
 * This is the half of the IPC surface that was declared and never implemented,
 * which made the whole thing send-only: a client app could ask us to send but
 * could never receive anything. With chat now being a pure IPC client, that
 * would have been fatal.
 *
 * Routing is by `application` string. An app claims one with SUBSCRIBE, and
 * only the claimant receives it - so one app cannot read another's traffic.
 */
public final class MaximaApiDelivery {

    private static final String TAG = "MaximaApiDelivery";

    private MaximaApiDelivery() {
    }

    /** Deliver one inbound message to whichever app owns its application string. */
    public static void deliver(Context zCtx, MaximaMessage zMsg, MiniData zMsgid) {
        String application = zMsg.mApplication.toString();
        String owner = MaximaApiReceiver.ownerOf(zCtx, application);
        if (owner == null) {
            // Nobody claimed it. Not an error - the transport app's own UI may
            // be the consumer, or it may simply be traffic we do not handle.
            return;
        }
        if (!MaximaApiReceiver.isApproved(zCtx, owner)) {
            Log.w(TAG, "dropping delivery to unapproved package " + owner);
            return;
        }

        Intent i = new Intent(MaximaApiMessages.ACTION_DELIVER);
        i.setPackage(owner);
        i.putExtra(MaximaApiMessages.EXTRA_APPLICATION, application);
        i.putExtra(MaximaApiMessages.EXTRA_FROM, zMsg.mFrom.to0xString());
        i.putExtra(MaximaApiMessages.EXTRA_MSGID, zMsgid.to0xString());
        i.putExtra(MaximaApiMessages.EXTRA_TIME, zMsg.mTimeMilli.getAsLong());

        byte[] data = zMsg.mData.getBytes();

        if (data.length <= MaximaApiMessages.MAX_INLINE) {
            i.putExtra(MaximaApiMessages.EXTRA_DATA, new MiniData(data).to0xString());
        } else {
            // A Maxima message can be 256KB. Putting that in a broadcast parcel
            // kills the RECEIVING app uncatchably - proven in this fleet - so
            // anything large goes over a content:// URI instead.
            Uri uri = writePayload(zCtx, zMsgid.to0xString(), data);
            if (uri == null) {
                Log.w(TAG, "could not stage a large payload; dropping");
                return;
            }
            i.putExtra(MaximaApiMessages.EXTRA_DATA_URI, uri);
            i.putExtra(MaximaApiMessages.EXTRA_DATA_LEN, data.length);
            zCtx.grantUriPermission(owner, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        zCtx.sendBroadcast(i);
    }

    /** Publish a contacts or hosts event to every approved app. */
    public static void event(Context zCtx, String zEvent, String zSubject, boolean zFlag) {
        for (String pkg : MaximaApiReceiver.approvedPackages(zCtx)) {
            Intent i = new Intent(MaximaApiMessages.ACTION_EVENT);
            i.setPackage(pkg);
            i.putExtra(MaximaApiMessages.EXTRA_EVENT, zEvent);
            if (MaximaApiMessages.EVENT_HOSTS.equals(zEvent)) {
                i.putExtra(MaximaApiMessages.EXTRA_HOST, zSubject);
                i.putExtra(MaximaApiMessages.EXTRA_CONNECTED, zFlag);
            } else {
                i.putExtra(MaximaApiMessages.EXTRA_PUBLICKEY, zSubject);
                i.putExtra(MaximaApiMessages.EXTRA_OP, zFlag ? "removed" : "updated");
            }
            zCtx.sendBroadcast(i);
        }
    }

    private static Uri writePayload(Context zCtx, String zId, byte[] zData) {
        try {
            File dir = new File(zCtx.getCacheDir(), "maximapayloads");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            prune(dir);
            File f = new File(dir, zId.replaceAll("[^A-Za-z0-9]", "") + ".bin");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(zData);
            }
            return FileProvider.getUriForFile(zCtx, MaximaApiMessages.AUTHORITY, f);
        } catch (Exception e) {
            Log.w(TAG, "payload staging failed: " + e);
            return null;
        }
    }

    /** Staged payloads are transient; do not let them accumulate forever. */
    private static void prune(File zDir) {
        File[] fs = zDir.listFiles();
        if (fs == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 5 * 60 * 1000L;
        for (File f : fs) {
            if (f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }
}
