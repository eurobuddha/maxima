package com.eurobuddha.maxima.app.portal;

import android.os.Bundle;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/**
 * The VPS control panel — the surface that makes a cloud node superior to a phone: live transport
 * figures, host management (add/detach at runtime), reachability, relay throughput, MLS/location
 * controls, and the node's rolling event log. All over the encrypted RPC channel.
 */
public final class CloudNodePanelActivity extends AppCompatActivity {

    private LinearLayout mRoot;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mBusy;
    private JSONObject mFigures;
    private JSONObject mStatus;
    private JSONArray mLog;
    private JSONObject mMls;   // last MLS op result (pinned flag + full permanent address)

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.ux_bg));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getColor(R.color.ux_header));
        int pad = PortalUi.dp(this, 16);
        bar.setPadding(pad, pad, pad, pad);
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(getColor(R.color.ux_on_header));
        back.setPadding(0, 0, PortalUi.dp(this, 18), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        Language.setText(title,m(R.string.l10n_node_panel_title));
        title.setTextSize(20);
        title.setTextColor(getColor(R.color.ux_on_header));
        bar.addView(title);
        root.addView(bar);

        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(mRoot);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        final int barTop = bar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sb.left, 0, sb.right, sb.bottom);
            bar.setPadding(bar.getPaddingLeft(), barTop + sb.top,
                    bar.getPaddingRight(), bar.getPaddingBottom());
            return WindowInsetsCompat.CONSUMED;
        });
        getWindow().setStatusBarColor(getColor(R.color.ux_header));

        mRoot.addView(PortalUi.label(this, m(R.string.l10n_common_loading)));
        refresh(false);
    }

    private void refresh(final boolean clearLog) {
        if (isFinishing() || isDestroyed()) {
            return;   // never fire a fresh interactive connect on a dead Activity
        }
        if (mBusy) {
            return;
        }
        mBusy = true;
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                JSONObject fig = null, st = null, lg = null;
                try { fig = r.nodeFigures(); } catch (Exception ignored) { }
                try { st = r.nodeStatus(); } catch (Exception ignored) { }
                try { lg = r.nodeLog(clearLog); } catch (Exception ignored) { }
                final JSONObject ffig = fig, fst = st, flg = lg;
                mMain.post(() -> {
                    mBusy = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (ffig != null && bool(ffig, "ok")) mFigures = ffig;
                    if (fst != null && bool(fst, "ok")) mStatus = fst;
                    if (flg != null && bool(flg, "ok")) {
                        Object ln = flg.get("lines");
                        mLog = ln instanceof JSONArray ? (JSONArray) ln : new JSONArray();
                    }
                    rebuild();
                });
            }
            public void err(String m) {
                mMain.post(() -> {
                    mBusy = false;
                    if (!isFinishing() && !isDestroyed() && mFigures == null) {
                        mRoot.removeAllViews();
                        mRoot.addView(PortalUi.label(CloudNodePanelActivity.this,
                                m(R.string.l10n_native_node_unreachable, SystemMessages.message(m))));
                    }
                });
            }
        });
    }

    private void rebuild() {
        mRoot.removeAllViews();
        if (mFigures == null) {
            mRoot.addView(PortalUi.label(this, m(R.string.l10n_common_loading)));
            return;
        }

        // --- always-on + reachability ---
        mRoot.addView(PortalUi.section(this, m(R.string.l10n_node_panel_status)));
        LinearLayout st = PortalUi.card(this);
        if (mStatus != null) {
            st.addView(PortalUi.kv(this, m(R.string.l10n_common_version), str(mStatus, "version")));
            st.addView(PortalUi.kv(this, m(R.string.l10n_native_node_uptime), uptime(lng(mStatus, "uptime"))));
        }
        st.addView(PortalUi.kv(this, m(R.string.l10n_help_contacts_title), String.valueOf(lng(mFigures, "contacts"))));
        st.addView(PortalUi.kv(this, m(R.string.l10n_native_node_mailbox), String.valueOf(lng(mFigures, "mailboxHeld"))));
        st.addView(PortalUi.kv(this, m(R.string.l10n_help_outbox_title), String.valueOf(lng(mFigures, "outbox"))));
        String own = str(mFigures, "ownRelay");
        if (!own.isEmpty()) {
            // A Parlons Node: its own cape IS its public door - no separate "direct port" story.
            st.addView(PortalUi.gap(this, 8));
            st.addView(PortalUi.label(this, m(R.string.l10n_node_panel_public_relay)));
            st.addView(PortalUi.value(this, own));
            st.addView(PortalUi.gap(this, 8));
            st.addView(PortalUi.label(this, m(R.string.l10n_node_panel_relay_check)));
            st.addView(PortalUi.value(this,
                    bool(mFigures, "ownRelayVerified") ? m(R.string.l10n_node_panel_verified)
                    : bool(mFigures, "ownRelayAttached") ? m(R.string.l10n_node_panel_verifying) : m(R.string.l10n_node_not_attached)));
        } else {
            st.addView(PortalUi.kv(this, m(R.string.l10n_native_network_directly_reachable),
                    bool(mFigures, "directlyReachable") ? m(R.string.l10n_native_node_yes) : m(R.string.l10n_native_node_no)));
            String da = str(mFigures, "directAddress");
            if (!da.isEmpty()) {
                st.addView(PortalUi.gap(this, 8));
                st.addView(PortalUi.label(this, m(R.string.l10n_node_direct_address)));
                st.addView(PortalUi.value(this, da));
            }
        }
        mRoot.addView(st);

        // --- relay contribution ---
        mRoot.addView(PortalUi.section(this, m(R.string.l10n_node_panel_relay)));
        LinearLayout rl = PortalUi.card(this);
        rl.addView(PortalUi.kv(this, m(R.string.l10n_desktop_network_clients),
                String.valueOf(lng(mFigures, "relayConnections"))));
        rl.addView(PortalUi.kv(this, m(R.string.l10n_desktop_network_relayed),
                String.valueOf(lng(mFigures, "relayRelayed"))));
        rl.addView(PortalUi.kv(this, m(R.string.l10n_node_panel_blobs), String.valueOf(lng(mFigures, "relayStored"))));
        JSONArray mesh = arr(mFigures, "meshPeers");
        rl.addView(PortalUi.kv(this, m(R.string.l10n_node_mesh_peers), String.valueOf(mesh.size())));
        mRoot.addView(rl);

        // --- hosts ---
        mRoot.addView(PortalUi.section(this, m(R.string.l10n_help_hosts_title)));
        LinearLayout hc = PortalUi.card(this);
        JSONArray hosts = arr(mFigures, "hosts");
        {
            for (int i = 0; i < hosts.size(); i++) {
                if (!(hosts.get(i) instanceof JSONObject)) continue;
                JSONObject h = (JSONObject) hosts.get(i);
                final String host = str(h, "host");
                boolean up = bool(h, "connected");
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int pv = PortalUi.dp(this, 6);
                row.setPadding(0, pv, 0, pv);
                TextView dot = new TextView(this);
                dot.setText("●");
                dot.setTextColor(getColor(up ? R.color.ux_success : R.color.ux_subtext));
                dot.setPadding(0, 0, PortalUi.dp(this, 8), 0);
                row.addView(dot);
                TextView t = new TextView(this);
                t.setText(host);
                t.setTextColor(getColor(R.color.ux_text));
                t.setTextSize(13);
                t.setTypeface(android.graphics.Typeface.MONOSPACE);
                row.addView(t, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView rm = PortalUi.ghost(this, m(R.string.l10n_node_panel_detach));
                rm.setTextColor(getColor(R.color.ux_error));
                rm.setOnClickListener(v -> hostOp("", host));
                row.addView(rm);
                hc.addView(row);
            }
        }
        hc.addView(PortalUi.gap(this, 8));
        final EditText add = new EditText(this);
        Language.hint(add,m(R.string.l10n_node_panel_host_hint));
        add.setSingleLine(true);
        add.setTextColor(getColor(R.color.ux_text));
        add.setHintTextColor(getColor(R.color.ux_subtext));
        add.setTextSize(13);
        hc.addView(add);
        TextView addBtn = PortalUi.button(this, m(R.string.l10n_native_network_add_connect));
        addBtn.setOnClickListener(v -> {
            // Typed host:port, a comma list, or the text of a relay's QR.
            String h = add.getText().toString().trim();
            if (!h.isEmpty()) {
                hostOp(h, "");
            }
        });
        hc.addView(addBtn);
        hc.addView(PortalUi.gap(this, 8));
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.addView(PortalUi.label(this, m(R.string.l10n_node_panel_builtin)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        androidx.appcompat.widget.SwitchCompat toggle = new androidx.appcompat.widget.SwitchCompat(this);
        toggle.setChecked(!mFigures.containsKey("builtin") || bool(mFigures, "builtin"));
        toggle.setOnCheckedChangeListener((b, on) -> builtInOp(on));
        sw.addView(toggle);
        hc.addView(sw);
        mRoot.addView(hc);

        // --- MLS / location ---
        mRoot.addView(PortalUi.section(this, m(R.string.l10n_node_panel_location)));
        LinearLayout ml = PortalUi.card(this);
        boolean pinned = mMls != null && bool(mMls, "pinned");
        ml.addView(PortalUi.label(this, pinned
                ? m(R.string.l10n_node_panel_pinned)
                : m(R.string.l10n_node_panel_pin_help)));
        // The permanent MAX# address — shown IN FULL and tap-to-copy (never truncated).
        final String perm = mMls != null && !str(mMls, "permanent").isEmpty()
                ? str(mMls, "permanent")
                : (mStatus != null ? str(mStatus, "permanent") : "");
        if (!perm.isEmpty()) {
            ml.addView(PortalUi.gap(this, 8));
            TextView pv = new TextView(this);
            pv.setText(perm);
            pv.setTextColor(getColor(R.color.ux_subtext));
            pv.setTextSize(10);
            pv.setTypeface(android.graphics.Typeface.MONOSPACE);
            pv.setOnClickListener(v -> copyText(Language.text(this, R.string.l10n_native_node_address), perm));
            ml.addView(pv);
            TextView copyPerm = PortalUi.ghost(this, m(R.string.l10n_node_panel_copy_permanent));
            copyPerm.setOnClickListener(v -> copyText(Language.text(this, R.string.l10n_native_node_address), perm));
            ml.addView(copyPerm);
        }
        ml.addView(PortalUi.gap(this, 8));
        LinearLayout mlBtns = new LinearLayout(this);
        boolean largeText = getResources().getConfiguration().fontScale > 1.2f;
        mlBtns.setOrientation(largeText ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        TextView pin = PortalUi.ghost(this, pinned ? m(R.string.l10n_node_panel_repin) : m(R.string.l10n_node_panel_pin));
        pin.setOnClickListener(v -> mlsOp("pin"));
        mlBtns.addView(pin, new LinearLayout.LayoutParams(largeText ? LinearLayout.LayoutParams.MATCH_PARENT : 0,
                LinearLayout.LayoutParams.WRAP_CONTENT, largeText ? 0f : 1f));
        if (pinned) {
            TextView clr = PortalUi.ghost(this, m(R.string.l10n_node_panel_clear_pin));
            clr.setTextColor(getColor(R.color.ux_error));
            clr.setOnClickListener(v -> mlsOp("clear"));
            mlBtns.addView(clr, new LinearLayout.LayoutParams(largeText ? LinearLayout.LayoutParams.MATCH_PARENT : 0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, largeText ? 0f : 1f));
        }
        TextView rep = PortalUi.ghost(this, m(R.string.l10n_node_republish));
        rep.setOnClickListener(v -> mlsOp("republish"));
        mlBtns.addView(rep, new LinearLayout.LayoutParams(largeText ? LinearLayout.LayoutParams.MATCH_PARENT : 0,
                LinearLayout.LayoutParams.WRAP_CONTENT, largeText ? 0f : 1f));
        ml.addView(mlBtns);
        mRoot.addView(ml);

        // --- event log ---
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        logHead.addView(PortalUi.section(this, m(R.string.l10n_help_log_title)), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = PortalUi.ghost(this, m(R.string.l10n_common_clear));
        clear.setOnClickListener(v -> {
            if (mBusy) { Toast.makeText(this, m(R.string.l10n_node_panel_busy), Toast.LENGTH_SHORT).show(); }
            else { refresh(true); }
        });
        logHead.addView(clear);
        mRoot.addView(logHead);
        LinearLayout lc = PortalUi.card(this);
        if (mLog == null || mLog.isEmpty()) {
            lc.addView(PortalUi.label(this, m(R.string.l10n_node_panel_empty)));
        } else {
            for (int i = 0; i < mLog.size(); i++) {
                TextView t = new TextView(this);
                t.setText(String.valueOf(mLog.get(i)));
                t.setTextColor(getColor(R.color.ux_subtext));
                t.setTextSize(11);
                t.setTypeface(android.graphics.Typeface.MONOSPACE);
                lc.addView(t);
            }
        }
        mRoot.addView(lc);

        TextView refreshBtn = PortalUi.ghost(this, m(R.string.l10n_common_refresh));
        refreshBtn.setOnClickListener(v -> {
            if (mBusy) { Toast.makeText(this, m(R.string.l10n_node_panel_busy), Toast.LENGTH_SHORT).show(); }
            else { refresh(false); }
        });
        LinearLayout.LayoutParams rlp = PortalUi.matchWrap(this);
        rlp.topMargin = PortalUi.dp(this, 12);
        rlp.bottomMargin = PortalUi.dp(this, 24);
        refreshBtn.setLayoutParams(rlp);
        mRoot.addView(refreshBtn);
    }

    /** Switch the account's use of the compiled-in relay list; the account refuses OFF when it
     *  would be left with no seed of its own. */
    private void builtInOp(boolean on) {
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String err = null;
                try {
                    JSONObject res = r.nodeHostsBuiltIn(on);
                    if (!bool(res, "ok")) {
                        err = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fe = err;
                mMain.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (fe != null) {
                        Toast.makeText(CloudNodePanelActivity.this, SystemMessages.text(CloudNodePanelActivity.this, fe), Toast.LENGTH_LONG).show();
                    }
                    refresh(false);
                });
            }
            public void err(String m) {
                mMain.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(CloudNodePanelActivity.this, SystemMessages.text(CloudNodePanelActivity.this, m), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void hostOp(String add, String remove) {
        Toast.makeText(this, add.isEmpty() ? m(R.string.l10n_node_panel_detaching) : m(R.string.l10n_connection_connecting),
                Toast.LENGTH_SHORT).show();
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String err = null;
                try {
                    JSONObject res = r.nodeHosts(add, remove);
                    if (!bool(res, "ok")) {
                        err = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fe = err;
                mMain.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (fe != null) {
                        Toast.makeText(CloudNodePanelActivity.this, SystemMessages.text(CloudNodePanelActivity.this, fe), Toast.LENGTH_LONG).show();
                    }
                    refresh(false);
                });
            }
            public void err(String m) {
                mMain.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(CloudNodePanelActivity.this, SystemMessages.text(CloudNodePanelActivity.this, m), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void mlsOp(final String action) {
        Toast.makeText(this, m(R.string.l10n_native_network_working), Toast.LENGTH_SHORT).show();
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String msg;
                JSONObject result = null;
                try {
                    JSONObject res = r.nodeMls(action, null);
                    if (bool(res, "ok")) {
                        result = res;
                        msg = "pin".equals(action) ? "Permanent address pinned"
                                : ("clear".equals(action) ? "Pin cleared"
                                   : "Directory record republished");
                    } else {
                        msg = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    msg = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fmsg = msg;
                final JSONObject fres = result;
                mMain.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (fres != null) mMls = fres;   // remember pin state + permanent addr
                    Toast.makeText(CloudNodePanelActivity.this, SystemMessages.text(CloudNodePanelActivity.this, fmsg), Toast.LENGTH_LONG).show();
                    refresh(false);
                });
            }
            public void err(String m) {
                mMain.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(CloudNodePanelActivity.this, SystemMessages.text(CloudNodePanelActivity.this, m), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static Language.Message m(int resource, Object... args) { return Language.msg(resource,args); }

    private static CharSequence uptime(long ms) {
        if (ms <= 0) return m(R.string.l10n_native_node_started);
        long s = ms / 1000, d = s / 86400, h = (s % 86400) / 3600, minutes = (s % 3600) / 60;
        if (d > 0) return m(R.string.l10n_native_node_days_hours,d,h);
        if (h > 0) return m(R.string.l10n_native_node_hours_minutes,h,minutes);
        return m(R.string.l10n_native_node_minutes,minutes);
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

    private static JSONArray arr(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONArray ? (JSONArray) v : new JSONArray();
    }

    /** Copy a value to the clipboard IN FULL (never truncated) and confirm. */
    private void copyText(String label, String value) {
        if (value == null || value.isEmpty()) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
        Toast.makeText(this, m(R.string.l10n_common_copied), Toast.LENGTH_SHORT).show();
    }
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        com.eurobuddha.maxima.app.i18n.Language.configurationChanged(this);
    }

}
