package com.eurobuddha.maxima.app.portal;

import android.content.Intent;
import android.os.Bundle;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import com.eurobuddha.maxima.app.i18n.LanguageDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
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

import java.util.ArrayList;
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
        String sender = "";
        String sname = "";   // group sender display name (never null — bind reads .isEmpty())
        String body;
        boolean mine;
        long time;
        long arrived;      // late-relay dual clock
        int delivered;     // group per-member delivery count
        String state;
    }

    private String mPeer;
    private String mName;
    private com.eurobuddha.maxima.app.LockGate mLock;
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


    private int mPollN;                   // every Nth poll is a full reconcile, rest are deltas
    private long mNewestServerTime;       // newest SERVER entry time — the delta cursor
    private long mPushRevision;           // UI-thread guard against a pre-push full snapshot

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        public void run() {
            load();
            // Push healthy → the poll is only a reconciler; relax it. Push quiet → tighten.
            mHandler.postDelayed(this, CloudSession.pushHealthy() ? 10_000 : 3000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) {
            String cu = b.getString("captureUri");
            if (cu != null) {
                mCaptureUri = android.net.Uri.parse(cu);   // survive rotation during camera capture
            }
        }
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
            Language.setText(sub, Language.msg(R.string.l10n_android_chat_cloud_subtitle));
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
        View emoji = findViewById(R.id.btn_chat_emoji);
        if (emoji != null) {
            emoji.setVisibility(View.VISIBLE);
            emoji.setOnClickListener(v -> showEmojiPanel());
        }
        View info = findViewById(R.id.btn_chat_info);
        if (info != null) {
            info.setVisibility(View.VISIBLE);
            info.setOnClickListener(v -> detailsDialog());
        }
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
        final LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        mList.setLayoutManager(lm);
        mList.setAdapter(mAdapter);

        // Jump-to-latest FAB: shown once the user scrolls a few messages up from the bottom.
        final android.widget.ImageButton jumpFab = findViewById(R.id.btn_scroll_bottom);
        jumpFab.setOnClickListener(v -> {
            if (!mMsgs.isEmpty()) {
                mList.smoothScrollToPosition(mMsgs.size() - 1);
            }
            jumpFab.setVisibility(View.GONE);
        });
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                int last = lm.findLastVisibleItemPosition();
                boolean awayFromBottom = last >= 0 && last < mMsgs.size() - 3;
                jumpFab.setVisibility(awayFromBottom ? View.VISIBLE : View.GONE);
            }
        });

        // Instant paint: the last-known conversation renders NOW, the live fetch reconciles
        // behind it. Parsed OFF the main thread (media bodies make the JSON non-trivial), and
        // unconfirmed/failed echoes replay too — a ✗ bubble must survive reopening.
        final String cachedConv = CloudSession.cached(this, "conv_" + mPeer);
        final String cachedEcho = CloudSession.cached(this, "echo_" + mPeer);
        if (!cachedConv.isEmpty() || !cachedEcho.isEmpty()) {
            new Thread(() -> {
                final List<Msg> fromCache = new ArrayList<>();
                try {
                    if (!cachedConv.isEmpty()) {
                        Object o = new org.minima.utils.json.parser.JSONParser().parse(cachedConv);
                        if (o instanceof JSONObject) {
                            fromCache.addAll(parseMsgs((JSONObject) o));
                        }
                    }
                    if (!cachedEcho.isEmpty()) {
                        Object o = new org.minima.utils.json.parser.JSONParser().parse(cachedEcho);
                        if (o instanceof JSONObject) {
                            JSONArray arr = (JSONArray) ((JSONObject) o).get("echoes");
                            if (arr != null) {
                                for (Object eo : arr) {
                                    JSONObject j = (JSONObject) eo;
                                    Msg m = new Msg();
                                    m.id = str(j, "id");
                                    m.sender = "";
                                    m.body = str(j, "body");
                                    m.mine = true;
                                    m.time = lng(j, "time");
                                    m.state = str(j, "state");
                                    fromCache.add(m);
                                }
                            }
                        }
                    }
                    fromCache.sort((a, b1) -> Long.compare(a.time, b1.time));
                } catch (Exception ignored) {
                }
                runOnUiThread(() -> {
                    // Only if the live fetch hasn't already won the race.
                    if (isFinishing() || isDestroyed() || !mMsgs.isEmpty() || fromCache.isEmpty()) {
                        return;
                    }
                    mMsgs.addAll(fromCache);
                    for (Msg m : mMsgs) {
                        if (!m.id.startsWith("local:") && m.time > mNewestServerTime) {
                            mNewestServerTime = m.time;
                        }
                    }
                    mAdapter.notifyDataSetChanged();
                    mList.scrollToPosition(mMsgs.size() - 1);
                    View empty2 = findViewById(R.id.chat_empty);
                    if (empty2 != null) {
                        empty2.setVisibility(View.GONE);
                    }
                });
            }, "portal-conv-cache").start();
        }

        mInput = findViewById(R.id.chat_input);
        final android.widget.ImageButton send = findViewById(R.id.btn_chat_send);
        // Send button shows a mic when the field is empty (tap to record a voice note) and a
        // send arrow once there's text — same affordance as Parlons.
        send.setImageResource(R.drawable.ic_mic);
        Language.description(send, Language.msg(R.string.l10n_desktop_chat_voice_note));
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
                    Language.description(send, Language.msg(has ? R.string.l10n_chat_send : R.string.l10n_desktop_chat_voice_note));
                }
            }
        });

        mLock = new com.eurobuddha.maxima.app.LockGate(this);
        mLock.onCreate();   // FLAG_SECURE + cover a chat opened straight from a notification
    }

    /** Cloud push → apply the event DIRECTLY. The push already carries the message body and the
     *  state change; fetching the whole page again on every event was a wasted round-trip and
     *  the visible "waiting for the chat to re-print". */
    private final PortalHub.Listener mPush = this::onPush;

    private void onPush(JSONObject ev) {
        String type = String.valueOf(ev.get("type"));
        if ("payfail".equals(type)
                && mPeer != null && mPeer.equalsIgnoreCase(String.valueOf(ev.get("peer")))) {
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(this, tr(R.string.l10n_android_chat_pay_failed, SystemMessages.message(str(ev, "error"))),
                            Toast.LENGTH_LONG).show();
                    load();
                }
            });
            return;
        }
        if (!"message".equals(type) && !"state".equals(type)) {
            return;
        }
        String peer = String.valueOf(ev.get("peer"));
        if (mPeer == null || !mPeer.equalsIgnoreCase(peer)) {
            return;
        }
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if ("message".equals(type)) {
                Msg m = mergePushedMessage(mMsgs, ev);
                if (m == null) {
                    return;
                }
                mPushRevision++;
                if (m.time > mNewestServerTime) {
                    mNewestServerTime = m.time;
                }
                // An own-message push may also retire one optimistic bubble.
                if (m.mine) saveEchoes();
                mAdapter.notifyDataSetChanged();
                mList.scrollToPosition(mMsgs.size() - 1);
                View empty = findViewById(R.id.chat_empty);
                if (empty != null) {
                    empty.setVisibility(View.GONE);
                }
                if (!m.mine && m.time > mReadMark) {
                    final long prev = mReadMark;
                    mReadMark = m.time;
                    CloudSession.connect(this, new CloudSession.Cb() {   // background lane
                        public void ok(ParlonsRemote r) {
                            try {
                                r.markRead(mPeer);
                            } catch (Exception e2) {
                                mReadMark = prev;   // retried when the next inbound arrives
                            }
                        }
                        public void err(String e2) {
                            mReadMark = prev;
                        }
                    });
                }
            } else {
                // State tick for a known bubble: patch in place. Unknown id = the server-side
                // copy of one of OUR sends — reconcile with one cheap delta fetch.
                String id = str(ev, "id");
                for (Msg m : mMsgs) {
                    if (m.id.equals(id)) {
                        m.state = str(ev, "state");
                        notifyRow(id);
                        return;
                    }
                }
                load();
            }
        });
    }

    /** Apply the account's message event without Android UI or transport side effects. */
    private static Msg mergePushedMessage(List<Msg> messages, JSONObject ev) {
        String id = str(ev, "id");
        if (id.isEmpty()) return null;
        for (Msg existing : messages) {
            if (existing.id.equals(id)) return null;
        }
        Msg m = parseMsg(ev);
        // Older accounts omitted direction/state because they pushed only inbound messages.
        if (!m.mine && m.state.isEmpty()) m.state = "delivered";
        if (m.mine) {
            List<Msg> echoes = new ArrayList<>();
            for (Msg existing : messages) {
                if (existing.id.startsWith("local:")) echoes.add(existing);
            }
            // Reuse the poll reconciler's nearest, one-for-one matching. Never collapse
            // repeated identical sends or let a replay consume another pending send.
            consumeEchoes(echoes, java.util.Collections.singletonList(m), messages);
            messages.removeIf(existing -> existing.id.startsWith("local:") && !echoes.contains(existing));
        }
        messages.add(m);
        messages.sort((a, b) -> Long.compare(a.time, b.time));
        return m;
    }

    private boolean hasMsg(String zId) {
        for (Msg m : mMsgs) {
            if (m.id.equals(zId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mLock != null) {
            mLock.onResume();
        }
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

    @Override
    protected void onStop() {
        super.onStop();
        if (mLock != null) {
            mLock.onStop();
        }
    }

    @Override
    protected void onDestroy() {
        if (mFileUi != null) mFileUi.close();
        if (mPhotoViewer != null) mPhotoViewer.dismiss();
        super.onDestroy();
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

    /** Parse a conversation reply into time-sorted messages. */
    private List<Msg> parseMsgs(JSONObject res) {
        List<Msg> got = new ArrayList<>();
        JSONArray arr = (JSONArray) res.get("messages");
        if (arr != null) {
            for (Object o : arr) {
                got.add(parseMsg((JSONObject) o));
            }
        }
        // Store order isn't time order — a resent message would render above older ones.
        got.sort((a, b1) -> Long.compare(a.time, b1.time));
        return got;
    }

    /** Push and history use the same account message schema, including outgoing direction. */
    private static Msg parseMsg(JSONObject m) {
        Msg x = new Msg();
        x.id = str(m, "id");
        x.sender = str(m, "sender");
        x.sname = str(m, "sname");
        x.body = str(m, "body");
        x.mine = bool(m, "mine");
        x.time = lng(m, "time");
        x.arrived = lng(m, "arrived");
        x.delivered = (int) lng(m, "delivered");
        x.state = str(m, "state");
        return x;
    }

    private void load() {
        if (mBusy || mPeer == null) {
            return;
        }
        mBusy = true;
        // Full page only for the first load and every 6th poll (reconciliation); the rest are
        // tiny AFTER-cursor deltas. Push carries live messages + ticks, so the poll is backup.
        final boolean full = mMsgs.isEmpty() || mNewestServerTime == 0 || (mPollN % 6 == 0);
        mPollN++;
        final long cursor = mNewestServerTime;
        final long pushRevision = mPushRevision;
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                List<Msg> parsed = null;
                long newestIn = 0;
                try {
                    JSONObject res = full ? r.conversation(mPeer)
                            : r.conversationAfter(mPeer, cursor);
                    parsed = parseMsgs(res);
                    for (Msg x : parsed) {
                        if (!x.mine && x.time > newestIn) {
                            newestIn = x.time;
                        }
                    }
                    // We're LOOKING at this conversation — mark read only when NEW inbound
                    // appeared. Roll the mark back if the RPC fails, else it's never retried.
                    if (newestIn > mReadMark) {
                        final long prev = mReadMark;
                        mReadMark = newestIn;
                        try {
                            r.markRead(mPeer);
                        } catch (Exception e) {
                            mReadMark = prev;
                        }
                    }
                } catch (Exception ignored) {
                }
                final List<Msg> got = parsed;
                runOnUiThread(() -> {
                    mBusy = false;
                    if (got == null || isFinishing() || isDestroyed()) {
                        return;                     // fetch failed / screen gone — keep as-is
                    }
                    if (full && pushRevision != mPushRevision) {
                        // This snapshot may predate a push that already replaced its echo.
                        // Keep the live rows and ask for a fresh full page on the next tick.
                        mPollN = 0;
                        return;
                    }
                    String prevNewest = mMsgs.isEmpty() ? "" : mMsgs.get(mMsgs.size() - 1).id;
                    // Preserve unconfirmed local echoes: a send records ASYNC on the node, so a
                    // fetch can race it — the bubble must not blink out. One-to-one consumption:
                    // each server entry retires at most ONE echo, so duplicate same-text sends
                    // keep their own bubbles.
                    List<Msg> echoes = new ArrayList<>();
                    for (Msg m : mMsgs) {
                        if (m.id.startsWith("local:")) {
                            echoes.add(m);
                        }
                    }
                    consumeEchoes(echoes, got, mMsgs);
                    if (full) {
                        mMsgs.clear();
                        mMsgs.addAll(got);
                        mMsgs.addAll(echoes);
                        // Cursor from SERVER entries alone — recoverable after a restore/rollback
                        // (a never-lowered cursor made every later delta permanently empty).
                        mNewestServerTime = 0;
                        for (Msg m : got) {
                            if (m.time > mNewestServerTime) {
                                mNewestServerTime = m.time;
                            }
                        }
                        CloudSession.cache(CloudChatActivity.this, "conv_" + mPeer, capConv(got));
                    } else {
                        final List<Msg> keep = echoes;
                        mMsgs.removeIf(m -> m.id.startsWith("local:") && !keep.contains(m));
                        for (Msg m : got) {
                            if (!hasMsg(m.id)) {
                                mMsgs.add(m);
                            }
                        }
                        for (Msg m : got) {
                            if (m.time > mNewestServerTime) {
                                mNewestServerTime = m.time;
                            }
                        }
                    }
                    mMsgs.sort((a, b1) -> Long.compare(a.time, b1.time));
                    saveEchoes();
                    mAdapter.notifyDataSetChanged();
                    View empty = findViewById(R.id.chat_empty);
                    if (empty != null) {
                        empty.setVisibility(mMsgs.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                    String nowNewest = mMsgs.isEmpty() ? "" : mMsgs.get(mMsgs.size() - 1).id;
                    if (!nowNewest.isEmpty() && !nowNewest.equals(prevNewest)) {
                        mList.scrollToPosition(mMsgs.size() - 1);
                    }
                });
            }
            public void err(String m) {
                runOnUiThread(() -> mBusy = false);
            }
        });
    }

    /** One-to-one echo consumption: each SERVER entry retires at most ONE matching echo (the
     *  nearest in time within 2 min). Survivors stay in the list. */
    private static void consumeEchoes(List<Msg> zEchoes, List<Msg> zServer, List<Msg> zKnown) {
        for (Msg s : zServer) {
            if (!s.mine) {
                continue;
            }
            // A push or earlier poll already reconciled this server id. Seeing it again
            // must not consume the next pending send with identical text.
            boolean confirmed = false;
            for (Msg known : zKnown) {
                if (known.mine && known.id.equals(s.id)) { confirmed = true; break; }
            }
            if (confirmed) continue;
            Msg best = null;
            long bestD = 120_000L;
            for (Msg m : zEchoes) {
                if (!m.body.equals(s.body)) {
                    continue;
                }
                long d = Math.abs(s.time - m.time);
                if (d < bestD) {
                    bestD = d;
                    best = m;
                }
            }
            if (best != null) {
                zEchoes.remove(best);
            }
        }
    }

    /** Cap the cached page to the last 30 entries — media bodies are multi-KB manifests, and
     *  the whole prefs file lives in RAM and rewrites wholesale. */
    private static String capConv(List<Msg> got) {
        List<Msg> tail = got.size() > 30 ? got.subList(got.size() - 30, got.size()) : got;
        JSONArray arr = new JSONArray();
        for (Msg m : tail) {
            JSONObject o = new JSONObject();
            o.put("id", m.id);
            o.put("sender", m.sender);
            o.put("body", m.body);
            o.put("mine", m.mine);
            o.put("time", m.time);
            o.put("state", m.state);
            arr.add(o);
        }
        JSONObject res = new JSONObject();
        res.put("messages", arr);
        return res.toString();
    }

    /** Persist unconfirmed/failed echoes — a ✗ bubble is the only record a message never went;
     *  it must survive rotation and reopening. */
    private void saveEchoes() {
        JSONArray arr = new JSONArray();
        long now = System.currentTimeMillis();
        for (Msg m : mMsgs) {
            if (!m.id.startsWith("local:") || now - m.time > 24L * 3600_000) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("id", m.id);
            o.put("body", m.body);
            o.put("time", m.time);
            o.put("state", m.state);
            arr.add(o);
        }
        JSONObject box = new JSONObject();
        box.put("echoes", arr);
        CloudSession.cache(this, "echo_" + mPeer, arr.isEmpty() ? "" : box.toString());
    }

    private void send() {
        final String body = mInput.getText().toString().trim();
        if (body.isEmpty()) {
            return;
        }
        mInput.setText("");
        // Optimistic echo: the bubble appears the instant you tap — the round-trip through the
        // relays confirms it behind the scenes. The echo swaps for the server copy on the next
        // fetch/push, or turns ✗ if the RPC itself fails.
        final Msg echo = new Msg();
        echo.id = "local:" + java.util.UUID.randomUUID();
        echo.sender = "";
        echo.body = body;
        echo.mine = true;
        echo.time = System.currentTimeMillis();
        echo.state = "queued";
        mMsgs.add(echo);
        saveEchoes();
        mAdapter.notifyItemInserted(mMsgs.size() - 1);
        mList.scrollToPosition(mMsgs.size() - 1);
        View empty = findViewById(R.id.chat_empty);
        if (empty != null) {
            empty.setVisibility(View.GONE);
        }
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.send(mPeer, body);
                    Object ok = res.get("ok");
                    if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                runOnUiThread(() -> {
                    if (err != null) {
                        echo.state = "failed";
                        saveEchoes();
                        notifyRow(echo.id);
                        Toast.makeText(CloudChatActivity.this, tr(R.string.l10n_wallet_send_failed, SystemMessages.message(err)),
                                Toast.LENGTH_LONG).show();
                    } else {
                        // Accepted ("queued") — the real entry + its ticks arrive via
                        // push/delta and replace this echo. No full reload needed.
                        echo.state = "sent";
                        saveEchoes();
                        notifyRow(echo.id);
                    }
                });
            }
            public void err(String m) {
                runOnUiThread(() -> {
                    echo.state = "failed";
                    saveEchoes();
                    notifyRow(echo.id);
                    Toast.makeText(CloudChatActivity.this,
                            tr(R.string.l10n_wallet_send_failed, SystemMessages.message(m)), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Tapping a failed message: force the node to re-resolve the peer's address NOW, then let
     *  the resend heartbeat re-drive the failed entries with the fresh address. */
    private void offerReconnect() {
        new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_android_chat_not_delivered))
                .setMessage(Language.msg(R.string.l10n_android_chat_reconnect_help, mName))
                .setNegativeButton(Language.msg(R.string.l10n_android_chat_not_now), null)
                .setPositiveButton(Language.msg(R.string.l10n_android_chat_reconnect), (d, w) -> {
                    Toast.makeText(this, tr(R.string.l10n_android_chat_reconnecting), Toast.LENGTH_SHORT).show();
                    CloudSession.connect(this, new CloudSession.Cb() {
                        public void ok(ParlonsRemote r) {
                            String msg;
                            try {
                                JSONObject res = r.resolveContact(mPeer);
                                Object ok = res.get("ok");
                                if (ok instanceof Boolean && (Boolean) ok) {
                                    msg = bool(res, "updated")
                                            ? tr(R.string.l10n_android_chat_resend_soon)
                                            : tr(R.string.l10n_native_contacts_address_unchanged);
                                } else {
                                    msg = String.valueOf(res.get("error"));
                                }
                            } catch (Exception e) {
                                msg = e.getMessage() == null ? e.toString() : e.getMessage();
                            }
                            final String fmsg = msg;
                            runOnUiThread(() -> Toast.makeText(CloudChatActivity.this,
                                    SystemMessages.text(CloudChatActivity.this, fmsg), Toast.LENGTH_LONG).show());
                        }
                        public void err(String m) {
                            runOnUiThread(() -> Toast.makeText(CloudChatActivity.this,
                                    SystemMessages.text(CloudChatActivity.this, m), Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .show();
    }

    private String stamp(long t) {
        return t > 0 ? Language.date(t, "Hm") : "";
    }


    /** Selected-language calendar day for the date separator. */
    private String dayLabel(long t) {
        return t > 0 ? Language.dayLabel(this, t, false) : "";
    }

    /** Long-press a bubble → Copy / Copy tx id (payments) / Save+Share (photos) / Info. */
    private void bubbleMenu(Msg m) {
        java.util.List<Integer> items = new ArrayList<>();
        boolean pay = com.eurobuddha.maxima.core.chat.ChatPay.isPayment(m.body);
        boolean media = !pay && com.eurobuddha.maxima.core.chat.ChatMedia.isMedia(m.body);
        String mime = media ? com.eurobuddha.maxima.core.chat.ChatMedia.mime(m.body) : "";
        boolean img = media && !mime.startsWith("audio");
        boolean hasImg = img && mImageCache.get(m.id) != null;
        String text = pay ? com.eurobuddha.maxima.core.chat.ChatPay.preview(m.body)
                : media ? (mime.startsWith("audio/") ? "" : com.eurobuddha.maxima.core.chat.ChatMedia.caption(m.body)) : com.eurobuddha.maxima.core.chat.ChatContact.isCard(m.body)
                        ? com.eurobuddha.maxima.core.chat.ChatContact.address(m.body) : m.body;
        if (text != null && !text.isEmpty()) items.add(media ? R.string.l10n_chat_copy_caption : com.eurobuddha.maxima.core.chat.ChatContact.isCard(m.body) ? R.string.l10n_chat_copy_contact : R.string.l10n_chat_copy_message);
        if (pay) {
            items.add(R.string.l10n_desktop_chat_copy_tx);
        }
        if (hasImg) {
            items.add(R.string.l10n_android_chat_save_photo);
            items.add(R.string.l10n_android_chat_share_photo);
        }
        items.add(R.string.l10n_desktop_chat_info);
        new LanguageDialog(this)
                .actions(items, (d, which) -> {
                    int pick = items.get(which);
                    if (pick == R.string.l10n_chat_copy_contact || pick == R.string.l10n_chat_copy_message || pick == R.string.l10n_chat_copy_caption) {
                        copyText(text);
                    } else if (pick == R.string.l10n_desktop_chat_copy_tx) {
                        copyText(com.eurobuddha.maxima.core.chat.ChatPay.txid(m.body));
                    } else if (pick == R.string.l10n_android_chat_save_photo) {
                        saveImage(m.id);
                    } else if (pick == R.string.l10n_android_chat_share_photo) {
                        shareImage(m.id);
                    } else {
                        infoDialog(m);
                    }
                })
                .show();
    }

    private void infoDialog(Msg m) {
        Language.TextBuilder sb = new Language.TextBuilder();
        sb.append(m.mine ? Language.msg(R.string.l10n_notification_you) : (m.sname.isEmpty() ? mName : m.sname)).append('\n');
        sb.append(Language.stamp(m.time, "yMMMdHm")).append('\n');
        if (m.arrived > 0 && m.arrived - m.time >= 60_000L) {
            sb.append(Language.msg(R.string.l10n_desktop_chat_arrived_at, Language.stamp(m.arrived, "yMMMdHm"))).append('\n');
        }
        if (m.mine) {
            CharSequence st;
            switch (m.state == null ? "" : m.state) {
                case "read": st = Language.msg(R.string.l10n_android_chat_read); break;
                case "delivered": st = Language.msg(R.string.l10n_android_chat_delivered); break;
                case "sent": st = Language.msg(R.string.l10n_android_chat_sent_relay); break;
                case "failed": st = Language.msg(R.string.l10n_android_chat_failed_send); break;
                default: st = Language.msg(R.string.l10n_android_chat_sending);
            }
            sb.append(Language.msg(R.string.l10n_desktop_chat_status, st)).append('\n');
            if (mGroup && m.delivered > 0) {
                sb.append(Language.count(R.plurals.l10n_android_chat_delivered_members, m.delivered)).append("\n");
            }
        }
        if (com.eurobuddha.maxima.core.chat.ChatPay.isPayment(m.body)) {
            sb.append("\n").append(Language.msg(R.string.l10n_desktop_chat_transaction, com.eurobuddha.maxima.core.chat.ChatPay.txid(m.body)));
        }
        new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_android_chat_message_info))
                .setMessage(sb.build())
                .setPositiveButton(Language.msg(R.string.l10n_language_close), null)
                .show();
    }

    private void copyText(String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", s));   // full value
        toast(tr(R.string.l10n_common_copied));
    }

    private String tr(int resource, Object... arguments) { return Language.text(this, resource, arguments); }

    private void toast(CharSequence zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_SHORT).show();
    }

    // ---- emoji panel (data + layout ported verbatim from the app) ----
    private static final String[][] EMOJI = {
            {"Smileys", "😀 😂 🤣 😊 😇 😉 😍 🥰 😘 😜 🤪 🤔 🤐 😐 🙄 😬 😴 🥵 🥶 🤯 😳 🥺 😢 😭 😡 🤬 🤢 🥳 😎 🤓 🙈 🙉 🙊 💀 🤡 💩"},
            {"Gestures", "👍 👎 👌 ✌️ 🤞 🤟 🤘 👊 ✊ 👏 🙌 👐 🤲 🤝 🙏 💪 ☝️ 👆 👇 👈 👉 🖐️ 🤙 👋"},
            {"Hearts", "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💋 💌"},
            {"Animals & nature", "🐶 🐱 🐭 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐵 🐔 🐧 🦆 🦅 🦉 🦋 🐝 🐢 🐙 🐳 🐬 🌵 🌲 🌻 🌹 🍂 ☀️ 🌙 ⭐ 🌈 ⚡ 🔥 ❄️ 🌊"},
            {"Food & drink", "🍎 🍌 🍇 🍓 🍋 🥑 🍕 🍔 🍟 🌭 🌮 🍜 🍣 🍦 🍰 🍫 🍿 ☕ 🍺 🍷 🥂 🍵"},
            {"Objects & symbols", "🎉 🎁 🎈 ⚽ 🏀 🎸 🎮 🎲 🚗 ✈️ 🚀 ⛵ 🏠 💡 🔑 💰 💎 ⏰ 📱 💻 🎧 📷 ✅ ❌ ❗ ❓ 💯 🎖️ 🏆 🚩"},
    };

    private static int emojiLabel(String category) {
        switch (category) {
            case "Smileys": return R.string.l10n_emoji_smileys;
            case "Gestures": return R.string.l10n_emoji_gestures;
            case "Hearts": return R.string.l10n_emoji_hearts;
            case "Animals & nature": return R.string.l10n_emoji_nature;
            case "Food & drink": return R.string.l10n_emoji_food;
            case "Objects & symbols": return R.string.l10n_emoji_objects;
            default: throw new IllegalArgumentException("Unknown emoji category");
        }
    }

    private void showEmojiPanel() {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(12));
        scroll.addView(box);
        final android.app.Dialog dlg = new android.app.Dialog(this);
        for (String[] cat : EMOJI) {
            TextView label = new TextView(this);
            Language.setText(label, Language.upper(Language.msg(emojiLabel(cat[0]))));
            label.setTextSize(11);
            label.setTypeface(null, android.graphics.Typeface.BOLD);
            label.setTextColor(getColor(R.color.ux_subtext));
            label.setPadding(dp(8), dp(10), 0, dp(2));
            box.addView(label);
            android.widget.GridLayout grid = new android.widget.GridLayout(this);
            grid.setColumnCount(8);
            for (String e : cat[1].split(" ")) {
                TextView cell = new TextView(this);
                cell.setText(e);
                cell.setTextSize(26);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(6), dp(6), dp(6), dp(6));
                cell.setOnClickListener(v -> {
                    int at = Math.max(0, mInput.getSelectionStart());
                    mInput.getText().insert(at, e);
                });
                grid.addView(cell);
            }
            box.addView(grid);
        }
        dlg.setContentView(scroll);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dp(340));
            w.setBackgroundDrawableResource(R.color.ux_bg);
        }
        dlg.show();
    }

    // ---- details: 1:1 info, or group roster + admin Edit members ----
    private void detailsDialog() {
        if (mGroup) {
            groupDetails();
        } else {
            contactDetails();
        }
    }

    private void contactDetails() {
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                CharSequence text;
                try {
                    JSONObject i = r.contactInfo(mPeer);
                    Language.TextBuilder sb = new Language.TextBuilder();
                    sb.append(str(i, "name")).append("\n\n");
                    String kind = str(i, "kind");
                    sb.append(Language.msg(R.string.l10n_android_chat_software, bool(i, "classic") ? "Classic Maxima"
                            : kind.isEmpty() ? "Parlons" : kind)).append('\n');
                    long ls = lng(i, "lastSeen");
                    sb.append(Language.msg(R.string.l10n_android_chat_last_seen, ls > 0 ? Language.stamp(ls, "yMMMdHm")
                            : Language.msg(R.string.l10n_ios_never_seen))).append("\n\n");
                    sb.append(Language.msg(R.string.l10n_android_chat_public_key, str(i, "key"))).append("\n\n");
                    JSONArray addrs = (JSONArray) i.get("addresses");
                    if (addrs != null && !addrs.isEmpty()) {
                        sb.append(Language.msg(R.string.l10n_native_contacts_addresses)).append("\n");
                        for (Object o : addrs) {
                            sb.append(String.valueOf(o)).append('\n');
                        }
                    }
                    String wallet = str(i, "wallet");
                    if (!wallet.isEmpty()) {
                        sb.append("\n").append(Language.msg(R.string.l10n_android_chat_payment_address, wallet));
                    }
                    text = sb.build();
                } catch (Exception e) {
                    text = Language.msg(R.string.l10n_android_chat_load_failed, SystemMessages.message(e.getMessage()));
                }
                final CharSequence ft = text;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    new LanguageDialog(CloudChatActivity.this)
                            .setTitle(Language.msg(R.string.l10n_notification_contact))
                            .setMessage(ft)
                            .setPositiveButton(Language.msg(R.string.l10n_language_close), null)
                            .setNeutralButton(Language.msg(R.string.l10n_android_chat_copy_key), (d, w) ->
                                    copyText(mPeer))
                            .show();
                });
            }
            public void err(String m) {
                runOnUiThread(() -> toast(tr(R.string.l10n_android_chat_load_failed, SystemMessages.message(m))));
            }
        });
    }

    private void groupDetails() {
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                JSONObject info = null;
                String err = null;
                try {
                    info = r.groupInfo(mPeer);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final JSONObject fi = info;
                final String fe = err;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (fi == null || !bool(fi, "ok")) {
                        toast(tr(R.string.l10n_android_chat_load_group_failed, SystemMessages.message(fi == null ? fe : str(fi, "error"))));
                        return;
                    }
                    JSONArray members = (JSONArray) fi.get("members");
                    Language.TextBuilder sb = new Language.TextBuilder();
                    sb.append(str(fi, "name")).append("  (")
                            .append(members == null ? 0 : members.size()).append(")\n\n");
                    if (members != null) {
                        for (Object o : members) {
                            JSONObject mm = (JSONObject) o;
                            boolean meRow = bool(mm, "me");
                            String nm = str(mm, "name");
                            // The account isn't its own contact, so its name resolves to the raw
                            // key — show the translated "You" label instead.
                            sb.append("• ").append(meRow ? Language.msg(R.string.l10n_notification_you) : nm);
                            if (bool(mm, "admin")) {
                                sb.append("  (").append(Language.msg(R.string.l10n_chat_admin)).append(")");
                            }
                            sb.append('\n');
                        }
                    }
                    sb.append("\n").append(Language.msg(R.string.l10n_android_chat_no_group_key));
                    androidx.appcompat.app.AlertDialog.Builder b =
                            new LanguageDialog(CloudChatActivity.this)
                                    .setTitle(Language.msg(R.string.l10n_chat_group))
                                    .setMessage(sb.build())
                                    .setPositiveButton(Language.msg(R.string.l10n_language_close), null);
                    if (bool(fi, "iAmAdmin")) {
                        b.setNeutralButton(Language.msg(R.string.l10n_desktop_chat_edit_members), (d, w) -> editMembers(fi));
                    }
                    b.show();
                });
            }
            public void err(String m) {
                runOnUiThread(() -> toast(tr(R.string.l10n_android_chat_load_group_failed, SystemMessages.message(m))));
            }
        });
    }

    private void editMembers(JSONObject groupInfo) {
        // Fetch the account's full contact list; pre-check current members.
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                final List<String> keys = new ArrayList<>();
                final List<String> names = new ArrayList<>();
                final java.util.Set<String> current = new java.util.HashSet<>();
                try {
                    JSONArray mem = (JSONArray) groupInfo.get("members");
                    if (mem != null) {
                        for (Object o : mem) {
                            current.add(str((JSONObject) o, "key"));
                        }
                    }
                    JSONObject res = r.contacts();
                    JSONArray arr = (JSONArray) res.get("contacts");
                    java.util.Set<String> haveContact = new java.util.HashSet<>();
                    if (arr != null) {
                        for (Object o : arr) {
                            JSONObject cc = (JSONObject) o;
                            String k = str(cc, "key");
                            keys.add(k);
                            haveContact.add(k);
                            String nm = str(cc, "name");
                            names.add(nm.isEmpty() ? k : nm);
                        }
                    }
                    // Include current members who aren't my contacts (added by another admin),
                    // pre-checked — otherwise Save would silently kick them.
                    if (mem != null) {
                        for (Object o : mem) {
                            JSONObject mm = (JSONObject) o;
                            String k = str(mm, "key");
                            if (!haveContact.contains(k) && !bool(mm, "me")) {
                                keys.add(k);
                                String nm = str(mm, "name");
                                names.add(nm.isEmpty() ? k : nm);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || keys.isEmpty()) {
                        return;
                    }
                    final boolean[] checked = new boolean[keys.size()];
                    for (int i = 0; i < keys.size(); i++) {
                        checked[i] = current.contains(keys.get(i));
                    }
                    new LanguageDialog(CloudChatActivity.this)
                            .setTitle(Language.msg(R.string.l10n_desktop_chat_members))
                            .setMultiChoiceItems(names.toArray(new CharSequence[0]), checked,
                                    (d, which, isChecked) -> checked[which] = isChecked)
                            .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                            .setPositiveButton(Language.msg(R.string.l10n_common_save), (d, w) -> {
                                List<String> members = new ArrayList<>();
                                for (int i = 0; i < checked.length; i++) {
                                    if (checked[i]) {
                                        members.add(keys.get(i));
                                    }
                                }
                                saveMembers(members);
                            })
                            .show();
                });
            }
            public void err(String m) {
                runOnUiThread(() -> toast(SystemMessages.text(CloudChatActivity.this, m)));
            }
        });
    }

    private void saveMembers(List<String> zMembers) {
        if (zMembers.size() + 1 > com.eurobuddha.maxima.core.chat.Group.MAX_MEMBERS) {
            toast(Language.count(R.plurals.l10n_android_chat_group_limit, com.eurobuddha.maxima.core.chat.Group.MAX_MEMBERS).toString());
            return;
        }
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.updateGroup(mPeer, null, zMembers);
                    Object ok = res.get("ok");
                    if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fe = error;
                runOnUiThread(() -> toast(fe == null ? tr(R.string.l10n_android_chat_group_updated) : tr(R.string.l10n_native_chat_operation_failed, SystemMessages.message(fe))));
            }
            public void err(String m) {
                runOnUiThread(() -> toast(tr(R.string.l10n_native_chat_operation_failed, SystemMessages.message(m))));
            }
        });
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

    /** Photo snapshot of this conversation, including images outside the thumbnail cache. */
    private com.eurobuddha.maxima.app.chat.ChatImageViewer mPhotoViewer;
    private void openImage(String id) {
        java.util.List<com.eurobuddha.maxima.core.chat.ChatImages.Photo> photos = new java.util.ArrayList<>();
        for (Msg m : mMsgs) {
            photos.add(new com.eurobuddha.maxima.core.chat.ChatImages.Photo(m.id, m.body, m.time));
        }
        com.eurobuddha.maxima.core.chat.ChatImages gallery =
                new com.eurobuddha.maxima.core.chat.ChatImages(photos, id);
        if (gallery.current() == null) return;
        if (mPhotoViewer != null) mPhotoViewer.dismiss();
        mPhotoViewer = new com.eurobuddha.maxima.app.chat.ChatImageViewer(this, gallery,
                mImageCache, photo -> decodeBounded(fetchMediaBytes(
                        com.eurobuddha.maxima.core.chat.ChatMedia.ref(photo.body))),
                this::saveImage, this::shareImage);
        long oldest = Long.MAX_VALUE;
        for (Msg m : mMsgs) if (m.time > 0) oldest = Math.min(oldest, m.time);
        if (oldest != Long.MAX_VALUE) mPhotoViewer.setHistory(before -> {
            ParlonsRemote remote = CloudSession.remoteOrNull();
            if (remote == null) throw new IllegalStateException(tr(R.string.l10n_native_contacts_not_connected));
            java.util.List<com.eurobuddha.maxima.core.chat.ChatImages.Photo> page = new java.util.ArrayList<>();
            for (Msg m : parseMsgs(remote.conversation(mPeer, 100, before))) {
                page.add(new com.eurobuddha.maxima.core.chat.ChatImages.Photo(m.id, m.body, m.time));
            }
            return page;
        }, oldest);
        mPhotoViewer.show();
    }

    private void saveImage(String id) { saveImage(mImageCache.get(id)); }

    private void saveImage(final android.graphics.Bitmap b) {
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
                runOnUiThread(() -> toast(tr(R.string.l10n_android_chat_saved)));
            } catch (Exception e) {
                runOnUiThread(() -> toast(tr(R.string.l10n_android_chat_save_failed)));
            }
        }, "portal-save-image").start();
    }

    private void shareImage(String id) { shareImage(mImageCache.get(id)); }

    private void shareImage(final android.graphics.Bitmap b) {
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
                    startActivity(Intent.createChooser(i, tr(R.string.l10n_android_chat_share_photo)));
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast(tr(R.string.l10n_android_chat_share_failed)));
            }
        }, "portal-share-image").start();
    }

    // ---- attach / camera / caption / send-photo ----

    private com.eurobuddha.maxima.app.chat.PrivateFileUi mFileUi;
    private com.eurobuddha.maxima.app.chat.PrivateFileUi fileUi() {
        if (mFileUi == null) {
            mFileUi = new com.eurobuddha.maxima.app.chat.PrivateFileUi(this, params -> {
                final com.eurobuddha.maxima.cloud.ParlonsRemote remote = CloudSession.remoteOrNull();
                if (remote == null) throw new java.io.IOException("Connect to your account first");
                org.minima.utils.json.JSONObject input = new org.minima.utils.json.JSONObject(); input.putAll(params);
                org.minima.utils.json.JSONObject response = remote.rpc("parlons.files", input);
                java.util.Map<String,String> out = new java.util.LinkedHashMap<>();
                for (Object k : response.keySet()) out.put(k.toString(), String.valueOf(response.get(k)));
                return out;
            }, mPeer, mGroup);
        }
        return mFileUi;
    }
    private void attachSheet() {
        final java.util.List<Integer> items = new ArrayList<>();
        items.add(R.string.l10n_android_chat_photo_library);
        items.add(R.string.l10n_files_private);
        items.add(R.string.l10n_files_title);
        items.add(R.string.l10n_desktop_chat_voice_note);
        if (!mGroup) {
            items.add(R.string.l10n_desktop_chat_send_payment);
        }
        new LanguageDialog(this)
                .actions(items, (d, which) -> {
                    int pick = items.get(which);
                    if (R.string.l10n_files_private == pick) {
                        fileUi().pick();
                    } else if (R.string.l10n_files_title == pick) {
                        fileUi().transfers();
                    } else if (R.string.l10n_desktop_chat_send_payment == pick) {
                        payContact();
                    } else if (R.string.l10n_desktop_chat_voice_note == pick) {
                        startVoiceNote();
                    } else {
                        pickPhoto();
                    }
                })
                .show();
    }

    /** Pay this contact from the ACCOUNT's wallet — built + signed on your node, the same
     *  Parlons pattern as the phone (the seed is the wallet). */
    private void payContact() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        // Minima or MxUSD (the Minima dollar-pegged token); the account node signs either.
        final com.eurobuddha.maxima.app.chat.PaymentCurrency currency = new com.eurobuddha.maxima.app.chat.PaymentCurrency(this);
        box.addView(currency);
        final EditText amt = new EditText(this);
        Language.hint(amt, Language.msg(R.string.l10n_android_chat_amount));
        currency.setOnCheckedChangeListener((g, id) -> Language.hint(amt, Language.msg(
                currency.isUsd() ? R.string.l10n_android_chat_amount_mxusd : R.string.l10n_android_chat_amount)));
        amt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(amt);
        final EditText memo = new EditText(this);
        Language.hint(memo, Language.msg(R.string.l10n_android_chat_memo));
        memo.setSingleLine(true);
        box.addView(memo);
        new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_android_chat_pay_cloud, mName))
                .setMessage(Language.msg(R.string.l10n_android_chat_cloud_wallet))
                .setView(box)
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_chat_send), (d, w) -> {
                    final String amount = amt.getText().toString().trim();
                    final String note = memo.getText().toString().trim();
                    final String tokenid = currency.tokenId();
                    if (amount.isEmpty()) {
                        toast(tr(R.string.l10n_android_chat_enter_amount));
                        return;
                    }
                    toast(tr(R.string.l10n_android_chat_payment_building));
                    CloudSession.connectInteractive(this, new CloudSession.Cb() {
                        public void ok(com.eurobuddha.maxima.cloud.ParlonsRemote r) {
                            String error = null;
                            try {
                                JSONObject res = r.pay(mPeer, amount, note, tokenid);
                                Object ok = res.get("ok");
                                if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                                    error = String.valueOf(res.get("error"));
                                }
                            } catch (Exception e) {
                                error = e.getMessage() == null ? e.toString() : e.getMessage();
                            }
                            final String err = error;
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) {
                                    return;
                                }
                                if (err != null) {
                                    toast(tr(R.string.l10n_android_chat_pay_failed, SystemMessages.message(err)));
                                }
                                load();
                            });
                        }
                        public void err(String m) {
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()) {
                                    toast(tr(R.string.l10n_android_chat_pay_failed, SystemMessages.message(m)));
                                }
                            });
                        }
                    });
                })
                .show();
    }

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, tr(R.string.l10n_android_chat_send_photo)), PICK_PHOTO);
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
            toast(tr(R.string.l10n_android_chat_no_camera));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // The camera app can push us through a config change / process death; without persisting
        // the capture uri, a returning photo (onActivityResult) is dropped as mCaptureUri==null.
        if (mCaptureUri != null) {
            out.putString("captureUri", mCaptureUri.toString());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (mFileUi != null && mFileUi.result(req, res, data)) return;
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
        Language.hint(cap, Language.msg(R.string.l10n_android_chat_caption));
        cap.setHintTextColor(getColor(R.color.ux_subtext));
        cap.setTextColor(getColor(R.color.ux_text));
        cap.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        cap.setMaxLines(4);
        box.addView(cap);
        new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_android_chat_send_photo))
                .setView(box)
                .setPositiveButton(Language.msg(R.string.l10n_chat_send),
                        (d, w) -> sendPhoto(uri, cap.getText().toString().trim()))
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .show();
    }

    private void sendPhoto(final android.net.Uri uri, final String caption) {
        toast(tr(R.string.l10n_android_chat_sending_photo));
        new Thread(() -> {
            try {
                byte[] jpeg = readScaledJpeg(uri, 1400);
                uploadMedia(jpeg, "image/jpeg", caption);
            } catch (Exception e) {
                runOnUiThread(() -> toast(tr(R.string.l10n_android_chat_photo_failed, SystemMessages.message(e.getMessage()))));
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
                toast(tr(R.string.l10n_wallet_send_failed, SystemMessages.message(err)));
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
            toast(tr(R.string.l10n_android_chat_no_microphone));
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
                new LanguageDialog(this)
                        .setTitle(Language.msg(R.string.l10n_desktop_chat_voice_note))
                        .setView(box)
                        .setPositiveButton(Language.msg(R.string.l10n_chat_send), null)
                        .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
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
                    Language.setText(timer, Language.msg(R.string.l10n_android_chat_max_duration, fmtSecs(com.eurobuddha.maxima.app.chat.VoiceNote.MAX_SECONDS)));
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
                        toast(tr(R.string.l10n_android_chat_record_failed));
                        dlg.dismiss();
                        return;
                    }
                    dlg.dismiss();
                    if (bytes == null || bytes.length == 0 || secs < 1) {
                        toast(tr(R.string.l10n_android_chat_record_short));
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
        toast(tr(R.string.l10n_android_chat_sending_voice));
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
        Language.description(h.audioBtn, Language.msg(playing ? R.string.l10n_desktop_chat_stop : R.string.l10n_media_play));
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
            h.audioTime.setText(total.isEmpty() ? tr(R.string.l10n_desktop_chat_voice_note) : total);
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
                    toast(tr(R.string.l10n_android_chat_play_voice));
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
                    toast(tr(R.string.l10n_android_chat_playback_failed));
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
            // Date separator when the day changes (or the very first message).
            String day = dayLabel(m.time);
            boolean newDay = position == 0
                    || !day.equals(dayLabel(mMsgs.get(position - 1).time));
            if (newDay && m.time > 0) {
                h.datePill.setVisibility(View.VISIBLE);
                h.datePill.setText(day);
            } else {
                h.datePill.setVisibility(View.GONE);
            }
            h.row.setGravity(m.mine ? Gravity.END : Gravity.START);
            h.bubble.setBackgroundResource(m.mine ? R.drawable.bubble_out : R.drawable.bubble_in);
            int ink = getColor(m.mine ? R.color.ux_bubble_out_text : R.color.ux_bubble_in_text);
            // A selectable TextView eats long-presses (starts text selection) — the bubble menu
            // wouldn't open on the text. Copy lives in the menu instead (app does the same).
            h.body.setTextIsSelectable(false);

            // Group sender name — shown once at the top of a run of same-sender messages.
            boolean showName = mGroup && !m.mine && !m.sname.isEmpty()
                    && (position == 0 || mMsgs.get(position - 1).mine
                        || !mMsgs.get(position - 1).sender.equals(m.sender)
                        || m.time - mMsgs.get(position - 1).time > 5 * 60_000L);
            if (showName) {
                h.sender.setVisibility(View.VISIBLE);
                h.sender.setText(m.sname);
            } else {
                h.sender.setVisibility(View.GONE);
            }

            h.body.setOnClickListener(null);
            com.eurobuddha.maxima.app.chat.ChatLinkText.reset(h.body);
            h.body.setTextIsSelectable(!com.eurobuddha.maxima.core.chat.ChatFile.isFile(m.body));
            h.body.setClickable(false);
            boolean pay = com.eurobuddha.maxima.core.chat.ChatPay.isPayment(m.body);
            boolean media = !pay && com.eurobuddha.maxima.core.chat.ChatMedia.isMedia(m.body);
            String mime = media ? com.eurobuddha.maxima.core.chat.ChatMedia.mime(m.body) : "";
            if (pay) {
                h.image.setVisibility(View.GONE);
                h.audio.setVisibility(View.GONE);
                h.body.setVisibility(View.VISIBLE);
                String payLine = com.eurobuddha.maxima.core.chat.ChatPay.preview(m.body);
                if (m.mine && "failed".equals(m.state)) {
                    // A failed PAYMENT must never read like a safe retry — the broadcast may
                    // have happened. Say so where the eye lands.
                    payLine += "\n" + tr(R.string.l10n_android_chat_unconfirmed);
                }
                com.eurobuddha.maxima.app.i18n.Language.setText(h.body, payLine);
                h.body.setTextColor(ink);
                h.body.setTypeface(null, android.graphics.Typeface.BOLD);
            } else if (media && mime.startsWith("audio") && !com.eurobuddha.maxima.core.chat.ChatFile.isFile(m.body)) {
                h.image.setVisibility(View.GONE);
                h.audio.setVisibility(View.VISIBLE);
                h.body.setVisibility(View.GONE);
                bindAudio(h, m);
            } else if (com.eurobuddha.maxima.core.chat.ChatFile.isFile(m.body)) {
                h.audio.setVisibility(View.GONE); h.image.setVisibility(View.GONE); h.image.setImageDrawable(null);
                h.body.setVisibility(View.VISIBLE); com.eurobuddha.maxima.app.i18n.Language.setText(h.body, com.eurobuddha.maxima.app.chat.PrivateFileUi.label(m.body));
                h.body.setOnClickListener(v -> fileUi().showBody(m.body));
                h.body.setTextColor(ink);
            } else if (media) {
                h.audio.setVisibility(View.GONE);
                h.image.setVisibility(View.VISIBLE);
                String cap = com.eurobuddha.maxima.core.chat.ChatMedia.caption(m.body);
                h.body.setVisibility(cap.isEmpty() ? View.GONE : View.VISIBLE);
                com.eurobuddha.maxima.app.i18n.Language.setText(h.body, cap);
                h.body.setTextColor(ink);
                h.body.setTypeface(null, android.graphics.Typeface.NORMAL);   // recycled pay bubble
                bindImage(h.image, m.body, m.id);
            } else {
                h.image.setVisibility(View.GONE);
                h.audio.setVisibility(View.GONE);
                h.body.setVisibility(View.VISIBLE);
                com.eurobuddha.maxima.app.i18n.Language.setText(h.body, m.body);
                h.body.setTextColor(ink);
                h.body.setTypeface(null, android.graphics.Typeface.NORMAL);
            }

            if (!pay && !com.eurobuddha.maxima.core.chat.ChatFile.isFile(m.body)
                    && !com.eurobuddha.maxima.core.chat.ChatContact.isCard(m.body)
                    && h.body.getVisibility() == View.VISIBLE) {
                com.eurobuddha.maxima.app.chat.ChatLinkText.bind(h.body, h.body.getText().toString(), () -> bubbleMenu(m));
            }

            String meta = stamp(m.time);
            if (m.mine) {
                meta = meta + "  " + ticks(m.state);
                h.meta.setText(meta);
                h.meta.setTextColor(tickColour(m.state));
            } else {
                // Dual clock: a late relay delivery shows "sent 13:56 · arrived 14:02".
                if (m.arrived > 0 && m.arrived - m.time >= 60_000L) {
                    meta = tr(R.string.l10n_desktop_chat_late_delivery, stamp(m.time), stamp(m.arrived));
                }
                h.meta.setText(meta);
                h.meta.setTextColor(getColor(R.color.ux_subtext));
            }
            // A failed (✗) message is tappable: reconnect now. Long-press ANY bubble → menu.
            if (m.mine && "failed".equals(m.state)) {
                h.bubble.setOnClickListener(v -> offerReconnect());
            } else {
                h.bubble.setOnClickListener(com.eurobuddha.maxima.core.chat.ChatFile.isFile(m.body)
                        ? v -> fileUi().showBody(m.body) : null);
                h.bubble.setClickable(com.eurobuddha.maxima.core.chat.ChatFile.isFile(m.body));
            }
            h.bubble.setOnLongClickListener(v -> {
                bubbleMenu(m);
                return true;
            });
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
        final TextView datePill;
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
            datePill = v.findViewById(R.id.date_pill);
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
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        if (mList != null) mAdapter.notifyDataSetChanged();
    }

}
