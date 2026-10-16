package com.eurobuddha.maxima.app.ui;

import com.eurobuddha.maxima.app.i18n.LanguageDialog;

import android.content.Intent;
import com.eurobuddha.maxima.app.i18n.Language;
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

import java.util.ArrayList;
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
        long time;
        int unread;
    }

    private final MainActivity mAct;
    private final View mView;
    private final ListView mList;
    private final TextView mEmpty;
    private final EditText mSearch;
    /** Everything render() built; {@link #mRows} is this filtered by the search box. */
    private final List<Row> mAll = new ArrayList<>();
    private final List<Row> mRows = new ArrayList<>();
    private final Adapter mAdapter = new Adapter();
    private String mQuery = "";


    public ChatsPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mList = zView.findViewById(R.id.conversations);
        mEmpty = zView.findViewById(R.id.chats_empty);
        mSearch = zView.findViewById(R.id.chats_search);
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener((p, v, pos, id) -> open(mRows.get(pos).key));
        mList.setOnItemLongClickListener((p, v, pos, id) -> {
            rowMenu(mRows.get(pos));
            return true;
        });
        zView.findViewById(R.id.btn_new_group).setOnClickListener(v -> newGroup());
        mSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                mQuery = s.toString().trim().toLowerCase(Locale.UK);
                applyFilter();
            }
        });
    }

    @Override
    public View view() {
        return mView;
    }

    @Override
    public CharSequence title() {
        return com.eurobuddha.maxima.app.i18n.Language.msg(com.eurobuddha.maxima.app.R.string.l10n_nav_chats);
    }

    private void open(String zConversation) {
        Intent i = new Intent(mAct, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_CONVERSATION, zConversation);
        mAct.startActivity(i);
    }

    @Override
    public void render() {
        com.eurobuddha.maxima.core.ChatPort node = MaximaService.port();
        ChatEngine chat = MaximaService.chat();
        if (node == null || chat == null) {
            Language.setText(mEmpty, Language.msg(R.string.l10n_native_chat_starting));
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
            String body = oneLine(com.eurobuddha.maxima.app.i18n.ChatPreview.text(mAct, s.lastBody));
            r.preview = s.lastMine ? tr(R.string.l10n_chat_you_prefix, body)
                    : (chat.group(s.conversation) != null ? Names.contact(node, s.lastSender) + ": " : "") + body;
            r.time = s.lastTime;
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

        mAll.clear();
        mAll.addAll(rows);
        applyFilter();
    }

    /** Show {@link #mAll} filtered by the search box, and the right empty text. */
    private void applyFilter() {
        mRows.clear();
        if (mQuery.isEmpty()) {
            mRows.addAll(mAll);
        } else {
            for (Row r : mAll) {
                if (r.name.toLowerCase(Locale.UK).contains(mQuery)
                        || r.preview.toLowerCase(Locale.UK).contains(mQuery)) {
                    mRows.add(r);
                }
            }
        }
        mAdapter.notifyDataSetChanged();

        mEmpty.setVisibility(mRows.isEmpty() ? View.VISIBLE : View.GONE);
        if (!mQuery.isEmpty()) {
            Language.setText(mEmpty, Language.msg(R.string.l10n_native_chat_no_matches, mSearch.getText().toString()));
        } else {
            Language.setText(mEmpty, Language.msg(R.string.l10n_native_chat_empty_device));
        }
    }

    private Row blank(String zKey, String zName) {
        Row r = new Row();
        r.key = zKey;
        r.name = zName;
        r.preview = tr(R.string.l10n_chat_no_messages);
        r.time = 0;
        return r;
    }


    @Override public void languageChanged() { render(); }

    private String tr(int resource, Object... arguments) { return Language.text(mAct, resource, arguments); }

    private static String oneLine(String zBody) {
        String s = zBody.replace('\n', ' ').trim();
        return s.length() > 70 ? s.substring(0, 70) + "…" : s;
    }

    // ---------------------------------------------------------------

    /** Long-press a conversation: mark read, or clear its history on this phone. */
    private void rowMenu(final Row row) {
        ChatEngine chat = MaximaService.chat();
        if (chat == null) {
            return;
        }
        final List<CharSequence> items = new ArrayList<>();
        if (row.unread > 0) {
            items.add(Language.msg(R.string.l10n_native_chat_mark_read));
        }
        items.add(Language.msg(R.string.l10n_native_chat_clear_messages));
        new LanguageDialog(mAct)
                .setTitle(row.name)
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    if (row.unread > 0 && which == 0) {
                        chat.markRead(row.key);
                        render();
                    } else {
                        confirmClear(row);
                    }
                })
                .show();
    }

    private void confirmClear(final Row row) {
        new LanguageDialog(mAct)
                .setTitle(Language.msg(R.string.l10n_native_chat_clear_title))
                .setMessage(Language.msg(R.string.l10n_native_chat_clear_device, row.name))
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_common_clear), (d, w) -> {
                    ChatEngine chat = MaximaService.chat();
                    if (chat != null) {
                        chat.clearConversation(row.key);
                        render();
                    }
                })
                .show();
    }

    /** Create a group. We are its only admin - see Explain "groups". */
    /** Public: the main overflow menu starts a group from any tab. */
    public void newGroup() {
        com.eurobuddha.maxima.core.ChatPort node = MaximaService.port();
        ChatEngine chat = MaximaService.chat();
        if (node == null || chat == null) {
            mAct.toast(tr(R.string.l10n_native_chat_transport_stopped));
            return;
        }
        List<Contact> contacts = node.contacts();
        if (contacts.isEmpty()) {
            mAct.toast(tr(R.string.l10n_native_chat_add_contacts_first));
            mAct.showTab(1);
            return;
        }
        String[] labels = new String[contacts.size()];
        boolean[] checked = new boolean[contacts.size()];
        for (int i = 0; i < contacts.size(); i++) {
            labels[i] = contacts.get(i).name + "   " + Names.shorten(contacts.get(i).publicKey);
        }
        new LanguageDialog(mAct)
                .setTitle(Language.msg(R.string.l10n_native_chat_pick_members))
                .setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setNeutralButton(Language.msg(R.string.l10n_native_chat_group_help), (d, w) -> Explain.show(mAct, "groups"))
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_common_next), (d, w) -> {
                    List<String> members = new ArrayList<>();
                    for (int i = 0; i < contacts.size(); i++) {
                        if (checked[i]) {
                            members.add(contacts.get(i).publicKey);
                        }
                    }
                    if (members.isEmpty()) {
                        mAct.toast(tr(R.string.l10n_native_chat_pick_one));
                        return;
                    }
                    if (members.size() + 1 > com.eurobuddha.maxima.core.chat.Group.MAX_MEMBERS) {
                        mAct.toast(Language.count(R.plurals.l10n_native_chat_group_capacity, com.eurobuddha.maxima.core.chat.Group.MAX_MEMBERS).toString());
                        return;
                    }
                    nameGroup(members);
                })
                .show();
    }

    private void nameGroup(List<String> zMembers) {
        final EditText input = new EditText(mAct);
        Language.hint(input, Language.msg(R.string.l10n_chat_group_name));
        int pad = Ui.dp(mAct, 20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new LanguageDialog(mAct)
                .setTitle(Language.msg(R.string.l10n_native_chat_name_group))
                .setView(input)
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_common_create), (d, w) -> {
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
                                    mAct.toast(tr(R.string.l10n_native_chat_create_failed, e.getMessage())));
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
            Avatars.apply(v.findViewById(R.id.conv_avatar), r.key, r.name);
            ((TextView) v.findViewById(R.id.conv_name)).setText(r.name);
            ((TextView) v.findViewById(R.id.conv_preview)).setText(r.preview);
            ((TextView) v.findViewById(R.id.conv_time)).setText(r.time > 0 ? Language.shortStamp(r.time) : "");
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
