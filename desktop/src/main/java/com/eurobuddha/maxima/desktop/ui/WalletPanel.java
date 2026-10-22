package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.desktop.wallet.DesktopNodeLink;
import com.eurobuddha.maxima.desktop.wallet.DesktopWallet;
import com.eurobuddha.maxima.desktop.wallet.DesktopWalletLedger;
import com.eurobuddha.maxima.desktop.wallet.DesktopWalletPublisher;
import com.eurobuddha.wallet.CoinSelector;
import com.eurobuddha.wallet.TokenInputs;
import com.eurobuddha.wallet.TxnFactory;

import org.minima.objects.base.MiniNumber;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Wallet tab — a FULLY LOCAL Minima wallet, the same as the phone (APK). The
 * seed, key derivation, transaction building and Winternitz signing all happen on
 * this machine ({@link DesktopWallet}); balances are read and the pre-signed txn is
 * relayed through the shipped read-only gateway (no node required) or, if you've
 * configured one, a connected node. Nothing about the wallet relies on a node.
 */
public final class WalletPanel extends JPanel implements MaximaWindow.Tab, MaximaWindow.Responsive {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;

    private final JPanel mBody = new JPanel();
    private int mSeg = 0;                     // 0 = Balance, 1 = History
    private JPanel mPaneHolder;
    private JPanel mActions;
    private JComponent mHeroAmount;

    private volatile DesktopWallet mWallet;   // built off-EDT (heavy WOTS derivation)
    private DesktopWalletPublisher mPub;
    private DesktopWalletLedger mLedger;
    private volatile boolean mBuilding;

    // Cached figures (native MINIMA), refreshed off-EDT.
    private volatile String mConf = "…", mUnconf = "0", mSpend = "…";
    private volatile org.json.JSONArray mBalanceArr;
    private long mLastFetch;
    private boolean mFetching;
    private boolean mBuilt;

