package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.desktop.wallet.DesktopNodeLink;
import com.eurobuddha.maxima.desktop.wallet.DesktopWallet;
import com.eurobuddha.maxima.desktop.wallet.DesktopWalletLedger;
import com.eurobuddha.maxima.desktop.wallet.DesktopWalletPublisher;
import com.eurobuddha.wallet.CoinSelector;
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
public final class WalletPanel extends JPanel implements MaximaWindow.Tab {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;

    private final JPanel mBody = new JPanel();
    private int mSeg = 0;                     // 0 = Balance, 1 = History
    private JPanel mPaneHolder;

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

    public String label() { return "Wallet"; }
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
        java.io.File wdir = new java.io.File(node.dataDir().toFile(), "wallet");
        mLedger = new DesktopWalletLedger(wdir);
        mPub = new DesktopWalletPublisher(DesktopNodeLink.configured());
        new Thread(() -> {
            try {
                DesktopWallet w = DesktopWallet.open(node.seedPhrase(), wdir);
                w.ensureAddress();   // heavy WOTS walk
                mWallet = w;
                // Register our script so a coin funded before tracking is spendable.
                mPub.trackScript(w.script(), new DesktopWalletPublisher.Cb() {
                    public void onResult(org.json.JSONObject r) { }
                    public void onError(String m) { }
                });
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
        DKit.Segmented seg = k.segmented(new String[]{"Balance", "History"}, mSeg, i -> {
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
        if (mSeg == 0) buildBalance(); else buildHistory();
        mPaneHolder.revalidate();
        mPaneHolder.repaint();
    }

    private void buildBalance() {
        // Hero.
        DKit.RoundPanel hero = k.round(t.accent, 18);
        hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
        hero.setBorder(new EmptyBorder(20, 20, 20, 20));
        hero.add(heroLabel("MINIMA BALANCE"));
        JLabel amt = new JLabel(mWallet == null ? "opening wallet…" : mConf);
        amt.setFont(t.extrabold(30f));
        amt.setForeground(t.onAccent);
        amt.setAlignmentX(Component.LEFT_ALIGNMENT);
        hero.add(k.vgap(4));
        hero.add(amt);
        JLabel sub = new JLabel(mWallet == null ? "" : mSpend + " sendable");
        sub.setFont(t.font(12.5f));
        sub.setForeground(DKit.alpha(t.onAccent, 190));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        hero.add(k.vgap(2));
        hero.add(sub);
        mPaneHolder.add(hero);
        mPaneHolder.add(k.vgap(12));

        // Actions.
        JPanel actions = rowX();
        DKit.HoverButton send = k.primaryButton("Send");
        send.onClick(this::showSend);
        DKit.HoverButton receive = k.ghostButton("Receive");
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
        bal.add(k.kvLine("Confirmed", mWallet == null ? "…" : mConf));
        bal.add(k.divider());
        bal.add(k.kvLine("Unconfirmed", mWallet == null ? "…" : mUnconf));
        bal.add(k.divider());
        bal.add(k.kvLine("Sendable", mWallet == null ? "…" : mSpend));
        mPaneHolder.add(bal);
        mPaneHolder.add(k.vgap(14));

        // Sends remaining (one-time-signature budget) — the wallet's life.
        mPaneHolder.add(k.sectionLabel("Sends remaining"));
        mPaneHolder.add(k.vgap(8));
        DKit.RoundPanel uses = k.card();
        int used = 0;
        try { used = mWallet == null ? 0 : mWallet.uses(new java.io.File(node.dataDir().toFile(), "wallet")); }
        catch (Exception ignored) { }
        int left = DesktopWallet.MAX_USES - used;
        JLabel big = new JLabel(mWallet == null ? "…" : String.format("%,d", left));
        big.setFont(t.extrabold(22f));
        big.setForeground(t.text);
        big.setAlignmentX(Component.LEFT_ALIGNMENT);
        uses.add(big);
        uses.add(k.vgap(2));
        uses.add(k.sub("Each send spends one of this key's one-time signatures. "
                + used + " used of " + String.format("%,d", DesktopWallet.MAX_USES) + "."));
        mPaneHolder.add(uses);
        mPaneHolder.add(k.vgap(14));

        // Token cards (non-Minima tokens held).
        org.json.JSONArray arr = mBalanceArr;
        if (arr != null && arr.length() > 1) {
            mPaneHolder.add(k.sectionLabel("Tokens"));
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
        JLabel nm = new JLabel(name);
        nm.setFont(t.semibold(14f));
        nm.setForeground(t.text);
        row.add(nm, BorderLayout.CENTER);
        JLabel bal = new JLabel(tk.optString("confirmed", "0"));
        bal.setFont(t.bold(14f));
        bal.setForeground(t.text);
        row.add(bal, BorderLayout.EAST);
        card.add(row);
        card.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        card.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) { showTokenDetail(tk); }
        });
        return card;
    }

    private void showTokenDetail(org.json.JSONObject tk) {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.add(k.kvLine("Sendable", tk.optString("sendable", tk.optString("confirmed", "0"))));
        body.add(k.kvLine("Confirmed", tk.optString("confirmed", "0")));
        body.add(k.kvLine("Pending", tk.optString("unconfirmed", "0")));
        if (tk.has("coins")) body.add(k.kvLine("Coins", tk.optString("coins", "0")));
        if (tk.has("total")) body.add(k.kvLine("Total supply", tk.optString("total", "")));
        body.add(k.vgap(10));
        body.add(k.copyField("Token ID", tk.optString("tokenid", "0x00"), false));
        dialog(tokenName(tk), body, 440);
    }

    private int mHistFilter = 0;   // 0 all · 1 sent · 2 received

    private void buildHistory() {
        mPaneHolder.add(k.sectionLabel("Transactions"));
        mPaneHolder.add(k.vgap(8));
        JPanel chips = rowX();
        chips.add(filterChip("All", 0));
        chips.add(Box.createRigidArea(new Dimension(8, 0)));
        chips.add(filterChip("Sent", 1));
        chips.add(Box.createRigidArea(new Dimension(8, 0)));
        chips.add(filterChip("Received", 2));
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
            c.add(k.sub(mHistFilter == 0 ? "No transactions yet. Sends and receipts appear here."
                    : "Nothing here yet."));
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

    private JComponent filterChip(String label, int idx) {
        boolean on = mHistFilter == idx;
        DKit.RoundPanel chip = k.round(on ? t.accent : t.input, 999);
        chip.setLayout(new BoxLayout(chip, BoxLayout.X_AXIS));
        chip.setBorder(new EmptyBorder(6, 14, 6, 14));
        JLabel l = new JLabel(label);
        l.setFont(t.semibold(11.5f));
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
        JLabel who = new JLabel((r.sent ? "Sent to " : "Received from ") + clip(r.who, 22));
        who.setFont(t.semibold(13f));
        who.setForeground(t.text);
        who.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel when = new JLabel(new java.text.SimpleDateFormat("dd MMM HH:mm")
                .format(new java.util.Date(r.time)));
        when.setFont(t.font(11f));
        when.setForeground(t.subtext);
        when.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(who);
        mid.add(when);
        row.add(mid, BorderLayout.CENTER);
        JLabel amt = new JLabel((r.sent ? "-" : "+") + r.amount + " " + r.token);
        amt.setFont(t.bold(13.5f));
        amt.setForeground(r.sent ? t.text : t.success);
        row.add(amt, BorderLayout.EAST);
        // RULE 1: the row truncates the address; the full to/from address + the
        // full transaction id are one click away in the detail sheet.
        row.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) { showHistoryDetail(r); }
        });
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
        body.add(k.kvLine("Direction", r.sent ? "Sent" : "Received"));
        body.add(k.kvLine("Amount", (r.sent ? "-" : "+") + r.amount + " " + r.token));
        body.add(k.kvLine("When", new java.text.SimpleDateFormat("d MMM yyyy · HH:mm")
                .format(new java.util.Date(r.time))));
        body.add(k.vgap(10));
        body.add(k.copyField(r.sent ? "To" : "From",
                r.who == null || r.who.isEmpty() ? "—" : r.who, false));
        if (r.tokenid != null && !r.tokenid.isEmpty()) {
            body.add(k.vgap(8));
            body.add(k.copyField("Token ID", r.tokenid, false));
        }
        if (r.txid != null && !r.txid.isEmpty()) {
            body.add(k.vgap(8));
            body.add(k.copyField("Transaction ID", r.txid, false));
        }
        dialog("Transaction", body, 460);
    }

