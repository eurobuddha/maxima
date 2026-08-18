package com.eurobuddha.maxima.desktop.ui;

import java.awt.BorderLayout;
import java.awt.Component;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Wallet tab. The desktop shares the phone's identity seed — which IS a
 * spendable Minima wallet seed — but a desktop install does not (yet) bundle a
 * Minima node to query balances or build transactions, so this tab presents the
 * identity honestly and explains what a spendable wallet needs, rather than
 * showing a fake zero balance. The layout mirrors the phone's Wallet hero.
 */
public final class WalletPanel extends JPanel implements MaximaWindow.Tab {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;
    private final JPanel mBody = new JPanel();
    private boolean mBuilt;

    public WalletPanel(DesktopNode zNode, Theme zTheme) {
        node = zNode;
        t = zTheme;
        k = new DKit(zTheme);
        setLayout(new BorderLayout());
        setBackground(t.bg);
        mBody.setLayout(new BoxLayout(mBody, BoxLayout.Y_AXIS));
        mBody.setBackground(t.bg);
        mBody.setBorder(new EmptyBorder(22, 26, 22, 26));
        JScrollPane sp = new JScrollPane(mBody,
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
        if (mBuilt) {
            return;   // static content — no per-tick churn
        }
        mBuilt = true;
        mBody.removeAll();
        mBody.add(k.title("Wallet"));
        mBody.add(k.vgap(16));

        // Hero.
        DKit.RoundPanel hero = k.round(t.accent, 18);
        hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
        hero.setBorder(new EmptyBorder(20, 20, 20, 20));
        JLabel lbl = new JLabel("YOUR MINIMA IDENTITY");
        lbl.setFont(t.semibold(10.5f));
        lbl.setForeground(DKit.alpha(t.onAccent, 190));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel amt = new JLabel(node.identity().mxIdentity());
        amt.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
        amt.setForeground(t.onAccent);
        amt.setAlignmentX(Component.LEFT_ALIGNMENT);
        amt.setBorder(new EmptyBorder(8, 0, 0, 0));
        hero.add(lbl);
        hero.add(amt);
        mBody.add(hero);
        mBody.add(k.vgap(14));

        // maxima publickey.
        DKit.RoundPanel idCard = k.card();
        idCard.add(k.sectionLabel("Identity"));
        idCard.add(k.vgap(8));
        idCard.add(k.copyField("maxima publickey", node.identity().publicKeyHex(), false));
        mBody.add(idCard);
        mBody.add(k.vgap(14));

        // Honest note about spending.
        DKit.RoundPanel note = k.card();
        note.add(k.sectionLabel("Spending"));
        note.add(k.vgap(8));
        JLabel body = new JLabel("<html><div style='width:560px'>"
                + "This desktop's identity seed is also a spendable Minima wallet seed — the "
                + "same 24 words shown when it was first created. To check a balance or send "
                + "MINIMA from this machine you need a Minima node connected to it; the desktop "
                + "app does not bundle one yet. On your phone, the Maxima app's Wallet tab spends "
                + "from this identity directly."
                + "</div></html>");
        body.setFont(t.font(12.5f));
        body.setForeground(t.subtext);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        note.add(body);
        mBody.add(note);

        mBody.add(Box.createVerticalGlue());
        mBody.revalidate();
        mBody.repaint();
    }
}
