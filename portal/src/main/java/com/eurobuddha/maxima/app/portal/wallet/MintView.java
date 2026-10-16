package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import static com.eurobuddha.maxima.app.i18n.Language.msg;

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
    private static final Language.Message NOT_SET = msg(R.string.l10n_node_mint_not_set);

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
        container.addView(hubCard(msg(R.string.l10n_wallet_token),
                msg(R.string.l10n_node_mint_token_help),
                false, v -> { screen = Screen.TOKEN; refresh(); }));
        container.addView(hubCard("NFT",
                msg(R.string.l10n_node_mint_nft_help),
                false, v -> { screen = Screen.NFT; refresh(); }));
        // State-NFT collection MINTING is deliberately not offered in this wallet (it needs
        // atelier-level tooling); State NFTs are still viewed, sent and received in the Gallery.
        // Any collection mint that was in flight elsewhere is listed so it can be resumed/inspected.
        org.json.JSONArray rows = LocalStore.load(act);
        if (rows.length() > 0) {
            TextView lbl = new TextView(act);
            Language.setText(lbl, Language.upper(msg(R.string.l10n_node_collection_collections)));
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
        boolean stacked = act.getResources().getConfiguration().fontScale > 1.2f;
        r.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
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
        Language.setText(n, msg(R.string.l10n_node_collection_collection_size, MintMessages.name(row), row.optInt("size")));
        n.setTextColor(Design.text());
        n.setTextSize(13f);
        n.setTypeface(Design.typefaceBold());
        col.addView(n);
        String err = row.optString("error", "");
        if (!err.isEmpty()) {
            TextView e = new TextView(act);
            Language.setText(e, MintMessages.status(err));
            e.setTextColor(Design.red());
            e.setTextSize(10f);
            e.setMaxLines(1);
            e.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(e);
        }
        r.addView(col, new LinearLayout.LayoutParams(stacked ? LinearLayout.LayoutParams.MATCH_PARENT : 0,
                LinearLayout.LayoutParams.WRAP_CONTENT, stacked ? 0f : 1f));

        String phase = row.optString("phase", "?");
        TextView p = new TextView(act);
        Language.setText(p, Language.upper(MintMessages.phase(phase)));
        p.setTextSize(10f);
        p.setLetterSpacing(0.08f);
        p.setTypeface(Design.typefaceBold());
        p.setTextColor("DONE".equals(phase) ? Design.success()
                : "NEEDIMAGES".equals(phase) || !err.isEmpty() ? Design.red() : Design.accent());
        r.addView(p);

        r.setOnClickListener(v -> { progressRowId = row.optLong("id", -1); screen = Screen.PROGRESS; refresh(); });
        return r;
    }

    private View hubCard(CharSequence title, CharSequence blurb, boolean accent, View.OnClickListener click) {
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
        Language.setText(t, title);
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
        Language.setText(b, blurb);
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

        tokenForm.addView(backRow(msg(R.string.l10n_node_mint_mint_token)));
        tName = addField(tokenForm, msg(R.string.l10n_node_mint_name_required), msg(R.string.l10n_node_mint_my_token), InputType.TYPE_CLASS_TEXT);
        LinearLayout triple = new LinearLayout(act);
        triple.setOrientation(LinearLayout.HORIZONTAL);
        tokenForm.addView(triple);
        tAmount = addWeightedField(triple, msg(R.string.l10n_node_mint_amount_required), "1000000", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 1.4f);
        tDecimals = addWeightedField(triple, msg(R.string.l10n_node_balance_decimals), "8", InputType.TYPE_CLASS_NUMBER, 1f);
        tTicker = addWeightedField(triple, msg(R.string.l10n_node_balance_ticker), "TOK", InputType.TYPE_CLASS_TEXT, 1f);
        tDesc = addField(tokenForm, msg(R.string.l10n_node_balance_description), msg(R.string.l10n_node_mint_max_description), InputType.TYPE_CLASS_TEXT);
        tUrl = addField(tokenForm, msg(R.string.l10n_node_mint_icon_image_url), "https://…", InputType.TYPE_TEXT_VARIATION_URI);
        tWebv = addField(tokenForm, msg(R.string.l10n_node_mint_web_url), msg(R.string.l10n_node_mint_web_hint), InputType.TYPE_TEXT_VARIATION_URI);
        tBurn = addField(tokenForm, msg(R.string.l10n_node_mint_burn_optional), "0.0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        tMetaRows = new LinearLayout(act);
        tMetaRows.setOrientation(LinearLayout.VERTICAL);
        tokenForm.addView(tMetaRows);
        TextView addMeta = new TextView(act);
        Language.setText(addMeta, msg(R.string.l10n_node_mint_add_metadata));
        addMeta.setTextColor(Design.accent());
        addMeta.setTextSize(13f);
        addMeta.setPadding(dp(2), dp(8), 0, dp(8));
        addMeta.setOnClickListener(v -> addMetaRow());
        tokenForm.addView(addMeta);

        tokenForm.addView(primaryButton(msg(R.string.l10n_node_mint_review_token), v -> onReviewToken()));
        tStatus = statusLine();
        tokenForm.addView(tStatus);
        return tokenForm;
    }

    private void addMetaRow() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        EditText k = plainField(msg(R.string.l10n_node_mint_key), InputType.TYPE_CLASS_TEXT);
        EditText v = plainField(msg(R.string.l10n_node_mint_value), InputType.TYPE_CLASS_TEXT);
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
        if (err != null) { status(tStatus, msg(R.string.l10n_node_mint_field_error, msg(R.string.l10n_common_name), SystemMessages.message(err)), false); return; }

        final String amountStr = tAmount.getText().toString().trim();
        final BigDecimal amount;
        try { amount = new BigDecimal(amountStr); } catch (Exception e) { status(tStatus, msg(R.string.l10n_node_send_valid_amount), false); return; }
        if (amount.signum() <= 0) { status(tStatus, msg(R.string.l10n_node_send_positive_amount), false); return; }
        if (amount.compareTo(new BigDecimal("1000000000000")) > 0) { status(tStatus, msg(R.string.l10n_node_mint_supply_cap), false); return; }

        int decimals = 8;
        String decStr = tDecimals.getText().toString().trim();
        if (!decStr.isEmpty()) {
            try { decimals = Integer.parseInt(decStr); } catch (Exception e) { decimals = -1; }
            if (decimals < 0 || decimals > 16) { status(tStatus, msg(R.string.l10n_node_mint_decimal_range), false); return; }
        }
        if (Util.decimalPlaces(amount) > 0) { status(tStatus, msg(R.string.l10n_node_mint_whole_supply), false); return; }

        final String ticker = tTicker.getText().toString().trim();
        if (ticker.length() > 5) { status(tStatus, msg(R.string.l10n_node_mint_ticker_length), false); return; }
        if ((err = badValue(ticker, false)) != null) { status(tStatus, msg(R.string.l10n_node_mint_field_error, msg(R.string.l10n_node_balance_ticker), SystemMessages.message(err)), false); return; }

        final String desc = tDesc.getText().toString().trim();
        if (desc.length() > 255) { status(tStatus, msg(R.string.l10n_node_mint_description_length), false); return; }
        if ((err = badValue(desc, false)) != null) { status(tStatus, msg(R.string.l10n_node_mint_field_error, msg(R.string.l10n_node_balance_description), SystemMessages.message(err)), false); return; }

        final String url = tUrl.getText().toString().trim();
        if (!url.isEmpty() && badUrl(url)) { status(tStatus, msg(R.string.l10n_node_mint_icon_url_invalid), false); return; }
        final String webv = tWebv.getText().toString().trim();
        if (!webv.isEmpty() && badUrl(webv)) { status(tStatus, msg(R.string.l10n_node_mint_web_url_invalid), false); return; }

        final String burn = tBurn.getText().toString().trim();
        if (!burn.isEmpty() && badAmount(burn)) { status(tStatus, msg(R.string.l10n_node_mint_burn_invalid), false); return; }

        // metadata pairs
        final List<String[]> meta = new ArrayList<>();
        for (int i = 0; i < tMetaRows.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) tMetaRows.getChildAt(i);
            String k = ((EditText) row.getChildAt(0)).getText().toString().trim();
            String v = ((EditText) row.getChildAt(1)).getText().toString().trim();
            if (k.isEmpty() && v.isEmpty()) continue;
            if (k.isEmpty()) { status(tStatus, msg(R.string.l10n_node_mint_metadata_key_missing, i + 1), false); return; }
            if (badValue(k, false) != null || badValue(v, false) != null) {
                status(tStatus, msg(R.string.l10n_node_mint_metadata_invalid), false); return;
            }
            if (RESERVED_META.contains(k.toLowerCase(java.util.Locale.ROOT))) {
                status(tStatus, msg(R.string.l10n_node_mint_reserved_key, k), false); return;
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
            status(tStatus, msg(R.string.l10n_node_mint_record_too_large, json.toString().length() + StateNft.DEF_WRAPPER + StateNft.DEF_SIGN_WEIGHT), false);
            return;
        }

        // Confirmation card, old-wallet style
        LinearLayout body = confirmBody();
        addConfirmRow(body, msg(R.string.l10n_common_name), name);
        addConfirmRow(body, msg(R.string.l10n_wallet_supply), amount.toBigInteger().toString());
        addConfirmRow(body, msg(R.string.l10n_node_balance_decimals), String.valueOf(decimals));
        if (!ticker.isEmpty()) addConfirmRow(body, msg(R.string.l10n_node_balance_ticker), ticker.toUpperCase(java.util.Locale.ROOT));
        addConfirmRow(body, msg(R.string.l10n_node_balance_description), desc.isEmpty() ? NOT_SET : desc);
        if (!url.isEmpty()) addConfirmRow(body, msg(R.string.l10n_node_mint_icon_url), url);
        addConfirmRow(body, msg(R.string.l10n_node_balance_web_validation), webv.isEmpty() ? NOT_SET : webv);
        for (String[] kv : meta) addConfirmRow(body, kv[0], kv[1]);
        if (!burn.isEmpty() && positive(burn)) addConfirmRow(body, msg(R.string.l10n_node_mint_burn), burn + " MINIMA");
        showConfirm(msg(R.string.l10n_node_mint_confirm_token), body, msg(R.string.l10n_node_mint_mint_action), () -> {
            // ALWAYS SIGNED — no key, no mint
            act.node().cmd("getaddress", new NodeApi.Cb() {
                @Override public void onResult(JSONObject gres) {
                    JSONObject r = gres.optJSONObject("response");
                    String pk = r == null ? "" : r.optString("publickey", "");
                    if (pk.isEmpty()) { status(tStatus, msg(R.string.l10n_node_mint_signing_key_missing), false); return; }
                    runMint(cmd.toString() + " signtoken:" + pk, tStatus, "Token");
                }
                @Override public void onError(String message) { status(tStatus, msg(R.string.l10n_node_mint_signing_key_error, SystemMessages.message(message)), false); }
            });
        });
    }

    // ===================== NFT FORM =====================

    private View nftForm() {
        if (nftForm != null) return detachedForReuse(nftForm);
        nftForm = new LinearLayout(act);
        nftForm.setOrientation(LinearLayout.VERTICAL);

        nftForm.addView(backRow(msg(R.string.l10n_node_mint_mint_nft)));

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
        Language.description(nPreview, msg(R.string.l10n_node_mint_image_description));
        nPreview.setOnClickListener(v -> pickNftImage());
        imgRow.addView(nPreview);

        LinearLayout modeCol = new LinearLayout(act);
        modeCol.setOrientation(LinearLayout.VERTICAL);
        modeCol.setGravity(Gravity.CENTER_VERTICAL);
        imgRow.addView(modeCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout seg = new LinearLayout(act);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        nModeEmbed = segBtn(msg(R.string.l10n_node_mint_embed_image), v -> setNftMode(true));
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
        nUrl = addField(nUrlBlock, msg(R.string.l10n_node_mint_image_url_mode), msg(R.string.l10n_node_mint_image_url_hint), InputType.TYPE_TEXT_VARIATION_URI);
        hostButton(nUrlBlock, msg(R.string.l10n_node_mint_host_image), v -> act.pickImage(uri -> {
            status(nStatus, msg(R.string.l10n_node_mint_uploading), true);
            NodeHost.upload(act, uri, new NodeHost.Cb() {
                @Override public void done(String url, String path, String sha256) {
                    if (url.isEmpty()) {
                        status(nStatus, msg(R.string.l10n_node_mint_host_no_public_url, path), false);
                        return;
                    }
                    nUrl.setText(url);
                    status(nStatus, msg(R.string.l10n_node_mint_hosted, url, sha256), true);
                }
                @Override public void fail(CharSequence why) { status(nStatus, msg(R.string.l10n_node_mint_hosting_failed, why), false); }
                @Override public void progress(CharSequence note) { status(nStatus, note, true); }
            });
        }));
        nName = addField(nftForm, msg(R.string.l10n_node_mint_name_required), msg(R.string.l10n_node_mint_my_nft), InputType.TYPE_CLASS_TEXT);
        LinearLayout duo = new LinearLayout(act);
        duo.setOrientation(LinearLayout.HORIZONTAL);
        nftForm.addView(duo);
        nEditions = addWeightedField(duo, msg(R.string.l10n_node_mint_editions_required), "1", InputType.TYPE_CLASS_NUMBER, 1f);
        nCreator = addWeightedField(duo, msg(R.string.l10n_node_gallery_creator), msg(R.string.l10n_node_mint_your_name), InputType.TYPE_CLASS_TEXT, 2f);
        nDesc = addField(nftForm, msg(R.string.l10n_node_balance_description), msg(R.string.l10n_node_mint_nft_description_hint), InputType.TYPE_CLASS_TEXT);
        nExtUrl = addField(nftForm, msg(R.string.l10n_node_mint_external_url), "https://…", InputType.TYPE_TEXT_VARIATION_URI);
        nWebv = addField(nftForm, msg(R.string.l10n_node_mint_web_url), "https://…/nft.txt", InputType.TYPE_TEXT_VARIATION_URI);
        nBurn = addField(nftForm, msg(R.string.l10n_node_mint_burn_optional), "0.0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        nSign = new CheckBox(act);
        Language.setText(nSign, msg(R.string.l10n_node_mint_always_signed));
        nSign.setTextColor(Design.dim());
        nSign.setTextSize(12f);
        nSign.setChecked(true);
        nSign.setEnabled(false);   // ALWAYS SIGNED: not a choice any more
        nftForm.addView(nSign);

        nftForm.addView(primaryButton(msg(R.string.l10n_node_mint_review_nft), v -> onReviewNft()));
        nStatus = statusLine();
        nftForm.addView(nStatus);

        setNftMode(true);
        prefillCreator();
        return nftForm;
    }

    /** A quiet outlined action under a URL field: "host this on my node". */
    private void hostButton(LinearLayout into, CharSequence label, View.OnClickListener click) {
        TextView t = new TextView(act);
        Language.setText(t, label);
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

    private TextView segBtn(CharSequence label, View.OnClickListener click) {
        TextView t = new TextView(act);
        Language.setText(t, label);
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
        Language.setText(nImageNote, embed
                ? (nftImageB64.isEmpty()
                    ? msg(R.string.l10n_node_mint_pick_image_help, ARTIMAGE_BUDGET)
                    : Language.count(R.plurals.l10n_node_mint_image_ready, nftImageB64.length()))
                : msg(R.string.l10n_node_mint_external_image_help));
    }

    private void pickNftImage() {
        act.pickImage(uri -> {
            if (uri == null) return;
            nPreview.setImageURI(uri);
            Language.setText(nImageNote, msg(R.string.l10n_node_mint_compressing));
            new Thread(() -> {
                final String result = sealFrom(uri, ARTIMAGE_BUDGET);
                act.runOnUiThread(() -> {
                    if (result.isEmpty()) {
                        nftImageB64 = "";
                        Language.setText(nImageNote, msg(R.string.l10n_node_mint_image_unreadable));
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
        if (err != null) { status(nStatus, msg(R.string.l10n_node_mint_field_error, msg(R.string.l10n_common_name), SystemMessages.message(err)), false); return; }

        final String edStr = nEditions.getText().toString().trim();
        final int editions;
        try { editions = edStr.isEmpty() ? 1 : Integer.parseInt(edStr); }
        catch (Exception e) { status(nStatus, msg(R.string.l10n_node_mint_whole_editions), false); return; }
        if (editions < 1 || editions > 1000000) { status(nStatus, msg(R.string.l10n_node_mint_editions_range), false); return; }

        final String desc = nDesc.getText().toString().trim();
        if (desc.length() > 255) { status(nStatus, msg(R.string.l10n_node_mint_description_length), false); return; }
        if ((err = badValue(desc, false)) != null) { status(nStatus, msg(R.string.l10n_node_mint_field_error, msg(R.string.l10n_node_balance_description), SystemMessages.message(err)), false); return; }
        final String creator = nCreator.getText().toString().trim();
        if ((err = badValue(creator, false)) != null) { status(nStatus, msg(R.string.l10n_node_mint_field_error, msg(R.string.l10n_node_gallery_creator), SystemMessages.message(err)), false); return; }

        final String extUrl = nExtUrl.getText().toString().trim();
        if (!extUrl.isEmpty() && badUrl(extUrl)) { status(nStatus, msg(R.string.l10n_node_mint_external_url_invalid), false); return; }
        final String webv = nWebv.getText().toString().trim();
        if (!webv.isEmpty() && badUrl(webv)) { status(nStatus, msg(R.string.l10n_node_mint_web_url_invalid), false); return; }
        final String burn = nBurn.getText().toString().trim();
        if (!burn.isEmpty() && badAmount(burn)) { status(nStatus, msg(R.string.l10n_node_mint_burn_invalid), false); return; }

        final String imageValue;
        if (nftEmbedMode) {
            if (nftImageB64.isEmpty()) { status(nStatus, msg(R.string.l10n_node_mint_pick_image), false); return; }
            imageValue = "<artimage>" + nftImageB64 + "</artimage>";
        } else {
            String u = nUrl.getText().toString().trim();
            boolean okScheme = u.startsWith("https://") || u.startsWith("http://") || u.startsWith("ipfs://");
            if (u.isEmpty() || !okScheme || hasBreakers(u)) {
                status(nStatus, msg(R.string.l10n_node_mint_image_url_invalid), false); return;
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
        addConfirmRow(body, msg(R.string.l10n_common_name), name);
        addConfirmRow(body, msg(R.string.l10n_node_mint_total_to_mint), String.valueOf(editions));
        addConfirmRow(body, msg(R.string.l10n_desktop_chat_image), nftEmbedMode ? msg(R.string.l10n_node_mint_embedded_image, nftImageB64.length()) : imageValue);
        addConfirmRow(body, msg(R.string.l10n_node_balance_description), desc.isEmpty() ? NOT_SET : desc);
        addConfirmRow(body, msg(R.string.l10n_node_gallery_creator), creator.isEmpty() ? NOT_SET : creator);
        addConfirmRow(body, msg(R.string.l10n_node_mint_external_url), extUrl.isEmpty() ? NOT_SET : extUrl);
        addConfirmRow(body, msg(R.string.l10n_node_balance_web_validation), webv.isEmpty() ? NOT_SET : webv);
        addConfirmRow(body, msg(R.string.l10n_node_mint_creator_signature), msg(R.string.l10n_node_mint_signature_yes));
        if (!burn.isEmpty() && positive(burn)) addConfirmRow(body, msg(R.string.l10n_node_mint_burn), burn + " MINIMA");

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
                status(nStatus, msg(R.string.l10n_node_mint_nft_record_too_large, metaLen + StateNft.DEF_WRAPPER + StateNft.DEF_SIGN_WEIGHT, editions > 1 ? Language.count(R.plurals.l10n_node_mint_edition_limit, editions) : msg(R.string.l10n_node_mint_sendable_limit)), false);
                return;
            }
        }
        showConfirm(msg(R.string.l10n_node_mint_confirm_nft), body, msg(R.string.l10n_node_mint_mint_nft_action), () -> {
            // ALWAYS SIGNED — no key, no mint
            act.node().cmd("getaddress", new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {
                    JSONObject r = json.optJSONObject("response");
                    String pk = r == null ? "" : r.optString("publickey", "");
                    if (pk.isEmpty()) { status(nStatus, msg(R.string.l10n_node_mint_signing_key_missing), false); return; }
                    cmd.append(" signtoken:").append(pk);
                    runMint(cmd.toString(), nStatus, "NFT");
                }
                @Override public void onError(String message) { status(nStatus, msg(R.string.l10n_node_mint_signing_key_error, SystemMessages.message(message)), false); }
            });
        });
    }

    // ===================== STATE NFT COLLECTION =====================

    private View collectionForm() {
        if (colForm != null) return detachedForReuse(colForm);
        colForm = new LinearLayout(act);
        colForm.setOrientation(LinearLayout.VERTICAL);

        colForm.addView(backRow(msg(R.string.l10n_node_collection_new)));

        TextView intro = new TextView(act);
        Language.setText(intro, msg(R.string.l10n_node_collection_intro));
        intro.setTextColor(Design.dim());
        intro.setTextSize(12f);
        colForm.addView(intro);

        cName = addField(colForm, msg(R.string.l10n_node_collection_name_required), "SolarPunks", InputType.TYPE_CLASS_TEXT);
        LinearLayout duo = new LinearLayout(act);
        duo.setOrientation(LinearLayout.HORIZONTAL);
        colForm.addView(duo);
        cSize = addWeightedField(duo, msg(R.string.l10n_node_collection_items_required), "5", InputType.TYPE_CLASS_NUMBER, 1f);
        cExt = addWeightedField(duo, msg(R.string.l10n_node_collection_extension), ".png", InputType.TYPE_CLASS_TEXT, 1.4f);
        cDesc = addField(colForm, msg(R.string.l10n_node_balance_description), msg(R.string.l10n_node_collection_description_hint), InputType.TYPE_CLASS_TEXT);

        // mode segment
        TextView modeLbl = new TextView(act);
        Language.setText(modeLbl, msg(R.string.l10n_node_collection_item_images));
        modeLbl.setTextColor(Design.dim());
        modeLbl.setTextSize(12f);
        modeLbl.setPadding(0, dp(10), 0, dp(4));
        colForm.addView(modeLbl);
        LinearLayout seg = new LinearLayout(act);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        cModeEmbed = segBtn(msg(R.string.l10n_node_collection_embed), v -> setColMode(true));
        cModeUrl = segBtn(msg(R.string.l10n_node_collection_url_mode), v -> setColMode(false));
        seg.addView(cModeEmbed, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        seg.addView(cModeUrl, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        colForm.addView(seg);

        cUrlBlock = new LinearLayout(act);
        cUrlBlock.setOrientation(LinearLayout.VERTICAL);
        colForm.addView(cUrlBlock);
        cBase = addField(cUrlBlock, msg(R.string.l10n_node_collection_base_url), "https://mysite.com/nft/", InputType.TYPE_TEXT_VARIATION_URI);

        cImagesBlock = new LinearLayout(act);
        cImagesBlock.setOrientation(LinearLayout.VERTICAL);
        colForm.addView(cImagesBlock);
        cImagesNote = new TextView(act);
        cImagesNote.setTextColor(Design.dim());
        cImagesNote.setTextSize(11f);
        cImagesNote.setPadding(0, dp(8), 0, dp(4));
        Language.setText(cImagesNote, msg(R.string.l10n_node_collection_image_budget, STATE_IMG_BUDGET));
        cImagesBlock.addView(cImagesNote);
        TextView pickAll = new TextView(act);
        Language.setText(pickAll, msg(R.string.l10n_node_collection_pick_empty));
        pickAll.setTextColor(Design.accent());
        pickAll.setTextSize(13f);
        pickAll.setTypeface(Design.typefaceBold());
        pickAll.setPadding(0, dp(6), 0, dp(8));
        pickAll.setOnClickListener(v -> {
            int size = parsedSize();
            if (size < 2 || size > 20) { status(cStatus, msg(R.string.l10n_node_collection_set_count), false); return; }
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
        Language.setText(buildSlots, msg(R.string.l10n_node_collection_build_slots));
        buildSlots.setTextColor(Design.accent());
        buildSlots.setTextSize(12f);
        buildSlots.setPadding(0, dp(4), 0, dp(4));
        buildSlots.setOnClickListener(v -> buildImageSlots());
        cImagesBlock.addView(buildSlots);

        // ---- collection icon (what the regular wallet shows for this token) ----
        TextView iconLbl = new TextView(act);
        Language.setText(iconLbl, msg(R.string.l10n_node_collection_icon));
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
        Language.description(cIconPreview, msg(R.string.l10n_node_collection_collection_icon_description));
        cIconPreview.setOnClickListener(v -> { if (colIconEmbed) pickCollectionIcon(); });
        iconRow.addView(cIconPreview);

        LinearLayout iconCol = new LinearLayout(act);
        iconCol.setOrientation(LinearLayout.VERTICAL);
        iconRow.addView(iconCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout iconSeg = new LinearLayout(act);
        iconSeg.setOrientation(LinearLayout.HORIZONTAL);
        cIconModeEmbed = segBtn(msg(R.string.l10n_node_collection_embed_icon), v -> setIconMode(true));
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
        cIconUrl = addField(cIconUrlBlock, msg(R.string.l10n_node_mint_icon_url), "https://…", InputType.TYPE_TEXT_VARIATION_URI);
        cExtUrl = addField(colForm, msg(R.string.l10n_node_collection_external_optional), "https://…", InputType.TYPE_TEXT_VARIATION_URI);
        cWebv = addField(colForm, msg(R.string.l10n_node_collection_web_optional), "https://…/collection.txt", InputType.TYPE_TEXT_VARIATION_URI);

        colForm.addView(primaryButton(msg(R.string.l10n_node_collection_review), v -> onReviewCollection()));
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
            Language.setText(cIconNote, msg(R.string.l10n_node_mint_compressing));
            new Thread(() -> {
                final String result = sealFrom(uri, ICON_BUDGET);
                act.runOnUiThread(() -> {
                    if (result.isEmpty()) { Language.setText(cIconNote, msg(R.string.l10n_node_mint_image_unreadable)); return; }
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
            Language.setText(cIconNote, msg(R.string.l10n_node_collection_icon_from_url));
            return;
        }
        if (!colIconB64.isEmpty()) {
            ImageLoader.loadOver(act, ImageTools.dataUri(colIconB64), cIconPreview, null);
            Language.setText(cIconNote, msg(R.string.l10n_node_collection_icon_ready, colIconB64.length()));
            return;
        }
        String first = colImages.get(1);
        if (first != null) {
            prepareIconDefault(first);
            // Preview the SHRUNK bytes once they exist, so what you see is what gets sealed.
            String show = colIconDefault.isEmpty() ? first : colIconDefault;
            ImageLoader.loadOver(act, ImageTools.dataUri(show), cIconPreview, null);
            Language.setText(cIconNote, colIconDefault.isEmpty()
                    ? msg(R.string.l10n_node_collection_preparing_icon)
                    : msg(R.string.l10n_node_collection_icon_default_ready, colIconDefault.length()));
        } else {
            cIconPreview.setImageBitmap(null);
            Language.setText(cIconNote, msg(R.string.l10n_node_collection_default_icon));
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
            status(cStatus, msg(R.string.l10n_node_collection_slots_range), false);
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
            Language.setText(n, msg(R.string.l10n_node_collection_item, idx));
            n.setTextColor(Design.text());
            n.setTextSize(13f);
            r.addView(n, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final TextView state = new TextView(act);
            Language.setText(state, have ? msg(R.string.l10n_node_collection_image_bytes, colImages.get(idx).length()) : msg(R.string.l10n_node_collection_pick_image));
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
        Language.setText(cImagesNote, filled == size
                ? msg(R.string.l10n_node_collection_images_ready, size)
                : msg(R.string.l10n_node_collection_images_partial, filled, size));
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
            if (size < 2 || size > 20) { status(cStatus, msg(R.string.l10n_node_collection_items_range), false); return; }
            java.util.List<Integer> targets = fillOrder(startIdx, size, uris.size(), colImages.keySet());
            compressInto(uris, targets, (idx, b64) -> colImages.put(idx, b64),
                    msg -> Language.setText(cImagesNote, msg),
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
                              final java.util.function.Consumer<CharSequence> progress,
                              final Runnable onDone) {
        final int n = Math.min(uris.size(), targets.size());
        if (n == 0) { onDone.run(); return; }
        progress.accept(msg(R.string.l10n_node_collection_compressing, 1, n));
        new Thread(() -> {
            for (int i = 0; i < n; i++) {
                final String result = sealFrom(uris.get(i), STATE_IMG_BUDGET);
                final int slot = targets.get(i);
                final int shown = i + 1;
                // Assign on the UI thread so the image map stays single-threaded.
                act.runOnUiThread(() -> {
                    if (!result.isEmpty()) assign.accept(slot, result);
                    if (shown < n) progress.accept(msg(R.string.l10n_node_collection_compressing, shown + 1, n));
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
            Toast.makeText(act, msg(R.string.l10n_node_collection_fill_extra, targetCount, picked - targetCount), Toast.LENGTH_LONG).show();
        } else if (filled < size) {
            Toast.makeText(act, msg(R.string.l10n_node_collection_fill_missing, filled, size, size - filled), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(act, msg(R.string.l10n_node_collection_images_ready, size), Toast.LENGTH_SHORT).show();
        }
    }

    private void onReviewCollection() {
        final String name = cName.getText().toString().trim();
        String err = badValue(name, true);
        if (err != null) { status(cStatus, msg(R.string.l10n_node_mint_field_error, msg(R.string.l10n_common_name), MintMessages.status(err)), false); return; }

        final int size = parsedSize();
        if (size < 2 || size > 20) { status(cStatus, msg(R.string.l10n_node_collection_review_range), false); return; }

        final String desc = cDesc.getText().toString().trim();
        if ((err = badValue(desc, false)) != null) { status(cStatus, msg(R.string.l10n_node_mint_field_error, msg(R.string.l10n_node_balance_description), MintMessages.status(err)), false); return; }

        final String base = cBase.getText().toString().trim();
        final String ext = cExt.getText().toString().trim().isEmpty() ? ".png" : cExt.getText().toString().trim();
        if (!colEmbedMode) {
            if (base.isEmpty() || badUrl(base)) { status(cStatus, msg(R.string.l10n_node_collection_base_invalid), false); return; }
            if (badValue(ext, false) != null || ext.contains(" ")) { status(cStatus, msg(R.string.l10n_node_collection_extension_invalid), false); return; }
        } else {
            for (int i = 1; i <= size; i++) {
                if (!colImages.containsKey(i)) {
                    status(cStatus, msg(R.string.l10n_node_collection_missing_image, i, size), false);
                    return;
                }
            }
        }
        // Resolve the icon NOW: tokencreate bakes it in permanently.
        final String iconUrl = cIconUrl.getText().toString().trim();
        final String icon;
        final CharSequence iconDesc;
        if (colIconEmbed) {
            if (!colIconB64.isEmpty()) {
                icon = colIconB64;
                iconDesc = msg(R.string.l10n_node_mint_embedded_image, colIconB64.length());
            } else if (colIconDefault != null && !colIconDefault.isEmpty()) {
                icon = colIconDefault;                      // already shrunk, off the UI thread
                iconDesc = msg(R.string.l10n_node_collection_icon_small, icon.length());
            } else if (colImages.containsKey(1)) {
                // Mirror item #1 — but SHRUNK to the icon budget first.
                //
                // The token's metadata is carried in every transaction that touches the token, not
                // once on the chain. Sealing a full-size state plate (up to STATE_IMG_BUDGET) as
                // the icon made the very first MOVE 80KB against the 64KB TxPoW cap, and since
                // metadata is immutable the whole collection was bricked on creation.
                icon = ImageTools.recompressBase64(colImages.get(1), ICON_BUDGET);
                iconDesc = icon.isEmpty()
                        ? msg(R.string.l10n_node_collection_icon_failed)
                        : msg(R.string.l10n_node_collection_icon_small, icon.length());
            } else if (!colEmbedMode && !base.isEmpty()) {
                icon = base + 1 + ext;                          // url-mode collections: item #1's URL
                iconDesc = icon;
            } else {
                icon = "";
                iconDesc = msg(R.string.l10n_node_collection_identicon);
            }
        } else {
            if (!iconUrl.isEmpty() && badUrl(iconUrl)) { status(cStatus, msg(R.string.l10n_node_mint_icon_url_invalid), false); return; }
            icon = iconUrl;
            iconDesc = iconUrl.isEmpty() ? msg(R.string.l10n_node_collection_identicon) : iconUrl;
        }
        // Last line of defence: an oversized icon bricks the collection permanently, so refuse to
        // start rather than seal one. Only embedded icons can be oversized; URLs are tiny.
        if (colIconEmbed && !icon.isEmpty() && icon.length() > ICON_BUDGET) {
            status(cStatus, msg(R.string.l10n_node_collection_icon_large, icon.length(), ICON_BUDGET), false);
            return;
        }
        final String extUrl = cExtUrl.getText().toString().trim();
        if (!extUrl.isEmpty() && badUrl(extUrl)) { status(cStatus, msg(R.string.l10n_node_mint_external_url_invalid), false); return; }
        // Whole-definition guard, EXACT (the Atelier joint model): the record the engine
        // will seal plus the largest picked image must clear the transfer budget, and the
        // engine may still have to drop the creator signature. Slim the icon first; refuse
        // rather than seal an unsplittable token. The engine re-checks with the true
        // weights before tokencreate regardless — this is the friendly early warning.
        String iconGuarded = icon;
        CharSequence iconDescGuarded = iconDesc;
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
                    iconDescGuarded = msg(R.string.l10n_node_collection_slim_icon);
                    g = "sign";
                } else if ("sign".equals(StateNft.jointGate(colDefMetaLen(name, desc, extUrl, ""), maxImg))) {
                    iconGuarded = "";
                    iconDescGuarded = msg(R.string.l10n_node_collection_dropped_icon);
                    g = "sign";
                }
            }
            if (!"sign".equals(g)) { status(cStatus, MintMessages.status(g), false); return; }
        }
        final String iconF = iconGuarded;
        final CharSequence iconDescF = iconDescGuarded;
        final String webv = cWebv.getText().toString().trim();
        if (!webv.isEmpty() && badUrl(webv)) { status(cStatus, msg(R.string.l10n_node_mint_web_url_invalid), false); return; }
        cStatus.setVisibility(View.GONE);

        LinearLayout body = confirmBody();
        addConfirmRow(body, msg(R.string.l10n_node_gallery_collection), name);
        addConfirmRow(body, msg(R.string.l10n_node_collection_items), String.valueOf(size));
        addConfirmRow(body, msg(R.string.l10n_node_collection_mode), colEmbedMode ? msg(R.string.l10n_node_collection_embedded_images) : msg(R.string.l10n_node_collection_url_images));
        if (!colEmbedMode) addConfirmRow(body, msg(R.string.l10n_node_collection_images_at), base + "<i>" + ext);
        addConfirmRow(body, msg(R.string.l10n_node_balance_description), desc.isEmpty() ? NOT_SET : desc);
        addConfirmRow(body, msg(R.string.l10n_node_balance_web_validation), webv.isEmpty() ? NOT_SET : webv);
        addConfirmRow(body, msg(R.string.l10n_node_collection_wallet_icon), iconDescF);
        addConfirmRow(body, msg(R.string.l10n_node_collection_contract), msg(R.string.l10n_node_collection_locked_contract));
        TextView pipeline = new TextView(act);
        Language.setText(pipeline, msg(R.string.l10n_node_collection_pipeline));
        pipeline.setTextColor(Design.dim());
        pipeline.setTextSize(11f);
        pipeline.setPadding(0, dp(8), 0, 0);
        body.addView(pipeline);

        showConfirm(msg(R.string.l10n_node_collection_confirm), body, msg(R.string.l10n_node_collection_start), () ->
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
        status(cStatus, msg(R.string.l10n_node_collection_fetch_creator), true);
        act.node().cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                String pk = r == null ? "" : r.optString("publickey", "");
                String addr = r == null ? "" : r.optString("address", "");
                if (pk.isEmpty() || addr.isEmpty()) {
                    status(cStatus, msg(R.string.l10n_node_collection_creator_missing), false);
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
                status(cStatus, msg(R.string.l10n_node_send_failed, MintMessages.status(message)), false);
            }
        });
    }

    // ===================== PROGRESS =====================

    private void renderProgress() {
        JSONObject row = LocalStore.findById(act, progressRowId);
        if (row == null) { screen = Screen.HUB; renderHub(); return; }

        TextView back = new TextView(act);
        Language.setText(back, Language.join("‹  ", MintMessages.name(row)));
        back.setTextColor(Design.heading());
        back.setTextSize(16f);
        back.setTypeface(Design.typefaceBold());
        back.setPadding(0, 0, 0, dp(8));
        back.setOnClickListener(v -> { screen = Screen.HUB; refresh(); });
        container.addView(back);

        // phase chips
        String phase = row.optString("phase", "?");
        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.VERTICAL);
        String[] phases = {"CREATE", "MOVE", "SPLIT", "STAMP", "DONE"};
        int cur = java.util.Arrays.asList(phases).indexOf(phase);
        LinearLayout phaseRow = null;
        for (int i = 0; i < phases.length; i++) {
            if (i % 2 == 0) {
                phaseRow = new LinearLayout(act);
                phaseRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.bottomMargin = dp(4);
                chips.addView(phaseRow, rowParams);
            }
            TextView c = new TextView(act);
            Language.setText(c, Language.upper(MintMessages.phase(phases[i])));
            c.setTextSize(9f);
            c.setLetterSpacing(0.06f);
            c.setTypeface(Design.typefaceBold());
            c.setPadding(dp(8), dp(5), dp(8), dp(5));
            boolean done = cur > i || "DONE".equals(phase);
            boolean active = cur == i && !"DONE".equals(phase);
            c.setTextColor(active ? Design.onAccent() : done ? Design.success() : Design.dim());
            c.setBackgroundColor(active ? Design.accent() : Design.surface());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 1) clp.leftMargin = dp(4);
            c.setLayoutParams(clp);
            phaseRow.addView(c);
        }
        container.addView(chips);
        if (cur < 0) {
            TextView currentPhase = new TextView(act);
            Language.setText(currentPhase, MintMessages.phase(phase));
            currentPhase.setTextColor(Design.dim());
            currentPhase.setTextSize(12f);
            currentPhase.setPadding(0, dp(6), 0, 0);
            container.addView(currentPhase);
        }

        if ("NEEDIMAGES".equals(phase)) {
            TextView warn = new TextView(act);
            Language.setText(warn, msg(R.string.l10n_node_collection_paused_images));
            warn.setTextColor(Design.amber());
            warn.setTextSize(12f);
            warn.setPadding(0, dp(8), 0, 0);
            container.addView(warn);
        }
        if (MintDriver.isStuck(row)) {
            TextView stuck = new TextView(act);
            Language.setText(stuck, msg(R.string.l10n_node_collection_stuck));
            stuck.setTextColor(Design.red());
            stuck.setTextSize(12f);
            stuck.setPadding(0, dp(10), 0, dp(4));
            container.addView(stuck);
        }
        String err = row.optString("error", "");
        if (!err.isEmpty() && !"NEEDIMAGES".equals(phase)) {
            TextView e = new TextView(act);
            Language.setText(e, MintMessages.status(err));
            e.setTextColor(Design.red());
            e.setTextSize(11f);
            e.setPadding(0, dp(6), 0, 0);
            container.addView(e);
        }
        String engineMsg = act.mintStatus();
        if (!engineMsg.isEmpty()) {
            TextView s = new TextView(act);
            Language.setText(s, msg(R.string.l10n_node_collection_engine, MintMessages.status(engineMsg)));
            s.setTextColor(Design.dim());
            s.setTextSize(10f);
            s.setPadding(0, dp(4), 0, 0);
            container.addView(s);
        }

        // token id, once known
        String tokenid = row.optString("tokenid", "");
        if (!tokenid.isEmpty()) {
            TextView tid = new TextView(act);
            Language.setText(tid, msg(R.string.l10n_node_collection_copy_id, Util.shorten(tokenid)));
            tid.setTextColor(Design.dim());
            tid.setTextSize(11f);
            tid.setTypeface(android.graphics.Typeface.MONOSPACE);
            tid.setPadding(0, dp(6), 0, 0);
            tid.setOnClickListener(v -> CoinDetailDialog.copy(act, msg(R.string.l10n_wallet_token_id).toString(), tokenid));
            container.addView(tid);
        }

        // item rows
        org.json.JSONArray items = MintEngine.localItems(row);
        TextView lbl = new TextView(act);
        Language.setText(lbl, Language.upper(msg(R.string.l10n_node_collection_items)));
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
            Language.setText(n, "#" + idx + (coinid.isEmpty() ? "" : "  ·  " + Util.shorten(coinid)));
            n.setTextColor(Design.text());
            n.setTextSize(12f);
            n.setTypeface(android.graphics.Typeface.MONOSPACE);
            r.addView(n, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final TextView st = new TextView(act);
            boolean needImage = "embed".equals(row.optString("mode")) && it.optString("image", "").isEmpty();
            Language.setText(st, !coinid.isEmpty() ? msg(R.string.l10n_node_collection_stamped) : needImage ? msg(R.string.l10n_node_collection_pick_image) : msg(R.string.l10n_node_collection_queued));
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
            Language.setText(repost, msg(R.string.l10n_node_collection_repost_link));
            repost.setTextColor(Design.amber());
            repost.setTextSize(13f);
            repost.setTypeface(Design.typefaceBold());
            repost.setPadding(0, dp(14), 0, dp(4));
            repost.setOnClickListener(v -> Sheet.create(act, msg(R.string.l10n_node_collection_repost_title)).stackActions()
                .subtitle(msg(R.string.l10n_node_collection_repost_warning))
                .action(msg(R.string.l10n_node_collection_wait_longer), Sheet.Style.SECONDARY, null)
                .action(msg(R.string.l10n_node_collection_repost), Sheet.Style.PRIMARY, () -> {
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
            Language.setText(buryAll, msg(R.string.l10n_node_collection_bury_all));
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

        if (!"DONE".equals(phase) && !"BURIED".equals(phase) && !MintDriver.isStuck(row)) {
            container.addView(primaryButton(msg(R.string.l10n_node_collection_resume), v -> {
                MintDriver.Result r = act.mintEngineTick();
                Toast.makeText(act, MintMessages.status(MintDriver.explain(r)), Toast.LENGTH_SHORT).show();
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
        } else if ("DONE".equals(phase)) {
            TextView doneNote = new TextView(act);
            Language.setText(doneNote, msg(R.string.l10n_node_collection_done));
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
                        Toast.makeText(act, msg(R.string.l10n_node_collection_images_saved),
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
        status(statusView, msg(R.string.l10n_node_mint_minting), true);
        act.node().cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (json.optBoolean("status", false)) {
                    boolean nft = "NFT".equals(what);
                    status(statusView, msg(nft ? R.string.l10n_node_mint_nft_minted_status : R.string.l10n_node_mint_token_minted_status), true);
                    resultDialog(msg(nft ? R.string.l10n_node_mint_nft_minted : R.string.l10n_node_mint_token_minted),
                            msg(nft ? R.string.l10n_node_mint_nft_minted_help : R.string.l10n_node_mint_token_minted_help));
                    act.reload();
                } else {
                    String err = json.optString("error", json.optString("message", "Mint failed."));
                    status(statusView, msg(R.string.l10n_node_send_failed, SystemMessages.message(err)), false);
                }
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "Enable this wallet in Minima Core → Apps first.";
                status(statusView, msg(R.string.l10n_node_send_failed, SystemMessages.message(message)), false);
            }
        });
    }

    private void resultDialog(CharSequence title, CharSequence message) {
        Sheet.create(act, title).stackActions()
                .subtitle(message)
                .action(msg(R.string.l10n_language_close), Sheet.Style.SECONDARY, null)
                .action(msg(R.string.l10n_node_mint_view_balances), Sheet.Style.PRIMARY, () -> act.goToTab(WalletActivity.TAB_BALANCES))
                .show();
    }

    private LinearLayout confirmBody() {
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Design.bg());
        body.setPadding(dp(18), dp(10), dp(18), dp(4));
        return body;
    }

    private void addConfirmRow(LinearLayout body, CharSequence label, CharSequence value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(3), 0, dp(3));
        TextView l = new TextView(act);
        Language.setText(l, label);
        l.setTextColor(Design.dim());
        l.setTextSize(12f);
        r.addView(l, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView v = new TextView(act);
        Language.setText(v, value);
        v.setTextColor(value == NOT_SET ? Design.dim() : Design.text());
        if (value == NOT_SET) v.setTypeface(null, Typeface.ITALIC);
        v.setTextSize(12f);
        v.setGravity(Gravity.START);
        r.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(r);
    }

    private void showConfirm(CharSequence title, LinearLayout body, CharSequence action, Runnable go) {
        TextView warn = new TextView(act);
        Language.setText(warn, msg(R.string.l10n_node_mint_warning));
        warn.setTextColor(Design.dim());
        warn.setTextSize(11f);
        warn.setPadding(0, dp(10), 0, 0);
        body.addView(warn);
        Sheet.create(act, title).stackActions()
                .body(body)
                .action(msg(R.string.l10n_common_back), Sheet.Style.SECONDARY, null)
                .action(action, Sheet.Style.PRIMARY, go::run)
                .show();
    }

    // ---- field + validation helpers ----

    private TextView backRow(CharSequence title) {
        TextView t = new TextView(act);
        Language.setText(t, Language.join("‹  ", title));
        t.setTextColor(Design.heading());
        t.setTextSize(16f);
        t.setTypeface(Design.typefaceBold());
        t.setPadding(0, 0, 0, dp(10));
        t.setOnClickListener(v -> { screen = Screen.HUB; refresh(); });
        return t;
    }

    private EditText addField(LinearLayout into, CharSequence label, CharSequence hint, int inputType) {
        TextView l = new TextView(act);
        Language.setText(l, label);
        l.setTextColor(Design.dim());
        l.setTextSize(12f);
        l.setPadding(0, dp(10), 0, dp(4));
        into.addView(l);
        EditText e = plainField(hint, inputType);
        into.addView(e);
        return e;
    }

    private EditText addWeightedField(LinearLayout row, CharSequence label, CharSequence hint, int inputType, float weight) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        boolean stacked = act.getResources().getConfiguration().fontScale > 1.2f;
        if (stacked) row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                stacked ? LinearLayout.LayoutParams.MATCH_PARENT : 0, LinearLayout.LayoutParams.WRAP_CONTENT, stacked ? 0f : weight);
        if (!stacked && row.getChildCount() > 0) clp.leftMargin = dp(8);
        col.setLayoutParams(clp);
        TextView l = new TextView(act);
        Language.setText(l, label);
        l.setTextColor(Design.dim());
        l.setTextSize(12f);
        l.setPadding(0, dp(10), 0, dp(4));
        col.addView(l);
        EditText e = plainField(hint, inputType);
        col.addView(e);
        row.addView(col);
        return e;
    }

    private EditText plainField(CharSequence hint, int inputType) {
        EditText e = new EditText(act);
        Language.hint(e, hint);
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

    private android.widget.Button primaryButton(CharSequence label, View.OnClickListener click) {
        android.widget.Button b = new android.widget.Button(act);
        Language.setText(b, label);
        b.setAllCaps(false);
        // Reuse Sheet's explicit filled shape: this activity's framework Button style is transparent.
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(dp(Math.max(10, (int) Design.radiusDp() * 2)));
        shape.setColor(Design.accent());
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((Design.onAccent() & 0x00FFFFFF) | 0x40000000), shape, null));
        b.setTextColor(Design.onAccent());
        b.setTypeface(Design.typefaceBold());
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
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

    private void status(TextView view, CharSequence msg, boolean ok) {
        view.setVisibility(View.VISIBLE);
        Language.setText(view, msg);
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
