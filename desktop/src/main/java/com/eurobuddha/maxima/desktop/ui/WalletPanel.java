package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.desktop.wallet.DesktopNodeLink;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Wallet tab. The desktop shares the phone's identity but has no bundled Minima
 * node, so a real, working wallet comes from connecting to the user's <b>Minima Core
 * Desktop</b> over RPC (just as the phone talks to its bundled node). Unconfigured,
 * it shows a clear connect card; connected, it shows a Balance / History segmented
 * view with a hero balance, per-token cards, and Send / Receive — all driven by the
 * node's own wallet over RPC.
 */
public final class WalletPanel extends JPanel implements MaximaWindow.Tab {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;

    private final JPanel mBody = new JPanel();
    private int mSeg = 0;                 // 0 = Balance, 1 = History
    private volatile JSONObject mBalance; // last balance reply
    private volatile JSONArray mHistory;  // last history reply
    private long mLastFetch;
    private boolean mFetching;

    public WalletPanel(DesktopNode zNode, Theme zTheme) {
        node = zNode;
        t = zTheme;
        k = new DKit(zTheme);
        setLayout(new BorderLayout());
        setBackground(t.bg);
        mBody.setLayout(new BoxLayout(mBody, BoxLayout.Y_AXIS));
        mBody.setOpaque(false);
        mBody.setBorder(new EmptyBorder(20, 22, 22, 22));
        JScrollPane sp = new JScrollPane(holder(k.centered(mBody, 720)),
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
        DesktopNodeLink link = DesktopNodeLink.configured();
        if (link == null) {
            if (mBody.getComponentCount() == 0 || mBalance != null) {
                mBalance = null;
                buildDisconnected();
            }
            return;
        }
        // Poll balance/history every 6s while connected.
        long now = System.currentTimeMillis();
        if (!mFetching && now - mLastFetch > 6000) {
            mLastFetch = now;
            fetch(link);
        }
        if (mBody.getComponentCount() == 0) {
            buildConnected();
        }
    }

    // ---- disconnected: connect card ----

    private void buildDisconnected() {
        mBody.removeAll();
        DKit.RoundPanel hero = k.round(t.accent, 18);
        hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
        hero.setBorder(new EmptyBorder(20, 20, 20, 20));
        JLabel lbl = new JLabel("YOUR MINIMA IDENTITY");
        lbl.setFont(t.semibold(10.5f));
        lbl.setForeground(DKit.alpha(t.onAccent, 190));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        DKit.WrapText id = new DKit.WrapText(node.identity().mxIdentity());
        id.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        id.setForeground(t.onAccent);
        id.setBorder(new EmptyBorder(8, 0, 0, 0));
        hero.add(lbl);
        hero.add(id);
        mBody.add(hero);
        mBody.add(k.vgap(14));

        mBody.add(k.sectionLabel("Connect your wallet"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel card = k.card();
        card.add(k.sub("Point Maxima at your running Minima Core Desktop node to check your "
                + "balance and send MINIMA from here. On the node, enable RPC (e.g. start with "
                + "-rpcenable), then enter its RPC address below."));
        card.add(k.vgap(12));
        JTextField url = k.field("http://127.0.0.1:9005");
        url.setText(DesktopNodeLink.configuredUrl().isEmpty()
                ? DesktopNodeLink.DEFAULT_URL : DesktopNodeLink.configuredUrl());
        url.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        url.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(url);
        card.add(k.vgap(8));
        JPasswordField pw = new JPasswordField();
        pw.setOpaque(true);
        pw.setBackground(t.input);
        pw.setForeground(t.text);
        pw.setCaretColor(t.text);
        pw.setBorder(new EmptyBorder(11, 14, 11, 14));
        pw.setFont(t.font(13f));
        pw.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        pw.setAlignmentX(Component.LEFT_ALIGNMENT);
        pw.setText(DesktopNodeLink.configuredPassword());
        card.add(pw);
        card.add(k.sub("RPC password — leave blank if your node has none."));
        card.add(k.vgap(12));
        final JLabel status = k.sub(" ");
        JPanel r = rowX();
        DKit.HoverButton save = k.primaryButton("Save & test");
        save.onClick(() -> {
            String u = url.getText().trim();
            String p = new String(pw.getPassword());
            status.setText("Testing " + u + "…");
            new Thread(() -> {
                DesktopNodeLink.save(u, p);
                DesktopNodeLink link = DesktopNodeLink.configured();
                boolean ok = link != null && link.ping();
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (ok) {
                        status.setText("Connected.");
                        mBody.removeAll();
                        mLastFetch = 0;
                        buildConnected();
                        refresh();
                    } else {
                        status.setText("Couldn't reach a Minima node at that address.");
                    }
                });
            }, "rpc-test").start();
        });
        r.add(save);
        r.add(Box.createHorizontalGlue());
        card.add(r);
        card.add(k.vgap(6));
        card.add(status);
        mBody.add(card);
        mBody.add(Box.createVerticalGlue());
        mBody.revalidate();
        mBody.repaint();
    }

    // ---- connected: balance / history ----

    private void buildConnected() {
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

    private JPanel mPaneHolder;

    private void rebuildPanes() {
        if (mPaneHolder == null) return;
        mPaneHolder.removeAll();
        if (mSeg == 0) buildBalance(); else buildHistory();
        mPaneHolder.revalidate();
        mPaneHolder.repaint();
    }

    private void buildBalance() {
        // Hero: native MINIMA.
        String conf = "…", sendable = "…";
        JSONArray toks = balanceArray();
        if (toks != null) {
            for (Object o : toks) {
                JSONObject tk = (JSONObject) o;
                if ("0x00".equals(str(tk, "tokenid"))) {
                    conf = str(tk, "confirmed");
                    sendable = str(tk, "sendable");
                }
            }
        }
        DKit.RoundPanel hero = k.round(t.accent, 18);
        hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
        hero.setBorder(new EmptyBorder(20, 20, 20, 20));
        JLabel l = new JLabel("MINIMA BALANCE");
        l.setFont(t.semibold(10.5f));
        l.setForeground(DKit.alpha(t.onAccent, 190));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel amt = new JLabel(conf);
        amt.setFont(t.extrabold(30f));
        amt.setForeground(t.onAccent);
        amt.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel sub = new JLabel(sendable + " sendable");
        sub.setFont(t.font(12.5f));
        sub.setForeground(DKit.alpha(t.onAccent, 190));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        hero.add(l);
        hero.add(k.vgap(4));
        hero.add(amt);
        hero.add(k.vgap(2));
        hero.add(sub);
        mPaneHolder.add(hero);
        mPaneHolder.add(k.vgap(12));

        JPanel actions = rowX();
        DKit.HoverButton send = k.primaryButton("Send");
        send.onClick(this::showSend);
        DKit.HoverButton receive = k.ghostButton("Receive");
        receive.onClick(this::showReceive);
        actions.add(send);
        actions.add(Box.createRigidArea(new Dimension(10, 0)));
        actions.add(receive);
        actions.add(Box.createHorizontalGlue());
        mPaneHolder.add(actions);
        mPaneHolder.add(k.vgap(14));

        // Token cards.
        mPaneHolder.add(k.sectionLabel("Tokens"));
        mPaneHolder.add(k.vgap(8));
        if (toks == null) {
            DKit.RoundPanel c = k.card();
            c.add(k.sub("Loading balance from your node…"));
            mPaneHolder.add(c);
        } else {
            for (Object o : toks) {
                mPaneHolder.add(tokenCard((JSONObject) o));
                mPaneHolder.add(k.vgap(8));
            }
        }
    }

    private JComponent tokenCard(JSONObject tk) {
        String name = tokenName(tk);
        String tokenid = str(tk, "tokenid");
        DKit.RoundPanel card = k.card();
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.add(k.avatar(tokenid, name, 40), BorderLayout.WEST);
        JPanel mid = new JPanel();
        mid.setOpaque(false);
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        JLabel nm = new JLabel(name);
        nm.setFont(t.semibold(14f));
        nm.setForeground(t.text);
        nm.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel sub = new JLabel(str(tk, "sendable") + " sendable");
        sub.setFont(t.font(11.5f));
        sub.setForeground(t.subtext);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(nm);
        mid.add(sub);
        row.add(mid, BorderLayout.CENTER);
        JLabel bal = new JLabel(str(tk, "confirmed"));
        bal.setFont(t.bold(15f));
        bal.setForeground(t.text);
        row.add(bal, BorderLayout.EAST);
        card.add(row);
        return card;
    }

    private void buildHistory() {
        mPaneHolder.add(k.sectionLabel("Recent transactions"));
        mPaneHolder.add(k.vgap(8));
        JSONArray hist = mHistory;
        if (hist == null) {
            DKit.RoundPanel c = k.card();
            c.add(k.sub("Loading history from your node…"));
            mPaneHolder.add(c);
            return;
        }
        if (hist.isEmpty()) {
            DKit.RoundPanel c = k.card();
            c.add(k.sub("No transactions yet."));
            mPaneHolder.add(c);
            return;
        }
        DKit.RoundPanel card = k.card();
        card.setBorder(new EmptyBorder(4, 8, 4, 8));
        int shown = 0;
        for (Object o : hist) {
            if (shown++ > 60) break;
            if (shown > 1) card.add(k.divider());
            card.add(historyRow((JSONObject) o));
        }
        mPaneHolder.add(card);
    }

    private JComponent historyRow(JSONObject h) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(9, 6, 9, 6));
        String detail = str(h, "detail");
        JLabel l = new JLabel(detail.isEmpty() ? "transaction" : detail);
        l.setFont(t.font(12.5f));
        l.setForeground(t.text);
        row.add(l, BorderLayout.CENTER);
        return row;
    }