    public WalletPanel(DesktopNode zNode, Theme zTheme) {
        node = zNode;
        t = zTheme;
        k = new DKit(zTheme);
        setLayout(new BorderLayout());
        setBackground(t.bg);
        mBody.setLayout(new BoxLayout(mBody, BoxLayout.Y_AXIS));
        mBody.setOpaque(false);
        mBody.setBorder(new EmptyBorder(20, 22, 22, 22));
        JScrollPane sp = new JScrollPane(new DKit.ScrollableColumn(k.centered(mBody, 720)),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getViewport().setBackground(t.bg);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        add(sp, BorderLayout.CENTER);
    }

    public String label() { return Language.text("nav.wallet"); }
    public JComponent view() { return this; }

    public void refresh() {
        if (!mBuilt) {
            mBuilt = true;
            buildScaffold();
            openWallet();
        }
        if (mWallet != null && !mFetching && System.currentTimeMillis() - mLastFetch > 6000) {
            mLastFetch = System.currentTimeMillis();
            fetchBalance();
        }
    }

    // ---- open the local wallet (heavy) ----

    private void openWallet() {
        if (mBuilding) return;
        mBuilding = true;
        // ONE construction path only. Both this and the in-chat payment path go
        // through ensureWalletOpenBlocking() (synchronized, null-guarded) so there
        // is never a second DesktopWallet instance — two instances would each own
        // a separate DesktopKeyUses lock and could reserve the SAME one-time WOTS
        // leaf twice (key-reuse = catastrophic). Never build/assign mWallet here.
        new Thread(() -> {
            try {
                ensureWalletOpenBlocking();   // heavy WOTS walk happens inside, under the lock
                javax.swing.SwingUtilities.invokeLater(() -> { mLastFetch = 0; rebuildPanes(); });
            } catch (Exception e) {
                javax.swing.SwingUtilities.invokeLater(() -> showError("Couldn't open wallet: " + e.getMessage()));
            } finally {
                mBuilding = false;
            }
        }, "wallet-open").start();
    }

    private void fetchBalance() {
        DesktopWallet w = mWallet;
        if (w == null || mPub == null) return;
        mFetching = true;
        mPub.balance(w.hexAddress(), new DesktopWalletPublisher.Cb() {
            public void onResult(org.json.JSONObject r) {
                try {
                    org.json.JSONArray arr = r.optJSONArray("response");
                    mBalanceArr = arr;
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject tk = arr.getJSONObject(i);
                            if ("0x00".equals(tk.optString("tokenid"))) {
                                mConf = tk.optString("confirmed", "0");
                                mUnconf = tk.optString("unconfirmed", "0");
                                mSpend = tk.optString("sendable", tk.optString("confirmed", "0"));
                            }
                        }
                    }
                } catch (Exception ignored) { }
                mFetching = false;
                javax.swing.SwingUtilities.invokeLater(WalletPanel.this::rebuildPanes);
            }
            public void onError(String m) {
                mFetching = false;
            }
        });
    }

    // ---- layout ----

    private void buildScaffold() {
        mBody.removeAll();
        DKit.Segmented seg = k.segmented(new CharSequence[]{Language.msg("wallet.balance"), Language.msg("wallet.history")}, mSeg, i -> {
            mSeg = i;
            rebuildPanes();
        });
        seg.setAlignmentX(Component.LEFT_ALIGNMENT);
        mBody.add(seg);
        mBody.add(k.vgap(14));
        mPaneHolder = new JPanel();
        mPaneHolder.setOpaque(false);
        mPaneHolder.setLayout(new BoxLayout(mPaneHolder, BoxLayout.Y_AXIS));
        mPaneHolder.setAlignmentX(Component.LEFT_ALIGNMENT);
        mBody.add(mPaneHolder);
        mBody.add(Box.createVerticalGlue());
        rebuildPanes();
    }

    private void rebuildPanes() {
        if (mPaneHolder == null) return;
        mPaneHolder.removeAll();
        mActions = null;
        mHeroAmount = null;
        if (mSeg == 0) buildBalance(); else buildHistory();
        if (getWidth() > 0) onWidth(getWidth());
        mPaneHolder.revalidate();
        mPaneHolder.repaint();
    }

    private void buildBalance() {
        // Hero.
        DKit.RoundPanel hero = k.round(t.accent, 18);
        hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
        hero.setBorder(new EmptyBorder(20, 20, 20, 20));
        hero.add(heroLabel(Language.msg("wallet.minimaBalance")));
        JComponent amt = mHeroAmount = mWallet == null ? k.sub(Language.msg("wallet.opening")) : exactValue(mConf);
        Language.font(amt, t.extrabold(30f));
        amt.setForeground(t.onAccent);
        amt.setAlignmentX(Component.LEFT_ALIGNMENT);
        hero.add(k.vgap(4));
        hero.add(amt);
        JLabel sub = k.sub("");
        k.renderSub(sub, () -> mWallet == null ? "" : Language.text("wallet.sendableAmount", "amount", mSpend));
        Language.font(sub, t.font(12.5f));
        sub.setForeground(DKit.alpha(t.onAccent, 190));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        hero.add(k.vgap(2));
        hero.add(sub);
        mPaneHolder.add(hero);
        mPaneHolder.add(k.vgap(12));

        // Actions.
        JPanel actions = mActions = rowX();
        DKit.HoverButton send = k.primaryButton(Language.msg("chat.send"));
        send.onClick(this::showSend);
        DKit.HoverButton receive = k.ghostButton(Language.msg("wallet.receive"));
        receive.onClick(this::showReceive);
        if (mWallet == null) { send.setButtonEnabled(false); receive.setButtonEnabled(false); }
        actions.add(send);
        actions.add(Box.createRigidArea(new Dimension(10, 0)));
        actions.add(receive);
        actions.add(Box.createHorizontalGlue());
        mPaneHolder.add(actions);
        mPaneHolder.add(k.vgap(14));

        // Four-field balance detail.
        DKit.RoundPanel bal = k.card();
        bal.add(detailLine(Language.msg("wallet.confirmed"), mWallet == null ? "…" : mConf));
        bal.add(k.divider());
        bal.add(detailLine(Language.msg("wallet.unconfirmed"), mWallet == null ? "…" : mUnconf));
        bal.add(k.divider());
        bal.add(detailLine(Language.msg("wallet.sendable"), mWallet == null ? "…" : mSpend));
        mPaneHolder.add(bal);
        mPaneHolder.add(k.vgap(14));

        // Sends remaining (one-time-signature budget) — the wallet's life.
        JPanel usesHead = new JPanel(new BorderLayout());
        usesHead.setOpaque(false);
        usesHead.setAlignmentX(Component.LEFT_ALIGNMENT);
        usesHead.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        usesHead.add(k.sectionLabel(Language.msg("wallet.used")), BorderLayout.WEST);
        JLabel usesHelp = new JLabel("?");
        Language.font(usesHelp, t.semibold(13f));
        usesHelp.setForeground(t.subtext);
        usesHelp.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        usesHelp.setBorder(new EmptyBorder(0, 8, 0, 6));
        usesHelp.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                DesktopExplain.show(WalletPanel.this, "keyuses", t);
            }
        });
        Language.tooltip(usesHelp, Language.msg("wallet.signatureHelp"));
        usesHead.add(usesHelp, BorderLayout.EAST);
        mPaneHolder.add(usesHead);
        mPaneHolder.add(k.vgap(8));
        DKit.RoundPanel uses = k.card();
        int used = 0;
        try { used = mWallet == null ? 0 : mWallet.uses(new java.io.File(node.dataDir().toFile(), "wallet")); }
        catch (Exception ignored) { }
        // Count UP: the big number is signatures USED (0 → MAX), exactly like the
        // phone. The remaining budget is the subtitle. A wallet spends key-uses;
        // it never "loses" a remaining count.
        final int usedCount = used;
        JLabel big = Language.render(new JLabel(), () -> mWallet == null ? "…" : count(usedCount));
        Language.font(big, t.extrabold(22f));
        big.setForeground(t.accent);
        big.setAlignmentX(Component.LEFT_ALIGNMENT);
        uses.add(big);
        uses.add(k.vgap(2));
        JLabel remaining = k.sub("");
        k.renderSub(remaining, () -> Language.text("wallet.budget", "remaining", count(DesktopWallet.MAX_USES - usedCount), "maximum", count(DesktopWallet.MAX_USES)));
        uses.add(remaining);
        mPaneHolder.add(uses);
        mPaneHolder.add(k.vgap(14));

        // Token cards (non-Minima tokens held).
        org.json.JSONArray arr = mBalanceArr;
        if (arr != null && arr.length() > 1) {
            mPaneHolder.add(k.sectionLabel(Language.msg("wallet.tokens")));
            mPaneHolder.add(k.vgap(8));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject tk = arr.optJSONObject(i);
                if (tk == null || "0x00".equals(tk.optString("tokenid"))) continue;
                mPaneHolder.add(tokenCard(tk));
                mPaneHolder.add(k.vgap(8));
            }
        }
    }

    private JComponent tokenCard(org.json.JSONObject tk) {
        String tokenid = tk.optString("tokenid");
        String name = tokenName(tk);
        DKit.RoundPanel card = k.card();
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.add(k.avatar(tokenid, name, 38), BorderLayout.WEST);
        JLabel nm = k.sub("");
        k.renderSub(nm, () -> tokenName(tk));
        Language.font(nm, t.semibold(14f), nm.getText());
        nm.setForeground(t.text);
        JPanel values = new JPanel();
        values.setOpaque(false);
        values.setLayout(new BoxLayout(values, BoxLayout.Y_AXIS));
        values.add(nm);
        values.add(k.vgap(4));
        DKit.WrapText bal = exactValue(tk.optString("confirmed", "0"));
        Language.font(bal, t.bold(14f));
        bal.setForeground(t.text);
        values.add(bal);
        row.add(values, BorderLayout.CENTER);
        card.add(row);
        card.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        java.awt.event.MouseAdapter openDetail = new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) { showTokenDetail(tk); }
        };
        card.addMouseListener(openDetail);
        bal.addMouseListener(openDetail);
        return card;
    }

    private void showTokenDetail(org.json.JSONObject tk) {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.add(detailLine(Language.msg("wallet.sendable"), tk.optString("sendable", tk.optString("confirmed", "0"))));
        body.add(detailLine(Language.msg("wallet.confirmed"), tk.optString("confirmed", "0")));
        body.add(detailLine(Language.msg("wallet.pending"), tk.optString("unconfirmed", "0")));
        if (tk.has("coins")) body.add(detailLine(Language.msg("wallet.coins"), tk.optString("coins", "0")));
        if (tk.has("total")) body.add(detailLine(Language.msg("wallet.supply"), tk.optString("total", "")));
        body.add(k.vgap(10));
        body.add(k.copyField(Language.msg("wallet.tokenId"), tk.optString("tokenid", "0x00"), false));
        dialog(tokenTitle(tk), body, 440);
    }

    private int mHistFilter = 0;   // 0 all · 1 sent · 2 received

    private void buildHistory() {
        mPaneHolder.add(k.sectionLabel(Language.msg("wallet.transactions")));
        mPaneHolder.add(k.vgap(8));
        JPanel chips = rowX();
        chips.add(filterChip(Language.msg("wallet.all"), 0));
        chips.add(Box.createRigidArea(new Dimension(8, 0)));
        chips.add(filterChip(Language.msg("wallet.sent"), 1));
        chips.add(Box.createRigidArea(new Dimension(8, 0)));
        chips.add(filterChip(Language.msg("wallet.received"), 2));
        chips.add(Box.createHorizontalGlue());
        mPaneHolder.add(chips);
        mPaneHolder.add(k.vgap(10));

        List<DesktopWalletLedger.Row> all = mLedger == null ? new ArrayList<>() : mLedger.list();
        List<DesktopWalletLedger.Row> rows = new ArrayList<>();
        for (DesktopWalletLedger.Row r : all) {
            if (mHistFilter == 1 && !r.sent) continue;
            if (mHistFilter == 2 && r.sent) continue;
            rows.add(r);
        }
        if (rows.isEmpty()) {
            DKit.RoundPanel c = k.card();
            c.add(k.sub(mHistFilter == 0 ? Language.msg("wallet.emptyHistory")
                    : Language.msg("wallet.emptyFilter")));
            mPaneHolder.add(c);
            return;
        }
        DKit.RoundPanel card = k.card();
        card.setBorder(new EmptyBorder(4, 8, 4, 8));
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) card.add(k.divider());
            card.add(historyRow(rows.get(i)));
        }
        mPaneHolder.add(card);
    }

    private JComponent filterChip(CharSequence label, int idx) {
        boolean on = mHistFilter == idx;
        DKit.RoundPanel chip = k.round(on ? t.accent : t.input, 999);
        chip.setLayout(new BoxLayout(chip, BoxLayout.X_AXIS));
        chip.setBorder(new EmptyBorder(6, 14, 6, 14));
        JLabel l = Language.bind(new JLabel(), label);
        Language.font(l, t.semibold(11.5f));
        l.setForeground(on ? t.onAccent : t.subtext);
        chip.add(l);
        chip.setMaximumSize(chip.getPreferredSize());
        chip.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        chip.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) { mHistFilter = idx; rebuildPanes(); }
        });
        return chip;
    }

    private JComponent historyRow(DesktopWalletLedger.Row r) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(9, 6, 9, 6));
        row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        row.add(directionDisc(r.sent), BorderLayout.WEST);
        JPanel mid = new JPanel();
        mid.setOpaque(false);
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        JLabel who = k.sub("");
        k.renderSub(who, () -> Language.text(r.sent ? "wallet.sentTo" : "wallet.receivedFrom", "address", clip(r.who, 22)));
        Language.font(who, t.semibold(13f), who.getText());
        who.setForeground(t.text);
        who.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel when = Language.render(new JLabel(), () -> Language.date(r.time, "dd MMM HH:mm"));
        Language.font(when, t.font(11f));
        when.setForeground(t.subtext);
        when.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(who);
        mid.add(when);
        row.add(mid, BorderLayout.CENTER);
        DKit.WrapText amt = exactValue((r.sent ? "-" : "+") + r.amount + " " + r.token);
        Language.font(amt, t.bold(13.5f), amt.getText());
        amt.setForeground(r.sent ? t.text : t.success);
        mid.add(k.vgap(4));
        mid.add(amt);
        // RULE 1: the row truncates the address; the full to/from address + the
        // full transaction id are one click away in the detail sheet.
        java.awt.event.MouseAdapter openDetail = new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) { showHistoryDetail(r); }
        };
        row.addMouseListener(openDetail);
        amt.addMouseListener(openDetail);
        return row;
    }

    /** A 40px disc with an up (sent) / down (received) arrow. */
    private JComponent directionDisc(boolean sent) {
        final Color c = sent ? t.text : t.success;
        JComponent disc = new JComponent() {
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(DKit.alpha(c, 28));
                g2.fillOval(2, 2, 36, 36);
                g2.setColor(c);
                g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                int cx = 20, cy = 20;
                if (sent) {
                    g2.drawLine(cx, cy + 6, cx, cy - 6);
                    g2.drawLine(cx, cy - 6, cx - 4, cy - 2);
                    g2.drawLine(cx, cy - 6, cx + 4, cy - 2);
                } else {
                    g2.drawLine(cx, cy - 6, cx, cy + 6);
                    g2.drawLine(cx, cy + 6, cx - 4, cy + 2);
                    g2.drawLine(cx, cy + 6, cx + 4, cy + 2);
                }
                g2.dispose();
            }
        };
        disc.setPreferredSize(new Dimension(40, 40));
        return disc;
    }

    private void showHistoryDetail(DesktopWalletLedger.Row r) {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.add(detailLine(Language.msg("wallet.direction"), r.sent ? Language.msg("wallet.sent") : Language.msg("wallet.received")));
        body.add(detailLine(Language.msg("wallet.amount"), (r.sent ? "-" : "+") + r.amount + " " + r.token));
        body.add(detailLine(Language.msg("wallet.when"), () -> Language.date(r.time, "d MMM yyyy · HH:mm")));
        body.add(k.vgap(10));
        body.add(k.copyField(r.sent ? Language.msg("wallet.to") : Language.msg("wallet.from"),
                r.who == null || r.who.isEmpty() ? "—" : r.who, false));
        if (r.tokenid != null && !r.tokenid.isEmpty()) {
            body.add(k.vgap(8));
            body.add(k.copyField(Language.msg("wallet.tokenId"), r.tokenid, false));
        }
        if (r.txid != null && !r.txid.isEmpty()) {
            body.add(k.vgap(8));
            body.add(k.copyField(Language.msg("wallet.transactionId"), r.txid, false));
        }
        dialog(Language.msg("wallet.transaction"), body, 460);
    }

    // ---- send / receive ----

    private void showSend() {
        final DesktopWallet w = mWallet;
        if (w == null) return;
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        JLabel explanation = k.sub("");
        k.renderSub(explanation, () -> Language.text("wallet.sendHelp", "backend", systemText(mPub.backendName())));
        body.add(explanation);
        body.add(k.vgap(10));
        JTextField to = k.field(Language.msg("wallet.addressHint"));
        to.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        to.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(to);
        body.add(k.vgap(8));
        javax.swing.JRadioButton usd = currencyChoice(body);
        JTextField amt = k.field(Language.msg("wallet.amountHint"));
        amt.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        amt.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(amt);
        body.add(k.vgap(12));
        final JLabel status = k.sub(" ");
        JPanel r = rowX();
        DKit.HoverButton sign = k.primaryButton(Language.msg("desktopChat.signSend"));
        r.add(sign);
        r.add(Box.createHorizontalGlue());
        body.add(r);
        body.add(k.vgap(6));
        body.add(status);
        JDialog d = dialog(Language.msg("chat.send"), body, 440);
        sign.onClick(() -> {
            String addr = to.getText().trim();
            String a = amt.getText().trim();
            if (addr.isEmpty() || a.isEmpty()) { k.setSub(status, Language.msg("wallet.addressAmountRequired")); return; }
            String addrErr = validateRecipient(addr);
            if (addrErr != null) { k.renderSub(status, () -> systemText(addrErr)); return; }
            final MiniNumber amount;
            try { amount = new MiniNumber(a); } catch (Exception e) { k.setSub(status, Language.msg("desktopChat.badAmount")); return; }
            if (!amount.isMore(MiniNumber.ZERO)) { k.setSub(status, Language.msg("desktopChat.positiveAmount")); return; }
            sign.setButtonEnabled(false);
            k.setSub(status, Language.msg("wallet.selecting"));
            final String tokenId = usd.isSelected() ? TokenInputs.MXUSD_TOKENID : TokenInputs.MINIMA_TOKENID;
            new Thread(() -> doSend(w, addr, amount, tokenId, status, sign, d), "wallet-send").start();
        });
    }

    /** Minima / MxUSD radio pair added to {@code zBody}; returns the MxUSD button. */
    static javax.swing.JRadioButton currencyChoice(JComponent zBody) {
        javax.swing.JRadioButton minima = new javax.swing.JRadioButton("Minima", true);
        javax.swing.JRadioButton usd = new javax.swing.JRadioButton(TokenInputs.MXUSD_NAME);
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        g.add(minima);
        g.add(usd);
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (javax.swing.JRadioButton b : new javax.swing.JRadioButton[]{minima, usd}) {
            b.setOpaque(false);
            row.add(b);
        }
        zBody.add(row);
        zBody.add(Box.createVerticalStrut(6));
        return usd;
    }

    private void doSend(DesktopWallet w, String to, MiniNumber amount, String tokenId,
                        JLabel status, DKit.HoverButton sign, JDialog d) {
        signAndPublish(w, to, amount, tokenId, new PayResult() {
            public void onStatus(String s) { uiStatus(status, s); }
            public void onTxid(String txid) {
                uiStatus(status, "Sent ⛏ (mining into a block)");
                javax.swing.SwingUtilities.invokeLater(() -> {
                    rebuildPanes();
                    javax.swing.Timer tm = new javax.swing.Timer(1400, e -> d.dispose());
                    tm.setRepeats(false); tm.start();
                });
            }
            public void onError(String m) {
                String msg = (m == null || m.isEmpty()) ? "unknown error" : m;
                uiStatus(status, msg.toLowerCase().contains("funds") ? msg : "Send failed: " + msg);
                javax.swing.SwingUtilities.invokeLater(() -> sign.setButtonEnabled(true));
            }
        });
    }

    /** Validate a payment recipient before we ever select coins or sign — a
     *  typo'd/short 0x address or a burn address would send funds into the void.
     *  Returns an error string to show, or null if the address is well-formed. */
    private static String validateRecipient(String zAddr) {
        String s = zAddr.trim();
        String low = s.toLowerCase();
        if (low.startsWith("mx")) {
            if (low.startsWith("mx00")) return "That looks like a burn address.";
            try {
                org.minima.objects.base.MiniData d = org.minima.objects.Address.convertMinimaAddress(s);
                if (d == null) return "Invalid Mx address.";
            } catch (Exception e) {
                return "Invalid Mx address (bad checksum?).";
            }
            return null;
        }
        if (low.startsWith("0x")) {
            if (!s.matches("(?i)0x[0-9a-f]{64}")) {
                return "A 0x address must be 32 bytes (0x + 64 hex digits).";
            }
            if (low.substring(2).chars().allMatch(c -> c == '0')) return "That's the burn address.";
            return null;
        }
        return "Address must start with Mx or 0x.";
    }

    /** Result of a payment (status ticks, then exactly one of txid/error). */
    public interface PayResult {
        void onStatus(String s);
        void onTxid(String txid);
        void onError(String e);
    }

    /**
     * The ONE fund-signing path, shared by the Wallet send sheet and the in-chat
     * "Send payment" flow. There is a single DesktopWallet instance (this panel
     * owns it) so the one-time-signature key-use counter is never raced by two
     * instances — the WOTS key-reuse fund-loss hazard. Call OFF the EDT.
     */
    private void signAndPublish(DesktopWallet w, String to, MiniNumber amount, String tokenId, PayResult cb) {
        try {
            // 1. coins for our address (read, via gateway/node).
            final org.json.JSONObject[] resp = new org.json.JSONObject[1];
            final String[] err = new String[1];
            final Object lk = new Object();
            mPub.coins(w.hexAddress(), new DesktopWalletPublisher.Cb() {
                public void onResult(org.json.JSONObject r) { synchronized (lk) { resp[0] = r; lk.notifyAll(); } }
                public void onError(String m) { synchronized (lk) { err[0] = m; lk.notifyAll(); } }
            });
            synchronized (lk) {
                long deadline = System.currentTimeMillis() + 45_000;
                while (resp[0] == null && err[0] == null) {
                    long rem = deadline - System.currentTimeMillis();
                    if (rem <= 0) break;
                    lk.wait(rem);
                }
            }
            if (resp[0] == null) throw new IllegalStateException(err[0] != null ? err[0] : "coins timeout");

            // 2. select coins to cover, build InputCoins.
            org.minima.utils.json.JSONObject full = (org.minima.utils.json.JSONObject)
                    new org.minima.utils.json.parser.JSONParser().parse(resp[0].toString());
            org.minima.utils.json.JSONArray coins = (org.minima.utils.json.JSONArray) full.get("response");
            //    Token amounts are scaled to raw; token inputs come byte-exact via coinexport.
            final boolean minima = TokenInputs.isMinima(tokenId);
            final MiniNumber raw = TokenInputs.rawAmount(coins, tokenId, amount);
            List<org.minima.utils.json.JSONObject> sel =
                    CoinSelector.selectToCover(coins, minima ? "0x00" : tokenId, raw);
            List<TxnFactory.InputCoin> inputs = TokenInputs.resolve(sel, DesktopWallet.KEY_INDEX,
                    coinid -> coinExportHex(coinid));

            // 3. build + SIGN locally (this reserves a one-time key use).
            cb.onStatus("Signing on this device…");
            TxnFactory factory = new TxnFactory(w.core());
            final TxnFactory.BuiltTxn built = factory.buildSend(inputs, to, raw,
                    TokenInputs.tokenData(tokenId), MiniNumber.ZERO, "mxw" + System.currentTimeMillis());

            // 4. publish the SIGNED txn (relay only) via gateway/node.
            cb.onStatus("Publishing via " + mPub.backendName() + "…");
            mPub.publish(built.getTxnImportCommand(), built.getID(), built.getTxnPostCommand(),
                    new DesktopWalletPublisher.Cb() {
                public void onResult(org.json.JSONObject r) {
                    mLedger.add(true, amount.toString(), TokenInputs.nameOf(tokenId), to, built.getID(),
                            minima ? "0x00" : tokenId);
                    mLastFetch = 0;
                    cb.onTxid(built.getID());
                }
                public void onError(String m) { cb.onError(m); }
            });
        } catch (CoinSelector.InsufficientFundsException ife) {
            cb.onError("Not enough confirmed funds.");
        } catch (IllegalStateException ise) {
            cb.onError(String.valueOf(ise.getMessage()));
        } catch (Exception e) {
            // Never hand a null message downstream — the UI does .toLowerCase() on it.
            cb.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** {@code coinexport} through the same publisher (node or gateway), awaited. OFF the EDT. */
    private String coinExportHex(String zCoinId) throws Exception {
        final org.json.JSONObject[] resp = new org.json.JSONObject[1];
        final String[] err = new String[1];
        final Object lk = new Object();
        mPub.coinExport(zCoinId, new DesktopWalletPublisher.Cb() {
            public void onResult(org.json.JSONObject r) { synchronized (lk) { resp[0] = r; lk.notifyAll(); } }
            public void onError(String m) { synchronized (lk) { err[0] = m; lk.notifyAll(); } }
        });
        synchronized (lk) {
            long deadline = System.currentTimeMillis() + 45_000;
            while (resp[0] == null && err[0] == null) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                lk.wait(rem);
            }
        }
        if (resp[0] == null) throw new IllegalStateException(err[0] != null ? err[0] : "coinexport timeout");
        Object r = resp[0].opt("response");
        if (r instanceof org.json.JSONObject) {
            org.json.JSONObject o = (org.json.JSONObject) r;
            return o.optString("data", o.optString("coin", "")).trim();
        }
        return r == null ? null : String.valueOf(r).trim();
    }

    /** Open the single wallet instance if it isn't already (blocking; OFF the EDT). */
    private synchronized DesktopWallet ensureWalletOpenBlocking() throws Exception {
        java.io.File wdir = new java.io.File(node.dataDir().toFile(), "wallet");
        if (mLedger == null) mLedger = new DesktopWalletLedger(wdir);
        if (mPub == null) mPub = new DesktopWalletPublisher(DesktopNodeLink.configured());
        if (mWallet == null) {
            DesktopWallet w = DesktopWallet.open(node.seedPhrase(), wdir);
            w.ensureAddress();
            mPub.trackScript(w.script(), new DesktopWalletPublisher.Cb() {
                public void onResult(org.json.JSONObject r) { }
                public void onError(String m) { }
            });
            mWallet = w;
        }
        return mWallet;
    }

    /**
     * In-chat "Send payment": sign+publish a MINIMA payment to an address using
     * the same single wallet + key-use counter as the Wallet screen. Call OFF
     * the EDT; the callback fires the resulting txid so the chat can post the
     * payment bubble.
     */
    public void requestPayment(String toAddr, MiniNumber amount, PayResult cb) {
        requestPayment(toAddr, amount, TokenInputs.MINIMA_TOKENID, cb);
    }

    /** As {@link #requestPayment(String, MiniNumber, PayResult)} in Minima or a token (MxUSD). */
    public void requestPayment(String toAddr, MiniNumber amount, String tokenId, PayResult cb) {
        try {
            DesktopWallet w = ensureWalletOpenBlocking();
            signAndPublish(w, toAddr, amount, tokenId, cb);
        } catch (Exception e) {
            cb.onError(e.getMessage() == null ? "wallet unavailable" : e.getMessage());
        }
    }

    private void showReceive() {
        final DesktopWallet w = mWallet;
        if (w == null) return;
        String addr = w.mxAddress();
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        JLabel qr = new JLabel();
        qr.setAlignmentX(Component.CENTER_ALIGNMENT);
        java.awt.image.BufferedImage img = DesktopQr.encode(addr, 230,
                0xFF000000 | (t.text.getRGB() & 0xFFFFFF), 0xFFFFFFFF);
        if (img != null) qr.setIcon(new javax.swing.ImageIcon(img));
        JPanel qw = new JPanel();
        qw.setOpaque(false);
        qw.setLayout(new BoxLayout(qw, BoxLayout.X_AXIS));
        qw.add(Box.createHorizontalGlue());
        qw.add(qr);
        qw.add(Box.createHorizontalGlue());
        body.add(qw);
        body.add(k.vgap(12));
        body.add(k.copyField(Language.msg("wallet.yourAddress"), addr, false));
        dialog(Language.msg("wallet.receiveMinima"), body, 320);
    }

    // ---- helpers ----

    private void showError(String m) {
        mBody.removeAll();
        JLabel message = k.sub("");
        k.renderSub(message, () -> systemText(m));
        mBody.add(message);
        mBody.revalidate();
        mBody.repaint();
    }

    private void uiStatus(JLabel l, String s) {
        javax.swing.SwingUtilities.invokeLater(() -> k.renderSub(l, () -> systemText(s)));
    }

    private JLabel heroLabel(CharSequence s) {
        JLabel l = Language.bind(new JLabel(), s);
        Language.font(l, t.semibold(10.5f));
        l.setForeground(DKit.alpha(t.onAccent, 190));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static String tokenName(org.json.JSONObject tk) {
        Object token = tk.opt("token");
        if (token instanceof org.json.JSONObject) {
            String n = ((org.json.JSONObject) token).optString("name", "");
            if (!n.isEmpty()) return n;
        }
        String s = tk.optString("token", "");
        return s.isEmpty() ? Language.text("wallet.token") : s;
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.codePointCount(0, s.length()) > n ? s.substring(0, s.offsetByCodePoints(0, n - 1)) + "…" : s;
    }

    private JDialog dialog(CharSequence title, JComponent body, int width) {
        JDialog d = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(this), title.toString(),
                java.awt.Dialog.ModalityType.MODELESS);
        Language.title(d, title);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(t.card);
        wrap.setBorder(new EmptyBorder(16, 18, 18, 18));
        JScrollPane scroll = new JScrollPane(new DKit.ScrollableColumn(body),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);scroll.getViewport().setBackground(t.card);
        wrap.add(scroll, BorderLayout.CENTER);
        d.setContentPane(wrap);
        Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        d.setSize(Math.min(screen.width - 80, Math.max(width, 490)), 500);
        d.validate();
        d.setSize(d.getWidth(), Math.min(screen.height - 80, Math.max(300 * t.textPercent() / 100, body.getPreferredSize().height + 70)));
        d.setLocationRelativeTo(this);
        d.setVisible(true);
        return d;
    }

    @Override public void onWidth(int width) {
        if (mActions != null) DKit.layoutActions(mActions, Math.max(1, Math.min(720, width - 44)));
        if (mHeroAmount instanceof DKit.WrapText) {
            // Keep the complete balance on one line when readable; unusually long
            // values still wrap at the normal text size instead of being rounded.
            java.awt.Font large = t.extrabold(30f);
            int needed = mHeroAmount.getFontMetrics(large).stringWidth(((DKit.WrapText) mHeroAmount).getText());
            int available = Math.max(1, Math.min(720, width - 44) - 80); // card padding, scrollbar and glyph rounding margin
            float size = Math.max(t.font(12.5f).getSize2D(), Math.min(large.getSize2D(), large.getSize2D() * available / Math.max(1, needed)));
            Language.font(mHeroAmount, large.deriveFont(size));
        }
    }
    /** Stack labels above full values so long translations cannot overlap amounts. */
    private JComponent detailLine(CharSequence label, CharSequence value) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(4, 0, 4, 0));
        row.add(k.sub(label));
        JComponent display = value instanceof String ? exactValue(value.toString()) : k.sub(value);
        Language.font(display, t.semibold(12.5f), value.toString());
        display.setForeground(t.text);
        row.add(display);
        return row;
    }
    private JComponent detailLine(CharSequence label, java.util.function.Supplier<String> value) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(4, 0, 4, 0));
        row.add(k.sub(label));
        JLabel display = k.sub("");
        k.renderSub(display, value);
        Language.font(display, t.semibold(12.5f), display.getText());
        display.setForeground(t.text);
        row.add(display);
        return row;
    }
    private DKit.WrapText exactValue(String value) {
        DKit.WrapText display = new DKit.WrapText(value);
        Language.font(display, t.semibold(12.5f), value);
        display.setForeground(t.text);
        return display;
    }
    private static String count(long number) {
        return java.text.NumberFormat.getIntegerInstance(Language.locale()).format(number);
    }
    private static CharSequence tokenTitle(org.json.JSONObject tk) {
        Object token = tk.opt("token");
        if (token instanceof org.json.JSONObject && !((org.json.JSONObject) token).optString("name", "").isEmpty())
            return ((org.json.JSONObject) token).optString("name");
        String name = tk.optString("token", "");
        return name.isEmpty() ? Language.msg("wallet.token") : name;
    }
    /** Wallet statuses only; never translate amounts, token names, addresses or transaction IDs. */
    static String systemText(String value) {
        if (value == null) return "";
        if (value.startsWith("Publishing via ") && value.endsWith("…"))
            return Language.text("wallet.publishing", "backend", Language.system(value.substring(15, value.length() - 1)));
        String[] prefixes = {"Send failed: ", "Couldn't open wallet: "};
        String[] keys = {"wallet.sendFailed", "wallet.openFailed"};
        for (int i = 0; i < prefixes.length; i++) if (value.startsWith(prefixes[i]))
            return Language.text(keys[i], "error", Language.system(value.substring(prefixes[i].length())));
        return Language.system(value);
    }

    private JPanel rowX() {
        JPanel r = new JPanel();
        r.setOpaque(false);
        r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        return r;
    }
}
