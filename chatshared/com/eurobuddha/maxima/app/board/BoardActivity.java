package com.eurobuddha.maxima.app.board;

import android.content.ClipData;
import android.content.ClipboardManager;
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
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.LanguageDialog;
import com.eurobuddha.maxima.app.ui.Avatars;
import com.eurobuddha.maxima.app.ui.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One board: posts and their replies, a composer, and the details dialog (link, people asking
 * to join, members, rename, leave). Reuses the chat screen's layout and bubble rows, so it
 * looks like a conversation - but nothing here is sent to anyone; the host's
 * {@link BoardSource} publishes once and pulls.
 */
public final class BoardActivity extends AppCompatActivity {

    public static final String EXTRA_BOARD = "board";
    /** The board on screen right now ("" when none): its notifications stay quiet. */
    public static volatile String sOnScreen = "";

    /** One notification per board; a hash collision merges two boards, never loses one. */
    public static int notificationId(String zBoardId) {
        return 0x4B00_0000 | (zBoardId.hashCode() & 0x00FF_FFFF);
    }
    private static final long POLL_MS = 8000;

    private String mBoardId = "";
    private BoardSource.BoardRow mBoard;
    private final List<BoardSource.PostRow> mPosts = new ArrayList<>();
    private final List<Row> mRows = new ArrayList<>();
    private BoardSource.PostRow mReplyTo;
    private RecyclerView mList;
    private TextView mEmpty;
    private TextView mTitle;
    private TextView mSubtitle;
    private EditText mInput;
    private final Adapter mAdapter = new Adapter();
    private final ExecutorService mIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "board-screen");
        t.setDaemon(true);
        return t;
    });
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            load(false);
            mUi.postDelayed(this, POLL_MS);
        }
    };
    private boolean mBusy;

    /** A post as laid out: replies sit under their parent, indented. */
    private static final class Row {
        BoardSource.PostRow post;
        boolean reply;
        boolean firstOfSender;
    }

    @Override
    protected void onCreate(Bundle zSaved) {
        super.onCreate(zSaved);
        setContentView(R.layout.activity_chat);
        mBoardId = String.valueOf(getIntent().getStringExtra(EXTRA_BOARD));
        // Same edge-to-edge handling as the chat screen: the dark app bar extends up into the
        // status bar and the composer stays above the nav bar / keyboard.
        final View root = findViewById(R.id.chat_root);
        final View appbar = findViewById(R.id.chat_appbar);
        final View composer = findViewById(R.id.chat_composer);
        final int barTop = appbar.getPaddingTop();
        final int compBottom = composer.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
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
        mList = findViewById(R.id.messages);
        mEmpty = findViewById(R.id.chat_empty);
        mTitle = findViewById(R.id.chat_title);
        mSubtitle = findViewById(R.id.chat_subtitle);
        mInput = findViewById(R.id.chat_input);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        mList.setLayoutManager(lm);
        mList.setAdapter(mAdapter);
        findViewById(R.id.btn_chat_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_chat_info).setOnClickListener(v -> details());
        // Boards are text only: the one button is always "send" (the chat layout starts it as a mic).
        android.widget.ImageButton sendBtn = findViewById(R.id.btn_chat_send);
        sendBtn.setImageResource(R.drawable.ic_send);
        Language.description(sendBtn, Language.msg(R.string.l10n_chat_send));
        sendBtn.setOnClickListener(v -> send());
        for (int id : new int[] {R.id.btn_chat_video, R.id.btn_chat_call, R.id.btn_chat_theme,
                R.id.btn_chat_emoji, R.id.btn_scroll_bottom}) {
            BoardFlows.gone(findViewById(android.R.id.content), id);
        }
        findViewById(R.id.btn_chat_attach).setOnClickListener(v -> pickPhoto());
        findViewById(R.id.btn_chat_camera).setOnClickListener(v -> takePhoto());
        Language.hint(mInput, Language.msg(R.string.l10n_board_placeholder));
        mSubtitle.setOnClickListener(v -> {
            if (mReplyTo != null) {
                setReply(null);
            }
        });
        Avatars.apply(findViewById(R.id.chat_avatar), mBoardId, "");
        Language.setText(mEmpty, Language.msg(R.string.l10n_board_loading));
        mEmpty.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sOnScreen = mBoardId;
        try {
            getSystemService(android.app.NotificationManager.class).cancel(notificationId(mBoardId));
        } catch (Exception ignored) {
        }
        load(true);
        mUi.postDelayed(mPoll, source() == null ? POLL_MS : source().pollMs());
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mBoardId.equals(sOnScreen)) {
            sOnScreen = "";
        }
        mUi.removeCallbacks(mPoll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mIo.shutdownNow();
    }

    private void toast(CharSequence zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_SHORT).show();
    }

    private BoardSource source() {
        return BoardSource.Holder.get();
    }

    // ---- loading ----

    private void load(boolean zScroll) {
        mImageFailed.clear();   // a transient mesh miss gets another go on the next read
        BoardSource s = source();
        if (s == null) {
            Language.setText(mEmpty, Language.msg(R.string.l10n_native_chat_starting));
            mEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (mBusy) {
            return;
        }
        mBusy = true;
        mIo.execute(() -> {
            BoardSource.Loaded got = null;
            String err = null;
            try {
                got = s.posts(mBoardId);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final BoardSource.Loaded loaded = got;
            final String error = err;
            runOnUiThread(() -> {
                mBusy = false;
                if (isFinishing()) {
                    return;
                }
                if (loaded == null) {
                    if (mPosts.isEmpty()) {
                        Language.setText(mEmpty, Language.msg(R.string.l10n_native_chat_load_failed, error));
                        mEmpty.setVisibility(View.VISIBLE);
                    }
                    return;
                }
                boolean grew = loaded.posts.size() != mPosts.size();
                mBoard = loaded.board;
                mPosts.clear();
                mPosts.addAll(loaded.posts);
                render(zScroll || grew);
                if (grew || zScroll) {
                    mIo.execute(() -> {
                        try {
                            s.markRead(mBoardId);
                        } catch (Exception ignored) {
                        }
                    });
                }
            });
        });
    }

    private CharSequence subtitle(BoardSource.BoardRow b) {
        if (b == null) {
            return "";
        }
        if (b.joining) {
            return Language.msg(R.string.l10n_board_waiting);
        }
        if (b.removed) {
            return Language.msg(R.string.l10n_board_removed_you);
        }
        return Language.msg(b.open ? R.string.l10n_board_open_members : R.string.l10n_board_invite_members, b.members);
    }

    private void render(boolean zScroll) {
        BoardSource.BoardRow b = mBoard;
        if (b != null) {
            mTitle.setText(b.name.isEmpty() ? Language.text(this, R.string.l10n_board_untitled) : b.name);
            Avatars.apply(findViewById(R.id.chat_avatar), b.id, b.name);
            if (mReplyTo == null) {
                Language.setText(mSubtitle, subtitle(b));
            }
            boolean can = b.canPost && !b.joining && !b.removed;
            mInput.setEnabled(can);
            findViewById(R.id.btn_chat_send).setEnabled(can);
        }
        mRows.clear();
        Map<String, List<BoardSource.PostRow>> replies = new HashMap<>();
        Map<String, BoardSource.PostRow> byId = new LinkedHashMap<>();
        for (BoardSource.PostRow p : mPosts) {
            byId.put(p.id, p);
        }
        List<BoardSource.PostRow> tops = new ArrayList<>();
        for (BoardSource.PostRow p : mPosts) {
            if (!p.re.isEmpty() && byId.containsKey(p.re)) {
                List<BoardSource.PostRow> l = replies.get(p.re);
                if (l == null) {
                    l = new ArrayList<>();
                    replies.put(p.re, l);
                }
                l.add(p);
            } else {
                tops.add(p);
            }
        }
        String lastSender = null;
        for (BoardSource.PostRow p : tops) {
            Row r = new Row();
            r.post = p;
            r.firstOfSender = !p.from.equals(lastSender);
            lastSender = p.from;
            mRows.add(r);
            List<BoardSource.PostRow> rs = replies.get(p.id);
            if (rs != null) {
                for (BoardSource.PostRow x : rs) {
                    Row rr = new Row();
                    rr.post = x;
                    rr.reply = true;
                    rr.firstOfSender = true;
                    mRows.add(rr);
                }
                lastSender = null;
            }
        }
        mAdapter.notifyDataSetChanged();
        if (b != null && b.joining) {
            Language.setText(mEmpty, Language.msg(R.string.l10n_board_waiting_long));
            mEmpty.setVisibility(View.VISIBLE);
        } else if (mRows.isEmpty()) {
            Language.setText(mEmpty, Language.msg(R.string.l10n_board_no_posts));
            mEmpty.setVisibility(View.VISIBLE);
        } else {
            mEmpty.setVisibility(View.GONE);
        }
        if (zScroll && !mRows.isEmpty()) {
            mList.scrollToPosition(mRows.size() - 1);
        }
    }

    // ---- posting ----

    private void setReply(BoardSource.PostRow zPost) {
        mReplyTo = zPost;
        if (zPost == null) {
            Language.setText(mSubtitle, subtitle(mBoard));
        } else {
            String who = zPost.mine ? Language.text(this, R.string.l10n_chat_you) : zPost.name.isEmpty() ? zPost.from : zPost.name;
            Language.setText(mSubtitle, Language.join(Language.msg(R.string.l10n_board_replying_to, who), " · ",
                    Language.msg(R.string.l10n_board_cancel_reply)));
            mInput.requestFocus();
        }
    }

    private void send() {
        BoardSource s = source();
        if (s == null) {
            toast(Language.text(this, R.string.l10n_native_chat_starting));
            return;
        }
        final String body = mInput.getText().toString().trim();
        if (body.isEmpty()) {
            return;
        }
        final String re = mReplyTo == null ? "" : mReplyTo.id;
        mInput.setText("");
        setReply(null);
        mIo.execute(() -> {
            try {
                BoardSource.PostRow p = s.post(mBoardId, body, re);
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    mPosts.add(p);
                    render(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    toast(Language.text(this, R.string.l10n_native_chat_operation_failed, String.valueOf(e.getMessage())));
                    mInput.setText(body);
                });
            }
        });
    }

    private void postMenu(BoardSource.PostRow zPost) {
        List<Integer> items = new ArrayList<>();
        if (mBoard != null && mBoard.canPost && !mBoard.joining) {
            items.add(R.string.l10n_board_reply);
        }
        items.add(R.string.l10n_chat_copy_message);
        new LanguageDialog(this)
                .actions(items, (d, which) -> {
                    int choice = items.get(which);
                    if (choice == R.string.l10n_board_reply) {
                        setReply(zPost);
                    } else {
                        ClipboardManager cm = getSystemService(ClipboardManager.class);
                        String text = com.eurobuddha.maxima.core.chat.ChatMedia.isMedia(zPost.body)
                                ? com.eurobuddha.maxima.core.chat.ChatMedia.caption(zPost.body) : zPost.body;
                        cm.setPrimaryClip(ClipData.newPlainText("post", text));
                        toast(Language.text(this, R.string.l10n_common_copied));
                    }
                })
                .show();
    }

    // ---- details ----

    private void details() {
        BoardSource.BoardRow b = mBoard;
        BoardSource s = source();
        if (b == null || s == null) {
            return;
        }
        Language.TextBuilder sb = new Language.TextBuilder();
        sb.append(subtitle(b)).append('\n');
        if (b.admin) {
            sb.append(Language.msg(R.string.l10n_board_admin)).append('\n');
        }
        sb.append('\n').append(Language.msg(b.open ? R.string.l10n_board_link_open_help : R.string.l10n_board_link_invite_help));
        LanguageDialog d = new LanguageDialog(this)
                .setTitle(b.name.isEmpty() ? Language.text(this, R.string.l10n_board_untitled) : b.name)
                .setMessage(sb.build())
                .setPositiveButton(Language.msg(R.string.l10n_board_link), (x, w) -> mIo.execute(() -> {
                    try {
                        String link = s.link(mBoardId);
                        runOnUiThread(() -> BoardFlows.showLink(this, b, link));
                    } catch (Exception e) {
                        runOnUiThread(() -> toast(Language.text(this, R.string.l10n_native_chat_operation_failed, String.valueOf(e.getMessage()))));
                    }
                }))
                .setNegativeButton(Language.msg(R.string.l10n_board_leave), (x, w) -> confirmLeave());
        d.setNeutralButton(Language.msg(b.admin ? R.string.l10n_board_members : R.string.l10n_board_members), (x, w) -> people());
        d.show();
    }

    private void confirmLeave() {
        new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_board_leave))
                .setMessage(Language.msg(R.string.l10n_board_leave_confirm))
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_board_leave), (d, w) -> mIo.execute(() -> {
                    try {
                        source().leave(mBoardId);
                        runOnUiThread(() -> {
                            toast(Language.text(this, R.string.l10n_board_left));
                            finish();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> toast(Language.text(this, R.string.l10n_native_chat_operation_failed, String.valueOf(e.getMessage()))));
                    }
                }))
                .show();
    }

    /** Members known from their posts, people asking to join (admin, invite-only), rename. */
    private void people() {
        BoardSource.BoardRow b = mBoard;
        BoardSource s = source();
        if (b == null || s == null) {
            return;
        }
        mIo.execute(() -> {
            List<BoardSource.KnockRow> knocks = new ArrayList<>();
            if (b.admin && !b.open) {
                try {
                    knocks = s.knocks(mBoardId);
                } catch (Exception ignored) {
                }
            }
            final List<BoardSource.KnockRow> ks = knocks;
            runOnUiThread(() -> peopleDialog(b, ks));
        });
    }

    private void peopleDialog(BoardSource.BoardRow b, List<BoardSource.KnockRow> zKnocks) {
        Map<String, String> members = new LinkedHashMap<>();
        for (BoardSource.PostRow p : mPosts) {
            if (!p.mine && !members.containsKey(p.from)) {
                members.put(p.from, p.name.isEmpty() ? p.from : p.name);
            }
        }
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (b.admin) {
            labels.add(Language.msg(R.string.l10n_common_rename));
            actions.add(this::rename);
        }
        for (BoardSource.KnockRow k : zKnocks) {
            labels.add(Language.join(Language.msg(R.string.l10n_board_approve), ": ", k.name.isEmpty() ? k.fp : k.name));
            actions.add(() -> act(() -> source().approve(mBoardId, k.fp), R.string.l10n_board_approved));
        }
        for (Map.Entry<String, String> m : members.entrySet()) {
            if (b.admin) {
                labels.add(Language.join(Language.msg(R.string.l10n_board_remove), ": ", m.getValue()));
                actions.add(() -> new LanguageDialog(this)
                        .setTitle(m.getValue())
                        .setMessage(Language.msg(R.string.l10n_board_remove_confirm))
                        .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                        .setPositiveButton(Language.msg(R.string.l10n_board_remove), (d, w) ->
                                act(() -> source().remove(mBoardId, m.getKey()), R.string.l10n_board_removed))
                        .show());
            } else {
                labels.add(m.getValue());
                actions.add(() -> { });
            }
        }
        if (labels.isEmpty()) {
            toast(Language.text(this, R.string.l10n_board_members_quiet));
            return;
        }
        new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_board_members))
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(Language.msg(R.string.l10n_language_close), null)
                .show();
    }

    private void rename() {
        final EditText input = new EditText(this);
        input.setText(mBoard == null ? "" : mBoard.name);
        input.setSingleLine(true);
        int pad = Ui.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_common_rename))
                .setView(input)
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_common_rename), (d, w) -> {
                    final String n = input.getText().toString().trim();
                    if (!n.isEmpty()) {
                        act(() -> source().rename(mBoardId, n), R.string.l10n_common_renamed);
                    }
                })
                .show();
    }

    private interface Work {
        void run() throws Exception;
    }

    private void act(Work zWork, int zOkMsg) {
        mIo.execute(() -> {
            try {
                zWork.run();
                runOnUiThread(() -> {
                    toast(Language.text(this, zOkMsg));
                    load(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast(Language.text(this, R.string.l10n_native_chat_operation_failed, String.valueOf(e.getMessage()))));
            }
        });
    }

    // ---- photos: shrunk on this device, published once, read by every member ----

    private static final int PICK_PHOTO = 71;
    private static final int TAKE_PHOTO = 72;
    private android.net.Uri mCaptureUri;
    private com.eurobuddha.maxima.app.chat.ChatImageViewer mPhotoViewer;
    private final android.util.LruCache<String, android.graphics.Bitmap> mImageCache =
            new android.util.LruCache<String, android.graphics.Bitmap>(
                    (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String k, android.graphics.Bitmap b) {
                    return b.getByteCount();
                }
            };
    private final java.util.Set<String> mImageFetching = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.Set<String> mImageFailed = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private void pickPhoto() {
        if (mBoard == null || !mBoard.canPost || mBoard.joining) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, Language.text(this, R.string.l10n_android_chat_send_photo)), PICK_PHOTO);
    }

    /** Capture into our own cache via the app's payloads FileProvider (same as the chat screen). */
    private void takePhoto() {
        if (mBoard == null || !mBoard.canPost || mBoard.joining) {
            return;
        }
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "maximapayloads");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "board-capture.jpg");
            mCaptureUri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".payloads", f);
            Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, mCaptureUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            for (android.content.pm.ResolveInfo ri : getPackageManager()
                    .queryIntentActivities(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)) {
                grantUriPermission(ri.activityInfo.packageName, mCaptureUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            startActivityForResult(i, TAKE_PHOTO);
        } catch (Exception e) {
            toast(Language.text(this, R.string.l10n_android_chat_no_camera));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == TAKE_PHOTO && res == RESULT_OK && mCaptureUri != null) {
            promptCaption(mCaptureUri);
        } else if (req == PICK_PHOTO && res == RESULT_OK && data != null && data.getData() != null) {
            promptCaption(data.getData());
        }
    }

    private void promptCaption(final android.net.Uri uri) {
        mIo.execute(() -> {
            android.graphics.Bitmap preview = null;
            try {
                byte[] jpeg = BoardImages.readScaledJpeg(getContentResolver(), uri, 600);
                preview = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            } catch (Exception ignored) {
                // no preview: still let them caption and send
            }
            final android.graphics.Bitmap thumb = preview;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                android.widget.LinearLayout box = new android.widget.LinearLayout(this);
                box.setOrientation(android.widget.LinearLayout.VERTICAL);
                box.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), 0);
                if (thumb != null) {
                    android.widget.ImageView iv = new android.widget.ImageView(this);
                    iv.setImageBitmap(thumb);
                    iv.setAdjustViewBounds(true);
                    iv.setMaxHeight(Ui.dp(this, 240));
                    iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = Ui.dp(this, 12);
                    iv.setLayoutParams(lp);
                    box.addView(iv);
                }
                final EditText cap = new EditText(this);
                Language.hint(cap, Language.msg(R.string.l10n_android_chat_caption));
                cap.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                cap.setMaxLines(4);
                box.addView(cap);
                new LanguageDialog(this)
                        .setTitle(Language.msg(R.string.l10n_android_chat_send_photo))
                        .setView(box)
                        .setPositiveButton(Language.msg(R.string.l10n_board_post), (d, w) -> sendPhoto(uri, cap.getText().toString().trim()))
                        .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                        .show();
            });
        });
    }

    private void sendPhoto(final android.net.Uri uri, final String caption) {
        final BoardSource s = source();
        if (s == null) {
            toast(Language.text(this, R.string.l10n_native_chat_starting));
            return;
        }
        final String re = mReplyTo == null ? "" : mReplyTo.id;
        setReply(null);
        toast(Language.text(this, R.string.l10n_android_chat_sending_photo));
        mIo.execute(() -> {
            try {
                byte[] jpeg = BoardImages.readScaledJpeg(getContentResolver(), uri, BoardImages.MAX_EDGE_PX);
                final BoardSource.PostRow p = s.postImage(mBoardId, jpeg, caption, re);
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    if (p != null) {
                        mPosts.add(p);
                        render(true);
                    } else {   // published on the account's own lane: pick it up shortly
                        mUi.postDelayed(() -> load(true), 4000);
                        mUi.postDelayed(() -> load(true), 15000);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast(Language.text(this, R.string.l10n_native_chat_operation_failed, String.valueOf(e.getMessage()))));
            }
        });
    }

    /** Cache hit, else fetch + decode off the UI thread and redraw. */
    private void bindImage(android.widget.ImageView view, String body, String id) {
        android.graphics.Bitmap cached = mImageCache.get(id);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setOnClickListener(v -> openImage(id));
            return;
        }
        view.setImageResource(R.drawable.ic_photo);
        view.setOnClickListener(null);
        if (mImageFailed.contains(id) || !mImageFetching.add(id)) {
            return;
        }
        final String ref = com.eurobuddha.maxima.core.chat.ChatMedia.ref(body);
        final BoardSource s = source();
        mIo.execute(() -> {
            try {
                android.graphics.Bitmap bmp = BoardImages.decodeBounded(
                        BoardImages.fetchMediaBytes(s == null ? null : s.media(), ref));
                if (bmp != null) {
                    mImageCache.put(id, bmp);
                    runOnUiThread(() -> render(false));
                    return;
                }
                mImageFailed.add(id);
            } catch (Exception e) {
                mImageFailed.add(id);
            } finally {
                mImageFetching.remove(id);
            }
        });
    }

    private void openImage(String id) {
        List<com.eurobuddha.maxima.core.chat.ChatImages.Photo> photos = new ArrayList<>();
        for (BoardSource.PostRow p : mPosts) {
            photos.add(new com.eurobuddha.maxima.core.chat.ChatImages.Photo(p.id, p.body, p.ts));
        }
        com.eurobuddha.maxima.core.chat.ChatImages gallery = new com.eurobuddha.maxima.core.chat.ChatImages(photos, id);
        if (gallery.current() == null) {
            return;
        }
        if (mPhotoViewer != null) {
            mPhotoViewer.dismiss();
        }
        final BoardSource s = source();
        mPhotoViewer = new com.eurobuddha.maxima.app.chat.ChatImageViewer(this, gallery, mImageCache,
                photo -> BoardImages.decodeBounded(BoardImages.fetchMediaBytes(s == null ? null : s.media(),
                        com.eurobuddha.maxima.core.chat.ChatMedia.ref(photo.body))),
                this::saveImage, this::shareImage);
        mPhotoViewer.show();
    }

    private void saveImage(final android.graphics.Bitmap b) {
        if (b == null) {
            return;
        }
        mIo.execute(() -> {
            try {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "parlons-board-" + System.currentTimeMillis() + ".jpg");
                cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, os);
                }
                runOnUiThread(() -> toast(Language.text(this, R.string.l10n_android_chat_saved)));
            } catch (Exception e) {
                runOnUiThread(() -> toast(Language.text(this, R.string.l10n_android_chat_save_failed)));
            }
        });
    }

    private void shareImage(final android.graphics.Bitmap b) {
        if (b == null) {
            return;
        }
        mIo.execute(() -> {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "maximapayloads");
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, "board-share.jpg");
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                    b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, os);
                }
                final android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".payloads", f);
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("image/jpeg");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, Language.text(this, R.string.l10n_android_chat_share_photo)));
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast(Language.text(this, R.string.l10n_android_chat_share_failed)));
            }
        });
    }

    // ---- rows ----

    private final class VH extends RecyclerView.ViewHolder {
        final android.widget.LinearLayout row;
        final View bubble;
        final TextView who;
        final TextView body;
        final TextView meta;
        final android.widget.ImageView image;

        VH(View v) {
            super(v);
            row = v.findViewById(R.id.bubble_row);
            bubble = v.findViewById(R.id.bubble);
            who = v.findViewById(R.id.bubble_sender);
            body = v.findViewById(R.id.bubble_body);
            meta = v.findViewById(R.id.bubble_meta);
            image = v.findViewById(R.id.bubble_image);
            BoardFlows.gone(v, R.id.bubble_audio);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(BoardActivity.this).inflate(R.layout.item_message, parent, false));
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            Row r = mRows.get(position);
            BoardSource.PostRow p = r.post;
            int dp12 = Ui.dp(BoardActivity.this, 12);
            h.row.setGravity(p.mine ? Gravity.END : Gravity.START);
            h.row.setPadding(r.reply && !p.mine ? Ui.dp(BoardActivity.this, 40) : dp12,
                    r.firstOfSender ? Ui.dp(BoardActivity.this, 7) : Ui.dp(BoardActivity.this, 1),
                    r.reply && p.mine ? Ui.dp(BoardActivity.this, 40) : dp12, Ui.dp(BoardActivity.this, 1));
            h.bubble.setBackgroundResource(p.mine
                    ? (r.firstOfSender ? R.drawable.bubble_out : R.drawable.bubble_out_mid)
                    : (r.firstOfSender ? R.drawable.bubble_in : R.drawable.bubble_in_mid));
            h.body.setTextColor(getColor(p.mine ? R.color.ux_bubble_out_text : R.color.ux_bubble_in_text));
            if (!p.mine && r.firstOfSender) {
                h.who.setVisibility(View.VISIBLE);
                h.who.setText(p.name.isEmpty() ? p.from : p.name);
            } else {
                h.who.setVisibility(View.GONE);
            }
            com.eurobuddha.maxima.app.chat.ChatLinkText.reset(h.body);
            if (com.eurobuddha.maxima.core.chat.ChatMedia.isMedia(p.body)) {
                String cap = com.eurobuddha.maxima.core.chat.ChatMedia.caption(p.body);
                Language.setText(h.body, cap);
                h.body.setVisibility(cap.isEmpty() ? View.GONE : View.VISIBLE);
                h.image.setVisibility(View.VISIBLE);
                bindImage(h.image, p.body, p.id);
            } else {
                h.image.setVisibility(View.GONE);
                h.image.setImageDrawable(null);
                h.body.setVisibility(View.VISIBLE);
                Language.setText(h.body, p.body);
            }
            if (h.body.getVisibility() == View.VISIBLE) {
                com.eurobuddha.maxima.app.chat.ChatLinkText.bind(h.body, h.body.getText().toString(), () -> postMenu(p));
            }
            h.meta.setVisibility(View.VISIBLE);
            h.meta.setText(Language.shortStamp(p.ts));
            h.bubble.setOnLongClickListener(v -> {
                postMenu(p);
                return true;
            });
            h.bubble.setOnClickListener(v -> postMenu(p));
        }
    }
}
