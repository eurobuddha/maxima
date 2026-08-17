package com.eurobuddha.maxima.app.chat;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.Group;
import com.eurobuddha.maxima.core.chat.Receipt;
import com.eurobuddha.maxima.core.contacts.Contact;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One conversation: the messages, and a box to add to them.
 *
 * The tick beside each of our own messages is the whole reason the chat layer
 * exists on top of Maxima rather than beside it - classic can only ever say
 * "the relay took the bytes", and this screen distinguishes that from "they
 * actually have it".
 */
public final class ChatActivity extends AppCompatActivity implements ChatEngine.Listener {

    public static final String EXTRA_CONVERSATION = "conversation";

    private String mConversation = "";
    private RecyclerView mList;
    private EditText mInput;
    private TextView mSubtitle;
    private TextView mTitle;
    private TextView mAvatar;
    private TextView mScrollBottom;
    private Adapter mAdapter;
    /** Sending always scrolls to your own message, even from up the history. */
    private boolean mLastSendWasMine;
    /** The rendered rows: date separators interleaved with messages. */
    private final List<Row> mRows = new ArrayList<>();
    /** Message count last render, to detect real growth vs a state-only redraw. */
    private int mMsgCount;
    private final SimpleDateFormat mTime = new SimpleDateFormat("HH:mm", Locale.UK);

    /** Cluster consecutive messages from one sender within this window. */
    private static final long CLUSTER_GAP_MS = 5 * 60 * 1000;

    private final android.os.Handler mHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** Coalesced, for the same reason as the list: one send, N receipts. */
    private final Runnable mRender = this::render;

