package com.eurobuddha.maxima.app.portal;

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
    private RecyclerView mList;
    private EditText mInput;
    private final List<Msg> mMsgs = new ArrayList<>();
    private final Adapter mAdapter = new Adapter();
    private volatile boolean mBusy;
    private volatile long mReadMark;      // newest inbound time we've told the node we read

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
        // Phase-2 controls (no cloud RPC yet): hide rather than dangle dead buttons.
        hide(R.id.btn_chat_video);
        hide(R.id.btn_chat_call);
        hide(R.id.btn_chat_info);
        hide(R.id.btn_chat_attach);
        hide(R.id.btn_chat_camera);
        hide(R.id.btn_chat_emoji);

        mList = findViewById(R.id.messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        mList.setLayoutManager(lm);
        mList.setAdapter(mAdapter);

        mInput = findViewById(R.id.chat_input);
        final android.widget.ImageButton send = findViewById(R.id.btn_chat_send);
        // Send button shows a mic when the field is empty and a send arrow once there's text —
        // same affordance as Parlons. (Voice notes are Phase 2, so the mic is inert for now, but the
        // arrow appears exactly when tapping it will actually send.)
        send.setImageResource(R.drawable.ic_mic);
        send.setOnClickListener(v -> send());
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

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
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
                    boolean grew = got.size() != mMsgs.size();
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
            h.body.setText(m.body);
            h.body.setTextColor(getColor(m.mine ? R.color.ux_bubble_out_text : R.color.ux_bubble_in_text));
            h.sender.setVisibility(View.GONE);
            String meta = stamp(m.time);
            if (m.mine) {
                meta = meta + "  " + ticks(m.state);
                h.meta.setText(meta);
                h.meta.setTextColor(tickColour(m.state));
            } else {
                h.meta.setText(meta);
                h.meta.setTextColor(getColor(R.color.ux_subtext));
            }
            // hide the media image row we don't render in v1 (voice-note row was removed from the layout)
            gone(h.itemView, R.id.bubble_image);
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

        Holder(View v) {
            super(v);
            row = v.findViewById(R.id.bubble_row);
            bubble = v.findViewById(R.id.bubble);
            sender = v.findViewById(R.id.bubble_sender);
            body = v.findViewById(R.id.bubble_body);
            meta = v.findViewById(R.id.bubble_meta);
        }
    }
}
