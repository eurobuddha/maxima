package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.List;

/** Balances tab: per-token aggregates with icon graphics and full token/coin data. */
public class BalancesView extends BaseView {

    private final LinearLayout container;
    private final android.widget.EditText search;

    /** Inflates the balances container, wires the search filter, renders the first listing. */
    public BalancesView(WalletActivity a) {
        super(a, R.layout.view_balances);
        container = find(R.id.balancesContainer);
        search = find(R.id.balSearch);
        root.setBackgroundColor(Design.bg());
        search.setBackgroundColor(Design.surface2());
        search.setTextColor(Design.text());
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { refresh(); }
        });
        refresh();
    }

    /** Rebuilds one rich card per token from the aggregated balances (search-filtered). */
    @Override
    public void refresh() {
        container.removeAllViews();
        List<TokenBalance> balances = act.balances();
        if (balances.isEmpty()) {
            TextView tv = new TextView(act);
            tv.setText("No balances yet.");
            tv.setTextColor(Design.dim());
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(40), 0, 0);
            container.addView(tv);
            return;
        }

        String q = search.getText().toString().trim().toLowerCase();
        java.util.Set<String> hidden = HiddenTokens.all(act);
        int shown = 0, hiddenCount = 0;
        for (TokenBalance b : balances) {
            if (!matches(b, q)) continue;
            if (hidden.contains(b.tokenid) && !showHidden) { hiddenCount++; continue; }
            View card = buildCard(b);
            if (hidden.contains(b.tokenid)) card.setAlpha(0.45f);
            container.addView(card);
            shown++;
        }
        if (shown == 0) {
            TextView tv = new TextView(act);
            tv.setText(q.isEmpty() ? "Nothing to show." : "Nothing matches “" + q + "”.");
            tv.setTextColor(Design.dim());
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(40), 0, 0);
            container.addView(tv);
        }
        if (hiddenCount > 0 || (showHidden && !hidden.isEmpty())) {
            TextView t = new TextView(act);
            t.setText(showHidden ? "hide hidden tokens" :
                    hiddenCount + (hiddenCount == 1 ? " hidden token · show" : " hidden tokens · show"));
            t.setTextColor(Design.dim());
            t.setTextSize(11f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(6));
            t.setOnClickListener(v -> { showHidden = !showHidden; refresh(); });
            container.addView(t);
        }
    }

    /** Session-only toggle for peeking at hidden tokens (dimmed cards). */
    private boolean showHidden = false;

    /** Search filter: name, ticker or tokenid substring (case-insensitive). */
    private boolean matches(TokenBalance b, String q) {
        if (q.isEmpty()) return true;
        if (b.name != null && b.name.toLowerCase().contains(q)) return true;
        if (b.meta != null && b.meta.ticker != null && b.meta.ticker.toLowerCase().contains(q)) return true;
        return b.tokenid != null && b.tokenid.toLowerCase().contains(q);
    }

    private final Runnable refreshTask = this::refresh;

    /** Coalesce the async web-validation callbacks (one per token) into a single re-render. */
    private void scheduleRefresh() {
        container.removeCallbacks(refreshTask);
        container.postDelayed(refreshTask, 120);
    }

    // ---------- one balance card, matching the utxoWallet dapp exactly (square, bordered, mono) ----------

    private static final int MP = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WC = LinearLayout.LayoutParams.WRAP_CONTENT;

    private View buildCard(TokenBalance b) {
        boolean nativeCoin = b.isMinima();

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));            // dapp: 12px V / 14px H
        card.setBackground(nativeCoin
                ? leftAccentBox(Design.bg(), Design.border(), Design.accent())
                : borderBox(Design.bg(), Design.border()));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MP, WC);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        // top row: [40dp icon] [symbol/name] [amount/count →]
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout slot = new FrameLayout(act);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
        slp.rightMargin = dp(12);
        slot.setLayoutParams(slp);
        if (nativeCoin) {
            slot.setBackground(borderBox(0xFFFFFFFF, Design.border()));         // official coin: white tile, black M
            ImageView g = new ImageView(act);
            g.setLayoutParams(new FrameLayout.LayoutParams(MP, MP));
            g.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int mp = dp(8); g.setPadding(mp, mp, mp, mp);
            g.setImageResource(R.drawable.minima_coin);                        // official Minima M (MediaKit shape), brand-black
            slot.addView(g);
            ImageView badge = new ImageView(act);                              // native coin is the official verified coin
            int bs = dp(15);
            badge.setLayoutParams(new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END));
            badge.setImageBitmap(Identicon.checkBadge(bs));
            slot.addView(badge);
        } else {
            slot.setBackground(borderBox(Design.surface(), Design.border()));
            ImageView icon = new ImageView(act);
            icon.setLayoutParams(new FrameLayout.LayoutParams(MP, MP));
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);          // object-fit: cover
            icon.setImageBitmap(Identicon.forToken(b.tokenid, dp(40)));  // deterministic base
            slot.addView(icon);
            ImageLoader.loadOver(act, b.meta.iconUrl, icon, null);   // updates this ImageView in place — no full re-render
        }

        // Web-validation checkmark: the token's webvalidate URL hosts its tokenid (domain-ownership proof).
        if (!nativeCoin && notEmpty(b.meta.webvalidate)) {
            WebValidate.ensure(act, b.tokenid, b.meta.webvalidate, this::scheduleRefresh);
            if (Boolean.TRUE.equals(WebValidate.status(b.tokenid))) {
                ImageView badge = new ImageView(act);
                int bs = dp(15);
                badge.setLayoutParams(new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END));
                badge.setImageBitmap(Identicon.checkBadge(bs));
                slot.addView(badge);
            }
        }
        top.addView(slot);

        LinearLayout ident = new LinearLayout(act);
        ident.setOrientation(LinearLayout.VERTICAL);
        String symbol = notEmpty(b.meta.ticker) ? b.meta.ticker : b.name;
        TextView sym = mono(symbol.toUpperCase(), 13f, Design.heading(), true);
        sym.setLetterSpacing(0.115f); sym.setMaxLines(1); sym.setEllipsize(TextUtils.TruncateAt.END);
        ident.addView(sym);
        String secondary = (notEmpty(b.name) && !b.name.equalsIgnoreCase(symbol)) ? b.name : null;
        if (b.isNft()) secondary = (secondary == null ? "NFT" : secondary + "  ·  NFT");
        if (secondary != null) {
            TextView nm = mono(secondary, 11f, b.isNft() ? Design.accent() : Design.dim(), false);
            nm.setMaxLines(1); nm.setEllipsize(TextUtils.TruncateAt.END); nm.setPadding(0, dp(2), 0, 0);
            ident.addView(nm);
        }
        top.addView(ident, new LinearLayout.LayoutParams(0, WC, 1f));

        LinearLayout amtCol = new LinearLayout(act);
        amtCol.setOrientation(LinearLayout.VERTICAL);
        amtCol.setGravity(Gravity.END);
        TextView amt = mono(Format.amount(b.sendable), 17f, Design.heading(), true);
        amt.setGravity(Gravity.END);
        amtCol.addView(amt);
        TextView cnt = mono((b.coins + (b.coins == 1 ? " coin" : " coins")).toUpperCase(), 10f, Design.dim(), false);
        cnt.setLetterSpacing(0.04f); cnt.setGravity(Gravity.END); cnt.setPadding(0, dp(3), 0, 0);
        amtCol.addView(cnt);
        top.addView(amtCol, new LinearLayout.LayoutParams(WC, WC));

        card.addView(top);

        // Full-granularity split — sendable / confirmed / unconfirmed always visible
        // (the family convention), plus locked when a contract is holding part of the balance.
        card.addView(divider());
        card.addView(splitRow("SENDABLE", Format.amount(b.sendable), Design.accent()));
        card.addView(splitRow("CONFIRMED", Format.amount(b.confirmed), Design.dim()));
        card.addView(splitRow("UNCONFIRMED", Format.amount(b.unconfirmed), Design.dim()));
        String locked = lockedAmount(b);
        if (positive(locked)) card.addView(splitRow("LOCKED", Format.amount(locked), Design.amber()));

        card.setOnClickListener(v -> showTokenDetail(b));
        return card;
    }

    private TextView mono(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextColor(color); t.setTextSize(sp);
        t.setTypeface(Design.typeface(), bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private View divider() {
        View v = new View(act);
        v.setBackgroundColor(Design.border());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, Math.max(1, dp(1)));
        lp.topMargin = dp(10); lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private View splitRow(String label, String value, int labelColor) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(2), 0, dp(2));
        TextView l = mono(label, 10f, labelColor, true); l.setLetterSpacing(0.08f);
        TextView val = mono(value, 13f, Design.heading(), true); val.setGravity(Gravity.END);
        r.addView(l, new LinearLayout.LayoutParams(0, WC, 1f));
        r.addView(val, new LinearLayout.LayoutParams(0, WC, 1f));
        return r;
    }

    /** Square, filled, 1px-bordered box (radius 0) — the dapp's card/slot look. */
    private GradientDrawable borderBox(int bg, int border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setStroke(Math.max(1, dp(1)), border);
        return d;
    }

    /** Same, plus a 3dp accent left rule (the .bal-card.native marker for the Minima coin). */
    private Drawable leftAccentBox(int bg, int border, int accent) {
        LayerDrawable ld = new LayerDrawable(new Drawable[]{ borderBox(bg, border), new ColorDrawable(accent) });
        ld.setLayerGravity(1, Gravity.LEFT);
        ld.setLayerWidth(1, dp(3));
        return ld;
    }

    /** The real Minima mark, rasterised from the bundled official SVG (native Minima carries no on-chain icon). */
    private Bitmap renderMinimaLogo(int px) {
        try {
            java.io.InputStream is = act.getResources().openRawResource(R.raw.minima_icon);
            com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromInputStream(is);
            is.close();
            float dw = svg.getDocumentWidth(), dh = svg.getDocumentHeight();
            int w = px, h = px;
            if (dw > 0 && dh > 0) {
                if (dw >= dh) { w = px; h = Math.max(1, Math.round(px * dh / dw)); }
                else { h = px; w = Math.max(1, Math.round(px * dw / dh)); }
            }
            svg.setDocumentWidth(w); svg.setDocumentHeight(h);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            svg.renderToCanvas(new android.graphics.Canvas(bmp));
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Full-detail dialog for one token: large icon (tap → full-res), all metadata, and a Receive action. */
    private void showTokenDetail(TokenBalance b) {
        final Sheet sheetUi = Sheet.create(act, b.name + (b.isNft() ? "  ·  NFT" : ""));
        LinearLayout box = sheetUi.body();

        ImageView big = new ImageView(act);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(128), dp(128));
        ip.gravity = Gravity.CENTER_HORIZONTAL; ip.bottomMargin = dp(6); big.setLayoutParams(ip);
        if (b.isMinima()) { big.setBackgroundColor(0xFFFFFFFF); int mp = dp(20); big.setPadding(mp, mp, mp, mp); big.setImageResource(R.drawable.minima_coin); big.setScaleType(ImageView.ScaleType.FIT_CENTER); }
        else { big.setImageBitmap(Identicon.forToken(b.tokenid, dp(128))); ImageLoader.loadOver(act, b.meta.iconUrl, big, null); }
        box.addView(big);
        if (b.hasIcon()) {
            big.setOnClickListener(v -> showImageFull(b.meta.iconUrl));
            TextView hint = new TextView(act);
            hint.setText(b.isNft() ? "Tap image for full resolution" : "Tap image to enlarge");
            hint.setTextColor(Design.dim()); hint.setTextSize(11f); hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, 0, 0, dp(8)); box.addView(hint);
        }

        // Full-granularity balance triple — always, per the family convention, and always at FULL
        // precision: the cards are trimmed for legibility, this sheet is where the exact figure is.
        // Tap any row to copy.
        addKv(box, "Sendable", Util.tidyAmount(b.sendable));
        addKv(box, "Confirmed", Util.tidyAmount(b.confirmed));
        addKv(box, "Unconfirmed", Util.tidyAmount(b.unconfirmed));
        String locked = lockedAmount(b);
        if (positive(locked)) addKv(box, "Locked in contracts", Util.tidyAmount(locked));
        addKv(box, "Coins", String.valueOf(b.coins));
        String supply = (b.isMinima() && !act.circulatingSupply().isEmpty())
                ? wholeNumber(act.circulatingSupply()) : Util.tidyAmount(b.total);
        addKv(box, "Supply", supply);
        if (b.meta.ticker != null && !b.meta.ticker.isEmpty()) addKv(box, "Ticker", b.meta.ticker);
        if (b.meta.decimals != null && !b.meta.decimals.isEmpty()) addKv(box, "Decimals", b.meta.decimals);
        if (notEmpty(b.meta.owner)) addKv(box, "Owner", b.meta.owner);

        box.addView(sectionLabel("Token ID  ·  tap to copy"));
        TextView idv = new TextView(act);
        idv.setText(b.tokenid); idv.setTextColor(Design.text()); idv.setTextSize(12f);
        idv.setTypeface(android.graphics.Typeface.MONOSPACE);
        idv.setOnClickListener(v -> CoinDetailDialog.copy(act, "Token ID", b.tokenid));
        box.addView(idv);

        if (notEmpty(b.meta.description)) {
            box.addView(sectionLabel("Description"));
            TextView d = new TextView(act);
            d.setText(b.meta.description); d.setTextColor(Design.dim()); d.setTextSize(13f);
            box.addView(d);
        }

        // Every URL the token carries, each openable in the external browser.
        if (notEmpty(b.meta.rawUrl)) box.addView(linkRow("Token URL", b.meta.rawUrl));
        if (notEmpty(b.meta.externalUrl) && !b.meta.externalUrl.equals(b.meta.rawUrl)) {
            box.addView(linkRow("Website", b.meta.externalUrl));
        }
        if (notEmpty(b.meta.webvalidate)) box.addView(linkRow("Web validation", b.meta.webvalidate));

        // Contract + coin explorer load into these as the node replies arrive.
        final LinearLayout contractBox = new LinearLayout(act);
        contractBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(contractBox);
        final LinearLayout coinsBox = new LinearLayout(act);
        coinsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(coinsBox);

        // Held by the async contract fetch so a bury action can close this sheet first.
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        if (!b.isMinima()) loadContract(b, contractBox, sheet);
        loadCoins(b, coinsBox);

        // Hide / unhide (old-wallet behaviour) — never for the native coin.
        if (!b.isMinima()) {
            final boolean hidden = HiddenTokens.isHidden(act, b.tokenid);
            TextView hide = new TextView(act);
            hide.setText(hidden ? "Unhide this token" : "Hide this token");
            hide.setTextColor(hidden ? Design.accent() : Design.dim());
            hide.setTextSize(12f);
            hide.setPadding(0, dp(12), 0, dp(4));
            hide.setOnClickListener(v -> {
                if (hidden) {
                    HiddenTokens.unhide(act, b.tokenid);
                    refresh();
                } else {
                    Sheet.create(act, "Hide this token?")
                .subtitle("“" + b.name + "” disappears from your balances list. "
                                    + "You can unhide it from Settings or the hidden-tokens row.")
                .action("Cancel", Sheet.Style.SECONDARY, null)
                .action("Hide", Sheet.Style.PRIMARY, () -> { HiddenTokens.hide(act, b.tokenid); refresh(); })
                .show();
                }
            });
            box.addView(hide);
        }

        sheetUi.action("Close", Sheet.Style.SECONDARY, null);
        sheetUi.action("Receive", Sheet.Style.SECONDARY, () -> act.goToTab(WalletActivity.TAB_RECEIVE));
        if (positive(b.sendable)) {
            sheetUi.action("Send", Sheet.Style.PRIMARY, () -> act.sendToken(b.tokenid));
        }
        sheetUi.show();
        sheet[0] = sheetUi.dialog();
    }

    /** Fetch the token record (script, total, creation) and add a collapsible contract section. */
    private void loadContract(final TokenBalance b, final LinearLayout into,
                              final android.app.Dialog[] sheet) {
        if (!Util.isValidHexId(b.tokenid)) return;   // chain-supplied id — never interpolate unvetted
        act.node().cmd("tokens tokenid:" + b.tokenid, new NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject json) {
                Object resp = json.opt("response");
                org.json.JSONObject tok = null;
                if (resp instanceof org.json.JSONObject) tok = (org.json.JSONObject) resp;
                else if (resp instanceof org.json.JSONArray && ((org.json.JSONArray) resp).length() > 0) {
                    tok = ((org.json.JSONArray) resp).optJSONObject(0);
                }
                if (tok == null) return;
                final String script = tok.optString("script", "");
                if (script.isEmpty()) return;
                into.addView(sectionLabel("Token script"));
                boolean plain = "RETURN TRUE".equalsIgnoreCase(script.trim());
                boolean stateNft = StateNft.isStateNftScript(script);
                TextView tag = new TextView(act);
                tag.setText(plain ? "Standard token (RETURN TRUE)"
                        : stateNft ? "STATE NFT — locked-edition contract" : "Custom contract");
                tag.setTextColor(stateNft ? Design.accent() : Design.dim());
                tag.setTextSize(12f);
                into.addView(tag);
                if (!plain) {
                    final TextView code = new TextView(act);
                    code.setText(script);
                    code.setTextColor(Design.dim());
                    code.setTextSize(10f);
                    code.setTypeface(android.graphics.Typeface.MONOSPACE);
                    code.setBackgroundColor(Design.surface());
                    code.setPadding(dp(8), dp(6), dp(8), dp(6));
                    code.setVisibility(View.GONE);
                    TextView toggle = new TextView(act);
                    toggle.setText("View contract source ▸");
                    toggle.setTextColor(Design.accent());
                    toggle.setTextSize(12f);
                    toggle.setPadding(0, dp(4), 0, dp(4));
                    toggle.setOnClickListener(v -> {
                        boolean open = code.getVisibility() == View.VISIBLE;
                        code.setVisibility(open ? View.GONE : View.VISIBLE);
                        toggle.setText(open ? "View contract source ▸" : "Hide contract source ▾");
                    });
                    code.setOnClickListener(v -> CoinDetailDialog.copy(act, "Token script", script));
                    into.addView(toggle);
                    into.addView(code);
                }

                // A locked edition can only be destroyed, never edited — and this sheet is where
                // you reach a collection you hold, whether or not this wallet minted it (a mint
                // row may not exist at all, e.g. for a duplicate the engine created).
                if (stateNft) {
                    StateNft.Meta cm = StateNft.parseMeta(b.tokenid, tok);
                    final String collection = cm != null && cm.name != null && !cm.name.isEmpty()
                            ? cm.name : b.name;
                    final String creatorPk = StateNft.creatorPk(script);
                    TextView sendAll = new TextView(act);
                    sendAll.setText("→  Send this entire collection");
                    sendAll.setTextColor(Design.accent());
                    sendAll.setTextSize(13f);
                    sendAll.setTypeface(Design.typefaceBold());
                    sendAll.setPadding(0, dp(14), 0, dp(4));
                    sendAll.setOnClickListener(v -> {
                        if (sheet != null && sheet[0] != null) sheet[0].dismiss();
                        StateNftActions.transferCollectionDialog(act, b.tokenid, collection,
                                () -> act.reload());
                    });
                    into.addView(sendAll);

                    TextView buryAll = new TextView(act);
                    buryAll.setText("✝  Bury this entire collection");
                    buryAll.setTextColor(Design.red());
                    buryAll.setTextSize(13f);
                    buryAll.setTypeface(Design.typefaceBold());
                    buryAll.setPadding(0, dp(6), 0, dp(6));
                    buryAll.setOnClickListener(v -> {
                        if (sheet != null && sheet[0] != null) sheet[0].dismiss();
                        StateNftActions.buryCollectionDialog(act, b.tokenid, collection, creatorPk,
                                () -> act.reload());
                    });
                    into.addView(buryAll);
                }
            }
            @Override public void onError(String message) { /* detail stays without the script */ }
        });
    }

    /** Coin explorer: every UTXO of this token, newest first, tap a row for the full coin modal. */
    private static final int MAX_COIN_ROWS = 100;

    private void loadCoins(TokenBalance b, final LinearLayout into) {
        if (!Util.isValidHexId(b.tokenid)) return;
        act.node().cmd("coins relevant:true tokenid:" + b.tokenid, new NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject json) {
                org.json.JSONArray arr = json.optJSONArray("response");
                if (arr == null || arr.length() == 0) return;
                // Filter BEFORE counting, so the header and the "showing N of M" line describe the
                // coins actually rendered rather than the raw reply.
                final java.util.List<Coin> live = new java.util.ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject cj = arr.optJSONObject(i);
                    if (cj == null) continue;
                    Coin c = Coin.from(cj);
                    // Same ingestion rule as the main coin list: a coin whose command-bound fields
                    // aren't well formed never reaches the UI or a transaction.
                    if (!c.isWellFormed()) continue;
                    if (StateNft.isBuried(c)) continue;   // graveyard — not part of the wallet
                    live.add(c);
                }
                if (live.isEmpty()) return;
                into.addView(sectionLabel("Coins (" + live.size() + ")  ·  tap for full detail"));
                int n = Math.min(live.size(), MAX_COIN_ROWS);
                for (int i = 0; i < n; i++) {
                    final Coin c = live.get(i);
                    // sendable flag comes from the main wallet scan when we track this coin
                    for (Coin known : act.coins()) {
                        if (known.coinid.equals(c.coinid)) { c.sendable = known.sendable; break; }
                    }
                    into.addView(coinRow(c));
                }
                if (live.size() > n) {
                    TextView more = new TextView(act);
                    more.setText("Showing " + n + " of " + live.size() + " coins.");
                    more.setTextColor(Design.dim()); more.setTextSize(11f);
                    more.setPadding(0, dp(4), 0, 0);
                    into.addView(more);
                }
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_TOO_LONG.equals(message)) {
                    TextView t = new TextView(act);
                    t.setText("Too many coins to list here — use the Coins tab.");
                    t.setTextColor(Design.dim()); t.setTextSize(11f);
                    into.addView(t);
                }
            }
        });
    }

    /** One coin-explorer row: amount, short coinid, age, and state/lock chips. */
    private View coinRow(final Coin c) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));

        LinearLayout left = new LinearLayout(act);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView amt = mono(Format.amount(c.amount), 13f, Design.text(), true);
        left.addView(amt);
        long age = c.ageBlocks(act.chainBlock());
        String sub = Util.shorten(c.coinid) + (age >= 0 ? "  ·  " + age + " blk" : "");
        TextView id = mono(sub, 10f, Design.dim(), false);
        left.addView(id);
        r.addView(left, new LinearLayout.LayoutParams(0, WC, 1f));

        StringBuilder chips = new StringBuilder();
        if (!c.state.isEmpty()) chips.append("STATE ").append(c.state.size());
        if (!c.sendable) chips.append(chips.length() > 0 ? " · " : "").append("LOCKED");
        if (chips.length() > 0) {
            TextView chip = mono(chips.toString(), 9f, c.sendable ? Design.amber() : Design.dim(), true);
            chip.setLetterSpacing(0.06f);
            r.addView(chip);
        }
        r.setOnClickListener(v -> CoinDetailDialog.show(act, c));
        return r;
    }

    /** Full-screen, full-resolution image (for NFT art). Tap to dismiss. */
    private void showImageFull(String url) {
        ImageView iv = new ImageView(act);
        iv.setAdjustViewBounds(true);
        iv.setBackgroundColor(0xFF000000);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageLoader.loadFull(act, url, iv, R.drawable.ic_coin_placeholder);
        final android.app.Dialog dlg = new android.app.Dialog(act);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(iv);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
        }
        iv.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    /** Label/value row; tapping it copies the value (the old wallet's copy-everywhere behaviour). */
    private void addKv(LinearLayout box, final String label, final String value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(4), 0, dp(4));
        TextView l = new TextView(act); l.setText(label); l.setTextColor(Design.dim()); l.setTextSize(13f);
        TextView v = new TextView(act); v.setText(value); v.setTextColor(Design.text()); v.setTextSize(13f); v.setGravity(Gravity.END);
        r.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        r.setOnClickListener(x -> CoinDetailDialog.copy(act, label, value));
        box.addView(r);
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(act);
        t.setText(s); t.setAllCaps(true); t.setTextColor(Design.dim()); t.setTextSize(11f);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    /** Label + the ACTUAL destination + open action. Chain-supplied URLs: http(s) only, shown
     *  in full so a phishing target isn't hidden behind "open", and confirmed before leaving. */
    private LinearLayout linkRow(String label, final String url) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.VERTICAL); r.setPadding(0, dp(8), 0, dp(8));
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = new TextView(act); l.setText(label); l.setTextColor(Design.dim()); l.setTextSize(13f);
        TextView v = new TextView(act);
        boolean web = Util.isWebUrl(url);
        v.setText(web ? "↗ open" : "blocked"); v.setTextColor(web ? Design.accent() : Design.red());
        v.setTextSize(13f); v.setGravity(Gravity.END);
        top.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(top);
        TextView dest = new TextView(act);
        dest.setText(url); dest.setTextColor(Design.dim2()); dest.setTextSize(10f);
        dest.setTypeface(android.graphics.Typeface.MONOSPACE);
        dest.setMaxLines(2); dest.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        r.addView(dest);
        r.setOnClickListener(x -> Util.openWebUrl(act, url));
        return r;
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    /** True if the amount string parses to a strictly positive number; false on null/garbage. */
    private boolean positive(String amt) {
        try { return new BigDecimal(amt).signum() > 0; } catch (Exception e) { return false; }
    }

    /** Whole-number format with thousands separators and no decimals (e.g. 999,978,181). */
    private static String wholeNumber(String amt) {
        try {
            java.math.BigInteger bi = new BigDecimal(amt).toBigInteger();
            return java.text.NumberFormat.getInstance(java.util.Locale.US).format(bi);
        } catch (Exception e) { return amt; }
    }

    /** Contract-locked / watch-only balance = max(0, confirmed − sendable); "0" on parse failure. */
    private static String lockedAmount(TokenBalance b) {
        try {
            BigDecimal l = new BigDecimal(b.confirmed).subtract(new BigDecimal(b.sendable));
            return l.signum() > 0 ? l.stripTrailingZeros().toPlainString() : "0";
        } catch (Exception e) { return "0"; }
    }

    /** Converts density-independent pixels to raw pixels for this device. */
    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }
}
