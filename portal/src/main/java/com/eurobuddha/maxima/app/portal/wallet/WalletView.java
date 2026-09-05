package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.PopupMenu;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wallet tab: every wallet address (with coins first, empties below) as a collapsible card with a
 * COPY button; UTXOs underneath with single-tokenid multi-select and tap-to-copy coinids. A
 * "Tools ▾" dropdown on the selection bar holds Split / Consolidate / Distribute / Untrack.
 */
public class WalletView extends BaseView {

    private final LinearLayout container;
    private final TextView selCount, selTotal, statusView;
    private final Button toolsBtn, clearBtn, sendBtn;

    private final WalletTools tools;
    private final Set<String> collapsed = new HashSet<>();   // address hexes collapsed by the user

    /** Wires up the selection bar, Tools dropdown, and renders the first address listing. */
    public WalletView(WalletActivity a) {
        super(a, R.layout.view_wallet);
        container = find(R.id.walletContainer);
        selCount = find(R.id.selCount);
        selTotal = find(R.id.selTotal);
        statusView = find(R.id.walletStatus);
        toolsBtn = find(R.id.toolsBtn);
        clearBtn = find(R.id.clearBtn);
        sendBtn = find(R.id.sendBtn);

        tools = new WalletTools(act, this::setStatus);

        clearBtn.setOnClickListener(v -> act.clearSelection());
        sendBtn.setOnClickListener(v -> act.goToTab(WalletActivity.TAB_SEND));
        toolsBtn.setOnClickListener(this::showToolsMenu);
        applyDesign();
        refresh();
    }

    /** Paints the view with the active theme (so Dark/Light toggle takes effect). */
    private void applyDesign() {
        android.view.ViewGroup rootVg = (android.view.ViewGroup) root;
        rootVg.setBackgroundColor(Design.bg());
        rootVg.getChildAt(0).setBackgroundColor(Design.surface());   // selection bar
        selCount.setTextColor(Design.text());
        selTotal.setTextColor(Design.accent());
        sendBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        sendBtn.setTextColor(Design.onAccent());
        toolsBtn.setTextColor(Design.accent());
        clearBtn.setTextColor(Design.accent());
    }

    /** Shows a one-line status message under the selection bar; green on ok, red on failure. */
    private void setStatus(String msg, boolean ok) {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(msg);
        statusView.setTextColor(ok ? Design.success() : Design.red());
    }

