package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.LanguageDialog;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.ui.Page;
import com.eurobuddha.maxima.app.ui.Qr;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The Node tab — the reason a VPS account beats a phone: it is ALWAYS ON, it holds your one account
 * for every device, and it IS a network relay. Replaces Parlons' local "Network" tab (transport
 * telemetry a portal has no business showing) with the account node's superpowers, over
 * {@link ParlonsRemote#nodeStatus()} + {@link ParlonsRemote#devices()}. Paired devices can be
 * approved / revoked here, and a fresh bootstrap code minted.
 */
public final class CloudNodePage implements Page {

    private static final class Dev {
        String key;
        String label;
        long pairedAt;
    }

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mRoot;
    private volatile boolean mBusy;
    private long mLastLoad;
    private boolean mBuilt;
    private String mLoadError;

    // status snapshot
    private String mName = "";
    private String mPermanent = "";
    private long mUptime;
    private String mVersion = "";
    private int mHosts;
    private boolean mMailboxHeld;
    private boolean mRelayOn;
    private int mMeshPeers;
    private boolean mStatusOk;
    private boolean mNodeConsole, mHosting, mAdminPassword;
    private final List<Dev> mAuthorized = new ArrayList<>();
    private final List<String> mPending = new ArrayList<>();

    public CloudNodePage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mRoot = zView.findViewById(R.id.network_root);
    }

    @Override
    public View view() {
        return mView;
    }

    @Override
    public CharSequence title() {
        return com.eurobuddha.maxima.app.i18n.Language.msg(com.eurobuddha.maxima.app.R.string.l10n_nav_node);
    }

    @Override
    public void render() {
        if (mBusy) {
            return;
        }
        long now = System.currentTimeMillis();
        if (mBuilt && now - mLastLoad < 4000) {
            return;
        }
        if (!mBuilt) {
            // Paint the last-known node status instantly while the live fetch attaches.
            String cached = CloudSession.cached(mAct, "nodestatus");
            if (!cached.isEmpty()) {
                try {
                    Object o = new org.minima.utils.json.parser.JSONParser().parse(cached);
                    if (o instanceof JSONObject) {
                        applyStatus((JSONObject) o);
                        rebuild();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        mBusy = true;
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                boolean statusOk = false;
                String name = mName, permanent = mPermanent, version = mVersion;
                long uptime = mUptime;
                int hosts = mHosts, mesh = mMeshPeers;
                boolean mailbox = mMailboxHeld, relay = mRelayOn;
                boolean console = false, hosting = false, adminPassword = false;
                List<Dev> auth = new ArrayList<>();
                List<String> pend = new ArrayList<>();
                try {
                    JSONObject s = r.nodeStatus();
                    if (bool(s, "ok")) {
                        statusOk = true;
                        name = str(s, "name");
                        permanent = str(s, "permanent");
                        version = str(s, "version");
                        uptime = lng(s, "uptime");
                        hosts = (int) lng(s, "hosts");
                        mailbox = bool(s, "mailboxHeld");
                        relay = bool(s, "relayOn");
                        mesh = (int) lng(s, "meshPeers");
                        console = hasConsole(s);
                        hosting = bool(s, "hostedAccounts");
                        adminPassword = bool(s, "adminPairingPassword");
                        CloudSession.cache(mAct, "nodestatus", s.toString());
                    }
                } catch (Exception e) {
                    // Older node without the node.status RPC — fall back to ping for identity.
                    try {
                        JSONObject p = r.ping();
                        name = str(p, "name");
                        permanent = str(p, "permanent");
                    } catch (Exception ignored) {
                    }
                }
                try {
                    JSONObject d = r.devices();
                    JSONArray a = (JSONArray) d.get("authorized");
                    if (a != null) {
                        for (Object o : a) {
                            JSONObject jd = (JSONObject) o;
                            Dev dev = new Dev();
                            dev.key = str(jd, "key");
                            dev.label = str(jd, "label");
                            dev.pairedAt = lng(jd, "pairedAt");
                            auth.add(dev);
                        }
                    }
                    JSONArray pn = (JSONArray) d.get("pending");
                    if (pn != null) {
                        for (Object o : pn) {
                            pend.add(String.valueOf(o));
                        }
                    }
                } catch (Exception ignored) {
                }

                final boolean fok = statusOk;
                final String fn = name, fp = permanent, fv = version;
                final long fu = uptime;
                final int fh = hosts, fm = mesh;
                final boolean fmail = mailbox, frelay = relay;
                final boolean fconsole = console, fhosting = hosting, fpassword = adminPassword;
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    mStatusOk = fok;
                    mNodeConsole = fconsole; mHosting = fhosting; mAdminPassword = fpassword;
                    mName = fn;
                    mPermanent = fp;
                    mVersion = fv;
                    mUptime = fu;
                    mHosts = fh;
                    mMailboxHeld = fmail;
                    mRelayOn = frelay;
                    mMeshPeers = fm;
                    mAuthorized.clear();
                    mAuthorized.addAll(auth);
                    mPending.clear();
                    mPending.addAll(pend);
                    rebuild();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    if (!mBuilt) showUnavailable(m);
                });
            }
        });
    }

    /** Populate the status snapshot fields from a (cached) node.status JSON. */
    private void applyStatus(JSONObject s) {
        mStatusOk = bool(s, "ok");
        mNodeConsole = hasConsole(s); mHosting = bool(s, "hostedAccounts"); mAdminPassword = bool(s, "adminPairingPassword");
        mName = str(s, "name");
        mPermanent = str(s, "permanent");
        mVersion = str(s, "version");
        mUptime = lng(s, "uptime");
        mHosts = (int) lng(s, "hosts");
        mMailboxHeld = bool(s, "mailboxHeld");
        mRelayOn = bool(s, "relayOn");
        mMeshPeers = (int) lng(s, "meshPeers");
    }

    private static boolean hasConsole(JSONObject status) {
        // Node 0.2.117 advertised identity import before console capabilities existed.
        // Only Node installs that provider; explicit capability flags always win.
        return status.containsKey("nodeConsole") ? bool(status, "nodeConsole") : bool(status, "identityImport");
    }

    private void showUnavailable(String error) {
        mLoadError = error == null ? "" : error;
        mRoot.removeAllViews();
        mRoot.addView(PortalUi.label(mAct, m(R.string.l10n_native_node_unreachable, SystemMessages.message(mLoadError))));
        mBuilt = true;
    }

    private void rebuild() {
        mLoadError = null;
        mBuilt = true;
        Context c = mAct;
        mRoot.removeAllViews();

        // --- your identity: the account's name, editable (answers "where is my identity?") ---
        LinearLayout ident = PortalUi.card(c);
        LinearLayout identRow = new LinearLayout(c);
        identRow.setOrientation(LinearLayout.HORIZONTAL);
        identRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identCol = new LinearLayout(c);
        identCol.setOrientation(LinearLayout.VERTICAL);
        identCol.addView(PortalUi.label(c, Language.msg(R.string.l10n_settings_your_name)));
        TextView identName = new TextView(c);
        Language.setText(identName, mName.isEmpty() ? tr(R.string.l10n_native_node_unset) : mName);
        identName.setTextColor(c.getColor(R.color.ux_text));
        identName.setTextSize(18);
        identName.setTypeface(identName.getTypeface(), Typeface.BOLD);
        identCol.addView(identName);
        identCol.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_node_name_help)));
        identRow.addView(identCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView edit = PortalUi.ghost(c, Language.msg(R.string.l10n_native_node_edit));
        edit.setOnClickListener(v -> promptSetName());
        identRow.addView(edit);
        ident.addView(identRow);
        mRoot.addView(ident);

        // --- always-on header ---
        LinearLayout head = PortalUi.card(c);
        LinearLayout titleRow = new LinearLayout(c);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(c);
        int ds = PortalUi.dp(c, 9);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ds, ds);
        dlp.rightMargin = PortalUi.dp(c, 8);
        dot.setLayoutParams(dlp);
        dot.setBackgroundResource(R.drawable.status_dot);
        dot.getBackground().mutate().setTint(c.getColor(mHosts > 0 ? R.color.ux_success : R.color.ux_error));
        titleRow.addView(dot);
        TextView t = PortalUi.title(c, mHosts > 0 ? tr(R.string.l10n_native_node_always_online) : tr(R.string.l10n_native_node_offline));
        titleRow.addView(t);
        head.addView(titleRow);
        head.addView(PortalUi.gap(c, 4));
        String sub = (mName.isEmpty() ? tr(R.string.l10n_contacts_owner) : mName)
                + (mVersion.isEmpty() ? "" : "  ·  parlons-cloud " + mVersion);
        head.addView(PortalUi.label(c, sub));
        if (mStatusOk) {
            head.addView(PortalUi.gap(c, 8));
            head.addView(PortalUi.kv(c, tr(R.string.l10n_native_node_uptime), uptime(mUptime)));
            head.addView(PortalUi.kv(c, tr(R.string.l10n_node_relays_attached), String.valueOf(mHosts)));
            head.addView(PortalUi.kv(c, tr(R.string.l10n_native_node_mailbox), mMailboxHeld ? tr(R.string.l10n_native_node_yes) : tr(R.string.l10n_native_node_no)));
        } else {
            head.addView(PortalUi.gap(c, 6));
            head.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_node_update_status)));
        }
        mRoot.addView(head);

        // --- superpowers ---
        mRoot.addView(PortalUi.section(c, Language.msg(R.string.l10n_native_node_benefits)));
        LinearLayout powers = PortalUi.card(c);
        powers.addView(power(c, tr(R.string.l10n_native_node_always_on),
                tr(R.string.l10n_native_node_always_help)));
        powers.addView(divider(c, powers));
        powers.addView(power(c, tr(R.string.l10n_native_node_every_device),
                Language.count(R.plurals.l10n_native_node_device_count, mAuthorized.size())));
        powers.addView(divider(c, powers));
        powers.addView(power(c, tr(R.string.l10n_native_node_relay),
                mRelayOn ? (mMeshPeers > 0 ? Language.count(R.plurals.l10n_native_node_mesh_peers, mMeshPeers) : Language.msg(R.string.l10n_native_node_carrying)) : Language.msg(R.string.l10n_native_node_relay_off)));
        mRoot.addView(powers);

        // --- permanent address ---
        mRoot.addView(PortalUi.section(c, Language.msg(R.string.l10n_native_node_address)));
        LinearLayout addr = PortalUi.card(c);
        addr.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_node_address_help)));
        addr.addView(PortalUi.gap(c, 8));
        TextView val = PortalUi.value(c, mPermanent.isEmpty() ? tr(R.string.l10n_native_contacts_resolving) : mPermanent);
        val.setTextSize(13);
        addr.addView(val);
        addr.addView(PortalUi.gap(c, 10));
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = PortalUi.ghost(c, Language.msg(R.string.l10n_common_copy));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = PortalUi.dp(c, 6);
        copy.setOnClickListener(v -> copy(mPermanent));
        btns.addView(copy, lp1);
        TextView qr = PortalUi.ghost(c, Language.msg(R.string.l10n_contacts_show_qr));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = PortalUi.dp(c, 6);
        qr.setOnClickListener(v -> showQr(mPermanent));
        btns.addView(qr, lp2);
        addr.addView(btns);
        mRoot.addView(addr);

        if (mHosting) {
            mRoot.addView(PortalUi.section(c, Language.msg(R.string.l10n_hosting_title)));
            LinearLayout hosting = PortalUi.card(c);
            hosting.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_node_hosting_help)));
            TextView manage = PortalUi.button(c, Language.msg(R.string.l10n_native_node_manage_hosting));
            manage.setOnClickListener(v -> hostedAction("list", ""));
            hosting.addView(manage); mRoot.addView(hosting);
        }

        if (mNodeConsole) {
            // Server tools are shown only when the server explicitly advertises its console.
            mRoot.addView(PortalUi.section(c, Language.msg(R.string.l10n_native_node_tools)));
            LinearLayout tools = PortalUi.card(c);
            TextView ide = PortalUi.button(c, Language.msg(R.string.l10n_native_node_open_ide));
            ide.setOnClickListener(v -> mAct.startActivity(
                    new android.content.Intent(mAct, com.eurobuddha.maxima.app.portal.ide.IdeActivity.class)));
            tools.addView(ide);
            tools.addView(PortalUi.gap(c, 6));
            tools.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_node_ide_help)));
            mRoot.addView(tools);
            mRoot.addView(PortalUi.gap(c, 12));

            mRoot.addView(PortalUi.section(c, Language.msg(R.string.l10n_native_node_transport)));
            LinearLayout ctrl = PortalUi.card(c);
            TextView open = PortalUi.button(c, Language.msg(R.string.l10n_native_node_open_panel));
            open.setOnClickListener(v -> mAct.startActivity(
                    new android.content.Intent(mAct, CloudNodePanelActivity.class)));
            ctrl.addView(open);
            ctrl.addView(PortalUi.gap(c, 6));
            ctrl.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_node_panel_help)));
            mRoot.addView(ctrl);

        }

        // --- paired devices ---
        mRoot.addView(PortalUi.section(c, tr(R.string.l10n_native_node_devices, mAuthorized.size())));
        LinearLayout devs = PortalUi.card(c);
        if (mAuthorized.isEmpty()) {
            devs.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_node_no_devices)));
        } else {
            for (int i = 0; i < mAuthorized.size(); i++) {
                if (i > 0) devs.addView(divider(c, devs));
                devs.addView(deviceRow(c, mAuthorized.get(i)));
            }
        }
        mRoot.addView(devs);

        if (!mPending.isEmpty()) {
            mRoot.addView(PortalUi.section(c, tr(R.string.l10n_native_node_pending, mPending.size())));
            LinearLayout pend = PortalUi.card(c);
            for (int i = 0; i < mPending.size(); i++) {
                if (i > 0) pend.addView(divider(c, pend));
                pend.addView(pendingRow(c, mPending.get(i)));
            }
            mRoot.addView(pend);
        }

        TextView newCode = PortalUi.ghost(c, Language.msg(R.string.l10n_native_node_pair_other));
        newCode.setOnClickListener(v -> newCode());
        LinearLayout.LayoutParams nclp = PortalUi.matchWrap(c);
        nclp.bottomMargin = PortalUi.dp(c, 24);
        newCode.setLayoutParams(nclp);
        mRoot.addView(newCode);
        mRoot.addView(PortalUi.label(c, mHosting
                ? tr(R.string.l10n_native_node_pair_hosted_help)
                : tr(R.string.l10n_native_node_pair_help)));
    }

    private View power(Context c, CharSequence title, CharSequence desc) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pv = PortalUi.dp(c, 8);
        col.setPadding(0, pv, 0, pv);
        TextView t = new TextView(c);
        Language.setText(t, title);
        t.setTextColor(c.getColor(R.color.ux_text));
        t.setTextSize(15);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        col.addView(t);
        TextView d = new TextView(c);
        Language.setText(d, desc);
        d.setTextColor(c.getColor(R.color.ux_subtext));
        d.setTextSize(13);
        col.addView(d);
        return col;
    }

    private View deviceRow(Context c, Dev dev) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pv = PortalUi.dp(c, 8);
        row.setPadding(0, pv, 0, pv);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(c);
        Language.setText(nm, dev.label.isEmpty() ? tr(R.string.l10n_native_node_device) : dev.label);
        nm.setTextColor(c.getColor(R.color.ux_text));
        nm.setTextSize(15);
        col.addView(nm);
        boolean isThisDevice = sameKey(dev.key, CloudSession.deviceKeyOrEmpty(mAct));
        TextView sub = new TextView(c);
        Language.setText(sub, isThisDevice ? tr(R.string.l10n_native_node_this_device) : tr(R.string.l10n_native_node_paired));
        sub.setTextColor(c.getColor(isThisDevice ? R.color.ux_success : R.color.ux_subtext));
        sub.setTextSize(12);
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView revoke = PortalUi.ghost(c, Language.msg(R.string.l10n_common_revoke));
        revoke.setTextColor(c.getColor(R.color.ux_error));
        revoke.setOnClickListener(v -> confirmRevoke(dev));
        row.addView(revoke);
        return row;
    }

    private View pendingRow(Context c, String key) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pv = PortalUi.dp(c, 8);
        row.setPadding(0, pv, 0, pv);
        TextView k = new TextView(c);
        Language.setText(k, tr(R.string.l10n_native_node_wants_pair));
        k.setTextColor(c.getColor(R.color.ux_text));
        k.setTextSize(15);
        row.addView(k, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView approve = PortalUi.button(c, Language.msg(R.string.l10n_common_approve));
        approve.setOnClickListener(v -> approve(key));
        row.addView(approve);
        return row;
    }

    private void confirmRevoke(Dev dev) {
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_native_node_revoke_title))
                .setMessage(m(R.string.l10n_native_node_revoke_help, dev.label.isEmpty() ? m(R.string.l10n_native_settings_this_device) : dev.label))
                .setNegativeButton(m(R.string.l10n_common_cancel), null)
                .setPositiveButton(m(R.string.l10n_common_revoke), (d, w) -> revoke(dev.key))
                .show();
    }

    /** Set the account's display name — the node re-announces it to every contact.
     *  Public so MainActivity's overflow menu ("Set account name") can open it too. */
    public void promptSetName() {
        final android.widget.EditText field = new android.widget.EditText(mAct);
        field.setText(mName);
        Language.hint(field, Language.msg(R.string.l10n_native_node_name_example));
        field.setSingleLine(true);
        int pad = PortalUi.dp(mAct, 20);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(mAct);
        wrap.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        wrap.addView(field);
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_settings_your_name))
                .setMessage(m(R.string.l10n_native_node_name_announce))
                .setView(wrap)
                .setNegativeButton(m(R.string.l10n_common_cancel), null)
                .setPositiveButton(m(R.string.l10n_native_settings_set), (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) {
                        mAct.toast(tr(R.string.l10n_native_contacts_name_required));
                        return;
                    }
                    act(r -> r.setName(name), m(R.string.l10n_native_node_name_set),
                            m(R.string.l10n_native_node_name_failed));
                })
                .show();
        field.requestFocus();
    }

    private void approve(String key) {
        act(r -> r.approve(key), m(R.string.l10n_common_approved), m(R.string.l10n_native_node_approve_failed));
    }

    private void revoke(String key) {
        act(r -> r.revoke(key), m(R.string.l10n_common_revoked), m(R.string.l10n_native_node_revoke_failed));
    }

    private void hostedAction(String action, String name) {
        final String account = CloudSession.account(mAct);
        mAct.toast(tr(R.string.l10n_native_node_loading_hosting));
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                if (!account.equals(CloudSession.account(mAct))) return;
                JSONObject result = null; String error = null;
                try {
                    result = r.hostedAccounts(action, name);
                    if (!bool(result, "ok")) error = str(result, "error");
                } catch (Exception e) { error = e.getMessage(); }
                final JSONObject reply = result; final String failure = error;
                mAct.runOnUiThread(() -> {
                    if (mAct.isFinishing() || !account.equals(CloudSession.account(mAct))) return;
                    if (reply == null || failure != null) { mAct.toast(failure == null ? tr(R.string.l10n_native_node_host_unreachable) : SystemMessages.text(mAct, failure)); return; }
                    if ("invite".equals(action)) {
                        String invite = str(reply, "invite");
                        new LanguageDialog(mAct).setTitle(m(R.string.l10n_native_node_invite_title, name))
                                .setMessage(m(R.string.l10n_native_node_invite_message, name, invite))
                                .setPositiveButton(m(R.string.l10n_pairing_copy_invite), (d,w) -> copy(invite, tr(R.string.l10n_native_node_host_invite)))
                                .setNeutralButton("QR", (d,w) -> showQr(m(R.string.l10n_native_node_invite_title, name), invite))
                                .setNegativeButton(m(R.string.l10n_language_close), null).show();
                    } else showHostedAccounts(reply);
                });
            }
            public void err(String message) { mAct.runOnUiThread(() -> {
                if (!mAct.isFinishing() && account.equals(CloudSession.account(mAct))) mAct.toast(message);
            }); }
        });
    }

    private void showHostedAccounts(JSONObject reply) {
        JSONArray accounts = (JSONArray) reply.get("accounts");
        boolean online = bool(reply, "online");
        boolean managed = bool(reply, "managed"), enabled = bool(reply, "enabled");
        boolean chatOnly = "chat-only".equals(str(reply, "access"));
        List<JSONObject> rows = new ArrayList<>(); List<CharSequence> labels = new ArrayList<>();
        if (accounts != null) for (Object o : accounts) {
            JSONObject row = (JSONObject) o; rows.add(row);
            labels.add(Language.join(str(row, "name"), " — ", SystemMessages.message(str(row, "state"))));
        }
        AlertDialog.Builder dialog = new LanguageDialog(mAct)
                .setTitle(online ? m(R.string.l10n_native_node_hosting_count, rows.size(), lng(reply, "limit")) : m(R.string.l10n_native_node_hosting_offline))
                .setNegativeButton(m(R.string.l10n_language_close), null)
                .setNeutralButton(m(R.string.l10n_common_refresh), (d,w) -> hostedAction("list", ""));
        if (managed) dialog.setNeutralButton(m(R.string.l10n_native_node_hosting_options), (d,w) ->
                new LanguageDialog(mAct).setTitle(m(R.string.l10n_native_node_hosting_computer))
                        .setItems(new CharSequence[]{m(R.string.l10n_common_refresh), enabled ? m(R.string.l10n_hosting_stop) : m(R.string.l10n_hosting_enable)}, (x,i) -> {
                            if (i == 0) hostedAction("list", "");
                            else new LanguageDialog(mAct).setTitle(enabled ? m(R.string.l10n_native_node_stop_hosting) : m(R.string.l10n_native_node_enable_hosting))
                                    .setMessage(enabled ? m(R.string.l10n_native_node_stop_help)
                                            : m(R.string.l10n_native_node_enable_help))
                                    .setNegativeButton(m(R.string.l10n_common_cancel), null).setPositiveButton(enabled ? m(R.string.l10n_native_node_stop) : m(R.string.l10n_native_node_enable),
                                            (a,b) -> hostedAction(enabled ? "disable" : "enable", "")).show();
                        }).setNegativeButton(m(R.string.l10n_language_close), null).show());
        if (rows.isEmpty()) dialog.setMessage(online
                ? (chatOnly ? m(R.string.l10n_native_node_empty_chat_only) : m(R.string.l10n_native_node_empty_hosting))
                : managed ? (enabled ? m(R.string.l10n_hosting_starting) : m(R.string.l10n_native_node_hosting_stopped)) : m(R.string.l10n_native_node_operator_start));
        else dialog.setItems(labels.toArray(new CharSequence[0]), (d,i) -> {
            JSONObject row = rows.get(i); String name = str(row, "name");
            boolean paused = bool(row, "paused");
            if (!online) { mAct.toast(tr(R.string.l10n_native_node_hosting_is_offline)); return; }
            List<CharSequence> actions = new ArrayList<>();
            boolean ready = "running".equals(str(row, "state"));
            if (ready) actions.add(m(R.string.l10n_hosting_show_invite));
            actions.add(paused ? m(R.string.l10n_native_node_resume_account) : m(R.string.l10n_native_node_pause_account));
            new LanguageDialog(mAct).setTitle(name).setItems(actions.toArray(new CharSequence[0]), (a,j) -> {
                if (ready && j == 0) hostedAction("invite", name);
                else new LanguageDialog(mAct).setTitle(paused ? m(R.string.l10n_native_node_resume_title, name) : m(R.string.l10n_native_node_pause_title, name))
                        .setMessage(paused ? m(R.string.l10n_native_node_resume_help) : m(R.string.l10n_native_node_pause_help))
                        .setNegativeButton(m(R.string.l10n_common_cancel), null).setPositiveButton(paused ? m(R.string.l10n_common_resume) : m(R.string.l10n_common_pause),
                                (x,y) -> hostedAction(paused ? "resume" : "pause", name)).show();
            }).setNegativeButton(m(R.string.l10n_language_close), null).show();
        });
        if (online) dialog.setPositiveButton(m(R.string.l10n_hosting_create), (d,w) -> {
            android.widget.EditText field = new android.widget.EditText(mAct);
            field.setSingleLine(true); Language.hint(field, Language.msg(R.string.l10n_native_node_account_example));
            LinearLayout wrap = new LinearLayout(mAct); int pad = PortalUi.dp(mAct, 20);
            wrap.setPadding(pad, pad, pad, 0); wrap.addView(field, PortalUi.matchWrap(mAct));
            new LanguageDialog(mAct).setTitle(m(R.string.l10n_native_node_host_friend))
                    .setMessage(chatOnly ? m(R.string.l10n_native_node_create_chat_help) : m(R.string.l10n_native_node_create_help))
                    .setView(wrap).setNegativeButton(m(R.string.l10n_common_cancel), null)
                    .setPositiveButton(m(R.string.l10n_common_create), (x,y) -> hostedAction("create", field.getText().toString().trim())).show();
        });
        dialog.show();
    }

    private void newCode() {
        if (!mAdminPassword) { newCode(""); return; }
        android.widget.EditText password = new android.widget.EditText(mAct);
        password.setSingleLine(true); Language.hint(password, Language.msg(R.string.l10n_pairing_admin_password));
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout wrap = new LinearLayout(mAct); int pad = PortalUi.dp(mAct, 20);
        wrap.setPadding(pad, pad, pad, 0); wrap.addView(password, PortalUi.matchWrap(mAct));
        new LanguageDialog(mAct).setTitle(m(R.string.l10n_native_node_invite_device))
                .setMessage(m(R.string.l10n_native_node_admin_help))
                .setView(wrap).setNegativeButton(m(R.string.l10n_common_cancel), (d,w) -> password.setText(""))
                .setPositiveButton(m(R.string.l10n_common_continue), (d,w) -> {
                    String value = password.getText().toString(); password.setText("");
                    if (value.isEmpty()) {
                        if (mHosting) hostedAction("list", "");
                        else mAct.toast(tr(R.string.l10n_native_node_hosting_not_enabled));
                    } else newCode(value);
                }).show();
    }

    private void newCode(String adminPassword) {
        mAct.toast(tr(R.string.l10n_native_node_minting));
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String note = null, error = null, code = "", invite = "";
                try {
                    JSONObject res = r.newCode(adminPassword);
                    if (bool(res, "ok")) {
                        note = str(res, "note");
                        code = str(res, "code");
                        invite = str(res, "invite");
                    } else {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fnote = note, ferr = error;
                final String fcode = code, finvite = invite;
                mAct.runOnUiThread(() -> {
                    if (ferr == null) {
                        AlertDialog.Builder dialog = new LanguageDialog(mAct)
                                .setTitle(m(R.string.l10n_native_node_code_minted))
                                .setMessage(pairingMessage(fcode, finvite, fnote))
                                .setNegativeButton(m(R.string.l10n_language_close), null);
                        if (!finvite.isEmpty()) {
                            dialog.setPositiveButton(m(R.string.l10n_pairing_copy_invite), (d, w) -> copy(finvite, tr(R.string.l10n_native_node_invite)));
                            dialog.setNeutralButton("QR", (d, w) -> showQr(m(R.string.l10n_native_node_pair_device), finvite));
                        } else if (!fcode.isEmpty()) {
                            dialog.setPositiveButton(m(R.string.l10n_native_node_copy_code), (d, w) -> copy(fcode, tr(R.string.l10n_onboarding_code_label)));
                        }
                        dialog.show();
                    } else {
                        mAct.toast(SystemMessages.text(mAct, ferr));
                    }
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> mAct.toast(m));
            }
        });
    }

    /** Prefer structured reply fields; the legacy English note must not hide translated guidance. */
    static CharSequence pairingMessage(String code, String invite, String note) {
        if (code != null && !code.isEmpty()) {
            return invite == null || invite.isEmpty()
                    ? Language.msg(R.string.l10n_pairing_code_manual_message, code)
                    : Language.msg(R.string.l10n_pairing_code_invite_message, code, invite);
        }
        return note == null || note.isEmpty() ? Language.msg(R.string.l10n_native_node_no_code)
                : SystemMessages.message(note);
    }

    private interface Call {
        JSONObject run(ParlonsRemote r) throws Exception;
    }

    private void act(Call call, Language.Message okMsg, Language.Message failMsg) {
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = call.run(r);
                    if (!bool(res, "ok")) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                mAct.runOnUiThread(() -> {
                    if (err == null) {
                        mAct.toast(okMsg.toString());
                        mLastLoad = 0;
                        render();
                    } else {
                        mAct.toast(tr(R.string.l10n_native_node_action_failed, failMsg.toString(), SystemMessages.text(mAct, err)));
                    }
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> mAct.toast(tr(R.string.l10n_native_node_action_failed, failMsg.toString(), SystemMessages.text(mAct, m))));
            }
        });
    }

    private View divider(Context c, LinearLayout parent) {
        View div = new View(c);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, PortalUi.dp(c, 1));
        div.setLayoutParams(dlp);
        div.setBackgroundColor(c.getColor(R.color.ux_divider));
        return div;
    }

    private void copy(String s) {
        copy(s, tr(R.string.l10n_common_address));
    }

    private void copy(String s, String label) {
        if (s == null || s.isEmpty()) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) mAct.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, s));   // full, never truncated
        mAct.toast(tr(R.string.l10n_native_node_copied, label));
    }

    private void showQr(String s) {
        showQr(m(R.string.l10n_native_node_address), s);
    }

    private void showQr(CharSequence title, String s) {
        if (s == null || s.isEmpty()) return;
        int px = PortalUi.dp(mAct, 260);
        Bitmap bmp = Qr.encode(s, px);
        ImageView iv = new ImageView(mAct);
        iv.setImageBitmap(bmp);
        int pad = PortalUi.dp(mAct, 20);
        iv.setPadding(pad, pad, pad, pad);
        new LanguageDialog(mAct)
                .setTitle(title)
                .setView(iv)
                .setPositiveButton(m(R.string.l10n_language_close), null)
                .show();
    }

    private String uptime(long ms) {
        if (ms <= 0) return tr(R.string.l10n_native_node_started);
        long s = ms / 1000;
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        if (d > 0) return tr(R.string.l10n_native_node_days_hours, d, h);
        if (h > 0) return tr(R.string.l10n_native_node_hours_minutes, h, m);
        return tr(R.string.l10n_native_node_minutes, m);
    }

    @Override public void languageChanged() { if (mBuilt && mLoadError == null) rebuild(); }

    private static Language.Message m(int resource, Object... args) { return Language.msg(resource, args); }

    private String tr(int resource, Object... args) { return Language.text(mAct, resource, args); }

    private static boolean sameKey(String a, String b) {
        return a != null && !a.isEmpty() && a.equalsIgnoreCase(b);
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
}