    // ---- send / receive ----

    private void showSend() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.add(k.sub("Send MINIMA from your connected node."));
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
        JDialog d = dialog("Send MINIMA", body, 420);
        sign.onClick(() -> {
            String addr = to.getText().trim();
            String a = amt.getText().trim();
            if (addr.isEmpty() || a.isEmpty()) { status.setText("Enter an address and amount."); return; }
            status.setText("Building + signing…");
            sign.setButtonEnabled(false);
            new Thread(() -> {
                String msg;
                try {
                    DesktopNodeLink link = DesktopNodeLink.configured();
                    JSONObject res = link.cmd("send address:" + addr + " amount:" + a);
                    boolean ok = Boolean.TRUE.equals(res.get("status"));
                    msg = ok ? "Sent." : "Failed: " + res.getOrDefault("error", res.toString());
                } catch (Exception e) {
                    msg = "Failed: " + e.getMessage();
                }
                final String fmsg = msg;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    status.setText(fmsg);
                    sign.setButtonEnabled(true);
                    mLastFetch = 0;
                    if (fmsg.equals("Sent.")) {
                        javax.swing.Timer tm = new javax.swing.Timer(1200, ev -> d.dispose());
                        tm.setRepeats(false);
                        tm.start();
                    }
                });
            }, "wallet-send").start();
        });
    }

    private void showReceive() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        JLabel qr = new JLabel("Fetching an address…", javax.swing.SwingConstants.CENTER);
        qr.setForeground(t.subtext);
        qr.setFont(t.font(12f));
        qr.setPreferredSize(new Dimension(230, 230));
        qr.setMaximumSize(new Dimension(230, 230));
        JPanel qw = new JPanel();
        qw.setOpaque(false);
        qw.setLayout(new BoxLayout(qw, BoxLayout.X_AXIS));
        qw.add(Box.createHorizontalGlue());
        qw.add(qr);
        qw.add(Box.createHorizontalGlue());
        body.add(qw);
        body.add(k.vgap(12));
        JPanel copyHolder = new JPanel(new BorderLayout());
        copyHolder.setOpaque(false);
        body.add(copyHolder);
        JDialog d = dialog("Receive MINIMA", body, 320);
        new Thread(() -> {
            String addr = "";
            try {
                DesktopNodeLink link = DesktopNodeLink.configured();
                JSONObject res = link.cmd("getaddress");
                Object resp = res.get("response");
                if (resp instanceof JSONObject) addr = str((JSONObject) resp, "miniaddress");
            } catch (Exception ignored) { }
            final String faddr = addr;
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (faddr.isEmpty()) { qr.setText("Couldn't fetch an address."); return; }
                java.awt.image.BufferedImage img = DesktopQr.encode(faddr, 230,
                        0xFF000000 | (t.text.getRGB() & 0xFFFFFF), 0xFFFFFFFF);
                if (img != null) { qr.setText(null); qr.setIcon(new javax.swing.ImageIcon(img)); }
                copyHolder.add(k.copyField("your MINIMA address", faddr, false), BorderLayout.CENTER);
                copyHolder.revalidate();
                d.pack();
                d.setLocationRelativeTo(WalletPanel.this);
            });
        }, "wallet-receive").start();
    }

    // ---- fetch ----

    private void fetch(DesktopNodeLink link) {
        mFetching = true;
        new Thread(() -> {
            JSONObject bal = null;
            JSONArray hist = null;
            try { bal = link.cmd("balance"); } catch (Exception ignored) { }
            try {
                JSONObject h = link.cmd("history");
                Object resp = h == null ? null : h.get("response");
                if (resp instanceof JSONObject) {
                    Object txns = ((JSONObject) resp).get("txpows");
                    if (txns instanceof JSONArray) hist = flattenHistory((JSONArray) txns);
                } else if (resp instanceof JSONArray) {
                    hist = (JSONArray) resp;
                }
            } catch (Exception ignored) { }
            mBalance = bal;
            if (hist != null) mHistory = hist;
            mFetching = false;
            javax.swing.SwingUtilities.invokeLater(this::rebuildPanes);
        }, "wallet-fetch").start();
    }

    private JSONArray flattenHistory(JSONArray txns) {
        JSONArray out = new JSONArray();
        for (Object o : txns) {
            JSONObject row = new JSONObject();
            row.put("detail", "transaction");
            out.add(row);
        }
        return out;
    }

    private JSONArray balanceArray() {
        JSONObject b = mBalance;
        if (b == null) return null;
        Object resp = b.get("response");
        return resp instanceof JSONArray ? (JSONArray) resp : null;
    }

    // ---- helpers ----

    private static String str(JSONObject o, String key) {
        Object v = o == null ? null : o.get(key);
        return v == null ? "" : v.toString();
    }

    private static String tokenName(JSONObject tk) {
        Object token = tk.get("token");
        if (token instanceof JSONObject) {
            Object n = ((JSONObject) token).get("name");
            if (n != null) return n.toString();
        }
        if (token != null) return token.toString();
        return "0x00".equals(str(tk, "tokenid")) ? "Minima" : str(tk, "tokenid");
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

    private static JComponent holder(JComponent c) {
        JPanel h = new JPanel(new BorderLayout());
        h.setOpaque(false);
        h.add(c, BorderLayout.NORTH);
        return h;
    }
}
