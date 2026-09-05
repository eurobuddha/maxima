package com.eurobuddha.maxima.app.portal;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.ui.Avatars;
import com.eurobuddha.maxima.app.ui.Page;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Conversations of the CLOUD account, newest first — the portal's Chats tab. Same layout and rows
 * as Parlons, but the source is the account node over {@link ParlonsRemote#summaries()} (a network
 * RPC), so the list is fetched on a background thread and posted back. The node already resolves
 * each peer's display name, so rows bind straight from JSON.
 */
public final class CloudChatsPage implements Page {

    private static final class Row {
        String key;
        String name;
        String preview;
        String time;
        int unread;
        boolean group;
    }

    private final MainActivity mAct;
    private final View mView;
    private final ListView mList;
    private final TextView mEmpty;
    private final EditText mSearch;
    private final List<Row> mAll = new ArrayList<>();

    /** Total unread across all conversations — published for the shell pill/badge. */
    private static final java.util.concurrent.atomic.AtomicInteger sUnread =
            new java.util.concurrent.atomic.AtomicInteger();

    public static int unreadTotal() {
        return sUnread.get();
    }

    private void publishUnread() {
        int total = 0;
        for (Row r : mAll) {
            total += Math.max(0, r.unread);
        }
        sUnread.set(total);
    }
    private final List<Row> mRows = new ArrayList<>();
    private final Adapter mAdapter = new Adapter();
    private String mQuery = "";
    private volatile boolean mBusy;
    private long mLastLoad;

    private final SimpleDateFormat mHm = new SimpleDateFormat("HH:mm", Locale.UK);
    private final SimpleDateFormat mDay = new SimpleDateFormat("d MMM", Locale.UK);

