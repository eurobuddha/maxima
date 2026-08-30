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
    }

    private final MainActivity mAct;
    private final View mView;
    private final ListView mList;
    private final TextView mEmpty;
    private final EditText mSearch;
    private final List<Row> mAll = new ArrayList<>();
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
        View newGroup = zView.findViewById(R.id.btn_new_group);
        if (newGroup != null) {
            // Groups have no cloud RPC yet (Phase 2). Hide the FAB rather than dangle a dead button.
            newGroup.setVisibility(View.GONE);
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
        mAct.startActivity(i);
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
            mEmpty.setVisibility(View.VISIBLE);
            mEmpty.setText("Loading your conversations…");
        }
        mBusy = true;
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                final List<Row> rows = new ArrayList<>();
                String error = null;
                try {
                    JSONObject res = r.summaries();
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
                            rows.add(row);
                        }
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    if (err == null) {
                        mAll.clear();
                        mAll.addAll(rows);
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
