package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * History tab — a faithful mirror of the standalone Minima History app: the SAME rows (↓received /
 * ↑sent / ⟲self glyph + signed amount + token, counterparty · time, #block) and the SAME tap-detail
 * (per-token effect + full inputs/outputs breakdown). Direction + amount come from the node's
 * details.difference (net per-token effect on the wallet). On-chain history is persisted (nodetx),
 * so it accumulates and shows instantly + offline; pages are adaptive to stay under the node's 256 KB cap.
 */
public class HistoryView extends BaseView {

    private static final int CAP = 1000;       // rows kept + shown

    private final LinearLayout container;
    private int pageMax = 8;                    // adaptive page size — shrinks under the 256 KB cap
    private boolean fetching = false;
    private int lastFetchBlock = -1;
    private boolean moreAvailable = false;
    /** Row filter: null = all, else "sent" / "received" / "self". */
    private String filter = null;
    /** Node-side offset reached so far — counts RAW txpows (incl. skipped non-transactions),
     *  so "Load older" never re-requests a page whose entries were all filtered out. */
    private int nextOffset = 0;

    public HistoryView(WalletActivity a) {
        super(a, R.layout.view_history);
        container = find(R.id.historyContainer);
        render();   // show whatever is already persisted, instantly + offline
    }

    @Override public void refresh() { if (visible() && !fetching) render(); }
    @Override public void onShown() { render(); fetch(true); }
    @Override public void onNewBlock() { if (visible()) fetch(false); }
    private boolean visible() { return act.currentTab() == WalletActivity.TAB_HISTORY; }

    private void fetch(boolean force) {
        if (fetching) return;
        if (!force && act.chainBlock() <= lastFetchBlock) return;
        fetchPage(0);
    }

    private void loadOlder() { if (!fetching) fetchPage(nextOffset); }

    /** One adaptive `history` page: shrink (8→4→2→1) + retry under the 256 KB cap; persist new rows; re-render. */
    private void fetchPage(final int offset) {
        fetching = true;
        act.node().cmd("history relevant:true max:" + pageMax + " offset:" + offset, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                JSONArray txpows = resp == null ? null : resp.optJSONArray("txpows");
                if (resp == null || txpows == null || !json.optBoolean("status", true)) {
                    if (pageMax > 1) { pageMax = Math.max(1, pageMax / 2); fetchPage(offset); return; }
                    fetching = false; render(); return;
                }
                fetching = false;
                lastFetchBlock = act.chainBlock();
                JSONArray details = resp.optJSONArray("details");
                int got = txpows.length();
                if (offset + got > nextOffset) nextOffset = offset + got;
                for (int i = 0; i < got; i++) {
                    JSONObject t = txpows.optJSONObject(i);
                    if (t == null || !t.optBoolean("istransaction", false)) continue;
                    NodeTx n = NodeTx.from(t, details != null && i < details.length() ? details.optJSONObject(i) : null);
                    if (!n.txpowid.isEmpty()) act.history().upsertNodeTx(n);
                }
                moreAvailable = got >= pageMax && act.history().nodeTxCount() < CAP;
                render();
            }
            @Override public void onError(String message) {
                if (pageMax > 1) { pageMax = Math.max(1, pageMax / 2); fetchPage(offset); return; }
                fetching = false; render();
            }
        });
    }

    // ----- render (identical to the History app's list) -----

    private void render() {
        root.setBackgroundColor(Design.bg());          // was showing dark in light mode
        container.setBackgroundColor(Design.bg());
        container.removeAllViews();
        container.addView(filterBar());
        List<NodeTx> rows = act.history().loadNodeTx(CAP);
        if (rows.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText(fetching ? "Loading history…" : "No transactions yet.");
            empty.setTextColor(Design.dim());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, 0);
            container.addView(empty);
            return;
        }
        int shown = 0;
        for (NodeTx n : rows) {
            if (filter != null && !filter.equals(n.direction)) continue;
            container.addView(row(n));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(act);
            empty.setText("No " + filter + " transactions.");
            empty.setTextColor(Design.dim());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(30), 0, 0);
            container.addView(empty);
        }
        if (moreAvailable) {
            TextView more = new TextView(act);
            more.setText("Load older ▾");
            more.setTextColor(Design.accent());
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(12), 0, dp(8));
            more.setOnClickListener(v -> loadOlder());
            container.addView(more);
        }
    }

    /** All / Sent / Received / Self chips + the CSV/JSON export action. */
    private View filterBar() {
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), dp(2), dp(4), dp(8));
        String[][] opts = {{"ALL", null}, {"SENT", "sent"}, {"RECEIVED", "received"}, {"SELF", "self"}};
        for (String[] o : opts) {
            final String value = o[1];
            TextView c = new TextView(act);
            c.setText(o[0]);
            c.setTextSize(10f);
            c.setLetterSpacing(0.08f);
            c.setTypeface(Design.typefaceBold());
            c.setPadding(dp(10), dp(6), dp(10), dp(6));
            boolean on = (filter == null && value == null) || (filter != null && filter.equals(value));
            c.setTextColor(on ? Design.onAccent() : Design.dim());
            c.setBackgroundColor(on ? Design.accent() : Design.surface2());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(4);
            c.setLayoutParams(lp);
            c.setOnClickListener(v -> { filter = value; render(); });
            bar.addView(c);
        }
        View spacer = new View(act);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView export = new TextView(act);
        export.setText("⇩ EXPORT");
        export.setTextSize(10f);
        export.setLetterSpacing(0.08f);
        export.setTypeface(Design.typefaceBold());
        export.setTextColor(Design.accent());
        export.setPadding(dp(8), dp(6), dp(4), dp(6));
        export.setOnClickListener(v -> exportDialog());
        bar.addView(export);
        return bar;
    }

    /** Share the (filtered) history as CSV or JSON via the system share sheet. */
    private void exportDialog() {
        Sheet.create(act, "Export history")
                .subtitle("Shares the rows currently shown, using the active filter.")
                .action("CSV", Sheet.Style.SECONDARY, () -> shareExport(true))
                .action("JSON", Sheet.Style.PRIMARY, () -> shareExport(false))
                .show();
    }

    private void shareExport(boolean csv) {
        List<NodeTx> rows = act.history().loadNodeTx(CAP);
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        try {
            if (csv) {
                sb.append("time,direction,amount,token,counterparty,block,txpowid\n");
                for (NodeTx n : rows) {
                    if (filter != null && !filter.equals(n.direction)) continue;
                    sb.append(fmt.format(new Date(n.timemilli))).append(',')
                      .append(n.direction).append(',')
                      .append(Util.tidyAmount(n.amount)).append(',')
                      .append(csvSafe(n.tokenName)).append(',')
                      .append(csvSafe(n.counterparty)).append(',')
                      .append(n.block).append(',')
                      .append(n.txpowid).append('\n');
                }
            } else {
                JSONArray arr = new JSONArray();
                for (NodeTx n : rows) {
                    if (filter != null && !filter.equals(n.direction)) continue;
                    JSONObject o = new JSONObject();
                    o.put("time", fmt.format(new Date(n.timemilli)));
                    o.put("direction", n.direction);
                    o.put("amount", Util.tidyAmount(n.amount));
                    o.put("token", n.tokenName);
                    o.put("counterparty", n.counterparty == null ? "" : n.counterparty);
                    o.put("block", n.block);
                    o.put("txpowid", n.txpowid);
                    arr.put(o);
                }
                sb.append(arr.toString());
            }
        } catch (Exception e) {
            Toast.makeText(act, "Export failed.", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_SUBJECT,
                "NFT Wallet history export (" + (csv ? "CSV" : "JSON") + ")");
        send.putExtra(android.content.Intent.EXTRA_TEXT, sb.toString());
        try { act.startActivity(android.content.Intent.createChooser(send, "Export history")); }
        catch (Exception e) { Toast.makeText(act, "No app can receive the export.", Toast.LENGTH_SHORT).show(); }
    }

    /** Quote a CSV field when it carries a comma/quote; escape embedded quotes. */
    private static String csvSafe(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private View row(final NodeTx n) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(11), dp(8), dp(11));

        int color = "received".equals(n.direction) ? 0xFF1EA85A          // green in / red out (both themes)
                : "sent".equals(n.direction) ? Design.red() : Design.dim();
        String g = "received".equals(n.direction) ? "↓" : "sent".equals(n.direction) ? "↑" : "⟲";
        String sign = n.incoming ? "+" : "sent".equals(n.direction) ? "−" : "";

        TextView glyph = new TextView(act);
        glyph.setText(g); glyph.setTextColor(color); glyph.setTextSize(18f); glyph.setWidth(dp(28));
        row.addView(glyph);

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(6), 0, dp(6), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        boolean reshuffle = n.isReshuffle();
        TextView line1 = new TextView(act);
        line1.setText(reshuffle ? n.grossDisplay() : (sign + Format.amount(n.amount) + "  " + n.tokenName));
        line1.setTextColor(color); line1.setTextSize(15f); line1.setTypeface(Typeface.DEFAULT_BOLD);
        TextView line2 = new TextView(act);
        String cp = (n.counterparty == null || n.counterparty.isEmpty()) ? "" : Util.shorten(n.counterparty) + "  ·  ";
        line2.setText((reshuffle ? n.reshuffleLabel() + "  ·  " : cp) + relative(n.timemilli));
        line2.setTextColor(Design.dim()); line2.setTextSize(12f);
        mid.addView(line1); mid.addView(line2);
        row.addView(mid);

        TextView right = new TextView(act);
        right.setText("#" + n.block); right.setTextColor(Design.dim()); right.setTextSize(11f); right.setGravity(Gravity.END);
        row.addView(right);

        row.setOnClickListener(v -> showDetail(n));
        return row;
    }

    // ----- detail dialog (identical to the History app) -----

    private void showDetail(NodeTx n) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        kv(box, "Direction", n.direction);
        kv(box, "Amount", (n.incoming ? "+" : "sent".equals(n.direction) ? "−" : "") + Util.tidyAmount(n.amount) + " " + n.tokenName);
        kv(box, "Block", String.valueOf(n.block));
        kv(box, "Time", new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.ENGLISH).format(new Date(n.timemilli)));
        copyRow(box, "Txpow id", n.txpowid);
        if (n.counterparty != null && !n.counterparty.isEmpty()) {
            addressRow(box, n.incoming ? "From" : "To", n.counterparty);
        }
        kv(box, "Per-token effect", prettyDeltas(n.deltas));
        addBreakdown(box, "Inputs", n.inputs);
        addBreakdown(box, "Outputs", n.outputs);
        Sheet.create(act, "Transaction")
                .body(box)
                .action("Close", Sheet.Style.SECONDARY, null)
                .show();
    }

    private void kv(LinearLayout p, String k, String v) {
        TextView t = new TextView(act);
        t.setText(k + ":  " + v);
        t.setTextColor(Design.text()); t.setTextSize(13f); t.setPadding(0, dp(4), 0, dp(4));
        p.addView(t);
    }

    private void copyRow(LinearLayout p, String k, final String v) {
        TextView l = new TextView(act);
        l.setText(k);
        l.setTextColor(Design.dim()); l.setTextSize(11.5f); l.setPadding(0, dp(7), 0, dp(1));
        p.addView(l);
        TextView t = new TextView(act);
        t.setText(v);
        t.setTextColor(Design.text()); t.setTextSize(11f); t.setTypeface(Typeface.MONOSPACE);
        t.setOnClickListener(view -> CoinDetailDialog.copy(act, k, v));
        p.addView(t);
    }

    private void addBreakdown(LinearLayout p, String title, String json) {
        try {
            JSONArray a = new JSONArray(json);
            if (a.length() == 0) return;
            TextView h = new TextView(act);
            h.setText(title);
            h.setTextColor(Design.accent()); h.setTextSize(12f); h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setPadding(0, dp(14), 0, dp(2));
            p.addView(h);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                String tid = c.optString("tokenid", "0x00");
                String tok = Util.isMinima(tid) ? "Minima" : Util.shorten(tid);
                addressRow(p, Util.tidyAmount(c.optString("amount", "")) + "  " + tok,
                        c.optString("addr", ""));
            }
        } catch (Exception ignored) {}
    }

    /**
     * One address, in full, tap-to-copy, with an answer to the only question that matters about a
     * change output: is it mine?
     *
     * Truncating these made the detail useless — you could not read where your change went, let
     * alone check it. The local address pool answers instantly for the common case; anything it
     * doesn't recognise is put to the node, whose {@code checkaddress relevant} flag is the
     * authoritative answer (the local pool is refreshed rarely and misses newaddress-minted ones).
     */
    private void addressRow(LinearLayout p, String label, final String addr) {
        if (addr == null || addr.isEmpty()) return;

        TextView l = new TextView(act);
        l.setText(label);
        l.setTextColor(Design.dim());
        l.setTextSize(11.5f);
        l.setPadding(0, dp(7), 0, dp(1));
        p.addView(l);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView a = new TextView(act);
        a.setText(addr);                       // FULL — never shortened
        a.setTextColor(Design.text());
        a.setTextSize(11f);
        a.setTypeface(Typeface.MONOSPACE);
        a.setOnClickListener(v -> CoinDetailDialog.copy(act, "Address", addr));
        row.addView(a, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView badge = new TextView(act);
        badge.setTextSize(9.5f);
        badge.setTypeface(Design.typefaceBold());
        badge.setLetterSpacing(0.06f);
        badge.setPadding(dp(6), 0, 0, 0);
        row.addView(badge);

        p.addView(row);
        markOwnership(addr, badge);
    }

    /** Cached across sheets — the same change address recurs constantly. */
    private static final java.util.HashMap<String, Boolean> OWN_CACHE = new java.util.HashMap<>();

    private void markOwnership(final String addr, final TextView badge) {
        if (isMineLocally(addr)) { setBadge(badge, true, "YOURS"); return; }
        Boolean cached = OWN_CACHE.get(addr);
        if (cached != null) { setBadge(badge, cached, cached ? "YOURS" : "NOT YOURS"); return; }
        if (!Util.isValidAddress(addr)) { badge.setText(""); return; }

        badge.setText("checking…");
        badge.setTextColor(Design.dim());
        act.node().cmd("checkaddress address:" + addr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                boolean mine = r != null && r.optBoolean("relevant", false);
                OWN_CACHE.put(addr, mine);
                setBadge(badge, mine, mine ? "YOURS" : "NOT YOURS");
            }
            @Override public void onError(String message) {
                // Say we don't know rather than implying it isn't yours.
                badge.setText("UNVERIFIED");
                badge.setTextColor(Design.amber());
            }
        });
    }

    private void setBadge(TextView badge, boolean mine, String text) {
        badge.setText(text);
        badge.setTextColor(mine ? Design.success() : Design.dim());
    }

    /** The wallet's known address pool, in either 0x or Mx form. */
    private boolean isMineLocally(String addr) {
        for (String[] pair : act.myAddresses()) {
            if (addr.equals(pair[0]) || addr.equals(pair[1])) return true;
        }
        return false;
    }

    private String prettyDeltas(String json) {
        try {
            JSONObject o = new JSONObject(json);
            StringBuilder sb = new StringBuilder();
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String tid = it.next();
                if (sb.length() > 0) sb.append(", ");
                sb.append(Util.tidyAmount(o.optString(tid, ""))).append(" ").append(Util.isMinima(tid) ? "Minima" : Util.shorten(tid));
            }
            return sb.length() > 0 ? sb.toString() : "—";
        } catch (Exception e) { return "—"; }
    }

    private static String relative(long ms) {
        if (ms <= 0) return "";
        long d = System.currentTimeMillis() - ms;
        if (d < 60000) return "just now";
        if (d < 3600000) return (d / 60000) + "m ago";
        if (d < 86400000) return (d / 3600000) + "h ago";
        if (d < 7 * 86400000L) return (d / 86400000) + "d ago";
        return new SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(new Date(ms));
    }

    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density); }
}