    // ---- send / receive ----

    private void showSend() {
        final DesktopWallet w = mWallet;
        if (w == null) return;
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.add(k.sub("Send MINIMA. Built and signed on THIS device; only the signed "
                + "transaction is relayed (via " + mPub.backendName() + ")."));
        body.add(k.vgap(10));
        JTextField to = k.field("Mx… address");
        to.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        to.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(to);
        body.add(k.vgap(8));
        JTextField amt = k.field("amount");
        amt.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        amt.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(amt);
        body.add(k.vgap(12));
        final JLabel status = k.sub(" ");
        JPanel r = rowX();
        DKit.HoverButton sign = k.primaryButton("Sign & send");
        r.add(sign);
        r.add(Box.createHorizontalGlue());
        body.add(r);
        body.add(k.vgap(6));
        body.add(status);
        JDialog d = dialog("Send MINIMA", body, 440);
        sign.onClick(() -> {
            String addr = to.getText().trim();
            String a = amt.getText().trim();
            if (addr.isEmpty() || a.isEmpty()) { status.setText("Enter an address and amount."); return; }
            final MiniNumber amount;
            try { amount = new MiniNumber(a); } catch (Exception e) { status.setText("Bad amount."); return; }
            if (!amount.isMore(MiniNumber.ZERO)) { status.setText("Amount must be greater than 0."); return; }
            sign.setButtonEnabled(false);
            status.setText("Selecting coins…");
            new Thread(() -> doSend(w, addr, amount, status, sign, d), "wallet-send").start();
        });
    }

