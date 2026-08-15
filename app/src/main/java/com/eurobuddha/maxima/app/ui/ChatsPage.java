package com.eurobuddha.maxima.app.ui;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.chat.ChatActivity;
import com.eurobuddha.maxima.app.chat.Names;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.Group;
import com.eurobuddha.maxima.core.contacts.Contact;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conversations, newest first. The screen the app opens on. */
public final class ChatsPage implements Page {

    /** One row's worth of already-resolved display data. */
    private static final class Row {
        String key;
        String name;
        String preview;
        String time;
        int unread;
    }

    private final MainActivity mAct;
    private final View mView;
    private final ListView mList;
    private final TextView mEmpty;
    private final List<Row> mRows = new ArrayList<>();
    private final Adapter mAdapter = new Adapter();

    private final SimpleDateFormat mHm = new SimpleDateFormat("HH:mm", Locale.UK);
    private final SimpleDateFormat mDay = new SimpleDateFormat("d MMM", Locale.UK);

    public ChatsPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mList = zView.findViewById(R.id.conversations);
        mEmpty = zView.findViewById(R.id.chats_empty);
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener((p, v, pos, id) -> open(mRows.get(pos).key));
        zView.findViewById(R.id.btn_new_group).setOnClickListener(v -> newGroup());
    }

    @Override
    public View view() {
        return mView;
    }

    @Override
    public CharSequence title() {
        return "Chats";
    }

    private void open(String zConversation) {
        Intent i = new Intent(mAct, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_CONVERSATION, zConversation);
        mAct.startActivity(i);
    }

    @Override
    public void render() {
        MaximaNode node = MaximaService.node();
        ChatEngine chat = MaximaService.chat();
        if (node == null || chat == null) {
            mEmpty.setText("Starting the transport…");
            mEmpty.setVisibility(View.VISIBLE);
            return;
        }

        // ONE pass over the messages for every conversation, then contacts and
        // groups we have not spoken to yet - a messenger that hides people
        // until you have already messaged them is useless for starting.
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ChatEngine.Summary s : chat.summaries()) {
            Row r = new Row();
            r.key = s.conversation;
            r.name = Names.of(node, chat, s.conversation);
            String who = s.lastMine ? "You: "
                    : (chat.group(s.conversation) != null
                    ? Names.contact(node, s.lastSender) + ": " : "");
            r.preview = who + oneLine(com.eurobuddha.maxima.core.chat.ChatMedia.preview(s.lastBody));
            r.time = stamp(s.lastTime);
            r.unread = s.unread;
            rows.add(r);
            seen.add(s.conversation);
        }
        for (Group g : chat.groups()) {
            if (seen.add(g.id)) {
                rows.add(blank(g.id, Names.of(node, chat, g.id)));
            }
        }
        for (Contact c : node.contacts()) {
            if (seen.add(c.publicKey)) {
                rows.add(blank(c.publicKey, Names.of(node, chat, c.publicKey)));
            }
        }

        mRows.clear();
        mRows.addAll(rows);
        mAdapter.notifyDataSetChanged();

        mEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        mEmpty.setText("No conversations yet.\n\nGo to Contacts, paste someone's "
                + "Mx…@host:port address and introduce yourself. Or send them yours.");
    }

    private static Row blank(String zKey, String zName) {
        Row r = new Row();
        r.key = zKey;
        r.name = zName;
        r.preview = "no messages yet";
        r.time = "";
        return r;
    }

    private String stamp(long zTime) {
        long age = System.currentTimeMillis() - zTime;
        return age < 20L * 60 * 60 * 1000 ? mHm.format(new Date(zTime))
                : mDay.format(new Date(zTime));
    }

    private static String oneLine(String zBody) {
        String s = zBody.replace('\n', ' ').trim();
        return s.length() > 70 ? s.substring(0, 70) + "…" : s;
    }

    // ---------------------------------------------------------------

    /** Create a group. We are its only admin - see Explain "groups". */
    private void newGroup() {
        MaximaNode node = MaximaService.node();
        ChatEngine chat = MaximaService.chat();
        if (node == null || chat == null) {
            mAct.toast("Transport not running");
            return;
        }
        List<Contact> contacts = node.contacts();
        if (contacts.isEmpty()) {
            mAct.toast("Add some contacts first");
            mAct.showTab(1);
            return;
        }
        String[] labels = new String[contacts.size()];
        boolean[] checked = new boolean[contacts.size()];
        for (int i = 0; i < contacts.size(); i++) {
            labels[i] = contacts.get(i).name + "   " + Names.shorten(contacts.get(i).publicKey);
        }
        new AlertDialog.Builder(mAct)
                .setTitle("Who is in the group?")
                .setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setNeutralButton("How groups work", (d, w) -> Explain.show(mAct, "groups"))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Next", (d, w) -> {
                    List<String> members = new ArrayList<>();
                    for (int i = 0; i < contacts.size(); i++) {
                        if (checked[i]) {
                            members.add(contacts.get(i).publicKey);
                        }
                    }
                    if (members.isEmpty()) {
                        mAct.toast("Pick at least one person");
                        return;
                    }
                    nameGroup(members);
                })
                .show();
    }

    private void nameGroup(List<String> zMembers) {
        final EditText input = new EditText(mAct);
        input.setHint("Group name");
        int pad = Ui.dp(mAct, 20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(mAct)
                .setTitle("Name the group")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (d, w) -> {
                    String n = input.getText().toString().trim();
                    final String name = n.isEmpty() ? "Group" : n;
                    // Creating fans the roster out to every member - a network
                    // operation, never on the main thread.
                    new Thread(() -> {
                        try {
                            Group g = MaximaService.chat().createGroup(name, zMembers);
                            mAct.runOnUiThread(() -> {
                                render();
                                open(g.id);
                            });
                        } catch (Exception e) {
                            mAct.runOnUiThread(() ->
                                    mAct.toast("Could not create: " + e.getMessage()));
                        }
                    }, "create-group").start();
                })
                .show();
    }

    // ---------------------------------------------------------------

    private final class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public Object getItem(int i) {
            return mRows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(mAct).inflate(R.layout.item_conversation, parent, false);
            }
            Row r = mRows.get(position);
            ((TextView) v.findViewById(R.id.conv_avatar)).setText(Ui.initial(r.name));
            ((TextView) v.findViewById(R.id.conv_name)).setText(r.name);
            ((TextView) v.findViewById(R.id.conv_preview)).setText(r.preview);
            ((TextView) v.findViewById(R.id.conv_time)).setText(r.time);
            TextView badge = v.findViewById(R.id.conv_badge);
            if (r.unread > 0) {
                badge.setVisibility(View.VISIBLE);
                badge.setText(String.valueOf(r.unread));
            } else {
                badge.setVisibility(View.GONE);
            }
            return v;
        }
    }
}
