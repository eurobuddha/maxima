package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Gallery tab — the NFT-first view of the wallet: a two-column grid of every NFT you hold.
 *
 *  - Regular NFTs (decimals-0 tokens) are one card per token.
 *  - StateNFT collections collapse to ONE card showing the collection icon and how many of the
 *    edition you hold. Tapping it drills into that collection, where the grid becomes one card per
 *    owned item with its sealed index and its own image (embedded state art beats the URL base).
 *    A 60-item collection used to bury every other NFT you owned under 60 near-identical tiles.
 *  - Collected / Favourites tabs (long-press or ♥ in detail to favourite), search by name,
 *    creator or tokenid, and a deep detail dialog with Send / Transfer / Bury.
 *
 * Favourites are per ITEM, so that tab always lists items directly — grouping there would hide the
 * one thing the user favourited behind a card they'd have to open.
 */
public class GalleryView extends BaseView {

    private static final String PREFS = "nftwallet_gallery";
    private static final String KEY_FAVS = "favs";

    private final LinearLayout header;
    private final RecyclerView grid;
    private final GalleryAdapter adapter = new GalleryAdapter();

    private EditText search;
    private TextView tabCollected, tabFavs, emptyNote;
    private LinearLayout crumbBar;
    private TextView crumbBack, crumbActions;
    private boolean showFavs = false;

    /** tokenid of the collection we've drilled into, or null at the top level. */
    private String openCollection = null;
    private String openCollectionName = "";

    /** tokenid → token record (script + meta) from `tokens tokenid:`; null value = fetch in flight. */
    private final Map<String, JSONObject> tokenRecords = new HashMap<>();
    private final Set<String> fetching = new HashSet<>();

    private final List<GItem> items = new ArrayList<>();

    public GalleryView(WalletActivity a) {
        super(a, R.layout.view_gallery);
        header = find(R.id.galleryHeader);
        grid = find(R.id.galleryGrid);
        grid.setLayoutManager(new GridLayoutManager(a, 2));
        grid.setAdapter(adapter);
        buildHeader();
    }

    private void buildHeader() {
        root.setBackgroundColor(Design.bg());

        LinearLayout tabs = new LinearLayout(act);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        // Either tab returns you to the top level: staying inside a collection while switching to
        // Favourites would show a filtered subset with no visible reason for what's missing.
        tabCollected = tabBtn("COLLECTED", v -> { showFavs = false; closeCollection(); });
        tabFavs = tabBtn("FAVOURITES", v -> { showFavs = true; closeCollection(); });
        tabs.addView(tabCollected, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabFavs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(tabs);

        search = new EditText(act);
        search.setHint("Search by name, creator or tokenid");
        search.setHintTextColor(Design.dim2());
        search.setTextColor(Design.text());
        search.setTextSize(13f);
        search.setBackgroundColor(Design.surface2());
        search.setPadding(dp(10), dp(9), dp(10), dp(9));
        search.setMaxLines(1);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        slp.bottomMargin = dp(8);
        search.setLayoutParams(slp);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { rebuild(); }
        });
        header.addView(search);

        // Shown only while drilled into a collection.
        crumbBar = new LinearLayout(act);
        crumbBar.setOrientation(LinearLayout.HORIZONTAL);
        crumbBar.setGravity(Gravity.CENTER_VERTICAL);
        crumbBar.setBackgroundColor(Design.surface2());
        crumbBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        crumbBar.setVisibility(View.GONE);

        crumbBack = new TextView(act);
        crumbBack.setTextColor(Design.accent());
        crumbBack.setTextSize(12f);
        crumbBack.setTypeface(Design.typefaceBold());
        crumbBack.setMaxLines(1);
        crumbBack.setEllipsize(TextUtils.TruncateAt.END);
        crumbBack.setOnClickListener(v -> closeCollection());
        crumbBar.addView(crumbBack, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        crumbActions = new TextView(act);
        crumbActions.setText("COLLECTION ⌄");
        crumbActions.setTextColor(Design.dim());
        crumbActions.setTextSize(10f);
        crumbActions.setLetterSpacing(0.08f);
        crumbActions.setTypeface(Design.typefaceBold());
        crumbActions.setPadding(dp(8), 0, 0, 0);
        crumbActions.setOnClickListener(v -> showCollectionActions());
        crumbBar.addView(crumbActions);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);
        crumbBar.setLayoutParams(clp);
        header.addView(crumbBar);