    private void doSend(DesktopWallet w, String to, MiniNumber amount,
                        JLabel status, DKit.HoverButton sign, JDialog d) {
        signAndPublish(w, to, amount, new PayResult() {
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
                uiStatus(status, m.toLowerCase().contains("funds") ? m : "Send failed: " + m);
                javax.swing.SwingUtilities.invokeLater(() -> sign.setButtonEnabled(true));
            }
        });
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
    private void signAndPublish(DesktopWallet w, String to, MiniNumber amount, PayResult cb) {
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
            List<org.minima.utils.json.JSONObject> sel = CoinSelector.selectToCover(coins, "0x00", amount);
            List<TxnFactory.InputCoin> inputs = new ArrayList<>();
            for (org.minima.utils.json.JSONObject cn : sel) {
                inputs.add(TxnFactory.fromCoinJson(cn, DesktopWallet.KEY_INDEX));
            }

            // 3. build + SIGN locally (this reserves a one-time key use).
            cb.onStatus("Signing on this device…");
            TxnFactory factory = new TxnFactory(w.core());
            final TxnFactory.BuiltTxn built = factory.buildSend(inputs, to, amount,
                    TxnFactory.TOKEN_MINIMA, MiniNumber.ZERO, "mxw" + System.currentTimeMillis());

            // 4. publish the SIGNED txn (relay only) via gateway/node.
            cb.onStatus("Publishing via " + mPub.backendName() + "…");
            mPub.publish(built.getTxnImportCommand(), built.getID(), built.getTxnPostCommand(),
                    new DesktopWalletPublisher.Cb() {
                public void onResult(org.json.JSONObject r) {
                    mLedger.add(true, amount.toString(), "MINIMA", to, built.getID(), "0x00");
                    mLastFetch = 0;
                    cb.onTxid(built.getID());
                }
                public void onError(String m) { cb.onError(m); }
            });
        } catch (CoinSelector.InsufficientFundsException ife) {
            cb.onError("Not enough confirmed funds.");
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
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
        try {
            DesktopWallet w = ensureWalletOpenBlocking();
            signAndPublish(w, toAddr, amount, cb);
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
        body.add(k.copyField("your MINIMA address", addr, false));
        dialog("Receive MINIMA", body, 320);
    }

    // ---- helpers ----

    private void showError(String m) {
        mBody.removeAll();
        mBody.add(k.sub(m));
        mBody.revalidate();
        mBody.repaint();
    }

    private void uiStatus(JLabel l, String s) {
        javax.swing.SwingUtilities.invokeLater(() -> l.setText(s));
    }

    private JLabel heroLabel(String s) {
        JLabel l = new JLabel(s);
        l.setFont(t.semibold(10.5f));
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
        return s.isEmpty() ? "Token" : s;
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    private JDialog dialog(String title, JComponent body, int width) {
        JDialog d = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(this), title,
                java.awt.Dialog.ModalityType.MODELESS);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(t.card);
        wrap.setBorder(new EmptyBorder(16, 18, 18, 18));
        wrap.add(body, BorderLayout.CENTER);
        d.setContentPane(wrap);
        d.pack();
        d.setSize(Math.max(width, d.getWidth()), d.getHeight());
        d.setLocationRelativeTo(this);
        d.setVisible(true);
        return d;
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