    public CloudChatsPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mList = zView.findViewById(R.id.conversations);
        mEmpty = zView.findViewById(R.id.chats_empty);
        mSearch = zView.findViewById(R.id.chats_search);
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener((p, v, pos, id) -> open(mRows.get(pos)));
        mList.setOnItemLongClickListener((p, v, pos, id) -> {
            rowMenu(mRows.get(pos));
            return true;
        });
        View newGroup = zView.findViewById(R.id.btn_new_group);
        if (newGroup != null) {
            newGroup.setVisibility(View.VISIBLE);
            newGroup.setOnClickListener(v -> newGroupFlow());
        }
        mSearch.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
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
        return "Chats";
    }

    private void open(Row r) {
        Intent i = new Intent(mAct, CloudChatActivity.class);
        i.putExtra(CloudChatActivity.EXTRA_PEER, r.key);
        i.putExtra(CloudChatActivity.EXTRA_NAME, r.name);
        i.putExtra(CloudChatActivity.EXTRA_GROUP, r.group);
        mAct.startActivity(i);
    }

    /** Long-press a chat → Mark as read (if unread) / Clear messages (local, with confirm). */
    private void rowMenu(Row r) {
        java.util.List<String> items = new ArrayList<>();
        if (r.unread > 0) {
            items.add("Mark as read");
        }
        items.add("Clear messages");
        new androidx.appcompat.app.AlertDialog.Builder(mAct)
                .setTitle(r.name)
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    if ("Mark as read".equals(items.get(which))) {
                        act(rr -> rr.markRead(r.key), "Marked read");
                    } else {
                        confirmClear(r);
                    }
                })
                .show();
    }

    private void confirmClear(Row r) {
        new androidx.appcompat.app.AlertDialog.Builder(mAct)
                .setTitle("Clear this chat?")
                .setMessage("Removes the messages from your account only. It does NOT unsend "
                        + "them or leave a group, and can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> act(rr -> rr.clearConversation(r.key),
                        "Chat cleared"))
                .show();
    }

    private interface Call {
        JSONObject run(ParlonsRemote r) throws Exception;
    }

    private void act(Call call, String okMsg) {
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = call.run(r);
                    Object ok = res.get("ok");
                    if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                mAct.runOnUiThread(() -> {
                    android.widget.Toast.makeText(mAct, err == null ? okMsg : "Failed: " + err,
                            android.widget.Toast.LENGTH_SHORT).show();
                    mLastLoad = 0;
                    render();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> android.widget.Toast.makeText(mAct, "Failed: " + m,
                        android.widget.Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** Called on the 2s UI tick. Kicks a background refresh, throttled so we don't hammer the node. */
    @Override
    public void render() {
        if (mBusy) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!mAll.isEmpty() && now - mLastLoad < 3500) {
            return;
        }
        if (mAll.isEmpty()) {
            // Paint the last-known list instantly while the live fetch attaches to the fleet.
            String cached = CloudSession.cached(mAct, "summaries");
            if (!cached.isEmpty()) {
                try {
                    Object o = new org.minima.utils.json.parser.JSONParser().parse(cached);
                    if (o instanceof JSONObject) {
                        mAll.addAll(parseRows((JSONObject) o));
                        publishUnread();
                        applyFilter();
                    }
                } catch (Exception ignored) {
                }
            }
            if (mAll.isEmpty()) {
                mEmpty.setVisibility(View.VISIBLE);
                mEmpty.setText("Loading your conversations…");
            }
        }
        mBusy = true;
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                List<Row> got = null;
                String error = null;
                try {
                    JSONObject res = r.summaries();
                    got = parseRows(res);
                    CloudSession.cache(mAct, "summaries", res.toString());
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                final List<Row> rows = got;
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    if (err == null) {
                        mAll.clear();
                        mAll.addAll(rows);
                        publishUnread();
                        applyFilter();
                    } else if (mAll.isEmpty()) {
                        mEmpty.setVisibility(View.VISIBLE);
                        mEmpty.setText("Couldn't load chats:\n" + err);
                    }
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    if (mAll.isEmpty()) {
                        mEmpty.setVisibility(View.VISIBLE);
                        mEmpty.setText("Can't reach your account.\n" + m);
                    }
                });
            }
        });
    }

    /** New group: name + pick members from the account's contacts → parlons.group.create.
     *  The node pushes the roster to every member, exactly like the app. */
    private void newGroupFlow() {
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                final List<String> keys = new ArrayList<>();
                final List<String> names = new ArrayList<>();
                try {
                    JSONObject res = r.contacts();
                    JSONArray arr = (JSONArray) res.get("contacts");
                    if (arr != null) {
                        for (Object o : arr) {
                            JSONObject c = (JSONObject) o;
                            keys.add(str(c, "key"));
                            String n = str(c, "name");
                            names.add(n.isEmpty() ? str(c, "key") : n);
                        }
                    }
                } catch (Exception ignored) {
                }
                mAct.runOnUiThread(() -> {
                    if (keys.isEmpty()) {
                        android.widget.Toast.makeText(mAct,
                                "Add some contacts first — a group needs members",
                                android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    android.widget.LinearLayout box = new android.widget.LinearLayout(mAct);
                    box.setOrientation(android.widget.LinearLayout.VERTICAL);
                    int pad = (int) (20 * mAct.getResources().getDisplayMetrics().density);
                    box.setPadding(pad, pad / 2, pad, 0);
                    final EditText nameField = new EditText(mAct);
                    nameField.setHint("Group name");
                    nameField.setSingleLine(true);
                    box.addView(nameField);
                    final boolean[] picked = new boolean[keys.size()];
                    for (int i = 0; i < names.size(); i++) {
                        final int idx = i;
                        android.widget.CheckBox cb = new android.widget.CheckBox(mAct);
                        cb.setText(names.get(i));
                        cb.setOnCheckedChangeListener((b, on) -> picked[idx] = on);
                        box.addView(cb);
                    }
                    android.widget.ScrollView scroll = new android.widget.ScrollView(mAct);
                    scroll.addView(box);
                    new androidx.appcompat.app.AlertDialog.Builder(mAct)
                            .setTitle("New group")
                            .setView(scroll)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Create", (d, w) -> {
                                String gname = nameField.getText().toString().trim();
                                List<String> members = new ArrayList<>();
                                for (int i = 0; i < picked.length; i++) {
                                    if (picked[i]) {
                                        members.add(keys.get(i));
                                    }
                                }
                                if (gname.isEmpty() || members.isEmpty()) {
                                    android.widget.Toast.makeText(mAct,
                                            "Name the group and pick at least one member",
                                            android.widget.Toast.LENGTH_LONG).show();
                                    return;
                                }
                                if (members.size() + 1 > com.eurobuddha.maxima.core.chat.Group.MAX_MEMBERS) {
                                    android.widget.Toast.makeText(mAct, "A group holds at most "
                                            + com.eurobuddha.maxima.core.chat.Group.MAX_MEMBERS
                                            + " people including you - pick fewer",
                                            android.widget.Toast.LENGTH_LONG).show();
                                    return;
                                }
                                createGroup(gname, members);
                            })
                            .show();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> android.widget.Toast.makeText(mAct, m,
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    private void createGroup(String zName, List<String> zMembers) {
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.createGroup(zName, zMembers);
                    Object ok = res.get("ok");
                    if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                mAct.runOnUiThread(() -> {
                    android.widget.Toast.makeText(mAct, err == null
                                    ? "Group created" : "Could not create group: " + err,
                            android.widget.Toast.LENGTH_LONG).show();
                    mLastLoad = 0;
                    render();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> android.widget.Toast.makeText(mAct, m,
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    private List<Row> parseRows(JSONObject res) {
        List<Row> rows = new ArrayList<>();
        JSONArray arr = (JSONArray) res.get("summaries");
        if (arr != null) {
            for (Object o : arr) {
                JSONObject s = (JSONObject) o;
                Row row = new Row();
                row.key = str(s, "peer");
                row.name = str(s, "name");
                if (row.name.isEmpty()) {
                    row.name = row.key;
                }
                String last = str(s, "last");
                boolean mine = bool(s, "lastMine");
                row.preview = last.isEmpty() ? "no messages yet"
                        : (mine ? "You: " : "") + oneLine(last);
                long t = lng(s, "time");
                row.time = t > 0 ? stamp(t) : "";
                row.unread = (int) lng(s, "unread");
                row.group = bool(s, "group");
                rows.add(row);
            }
        }
        return rows;
    }

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
        if (mRows.isEmpty()) {
            mEmpty.setText(mQuery.isEmpty()
                    ? "No conversations yet.\n\nAdd a contact in the Contacts tab, then message them."
                    : "No chats match “" + mSearch.getText() + "”.");
        }
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

    // ---- JSON coercion (org.minima.utils.json) ----
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

    private final class Adapter extends BaseAdapter {
        public int getCount() { return mRows.size(); }
        public Object getItem(int i) { return mRows.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(mAct).inflate(R.layout.item_conversation, parent, false);
            }
            Row r = mRows.get(position);
            Avatars.apply(v.findViewById(R.id.conv_avatar), r.key, r.name);
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
