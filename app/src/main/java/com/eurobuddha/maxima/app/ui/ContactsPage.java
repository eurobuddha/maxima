package com.eurobuddha.maxima.app.ui;

import com.eurobuddha.maxima.app.i18n.LanguageDialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import com.eurobuddha.maxima.app.i18n.Language;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.chat.ChatActivity;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Who can reach you, and how you reach them — in the greyscale design language.
 *
 * A Maxima address is {@code Mx<base32 pubkey>@host:port} ≈ 273 characters, far
 * too long to read, so this screen is built so you never have to: you SHARE your
 * address (QR + one-tap copy of the complete string), and each contact's full
 * address lives in their card, copyable whole. Nothing is ever shown truncated
 * without the complete value one tap away.
 *
 * When we hand out our own address the appended host is always an IP, never a
 * domain (see {@link #toIpForm}).
 */
public final class ContactsPage implements Page {

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mBox;

    private TextView mHostInfo;
    private EditText mSearch;
    private TextView mCountLabel;
    private LinearLayout mList;

    private String mSearchQuery = "";
    private String mLastSignature = "";

    private static final long ONLINE_MS = 30 * 60 * 1000L;

    public ContactsPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mBox = zView.findViewById(R.id.contacts_root);
        buildUi();
    }

    @Override
    public View view() {
        return mView;
    }

    @Override
    public CharSequence title() {
        return com.eurobuddha.maxima.app.i18n.Language.msg(com.eurobuddha.maxima.app.R.string.l10n_nav_contacts);
    }

    @Override
    public void render() {
        com.eurobuddha.maxima.core.ChatPort node = MaximaService.port();
        if (node == null) {
            Language.setText(mHostInfo, Language.msg(R.string.l10n_native_contacts_starting));
            return;
        }
        List<String> addrs = node.myAddresses();
        Language.setText(mHostInfo, addrs.isEmpty()
                ? Language.msg(R.string.l10n_native_contacts_no_host)
                : Language.count(R.plurals.l10n_native_contacts_hosts, addrs.size()));

        List<Contact> cs = node.contacts();
        StringBuilder sig = new StringBuilder();
        for (Contact c : cs) {
            sig.append(c.publicKey).append(c.name).append(c.primaryAddress())
                    .append(statusLabel(c)).append(peerPill(c)).append('|');
        }
        if (sig.toString().equals(mLastSignature)) {
            return;
        }
        mLastSignature = sig.toString();
        rebuildList(cs);
    }

    // ---------------------------------------------------------------
    // List
    // ---------------------------------------------------------------

    private void rebuildList(List<Contact> all) {
        mList.removeAllViews();
        List<Contact> cs = filtered(all);
        Language.setText(mCountLabel, Language.msg(R.string.l10n_native_contacts_list_count, all.size()));
        if (cs.isEmpty()) {
            mList.addView(sub(all.isEmpty()
                    ? tr(R.string.l10n_native_contacts_empty)
                    : tr(R.string.l10n_native_contacts_no_matches, mSearchQuery.trim())));
            return;
        }
        boolean first = true;
        for (Contact c : cs) {
            if (!first) {
                mList.addView(divider());
            }
            mList.addView(contactRow(c));
            first = false;
        }
    }

    private List<Contact> filtered(List<Contact> all) {
        String q = mSearchQuery.trim().toLowerCase();
        if (q.isEmpty()) {
            return all;
        }
        List<Contact> out = new ArrayList<>();
        for (Contact c : all) {
            if ((c.name != null && c.name.toLowerCase().contains(q))
                    || (c.publicKey != null && c.publicKey.toLowerCase().contains(q))) {
                out.add(c);
            }
        }
        return out;
    }

    private View contactRow(Contact c) {
        LinearLayout row = new LinearLayout(mAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vp = dp(11);
        row.setPadding(dp(4), vp, dp(4), vp);
        row.setClickable(true);
        row.setForeground(ripple());

        TextView av = new TextView(mAct);
        av.setGravity(Gravity.CENTER);
        av.setTextSize(17);
        av.setTypeface(Typeface.DEFAULT_BOLD);
        Avatars.apply(av, c.publicKey, c.name);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(46), dp(46));
        ap.rightMargin = dp(12);
        row.addView(av, ap);

        LinearLayout mid = new LinearLayout(mAct);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(mAct);
        Language.setText(name, c.name);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextColor(col(R.color.ux_text));
        boolean online = c.lastSeen > 0 && System.currentTimeMillis() - c.lastSeen < ONLINE_MS;
        TextView pr = new TextView(mAct);
        Language.setText(pr, "● " + presenceText(c));
        pr.setTextSize(11.5f);
        pr.setSingleLine(true);
        pr.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pr.setTextColor(col(online ? R.color.ux_success : R.color.ux_subtext));
        mid.addView(name);
        mid.addView(pr);
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));

        String pill = peerPill(c);
        if (pill != null) {
            TextView badge = new TextView(mAct);
            Language.setText(badge, pill.toUpperCase());
            badge.setTextSize(9);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setLetterSpacing(0.08f);
            boolean core = pill.equals("core");
            badge.setBackgroundResource(core ? R.drawable.chip_on : R.drawable.chip_off);
            badge.setTextColor(col(core ? R.color.ux_on_accent : R.color.ux_subtext));
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.rightMargin = dp(4);
            row.addView(badge, bp);
        }

        ImageView info = new ImageView(mAct);
        info.setImageResource(R.drawable.ic_more);
        info.setColorFilter(col(R.color.ux_subtext));
        info.setBackground(ripple());
        int ip = dp(7);
        info.setPadding(ip, ip, ip, ip);
        info.setOnClickListener(x -> showContactCard(c));
        row.addView(info, new LinearLayout.LayoutParams(dp(32), dp(32)));

        // Tap → chat; long-press or the ⋯ button → contact card.
        row.setOnClickListener(x -> openChat(c));
        row.setOnLongClickListener(x -> {
            showContactCard(c);
            return true;
        });
        return row;
    }

    private void openChat(Contact c) {
        Intent i = new Intent(mAct, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_CONVERSATION, c.publicKey);
        mAct.startActivity(i);
    }

    // ---------------------------------------------------------------
    // Contact card sheet
    // ---------------------------------------------------------------

    private void showContactCard(Contact c) {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(mAct);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView av = new TextView(mAct);
        av.setGravity(Gravity.CENTER);
        av.setTextSize(20);
        av.setTypeface(Typeface.DEFAULT_BOLD);
        Avatars.apply(av, c.publicKey, c.name);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(52), dp(52));
        ap.rightMargin = dp(13);
        head.addView(av, ap);
        LinearLayout hn = new LinearLayout(mAct);
        hn.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(mAct);
        Language.setText(nm, c.name);
        nm.setTextSize(18);
        nm.setTypeface(manrope(Typeface.BOLD));
        nm.setTextColor(col(R.color.ux_text));
        boolean online = c.lastSeen > 0 && System.currentTimeMillis() - c.lastSeen < ONLINE_MS;
        TextView pr = new TextView(mAct);
        Language.setText(pr, "● " + presenceText(c));
        pr.setTextSize(12);
        pr.setTextColor(col(online ? R.color.ux_success : R.color.ux_subtext));
        hn.addView(nm);
        hn.addView(pr);
        head.addView(hn);
        body.addView(head, marginBottom(dp(16)));

        TextView msg = primaryButton(Language.msg(R.string.l10n_chat_message));
        body.addView(msg, marginBottom(dp(4)));

        String addr = c.primaryAddress();
        if (addr != null) {
            body.addView(copyField(Language.msg(R.string.l10n_native_contacts_address), addr, Language.msg(R.string.l10n_native_contacts_address_copied)));
            // RULE 1: every host address must be copyable in full, not just
            // counted. Tap to reveal the rest as their own copy fields.
            if (c.addresses != null && c.addresses.size() > 1) {
                final LinearLayout extras = new LinearLayout(mAct);
                extras.setOrientation(LinearLayout.VERTICAL);
                extras.setVisibility(View.GONE);
                final int n = c.addresses.size() - 1;
                final TextView more = ghostButton(Language.count(R.plurals.l10n_native_contacts_more_hosts, n));
                more.setPadding(dp(2), dp(8), 0, 0);
                more.setOnClickListener(v -> {
                    if (extras.getChildCount() == 0) {
                        int idx = 0;
                        for (String a : c.addresses) {
                            if (a != null && !a.equals(addr)) {
                                extras.addView(copyField(Language.msg(R.string.l10n_native_contacts_host_number, ++idx), a, Language.msg(R.string.l10n_native_contacts_address_copied)));
                            }
                        }
                    }
                    extras.setVisibility(View.VISIBLE);
                    more.setVisibility(View.GONE);
                });
                body.addView(more);
                body.addView(extras);
            }
        } else {
            TextView none = sub(Language.msg(R.string.l10n_native_contacts_await_reply));
            none.setPadding(0, dp(12), 0, 0);
            body.addView(none);
        }

        // Share this contact: the permanent address when we know their directory (it survives
        // their relay changes), else the current one - copy it, hand it to any app, or send it
        // to another contact as a card they can add with one tap.
        final String share = c.shareAddress();
        if (!share.isEmpty()) {
            if (!share.equals(addr)) {
                body.addView(copyField(Language.msg(R.string.l10n_native_contacts_permanent_share), share, Language.msg(R.string.l10n_native_contacts_share_copied)));
            }
            LinearLayout shareRow = new LinearLayout(mAct);
            shareRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView shareBtn = ghostButton(Language.msg(R.string.l10n_native_contacts_share));
            shareBtn.setOnClickListener(v -> shareText(share, tr(R.string.l10n_native_contacts_share_name, c.name)));
            TextView sendBtn = ghostButton(Language.msg(R.string.l10n_contacts_send_to));
            sendBtn.setOnClickListener(v -> showSendContactPicker(c, share));
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
            shareRow.addView(shareBtn, half);
            shareRow.addView(sendBtn, half);
            body.addView(shareRow, marginBottom(dp(4)));
        }

        if (c.minimaAddress != null && !c.minimaAddress.isEmpty()) {
            body.addView(copyField(Language.msg(R.string.l10n_native_contacts_payment_address), c.minimaAddress, Language.msg(R.string.l10n_native_contacts_payment_copied)));
        }

        LinearLayout meta = new LinearLayout(mAct);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(0, dp(16), 0, 0);
        meta.addView(kv(Language.msg(R.string.l10n_native_contacts_software), softwareLabel(c)));
        meta.addView(kv(Language.msg(R.string.l10n_native_contacts_last_seen), online ? tr(R.string.l10n_native_contacts_online_now) : lastSeenText(c)));
        body.addView(meta);

        body.addView(divider(), marginTB(dp(16)));
        TextView remove = ghostButton(Language.msg(R.string.l10n_contacts_remove));
        remove.setTextColor(col(R.color.ux_error));
        body.addView(remove);

        BottomSheetDialog d = sheet(c.name, body);
        msg.setOnClickListener(v -> {
            d.dismiss();
            openChat(c);
        });
        remove.setOnClickListener(v -> confirmRemove(c, d));
    }

    private void confirmRemove(Contact c, BottomSheetDialog sheet) {
        new LanguageDialog(mAct)
                .setTitle(Language.msg(R.string.l10n_native_contacts_remove_name, c.name))
                .setMessage(Language.msg(R.string.l10n_native_contacts_remove_help))
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_common_remove), (d, w) -> {
                    com.eurobuddha.maxima.core.ChatPort n = MaximaService.port();
                    if (n != null) {
                        n.removeContact(c.publicKey);
                    }
                    mLastSignature = "";
                    render();
                    sheet.dismiss();
                })
                .show();
    }

    // ---------------------------------------------------------------
    // My address + add sheets
    // ---------------------------------------------------------------

    private void showMyAddressSheet() {
        String raw = pickShareAddrRaw();
        if (raw == null) {
            mAct.toast(tr(R.string.l10n_native_contacts_no_host_wait));
            return;
        }
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        final ImageView qr = new ImageView(mAct);
        int qp = dp(12);
        qr.setPadding(qp, qp, qp, qp);
        qr.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(210), dp(210));
        qlp.bottomMargin = dp(14);
        body.addView(qr, qlp);

        final LinearLayout copyHolder = new LinearLayout(mAct);
        copyHolder.setOrientation(LinearLayout.VERTICAL);
        body.addView(copyHolder, new LinearLayout.LayoutParams(-1, -2));

        // KeetChat identity: the phone node's keets pubkey - what a KeetChat
        // user adds as a contact. Independent of the Maxima address resolve,
        // shown in FULL, copyable.
        com.eurobuddha.maxima.core.chat.ChatEngine keetsCe = MaximaService.chat();
        String keetsPk = keetsCe == null ? null : keetsCe.keetsPubkey();
        if (keetsPk != null) {
            body.addView(copyField(Language.msg(R.string.l10n_native_contacts_keets_address), keetsPk,
                    Language.msg(R.string.l10n_native_contacts_keets_copied)), new LinearLayout.LayoutParams(-1, -2));
        }

        final TextView shareBtn = primaryButton(Language.msg(R.string.l10n_native_contacts_share));
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, -2);
        sbp.topMargin = dp(12);
        body.addView(shareBtn, sbp);

        int hosts = 1;
        com.eurobuddha.maxima.core.ChatPort node = MaximaService.port();
        if (node != null) {
            hosts = Math.max(1, node.myAddresses().size());
        }
        TextView note = sub(raw.startsWith("MAX#")
                ? Language.msg(R.string.l10n_native_contacts_permanent_help)
                : Language.count(R.plurals.l10n_native_contacts_share_hosts, hosts));
        note.setPadding(dp(2), dp(10), dp(2), 0);
        body.addView(note);

        sheet(Language.msg(R.string.l10n_native_contacts_share_my_address), body);

        // Resolve host → IP off-main, then fill QR + copy field + share.
        // A MAX# permanent address is location-independent - no resolving.
        new Thread(() -> {
            final String ipAddr = raw.startsWith("MAX#") ? raw : toIpForm(raw);
            mAct.runOnUiThread(() -> {
                if (ipAddr == null) {
                    // Couldn't turn a domain host into an IP — never share the
                    // domain form. Ask the user to retry once online.
                    copyHolder.removeAllViews();
                    TextView warn = sub(Language.msg(R.string.l10n_native_contacts_ip_failed));
                    warn.setPadding(dp(2), dp(6), dp(2), 0);
                    copyHolder.addView(warn);
                    shareBtn.setEnabled(false);
                    shareBtn.setAlpha(0.4f);
                    return;
                }
                final Bitmap bmp = Qr.encode(ipAddr, dp(200));
                if (bmp != null) {
                    qr.setImageBitmap(bmp);
                }
                copyHolder.removeAllViews();
                copyHolder.addView(copyField(Language.msg(R.string.l10n_native_contacts_your_address), ipAddr, Language.msg(R.string.l10n_native_contacts_address_copied)));
                shareBtn.setOnClickListener(v -> shareText(ipAddr));
            });
        }, "resolve-share-addr").start();
    }

    private void showAddSheet() {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView scan = primaryButton(Language.msg(R.string.l10n_native_contacts_scan_their_qr));
        body.addView(scan, marginBottom(dp(2)));

        TextView or = sub(Language.msg(R.string.l10n_native_contacts_or_paste));
        or.setGravity(Gravity.CENTER);
        or.setLetterSpacing(0.1f);
        or.setPadding(0, dp(14), 0, dp(10));
        body.addView(or);

        final EditText paste = new EditText(mAct);
        Language.hint(paste, "Mx…@host:port");
        paste.setBackgroundResource(R.drawable.field_pill);
        paste.setPadding(dp(14), dp(12), dp(14), dp(12));
        paste.setTextColor(col(R.color.ux_text));
        paste.setHintTextColor(col(R.color.ux_subtext));
        paste.setTextSize(13);
        paste.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(paste, marginBottom(dp(11)));

        TextView intro = ghostButton(Language.msg(R.string.l10n_native_contacts_introduce));
        body.addView(intro);

        TextView note = sub(Language.msg(R.string.l10n_native_contacts_intro_help));
        note.setPadding(dp(2), dp(12), dp(2), 0);
        body.addView(note);

        BottomSheetDialog d = sheet(Language.msg(R.string.l10n_contacts_add_heading), body);
        scan.setOnClickListener(v -> {
            d.dismiss();
            mAct.scanQr(this::onScanned);
        });
        intro.setOnClickListener(v -> {
            if (introduceAddr(paste.getText().toString())) {
                d.dismiss();
            }
        });
    }

    /** Called by MainActivity with a scanned address; introduce to it. */
    public void onScanned(String zText) {
        introduceAddr(zText);
    }

    /** Validate + fire the introduce handshake. Returns true if accepted. */
    private boolean introduceAddr(String zText) {
        if (zText == null) {
            return false;
        }
        final String addr = zText.trim();
        if (addr.isEmpty()) {
            mAct.toast(tr(R.string.l10n_native_contacts_paste_mx));
            return false;
        }
        final com.eurobuddha.maxima.core.ChatPort node = MaximaService.port();
        if (node == null) {
            mAct.toast(tr(R.string.l10n_native_chat_transport_stopped));
            return false;
        }
        // A bare keets pubkey (0x + 64 hex - the KeetChat QR / identity dialog):
        // a keets-only contact, no Maxima handshake. Needs the phone's keets-enabled
        // minimaCore node; without it the engine has no keets transport.
        if (addr.matches("(?i)0x[0-9a-f]{64}")) {
            com.eurobuddha.maxima.core.chat.ChatEngine ce = MaximaService.chat();
            if (ce == null || !ce.hasKeets()) {
                mAct.toast(tr(R.string.l10n_native_contacts_keets_required));
                return false;
            }
            com.eurobuddha.maxima.core.contacts.Contact kc = node.storeKeetsContact(addr, "");
            if (kc == null) {
                mAct.toast(tr(R.string.l10n_native_contacts_keets_unsupported));
                return false;
            }
            mAct.toast(tr(R.string.l10n_native_contacts_keets_added));
            mLastSignature = "";
            render();
            return true;
        }
        if (!addr.startsWith("Mx") && !addr.startsWith("MAX#")) {
            mAct.toast(tr(R.string.l10n_native_contacts_invalid_address));
            return false;
        }
        EventLog.add("introducing ourselves to " + addr.substring(Math.max(0, addr.indexOf('@'))));
        new Thread(() -> {
            try {
                node.introduce(addr, true);
                mAct.runOnUiThread(() -> {
                    mAct.toast(tr(R.string.l10n_native_contacts_intro_sent));
                    mLastSignature = "";
                    render();
                });
            } catch (Exception e) {
                EventLog.add("introduce failed: " + e.getMessage());
                mAct.runOnUiThread(() -> mAct.toast(tr(R.string.l10n_native_chat_operation_failed, e.getMessage())));
            }
        }, "introduce").start();
        return true;
    }

    private void shareText(String text) {
        shareText(text, tr(R.string.l10n_native_contacts_share_your_parlons));
    }

    private void shareText(String text, String title) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        mAct.startActivity(Intent.createChooser(i, title));
    }

    /** Pick another contact and send them zWho as a contact card (ChatContact body kind). */
    private void showSendContactPicker(final Contact zWho, final String zShare) {
        com.eurobuddha.maxima.core.MaximaNode node = MaximaService.node();
        if (node == null) {
            mAct.toast(tr(R.string.l10n_native_contacts_not_connected));
            return;
        }
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);
        int n = 0;
        for (final Contact t : node.contacts()) {
            if (t.publicKey == null || t.publicKey.equalsIgnoreCase(zWho.publicKey)) {
                continue;
            }
            n++;
            TextView row = ghostButton(t.name == null || t.name.isEmpty() ? t.publicKey : t.name);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            body.addView(row);
        }
        if (n == 0) {
            body.addView(sub(Language.msg(R.string.l10n_contacts_none_to_send)));
        }
        final BottomSheetDialog d = sheet(Language.msg(R.string.l10n_native_contacts_send_name_to, zWho.name), body);
        int idx = 0;
        for (final Contact t : node.contacts()) {
            if (t.publicKey == null || t.publicKey.equalsIgnoreCase(zWho.publicKey)) {
                continue;
            }
            final View row = body.getChildAt(idx++);
            row.setOnClickListener(v -> {
                d.dismiss();
                final String card = com.eurobuddha.maxima.core.chat.ChatContact.wrap(zWho.publicKey, zWho.name, zShare);
                new Thread(() -> {
                    com.eurobuddha.maxima.core.chat.ChatEngine chat = MaximaService.chat();
                    boolean ok = false;
                    try { ok = chat != null && chat.send(t, card) != null; } catch (Exception ignored) { }
                    final boolean fok = ok;
                    mAct.runOnUiThread(() -> mAct.toast(fok ? tr(R.string.l10n_native_contacts_sent_to, t.name) : tr(R.string.l10n_native_contacts_send_failed)));
                }).start();
            });
        }
    }

    // ---------------------------------------------------------------
    // Address helpers — appended host is ALWAYS an IP, never a domain.
    // ---------------------------------------------------------------

    /** Prefer an address whose host is already an IP; else the first one.
     *  With a static MLS pinned, share the PERMANENT MAX# address instead -
     *  it survives every rotation, which is the whole point of sharing it. */
    private String pickShareAddrRaw() {
        com.eurobuddha.maxima.app.jar.JarEngine jarEngine = MaximaService.jar();
        if (jarEngine != null) {
            String perm = jarEngine.permanentAddress();
            if (!perm.isEmpty()) {
                return perm;
            }
        }
        // Built-in engine: share the FIXED permanent MAX# (MAX#<you>#<pool relay>)
        // ONLY when the user has pinned an anchor (isStaticMls) — otherwise the
        // address drifts and isn't permanent, so we share the per-relay form below.
        com.eurobuddha.maxima.core.MaximaNode builtin = MaximaService.node();
        if (builtin != null && builtin.isStaticMls()) {
            String perm = builtin.permanentAddress();
            if (!perm.isEmpty()) {
                return perm;
            }
        }
        com.eurobuddha.maxima.core.ChatPort node = MaximaService.port();
        if (node == null) {
            return null;
        }
        List<String> addrs = node.myAddresses();
        for (String a : addrs) {
            if (isIp(hostOf(a))) {
                return a;
            }
        }
        return addrs.isEmpty() ? null : addrs.get(0);
    }

    /** {@code Mx…@host:port} → {@code Mx…@<ip>:port}, resolving a domain via DNS.
     *  Call off-main. Returns null rather than ever handing out a domain form:
     *  the rule is that OUR shared address always carries an IP host, never a
     *  domain (a domain can be repointed or shared across hosts). A malformed
     *  input with no '@' has no host to leak and is returned unchanged. */
    private String toIpForm(String addr) {
        int at = addr == null ? -1 : addr.lastIndexOf('@');
        if (at < 0) {
            return addr;
        }
        String mx = addr.substring(0, at);
        String hp = addr.substring(at + 1);
        // Bracketed IPv6 literal ([2001:db8::1]:port): the host itself contains
        // colons, so never split on the last colon.
        if (hp.startsWith("[")) {
            return addr;
        }
        int colon = hp.lastIndexOf(':');
        String host = colon < 0 ? hp : hp.substring(0, colon);
        String port = colon < 0 ? "" : hp.substring(colon);
        if (isIp(host)) {
            return addr;
        }
        try {
            java.net.InetAddress ia = java.net.InetAddress.getByName(host);
            return mx + "@" + ia.getHostAddress() + port;
        } catch (Exception e) {
            // Can't produce an IP — refuse to share a domain-based address.
            return null;
        }
    }

    private String hostOf(String addr) {
        int at = addr.lastIndexOf('@');
        if (at < 0) {
            return "";
        }
        String hp = addr.substring(at + 1);
        // Bracketed IPv6 literal: host is what's inside the brackets.
        if (hp.startsWith("[")) {
            int close = hp.indexOf(']');
            return close > 1 ? hp.substring(1, close) : hp;
        }
        int colon = hp.lastIndexOf(':');
        return colon < 0 ? hp : hp.substring(0, colon);
    }

    /** True for an IPv4 dotted-quad or an IPv6 literal (colon-bearing) host. */
    private boolean isIp(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return true; // IPv6 literal
        }
        return host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    // ---------------------------------------------------------------
    // Presence / pill labels
    // ---------------------------------------------------------------

    private String presenceText(Contact c) {
        long ls = c.lastSeen;
        if (ls <= 0) {
            return tr(R.string.l10n_native_contacts_offline);
        }
        long d = System.currentTimeMillis() - ls;
        if (d < ONLINE_MS) {
            return tr(R.string.l10n_presence_online);
        }
        return tr(R.string.l10n_presence_last_seen, Language.ago(d));
    }

    private String lastSeenText(Contact c) {
        if (c.lastSeen <= 0) {
            return tr(R.string.l10n_native_contacts_never);
        }
        return Language.ago(System.currentTimeMillis() - c.lastSeen);
    }

    private String ago(long d) { return Language.ago(d); }

    private String statusLabel(Contact c) {
        if (c.isKeets()) {
            // keets presence is per-message (direct vs mailbox), not polled
            return "KeetChat";
        }
        long ls = c.lastSeen;
        if (ls <= 0) {
            return tr(R.string.l10n_native_contacts_offline);
        }
        long d = System.currentTimeMillis() - ls;
        return d < ONLINE_MS ? tr(R.string.l10n_presence_online) : ago(d);
    }

    private String peerPill(Contact c) {
        if (c.isKeets()) {
            return "keets";
        }
        if (c.kind != null && c.kind.equalsIgnoreCase("core")) {
            return "core";
        }
        boolean hasChain = false;
        try {
            hasChain = c.topBlock != null && Long.parseLong(c.topBlock.trim()) > 0;
        } catch (NumberFormatException ignored) {
            hasChain = false;
        }
        if (hasChain) {
            return "core";
        }
        if (c.isClassic()) {
            return "classic";
        }
        return null;
    }

    private String softwareLabel(Contact c) {
        String pill = peerPill(c);
        if ("core".equals(pill)) {
            return "Minima Core";
        }
        if ("classic".equals(pill)) {
            return "Classic Maxima";
        }
        return "Parlons app";
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    private void buildUi() {
        // Identity card.
        LinearLayout id = card();
        LinearLayout idrow = new LinearLayout(mAct);
        idrow.setOrientation(LinearLayout.HORIZONTAL);
        idrow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ico = new ImageView(mAct);
        ico.setImageResource(R.drawable.ic_qr);
        // The disc is always dark, so the glyph is always white (not ux_on_accent,
        // which would invert to dark in dark mode and vanish).
        ico.setColorFilter(0xFFFFFFFF);
        ico.setBackgroundResource(R.drawable.coin_minima_bg);
        int ip = dp(11);
        ico.setPadding(ip, ip, ip, ip);
        LinearLayout.LayoutParams iconlp = new LinearLayout.LayoutParams(dp(46), dp(46));
        iconlp.rightMargin = dp(13);
        idrow.addView(ico, iconlp);
        LinearLayout idcol = new LinearLayout(mAct);
        idcol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(mAct);
        Language.setText(title, Language.msg(R.string.l10n_native_contacts_your_parlons));
        title.setTextSize(16);
        title.setTypeface(manrope(Typeface.BOLD));
        title.setTextColor(col(R.color.ux_text));
        mHostInfo = sub("…");
        idcol.addView(title);
        idcol.addView(mHostInfo);
        idrow.addView(idcol, new LinearLayout.LayoutParams(0, -2, 1f));
        id.addView(idrow);

        LinearLayout actions = new LinearLayout(mAct);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(-1, -2);
        acp.topMargin = dp(14);
        TextView share = primaryButton(Language.msg(R.string.l10n_native_contacts_share_my_address));
        TextView scan = ghostButton(Language.msg(R.string.l10n_native_contacts_scan));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 2f);
        l.rightMargin = dp(5);
        LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, -2, 1f);
        r.leftMargin = dp(5);
        actions.addView(share, l);
        actions.addView(scan, r);
        id.addView(actions, acp);
        share.setOnClickListener(v -> showMyAddressSheet());
        scan.setOnClickListener(v -> mAct.scanQr(this::onScanned));
        mBox.addView(id, marginBottom(dp(4)));

        // Search field.
        LinearLayout searchRow = new LinearLayout(mAct);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackgroundResource(R.drawable.field_pill);
        searchRow.setPadding(dp(13), dp(2), dp(13), dp(2));
        ImageView si = new ImageView(mAct);
        si.setImageResource(R.drawable.ic_search);
        si.setColorFilter(col(R.color.ux_subtext));
        LinearLayout.LayoutParams silp = new LinearLayout.LayoutParams(dp(18), dp(18));
        silp.rightMargin = dp(9);
        searchRow.addView(si, silp);
        mSearch = new EditText(mAct);
        Language.hint(mSearch, Language.msg(R.string.l10n_contacts_search));
        mSearch.setBackground(null);
        mSearch.setTextColor(col(R.color.ux_text));
        mSearch.setHintTextColor(col(R.color.ux_subtext));
        mSearch.setTextSize(14);
        mSearch.setSingleLine(true);
        mSearch.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable e) {
                mSearchQuery = e.toString();
                com.eurobuddha.maxima.core.ChatPort node = MaximaService.port();
                rebuildList(node == null ? new ArrayList<>() : node.contacts());
            }
        });
        searchRow.addView(mSearch, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, -2);
        srp.topMargin = dp(14);
        mBox.addView(searchRow, srp);

        // Section header: CONTACTS · N   + Add
        LinearLayout head = new LinearLayout(mAct);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(18), dp(4), dp(9));
        mCountLabel = new TextView(mAct);
        Language.setText(mCountLabel, Language.msg(R.string.l10n_nav_contacts));
        mCountLabel.setTextSize(10);
        mCountLabel.setTypeface(Typeface.DEFAULT_BOLD);
        mCountLabel.setLetterSpacing(0.15f);
        mCountLabel.setTextColor(col(R.color.ux_subtext));
        head.addView(mCountLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView add = new TextView(mAct);
        Language.setText(add, Language.msg(R.string.l10n_native_contacts_add_plus));
        add.setTextSize(12.5f);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setTextColor(col(R.color.ux_text));
        add.setPadding(dp(8), dp(4), dp(4), dp(4));
        add.setClickable(true);
        add.setBackground(ripple());
        add.setOnClickListener(v -> showAddSheet());
        head.addView(add);
        mBox.addView(head);

        // List card.
        LinearLayout listCard = card();
        mList = new LinearLayout(mAct);
        mList.setOrientation(LinearLayout.VERTICAL);
        listCard.addView(mList);
        mBox.addView(listCard, marginBottom(dp(12)));
    }

    // ---------------------------------------------------------------
    // Sheet + widget helpers
    // ---------------------------------------------------------------

    private BottomSheetDialog sheet(CharSequence titleText, View content) {
        LinearLayout root = new LinearLayout(mAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.sheet_bg);
        root.setPadding(dp(20), dp(8), dp(20), dp(24));

        View grab = new View(mAct);
        grab.setBackgroundResource(R.drawable.grab_handle);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(38), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(10);
        root.addView(grab, gp);

        TextView t = new TextView(mAct);
        Language.setText(t, titleText);
        t.setTextSize(17);
        t.setTypeface(manrope(Typeface.BOLD));
        t.setTextColor(col(R.color.ux_text));
        t.setPadding(0, 0, 0, dp(14));
        root.addView(t);

        ScrollView sv = new ScrollView(mAct);
        sv.addView(content);
        root.addView(sv);

        BottomSheetDialog d = new BottomSheetDialog(mAct);
        d.setContentView(root);
        View bs = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs != null) {
            bs.setBackgroundColor(Color.TRANSPARENT);
        }
        // Keyboard: a sheet with a field must RISE above it, not sit under it (the
        // "add a contact" / send / wallet-node sheets all suffered). ADJUST_RESIZE on the
        // dialog window shrinks the sheet to the space above the keyboard; EXPANDED keeps
        // the whole sheet (field AND its button) in that space instead of a half peek.
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        d.getBehavior().setSkipCollapsed(true);
        d.show();
        return d;
    }

    private LinearLayout kv(CharSequence key, CharSequence value) {
        LinearLayout row = new LinearLayout(mAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        TextView k = new TextView(mAct);
        Language.setText(k, Language.upper(key));
        k.setTextSize(10);
        k.setLetterSpacing(0.1f);
        k.setTextColor(col(R.color.ux_subtext));
        TextView v = new TextView(mAct);
        Language.setText(v, value);
        v.setTextSize(14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.END);
        v.setTextColor(col(R.color.ux_text));
        row.addView(k, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1.4f));
        return row;
    }

    private LinearLayout copyField(CharSequence labelText, String value, CharSequence toastMsg) {
        LinearLayout box = new LinearLayout(mAct);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.field_pill);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(12);
        box.setLayoutParams(bp);

        LinearLayout head = new LinearLayout(mAct);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(mAct);
        Language.setText(l, Language.upper(labelText));
        l.setTextSize(10);
        l.setLetterSpacing(0.12f);
        l.setTextColor(col(R.color.ux_subtext));
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView cp = new ImageView(mAct);
        cp.setImageResource(R.drawable.ic_copy);
        cp.setColorFilter(col(R.color.ux_subtext));
        head.addView(cp, new LinearLayout.LayoutParams(dp(15), dp(15)));
        box.addView(head);

        TextView v = new TextView(mAct);
        Language.setText(v, value);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(12);
        v.setPadding(0, dp(7), 0, 0);
        v.setTextColor(col(R.color.ux_text));
        v.setTextIsSelectable(true);
        box.addView(v);

        box.setOnClickListener(view -> {
            ((ClipboardManager) mAct.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("maxima", value));
            mAct.toast(toastMsg.toString());
        });
        return box;
    }

    private View divider() {
        View d = new View(mAct);
        d.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        d.setBackgroundColor(col(R.color.ux_divider));
        return d;
    }

    private android.graphics.drawable.Drawable ripple() {
        android.util.TypedValue tv = new android.util.TypedValue();
        mAct.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return androidx.core.content.ContextCompat.getDrawable(mAct, tv.resourceId);
    }

    private TextView primaryButton(CharSequence text) {
        TextView b = new TextView(mAct);
        Language.setText(b, text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);
        b.setTypeface(manrope(Typeface.BOLD));
        b.setBackgroundResource(R.drawable.btn_primary);
        b.setTextColor(col(R.color.ux_on_accent));
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView ghostButton(CharSequence text) {
        TextView b = new TextView(mAct);
        Language.setText(b, text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);
        b.setTypeface(manrope(Typeface.BOLD));
        b.setBackgroundResource(R.drawable.btn_ghost);
        b.setTextColor(col(R.color.ux_text));
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(mAct);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        return c;
    }

    private LinearLayout.LayoutParams marginBottom(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px;
        return lp;
    }

    private LinearLayout.LayoutParams marginTB(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px;
        lp.bottomMargin = px;
        return lp;
    }

    private TextView sub(CharSequence t) {
        TextView v = new TextView(mAct);
        Language.setText(v, t);
        v.setTextSize(12);
        v.setTextColor(col(R.color.ux_subtext));
        return v;
    }

    private Typeface manrope(int style) {
        try {
            Typeface base = androidx.core.content.res.ResourcesCompat.getFont(mAct, R.font.manrope);
            return Typeface.create(base, style);
        } catch (Exception e) {
            return Typeface.defaultFromStyle(style);
        }
    }

    @Override public void languageChanged() { mLastSignature = ""; render(); }

    private int col(int res) {
        return mAct.getResources().getColor(res, mAct.getTheme());
    }

    private int dp(float v) {
        return (int) (v * mAct.getResources().getDisplayMetrics().density);
    }
    private String tr(int resource, Object... arguments) { return Language.text(mAct, resource, arguments); }

}
