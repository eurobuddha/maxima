package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;

import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The Mint tab — three creation paths, matching the old Wallet's token/NFT creation suite:
 *
 *  TOKEN — fungible: name, amount, decimals, ticker, description, icon URL, web validation,
 *          arbitrary key/value metadata, burn → one {@code tokencreate} with a JSON name.
 *  NFT   — decimals:0 editions: image upload (compressed ≤{@link #ARTIMAGE_BUDGET} base64,
 *          embedded on-chain in &lt;artimage&gt;) or URL/IPFS mode, creator name, external URL,
 *          web validation, optional creator signature ({@code signtoken}).
 *  STATE NFT COLLECTION — the statenft-suite locked-edition pipeline (enabled in the next stage).
 *
 * Every path shows a confirmation card before posting and a result dialog after.
 */
public class MintView extends BaseView {

    // Budgets live in ImageTools so this app and Atelier cannot drift apart.
    private static final int ARTIMAGE_BUDGET = ImageTools.ARTIMAGE_BUDGET;
    private static final int STATE_IMG_BUDGET = ImageTools.STATE_IMG_BUDGET;
    private static final int ICON_BUDGET = ImageTools.ICON_BUDGET;

    /** Keys the wallet writes itself — a custom metadata pair may not overwrite them. */
    private static final java.util.Set<String> RESERVED_META = new java.util.HashSet<>(java.util.Arrays.asList(
            "name", "url", "description", "ticker", "webvalidate", "external_url", "owner", "nft", "icon"));

    private enum Screen { HUB, TOKEN, NFT, COLLECTION, PROGRESS }

    private final LinearLayout container;
    private Screen screen = Screen.HUB;
    /** LocalStore row id shown by the PROGRESS screen. */
    private long progressRowId = -1;

    // ---- collection form ----
    private LinearLayout colForm;
    private EditText cName, cSize, cDesc, cBase, cExt, cIconUrl, cExtUrl, cWebv;
    private TextView cStatus, cModeEmbed, cModeUrl, cImagesNote;
    private LinearLayout cUrlBlock, cImagesBlock, cImageSlots, cIconUrlBlock;
    private TextView cIconModeEmbed, cIconModeUrl, cIconNote;
    private ImageView cIconPreview;
    private boolean colEmbedMode = true;
    /** Collection icon: true = embed on-chain, false = point at a URL. */
    private boolean colIconEmbed = true;
    /** Embedded collection icon, base64 (empty = fall back to item #1). */
    private String colIconB64 = "";
    /** Item #1 pre-shrunk to ICON_BUDGET, prepared in the background when slot 1 is filled, so the
     *  confirm step never has to decode and re-encode on the UI thread. */
    private String colIconDefault = "";
    /** Which item-#1 image colIconDefault was derived from, so we only shrink once per image. */
    private String colIconDefaultSource = "";
    /** Per-item base64 images, key = item index 1..size. */
    private final java.util.HashMap<Integer, String> colImages = new java.util.HashMap<>();

    // ---- token form (built once, values persist across re-renders) ----
    private LinearLayout tokenForm;
    private EditText tName, tAmount, tDecimals, tTicker, tDesc, tUrl, tWebv, tBurn;
    private LinearLayout tMetaRows;
    private TextView tStatus;

    // ---- NFT form ----
    private LinearLayout nftForm;
    private EditText nName, nEditions, nDesc, nExtUrl, nUrl, nCreator, nWebv, nBurn;
    private TextView nStatus, nImageNote, nModeEmbed, nModeUrl;
    private ImageView nPreview;
    private LinearLayout nUrlBlock;
    private CheckBox nSign;
    private boolean nftEmbedMode = true;
    private String nftImageB64 = "";

    public MintView(WalletActivity a) {
        super(a, R.layout.view_mint);
        container = find(R.id.mintRoot);
        root.setBackgroundColor(Design.bg());
    }

    @Override
    public void refresh() {
        // A live form already on screen keeps its focus/keyboard: every reload (per new block)
        // calls refresh(), and detach/re-attach of the focused EditText would yank the keyboard
        // away mid-typing. Forms are static — nothing in them depends on wallet state.
        if ((screen == Screen.TOKEN && tokenForm != null && tokenForm.getParent() == container)
                || (screen == Screen.NFT && nftForm != null && nftForm.getParent() == container)
                || (screen == Screen.COLLECTION && colForm != null && colForm.getParent() == container)) {
            return;
        }
        container.removeAllViews();
        switch (screen) {
            case TOKEN:      container.addView(tokenForm()); break;
            case NFT:        container.addView(nftForm());   break;
            case COLLECTION: container.addView(collectionForm()); break;
            case PROGRESS:   renderProgress(); break;
            default:         renderHub();
        }
    }

    /** Engine progress: refresh only the passive screens — never yank focus from an open form. */
    public void onEngineTick() {
        if (screen == Screen.PROGRESS || screen == Screen.HUB) refresh();
    }

    // ===================== HUB =====================

    private void renderHub() {
        container.addView(hubCard("Token",
                "Fungible. Name, ticker, decimals, description, icon URL, web validation, custom metadata.",
                false, v -> { screen = Screen.TOKEN; refresh(); }));
        container.addView(hubCard("NFT",
                "1-of-1 or edition, decimals 0. Embed an image on-chain or point at a URL / IPFS.",
                false, v -> { screen = Screen.NFT; refresh(); }));
        container.addView(hubCard("State NFT collection",
                "One tokenid, 2–20 unique items. Per-item identity sealed in coin state — immutable even to you.",
                true, v -> { screen = Screen.COLLECTION; refresh(); }));

        // resumable collections list
        org.json.JSONArray rows = LocalStore.load(act);
        if (rows.length() > 0) {
            TextView lbl = new TextView(act);
            lbl.setText("COLLECTIONS");
            lbl.setTextColor(Design.dim());
            lbl.setTextSize(11f);
            lbl.setLetterSpacing(0.1f);
            lbl.setPadding(0, dp(10), 0, dp(6));
            container.addView(lbl);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) container.addView(collectionRow(row));
            }
        }
    }

    private View collectionRow(final JSONObject row) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackgroundColor(Design.surface());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        r.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(act);
        n.setText(row.optString("name", "Collection") + "  ·  " + row.optInt("size") + " items");
        n.setTextColor(Design.text());
        n.setTextSize(13f);
        n.setTypeface(Design.typefaceBold());
        col.addView(n);
        String err = row.optString("error", "");
        if (!err.isEmpty()) {
            TextView e = new TextView(act);
            e.setText(err);
            e.setTextColor(Design.red());
            e.setTextSize(10f);
            e.setMaxLines(1);
            e.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(e);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        String phase = row.optString("phase", "?");
        TextView p = new TextView(act);
        p.setText(phase);
        p.setTextSize(10f);
        p.setLetterSpacing(0.08f);
        p.setTypeface(Design.typefaceBold());
        p.setTextColor("DONE".equals(phase) ? Design.success()
                : "NEEDIMAGES".equals(phase) || !err.isEmpty() ? Design.red() : Design.accent());
        r.addView(p);

        r.setOnClickListener(v -> { progressRowId = row.optLong("id", -1); screen = Screen.PROGRESS; refresh(); });
        return r;
    }

    private View hubCard(String title, String blurb, boolean accent, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        android.graphics.drawable.GradientDrawable bgd = new android.graphics.drawable.GradientDrawable();
        bgd.setColor(Design.surface());
        bgd.setStroke(Math.max(1, dp(1)), accent ? Design.accent() : Design.border());
        bgd.setCornerRadius(dp((int) Design.radiusDp()));
        card.setBackground(bgd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(Design.heading());
        t.setTextSize(15f);
        t.setTypeface(Design.typefaceBold());
        top.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = new TextView(act);
        arrow.setText("→");
        arrow.setTextColor(Design.accent());
        arrow.setTextSize(15f);
        top.addView(arrow);
        card.addView(top);

        TextView b = new TextView(act);
        b.setText(blurb);
        b.setTextColor(Design.dim());
        b.setTextSize(12f);
        b.setPadding(0, dp(4), 0, 0);
        card.addView(b);

        card.setOnClickListener(click);
        return card;
    }

    // ===================== TOKEN FORM =====================

    private View tokenForm() {
        if (tokenForm != null) return detachedForReuse(tokenForm);
        tokenForm = new LinearLayout(act);
        tokenForm.setOrientation(LinearLayout.VERTICAL);

        tokenForm.addView(backRow("Mint token"));
        tName = addField(tokenForm, "Name *", "My token", InputType.TYPE_CLASS_TEXT);
        LinearLayout triple = new LinearLayout(act);
        triple.setOrientation(LinearLayout.HORIZONTAL);
        tokenForm.addView(triple);
        tAmount = addWeightedField(triple, "Amount *", "1000000", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 1.4f);
        tDecimals = addWeightedField(triple, "Decimals", "8", InputType.TYPE_CLASS_NUMBER, 1f);
        tTicker = addWeightedField(triple, "Ticker", "TOK", InputType.TYPE_CLASS_TEXT, 1f);
        tDesc = addField(tokenForm, "Description", "Max 255 characters", InputType.TYPE_CLASS_TEXT);
        tUrl = addField(tokenForm, "Icon / image URL", "https://…", InputType.TYPE_TEXT_VARIATION_URI);
        tWebv = addField(tokenForm, "Web validation URL", "https://…/token.txt  (host the tokenid there after minting)", InputType.TYPE_TEXT_VARIATION_URI);
        tBurn = addField(tokenForm, "Burn (optional)", "0.0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        tMetaRows = new LinearLayout(act);
        tMetaRows.setOrientation(LinearLayout.VERTICAL);
        tokenForm.addView(tMetaRows);
        TextView addMeta = new TextView(act);
        addMeta.setText("+ Add metadata (key / value)");
        addMeta.setTextColor(Design.accent());
        addMeta.setTextSize(13f);
        addMeta.setPadding(dp(2), dp(8), 0, dp(8));
        addMeta.setOnClickListener(v -> addMetaRow());
        tokenForm.addView(addMeta);

        tokenForm.addView(primaryButton("Review & mint", v -> onReviewToken()));
        tStatus = statusLine();
        tokenForm.addView(tStatus);
        return tokenForm;
    }

    private void addMetaRow() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        EditText k = plainField("key", InputType.TYPE_CLASS_TEXT);
        EditText v = plainField("value", InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        klp.rightMargin = dp(6);
        row.addView(k, klp);
        row.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        row.setLayoutParams(rlp);
        tMetaRows.addView(row);
    }

    private void onReviewToken() {
        final String name = tName.getText().toString().trim();
        String err = badValue(name, true);
        if (err != null) { status(tStatus, "Name: " + err, false); return; }

        final String amountStr = tAmount.getText().toString().trim();
        final BigDecimal amount;
        try { amount = new BigDecimal(amountStr); } catch (Exception e) { status(tStatus, "Enter a valid amount.", false); return; }
        if (amount.signum() <= 0) { status(tStatus, "Amount must be greater than zero.", false); return; }
        if (amount.compareTo(new BigDecimal("1000000000000")) > 0) { status(tStatus, "Amount is above the 1 trillion cap.", false); return; }

        int decimals = 8;
        String decStr = tDecimals.getText().toString().trim();
        if (!decStr.isEmpty()) {
            try { decimals = Integer.parseInt(decStr); } catch (Exception e) { decimals = -1; }
            if (decimals < 0 || decimals > 16) { status(tStatus, "Decimals must be 0–16.", false); return; }
        }
        if (Util.decimalPlaces(amount) > 0) { status(tStatus, "Total supply must be a whole number.", false); return; }

        final String ticker = tTicker.getText().toString().trim();
        if (ticker.length() > 5) { status(tStatus, "Ticker is at most 5 characters.", false); return; }
        if ((err = badValue(ticker, false)) != null) { status(tStatus, "Ticker: " + err, false); return; }

        final String desc = tDesc.getText().toString().trim();
        if (desc.length() > 255) { status(tStatus, "Description is at most 255 characters.", false); return; }
        if ((err = badValue(desc, false)) != null) { status(tStatus, "Description: " + err, false); return; }

        final String url = tUrl.getText().toString().trim();
        if (!url.isEmpty() && badUrl(url)) { status(tStatus, "Icon URL must be a plain http(s) link.", false); return; }
        final String webv = tWebv.getText().toString().trim();
        if (!webv.isEmpty() && badUrl(webv)) { status(tStatus, "Web validation must be a plain https link.", false); return; }

        final String burn = tBurn.getText().toString().trim();
        if (!burn.isEmpty() && badAmount(burn)) { status(tStatus, "Invalid burn amount.", false); return; }

        // metadata pairs
        final List<String[]> meta = new ArrayList<>();
        for (int i = 0; i < tMetaRows.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) tMetaRows.getChildAt(i);
            String k = ((EditText) row.getChildAt(0)).getText().toString().trim();
            String v = ((EditText) row.getChildAt(1)).getText().toString().trim();
            if (k.isEmpty() && v.isEmpty()) continue;
            if (k.isEmpty()) { status(tStatus, "Metadata row " + (i + 1) + " has no key.", false); return; }
            if (badValue(k, false) != null || badValue(v, false) != null) {
                status(tStatus, "Metadata may not contain quotes, backslashes or semicolons.", false); return;
            }
            if (RESERVED_META.contains(k.toLowerCase())) {
                status(tStatus, "“" + k + "” is a reserved field — use the form inputs for it.", false); return;
            }
            meta.add(new String[]{k, v});
        }
        tStatus.setVisibility(View.GONE);

        JSONObject json = new JSONObject();
        try {
            json.put("name", name);
            if (!desc.isEmpty()) json.put("description", desc);
            if (!ticker.isEmpty()) json.put("ticker", ticker);
            if (!url.isEmpty()) json.put("url", url);
            if (!webv.isEmpty()) json.put("webvalidate", webv);
            for (String[] kv : meta) json.put(kv[0], kv[1]);
        } catch (Exception ignored) {}

        StringBuilder cmd = new StringBuilder("tokencreate name:").append(json)
                .append(" amount:").append(amount.toBigInteger())
                .append(" decimals:").append(decimals);
        if (!burn.isEmpty() && positive(burn)) cmd.append(" burn:").append(burn);
        if (!webv.isEmpty()) cmd.append(" webvalidate:").append(webv);

        // ALWAYS SIGNED: gate the record with the signature aboard
        if (json.toString().length() > StateNft.META_MAX) {
            status(tStatus, "Record " + (json.toString().length() + StateNft.DEF_WRAPPER
                    + StateNft.DEF_SIGN_WEIGHT) + "B signed is past the split bound — "
                    + "smaller icon or shorter fields.", false);
            return;
        }

        // Confirmation card, old-wallet style
        LinearLayout body = confirmBody();
        addConfirmRow(body, "Name", name);
        addConfirmRow(body, "Total supply", amount.toBigInteger().toString());
        addConfirmRow(body, "Decimals", String.valueOf(decimals));
        if (!ticker.isEmpty()) addConfirmRow(body, "Ticker", ticker.toUpperCase());
        addConfirmRow(body, "Description", desc.isEmpty() ? "Not set" : desc);
        if (!url.isEmpty()) addConfirmRow(body, "Icon URL", url);
        addConfirmRow(body, "Web validation", webv.isEmpty() ? "Not set" : webv);
        for (String[] kv : meta) addConfirmRow(body, kv[0], kv[1]);
        if (!burn.isEmpty() && positive(burn)) addConfirmRow(body, "Burn", burn + " MINIMA");
        showConfirm("Mint this token?", body, "Mint →", () -> {
            // ALWAYS SIGNED — no key, no mint
            act.node().cmd("getaddress", new NodeApi.Cb() {
                @Override public void onResult(JSONObject gres) {
                    JSONObject r = gres.optJSONObject("response");
                    String pk = r == null ? "" : r.optString("publickey", "");
                    if (pk.isEmpty()) { status(tStatus, "Could not fetch the signing key — node offline?", false); return; }
                    runMint(cmd.toString() + " signtoken:" + pk, tStatus, "Token");
                }
                @Override public void onError(String message) { status(tStatus, "Could not fetch the signing key: " + message, false); }
            });
        });
    }

    // ===================== NFT FORM =====================

    private View nftForm() {
        if (nftForm != null) return detachedForReuse(nftForm);
        nftForm = new LinearLayout(act);
        nftForm.setOrientation(LinearLayout.VERTICAL);

        nftForm.addView(backRow("Mint NFT"));

        // image row: preview + mode toggle
        LinearLayout imgRow = new LinearLayout(act);
        imgRow.setOrientation(LinearLayout.HORIZONTAL);
        nftForm.addView(imgRow);

        nPreview = new ImageView(act);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(92), dp(92));
        plp.rightMargin = dp(10);
        nPreview.setLayoutParams(plp);
        nPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        nPreview.setBackgroundColor(Design.surface2());
        nPreview.setOnClickListener(v -> pickNftImage());
        imgRow.addView(nPreview);

        LinearLayout modeCol = new LinearLayout(act);
        modeCol.setOrientation(LinearLayout.VERTICAL);
        modeCol.setGravity(Gravity.CENTER_VERTICAL);
        imgRow.addView(modeCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout seg = new LinearLayout(act);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        nModeEmbed = segBtn("EMBED IMAGE", v -> setNftMode(true));
        nModeUrl = segBtn("URL / IPFS", v -> setNftMode(false));
        seg.addView(nModeEmbed, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        seg.addView(nModeUrl, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        modeCol.addView(seg);

        nImageNote = new TextView(act);
        nImageNote.setTextColor(Design.dim());
        nImageNote.setTextSize(10f);
        nImageNote.setPadding(0, dp(6), 0, 0);
        modeCol.addView(nImageNote);

        nUrlBlock = new LinearLayout(act);
        nUrlBlock.setOrientation(LinearLayout.VERTICAL);
        nftForm.addView(nUrlBlock);
        nUrl = addField(nUrlBlock, "Image URL (URL mode)", "https://…  or  ipfs://…", InputType.TYPE_TEXT_VARIATION_URI);
        hostButton(nUrlBlock, "Upload the image to my node instead…", v -> act.pickImage(uri -> {
            status(nStatus, "Uploading to your node…", true);
            NodeHost.upload(act, uri, "", 0, new NodeHost.Cb() {
                @Override public void done(String url, String path, String sha256) {
                    if (url.isEmpty()) {
                        status(nStatus, "Hosted on the node as " + path + " but the node has no public web address set "
                                + "(-Dparlons.node.public) - ask the operator, then paste its URL here.", false);
                        return;
                    }
                    nUrl.setText(url);
                    status(nStatus, "Hosted on your node ✓ " + url + " (sha256 " + sha256 + ")", true);
                }
                @Override public void fail(String why) { status(nStatus, "Hosting failed: " + why, false); }
                @Override public void progress(String note) { status(nStatus, note, true); }
            });
        }));
        nName = addField(nftForm, "Name *", "My NFT", InputType.TYPE_CLASS_TEXT);
        LinearLayout duo = new LinearLayout(act);
        duo.setOrientation(LinearLayout.HORIZONTAL);
        nftForm.addView(duo);
        nEditions = addWeightedField(duo, "Editions *", "1", InputType.TYPE_CLASS_NUMBER, 1f);
        nCreator = addWeightedField(duo, "Creator", "your name", InputType.TYPE_CLASS_TEXT, 2f);
        nDesc = addField(nftForm, "Description", "Tell collectors what this is", InputType.TYPE_CLASS_TEXT);
        nExtUrl = addField(nftForm, "External URL", "https://…", InputType.TYPE_TEXT_VARIATION_URI);
        nWebv = addField(nftForm, "Web validation URL", "https://…/nft.txt", InputType.TYPE_TEXT_VARIATION_URI);
        nBurn = addField(nftForm, "Burn (optional)", "0.0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        nSign = new CheckBox(act);
        nSign.setText("Signed as creator — always (the signature is your provenance)");
        nSign.setTextColor(Design.dim());
        nSign.setTextSize(12f);
        nSign.setChecked(true);
        nSign.setEnabled(false);   // ALWAYS SIGNED: not a choice any more
        nftForm.addView(nSign);

        nftForm.addView(primaryButton("Review & mint NFT", v -> onReviewNft()));
        nStatus = statusLine();
        nftForm.addView(nStatus);

        setNftMode(true);
        prefillCreator();
        return nftForm;
    }

    /** A quiet outlined action under a URL field: "host this on my node". */
    private void hostButton(LinearLayout into, String label, View.OnClickListener click) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(12f);
        t.setTypeface(Design.typefaceBold());
        t.setTextColor(Design.accent());
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(9), dp(10), dp(9));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Design.surface2());
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Design.border());
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(click);
        into.addView(t);
    }

    private TextView segBtn(String label, View.OnClickListener click) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(10f);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Design.typefaceBold());
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(7), dp(6), dp(7));
        t.setOnClickListener(click);
        return t;
    }

    private void setNftMode(boolean embed) {
        nftEmbedMode = embed;
        nModeEmbed.setTextColor(embed ? Design.onAccent() : Design.dim());
        nModeEmbed.setBackgroundColor(embed ? Design.accent() : Design.surface2());
        nModeUrl.setTextColor(!embed ? Design.onAccent() : Design.dim());
        nModeUrl.setBackgroundColor(!embed ? Design.accent() : Design.surface2());
        nUrlBlock.setVisibility(embed ? View.GONE : View.VISIBLE);
        nImageNote.setText(embed
                ? (nftImageB64.isEmpty()
                    ? "Tap the square to pick an image — compressed and stored on-chain (≤" + ARTIMAGE_BUDGET + " b64 chars)."
                    : "Image ready — " + nftImageB64.length() + " base64 chars, stored on-chain.")
                : "The NFT points at an external image URL (or ipfs://…).");
    }

    private void pickNftImage() {
        act.pickImage(uri -> {
            if (uri == null) return;
            nPreview.setImageURI(uri);
            nImageNote.setText("Compressing…");
            new Thread(() -> {
                final String result = sealFrom(uri, ARTIMAGE_BUDGET);
                act.runOnUiThread(() -> {
                    if (result.isEmpty()) {
                        nftImageB64 = "";
                        nImageNote.setText("Could not read that image — try another, or a smaller one.");
                    } else {
                        nftImageB64 = result;
                        setNftMode(true);   // picking an image implies embed mode
                    }
                });
            }).start();
        });
    }

    /** Prefill the creator field from the node's Maxima name (best-effort). */
    private void prefillCreator() {
        act.node().cmd("maxima action:info", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                String name = r == null ? "" : r.optString("name", "");
                if (!name.isEmpty() && !"noname".equals(name)
                        && nCreator.getText().toString().trim().isEmpty()) {
                    nCreator.setText(name);
                }
            }
            @Override public void onError(String message) {}
        });
    }

    private void onReviewNft() {
        final String name = nName.getText().toString().trim();
        String err = badValue(name, true);
        if (err != null) { status(nStatus, "Name: " + err, false); return; }

        final String edStr = nEditions.getText().toString().trim();
        final int editions;
        try { editions = edStr.isEmpty() ? 1 : Integer.parseInt(edStr); }
        catch (Exception e) { status(nStatus, "Editions must be a whole number.", false); return; }
        if (editions < 1 || editions > 1000000) { status(nStatus, "Editions must be 1–1,000,000.", false); return; }

        final String desc = nDesc.getText().toString().trim();
        if (desc.length() > 255) { status(nStatus, "Description is at most 255 characters.", false); return; }
        if ((err = badValue(desc, false)) != null) { status(nStatus, "Description: " + err, false); return; }
        final String creator = nCreator.getText().toString().trim();
        if ((err = badValue(creator, false)) != null) { status(nStatus, "Creator: " + err, false); return; }

        final String extUrl = nExtUrl.getText().toString().trim();
        if (!extUrl.isEmpty() && badUrl(extUrl)) { status(nStatus, "External URL must be a plain http(s) link.", false); return; }
        final String webv = nWebv.getText().toString().trim();
        if (!webv.isEmpty() && badUrl(webv)) { status(nStatus, "Web validation must be a plain https link.", false); return; }
        final String burn = nBurn.getText().toString().trim();
        if (!burn.isEmpty() && badAmount(burn)) { status(nStatus, "Invalid burn amount.", false); return; }

        final String imageValue;
        if (nftEmbedMode) {
            if (nftImageB64.isEmpty()) { status(nStatus, "Pick an image first (or switch to URL mode).", false); return; }
            imageValue = "<artimage>" + nftImageB64 + "</artimage>";
        } else {
            String u = nUrl.getText().toString().trim();
            boolean okScheme = u.startsWith("https://") || u.startsWith("http://") || u.startsWith("ipfs://");
            if (u.isEmpty() || !okScheme || hasBreakers(u)) {
                status(nStatus, "Enter a plain image URL (https or ipfs, no spaces or quotes).", false); return;
            }
            imageValue = u;
        }
        nStatus.setVisibility(View.GONE);

        JSONObject json = new JSONObject();
        try {
            json.put("name", name);
            json.put("url", imageValue);
            if (!desc.isEmpty()) json.put("description", desc);
            if (!creator.isEmpty()) json.put("owner", creator);
            if (!extUrl.isEmpty()) json.put("external_url", extUrl);
            if (!webv.isEmpty()) json.put("webvalidate", webv);
            json.put("nft", "true");
        } catch (Exception ignored) {}

        final StringBuilder cmd = new StringBuilder("tokencreate name:").append(json)
                .append(" amount:").append(editions)
                .append(" decimals:0");
        if (!burn.isEmpty() && positive(burn)) cmd.append(" burn:").append(burn);
        if (!webv.isEmpty()) cmd.append(" webvalidate:").append(webv);

        LinearLayout body = confirmBody();
        if (nftEmbedMode) {
            ImageView iv = new ImageView(act);
            LinearLayout.LayoutParams ivlp = new LinearLayout.LayoutParams(dp(110), dp(110));
            ivlp.gravity = Gravity.CENTER_HORIZONTAL; ivlp.bottomMargin = dp(8);
            iv.setLayoutParams(ivlp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ImageLoader.loadOver(act, ImageTools.dataUri(nftImageB64), iv, null);
            body.addView(iv, 0);
        }
        addConfirmRow(body, "Name", name);
        addConfirmRow(body, "Total to mint", String.valueOf(editions));
        addConfirmRow(body, "Image", nftEmbedMode ? "embedded on-chain (" + nftImageB64.length() + " b64)" : imageValue);
        addConfirmRow(body, "Description", desc.isEmpty() ? "Not set" : desc);
        addConfirmRow(body, "Creator", creator.isEmpty() ? "Not set" : creator);
        addConfirmRow(body, "External URL", extUrl.isEmpty() ? "None set" : extUrl);
        addConfirmRow(body, "Web validation", webv.isEmpty() ? "None set" : webv);
        addConfirmRow(body, "Creator signature", "yes — always (signtoken)");
        if (!burn.isEmpty() && positive(burn)) addConfirmRow(body, "Burn", burn + " MINIMA");

        // Multi-edition NFTs split like any token, and every split output carries the
        // full record — a signed 9K artimage record passes the eye but seals past the
        // split bound. Exact check; the fix is theirs to choose.
        {
            // ALWAYS SIGNED: the record with the 8.4KB signature must split
            // (editions>1) and send. Refuse with the numbers; the fix is a
            // smaller image or fewer editions — never an unsigned mint.
            int metaLen = json.toString().length();
            int bound = editions > 1 ? StateNft.META_MAX
                    : (StateNft.TRANSFER_PAIR_BUDGET - StateNft.DEF_WRAPPER - StateNft.DEF_SIGN_WEIGHT);
            if (metaLen > bound) {
                status(nStatus, "Signed, this record is " + (metaLen + StateNft.DEF_WRAPPER
                        + StateNft.DEF_SIGN_WEIGHT) + "B — past the limit for "
                        + (editions > 1 ? editions + " editions (must split)" : "a sendable record")
                        + ". Use a smaller image or shorter text.", false);
                return;
            }
        }
        showConfirm("Mint this NFT?", body, "Mint NFT →", () -> {
            // ALWAYS SIGNED — no key, no mint
            act.node().cmd("getaddress", new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {
                    JSONObject r = json.optJSONObject("response");
                    String pk = r == null ? "" : r.optString("publickey", "");
                    if (pk.isEmpty()) { status(nStatus, "Could not fetch the signing key — node offline?", false); return; }
                    cmd.append(" signtoken:").append(pk);
                    runMint(cmd.toString(), nStatus, "NFT");
                }
                @Override public void onError(String message) { status(nStatus, "Could not fetch the signing key: " + message, false); }
            });
        });
    }

    // ===================== STATE NFT COLLECTION =====================

    private View collectionForm() {
        if (colForm != null) return detachedForReuse(colForm);
        colForm = new LinearLayout(act);
        colForm.setOrientation(LinearLayout.VERTICAL);

        colForm.addView(backRow("New State NFT collection"));

        TextView intro = new TextView(act);
        intro.setText("A locked edition: one tokenid, each coin carrying a sealed identity. Once an "
                + "item is stamped, nobody — you included — can alter it.");
        intro.setTextColor(Design.dim());
        intro.setTextSize(12f);
        colForm.addView(intro);

        cName = addField(colForm, "Collection name *", "SolarPunks", InputType.TYPE_CLASS_TEXT);
        LinearLayout duo = new LinearLayout(act);
        duo.setOrientation(LinearLayout.HORIZONTAL);
        colForm.addView(duo);
        cSize = addWeightedField(duo, "Items (2–20) *", "5", InputType.TYPE_CLASS_NUMBER, 1f);
        cExt = addWeightedField(duo, "Extension (url mode)", ".png", InputType.TYPE_CLASS_TEXT, 1.4f);
        cDesc = addField(colForm, "Description", "What is this collection?", InputType.TYPE_CLASS_TEXT);

        // mode segment
        TextView modeLbl = new TextView(act);
        modeLbl.setText("Item images");
        modeLbl.setTextColor(Design.dim());
        modeLbl.setTextSize(12f);
        modeLbl.setPadding(0, dp(10), 0, dp(4));
        colForm.addView(modeLbl);
        LinearLayout seg = new LinearLayout(act);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        cModeEmbed = segBtn("EMBED ON-CHAIN", v -> setColMode(true));
        cModeUrl = segBtn("URL BASE + INDEX", v -> setColMode(false));
        seg.addView(cModeEmbed, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        seg.addView(cModeUrl, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        colForm.addView(seg);

        cUrlBlock = new LinearLayout(act);
        cUrlBlock.setOrientation(LinearLayout.VERTICAL);
        colForm.addView(cUrlBlock);
        cBase = addField(cUrlBlock, "Image base URL (item = base + index + ext)", "https://mysite.com/nft/", InputType.TYPE_TEXT_VARIATION_URI);
        hostButton(cUrlBlock, "Upload the item images to my node (in order)…", v -> act.pickImages(uris -> {
            status(cStatus, "Hosting " + uris.size() + " item image(s) on your node…", true);
            NodeHost.uploadCollection(act, uris, new NodeHost.CollectionCb() {
                @Override public void done(String base, String ext, int count) {
                    if (base.isEmpty()) {
                        status(cStatus, "Hosted on the node, but it has no public web address set "
                                + "(-Dparlons.node.public) - ask the operator.", false);
                        return;
                    }
                    cBase.setText(base);
                    cExt.setText(ext);
                    if (cSize.getText().toString().trim().isEmpty()) cSize.setText(String.valueOf(count));
                    status(cStatus, "Hosted " + count + " item(s) on your node ✓ base " + base + " ext " + ext
                            + " - item N = base + N + ext (1-based, matching the stamp index)", true);
                }
                @Override public void fail(String why) { status(cStatus, "Hosting failed: " + why, false); }
                @Override public void progress(String note) { status(cStatus, note, true); }
            });
        }));

        cImagesBlock = new LinearLayout(act);
        cImagesBlock.setOrientation(LinearLayout.VERTICAL);
        colForm.addView(cImagesBlock);
        cImagesNote = new TextView(act);
        cImagesNote.setTextColor(Design.dim());
        cImagesNote.setTextSize(11f);
        cImagesNote.setPadding(0, dp(8), 0, dp(4));
        cImagesNote.setText("Each item's image is compressed to ≤" + STATE_IMG_BUDGET
                + " base64 chars and sealed into its coin. Set the item count, then pick each image.");
        cImagesBlock.addView(cImagesNote);
        TextView pickAll = new TextView(act);
        pickAll.setText("＋ Pick images for the empty slots");
        pickAll.setTextColor(Design.accent());
        pickAll.setTextSize(13f);
        pickAll.setTypeface(Design.typefaceBold());
        pickAll.setPadding(0, dp(6), 0, dp(8));
        pickAll.setOnClickListener(v -> {
            int size = parsedSize();
            if (size < 2 || size > 20) { status(cStatus, "Set the item count (2–20) first.", false); return; }
            if (cImageSlots.getChildCount() == 0) buildImageSlots();
            int first = 1;
            for (int i = 1; i <= size; i++) if (!colImages.containsKey(i)) { first = i; break; }
            pickCollectionImages(first);
        });
        cImagesBlock.addView(pickAll);

        cImageSlots = new LinearLayout(act);
        cImageSlots.setOrientation(LinearLayout.VERTICAL);
        cImagesBlock.addView(cImageSlots);
        TextView buildSlots = new TextView(act);
        buildSlots.setText("⟳ Build image slots from the item count");
        buildSlots.setTextColor(Design.accent());
        buildSlots.setTextSize(12f);
        buildSlots.setPadding(0, dp(4), 0, dp(4));
        buildSlots.setOnClickListener(v -> buildImageSlots());
        cImagesBlock.addView(buildSlots);

        // ---- collection icon (what the regular wallet shows for this token) ----
        TextView iconLbl = new TextView(act);
        iconLbl.setText("Collection icon");
        iconLbl.setTextColor(Design.dim());
        iconLbl.setTextSize(12f);
        iconLbl.setPadding(0, dp(14), 0, dp(4));
        colForm.addView(iconLbl);

        LinearLayout iconRow = new LinearLayout(act);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        colForm.addView(iconRow);

        cIconPreview = new ImageView(act);
        LinearLayout.LayoutParams iplp = new LinearLayout.LayoutParams(dp(56), dp(56));
        iplp.rightMargin = dp(10);
        cIconPreview.setLayoutParams(iplp);
        cIconPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cIconPreview.setBackgroundColor(Design.surface2());
        cIconPreview.setOnClickListener(v -> { if (colIconEmbed) pickCollectionIcon(); });
        iconRow.addView(cIconPreview);

        LinearLayout iconCol = new LinearLayout(act);
        iconCol.setOrientation(LinearLayout.VERTICAL);
        iconRow.addView(iconCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout iconSeg = new LinearLayout(act);
        iconSeg.setOrientation(LinearLayout.HORIZONTAL);
        cIconModeEmbed = segBtn("EMBED", v -> setIconMode(true));
        cIconModeUrl = segBtn("URL", v -> setIconMode(false));
        iconSeg.addView(cIconModeEmbed, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        iconSeg.addView(cIconModeUrl, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        iconCol.addView(iconSeg);

        cIconNote = new TextView(act);
        cIconNote.setTextColor(Design.dim());
        cIconNote.setTextSize(10f);
        cIconNote.setPadding(0, dp(5), 0, 0);
        iconCol.addView(cIconNote);

        cIconUrlBlock = new LinearLayout(act);
        cIconUrlBlock.setOrientation(LinearLayout.VERTICAL);
        colForm.addView(cIconUrlBlock);
        cIconUrl = addField(cIconUrlBlock, "Icon URL", "https://…", InputType.TYPE_TEXT_VARIATION_URI);
        hostButton(cIconUrlBlock, "Upload the icon to my node instead…", v -> act.pickImage(uri -> {
            status(cStatus, "Uploading the icon to your node…", true);
            NodeHost.upload(act, uri, "", 0, new NodeHost.Cb() {
                @Override public void done(String url, String path, String sha256) {
                    if (url.isEmpty()) {
                        status(cStatus, "Hosted as " + path + " but the node has no public web address set.", false);
                        return;
                    }
                    cIconUrl.setText(url);
                    status(cStatus, "Icon hosted on your node ✓ " + url, true);
                }
                @Override public void fail(String why) { status(cStatus, "Hosting failed: " + why, false); }
                @Override public void progress(String note) { status(cStatus, note, true); }
            });
        }));
        cExtUrl = addField(colForm, "External URL (optional)", "https://…", InputType.TYPE_TEXT_VARIATION_URI);
        cWebv = addField(colForm, "Web validation URL (optional)", "https://…/collection.txt", InputType.TYPE_TEXT_VARIATION_URI);

        colForm.addView(primaryButton("Review & start mint", v -> onReviewCollection()));
        cStatus = statusLine();
        colForm.addView(cStatus);

        setColMode(true);
        setIconMode(true);
        return colForm;
    }

    /**
     * The collection icon is what the ordinary wallet shows for this token. It is written into the
     * token metadata by {@code tokencreate} and is therefore FIXED FOREVER at mint time — which is
     * why the default (item #1) has to be resolved before minting starts, not looked up later.
     */
    private void setIconMode(boolean embed) {
        colIconEmbed = embed;
        cIconModeEmbed.setTextColor(embed ? Design.onAccent() : Design.dim());
        cIconModeEmbed.setBackgroundColor(embed ? Design.accent() : Design.surface2());
        cIconModeUrl.setTextColor(!embed ? Design.onAccent() : Design.dim());
        cIconModeUrl.setBackgroundColor(!embed ? Design.accent() : Design.surface2());
        cIconUrlBlock.setVisibility(embed ? View.GONE : View.VISIBLE);
        refreshIconPreview();
    }

    /**
     * Shrink item #1 to the icon budget ahead of time, in the background.
     *
     * The token record travels with every transaction, so the icon must be small — but doing the
     * decode-and-re-encode when the user taps "Review & start mint" froze the UI for about a
     * second. Prepare it the moment slot 1 is filled instead.
     */
    private void prepareIconDefault(final String first) {
        if (first == null || first.isEmpty()) { colIconDefault = ""; colIconDefaultSource = ""; return; }
        if (!colIconB64.isEmpty()) return;                       // a chosen icon wins
        if (first.equals(colIconDefaultSource)) return;          // already done for this image
        colIconDefaultSource = first;
        // Drop the value derived from the PREVIOUS image before starting the new shrink. Without
        // this, swapping slot 1 left the old shrunk copy in place for the second or so the new one
        // takes — the preview showed the old image, and confirming in that window sealed the old
        // image into the token record, which is immutable and unfixable after creation.
        colIconDefault = "";
        new Thread(() -> {
            final String shrunk = ImageTools.recompressBase64(first, ICON_BUDGET);
            act.runOnUiThread(() -> {
                if (!first.equals(colIconDefaultSource)) return;  // slot 1 changed meanwhile
                colIconDefault = shrunk;
                refreshIconPreview();
            });
        }).start();
    }

    private void pickCollectionIcon() {
        act.pickImage(uri -> {
            cIconNote.setText("Compressing…");
            new Thread(() -> {
                final String result = sealFrom(uri, ICON_BUDGET);
                act.runOnUiThread(() -> {
                    if (result.isEmpty()) { cIconNote.setText("Could not read that image — try another, or a smaller one."); return; }
                    colIconB64 = result;
                    refreshIconPreview();
                });
            }).start();
        });
    }

    /** Show whichever icon will actually be minted — chosen, or the item #1 fallback. */
    private void refreshIconPreview() {
        if (!colIconEmbed) {
            cIconPreview.setImageBitmap(null);
            cIconNote.setText("The wallet loads the icon from this URL.");
            return;
        }
        if (!colIconB64.isEmpty()) {
            ImageLoader.loadOver(act, ImageTools.dataUri(colIconB64), cIconPreview, null);
            cIconNote.setText("Embedded on-chain · " + colIconB64.length() + " b64 · tap to change");
            return;
        }
        String first = colImages.get(1);
        if (first != null) {
            prepareIconDefault(first);
            // Preview the SHRUNK bytes once they exist, so what you see is what gets sealed.
            String show = colIconDefault.isEmpty() ? first : colIconDefault;
            ImageLoader.loadOver(act, ImageTools.dataUri(show), cIconPreview, null);
            cIconNote.setText(colIconDefault.isEmpty()
                    ? "Using item #1 by default · preparing a small copy for the token record…"
                    : "Using item #1, shrunk to " + colIconDefault.length()
                      + " b64 for the token record · tap to choose a different icon");
        } else {
            cIconPreview.setImageBitmap(null);
            cIconNote.setText("Defaults to item #1's image · tap to choose a different one");
        }
    }

    private void setColMode(boolean embed) {
        colEmbedMode = embed;
        cModeEmbed.setTextColor(embed ? Design.onAccent() : Design.dim());
        cModeEmbed.setBackgroundColor(embed ? Design.accent() : Design.surface2());
        cModeUrl.setTextColor(!embed ? Design.onAccent() : Design.dim());
        cModeUrl.setBackgroundColor(!embed ? Design.accent() : Design.surface2());
        cUrlBlock.setVisibility(embed ? View.GONE : View.VISIBLE);
        cImagesBlock.setVisibility(embed ? View.VISIBLE : View.GONE);
    }

    private int parsedSize() {
        try { return Integer.parseInt(cSize.getText().toString().trim()); }
        catch (Exception e) { return 0; }
    }

    private void buildImageSlots() {
        int size = parsedSize();
        cImageSlots.removeAllViews();
        if (size < 2 || size > 20) {
            status(cStatus, "Items must be 2–20 before building slots.", false);
            return;
        }
        cStatus.setVisibility(View.GONE);
        for (int i = 1; i <= size; i++) {
            final int idx = i;
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(8), dp(6), dp(8), dp(6));
            r.setBackgroundColor(Design.surface());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            r.setLayoutParams(lp);
            // Thumbnail of the actual bytes that will be sealed on-chain — the only way to catch a
            // wrong or sideways image BEFORE it is stamped, which cannot be undone.
            ImageView thumb = new ImageView(act);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(44), dp(44));
            tlp.rightMargin = dp(10);
            thumb.setLayoutParams(tlp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(Design.surface2());
            boolean have = colImages.containsKey(idx);
            if (have) ImageLoader.loadOver(act, ImageTools.dataUri(colImages.get(idx)), thumb, null);
            else thumb.setImageBitmap(null);
            r.addView(thumb);

            TextView n = new TextView(act);
            n.setText("Item #" + idx);
            n.setTextColor(Design.text());
            n.setTextSize(13f);
            r.addView(n, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final TextView state = new TextView(act);
            state.setText(have ? "✓ " + colImages.get(idx).length() + " b64" : "PICK IMAGE");
            state.setTextColor(have ? Design.success() : Design.accent());
            state.setTextSize(11f);
            state.setLetterSpacing(0.05f);
            r.addView(state);
            r.setOnClickListener(v -> pickCollectionImages(idx));
            cImageSlots.addView(r);
        }
        updateSlotSummary();
        if (cIconPreview != null) refreshIconPreview();   // the item-#1 default may have just changed
    }

    /** Live count under the slot list so you can see what's still outstanding. */
    private void updateSlotSummary() {
        int size = parsedSize();
        int filled = 0;
        for (int i = 1; i <= size; i++) if (colImages.containsKey(i)) filled++;
        cImagesNote.setText(filled == size
                ? "All " + size + " images ready."
                : filled + " of " + size + " images ready — tap any slot (or “Pick images”) and "
                  + "select as many as you like; they fill the empty slots in order.");
    }

    /**
     * Pick one or many images starting at {@code startIdx}. The first selection lands on that slot
     * (replacing it if it was already filled — tapping a filled slot is how you redo one), and any
     * further selections wrap forward through the collection filling only the EMPTY slots, so a
     * part-finished collection can be completed in a single pass.
     */
    private void pickCollectionImages(final int startIdx) {
        act.pickImages(uris -> {
            int size = parsedSize();
            if (size < 2 || size > 20) { status(cStatus, "Items must be 2–20.", false); return; }
            java.util.List<Integer> targets = fillOrder(startIdx, size, uris.size(), colImages.keySet());
            compressInto(uris, targets, (idx, b64) -> colImages.put(idx, b64),
                    msg -> cImagesNote.setText(msg),
                    () -> { buildImageSlots(); reportFill(targets.size(), uris.size(), size); });
        });
    }

    /**
     * Which slots a multi-selection should fill: the tapped slot first, then forward from it,
     * wrapping around, skipping slots that already hold an image. Never longer than the selection.
     */
    private java.util.List<Integer> fillOrder(int startIdx, int size, int count, java.util.Set<Integer> taken) {
        java.util.List<Integer> out = new ArrayList<>();
        if (count <= 0) return out;
        out.add(startIdx);                                   // the slot you tapped, filled or not
        for (int step = 1; step < size && out.size() < count; step++) {
            int idx = ((startIdx - 1 + step) % size) + 1;
            if (!taken.contains(idx)) out.add(idx);
        }
        return out;
    }

    /** Compress each picked image onto its target slot off the UI thread, reporting progress. */
    private void compressInto(final java.util.List<android.net.Uri> uris,
                              final java.util.List<Integer> targets,
                              final java.util.function.BiConsumer<Integer, String> assign,
                              final java.util.function.Consumer<String> progress,
                              final Runnable onDone) {
        final int n = Math.min(uris.size(), targets.size());
        if (n == 0) { onDone.run(); return; }
        progress.accept("Compressing 1 / " + n + "…");
        new Thread(() -> {
            for (int i = 0; i < n; i++) {
                final String result = sealFrom(uris.get(i), STATE_IMG_BUDGET);
                final int slot = targets.get(i);
                final int shown = i + 1;
                // Assign on the UI thread so the image map stays single-threaded.
                act.runOnUiThread(() -> {
                    if (!result.isEmpty()) assign.accept(slot, result);
                    if (shown < n) progress.accept("Compressing " + (shown + 1) + " / " + n + "…");
                });
            }
            act.runOnUiThread(onDone);
        }).start();
    }

    /**
     * Turn a picked file into the base64 that will be sealed on-chain.
     *
     * Vector art takes its own lane: an SVG is sanitized and stored as text, keeping it sharp at
     * any size, where pushing it through the raster ladder would flatten it to pixels. Everything
     * else goes through the WebP search. Returns "" when it cannot be read or will not fit.
     */
    private String sealFrom(android.net.Uri uri, int budget) {
        try {
            if (ImageTools.isSvgUri(act, uri)) return ImageTools.svgBase64FromUri(act, uri, budget);
            return ImageTools.compressUri(act, uri, budget);
        } catch (Exception e) {
            return "";
        }
    }

    /** Tell the user what the batch actually did — including leftovers or shortfall. */
    private void reportFill(int targetCount, int picked, int size) {
        int filled = 0;
        for (int i = 1; i <= size; i++) if (colImages.containsKey(i)) filled++;
        if (picked > targetCount) {
            Toast.makeText(act, "Filled " + targetCount + " slot(s) — " + (picked - targetCount)
                    + " image(s) left over, there were no empty slots for them.", Toast.LENGTH_LONG).show();
        } else if (filled < size) {
            Toast.makeText(act, filled + " of " + size + " slots filled — pick "
                    + (size - filled) + " more.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(act, "All " + size + " images ready.", Toast.LENGTH_SHORT).show();
        }
    }

    private void onReviewCollection() {
        final String name = cName.getText().toString().trim();
        String err = badValue(name, true);
        if (err != null) { status(cStatus, "Name: " + err, false); return; }

        final int size = parsedSize();
        if (size < 2 || size > 20) { status(cStatus, "Items must be between 2 and 20.", false); return; }

        final String desc = cDesc.getText().toString().trim();
        if ((err = badValue(desc, false)) != null) { status(cStatus, "Description: " + err, false); return; }

        final String base = cBase.getText().toString().trim();
        final String ext = cExt.getText().toString().trim().isEmpty() ? ".png" : cExt.getText().toString().trim();
        if (!colEmbedMode) {
            if (base.isEmpty() || badUrl(base)) { status(cStatus, "URL mode needs a plain https base URL.", false); return; }
            if (badValue(ext, false) != null || ext.contains(" ")) { status(cStatus, "Bad extension.", false); return; }
        } else {
            for (int i = 1; i <= size; i++) {
                if (!colImages.containsKey(i)) {
                    status(cStatus, "Item #" + i + " has no image yet — build slots and pick all "
                            + size + " images first.", false);
                    return;
                }
            }
        }
        // Resolve the icon NOW: tokencreate bakes it in permanently.
        final String iconUrl = cIconUrl.getText().toString().trim();
        final String icon;
        final String iconDesc;
        if (colIconEmbed) {
            if (!colIconB64.isEmpty()) {
                icon = colIconB64;
                iconDesc = "embedded on-chain (" + colIconB64.length() + " b64)";
            } else if (colIconDefault != null && !colIconDefault.isEmpty()) {
                icon = colIconDefault;                      // already shrunk, off the UI thread
                iconDesc = "item #1's image, shrunk to " + icon.length() + " b64 for the token record";
            } else if (colImages.containsKey(1)) {
                // Mirror item #1 — but SHRUNK to the icon budget first.
                //
                // The token's metadata is carried in every transaction that touches the token, not
                // once on the chain. Sealing a full-size state plate (up to STATE_IMG_BUDGET) as
                // the icon made the very first MOVE 80KB against the 64KB TxPoW cap, and since
                // metadata is immutable the whole collection was bricked on creation.
                icon = ImageTools.recompressBase64(colImages.get(1), ICON_BUDGET);
                iconDesc = icon.isEmpty()
                        ? "None — item #1 could not be shrunk to fit the icon budget"
                        : "item #1's image, shrunk to " + icon.length() + " b64 for the token record";
            } else if (!colEmbedMode && !base.isEmpty()) {
                icon = base + 1 + ext;                          // url-mode collections: item #1's URL
                iconDesc = icon;
            } else {
                icon = "";
                iconDesc = "None — the wallet will show a generated identicon";
            }
        } else {
            if (!iconUrl.isEmpty() && badUrl(iconUrl)) { status(cStatus, "Icon URL must be a plain http(s) link.", false); return; }
            icon = iconUrl;
            iconDesc = iconUrl.isEmpty() ? "None — the wallet will show a generated identicon" : iconUrl;
        }
        // Last line of defence: an oversized icon bricks the collection permanently, so refuse to
        // start rather than seal one. Only embedded icons can be oversized; URLs are tiny.
        if (colIconEmbed && !icon.isEmpty() && icon.length() > ICON_BUDGET) {
            status(cStatus, "That icon is too large (" + icon.length() + " of " + ICON_BUDGET
                    + " chars). The token record travels with every transaction, so a big icon "
                    + "would make the collection unusable. Pick a simpler icon.", false);
            return;
        }
        final String extUrl = cExtUrl.getText().toString().trim();
        if (!extUrl.isEmpty() && badUrl(extUrl)) { status(cStatus, "External URL must be a plain http(s) link.", false); return; }
        // Whole-definition guard, EXACT (the Atelier joint model): the record the engine
        // will seal plus the largest picked image must clear the transfer budget, and the
        // engine may still have to drop the creator signature. Slim the icon first; refuse
        // rather than seal an unsplittable token. The engine re-checks with the true
        // weights before tokencreate regardless — this is the friendly early warning.
        String iconGuarded = icon;
        String iconDescGuarded = iconDesc;
        {
            int maxImg = 0;
            for (String img : colImages.values()) maxImg = Math.max(maxImg, img.length());
            // ALWAYS SIGNED: fit the icon into the envelope — slim, then drop,
            // then refuse with the numbers. Never an unsigned mint.
            String g = StateNft.jointGate(colDefMetaLen(name, desc, extUrl, iconGuarded), maxImg);
            if (!"sign".equals(g) && colIconEmbed && !iconGuarded.isEmpty()) {
                String slim = ImageTools.recompressBase64(iconGuarded, 2000);
                if (!slim.isEmpty()
                        && "sign".equals(StateNft.jointGate(colDefMetaLen(name, desc, extUrl, slim), maxImg))) {
                    iconGuarded = slim;
                    iconDescGuarded = "embedded (slimmed so every item signs and travels)";
                    g = "sign";
                } else if ("sign".equals(StateNft.jointGate(colDefMetaLen(name, desc, extUrl, ""), maxImg))) {
                    iconGuarded = "";
                    iconDescGuarded = "none — dropped so every item signs (wallet shows the identicon)";
                    g = "sign";
                }
            }
            if (!"sign".equals(g)) { status(cStatus, g, false); return; }
        }
        final String iconF = iconGuarded;
        final String iconDescF = iconDescGuarded;
        final String webv = cWebv.getText().toString().trim();
        if (!webv.isEmpty() && badUrl(webv)) { status(cStatus, "Web validation must be a plain https link.", false); return; }
        cStatus.setVisibility(View.GONE);

        LinearLayout body = confirmBody();
        addConfirmRow(body, "Collection", name);
        addConfirmRow(body, "Items", String.valueOf(size));
        addConfirmRow(body, "Mode", colEmbedMode ? "embedded on-chain images" : "URL base + index");
        if (!colEmbedMode) addConfirmRow(body, "Images at", base + "<i>" + ext);
        addConfirmRow(body, "Description", desc.isEmpty() ? "Not set" : desc);
        addConfirmRow(body, "Web validation", webv.isEmpty() ? "None set" : webv);
        addConfirmRow(body, "Wallet icon", iconDescF);
        addConfirmRow(body, "Contract", "SAMESTATE locked edition — immutable once stamped");
        TextView pipeline = new TextView(act);
        pipeline.setText("The mint runs in phases (CREATE → MOVE → SPLIT → STAMP), one transaction "
                + "per step, driven by new blocks. It is resumable — you can close the app.");
        pipeline.setTextColor(Design.dim());
        pipeline.setTextSize(11f);
        pipeline.setPadding(0, dp(8), 0, 0);
        body.addView(pipeline);

        showConfirm("Start minting this collection?", body, "Start mint →", () ->
                startCollection(name, size, desc, base, ext, iconF, extUrl, webv));
    }

    /** Exact metadata length the engine will seal for these collection fields. */
    private int colDefMetaLen(String name, String desc, String extUrl, String icon) {
        StateNft.Meta m = new StateNft.Meta();
        m.name = name;
        m.description = desc;
        m.mode = colEmbedMode ? "embed" : "url";
        m.icon = icon;
        m.externalUrl = extUrl;
        return StateNft.tokenMetadata(m).toString().length();
    }

    /** Fetch the creator identity, persist the LocalStore row, kick the engine, show progress. */
    private void startCollection(final String name, final int size, final String desc,
                                 final String base, final String ext, final String iconUrl,
                                 final String extUrl, final String webv) {
        status(cStatus, "Fetching creator identity…", true);
        act.node().cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                String pk = r == null ? "" : r.optString("publickey", "");
                String addr = r == null ? "" : r.optString("address", "");
                if (pk.isEmpty() || addr.isEmpty()) {
                    status(cStatus, "Could not get a creator key from the node.", false);
                    return;
                }
                StateNft.Meta m = new StateNft.Meta();
                m.localId = LocalStore.nextId(act);
                m.name = name;
                m.description = desc;
                m.mode = colEmbedMode ? "embed" : "url";
                m.size = size;
                m.base = colEmbedMode ? "" : base;
                m.ext = ext;
                m.icon = iconUrl;
                m.externalUrl = extUrl;
                m.webvalidate = webv;
                m.creatorPk = pk;
                m.creatorAddr = addr;
                m.tokenid = "";
                m.phase = "CREATE";
                m.error = "";

                org.json.JSONArray items = new org.json.JSONArray();
                for (int i = 1; i <= size; i++) {
                    JSONObject it = new JSONObject();
                    try {
                        it.put("idx", i);
                        it.put("image", colEmbedMode ? colImages.get(i) : "");
                    } catch (Exception ignored) {}
                    items.put(it);
                }
                JSONObject row = MintEngine.rowFromMeta(m, items);
                LocalStore.upsert(act, row);
                colImages.clear();
                colIconB64 = "";
                colIconDefault = "";
                colIconDefaultSource = "";
                colForm = null;                    // fresh form next time
                progressRowId = m.localId;
                screen = Screen.PROGRESS;
                refresh();
                act.mintEngineTick();
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "Enable this wallet in Minima Core → Apps first.";
                status(cStatus, "Failed: " + message, false);
            }
        });
    }

    // ===================== PROGRESS =====================

    private void renderProgress() {
        JSONObject row = LocalStore.findById(act, progressRowId);
        if (row == null) { screen = Screen.HUB; renderHub(); return; }

        TextView back = new TextView(act);
        back.setText("‹  " + row.optString("name", "Collection"));
        back.setTextColor(Design.heading());
        back.setTextSize(16f);
        back.setTypeface(Design.typefaceBold());
        back.setPadding(0, 0, 0, dp(8));
        back.setOnClickListener(v -> { screen = Screen.HUB; refresh(); });
        container.addView(back);

        // phase chips
        String phase = row.optString("phase", "?");
        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[] phases = {"CREATE", "MOVE", "SPLIT", "STAMP", "DONE"};
        int cur = java.util.Arrays.asList(phases).indexOf(phase);
        for (int i = 0; i < phases.length; i++) {
            TextView c = new TextView(act);
            c.setText(phases[i]);
            c.setTextSize(9f);
            c.setLetterSpacing(0.06f);
            c.setTypeface(Design.typefaceBold());
            c.setPadding(dp(8), dp(5), dp(8), dp(5));
            boolean done = cur > i || "DONE".equals(phase);
            boolean active = cur == i && !"DONE".equals(phase);
            c.setTextColor(active ? Design.onAccent() : done ? Design.success() : Design.dim());
            c.setBackgroundColor(active ? Design.accent() : Design.surface());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) clp.leftMargin = dp(4);
            c.setLayoutParams(clp);
            chips.addView(c);
        }
        container.addView(chips);

        if ("NEEDIMAGES".equals(phase)) {
            TextView warn = new TextView(act);
            warn.setText("Stamping paused — an item is missing its image. Pick it below; stamping is "
                    + "irreversible, so the engine refuses to stamp an imageless item.");
            warn.setTextColor(Design.amber());
            warn.setTextSize(12f);
            warn.setPadding(0, dp(8), 0, 0);
            container.addView(warn);
        }
        if (MintDriver.isStuck(row)) {
            TextView stuck = new TextView(act);
            stuck.setText("This collection cannot be completed.\n\nIts transactions exceed the "
                    + "64KB chain limit, and the token record that causes it was fixed when the "
                    + "token was created — it cannot be edited or shrunk. Nothing further will be "
                    + "attempted.\n\nBury it below to clear it, or leave it: the coins are inert.");
            stuck.setTextColor(Design.red());
            stuck.setTextSize(12f);
            stuck.setPadding(0, dp(10), 0, dp(4));
            container.addView(stuck);
        }
        String err = row.optString("error", "");
        if (!err.isEmpty() && !"NEEDIMAGES".equals(phase)) {
            TextView e = new TextView(act);
            e.setText(err);
            e.setTextColor(Design.red());
            e.setTextSize(11f);
            e.setPadding(0, dp(6), 0, 0);
            container.addView(e);
        }
        String engineMsg = act.mintStatus();
        if (!engineMsg.isEmpty()) {
            TextView s = new TextView(act);
            s.setText("engine: " + engineMsg);
            s.setTextColor(Design.dim());
            s.setTextSize(10f);
            s.setPadding(0, dp(4), 0, 0);
            container.addView(s);
        }

        // token id, once known
        String tokenid = row.optString("tokenid", "");
        if (!tokenid.isEmpty()) {
            TextView tid = new TextView(act);
            tid.setText(Util.shorten(tokenid) + "  ·  tap to copy");
            tid.setTextColor(Design.dim());
            tid.setTextSize(11f);
            tid.setTypeface(android.graphics.Typeface.MONOSPACE);
            tid.setPadding(0, dp(6), 0, 0);
            tid.setOnClickListener(v -> CoinDetailDialog.copy(act, "Token ID", tokenid));
            container.addView(tid);
        }

        // item rows
        org.json.JSONArray items = MintEngine.localItems(row);
        TextView lbl = new TextView(act);
        lbl.setText("ITEMS");
        lbl.setTextColor(Design.dim());
        lbl.setTextSize(11f);
        lbl.setLetterSpacing(0.1f);
        lbl.setPadding(0, dp(12), 0, dp(4));
        container.addView(lbl);
        for (int i = 0; i < items.length(); i++) {
            final JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            final int idx = it.optInt("idx");
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(10), dp(8), dp(10), dp(8));
            r.setBackgroundColor(Design.surface());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(3);
            r.setLayoutParams(lp);
            TextView n = new TextView(act);
            String coinid = it.optString("coinid", "");
            n.setText("#" + idx + (coinid.isEmpty() ? "" : "  ·  " + Util.shorten(coinid)));
            n.setTextColor(Design.text());
            n.setTextSize(12f);
            n.setTypeface(android.graphics.Typeface.MONOSPACE);
            r.addView(n, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final TextView st = new TextView(act);
            boolean needImage = "embed".equals(row.optString("mode")) && it.optString("image", "").isEmpty();
            st.setText(!coinid.isEmpty() ? "✓ STAMPED" : needImage ? "PICK IMAGE" : "queued");
            st.setTextSize(10f);
            st.setLetterSpacing(0.06f);
            st.setTextColor(!coinid.isEmpty() ? Design.success() : needImage ? Design.accent() : Design.dim());
            r.addView(st);
            if (needImage) {
                r.setOnClickListener(v -> pickProgressImages(row, idx));
            }
            container.addView(r);
        }

        // Parked create: the engine refuses to re-post on its own because a duplicate tokencreate
        // means a second identical collection that can never be merged away.
        if (row.optInt("createstuck", 0) == 1) {
            TextView repost = new TextView(act);
            repost.setText("↻  Re-post the create transaction");
            repost.setTextColor(Design.amber());
            repost.setTextSize(13f);
            repost.setTypeface(Design.typefaceBold());
            repost.setPadding(0, dp(14), 0, dp(4));
            repost.setOnClickListener(v -> Sheet.create(act, "Re-post the create?")
                .subtitle("Only do this if the first create was definitely lost. If it later "
                            + "confirms too, you end up with TWO identical collections and no way to "
                            + "merge them — you would have to bury one.")
                .action("Wait longer", Sheet.Style.SECONDARY, null)
                .action("Re-post", Sheet.Style.PRIMARY, () -> {
                        JSONObject fresh = LocalStore.findById(act, progressRowId);
                        if (fresh != null) {
                            try { fresh.put("posted", 0); fresh.put("createstuck", 0); fresh.put("error", ""); }
                            catch (Exception ignored) {}
                            LocalStore.upsert(act, fresh);
                        }
                        refresh();
                        act.mintEngineTick();
                    }).show());
            container.addView(repost);
        }

        // Escape hatch: a locked edition can never be edited, only destroyed. Available in every
        // phase — a mint that went wrong (bad image, wrong index) is exactly when it's needed.
        if (!tokenid.isEmpty()) {
            TextView buryAll = new TextView(act);
            buryAll.setText("✝  Bury this entire collection");
            buryAll.setTextColor(Design.red());
            buryAll.setTextSize(13f);
            buryAll.setTypeface(Design.typefaceBold());
            buryAll.setPadding(0, dp(18), 0, dp(6));
            buryAll.setOnClickListener(v -> StateNftActions.buryCollectionDialog(
                    act, tokenid, row.optString("name", "Collection"), row.optString("creatorpk", ""),
                    () -> {
                        JSONObject fresh = LocalStore.findById(act, progressRowId);
                        if (fresh != null) {
                            try { fresh.put("phase", "BURIED"); fresh.put("error", ""); }
                            catch (Exception ignored) {}
                            LocalStore.upsert(act, fresh);   // stops the mint engine touching it
                        }
                        refresh();
                    }));
            container.addView(buryAll);
        }

        if (!"DONE".equals(phase) && !MintDriver.isStuck(row)) {
            container.addView(primaryButton("Resume now", v -> {
                MintDriver.Result r = act.mintEngineTick();
                Toast.makeText(act, MintDriver.explain(r), Toast.LENGTH_SHORT).show();
                if (r == MintDriver.Result.NEEDS_IMAGES) {
                    // Don't pretend to nudge: it's waiting on the user, so take them to the picker.
                    int first = 1;
                    org.json.JSONArray its = MintEngine.localItems(row);
                    for (int i = 0; i < its.length(); i++) {
                        JSONObject it2 = its.optJSONObject(i);
                        if (it2 != null && it2.optString("image", "").isEmpty()) { first = it2.optInt("idx", 1); break; }
                    }
                    pickProgressImages(row, first);
                }
            }));
        } else {
            TextView doneNote = new TextView(act);
            doneNote.setText("✓ Collection fully minted. The items live in your Gallery and Balances.");
            doneNote.setTextColor(Design.success());
            doneNote.setTextSize(13f);
            doneNote.setPadding(0, dp(14), 0, 0);
            container.addView(doneNote);
        }
    }

    /**
     * Multi-fill for a paused (NEEDIMAGES) mint: pick as many images as you like starting at the
     * tapped item; they land on that item and then on the other still-imageless items in order,
     * are persisted to the collection row, and the phase is released back to STAMP.
     */
    private void pickProgressImages(final JSONObject row, final int startIdx) {
        final int size = row.optInt("size", 0);
        if (size <= 0) return;
        // Only items still missing an image are candidates (besides the tapped one).
        final java.util.Set<Integer> haveImage = new java.util.HashSet<>();
        org.json.JSONArray items = MintEngine.localItems(row);
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && !it.optString("image", "").isEmpty()) haveImage.add(it.optInt("idx"));
        }
        act.pickImages(uris -> {
            java.util.List<Integer> targets = fillOrder(startIdx, size, uris.size(), haveImage);
            compressInto(uris, targets,
                    (idx, b64) -> saveProgressImage(idx, b64),
                    msg -> Toast.makeText(act, msg, Toast.LENGTH_SHORT).show(),
                    () -> {
                        refresh();
                        Toast.makeText(act, "Images saved — stamping resumes on the next block.",
                                Toast.LENGTH_SHORT).show();
                        act.mintEngineTick();
                    });
        });
    }

    /** Persist one item's image into the collection row, releasing NEEDIMAGES back to STAMP. */
    private void saveProgressImage(int idx, String b64) {
        JSONObject fresh = LocalStore.findById(act, progressRowId);
        if (fresh == null) return;
        org.json.JSONArray fitems = MintEngine.localItems(fresh);
        for (int j = 0; j < fitems.length(); j++) {
            JSONObject fit = fitems.optJSONObject(j);
            if (fit != null && fit.optInt("idx") == idx) {
                try { fit.put("image", b64); } catch (Exception ignored) {}
            }
        }
        try {
            fresh.put("items", fitems);
            if ("NEEDIMAGES".equals(fresh.optString("phase"))) {
                fresh.put("phase", "STAMP");
                fresh.put("error", "");
            }
        } catch (Exception ignored) {}
        LocalStore.upsert(act, fresh);
    }

    // ===================== shared =====================

    /** Posts the tokencreate and shows the result dialog. */
    private void runMint(String cmd, final TextView statusView, final String what) {
        status(statusView, "Minting — building and mining the transaction…", true);
        act.node().cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (json.optBoolean("status", false)) {
                    status(statusView, "✓ " + what + " minted — it appears in Balances once confirmed.", true);
                    resultDialog(what + " minted",
                            "The mint transaction is posted. Your new " + what.toLowerCase()
                                    + " appears in Balances (unconfirmed first) within a couple of blocks.");
                    act.reload();
                } else {
                    String err = json.optString("error", json.optString("message", "Mint failed."));
                    status(statusView, "Failed: " + err, false);
                }
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "Enable this wallet in Minima Core → Apps first.";
                status(statusView, "Failed: " + message, false);
            }
        });
    }

    private void resultDialog(String title, String message) {
        Sheet.create(act, title)
                .subtitle(message)
                .action("Close", Sheet.Style.SECONDARY, null)
                .action("View balances", Sheet.Style.PRIMARY, () -> act.goToTab(WalletActivity.TAB_BALANCES))
                .show();
    }

    private LinearLayout confirmBody() {
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Design.bg());
        body.setPadding(dp(18), dp(10), dp(18), dp(4));
        return body;
    }

    private void addConfirmRow(LinearLayout body, String label, String value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(3), 0, dp(3));
        TextView l = new TextView(act);
        l.setText(label);
        l.setTextColor(Design.dim());
        l.setTextSize(12f);
        r.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(act);
        v.setText(value);
        v.setTextColor("Not set".equals(value) || "None set".equals(value) ? Design.dim() : Design.text());
        if ("Not set".equals(value) || "None set".equals(value)) v.setTypeface(null, Typeface.ITALIC);
        v.setTextSize(12f);
        v.setGravity(Gravity.END);
        v.setMaxLines(2);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        body.addView(r);
    }

    private void showConfirm(String title, LinearLayout body, String action, Runnable go) {
        TextView warn = new TextView(act);
        warn.setText("Minting consumes a small fraction of Minima and cannot be undone.");
        warn.setTextColor(Design.dim());
        warn.setTextSize(11f);
        warn.setPadding(0, dp(10), 0, 0);
        body.addView(warn);
        Sheet.create(act, title)
                .body(body)
                .action("Back", Sheet.Style.SECONDARY, null)
                .action(action, Sheet.Style.PRIMARY, go::run)
                .show();
    }

    // ---- field + validation helpers ----

    private TextView backRow(String title) {
        TextView t = new TextView(act);
        t.setText("‹  " + title);
        t.setTextColor(Design.heading());
        t.setTextSize(16f);
        t.setTypeface(Design.typefaceBold());
        t.setPadding(0, 0, 0, dp(10));
        t.setOnClickListener(v -> { screen = Screen.HUB; refresh(); });
        return t;
    }

    private EditText addField(LinearLayout into, String label, String hint, int inputType) {
        TextView l = new TextView(act);
        l.setText(label);
        l.setTextColor(Design.dim());
        l.setTextSize(12f);
        l.setPadding(0, dp(10), 0, dp(4));
        into.addView(l);
        EditText e = plainField(hint, inputType);
        into.addView(e);
        return e;
    }

    private EditText addWeightedField(LinearLayout row, String label, String hint, int inputType, float weight) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        if (row.getChildCount() > 0) clp.leftMargin = dp(8);
        col.setLayoutParams(clp);
        TextView l = new TextView(act);
        l.setText(label);
        l.setTextColor(Design.dim());
        l.setTextSize(12f);
        l.setPadding(0, dp(10), 0, dp(4));
        col.addView(l);
        EditText e = plainField(hint, inputType);
        col.addView(e);
        row.addView(col);
        return e;
    }

    private EditText plainField(String hint, int inputType) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setHintTextColor(Design.dim2());
        e.setTextColor(Design.text());
        e.setTextSize(13f);
        e.setBackgroundColor(Design.surface2());
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        e.setInputType(inputType == InputType.TYPE_TEXT_VARIATION_URI
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI : inputType);
        e.setMaxLines(2);
        return e;
    }

    private android.widget.Button primaryButton(String label, View.OnClickListener click) {
        android.widget.Button b = new android.widget.Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        b.setTextColor(Design.onAccent());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    private TextView statusLine() {
        TextView t = new TextView(act);
        t.setTextSize(13f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(12));
        t.setVisibility(View.GONE);
        return t;
    }

    private void status(TextView view, String msg, boolean ok) {
        view.setVisibility(View.VISIBLE);
        view.setText(msg);
        view.setTextColor(ok ? Design.success() : Design.red());
    }

    /** A cached form may still be attached to the old container after a refresh — detach first. */
    private View detachedForReuse(View v) {
        if (v.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) v.getParent()).removeView(v);
        }
        return v;
    }

    /**
     * Field-value guard: quotes and backslashes break the metadata JSON when the node re-parses
     * the command line, and a semicolon would let a value smuggle a second node command.
     */
    private String badValue(String s, boolean required) {
        if (s == null || s.isEmpty()) return required ? "required." : null;
        // Braces/brackets break the node's JSON-parameter tokenizer (it counts them without string
        // awareness), truncating name:{...} — and for collections the broken command would be
        // re-posted every block from the persisted row with no way to edit it.
        if (s.contains("\"") || s.contains("\\") || s.contains(";") || s.contains("\n")
                || s.contains("{") || s.contains("}") || s.contains("[") || s.contains("]")) {
            return "may not contain quotes, backslashes, semicolons or brackets.";
        }
        return null;
    }

    /** Characters that break the command line or the metadata JSON. */
    private boolean hasBreakers(String u) {
        return u.contains(" ") || u.contains("\"") || u.contains("\\") || u.contains(";") || u.contains("\n");
    }

    /** URLs ride the command line outside JSON too (webvalidate:) — no spaces or breakers allowed. */
    private boolean badUrl(String u) {
        if (hasBreakers(u)) return true;
        return !(u.startsWith("https://") || u.startsWith("http://"));
    }

    private boolean badAmount(String s) {
        try { return new BigDecimal(s).signum() < 0; } catch (Exception e) { return true; }
    }

    private boolean positive(String s) {
        try { return new BigDecimal(s).signum() > 0; } catch (Exception e) { return false; }
    }

    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density); }
}