    /** The "Tools ▾" dropdown: Split / Consolidate / Distribute / Untrack on the current selection. */
    private void showToolsMenu(View anchor) {
        PopupMenu menu = new PopupMenu(act, anchor);
        menu.getMenu().add(0, 1, 0, "Split selected…");
        menu.getMenu().add(0, 2, 1, "Consolidate");
        menu.getMenu().add(0, 3, 2, "Distribute selected…");
        menu.getMenu().add(0, 4, 3, "Untrack selected");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: tools.showSplit(); return true;
                case 2: tools.showConsolidate(); return true;
                case 3: tools.showDistribute(); return true;
                case 4: tools.untrackSelected(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    /** Rebuilds the selection summary and the full address-grouped coin listing. */
    @Override
    public void refresh() {
        List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) {
            selCount.setText("Pick coins to send");
            selTotal.setText("");
            sendBtn.setEnabled(false);
            clearBtn.setEnabled(false);
        } else {
            selCount.setText(sel.size() + (sel.size() == 1 ? " coin selected" : " coins selected"));
            selTotal.setText(act.selectedTotalString() + " " + sel.get(0).tokenName);
            sendBtn.setEnabled(true);
            clearBtn.setEnabled(true);
        }

        // Running multi-batch Distribute progress takes over the status line while active.
        DistributeManager dm = act.distribute();
        if (dm != null && dm.isActive()) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(dm.statusLine());
            statusView.setTextColor(Design.accent());
        }

        container.removeAllViews();

        // Coins grouped by their (hex) address.
        LinkedHashMap<String, List<Coin>> byAddr = new LinkedHashMap<>();
        for (Coin c : act.coins()) {
            List<Coin> l = byAddr.get(c.address);
            if (l == null) { l = new ArrayList<>(); byAddr.put(c.address, l); }
            l.add(c);
        }

        // hex -> miniaddress lookup across coins and my wallet addresses.
        Map<String, String> miniOf = new HashMap<>();
        for (Coin c : act.coins()) if (!miniOf.containsKey(c.address)) miniOf.put(c.address, c.miniaddress);
        for (String[] a : act.myAddresses()) if (!miniOf.containsKey(a[0])) miniOf.put(a[0], a[1]);

        // Our own addresses (the wallet's default scripts). Anything with coins that ISN'T one of these
        // is a contract address — funds locked in a script (casino, vesting, …), not a wallet of ours.
        Set<String> ownHex = new HashSet<>();
        for (String[] a : act.myAddresses()) ownHex.add(a[0]);

        // Total Minima (0x00) held per address — used to sort funded addresses by value, like the original.
        Map<String, BigDecimal> minimaTotal = new HashMap<>();
        for (Map.Entry<String, List<Coin>> en : byAddr.entrySet()) {
            BigDecimal t = BigDecimal.ZERO;
            for (Coin c : en.getValue()) {
                if (Util.isMinima(c.tokenid)) { try { t = t.add(new BigDecimal(c.amount)); } catch (Exception ignored) {} }
            }
            minimaTotal.put(en.getKey(), t);
        }

        // Split the funded addresses into ours vs contracts, each sorted by Minima desc.
        List<String> ownCoined = new ArrayList<>(), contractCoined = new ArrayList<>();
        for (String hex : byAddr.keySet()) (ownHex.contains(hex) ? ownCoined : contractCoined).add(hex);
        Comparator<String> byMinimaDesc = (x, y) ->
                minimaTotal.getOrDefault(y, BigDecimal.ZERO).compareTo(minimaTotal.getOrDefault(x, BigDecimal.ZERO));
        Collections.sort(ownCoined, byMinimaDesc);
        Collections.sort(contractCoined, byMinimaDesc);

        // Order (money first): our funded addresses, THEN contracts, THEN our empty/unused addresses.
        LinkedHashMap<String, String> order = new LinkedHashMap<>(); // hex -> mini
        for (String hex : ownCoined) order.put(hex, miniOf.get(hex));
        for (String hex : contractCoined) order.put(hex, miniOf.get(hex));
        for (String[] a : act.myAddresses()) if (!order.containsKey(a[0])) order.put(a[0], a[1]);

        if (order.isEmpty()) {
            addNote("No addresses yet. Make sure the wallet is enabled in Minima Core.");
            return;
        }

        for (Map.Entry<String, String> e : order.entrySet()) {
            String hex = e.getKey();
            String mini = (e.getValue() != null && !e.getValue().isEmpty()) ? e.getValue() : hex;
            List<Coin> addrCoins = byAddr.get(hex);
            int count = addrCoins == null ? 0 : addrCoins.size();
            boolean isCollapsed = collapsed.contains(hex);
            boolean isContract = !ownHex.contains(hex);
            container.addView(buildCard(hex, mini, addrCoins, count, isCollapsed, isContract,
                    minimaTotal.getOrDefault(hex, BigDecimal.ZERO)));
        }
    }

    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;