    private void renderSoon() {
        mHandler.removeCallbacks(mRender);
        mHandler.postDelayed(mRender, 80);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        if (!adopt(getIntent())) {
            finish();
            return;
        }

        View root = findViewById(R.id.chat_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        mList = findViewById(R.id.messages);
        mInput = findViewById(R.id.chat_input);
        mSubtitle = findViewById(R.id.chat_subtitle);
        mTitle = findViewById(R.id.chat_title);
        mAvatar = findViewById(R.id.chat_avatar);
        findViewById(R.id.btn_chat_back).setOnClickListener(v -> finish());
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);   // a short thread sits at the bottom, like a chat
        mList.setLayoutManager(lm);
        mAdapter = new Adapter();
        mList.setAdapter(mAdapter);
        ((androidx.recyclerview.widget.SimpleItemAnimator) mList.getItemAnimator())
                .setSupportsChangeAnimations(false);   // don't flash a bubble on a tick change

        mScrollBottom = findViewById(R.id.btn_scroll_bottom);
        mScrollBottom.setOnClickListener(v -> {
            if (!mRows.isEmpty()) {
                mList.smoothScrollToPosition(mRows.size() - 1);
            }
        });
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                updateScrollFab();
            }
        });

        findViewById(R.id.btn_chat_send).setOnClickListener(v -> send());
        findViewById(R.id.btn_chat_info).setOnClickListener(v -> showInfo());
        findViewById(R.id.btn_chat_attach).setOnClickListener(v -> attachPhoto());
    }

    private static final int PICK_PHOTO = 71;

    /** Decoded chat images, by message id — the WOTS-free path: fetch once. */
    private final java.util.Map<String, android.graphics.Bitmap> mImageCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> mImageFetching =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Show the image for a media bubble: cache hit, else fetch+decode off-main. */
    private void bindImage(android.widget.ImageView view, String body, String id) {
        android.graphics.Bitmap cached = mImageCache.get(id);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setOnClickListener(v -> saveImage(id));
            return;
        }
        view.setImageResource(android.R.drawable.ic_menu_gallery);
        if (!mImageFetching.add(id)) {
            return;   // already fetching
        }
        final String ref = com.eurobuddha.maxima.core.chat.ChatMedia.ref(body);
        new Thread(() -> {
            try {
                com.eurobuddha.maxima.core.media.MediaService media = MaximaService.media();
                com.eurobuddha.maxima.core.media.MediaManifest mf =
                        com.eurobuddha.maxima.core.media.MediaManifest.decode(
                                new String(android.util.Base64.decode(
                                        ref.substring("mx1:".length()),
                                        android.util.Base64.URL_SAFE),
                                        java.nio.charset.StandardCharsets.UTF_8));
                byte[] bytes = media.fetch(mf);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory
                        .decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    mImageCache.put(id, bmp);
                    runOnUiThread(this::render);
                }
            } catch (Exception e) {
                // leave the placeholder; a redraw will retry
            } finally {
                mImageFetching.remove(id);
            }
        }, "chat-image").start();
    }

    private void saveImage(String id) {
        android.graphics.Bitmap b = mImageCache.get(id);
        if (b == null) {
            return;
        }
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    "maxima-" + System.currentTimeMillis() + ".jpg");
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, os);
            }
            toast("Saved to Photos");
        } catch (Exception e) {
            toast("Save failed");
        }
    }

    private void attachPhoto() {
        android.content.Intent i = new android.content.Intent(
                android.content.Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(android.content.Intent.createChooser(i, "Send photo"), PICK_PHOTO);
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_PHOTO || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        final ChatEngine chat = MaximaService.chat();
        final MaximaNode node = MaximaService.node();
        if (chat == null || node == null) {
            toast("Transport not running");
            return;
        }
        final Group g = chat.group(mConversation);
        final Contact c = g == null ? node.contact(mConversation) : null;
        if (g == null && c == null) {
            return;
        }
        toast("Sending photo…");
        mLastSendWasMine = true;
        new Thread(() -> {
            try {
                byte[] jpeg = readScaledJpeg(uri, 1400);
                if (g != null) {
                    chat.sendGroupMedia(g.id, jpeg, "image/jpeg", "");
                } else {
                    chat.sendMedia(c, jpeg, "image/jpeg", "");
                }
            } catch (Exception e) {
                runOnUiThread(() -> toast("Photo failed: " + e.getMessage()));
            }
            runOnUiThread(this::render);
        }, "chat-media").start();
    }

    /** Decode + downscale + re-encode a picked image to a modest JPEG. */
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
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bos);
            return bos.toByteArray();
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * We are singleTop, so tapping a notification for a DIFFERENT conversation
     * while this screen is open delivers a new intent instead of a new
     * activity. Without this the old thread stays on screen under the new
     * notification's name - you tap Bob and get Alice, and the message you type
     * next goes to Alice.
     */
    @Override
    protected void onNewIntent(android.content.Intent zIntent) {
        super.onNewIntent(zIntent);
        setIntent(zIntent);
        String previous = mConversation;
        if (!adopt(zIntent)) {
            return;
        }
        if (!previous.equals(mConversation)) {
            ChatEngine chat = MaximaService.chat();
            if (chat != null) {
                chat.markRead(previous);
            }
            mRows.clear();
            mMsgCount = 0;
            mAdapter.notifyDataSetChanged();
        }
        ChatHub.setForeground(mConversation);
        ChatNotifier.clear(this, mConversation);
        if (chatOrNull() != null) {
            chatOrNull().markRead(mConversation);
        }
        render();
    }

    private ChatEngine chatOrNull() {
        return MaximaService.chat();
    }

    /** Read the conversation out of an intent. False if there is not one. */
    private boolean adopt(android.content.Intent zIntent) {
        String c = zIntent == null ? null : zIntent.getStringExtra(EXTRA_CONVERSATION);
        if (c == null || c.trim().isEmpty()) {
            return false;
        }
        mConversation = c.trim();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChatHub.setForeground(mConversation);
        ChatHub.register(this);
        ChatNotifier.clear(this, mConversation);

        ChatEngine chat = MaximaService.chat();
        if (chat != null) {
            // Reading it IS marking it read. Whether the other side is told is
            // their setting, not this screen's business.
            chat.markRead(mConversation);
        }
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatHub.setForeground("");
        ChatHub.unregister(this);
        mHandler.removeCallbacks(mRender);
        ChatEngine chat = MaximaService.chat();
        if (chat != null) {
            chat.markRead(mConversation);
            // Leaving the thread is a natural point to write out the ticks that
            // arrived while it was open.
            chat.flushState();
        }
    }

    // ---------------------------------------------------------------

    private void render() {
        MaximaNode node = MaximaService.node();
        ChatEngine chat = MaximaService.chat();
        if (node == null || chat == null) {
            return;
        }
        String name = Names.of(node, chat, mConversation);
        mTitle.setText(name);
        com.eurobuddha.maxima.app.ui.Avatars.apply(mAvatar, mConversation, name);

        Group g = chat.group(mConversation);
        if (g != null) {
            mSubtitle.setText(g.size() + " member(s)"
                    + (g.isAdmin(node.identity().publicKeyHex()) ? "  ·  you are an admin" : ""));
            mSubtitle.setTextColor(getColor(R.color.ux_subtext));
        } else {
            Contact c = node.contact(mConversation);
            String presence = c == null ? "" : com.eurobuddha.maxima.app.ui.Presence.of(c.lastSeen);
            if (c == null) {
                mSubtitle.setText("not in your contacts");
                mSubtitle.setTextColor(getColor(R.color.ux_subtext));
            } else if (!presence.isEmpty()) {
                mSubtitle.setText(presence);
                mSubtitle.setTextColor(getColor(
                        com.eurobuddha.maxima.app.ui.Presence.online(c.lastSeen)
                                ? R.color.ux_success : R.color.ux_subtext));
            } else {
                mSubtitle.setText(c.primaryAddress() == null
                        ? "no address yet" : "not reached yet");
                mSubtitle.setTextColor(getColor(R.color.ux_subtext));
            }
        }

        List<ChatEngine.Entry> conv = chat.conversation(mConversation);
        conv.sort((a, b) -> Long.compare(a.time, b.time));

        // Only follow the bottom if we were ALREADY at the bottom. Jumping to
        // the newest message on every receipt drags the user out of the history
        // they are scrolled back reading, which a burst of group ticks would do
        // several times a second.
        LinearLayoutManager lm = (LinearLayoutManager) mList.getLayoutManager();
        boolean atBottom = mRows.isEmpty()
                || (lm != null && lm.findLastVisibleItemPosition() >= mRows.size() - 1);
        boolean grew = conv.size() > mMsgCount;

        // Rebuild the row list: a date separator whenever the day changes, and
        // per-message cluster flags (a run from one sender within CLUSTER_GAP_MS
        // is drawn as one group - tail + timestamp only on the last of the run).
        mRows.clear();
        long lastDay = Long.MIN_VALUE;
        for (int i = 0; i < conv.size(); i++) {
            ChatEngine.Entry e = conv.get(i);
            long day = dayStart(e.time);
            if (day != lastDay) {
                mRows.add(Row.date(dayLabel(e.time)));
                lastDay = day;
            }
            ChatEngine.Entry prev = i > 0 ? conv.get(i - 1) : null;
            ChatEngine.Entry next = i < conv.size() - 1 ? conv.get(i + 1) : null;
            Row r = Row.msg(e);
            r.firstInCluster = !sameCluster(prev, e);
            r.lastInCluster = !sameCluster(e, next);
            mRows.add(r);
        }
        mMsgCount = conv.size();
        mAdapter.notifyDataSetChanged();
        if (!mRows.isEmpty() && (atBottom || (grew && mLastSendWasMine))) {
            mList.scrollToPosition(mRows.size() - 1);
        }
        mLastSendWasMine = false;
        mList.post(this::updateScrollFab);
    }

    /** Show the jump-to-latest button only while scrolled up the history. */
    private void updateScrollFab() {
        LinearLayoutManager lm = (LinearLayoutManager) mList.getLayoutManager();
        if (lm == null || mScrollBottom == null) {
            return;
        }
        boolean atBottom = mRows.isEmpty()
                || lm.findLastVisibleItemPosition() >= mRows.size() - 1;
        mScrollBottom.setVisibility(atBottom ? View.GONE : View.VISIBLE);
    }

    /** Two messages cluster if the same sender sent them close in time on the
     *  same day (a date separator between them already breaks the day). */
    private static boolean sameCluster(ChatEngine.Entry a, ChatEngine.Entry b) {
        if (a == null || b == null) {
            return false;
        }
        return a.mine == b.mine
                && a.sender.equals(b.sender)
                && dayStart(a.time) == dayStart(b.time)
                && b.time - a.time < CLUSTER_GAP_MS;
    }

    private static long dayStart(long zMillis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(zMillis);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** "Today" / "Yesterday" / weekday (within a week) / a date. */
    private String dayLabel(long zMillis) {
        long today = dayStart(System.currentTimeMillis());
        long day = dayStart(zMillis);
        long days = (today - day) / (24L * 60 * 60 * 1000);
        if (days == 0) {
            return "Today";
        }
        if (days == 1) {
            return "Yesterday";
        }
        if (days > 1 && days < 7) {
            return new SimpleDateFormat("EEEE", Locale.UK).format(new Date(zMillis));
        }
        return new SimpleDateFormat("d MMM yyyy", Locale.UK).format(new Date(zMillis));
    }

    private int dp(int zDp) {
        return Math.round(zDp * getResources().getDisplayMetrics().density);
    }

    private void send() {
        String text = mInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        MaximaNode node = MaximaService.node();
        ChatEngine chat = MaximaService.chat();
        if (node == null || chat == null) {
            toast("Transport not running");
            return;
        }
        final Group g = chat.group(mConversation);
        final Contact c = g == null ? node.contact(mConversation) : null;
        if (g == null && c == null) {
            toast("They are not in your contacts");
            return;
        }
        mInput.setText("");
        mLastSendWasMine = true;
        // Sending opens sockets - one per member for a group.
        new Thread(() -> {
            try {
                if (g != null) {
                    chat.sendGroup(g.id, text);
                } else {
                    chat.send(c, text);
                }
            } catch (Exception e) {
                runOnUiThread(() -> toast("Send failed: " + e.getMessage()));
            }
            runOnUiThread(this::render);
        }, "chat-send").start();
        render();
    }

    /** Group roster, or the contact's addresses. */
    private void showInfo() {
        MaximaNode node = MaximaService.node();
        ChatEngine chat = MaximaService.chat();
        if (node == null || chat == null) {
            return;
        }
        Group g = chat.group(mConversation);
        StringBuilder sb = new StringBuilder();
        if (g != null) {
            sb.append("Members\n");
            for (String m : g.members()) {
                sb.append("  ").append(Names.contact(node, m));
                if (g.isAdmin(m)) {
                    sb.append("   (admin)");
                }
                sb.append('\n');
            }
            sb.append("\nEvery message is sealed separately to each member. ")
                    .append("There is no shared group key, so removing someone ")
                    .append("removes them immediately.");
        } else {
            Contact c = node.contact(mConversation);
            if (c == null) {
                sb.append("Not in your contacts.");
            } else {
                sb.append(c.name).append('\n').append(c.publicKey).append("\n\nAddresses\n");
                for (String a : c.addresses) {
                    sb.append("  ").append(a).append('\n');
                }
            }
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(Names.of(node, chat, mConversation))
                .setMessage(sb.toString())
                .setPositiveButton("Close", null)
                .setNegativeButton("What do the ticks mean?", (d, w) ->
                        com.eurobuddha.maxima.app.ui.Explain.show(this, "ticks"));
        if (g != null && g.isAdmin(node.identity().publicKeyHex())) {
            b.setNeutralButton("Edit members", (d, w) -> editMembers(g));
        }
        b.show();
    }

    /** Only an admin gets here, and only an admin is obeyed at the other end. */
    private void editMembers(Group zGroup) {
        MaximaNode node = MaximaService.node();
        List<Contact> contacts = node.contacts();
        String[] labels = new String[contacts.size()];
        boolean[] checked = new boolean[contacts.size()];
        for (int i = 0; i < contacts.size(); i++) {
            labels[i] = contacts.get(i).name + "  " + Names.shorten(contacts.get(i).publicKey);
            checked[i] = zGroup.isMember(contacts.get(i).publicKey);
        }
        new AlertDialog.Builder(this)
                .setTitle("Members")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) ->
                        checked[which] = isChecked)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    for (int i = 0; i < contacts.size(); i++) {
                        if (checked[i]) {
                            zGroup.addMember(contacts.get(i).publicKey);
                        } else {
                            zGroup.removeMember(contacts.get(i).publicKey);
                        }
                    }
                    // We must stay in our own group, and stay its admin.
                    zGroup.addAdmin(node.identity().publicKeyHex());
                    new Thread(() -> {
                        try {
                            MaximaService.chat().updateGroup(zGroup);
                        } catch (Exception e) {
                            runOnUiThread(() -> toast("Failed: " + e.getMessage()));
                        }
                        runOnUiThread(this::render);
                    }, "update-group").start();
                })
                .show();
    }

    private void toast(String zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_SHORT).show();
    }

    // ---- listener ----

    @Override
    public void onMessage(ChatEngine.Entry zEntry) {
        renderSoon();
        ChatEngine chat = MaximaService.chat();
        // A message arriving in the thread you are looking at is already read.
        if (chat != null && !zEntry.mine
                && mConversation.equals(zEntry.isGroup() ? zEntry.groupId : zEntry.peer)) {
            chat.markRead(mConversation);
        }
    }

    @Override
    public void onStateChanged(ChatEngine.Entry zEntry) {
        renderSoon();
    }

    @Override
    public void onGroupChanged(Group zGroup) {
        renderSoon();
    }

    // ---------------------------------------------------------------

    /**
     * Ticks, in the order the state machine can reach them.
     *
     * SENT is one tick and means only that a relay accepted the bytes - exactly
     * what classic Maxima can tell you. DELIVERED is two, and requires the
     * recipient's own reply.
     */
    private static String ticks(String zState) {
        if (Receipt.FAILED.equals(zState)) {
            return "✗";
        }
        if (Receipt.READ.equals(zState)) {
            return "✓✓";
        }
        if (Receipt.DELIVERED.equals(zState)) {
            return "✓✓";
        }
        if (Receipt.SENT.equals(zState)) {
            return "✓";
        }
        return "⋯";
    }

    private int tickColour(String zState) {
        if (Receipt.FAILED.equals(zState)) {
            return getColor(R.color.ux_error);
        }
        if (Receipt.READ.equals(zState)) {
            return getColor(R.color.ux_tick_read);
        }
        return getColor(R.color.ux_subtext);
    }

    /** A rendered row: a date separator, or a message. */
    private static final class Row {
        static final int DATE = 0, MSG = 1;
        final int type;
        final ChatEngine.Entry entry;
        final String date;
        boolean firstInCluster, lastInCluster;

        private Row(int t, ChatEngine.Entry e, String d) {
            type = t;
            entry = e;
            date = d;
        }
        static Row date(String d) { return new Row(DATE, null, d); }
        static Row msg(ChatEngine.Entry e) { return new Row(MSG, e, null); }
    }

    private static final class DateVH extends RecyclerView.ViewHolder {
        final TextView pill;
        DateVH(View v, TextView p) { super(v); pill = p; }
    }

    private static final class MsgVH extends RecyclerView.ViewHolder {
        final LinearLayout row, bubble;
        final TextView who, body, meta;
        final android.widget.ImageView image;
        MsgVH(View v) {
            super(v);
            row = v.findViewById(R.id.bubble_row);
            bubble = v.findViewById(R.id.bubble);
            who = v.findViewById(R.id.bubble_sender);
            body = v.findViewById(R.id.bubble_body);
            meta = v.findViewById(R.id.bubble_meta);
            image = v.findViewById(R.id.bubble_image);
        }
    }

    private void bindMessage(MsgVH h, Row r) {
        ChatEngine.Entry e = r.entry;
        h.row.setGravity(e.mine ? Gravity.END : Gravity.START);
        // Tight within a cluster; a clear gap starting each new run.
        h.row.setPadding(dp(12), r.firstInCluster ? dp(7) : dp(1), dp(12), dp(1));
        // Tail only on the first bubble of a run; the rest fully rounded.
        int bg = e.mine
                ? (r.firstInCluster ? R.drawable.bubble_out : R.drawable.bubble_out_mid)
                : (r.firstInCluster ? R.drawable.bubble_in : R.drawable.bubble_in_mid);
        h.bubble.setBackgroundResource(bg);

        // In a group, name the sender once at the top of their run.
        if (!e.mine && e.isGroup() && r.firstInCluster) {
            h.who.setVisibility(View.VISIBLE);
            h.who.setText(Names.contact(MaximaService.node(), e.sender));
        } else {
            h.who.setVisibility(View.GONE);
        }

        if (com.eurobuddha.maxima.core.chat.ChatMedia.isMedia(e.body)) {
            String cap = com.eurobuddha.maxima.core.chat.ChatMedia.caption(e.body);
            h.body.setText(cap);
            h.body.setVisibility(cap.isEmpty() ? View.GONE : View.VISIBLE);
            h.image.setVisibility(View.VISIBLE);
            bindImage(h.image, e.body, e.id);
        } else {
            h.image.setVisibility(View.GONE);
            h.image.setImageDrawable(null);
            h.body.setVisibility(View.VISIBLE);
            h.body.setText(e.body);
        }

        // Timestamp + ticks only on the last of a run (iMessage-style).
        if (r.lastInCluster) {
            h.meta.setVisibility(View.VISIBLE);
            String stamp = mTime.format(new Date(e.time));
            if (e.mine) {
                h.meta.setText(stamp + "  " + ticks(e.state));
                h.meta.setTextColor(tickColour(e.state));
            } else {
                h.meta.setText(stamp);
                h.meta.setTextColor(getColor(R.color.ux_subtext));
            }
        } else {
            h.meta.setVisibility(View.GONE);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).type;
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == Row.DATE) {
                LinearLayout wrap = new LinearLayout(ChatActivity.this);
                wrap.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                wrap.setGravity(Gravity.CENTER_HORIZONTAL);
                wrap.setPadding(0, dp(10), 0, dp(6));
                TextView pill = new TextView(ChatActivity.this);
                pill.setBackgroundResource(R.drawable.pill);
                pill.setPadding(dp(12), dp(3), dp(12), dp(3));
                pill.setTextSize(11);
                pill.setTextColor(getColor(R.color.ux_subtext));
                wrap.addView(pill);
                return new DateVH(wrap, pill);
            }
            View v = LayoutInflater.from(ChatActivity.this)
                    .inflate(R.layout.item_message, parent, false);
            return new MsgVH(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row r = mRows.get(position);
            if (r.type == Row.DATE) {
                ((DateVH) holder).pill.setText(r.date);
            } else {
                bindMessage((MsgVH) holder, r);
            }
        }
    }
}
