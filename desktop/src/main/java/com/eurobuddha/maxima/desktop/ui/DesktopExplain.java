package com.eurobuddha.maxima.desktop.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Desktop counterpart of the phone's Explain: what every number and every
 * setting actually means. Maxima exposes real transport internals — hosts,
 * routing keys, a directory, a mailbox, contribution counters — and a number
 * with no explanation is worse than no number. Same content as the phone, so a
 * "?" beside a figure opens the same words on either platform.
 */
public final class DesktopExplain {

    private static final Map<String, String[]> ENTRIES = new LinkedHashMap<>();

    private static void put(String zKey, String zTitle, String zBody) {
        ENTRIES.put(zKey, new String[]{zTitle, zBody});
    }

    static {
        put("status", "help.statusTitle", "help.statusBody");
        put("address", "help.addressTitle", "help.addressBody");
        put("hosts", "help.hostsTitle", "help.hostsBody");
        put("mls", "help.mlsTitle", "help.mlsBody");
        put("contacts", "help.contactsTitle", "help.contactsBody");
        put("mailbox", "help.mailboxTitle", "help.mailboxBody");
        put("outbox", "help.outboxTitle", "help.outboxBody");
        put("services", "help.servicesTitle", "help.servicesBody");
        put("contribution", "help.contributionTitle", "help.contributionBody");
        put("witness", "help.witnessTitle", "help.witnessBody");
        put("storage", "help.storageTitle", "help.storageBody");
        put("direct", "help.directTitle", "help.directBody");
        put("counters", "help.countersTitle", "help.countersBody");
        put("ticks", "help.ticksTitle", "help.ticksBody");
        put("readreceipts", "help.readreceiptsTitle", "help.readreceiptsBody");
        put("groups", "help.groupsTitle", "help.groupsBody");
        put("name", "help.nameTitle", "help.nameBody");
        put("appearance", "help.appearanceTitle", "help.appearanceBody");
        put("seed", "help.seedTitle", "help.seedBody");
        put("relays", "help.relaysTitle", "help.relaysBody");
        put("log", "help.logTitle", "help.logBody");
        put("keyuses", "help.keyusesTitle", "help.keyusesBody");
        put("nodekind", "help.nodekindTitle", "help.nodekindBody");
    }

    private DesktopExplain() { }

    public static String title(String zKey) {
        String[] e = ENTRIES.get(zKey);
        return e == null ? zKey : Language.text(e[0]);
    }

    public static String body(String zKey) {
        String[] e = ENTRIES.get(zKey);
        return e == null ? Language.text("help.missing", "topic", zKey) : Language.text(e[1]);
    }

    /** Pop the explanation for {@code zKey} as a modal dialog over {@code zOver}. */
    public static void show(Component zOver, String zKey, Theme theme) {
        dialog(zOver, zKey, theme).setVisible(true);
    }

    static JDialog dialog(Component zOver, String zKey, Theme theme) {
        JTextArea body = new JTextArea(body(zKey));
        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setOpaque(false);
        body.setForeground(theme.text);
        body.setBorder(new EmptyBorder(0, 0, 0, 0));
        Language.font(body, theme.font(13f), body.getText());
        ((javax.swing.text.DefaultCaret) body.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        JScrollPane sp = new JScrollPane(body,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getViewport().setBackground(theme.card);
        sp.setPreferredSize(new Dimension(420, 260));

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(theme.card);
        wrap.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        wrap.add(sp, BorderLayout.CENTER);

        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(zOver),
                title(zKey), java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        d.setContentPane(wrap);
        d.setSize(460, 340);
        d.setLocationRelativeTo(zOver);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        Runnable refresh = () -> {
            int start = body.getSelectionStart(), end = body.getSelectionEnd();
            int scroll = sp.getVerticalScrollBar().getValue();
            d.setTitle(title(zKey));
            body.setText(body(zKey));
            Language.refreshFont(body);
            body.select(Math.min(start, body.getDocument().getLength()), Math.min(end, body.getDocument().getLength()));
            SwingUtilities.invokeLater(() -> { if (d.isDisplayable()) sp.getVerticalScrollBar().setValue(scroll); });
        };
        Language.addListener(refresh);
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { Language.removeListener(refresh); }
        });
        return d;
    }
}
