package com.eurobuddha.maxima.app.portal;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.ui.Avatars;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One conversation of the cloud account. Same bubble chrome as Parlons, driven over
 * {@link ParlonsRemote#conversation(String)} + {@link ParlonsRemote#send(String, String)} — the
 * messages live on the node, this screen is a thin remote view of them. Media / voice / calls /
 * groups are Phase 2 (no cloud RPC yet); the composer here is text-only.
 */
public final class CloudChatActivity extends AppCompatActivity {

    public static final String EXTRA_PEER = "peer";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_GROUP = "group";

    private static final class Msg {
        String id;
        String sender;
        String body;
        boolean mine;
        long time;
        String state;
    }

    private String mPeer;
    private String mName;
    private boolean mGroup;
    private RecyclerView mList;
    private EditText mInput;
    private final List<Msg> mMsgs = new ArrayList<>();
    private final Adapter mAdapter = new Adapter();
    private volatile boolean mBusy;
    private volatile long mReadMark;      // newest inbound time we've told the node we read

    // ---- media (image + voice-note) state — same discipline as the app's ChatActivity ----
    private static final int PICK_PHOTO = 41;
    private static final int TAKE_PHOTO = 42;
    private android.net.Uri mCaptureUri;
    private android.media.MediaPlayer mAudioPlayer;
    private String mAudioPlayingId;
    private volatile int mAudioToken;
    private final android.os.Handler mAudioTicker =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private com.eurobuddha.maxima.app.chat.VoiceNote mActiveRecorder;

    /** Decoded chat images by message id — LruCache of DOWNSAMPLED bitmaps (heap/8 cap). */
    private final android.util.LruCache<String, android.graphics.Bitmap> mImageCache =
            new android.util.LruCache<String, android.graphics.Bitmap>(
                    (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String k, android.graphics.Bitmap b) {
                    return b.getByteCount();
                }
            };
    private final java.util.Set<String> mImageFetching =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** id → when the fetch failed; retried after 30s so a transient network blip isn't forever. */
    private final java.util.Map<String, Long> mImageFailed =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final SimpleDateFormat mHm = new SimpleDateFormat("HH:mm", Locale.UK);

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        public void run() {
            load();
            mHandler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mPeer = getIntent().getStringExtra(EXTRA_PEER);
        mName = getIntent().getStringExtra(EXTRA_NAME);
        mGroup = getIntent().getBooleanExtra(EXTRA_GROUP, false);
        if (mName == null || mName.isEmpty()) {
            mName = mPeer;
        }
        setContentView(R.layout.activity_chat);

        // Edge-to-edge insets (targetSdk 35): extend the dark app bar up into the status bar and
        // keep the composer above the nav bar / keyboard — mirrors the app's ChatActivity so the
        // header isn't under the clock and the Message field isn't hidden behind the nav bar.
        final View root = findViewById(R.id.chat_root);
        final View appbar = findViewById(R.id.chat_appbar);
        final View composer = findViewById(R.id.chat_composer);
        final int barTop = appbar.getPaddingTop();
        final int compBottom = composer.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, 0, bars.right, 0);
            appbar.setPadding(appbar.getPaddingLeft(), barTop + bars.top,
                    appbar.getPaddingRight(), appbar.getPaddingBottom());
            composer.setPadding(composer.getPaddingLeft(), composer.getPaddingTop(),
                    composer.getPaddingRight(), compBottom + Math.max(bars.bottom, ime.bottom));
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        getWindow().setStatusBarColor(getColor(R.color.ux_header));
        androidx.core.view.WindowInsetsControllerCompat wic =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), root);
        if (wic != null) {
            wic.setAppearanceLightStatusBars(false);
        }

        findViewById(R.id.btn_chat_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.chat_title)).setText(mName);
        TextView sub = findViewById(R.id.chat_subtitle);
        if (sub != null) {
            sub.setText("on your cloud account");
        }
        View avatar = findViewById(R.id.chat_avatar);
        if (avatar instanceof TextView) {
            Avatars.apply((TextView) avatar, mPeer, mName);
        }
        // Calls: WebRTC terminates on this device; signaling relays through the cloud account.
        View call = findViewById(R.id.btn_chat_call);
        if (call != null) {
            call.setOnClickListener(v -> startCall(false));
        }
        View video = findViewById(R.id.btn_chat_video);
        if (video != null) {
            video.setOnClickListener(v -> startCall(true));
        }
        hide(R.id.btn_chat_info);
        hide(R.id.btn_chat_emoji);
        View attach = findViewById(R.id.btn_chat_attach);
        if (attach != null) {
            attach.setVisibility(View.VISIBLE);
            attach.setOnClickListener(v -> attachSheet());
        }
        View camera = findViewById(R.id.btn_chat_camera);
        if (camera != null) {
            camera.setVisibility(View.VISIBLE);
            camera.setOnClickListener(v -> takePhoto());
        }

        mList = findViewById(R.id.messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        mList.setLayoutManager(lm);
        mList.setAdapter(mAdapter);

        mInput = findViewById(R.id.chat_input);
        final android.widget.ImageButton send = findViewById(R.id.btn_chat_send);
        // Send button shows a mic when the field is empty (tap to record a voice note) and a
        // send arrow once there's text — same affordance as Parlons.
        send.setImageResource(R.drawable.ic_mic);
        send.setOnClickListener(v -> {
            if (mInput.getText().toString().trim().isEmpty()) {
                startVoiceNote();
            } else {
                send();
            }
        });
        mInput.addTextChangedListener(new android.text.TextWatcher() {
            boolean hasText = false;
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) {
                boolean has = s.toString().trim().length() > 0;
                if (has != hasText) {
                    hasText = has;
                    send.setImageResource(has ? R.drawable.ic_send : R.drawable.ic_mic);
                }
            }
        });
    }

    /** Cloud push → instant refresh when the event is about THIS conversation. */
    private final PortalHub.Listener mPush = ev -> {
        String type = String.valueOf(ev.get("type"));
        if (!"message".equals(type) && !"state".equals(type)) {
            return;
        }
        String peer = String.valueOf(ev.get("peer"));
        if (mPeer != null && mPeer.equalsIgnoreCase(peer)) {
            runOnUiThread(this::load);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        PortalHub.setForeground(mPeer);
        PortalHub.add(mPush);
        PortalNotifier.clear(this, mPeer);
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        PortalHub.setForeground("");
        PortalHub.remove(mPush);
        mHandler.removeCallbacks(mTick);
        stopAudio();
    }

    private void startCall(boolean zVideo) {
        Intent i = new Intent(this, PortalCallActivity.class);
        i.putExtra(PortalCallActivity.EXTRA_PEER, mPeer);
        i.putExtra(PortalCallActivity.EXTRA_NAME, mName);
        i.putExtra(PortalCallActivity.EXTRA_OUTGOING, true);
        i.putExtra(PortalCallActivity.EXTRA_VIDEO, zVideo);
        startActivity(i);
    }

    private void hide(int id) {
        View v = findViewById(id);
        if (v != null) {
            v.setVisibility(View.GONE);
        }
    }

    private void load() {
        if (mBusy || mPeer == null) {
            return;
        }
        mBusy = true;
        CloudSession.connect(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                final List<Msg> got = new ArrayList<>();
                long newestIn = 0;
                try {
                    JSONObject res = r.conversation(mPeer);
                    JSONArray arr = (JSONArray) res.get("messages");
                    if (arr != null) {
                        for (Object o : arr) {
                            JSONObject m = (JSONObject) o;
                            Msg x = new Msg();
                            x.id = str(m, "id");
                            x.sender = str(m, "sender");
                            x.body = str(m, "body");
                            x.mine = bool(m, "mine");
                            x.time = lng(m, "time");
                            x.state = str(m, "state");
                            got.add(x);
                            if (!x.mine && x.time > newestIn) {
                                newestIn = x.time;
                            }
                        }
                    }
                    // The node returns entries in store order, not time order — a resent or
                    // late-receipted message would otherwise render above older ones.
                    got.sort((a, b1) -> Long.compare(a.time, b1.time));
                    // We're LOOKING at this conversation — mark it read on the account (clears
                    // the unread badge, sends the read receipt if allowed). Only when a NEW
                    // inbound appeared, so the 3s poll doesn't spam the node.
                    if (newestIn > mReadMark) {
                        mReadMark = newestIn;
                        try { r.markRead(mPeer); } catch (Exception ignored) { }
                    }
                } catch (Exception ignored) {
                }
                runOnUiThread(() -> {
                    mBusy = false;
                    // "New message?" by newest id, not list size — the server pages to the
                    // newest 100, so sizes stop changing once a thread is long.
                    String prevNewest = mMsgs.isEmpty() ? "" : mMsgs.get(mMsgs.size() - 1).id;
                    String nowNewest = got.isEmpty() ? "" : got.get(got.size() - 1).id;
                    boolean grew = !nowNewest.isEmpty() && !nowNewest.equals(prevNewest);
                    mMsgs.clear();
                    mMsgs.addAll(got);
                    mAdapter.notifyDataSetChanged();
                    View empty = findViewById(R.id.chat_empty);
                    if (empty != null) {
                        empty.setVisibility(mMsgs.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                    if (grew && !mMsgs.isEmpty()) {
                        mList.scrollToPosition(mMsgs.size() - 1);
                    }
                });
            }
            public void err(String m) {
                runOnUiThread(() -> mBusy = false);
            }
        });
    }

    private void send() {
        final String body = mInput.getText().toString().trim();
        if (body.isEmpty()) {
            return;
        }
        mInput.setText("");
        CloudSession.connect(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                boolean failed = false;
                try {
                    JSONObject res = r.send(mPeer, body);
                    Object ok = res.get("ok");
                    if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                        error = String.valueOf(res.get("error"));
                    } else {
                        failed = "failed".equalsIgnoreCase(str(res, "state"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                final boolean ffail = failed;
                runOnUiThread(() -> {
                    if (err != null) {
                        Toast.makeText(CloudChatActivity.this, "Send failed: " + err,
                                Toast.LENGTH_LONG).show();
                    } else if (ffail) {
                        // The node tried every address AND the directory heal — the peer is
                        // genuinely unreachable right now. Say so honestly; the node's resend
                        // heartbeat keeps retrying failed messages for 24h.
                        Toast.makeText(CloudChatActivity.this,
                                mName + " looks offline — your node will keep retrying. "
                                        + "Tap the ✗ to reconnect now.", Toast.LENGTH_LONG).show();
                    }
                    load();
                });
            }
            public void err(String m) {
                runOnUiThread(() -> Toast.makeText(CloudChatActivity.this,
                        "Send failed: " + m, Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Tapping a failed message: force the node to re-resolve the peer's address NOW, then let
     *  the resend heartbeat re-drive the failed entries with the fresh address. */
    private void offerReconnect() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Message not delivered")
                .setMessage("Your node couldn't reach " + mName + " at any known address. "
                        + "Reconnect asks their directory for their current address, then failed "
                        + "messages are re-sent automatically.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Reconnect", (d, w) -> {
                    Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show();
                    CloudSession.connect(this, new CloudSession.Cb() {
                        public void ok(ParlonsRemote r) {
                            String msg;
                            try {
                                JSONObject res = r.resolveContact(mPeer);
                                Object ok = res.get("ok");
                                if (ok instanceof Boolean && (Boolean) ok) {
                                    msg = bool(res, "updated")
                                            ? "Fresh address found — resending shortly"
                                            : "Address unchanged — they may just be offline";
                                } else {
                                    msg = String.valueOf(res.get("error"));
                                }
                            } catch (Exception e) {
                                msg = e.getMessage() == null ? e.toString() : e.getMessage();
                            }
                            final String fmsg = msg;
                            runOnUiThread(() -> Toast.makeText(CloudChatActivity.this,
                                    fmsg, Toast.LENGTH_LONG).show());
                        }
                        public void err(String m) {
                            runOnUiThread(() -> Toast.makeText(CloudChatActivity.this,
                                    m, Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .show();
    }

    private String stamp(long t) {
        return t > 0 ? mHm.format(new Date(t)) : "";
    }

    private void toast(String zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_SHORT).show();
    }

    // ==================================================================
    // Media — image + voice note, ported from the app's ChatActivity.
    // Receive: manifests are fetched chunk-by-chunk over MediaWire from
    // the ALWAYS-ON cloud node + its replicas, cached in this device's
    // BlobStore. Send: chunked upload to the node, which publishes the
    // blobs (they live on the VPS) and sends the media message.
    // ==================================================================

    /** Decode within a ~1280px bound so one photo can't blow the heap. */
    private static android.graphics.Bitmap decodeBounded(byte[] zRaw) {
        if (zRaw == null || zRaw.length == 0) {
            return null;
        }
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(zRaw, 0, zRaw.length, o);
        int sample = 1;
        int max = Math.max(o.outWidth, o.outHeight);
        while (max / sample > 1280) {
            sample *= 2;
        }
        android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options();
        d.inSampleSize = sample;
        return android.graphics.BitmapFactory.decodeByteArray(zRaw, 0, zRaw.length, d);
    }

    /** Fetch a media ref's raw bytes: embedded data: URI, or mx1 manifest via MediaService. */
    private byte[] fetchMediaBytes(String zRef) throws Exception {
        if (zRef.startsWith("data:")) {
            return android.util.Base64.decode(
                    zRef.substring(zRef.indexOf(',') + 1), android.util.Base64.DEFAULT);
        }
        com.eurobuddha.maxima.core.media.MediaService media = CloudSession.media(this);
        if (media == null) {
            throw new IllegalStateException("not connected");
        }
        com.eurobuddha.maxima.core.media.MediaManifest mf =
                com.eurobuddha.maxima.core.media.MediaManifest.decode(
                        new String(android.util.Base64.decode(
                                zRef.substring("mx1:".length()), android.util.Base64.URL_SAFE),
                                java.nio.charset.StandardCharsets.UTF_8));
        return media.fetch(mf);
    }

    /** Show the image for a media bubble: cache hit, else fetch+decode off-main. */
    private void bindImage(android.widget.ImageView view, String body, String id) {
        android.graphics.Bitmap cached = mImageCache.get(id);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setOnClickListener(v -> openImage(id));
            return;
        }
        view.setImageResource(R.drawable.ic_photo);
        view.setOnClickListener(null);
        Long failedAt = mImageFailed.get(id);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 30_000) {
            return;   // failed recently — no retry-storm; a fresh bind after 30s retries
        }
        if (!mImageFetching.add(id)) {
            return;
        }
        final String ref = com.eurobuddha.maxima.core.chat.ChatMedia.ref(body);
        new Thread(() -> {
            try {
                android.graphics.Bitmap bmp = decodeBounded(fetchMediaBytes(ref));
                if (bmp != null) {
                    mImageCache.put(id, bmp);
                    mImageFailed.remove(id);
                    runOnUiThread(() -> notifyRow(id));
                    return;
                }
                mImageFailed.put(id, System.currentTimeMillis());
            } catch (Exception e) {
                mImageFailed.put(id, System.currentTimeMillis());
            } finally {
                mImageFetching.remove(id);
            }
        }, "portal-image").start();
    }

    /** Rebind just the row holding this message id (falls back to a full refresh if unknown). */
    private void notifyRow(String zId) {
        for (int i = 0; i < mMsgs.size(); i++) {
            if (zId.equals(mMsgs.get(i).id)) {
                mAdapter.notifyItemChanged(i);
                return;
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    /** Full-screen in-app viewer: pinch-zoom, pan, double-tap, save, share. */
    private void openImage(String id) {
        final android.graphics.Bitmap b = mImageCache.get(id);
        if (b == null) {
            return;
        }
        final android.app.Dialog d = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        com.eurobuddha.maxima.app.chat.ZoomImageView z =
                new com.eurobuddha.maxima.app.chat.ZoomImageView(this);
        z.setImageBitmap(b);
        root.addView(z, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xB3000000);
        bar.setPadding(dp(10), dp(14), dp(14), dp(10));

        android.widget.ImageView close = new android.widget.ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(0xFFFFFFFF);
        close.setPadding(dp(8), dp(8), dp(8), dp(8));
        close.setOnClickListener(v -> d.dismiss());
        bar.addView(close, new android.widget.LinearLayout.LayoutParams(dp(40), dp(40)));

        View spacer = new View(this);
        bar.addView(spacer, new android.widget.LinearLayout.LayoutParams(0, 1, 1f));

        TextView save = new TextView(this);
        save.setText("Save");
        save.setTextColor(0xFFFFFFFF);
        save.setTextSize(15);
        save.setTypeface(null, android.graphics.Typeface.BOLD);
        save.setPadding(dp(14), dp(8), dp(14), dp(8));
        save.setOnClickListener(v -> saveImage(id));
        bar.addView(save);

        TextView share = new TextView(this);
        share.setText("Share");
        share.setTextColor(0xFFFFFFFF);
        share.setTextSize(15);
        share.setTypeface(null, android.graphics.Typeface.BOLD);
        share.setPadding(dp(14), dp(8), dp(14), dp(8));
        share.setOnClickListener(v -> shareImage(id));
        bar.addView(share);

        root.addView(bar, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
        z.setOnSingleTap(() -> bar.setVisibility(
                bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        d.setContentView(root);
        d.show();
    }

    private void saveImage(String id) {
        final android.graphics.Bitmap b = mImageCache.get(id);
        if (b == null) {
            return;
        }
        new Thread(() -> {
            try {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        "parlons-" + System.currentTimeMillis() + ".jpg");
                cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, os);
                }
                runOnUiThread(() -> toast("Saved to Photos"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Save failed"));
            }
        }, "portal-save-image").start();
    }

    private void shareImage(String id) {
        final android.graphics.Bitmap b = mImageCache.get(id);
        if (b == null) {
            return;
        }
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "maximapayloads");
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, "share.jpg");
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                    b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, os);
                }
                final android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, "com.eurobuddha.parlons.cloud.payloads", f);
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("image/jpeg");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Share photo"));
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Could not share image"));
            }
        }, "portal-share-image").start();
    }

    // ---- attach / camera / caption / send-photo ----

    private void attachSheet() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setItems(new CharSequence[]{"Photo library", "Voice note"}, (d, which) -> {
                    if (which == 0) {
                        pickPhoto();
                    } else {
                        startVoiceNote();
                    }
                })
                .show();
    }

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "Send photo"), PICK_PHOTO);
    }

    private void takePhoto() {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "maximapayloads");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "capture.jpg");
            mCaptureUri = androidx.core.content.FileProvider.getUriForFile(
                    this, "com.eurobuddha.parlons.cloud.payloads", f);
            Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, mCaptureUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            // OEM camera apps that don't self-grant get a SecurityException and the
            // capture silently returns nothing — grant the output uri explicitly.
            for (android.content.pm.ResolveInfo ri : getPackageManager()
                    .queryIntentActivities(i,
                            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)) {
                grantUriPermission(ri.activityInfo.packageName, mCaptureUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            startActivityForResult(i, TAKE_PHOTO);
        } catch (Exception e) {
            toast("No camera available");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == TAKE_PHOTO && res == RESULT_OK && mCaptureUri != null) {
            promptCaption(mCaptureUri);
            return;
        }
        if (req != PICK_PHOTO || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        promptCaption(data.getData());
    }

    private void promptCaption(final android.net.Uri uri) {
        new Thread(() -> {
            android.graphics.Bitmap preview = null;
            try {
                byte[] jpeg = readScaledJpeg(uri, 600);
                preview = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            } catch (Exception ignored) {
            }
            final android.graphics.Bitmap thumb = preview;
            runOnUiThread(() -> showCaptionDialog(uri, thumb));
        }, "portal-preview").start();
    }

    private void showCaptionDialog(final android.net.Uri uri, android.graphics.Bitmap thumb) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), 0);
        if (thumb != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(thumb);
            iv.setAdjustViewBounds(true);
            iv.setMaxHeight(dp(240));
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            iv.setLayoutParams(lp);
            box.addView(iv);
        }
        final EditText cap = new EditText(this);
        cap.setHint("Add a caption…");
        cap.setHintTextColor(getColor(R.color.ux_subtext));
        cap.setTextColor(getColor(R.color.ux_text));
        cap.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        cap.setMaxLines(4);
        box.addView(cap);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Send photo")
                .setView(box)
                .setPositiveButton("Send",
                        (d, w) -> sendPhoto(uri, cap.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendPhoto(final android.net.Uri uri, final String caption) {
        toast("Sending photo…");
        new Thread(() -> {
            try {
                byte[] jpeg = readScaledJpeg(uri, 1400);
                uploadMedia(jpeg, "image/jpeg", caption);
            } catch (Exception e) {
                runOnUiThread(() -> toast("Photo failed: " + e.getMessage()));
            }
        }, "portal-media").start();
    }

    /** Chunked upload → the node publishes and sends. Blocking; call off-main. */
    private void uploadMedia(byte[] zBytes, String zMime, String zCaption) {
        String error = null;
        try {
            com.eurobuddha.maxima.cloud.ParlonsRemote r = CloudSession.remoteOrNull();
            if (r == null) {
                error = "not connected to your account";
            } else {
                JSONObject res = r.sendMedia(mPeer, mGroup, zBytes, zMime, zCaption);
                Object ok = res.get("ok");
                if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                    error = String.valueOf(res.get("error"));
                }
            }
        } catch (Exception e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        final String err = error;
        runOnUiThread(() -> {
            if (err != null) {
                toast("Send failed: " + err);
            }
            load();
        });
    }

    /** Decode + downscale + re-encode a picked image to a modest JPEG (EXIF honoured). */
    private byte[] readScaledJpeg(android.net.Uri uri, int maxPx) throws Exception {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            byte[] all = readAll(in);
            android.graphics.BitmapFactory.decodeByteArray(all, 0, all.length, bounds);
            int sample = 1;
            int big = Math.max(bounds.outWidth, bounds.outHeight);
            while (big / sample > maxPx * 2) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            android.graphics.Bitmap bmp =
                    android.graphics.BitmapFactory.decodeByteArray(all, 0, all.length, o);
            if (bmp == null) {
                throw new Exception("could not read image");
            }
            bmp = applyExifOrientation(bmp, all);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bos);
            return bos.toByteArray();
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static android.graphics.Bitmap applyExifOrientation(
            android.graphics.Bitmap zBmp, byte[] zJpeg) {
        try {
            androidx.exifinterface.media.ExifInterface exif =
                    new androidx.exifinterface.media.ExifInterface(
                            new java.io.ByteArrayInputStream(zJpeg));
            int o = exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            android.graphics.Matrix m = new android.graphics.Matrix();
            switch (o) {
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90:
                    m.postRotate(90);
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180:
                    m.postRotate(180);
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270:
                    m.postRotate(270);
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    m.postScale(-1f, 1f);
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    m.postScale(1f, -1f);
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE:
                    m.postRotate(90);
                    m.postScale(-1f, 1f);
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE:
                    m.postRotate(270);
                    m.postScale(-1f, 1f);
                    break;
                default:
                    return zBmp;
            }
            return android.graphics.Bitmap.createBitmap(zBmp, 0, 0,
                    zBmp.getWidth(), zBmp.getHeight(), m, true);
        } catch (Exception e) {
            return zBmp;
        }
    }

    // ---- voice notes ----

    private void startVoiceNote() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 61);
            return;
        }
        recordVoiceDialog();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == 61 && grants.length > 0
                && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            recordVoiceDialog();
        }
    }

    private void recordVoiceDialog() {
        final com.eurobuddha.maxima.app.chat.VoiceNote rec =
                new com.eurobuddha.maxima.app.chat.VoiceNote(this);
        mActiveRecorder = rec;
        try {
            rec.start();
        } catch (Exception e) {
            toast("Microphone unavailable");
            return;
        }
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(18), dp(22), dp(6));
        final com.eurobuddha.maxima.app.chat.WaveformView wave =
                new com.eurobuddha.maxima.app.chat.WaveformView(this);
        wave.setInk(getColor(R.color.ux_accent));
        android.widget.LinearLayout.LayoutParams wlp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        wlp.bottomMargin = dp(10);
        box.addView(wave, wlp);
        android.widget.LinearLayout rowBox = new android.widget.LinearLayout(this);
        rowBox.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        rowBox.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(rowBox);
        final java.util.List<Integer> samples = new java.util.ArrayList<>();
        final TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextColor(0xFFE0524D);
        dot.setTextSize(16);
        final TextView timer = new TextView(this);
        timer.setTextSize(28);
        timer.setTypeface(android.graphics.Typeface.MONOSPACE);
        timer.setTextColor(getColor(R.color.ux_text));
        timer.setPadding(dp(14), 0, 0, 0);
        timer.setText("0:00");
        rowBox.addView(dot);
        rowBox.addView(timer);

        final androidx.appcompat.app.AlertDialog dlg =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Voice note")
                        .setView(box)
                        .setPositiveButton("Send", null)
                        .setNegativeButton("Cancel", null)
                        .create();
        dlg.setCanceledOnTouchOutside(false);
        dlg.show();

        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable sampler = new Runnable() {
            @Override
            public void run() {
                int a = rec.amplitude();
                samples.add(a);
                wave.append(a);
                if (rec.elapsedSeconds() < com.eurobuddha.maxima.app.chat.VoiceNote.MAX_SECONDS) {
                    h.postDelayed(this, 100);
                }
            }
        };
        h.post(sampler);
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                int sSec = rec.elapsedSeconds();
                dot.setAlpha(dot.getAlpha() > 0.5f ? 0.25f : 1f);
                if (sSec >= com.eurobuddha.maxima.app.chat.VoiceNote.MAX_SECONDS) {
                    timer.setText(fmtSecs(com.eurobuddha.maxima.app.chat.VoiceNote.MAX_SECONDS)
                            + "  max");
                    rec.stopQuiet();
                } else {
                    timer.setText(fmtSecs(sSec));
                    h.postDelayed(this, 500);
                }
            }
        };
        h.post(tick);

        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    h.removeCallbacksAndMessages(null);
                    rec.stopQuiet();
                    byte[] bytes;
                    int secs = rec.recordedSeconds();
                    try {
                        bytes = rec.bytes();
                    } catch (Exception e) {
                        toast("Recording failed");
                        dlg.dismiss();
                        return;
                    }
                    dlg.dismiss();
                    if (bytes == null || bytes.length == 0 || secs < 1) {
                        toast("Too short - hold on a moment longer");
                        return;
                    }
                    sendVoice(bytes, rec.mime(), secs,
                            com.eurobuddha.maxima.app.chat.WaveformView.encode(
                                    com.eurobuddha.maxima.app.chat.WaveformView.summarise(samples)));
                });
        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(v -> {
                    h.removeCallbacksAndMessages(null);
                    rec.cancel();
                    dlg.dismiss();
                });
        dlg.setOnDismissListener(d -> {
            h.removeCallbacksAndMessages(null);
            rec.stopQuiet();
            if (mActiveRecorder == rec) {
                mActiveRecorder = null;
            }
        });
    }

    private void sendVoice(final byte[] zBytes, final String zMime, final int zSecs,
                           final String zWave) {
        toast("Sending voice note…");
        new Thread(() -> {
            // Caption slot carries "duration|waveformhex" — the bubble draws the real
            // shape without decoding the audio; previews show only the duration.
            String cap = fmtSecs(zSecs) + (zWave == null || zWave.isEmpty() ? "" : "|" + zWave);
            uploadMedia(zBytes, zMime, cap);
        }, "portal-voice").start();
    }

    private void bindAudio(Holder h, Msg m) {
        boolean playing = m.id.equals(mAudioPlayingId) && mAudioPlayer != null;
        int ink = getColor(m.mine ? R.color.ux_bubble_out_text : R.color.ux_bubble_in_text);
        h.audioBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        h.audioBtn.setColorFilter(ink);
        h.audioTime.setTextColor(ink);
        String cap = com.eurobuddha.maxima.core.chat.ChatMedia.caption(m.body);
        String total = cap;
        int[] bars = null;
        int sep = cap.indexOf('|');
        if (sep >= 0) {
            total = cap.substring(0, sep);
            bars = com.eurobuddha.maxima.app.chat.WaveformView.decode(cap.substring(sep + 1));
        }
        if (bars == null) {
            bars = new int[com.eurobuddha.maxima.app.chat.WaveformView.BAR_COUNT];
            java.util.Arrays.fill(bars, 4);
        }
        h.audioBar.setInk(ink);
        h.audioBar.setBars(bars);
        if (playing) {
            int pos = mAudioPlayer.getCurrentPosition();
            int dur = Math.max(1, mAudioPlayer.getDuration());
            h.audioTime.setText(fmtSecs(pos / 1000) + " / " + fmtSecs(dur / 1000));
            h.audioBar.setProgress(pos / (float) dur);
        } else {
            h.audioTime.setText(total.isEmpty() ? "voice note" : total);
            h.audioBar.setProgress(0f);
        }
        h.audio.setOnClickListener(v -> toggleAudio(m));
    }

    private void toggleAudio(final Msg m) {
        if (m.id.equals(mAudioPlayingId)) {
            stopAudio();
            return;
        }
        stopAudio();
        final int token = ++mAudioToken;
        new Thread(() -> {
            final java.io.File f = audioCacheFile(m.id, m.body);
            android.media.MediaPlayer prepared = null;
            if (f != null) {
                try {
                    // prepare() is disk+codec work — on THIS thread, never main.
                    prepared = new android.media.MediaPlayer();
                    prepared.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                    prepared.setDataSource(f.getAbsolutePath());
                    prepared.prepare();
                } catch (Exception ex) {
                    try { prepared.release(); } catch (Exception ignored) { }
                    prepared = null;
                }
            }
            final android.media.MediaPlayer player = prepared;
            runOnUiThread(() -> {
                if (token != mAudioToken || isFinishing() || isDestroyed()) {
                    if (player != null) {
                        try { player.release(); } catch (Exception ignored) { }
                    }
                    return;
                }
                if (player == null) {
                    toast("Could not play voice note");
                    return;
                }
                try {
                    mAudioPlayer = player;
                    mAudioPlayer.setOnCompletionListener(mp -> stopAudio());
                    mAudioPlayer.start();
                    mAudioPlayingId = m.id;
                    audioTick();
                    notifyRow(m.id);
                } catch (Exception ex) {
                    toast("Playback failed");
                    stopAudio();
                }
            });
        }, "portal-audio").start();
    }

    private void stopAudio() {
        mAudioToken++;
        mAudioTicker.removeCallbacksAndMessages(null);
        if (mAudioPlayer != null) {
            try { mAudioPlayer.stop(); } catch (Exception ignored) { }
            try { mAudioPlayer.release(); } catch (Exception ignored) { }
            mAudioPlayer = null;
        }
        String was = mAudioPlayingId;
        if (was != null) {
            mAudioPlayingId = null;
            notifyRow(was);
        }
    }

    private void audioTick() {
        mAudioTicker.removeCallbacksAndMessages(null);
        mAudioTicker.postDelayed(() -> {
            if (mAudioPlayer == null || mAudioPlayingId == null) {
                return;
            }
            notifyRow(mAudioPlayingId);   // just the playing row — not the whole list at 3Hz
            audioTick();
        }, 300);
    }

    /** Decode a voice-note ref into a playable cache file, once per message id. */
    private java.io.File audioCacheFile(String zId, String zBody) {
        try {
            String ref = com.eurobuddha.maxima.core.chat.ChatMedia.ref(zBody);
            java.io.File dir = new java.io.File(getCacheDir(), "maximavoice");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir,
                    "vn_" + zId.replaceAll("[^A-Za-z0-9_-]", "_"));
            if (f.exists() && f.length() > 0) {
                return f;
            }
            byte[] raw = fetchMediaBytes(ref);
            // Temp + rename: a write that dies half-way must not leave a "valid-looking"
            // partial file that exists+length>0 trusts forever.
            java.io.File tmp = new java.io.File(dir, f.getName() + ".tmp");
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
                os.write(raw);
            }
            if (!tmp.renameTo(f)) {
                return tmp.exists() && tmp.length() > 0 ? tmp : null;
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmtSecs(int zSecs) {
        return (zSecs / 60) + ":" + String.format(Locale.UK, "%02d", zSecs % 60);
    }

    private int dp(int zDp) {
        return Math.round(zDp * getResources().getDisplayMetrics().density);
    }

    /** Delivery-state glyph — matches Parlons' ChatActivity.ticks(). */
    private static String ticks(String state) {
        if ("failed".equals(state)) return "✗";
        if ("read".equals(state)) return "✓✓";
        if ("delivered".equals(state)) return "✓✓";
        if ("sent".equals(state)) return "✓";
        return "⋯";
    }

    /** Tick colour — matches Parlons' ChatActivity.tickColour(). */
    private int tickColour(String state) {
        if ("failed".equals(state)) return getColor(R.color.ux_error);
        if ("read".equals(state)) return getColor(R.color.ux_tick_read);
        return getColor(R.color.ux_subtext);
    }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static long lng(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static boolean bool(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Boolean && (Boolean) v;
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_message, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            Msg m = mMsgs.get(position);
            h.row.setGravity(m.mine ? Gravity.END : Gravity.START);
            h.bubble.setBackgroundResource(m.mine ? R.drawable.bubble_out : R.drawable.bubble_in);
            int ink = getColor(m.mine ? R.color.ux_bubble_out_text : R.color.ux_bubble_in_text);
            h.sender.setVisibility(View.GONE);

            boolean media = com.eurobuddha.maxima.core.chat.ChatMedia.isMedia(m.body);
            String mime = media ? com.eurobuddha.maxima.core.chat.ChatMedia.mime(m.body) : "";
            if (media && mime.startsWith("audio")) {
                h.image.setVisibility(View.GONE);
                h.audio.setVisibility(View.VISIBLE);
                h.body.setVisibility(View.GONE);
                bindAudio(h, m);
            } else if (media) {
                h.audio.setVisibility(View.GONE);
                h.image.setVisibility(View.VISIBLE);
                String cap = com.eurobuddha.maxima.core.chat.ChatMedia.caption(m.body);
                h.body.setVisibility(cap.isEmpty() ? View.GONE : View.VISIBLE);
                h.body.setText(cap);
                h.body.setTextColor(ink);
                bindImage(h.image, m.body, m.id);
            } else {
                h.image.setVisibility(View.GONE);
                h.audio.setVisibility(View.GONE);
                h.body.setVisibility(View.VISIBLE);
                h.body.setText(m.body);
                h.body.setTextColor(ink);
            }

            String meta = stamp(m.time);
            if (m.mine) {
                meta = meta + "  " + ticks(m.state);
                h.meta.setText(meta);
                h.meta.setTextColor(tickColour(m.state));
            } else {
                h.meta.setText(meta);
                h.meta.setTextColor(getColor(R.color.ux_subtext));
            }
            // A failed (✗) message is tappable: reconnect to the peer now instead of waiting
            // for the node's retry heartbeat.
            if (m.mine && "failed".equals(m.state)) {
                h.bubble.setOnClickListener(v -> offerReconnect());
            } else {
                h.bubble.setOnClickListener(null);
                h.bubble.setClickable(false);
            }
        }

        @Override
        public int getItemCount() {
            return mMsgs.size();
        }
    }

    private static void gone(View root, int id) {
        View v = root.findViewById(id);
        if (v != null) {
            v.setVisibility(View.GONE);
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final android.widget.LinearLayout row;
        final android.widget.LinearLayout bubble;
        final TextView sender;
        final TextView body;
        final TextView meta;
        final android.widget.ImageView image;
        final View audio;
        final android.widget.ImageView audioBtn;
        final TextView audioTime;
        final com.eurobuddha.maxima.app.chat.WaveformView audioBar;

        Holder(View v) {
            super(v);
            row = v.findViewById(R.id.bubble_row);
            bubble = v.findViewById(R.id.bubble);
            sender = v.findViewById(R.id.bubble_sender);
            body = v.findViewById(R.id.bubble_body);
            meta = v.findViewById(R.id.bubble_meta);
            image = v.findViewById(R.id.bubble_image);
            audio = v.findViewById(R.id.bubble_audio);
            audioBtn = v.findViewById(R.id.bubble_audio_btn);
            audioTime = v.findViewById(R.id.bubble_audio_time);
            audioBar = v.findViewById(R.id.bubble_audio_bar);
        }
    }
}