    /** One address as a dapp-style .card: 1px bordered box (radius 0), surface header strip, meta row, coin rows.
     *  Contract addresses (funds in a script, not ours) get the 3px amber left rule + CONTRACT badge. */
    private View buildCard(String hex, String mini, List<Coin> addrCoins, int count,
                           boolean isCollapsed, boolean isContract, BigDecimal minimaAmt) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH, WRAP);
        clp.bottomMargin = dp(6);
        card.setLayoutParams(clp);
        card.setBackground(cardBg(isContract));

        card.addView(buildAddrRow(hex, mini, isCollapsed));
        if (isCollapsed) return card;

        card.addView(buildMetaRow(minimaAmt, count, isContract));

        if (count == 0) {
            TextView empty = new TextView(act);
            empty.setText("empty");
            empty.setTypeface(Design.typeface());
            empty.setAllCaps(true);
            empty.setTextSize(10f);
            empty.setTextColor(Design.dim2());
            empty.setPadding(dp(8), dp(6), dp(8), dp(6));
            card.addView(empty);
            return card;
        }

        // Minima (0x00) first, then tokens; confirmed before pending, amount descending — like the dapp.
        List<Coin> sorted = new ArrayList<>(addrCoins);
        Collections.sort(sorted, (a, b) -> {
            boolean am = Util.isMinima(a.tokenid), bm = Util.isMinima(b.tokenid);
            if (am != bm) return am ? -1 : 1;
            if (a.confirmed != b.confirmed) return a.confirmed ? -1 : 1;
            try { return new BigDecimal(b.amount).compareTo(new BigDecimal(a.amount)); } catch (Exception e) { return 0; }
        });
        for (int i = 0; i < sorted.size(); i++) card.addView(buildCoinRow(sorted.get(i), i == sorted.size() - 1));
        return card;
    }

    /** .card-addr-row: surface strip, bottom border, caret + address + COPY; tap toggles collapse. */
    private View buildAddrRow(String hex, String mini, boolean isCollapsed) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(bottomBorder(Design.surface(), Design.border(), dp(1)));
        row.setPadding(dp(8), dp(5), dp(8), dp(5));

        TextView caret = new TextView(act);
        caret.setText(isCollapsed ? "▶" : "▼");
        caret.setTextSize(8f);
        caret.setTextColor(Design.dim());
        caret.setPadding(0, 0, dp(8), 0);
        row.addView(caret);

        TextView addr = new TextView(act);
        addr.setText(Util.shorten(mini));
        addr.setTypeface(Design.typeface());
        addr.setTextSize(11f);
        addr.setTextColor(Design.heading());
        addr.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(addr);

        TextView copy = new TextView(act);
        copy.setText("COPY");
        copy.setTypeface(Design.typeface());
        copy.setTextSize(9f);
        copy.setTextColor(Design.dim());
        copy.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(Design.bg());
        cb.setStroke(dp(1), Design.border());
        copy.setBackground(cb);
        copy.setOnClickListener(v -> copyToClipboard("Address", mini));
        row.addView(copy);

        row.setOnClickListener(v -> {
            if (isCollapsed) collapsed.remove(hex); else collapsed.add(hex);
            refresh();
        });
        return row;
    }

    /** .card-meta-row: total MINIMA · coin count, with a CONTRACT badge pushed right for script addresses. */
    private View buildMetaRow(BigDecimal minimaAmt, int count, boolean isContract) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(bottomBorder(Design.bg(), Design.border2(), dp(1)));
        row.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView meta = new TextView(act);
        meta.setText(Util.tidyAmount(minimaAmt.toPlainString()) + " MINIMA  ·  "
                + count + (count == 1 ? " coin" : " coins"));
        meta.setTypeface(Design.typeface());
        meta.setAllCaps(true);
        meta.setTextSize(10f);
        meta.setTextColor(Design.dim());
        meta.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(meta);

        if (isContract) {
            TextView badge = new TextView(act);
            badge.setText("CONTRACT");
            badge.setTypeface(Design.typeface(), Typeface.BOLD);
            badge.setTextSize(8.5f);
            badge.setTextColor(Design.amber());
            badge.setPadding(dp(6), dp(1), dp(6), dp(1));
            GradientDrawable bd = new GradientDrawable();
            bd.setColor((Design.amber() & 0x00FFFFFF) | 0x22000000);   // faint amber fill
            bd.setStroke(dp(1), Design.amber());
            badge.setBackground(bd);
            row.addView(badge);
        }
        return row;
    }

    /** .card background: 1px near-black border, radius 0; contract adds a 3px amber left rule. */
    private Drawable cardBg(boolean contract) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Design.bg());
        g.setStroke(dp(1), Design.border());
        if (!contract) return g;
        LayerDrawable ld = new LayerDrawable(new Drawable[]{ g, new ColorDrawable(Design.amber()) });
        ld.setLayerGravity(1, Gravity.LEFT);
        ld.setLayerWidth(1, dp(3));
        return ld;
    }

    /** A solid fill with only a bottom rule of width w (the dapp's header/row dividers). */
    private Drawable bottomBorder(int bg, int line, int w) {
        LayerDrawable ld = new LayerDrawable(new Drawable[]{ new ColorDrawable(line), new ColorDrawable(bg) });
        ld.setLayerInset(1, 0, 0, 0, w);
        return ld;
    }

    /** .utxo-row: brutalist square checkbox + bold mono amount (right) + token tag + status; bottom divider
     *  except on the last row; selected → accent-soft; unconfirmed/watch dimmed and not selectable. */
    private View buildCoinRow(Coin c, boolean last) {
        boolean usable = c.confirmed && c.sendable;      // only spendable coins toggle (keeps sends valid)
        boolean selected = act.isSelected(c.coinid);
        int bg = selected ? Design.accentSoft() : Design.bg();

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(24));
        row.setPadding(dp(8), dp(4), dp(8), dp(4));
        row.setBackground(last ? new ColorDrawable(bg) : bottomBorder(bg, Design.border2(), dp(1)));
        if (!usable) row.setAlpha(0.36f);

        ImageView box = new ImageView(act);
        LinearLayout.LayoutParams bxlp = new LinearLayout.LayoutParams(dp(14), dp(14));
        bxlp.rightMargin = dp(8);
        box.setLayoutParams(bxlp);
        box.setImageBitmap(Identicon.squareCheck(dp(14), selected, Design.bg(), Design.border(), Design.accent()));
        row.addView(box);

        TextView amt = new TextView(act);
        amt.setText(Util.tidyAmount(c.amount));
        amt.setTypeface(Design.typeface(), Typeface.BOLD);
        amt.setTextSize(12f);
        amt.setTextColor(Design.heading());
        amt.setGravity(Gravity.END);
        amt.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(amt);

        TextView tok = new TextView(act);
        tok.setText(c.tokenName == null ? "" : c.tokenName.toUpperCase());
        tok.setTypeface(Design.typeface(), Typeface.BOLD);
        tok.setTextSize(9f);
        tok.setTextColor(Design.dim());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(WRAP, WRAP);
        tlp.leftMargin = dp(8);
        tok.setLayoutParams(tlp);
        row.addView(tok);

        TextView st = new TextView(act);
        st.setText(!c.confirmed ? "PENDING" : (c.sendable ? "·" : "WATCH"));
        st.setTypeface(Design.typeface());
        st.setTextSize(9f);
        st.setTextColor(!c.confirmed ? Design.amber() : Design.dim2());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(WRAP, WRAP);
        slp.leftMargin = dp(8);
        st.setLayoutParams(slp);
        row.addView(st);

        row.setOnClickListener(v -> { if (usable) act.toggleCoin(c); });
        // Long-press: the full-granularity coin modal (every field + state vars, tap-to-copy).
        row.setOnLongClickListener(v -> { CoinDetailDialog.show(act, c); return true; });
        return row;
    }

    /** Copies text to the clipboard and toasts confirmation; no-op on empty. */
    private void copyToClipboard(String label, String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(act, label + " copied", Toast.LENGTH_SHORT).show();
    }

    /** Adds a centered dim placeholder note (e.g. the "no addresses yet" message). */
    private void addNote(String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextColor(Design.dim());
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(40), 0, 0);
        container.addView(tv);
    }

    /** Converts density-independent pixels to raw pixels for this device. */
    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }
}