        emptyNote = new TextView(act);
        emptyNote.setTextColor(Design.dim());
        emptyNote.setTextSize(13f);
        emptyNote.setGravity(Gravity.CENTER);
        emptyNote.setPadding(0, dp(30), 0, 0);
        emptyNote.setVisibility(View.GONE);
        header.addView(emptyNote);
    }

    private TextView tabBtn(String label, View.OnClickListener click) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(11f);
        t.setLetterSpacing(0.1f);
        t.setTypeface(Design.typefaceBold());
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, dp(8));
        t.setOnClickListener(click);
        return t;
    }

    @Override
    public void refresh() {
        boolean fav = showFavs;
        tabCollected.setTextColor(!fav ? Design.onAccent() : Design.dim());
        tabCollected.setBackgroundColor(!fav ? Design.accent() : Design.surface2());
        tabFavs.setTextColor(fav ? Design.onAccent() : Design.dim());
        tabFavs.setBackgroundColor(fav ? Design.accent() : Design.surface2());
        if (openCollection != null) {
            crumbBar.setVisibility(View.VISIBLE);
            crumbBack.setText("←  All NFTs   ·   " + openCollectionName);
        } else {
            crumbBar.setVisibility(View.GONE);
        }
        rebuild();
    }

    // ===================== item assembly =====================

    /** True NFT candidate: any non-Minima token with no fractional decimals (the nftstudio rule). */
    private boolean isNftCandidate(TokenBalance b) {
        if (b.isMinima()) return false;
        String d = b.meta == null ? "" : b.meta.decimals;
        return d.isEmpty() || "0".equals(d.trim());
    }

    private void rebuild() {
        items.clear();
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase();
        Set<String> favs = favs();
        // Group only at the top level of the Collected tab. Favourites are per item, and once
        // you've drilled in the whole point is to see the items.
        final boolean grouped = !showFavs && openCollection == null;

        for (TokenBalance b : act.balances()) {
            if (!isNftCandidate(b)) continue;
            if (HiddenTokens.isHidden(act, b.tokenid)) continue;
            JSONObject record = tokenRecords.get(b.tokenid);
            if (record == null) { ensureRecord(b.tokenid); }
            String script = record == null ? "" : record.optString("script", "");
            boolean stateNft = !script.isEmpty() && StateNft.isStateNftScript(script);

            if (stateNft) {
                // Drilled in: every other collection is out of scope.
                if (openCollection != null && !openCollection.equals(b.tokenid)) continue;
                StateNft.Meta meta = StateNft.parseMeta(b.tokenid, record);
                List<GItem> mine = new ArrayList<>();
                for (Coin c : act.coins()) {
                    if (!c.tokenid.equals(b.tokenid)) continue;
                    String idx = StateNft.stamped(c.raw);
                    GItem it = new GItem();
                    it.tokenid = b.tokenid;
                    it.stateNft = true;
                    it.meta = meta;
                    it.coin = c;
                    it.idx = idx == null ? -1 : parseInt(idx);
                    it.name = meta.name + (idx != null ? " #" + idx : " (unstamped)");
                    it.creator = b.meta == null ? "" : b.meta.owner;
                    it.sub = "state · " + (idx != null ? "#" + idx + " of " + meta.size : "unstamped");
                    it.imageUrl = StateNft.imageUrl(meta, it.idx <= 0 ? 1 : it.idx, c.raw);
                    it.favKey = b.tokenid + "#" + it.idx;
                    it.webvalidate = meta.webvalidate;
                    it.externalUrl = meta.externalUrl;
                    it.description = meta.description;
                    it.searchBlob = blob(it.name, it.creator, it.tokenid);
                    mine.add(it);
                }
                if (mine.isEmpty()) continue;
                // Sealed index order, not UTXO order — #2 should follow #1 every time.
                java.util.Collections.sort(mine, (x, y) -> Integer.compare(x.idx, y.idx));
                if (grouped) items.add(collectionCard(b, meta, mine));
                else items.addAll(mine);
            } else {
                GItem it = new GItem();
                it.tokenid = b.tokenid;
                it.stateNft = false;
                it.balance = b;
                it.idx = -1;
                it.name = b.name;
                it.creator = b.meta == null ? "" : b.meta.owner;
                it.sub = "nft · " + Format.amount(b.sendable) + "/" + Format.amount(b.total);
                it.imageUrl = b.meta == null ? "" : b.meta.iconUrl;
                it.favKey = b.tokenid;
                it.webvalidate = b.meta == null ? "" : b.meta.webvalidate;
                it.externalUrl = b.meta == null ? "" : b.meta.externalUrl;
                it.description = b.meta == null ? "" : b.meta.description;
                it.searchBlob = blob(it.name, it.creator, it.tokenid);
                items.add(it);
            }
        }

        // filter: favourites tab + search
        List<GItem> filtered = new ArrayList<>();
        for (GItem it : items) {
            if (showFavs && !favs.contains(it.favKey)) continue;
            // searchBlob carries a collection card's ITEM names too, so searching "#7" still finds
            // the collection holding it rather than coming back empty.
            if (!q.isEmpty() && (it.searchBlob == null || !it.searchBlob.contains(q))) continue;
            filtered.add(it);
        }
        items.clear();
        items.addAll(filtered);

        emptyNote.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        emptyNote.setText(showFavs ? "No favourites yet — open an NFT and tap ♥."
                : openCollection != null ? "You no longer hold any items of this collection."
                : "No NFTs yet — mint one from the Mint tab.");
        adapter.notifyDataSetChanged();
    }

    /** Lower-cased haystack for the search box. Nulls are skipped, not rendered as "null". */
    private static String blob(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (p != null) sb.append(p).append(' ');
        return sb.toString().toLowerCase();
    }

    /** The single card that stands in for a whole StateNFT collection at the top level. */
    private GItem collectionCard(TokenBalance b, StateNft.Meta meta, List<GItem> mine) {
        GItem it = new GItem();
        it.collection = true;
        it.stateNft = true;
        it.tokenid = b.tokenid;
        it.meta = meta;
        it.owned = mine.size();
        it.idx = -1;
        it.name = meta.name;
        it.creator = b.meta == null ? "" : b.meta.owner;
        it.sub = "collection · " + mine.size() + " of " + meta.size + " held";
        // The token record's own icon is the collection's cover; fall back to the lowest-numbered
        // item you hold, which is what the mint form defaults the icon to anyway.
        String cover = b.meta == null ? "" : b.meta.iconUrl;
        it.imageUrl = cover == null || cover.isEmpty() ? mine.get(0).imageUrl : cover;
        it.favKey = b.tokenid;
        it.webvalidate = meta.webvalidate;
        it.externalUrl = meta.externalUrl;
        it.description = meta.description;
        StringBuilder names = new StringBuilder(blob(it.name, it.creator, it.tokenid));
        for (GItem m : mine) names.append(m.searchBlob);
        it.searchBlob = names.toString();
        return it;
    }

    // ===================== drill in / out =====================

    private void openCollection(GItem card) {
        openCollection = card.tokenid;
        openCollectionName = card.name == null ? "Collection" : card.name;
        refresh();
        grid.scrollToPosition(0);
    }

    private void closeCollection() {
        openCollection = null;
        openCollectionName = "";
        refresh();
        grid.scrollToPosition(0);
    }

    /** True if the tab handled a Back press by stepping out of a collection. */
    public boolean onBackPressed() {
        if (openCollection == null) return false;
        closeCollection();
        return true;
    }

    /** Whole-collection actions, reachable from inside the collection where they make sense. */
    private void showCollectionActions() {
        if (openCollection == null) return;
        final String tokenid = openCollection;
        final String name = openCollectionName;
        String creatorPk = "";
        JSONObject record = tokenRecords.get(tokenid);
        if (record != null) creatorPk = StateNft.creatorPk(record.optString("script", ""));
        final String pk = creatorPk;
        Sheet.create(act, name)
                .subtitle("Actions that apply to every item of this collection you still hold. "
                        + "Each item is its own transaction — the chain requires that.")
                .action("Send the whole collection", Sheet.Style.PRIMARY,
                        () -> StateNftActions.transferCollectionDialog(act, tokenid, name,
                                () -> act.reload()))
                .action("Bury the whole collection", Sheet.Style.DANGER,
                        () -> StateNftActions.buryCollectionDialog(act, tokenid, name, pk,
                                () -> act.reload()))
                .action("Close", Sheet.Style.SECONDARY, null)
                .show();
    }

    /** Fetch the token record once per tokenid (script decides state-vs-regular; meta feeds images). */
    private void ensureRecord(final String tokenid) {
        if (!Util.isValidHexId(tokenid)) return;   // chain-supplied id — never interpolate unvetted
        if (tokenRecords.containsKey(tokenid) || fetching.contains(tokenid)) return;
        fetching.add(tokenid);
        act.node().cmd("tokens tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                fetching.remove(tokenid);
                Object resp = json.opt("response");
                JSONObject tok = resp instanceof JSONObject ? (JSONObject) resp
                        : (resp instanceof org.json.JSONArray && ((org.json.JSONArray) resp).length() > 0
                            ? ((org.json.JSONArray) resp).optJSONObject(0) : null);
                if (tok != null) {
                    tokenRecords.put(tokenid, tok);
                    rebuild();
                }
            }
            @Override public void onError(String message) { fetching.remove(tokenid); }
        });
    }

    // ===================== favourites =====================

    private Set<String> favs() {
        return new HashSet<>(act.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getStringSet(KEY_FAVS, new HashSet<>()));
    }

    private void toggleFav(String key) {
        Set<String> f = favs();
        if (!f.remove(key)) f.add(key);
        act.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_FAVS, f).apply();
        rebuild();
    }

    // ===================== grid =====================

    private static class GItem {
        String tokenid, name, creator, sub, imageUrl, favKey, webvalidate, externalUrl, description;
        String searchBlob;         // lower-cased haystack; a collection's includes its item names
        boolean stateNft;
        boolean collection;        // this card stands for a whole collection, not one item
        int owned;                 // collection cards: how many of the edition you hold
        int idx;
        Coin coin;                 // state item's UTXO
        TokenBalance balance;      // regular NFT's balance row
        StateNft.Meta meta;        // state collection meta
    }

    private class Holder extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView name, sub, idxChip, heart;

        Holder(FrameLayout shell, ImageView art, TextView name, TextView sub, TextView idxChip, TextView heart) {
            super(shell);
            this.art = art; this.name = name; this.sub = sub; this.idxChip = idxChip; this.heart = heart;
        }
    }

    private class GalleryAdapter extends RecyclerView.Adapter<Holder> {

        @Override public int getItemCount() { return items.size(); }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout shell = new FrameLayout(act);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = dp(4);
            lp.setMargins(m, m, m, m);
            shell.setLayoutParams(lp);
            shell.setBackgroundColor(Design.surface());

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            shell.addView(col, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView art = new ImageView(act);
            art.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            col.addView(art);

            LinearLayout meta = new LinearLayout(act);
            meta.setOrientation(LinearLayout.VERTICAL);
            meta.setPadding(dp(8), dp(6), dp(8), dp(7));
            TextView name = new TextView(act);
            name.setTextColor(Design.text());
            name.setTextSize(12f);
            name.setTypeface(Design.typefaceBold());
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            TextView sub = new TextView(act);
            sub.setTextColor(Design.dim());
            sub.setTextSize(9.5f);
            sub.setLetterSpacing(0.04f);
            meta.addView(name);
            meta.addView(sub);
            col.addView(meta);

            TextView idxChip = new TextView(act);
            idxChip.setTextColor(0xFFFFFFFF);
            idxChip.setBackgroundColor(0x8C000000);
            idxChip.setTextSize(9f);
            idxChip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            idxChip.setPadding(dp(6), dp(3), dp(6), dp(3));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP);
            clp.setMargins(dp(6), dp(6), 0, 0);
            shell.addView(idxChip, clp);

            TextView heart = new TextView(act);
            heart.setTextSize(14f);
            heart.setShadowLayer(4f, 0, 1, 0xAA000000);
            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.TOP);
            hlp.setMargins(0, dp(4), dp(8), 0);
            shell.addView(heart, hlp);

            return new Holder(shell, art, name, sub, idxChip, heart);
        }

        @Override public void onBindViewHolder(Holder h, int position) {
            final GItem it = items.get(position);
            h.name.setText(it.name == null ? "NFT" : it.name);
            h.sub.setText(it.sub == null ? "" : it.sub.toUpperCase());

            if (it.collection) {
                // The chip carries the count, so a stack of items reads as a stack at a glance.
                h.idxChip.setVisibility(View.VISIBLE);
                h.idxChip.setText("▣ " + it.owned + (it.owned == 1 ? " ITEM" : " ITEMS"));
            } else if (it.stateNft && it.idx > 0 && it.meta != null) {
                h.idxChip.setVisibility(View.VISIBLE);
                h.idxChip.setText("#" + it.idx + " / " + it.meta.size);
            } else {
                h.idxChip.setVisibility(View.GONE);
            }

            boolean fav = favs().contains(it.favKey);
            h.heart.setText(fav ? "♥" : "♡");
            h.heart.setTextColor(fav ? 0xFFFF5A6E : 0xFFFFFFFF);
            h.heart.setOnClickListener(v -> toggleFav(it.favKey));

            h.art.setImageBitmap(Identicon.forToken(it.tokenid + "#" + it.idx, dp(150)));
            if (it.imageUrl != null && !it.imageUrl.isEmpty()) {
                ImageLoader.loadOver(act, it.imageUrl, h.art, null);
            }
            ((FrameLayout) h.itemView).setForeground(null);
            // A collection card opens the collection; everything else opens its detail sheet.
            h.itemView.setOnClickListener(v -> { if (it.collection) openCollection(it); else showDetail(it); });
            h.itemView.setOnLongClickListener(v -> { toggleFav(it.favKey); return true; });
        }
    }

    // ===================== detail =====================

    private void showDetail(final GItem it) {
        final Sheet sheet = Sheet.create(act, it.name);
        LinearLayout box = sheet.body();

        // hero
        final ImageView hero = new ImageView(act);
        hero.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.setImageBitmap(Identicon.forToken(it.tokenid + "#" + it.idx, dp(200)));
        if (it.imageUrl != null && !it.imageUrl.isEmpty()) {
            ImageLoader.loadOver(act, it.imageUrl, hero, null);
            hero.setOnClickListener(v -> showImageFull(it.imageUrl));
        }
        box.addView(hero);
        TextView hint = new TextView(act);
        hint.setText("tap image for full resolution");
        hint.setTextColor(Design.dim());
        hint.setTextSize(10f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(3), 0, dp(6));
        box.addView(hint);

        // chips line
        StringBuilder chips = new StringBuilder();
        if (it.stateNft) {
            chips.append(it.idx > 0 ? "#" + it.idx + " of " + (it.meta == null ? "?" : it.meta.size) : "unstamped");
            chips.append("  ·  LOCKED EDITION");
        } else if (it.balance != null) {
            chips.append("1".equals(it.balance.total) ? "1 OF 1" : it.balance.total + " EDITIONS");
        }
        if (it.webvalidate != null && !it.webvalidate.isEmpty()
                && Boolean.TRUE.equals(WebValidate.status(it.tokenid))) {
            chips.append("  ·  ✓ WEB VALIDATED");
        }
        TextView chipLine = new TextView(act);
        chipLine.setText(chips.toString());
        chipLine.setTextColor(Design.accent());
        chipLine.setTextSize(10f);
        chipLine.setLetterSpacing(0.06f);
        chipLine.setTypeface(Design.typefaceBold());
        chipLine.setPadding(0, dp(3), 0, dp(8));
        box.addView(chipLine);

        if (it.description != null && !it.description.isEmpty()) {
            TextView d = new TextView(act);
            d.setText(it.description);
            d.setTextColor(Design.dim());
            d.setTextSize(13f);
            d.setPadding(0, 0, 0, dp(6));
            box.addView(d);
        }

        kvCopy(box, "Token ID", it.tokenid);
        if (it.coin != null) kvCopy(box, "Coin ID", it.coin.coinid);
        if (it.creator != null && !it.creator.isEmpty()) kvCopy(box, "Creator", it.creator);
        if (it.stateNft && it.idx > 0) kvCopy(box, "Identity (state 0)", it.idx + " · sealed");
        if (it.externalUrl != null && !it.externalUrl.isEmpty()) linkRow(box, "External URL", it.externalUrl);
        if (it.webvalidate != null && !it.webvalidate.isEmpty()) linkRow(box, "Web validation", it.webvalidate);

        final boolean fav = favs().contains(it.favKey);
        sheet.action("Close", Sheet.Style.SECONDARY, null);
        sheet.action(fav ? "♥ Unfavourite" : "♡ Favourite", Sheet.Style.SECONDARY,
                () -> toggleFav(it.favKey));

        if (it.stateNft && it.coin != null) {
            final String display = it.name;
            final String collection = it.meta == null ? it.name : it.meta.name;
            sheet.action("Transfer", Sheet.Style.PRIMARY, () ->
                    StateNftActions.transferDialog(act, it.tokenid, display, it.coin.raw));
            final android.app.Dialog dlg = sheet.show();
            // Bury lives behind long-press on Transfer? No — add a dedicated row instead.
            TextView bury = new TextView(act);
            bury.setText("✝  Bury this item (irreversible)");
            bury.setTextColor(Design.red());
            bury.setTextSize(12f);
            bury.setPadding(0, dp(12), 0, dp(4));
            bury.setOnClickListener(v -> {
                dlg.dismiss();
                StateNftActions.buryDialog(act, it.tokenid, collection, display, it.coin.raw);
            });
            box.addView(bury);

            TextView sendAll = new TextView(act);
            sendAll.setText("→  Send the WHOLE collection (every item you hold)");
            sendAll.setTextColor(Design.accent());
            sendAll.setTextSize(12f);
            sendAll.setPadding(0, dp(8), 0, dp(2));
            sendAll.setOnClickListener(v -> {
                dlg.dismiss();
                StateNftActions.transferCollectionDialog(act, it.tokenid, collection,
                        () -> act.reload());
            });
            box.addView(sendAll);

            TextView buryAll = new TextView(act);
            buryAll.setText("✝  Bury the WHOLE collection (every item you hold)");
            buryAll.setTextColor(Design.red());
            buryAll.setTextSize(12f);
            buryAll.setPadding(0, dp(2), 0, dp(4));
            final JSONObject rec = tokenRecords.get(it.tokenid);
            final String creatorPk = rec == null ? "" : StateNft.creatorPk(rec.optString("script", ""));
            buryAll.setOnClickListener(v -> {
                dlg.dismiss();
                StateNftActions.buryCollectionDialog(act, it.tokenid, collection, creatorPk,
                        () -> act.reload());
            });
            box.addView(buryAll);
        } else {
            sheet.action("Send", Sheet.Style.PRIMARY, () -> act.sendToken(it.tokenid));
            sheet.show();
        }

        // fire the web-validation check so the badge is fresh next open
        if (it.webvalidate != null && !it.webvalidate.isEmpty()) {
            WebValidate.ensure(act, it.tokenid, it.webvalidate, () -> {});
        }
    }

    /** Full-screen, full-resolution image. Tap to dismiss. */
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

    private void kvCopy(LinearLayout box, final String label, final String value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, dp(4));
        TextView l = new TextView(act);
        l.setText(label);
        l.setTextColor(Design.dim());
        l.setTextSize(12f);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(act);
        v.setText(value.length() > 26 ? Util.shorten(value) : value);
        v.setTextColor(Design.text());
        v.setTextSize(12f);
        v.setTypeface(Typeface.MONOSPACE);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        r.setOnClickListener(x -> CoinDetailDialog.copy(act, label, value));
        box.addView(r);
    }

    private void linkRow(LinearLayout box, String label, final String url) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(6), 0, dp(6));
        TextView l = new TextView(act);
        l.setText(label);
        l.setTextColor(Design.dim());
        l.setTextSize(12f);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(act);
        boolean web = Util.isWebUrl(url);
        v.setText(web ? "↗ open" : "blocked");
        v.setTextColor(web ? Design.accent() : Design.red());
        v.setTextSize(12f);
        v.setGravity(Gravity.END);
        r.addView(v);
        r.setOnClickListener(x -> Util.openWebUrl(act, url));
        box.addView(r);
        TextView dest = new TextView(act);
        dest.setText(url);
        dest.setTextColor(Design.dim2());
        dest.setTextSize(10f);
        dest.setTypeface(Typeface.MONOSPACE);
        dest.setMaxLines(2);
        dest.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        box.addView(dest);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density); }
}
