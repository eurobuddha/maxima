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
    private View mScrollBottom;
    private TextView mEmpty;
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

    /** Poll, while the chat is open, for received payments whose funds have
     *  landed on-chain, flipping their bubble from Pending to Confirmed. */
    private final Runnable mPayWatch = new Runnable() {
        @Override
        public void run() {
            watchPayments();
            mHandler.postDelayed(this, 20_000);
        }
    };

    private void watchPayments() {
        if (mSender == null) {
            return;
        }
        ChatEngine chat = MaximaService.chat();
        if (chat == null) {
            return;
        }
        for (ChatEngine.Entry e : chat.conversation(mConversation)) {
            if (e.mine || !com.eurobuddha.maxima.core.chat.ChatPay.isPayment(e.body)) {
                continue;   // we only watch payments received BY us
            }
            final String tx = com.eurobuddha.maxima.core.chat.ChatPay.txid(e.body);
            if (tx.isEmpty() || isPayConfirmed(tx)) {
                continue;
            }
            mSender.hasIncomingCoin(
                    com.eurobuddha.maxima.core.chat.ChatPay.amount(e.body), arrived -> {
                        if (arrived) {
                            markPayConfirmed(tx);
                            runOnUiThread(ChatActivity.this::render);
                        }
                    });
        }
    }

    private boolean isPayConfirmed(String zTxid) {
        return getSharedPreferences("maxima_pay_confirmed", MODE_PRIVATE)
                .getBoolean(zTxid, false);
    }

    private void markPayConfirmed(String zTxid) {
        getSharedPreferences("maxima_pay_confirmed", MODE_PRIVATE)
                .edit().putBoolean(zTxid, true).apply();
    }

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
        final View appbar = findViewById(R.id.chat_appbar);
        final View composer = findViewById(R.id.chat_composer);
        final int barTop = appbar.getPaddingTop();          // XML base
        final int compBottom = composer.getPaddingBottom();  // XML base
        // Extend the dark app bar UP into the status bar, and keep the composer
        // above the nav bar / keyboard - so the header colour fills the top like
        // WhatsApp, not a pale strip above it.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, 0, bars.right, 0);
            appbar.setPadding(appbar.getPaddingLeft(), barTop + bars.top,
                    appbar.getPaddingRight(), appbar.getPaddingBottom());
            composer.setPadding(composer.getPaddingLeft(), composer.getPaddingTop(),
                    composer.getPaddingRight(), compBottom + Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        getWindow().setStatusBarColor(getColor(R.color.ux_header));
        androidx.core.view.WindowInsetsControllerCompat wic =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), root);
        if (wic != null) {
            wic.setAppearanceLightStatusBars(false);   // dark header → light status icons
        }

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
        mEmpty = findViewById(R.id.chat_empty);
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

        final android.widget.ImageButton send = findViewById(R.id.btn_chat_send);
        send.setOnClickListener(v -> {
            if (mInput.getText().toString().trim().length() > 0) {
                send();
            } else {
                toast("Voice notes are coming soon");
            }
        });
        findViewById(R.id.btn_chat_info).setOnClickListener(v -> showInfo());
        android.widget.ImageButton themeBtn = findViewById(R.id.btn_chat_theme);
        themeBtn.setImageResource(com.eurobuddha.maxima.app.ChatPrefs.appearanceIcon(this));
        themeBtn.setOnClickListener(v -> {
            com.eurobuddha.maxima.app.ChatPrefs.cycleAppearance(this);
            toast("Theme: " + com.eurobuddha.maxima.app.ChatPrefs.appearanceLabel(this));
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    com.eurobuddha.maxima.app.ChatPrefs.nightMode(this));
        });
        findViewById(R.id.btn_chat_attach).setOnClickListener(v -> attachSheet());
        findViewById(R.id.btn_chat_camera).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btn_chat_video).setOnClickListener(v -> toast("Calls are coming soon"));
        findViewById(R.id.btn_chat_call).setOnClickListener(v -> toast("Calls are coming soon"));
        findViewById(R.id.btn_chat_emoji).setOnClickListener(v -> {
            mInput.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) {
                imm.showSoftInput(mInput, 0);
            }
        });

        // The FAB shows a mic when the field is empty and the send plane once
        // there's text (WhatsApp), swapping with a small punch.
        wireSendReveal(send);

        initPayments();
    }

    /** For a 1:1 chat, warm the wallet and share our receive address so the
     *  contact can pay us without pasting anything (mirrors FreezePeach). */
    private void initPayments() {
        ChatEngine chat = MaximaService.chat();
        if (chat == null || chat.group(mConversation) != null) {
            return;   // payments are one-to-one for now
        }
        mSender = new com.eurobuddha.maxima.app.wallet.PaymentSender(this);
        mSender.whenReady(() -> {
            String my = mSender.myAddress();
            ChatEngine c = MaximaService.chat();
            MaximaNode node = MaximaService.node();
            if (my == null || c == null || node == null) {
                return;
            }
            Contact contact = node.contact(mConversation);
            if (contact != null) {
                c.shareWalletAddress(contact, my);   // off-main (whenReady runs on a worker)
            }
        });
    }

    /** Swap the FAB glyph: mic when the field is empty, send plane when there's
     *  text - with a small scale-punch on the change (WhatsApp). */
    private void wireSendReveal(final android.widget.ImageButton zSend) {
        boolean startText = mInput.getText().toString().trim().length() > 0;
        zSend.setImageResource(startText ? R.drawable.ic_send : R.drawable.ic_mic);
        mInput.addTextChangedListener(new android.text.TextWatcher() {
            boolean hasText = startText;

            @Override
            public void afterTextChanged(android.text.Editable s) {
                boolean has = s.toString().trim().length() > 0;
                if (has == hasText) {
                    return;
                }
                hasText = has;
                zSend.setImageResource(has ? R.drawable.ic_send : R.drawable.ic_mic);
                zSend.animate().scaleX(1.18f).scaleY(1.18f).setDuration(90)
                        .withEndAction(() -> zSend.animate().scaleX(1f).scaleY(1f)
                                .setDuration(130).setInterpolator(
                                        new android.view.animation.OvershootInterpolator(2.2f))
                                .start()).start();
            }

            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }
        });
    }

    private static final int PICK_PHOTO = 71;
    private static final int TAKE_PHOTO = 72;
    private android.net.Uri mCaptureUri;
    private com.eurobuddha.maxima.app.wallet.PaymentSender mSender;

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
            view.setOnClickListener(v -> openImage(id));
            return;
        }
        view.setImageResource(R.drawable.ic_photo);
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
        final android.graphics.Bitmap b = mImageCache.get(id);
        if (b == null) {
            return;
        }
        // MediaStore insert + JPEG compress + stream write is disk I/O - off the
        // main thread, or a big photo can ANR.
        new Thread(() -> {
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
                runOnUiThread(() -> toast("Saved to Photos"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Save failed"));
            }
        }, "chat-save-image").start();
    }

    /** Tap a photo to open it full-screen in the system viewer (pinch-zoom,
     *  share, save all come for free there). We write the decoded bitmap to our
     *  own cache and hand it out through the existing FileProvider. */
    private void openImage(String id) {
        final android.graphics.Bitmap b = mImageCache.get(id);
        if (b == null) {
            return;
        }
        // Compress + write the cache file off the main thread; only the launch
        // itself has to be back on it.
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "maximapayloads");
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, "view.jpg");
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                    b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, os);
                }
                final android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, "com.eurobuddha.maxima.app.payloads", f);
                runOnUiThread(() -> {
                    try {
                        android.content.Intent i = new android.content.Intent(
                                android.content.Intent.ACTION_VIEW);
                        i.setDataAndType(uri, "image/jpeg");
                        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Could not open image");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Could not open image"));
            }
        }, "chat-open-image").start();
    }

    /** Send Minima to this contact from inside the chat. */
    private void payContact() {
        final ChatEngine chat = MaximaService.chat();
        final MaximaNode node = MaximaService.node();
        if (chat == null || node == null) {
            toast("Transport not running");
            return;
        }
        if (chat.group(mConversation) != null) {
            toast("Payments are one-to-one for now");
            return;
        }
        final Contact contact = node.contact(mConversation);
        if (contact == null) {
            toast("Not in your contacts");
            return;
        }
        final String to = chat.walletAddress(mConversation);
        if (to == null || to.isEmpty()) {
            toast("No wallet address yet — ask them to open this chat");
            return;
        }
        if (mSender == null) {
            mSender = new com.eurobuddha.maxima.app.wallet.PaymentSender(this);
        }

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText amt = new EditText(this);
        amt.setHint("Amount (MINIMA)");
        amt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amt.setTextColor(getColor(R.color.ux_text));
        amt.setHintTextColor(getColor(R.color.ux_subtext));
        box.addView(amt);
        final EditText memo = new EditText(this);
        memo.setHint("Note (optional)");
        memo.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        memo.setTextColor(getColor(R.color.ux_text));
        memo.setHintTextColor(getColor(R.color.ux_subtext));
        box.addView(memo);

        new AlertDialog.Builder(this)
                .setTitle("Pay " + Names.of(node, chat, mConversation))
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (d, w) -> {
                    String amtStr = amt.getText().toString().trim();
                    final String note = memo.getText().toString().trim();
                    final org.minima.objects.base.MiniNumber amount;
                    try {
                        amount = new org.minima.objects.base.MiniNumber(amtStr);
                    } catch (Exception e) {
                        toast("Bad amount");
                        return;
                    }
                    if (!amount.isMore(org.minima.objects.base.MiniNumber.ZERO)) {
                        toast("Amount must be more than zero");
                        return;
                    }
                    confirmPay(chat, contact, to, amount, note);
                })
                .show();
    }

    private void confirmPay(final ChatEngine chat, final Contact contact, final String to,
                            final org.minima.objects.base.MiniNumber amount, final String note) {
        new AlertDialog.Builder(this)
                .setTitle("Send " + amount.toString() + " MINIMA?")
                .setMessage("To " + contact.name + "\n\nThis signs and broadcasts a real "
                        + "transaction and cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Confirm", (d, w) -> {
                    toast("Signing payment…");
                    final ChatEngine.Entry[] pending = new ChatEngine.Entry[1];
                    mSender.send(to, amount, new com.eurobuddha.maxima.app.wallet.PaymentSender.Cb() {
                        @Override
                        public void onProgress(String zStep) {
                            runOnUiThread(() -> toast(zStep));
                        }

                        @Override
                        public void onBuilt(String zTxid) {
                            // Signed - show the sender a pending bubble at once,
                            // before the broadcast round-trip. Not sent to the
                            // peer yet.
                            pending[0] = chat.beginPayment(contact, amount.toString(),
                                    "MINIMA", note, zTxid);
                            mLastSendWasMine = true;
                            // Signing just burned one key-use; surface it so the
                            // count is visible even from the chat.
                            final String uses = mSender.usesLine();
                            runOnUiThread(() -> {
                                render();
                                if (!uses.isEmpty()) {
                                    toast(uses);
                                }
                            });
                        }

                        @Override
                        public void onSent(String zTxid) {
                            // Broadcast accepted - now tell the peer (worker
                            // thread: a network deliver), then refresh.
                            if (pending[0] != null) {
                                chat.completePayment(contact, pending[0]);
                            }
                            com.eurobuddha.maxima.app.wallet.WalletLedger.add(
                                    ChatActivity.this, true, amount.toString(), "MINIMA",
                                    contact.name, zTxid);
                            runOnUiThread(() -> {
                                render();
                                toast("Payment sent");
                            });
                        }

                        @Override
                        public void onError(String zMessage) {
                            if (pending[0] != null) {
                                chat.failPayment(pending[0]);
                            }
                            runOnUiThread(() -> {
                                render();
                                toast(zMessage);
                            });
                        }
                    });
                })
                .show();
    }

    /** The attach "+" sheet: gallery, and (1:1 only) a payment - the camera has
     *  its own button in the bar now, WhatsApp-style. */
    private void attachSheet() {
        ChatEngine chat = MaximaService.chat();
        MaximaNode node = MaximaService.node();
        boolean canPay = chat != null && node != null
                && chat.group(mConversation) == null
                && node.contact(mConversation) != null;
        final List<String> items = new ArrayList<>();
        items.add("Photo library");
        if (canPay) {
            items.add("Send payment");
        }
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String c = items.get(which);
                    if ("Send payment".equals(c)) {
                        payContact();
                    } else {
                        pickPhoto();
                    }
                })
                .show();
    }

    private void pickPhoto() {
        android.content.Intent i = new android.content.Intent(
                android.content.Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(android.content.Intent.createChooser(i, "Send photo"), PICK_PHOTO);
    }

    /** Capture straight from the camera into our own cache, via the existing
     *  FileProvider, then caption + send it like any other photo. */
    private void takePhoto() {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "maximapayloads");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "capture.jpg");
            mCaptureUri = androidx.core.content.FileProvider.getUriForFile(
                    this, "com.eurobuddha.maxima.app.payloads", f);
            android.content.Intent i = new android.content.Intent(
                    android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, mCaptureUri);
            // FLAG_GRANT_WRITE only covers the intent's DATA uri, not one passed
            // in an extra - so the output uri must be granted to the camera app
            // explicitly, or OEM camera apps that don't self-grant get a
            // SecurityException and the capture silently returns nothing.
            i.addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            for (android.content.pm.ResolveInfo ri : getPackageManager()
                    .queryIntentActivities(i,
                            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)) {
                grantUriPermission(ri.activityInfo.packageName, mCaptureUri,
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            startActivityForResult(i, TAKE_PHOTO);
        } catch (Exception e) {
            toast("No camera available");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
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

    /** Preview the picked photo and let the sender add a caption before it goes.
     *  The data layer already carries captions - this is just the UI for one. */
    private void promptCaption(final android.net.Uri uri) {
        new Thread(() -> {
            android.graphics.Bitmap preview = null;
            try {
                byte[] jpeg = readScaledJpeg(uri, 600);
                preview = android.graphics.BitmapFactory
                        .decodeByteArray(jpeg, 0, jpeg.length);
            } catch (Exception ignored) {
                // no preview - still let them caption and send
            }
            final android.graphics.Bitmap thumb = preview;
            runOnUiThread(() -> showCaptionDialog(uri, thumb));
        }, "chat-preview").start();
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
        cap.setHintTextColor(getResources().getColor(R.color.ux_subtext));
        cap.setTextColor(getResources().getColor(R.color.ux_text));
        cap.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        cap.setMaxLines(4);
        box.addView(cap);
        new AlertDialog.Builder(this)
                .setTitle("Send photo")
                .setView(box)
                .setPositiveButton("Send",
                        (d, w) -> sendPhoto(uri, cap.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendPhoto(final android.net.Uri uri, final String caption) {
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
                    chat.sendGroupMedia(g.id, jpeg, "image/jpeg", caption);
                } else {
                    chat.sendMedia(c, jpeg, "image/jpeg", caption);
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
            // Rotate the pixels to match the source's EXIF orientation, then
            // re-encode - otherwise the tag is lost and the photo ships sideways.
            bmp = applyExifOrientation(bmp, all);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bos);
            return bos.toByteArray();
        }
    }

    /** Apply the JPEG's EXIF orientation to the decoded bitmap so it is upright.
     *  Best-effort: on any failure the original bitmap is returned unchanged. */
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
                    return zBmp;   // normal / undefined - nothing to do
            }
            android.graphics.Bitmap rotated = android.graphics.Bitmap.createBitmap(
                    zBmp, 0, 0, zBmp.getWidth(), zBmp.getHeight(), m, true);
            if (rotated != zBmp) {
                zBmp.recycle();
            }
            return rotated;
        } catch (Exception e) {
            return zBmp;
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
        mHandler.postDelayed(mPayWatch, 3_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatHub.setForeground("");
        ChatHub.unregister(this);
        mHandler.removeCallbacks(mRender);
        mHandler.removeCallbacks(mPayWatch);
        ChatEngine chat = MaximaService.chat();
        if (chat != null) {
            chat.markRead(mConversation);
            // Leaving the thread is a natural point to write out the ticks that
            // arrived while it was open.
            chat.flushState();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSender != null) {
            mSender.close();
            mSender = null;
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
        if (conv.isEmpty()) {
            mEmpty.setText(g != null
                    ? "This is the start of the “" + name + "” group.\n\nSay something."
                    : "This is the start of your conversation with " + name
                    + ".\n\nMessages are end-to-end encrypted - only the two of you "
                    + "can read them.");
            mEmpty.setVisibility(View.VISIBLE);
        } else {
            mEmpty.setVisibility(View.GONE);
        }
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

    /** Long-press a message: copy its text, or see when it was sent + its state. */
    private void showMessageMenu(ChatEngine.Entry e) {
        boolean media = com.eurobuddha.maxima.core.chat.ChatMedia.isMedia(e.body);
        final String copyText = media
                ? com.eurobuddha.maxima.core.chat.ChatMedia.caption(e.body) : e.body;
        final boolean haveImage = media && mImageCache.containsKey(e.id);
        final List<String> items = new ArrayList<>();
        if (copyText != null && !copyText.isEmpty()) {
            items.add("Copy");
        }
        if (haveImage) {
            items.add("Save photo");
        }
        items.add("Info");
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String choice = items.get(which);
                    if ("Copy".equals(choice)) {
                        android.content.ClipboardManager cm =
                                getSystemService(android.content.ClipboardManager.class);
                        cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("message", copyText));
                        toast("Copied");
                    } else if ("Save photo".equals(choice)) {
                        saveImage(e.id);
                    } else {
                        showMessageInfo(e);
                    }
                })
                .show();
    }

    private void showMessageInfo(ChatEngine.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.mine ? "You" : Names.contact(MaximaService.node(), e.sender)).append('\n');
        sb.append(new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK)
                .format(new Date(e.time)));
        if (e.mine) {
            sb.append("\n\nStatus: ").append(stateWord(e.state));
        }
        // A payment carries a transaction id - show it IN FULL (it is the only
        // way to look the payment up on-chain), never shortened.
        final String txid = com.eurobuddha.maxima.core.chat.ChatPay.isPayment(e.body)
                ? com.eurobuddha.maxima.core.chat.ChatPay.txid(e.body) : "";
        if (!txid.isEmpty()) {
            sb.append("\n\nTransaction:\n").append(txid);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Message")
                .setMessage(sb.toString())
                .setPositiveButton("Close", null);
        if (!txid.isEmpty()) {
            b.setNeutralButton("Copy tx id", (d, w) -> {
                android.content.ClipboardManager cm =
                        getSystemService(android.content.ClipboardManager.class);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("txid", txid));
                toast("Transaction id copied");
            });
        }
        b.show();
    }

    private static String stateWord(String zState) {
        if (Receipt.FAILED.equals(zState)) {
            return "Failed to send";
        }
        if (Receipt.READ.equals(zState)) {
            return "Read";
        }
        if (Receipt.DELIVERED.equals(zState)) {
            return "Delivered";
        }
        if (Receipt.SENT.equals(zState)) {
            return "Sent (relay took it)";
        }
        return "Sending…";
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

    /** Meta colour for a SENT bubble - it sits ON the green fill, so it must be
     *  dark ink to stay readable (blue read-ticks are invisible on green). The
     *  read/delivered/failed state is carried by the tick GLYPH itself
     *  (✓ / ✓✓ / ✗), not by colour. */
    private int sentMetaColour(String zState) {
        if (Receipt.FAILED.equals(zState)) {
            return 0xE0731105;   // dark red-brown, still legible on green
        }
        return 0xCC0A1F12;       // dark ink
    }

    /** The two-line payment card text: a small direction line, then a big bold
     *  amount, then an optional memo. */
    private CharSequence paymentText(ChatEngine.Entry e) {
        String amt = com.eurobuddha.maxima.core.chat.ChatPay.amount(e.body);
        String tok = com.eurobuddha.maxima.core.chat.ChatPay.tokenName(e.body);
        String memo = com.eurobuddha.maxima.core.chat.ChatPay.memo(e.body);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int hs = sb.length();
        sb.append(e.mine ? "↑  You sent" : "↓  You received");
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.82f), hs, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n');
        int bs = sb.length();
        sb.append(amt).append(' ').append(tok);
        sb.setSpan(new android.text.style.RelativeSizeSpan(1.35f), bs, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                bs, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (memo != null && !memo.isEmpty()) {
            sb.append('\n').append(memo);
        }
        // For a received payment, a live on-chain status: Pending until a coin
        // of this amount lands at our wallet, then Confirmed.
        if (!e.mine) {
            String tx = com.eurobuddha.maxima.core.chat.ChatPay.txid(e.body);
            boolean confirmed = !tx.isEmpty() && isPayConfirmed(tx);
            int st = sb.length();
            sb.append('\n').append(confirmed ? "✓ Confirmed" : "⏳ Pending…");
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.82f), st, sb.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
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
        h.bubble.setOnLongClickListener(v -> {
            showMessageMenu(e);
            return true;
        });
        h.row.setGravity(e.mine ? Gravity.END : Gravity.START);
        // Tight within a cluster; a clear gap starting each new run.
        h.row.setPadding(dp(12), r.firstInCluster ? dp(7) : dp(1), dp(12), dp(1));
        // Tail on the LAST bubble of a run (nearest the timestamp, WhatsApp-
        // style); the rest fully rounded so a run reads as one group.
        boolean pay = com.eurobuddha.maxima.core.chat.ChatPay.isPayment(e.body);
        if (pay) {
            // A payment reads as its own accent card - money is not chat.
            h.bubble.setBackgroundResource(R.drawable.bubble_pay);
            h.who.setVisibility(View.GONE);
            h.image.setVisibility(View.GONE);
            h.image.setImageDrawable(null);
            h.body.setVisibility(View.VISIBLE);
            h.body.setText(paymentText(e));
            h.body.setTextColor(getColor(R.color.ux_on_accent));
            h.body.setTextIsSelectable(false);
        } else {
            // WhatsApp tails the FIRST bubble of a run on its top-outer corner.
            int bg = e.mine
                    ? (r.firstInCluster ? R.drawable.bubble_out : R.drawable.bubble_out_mid)
                    : (r.firstInCluster ? R.drawable.bubble_in : R.drawable.bubble_in_mid);
            h.bubble.setBackgroundResource(bg);
            // Light ink on the charcoal sent bubble; theme text on the received one.
            h.body.setTextColor(getColor(
                    e.mine ? R.color.ux_bubble_out_text : R.color.ux_bubble_in_text));

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
        }

        // Timestamp + ticks only on the last of a run (iMessage-style).
        if (r.lastInCluster) {
            h.meta.setVisibility(View.VISIBLE);
            String stamp = mTime.format(new Date(e.time));
            if (e.mine) {
                h.meta.setText(stamp + "  " + ticks(e.state));
                h.meta.setTextColor(sentMetaColour(e.state));
            } else {
                h.meta.setText(stamp);
                // On the orange payment card, grey is unreadable - use dark ink.
                h.meta.setTextColor(pay ? 0xCC0A1F12 : getColor(R.color.ux_subtext));
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
