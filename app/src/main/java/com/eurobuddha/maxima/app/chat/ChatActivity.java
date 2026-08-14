package com.eurobuddha.maxima.app.chat;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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
    private ListView mList;
    private EditText mInput;
    private TextView mSubtitle;
    private TextView mTitle;
    private TextView mAvatar;
    private Adapter mAdapter;
    /** Sending always scrolls to your own message, even from up the history. */
    private boolean mLastSendWasMine;
    private final List<ChatEngine.Entry> mEntries = new ArrayList<>();
    private final SimpleDateFormat mTime = new SimpleDateFormat("HH:mm", Locale.UK);

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
        mAdapter = new Adapter();
        mList.setAdapter(mAdapter);
        mList.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);

        findViewById(R.id.btn_chat_send).setOnClickListener(v -> send());
        findViewById(R.id.btn_chat_info).setOnClickListener(v -> showInfo());
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
            mEntries.clear();
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
        mAvatar.setText(com.eurobuddha.maxima.app.ui.Ui.initial(name));

        Group g = chat.group(mConversation);
        if (g != null) {
            mSubtitle.setText(g.size() + " member(s)"
                    + (g.isAdmin(node.identity().publicKeyHex()) ? "  ·  you are an admin" : ""));
        } else {
            Contact c = node.contact(mConversation);
            mSubtitle.setText(c == null
                    ? "not in your contacts"
                    : (c.primaryAddress() == null ? "no known address" : c.primaryAddress()));
        }

        List<ChatEngine.Entry> conv = chat.conversation(mConversation);
        conv.sort((a, b) -> Long.compare(a.time, b.time));

        // Only follow the bottom if we were ALREADY at the bottom. Jumping to
        // the newest message on every receipt drags the user out of the history
        // they are scrolled back reading, which a burst of group ticks would do
        // several times a second.
        boolean atBottom = mEntries.isEmpty()
                || mList.getLastVisiblePosition() >= mEntries.size() - 1;
        boolean grew = conv.size() > mEntries.size();

        mEntries.clear();
        mEntries.addAll(conv);
        mAdapter.notifyDataSetChanged();
        if (!mEntries.isEmpty() && (atBottom || grew && mLastSendWasMine)) {
            mList.setSelection(mEntries.size() - 1);
        }
        mLastSendWasMine = false;
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

    private final class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mEntries.size();
        }

        @Override
        public Object getItem(int i) {
            return mEntries.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(ChatActivity.this)
                        .inflate(R.layout.item_message, parent, false);
            }
            ChatEngine.Entry e = mEntries.get(position);

            LinearLayout row = v.findViewById(R.id.bubble_row);
            LinearLayout bubble = v.findViewById(R.id.bubble);
            TextView who = v.findViewById(R.id.bubble_sender);
            TextView body = v.findViewById(R.id.bubble_body);
            TextView meta = v.findViewById(R.id.bubble_meta);

            row.setGravity(e.mine ? Gravity.END : Gravity.START);
            bubble.setBackgroundResource(e.mine ? R.drawable.bubble_out : R.drawable.bubble_in);

            // In a group you must know who is talking; in 1:1 it is noise.
            if (!e.mine && e.isGroup()) {
                who.setVisibility(View.VISIBLE);
                who.setText(Names.contact(MaximaService.node(), e.sender));
            } else {
                who.setVisibility(View.GONE);
            }

            body.setText(e.body);

            String stamp = mTime.format(new Date(e.time));
            if (e.mine) {
                meta.setText(stamp + "  " + ticks(e.state));
                meta.setTextColor(tickColour(e.state));
            } else {
                meta.setText(stamp);
                meta.setTextColor(getColor(R.color.ux_subtext));
            }
            return v;
        }
    }
}
