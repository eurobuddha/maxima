package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.desktop.DesktopMain;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.prefs.Preferences;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Settings tab — parity with the phone's desktop-meaningful settings: your name
 * + maxima publickey, privacy (read receipts), appearance (System/Light/Dark),
 * node kind (run as core), keys & security (show seed with 60s auto-clear copy,
 * restore a seed phrase), and about. Phone-only items (app-lock, battery, Android
 * app IPC) are intentionally omitted.
 */
public final class SettingsPanel extends JPanel implements MaximaWindow.Tab, MaximaWindow.Responsive {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;
    private final MaximaWindow window;
    private final JPanel mBody = new JPanel();
    private JLabel mKeyValue;
    private boolean mBuilt;
    private final java.util.List<JPanel> mActionRows = new java.util.ArrayList<>();

    private static final Preferences PREFS =
            Preferences.userRoot().node("com/eurobuddha/maxima/desktop");

    public SettingsPanel(DesktopNode zNode, Theme zTheme, MaximaWindow zWindow) {
        node = zNode;
        t = zTheme;
        k = new DKit(zTheme);
        window = zWindow;
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

    public String label() { return Language.text("nav.settings"); }
    public JComponent view() { return this; }

    public void refresh() {
        if (!mBuilt) { mBuilt = true; build(); }
        if (mKeyValue != null) {
            try { mKeyValue.setText(node.port().publicKeyHex()); } catch (Exception ignored) { }
        }
    }

    private void build() {
        mBody.removeAll();
        mActionRows.clear();

        mBody.add(k.sectionLabel(Language.msg("language.title")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel languageCard = k.card();
        languageCard.add(k.sub(Language.msg("language.description")));
        languageCard.add(k.vgap(8));
        languageCard.add(k.ghostButton(Language.msg("language.change"))
                .onClick(() -> Language.chooser(this)));
        mBody.add(languageCard);
        mBody.add(k.vgap(14));

        // ---- your name + maxima publickey ----
        mBody.add(k.sectionLabel(Language.msg("settings.yourName")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel nameCard = k.card();
        JPanel nameRow = rowX();
        JTextField name = k.field(Language.msg("desktop.displayName"));
        name.setText(currentName());
        name.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        nameRow.add(name);
        nameRow.add(Box.createRigidArea(new Dimension(9, 0)));
        DKit.HoverButton set = k.primaryButton(Language.msg("nativeSettings.set"));
        set.onClick(() -> {
            String nm = name.getText().trim();
            if (nm.isEmpty()) return;
            PREFS.put("name", nm);
            if (node.node() != null) node.node().setName(nm);   // classic name is set at boot
            window.frame().setTitle("Parlons! — " + nm);
            new Thread(() -> { try { if (node.node() != null) node.node().refreshContacts(); }
                    catch (Exception ignored) { } }, "refresh-contacts").start();
        });
        nameRow.add(set);
        nameCard.add(nameRow);
        nameCard.add(k.vgap(10));
        JComponent keyField = k.copyField(Language.msg("desktop.publicKey"), node.port().publicKeyHex(), false);
        nameCard.add(keyField);
        mKeyValue = findValueLabel(keyField);
        mBody.add(nameCard);
        mBody.add(k.vgap(14));

        mBody.add(k.sectionLabel(Language.msg("settings.connectedApps")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel apps = k.card();
        apps.add(k.sub(Language.msg("desktop.docsHelp")));
        apps.add(k.sub(Language.msg("desktop.docsAccount", "name", node.port().name())));
        apps.add(k.sub(Language.msg("desktop.docsCount", "count", node.docsLink().approvals())));
        JPanel actions = rowX();
        mActionRows.add(actions);
        DKit.HoverButton connectDocs = k.primaryButton(Language.msg("settings.docsConnect"));
        connectDocs.onClick(() -> {
            try {
                JPanel content = new JPanel(); content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
                content.add(k.sub(Language.msg("desktop.docsPaste")));
                content.add(k.copyField(Language.msg("desktop.connectionLink"), node.docsLink().approve(), false));
                dialog(Language.text("desktop.docsTitle", "name", node.port().name()), content, 640);
            } catch (Exception e) { javax.swing.JOptionPane.showMessageDialog(this, Language.text("desktop.docsFailed")); }
        });
        DKit.HoverButton revokeDocs = k.ghostButton(Language.msg("settings.docsRevoke"));
        revokeDocs.onClick(() -> {
            int answer = javax.swing.JOptionPane.showConfirmDialog(this,
                    Language.text("desktop.docsRevokeQuestion"),
                    Language.text("settings.docsRevoke"), javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);
            if (answer != javax.swing.JOptionPane.OK_OPTION) return;
            try { node.docsLink().revoke(); rebuild(); }
            catch (Exception e) { javax.swing.JOptionPane.showMessageDialog(this, Language.text("desktop.docsRevokeFailed")); }
        });
        actions.add(connectDocs); actions.add(revokeDocs); apps.add(actions); mBody.add(apps); mBody.add(k.vgap(14));

        // ---- privacy ----
        mBody.add(k.sectionLabel(Language.msg("settings.privacy")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel priv = k.card();
        boolean rr = PREFS.getBoolean("readReceipts", true);
        node.setReadReceipts(rr);
        priv.add(switchRow(Language.msg("settings.readReceipts"),
                rr ? Language.msg("nativeSettings.receiptsOn")
                        : Language.msg("nativeSettings.receiptsOff"), rr, on -> {
            PREFS.putBoolean("readReceipts", on);
            node.setReadReceipts(on);
            rebuild();
        }));
        priv.add(k.divider());
        boolean snd = Chirp.enabled();
        priv.add(switchRow(Language.msg("nativeSettings.sound"),
                snd ? Language.msg("nativeSettings.soundOn") : Language.msg("desktop.silent"), snd, on -> {
            Chirp.setEnabled(on);
            if (on) Chirp.play();   // audition it, like flipping it on the phone
            rebuild();
        }));
        priv.add(k.divider());
        priv.add(k.kvLine(Language.msg("nativeSettings.delivery"), Language.msg("desktop.alwaysOn")));
        mBody.add(priv);
        mBody.add(k.vgap(14));

        // ---- appearance ----
        mBody.add(k.sectionLabel(Language.msg("settings.appearance")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel appear = k.card();
        String cur = PREFS.get("appearance", "system");
        int sel = cur.equals("light") ? 1 : cur.equals("dark") ? 2 : 0;
        DKit.Segmented seg = k.segmented(new CharSequence[]{Language.msg("theme.systemLabel"), Language.msg("theme.lightLabel"), Language.msg("theme.darkLabel")}, sel, i -> {
            String val = i == 1 ? "light" : i == 2 ? "dark" : "system";
            PREFS.put("appearance", val);
            Theme.Mode m = val.equals("dark") ? Theme.Mode.DARK
                    : val.equals("light") ? Theme.Mode.LIGHT : Theme.detectMode();
            window.switchTheme(m);
        });
        seg.setAlignmentX(Component.LEFT_ALIGNMENT);
        appear.add(seg);
        mBody.add(appear);
        mBody.add(k.vgap(14));

        // ---- node ----
        mBody.add(k.sectionLabel(Language.msg("nav.node")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel nodeCard = k.card();
        boolean core = PREFS.getBoolean("coreNode", true);
        nodeCard.add(switchRow(Language.msg("nativeSettings.core"),
                core ? Language.msg("nativeSettings.coreOn")
                        : Language.msg("desktop.ordinaryNode"), core, on -> {
            PREFS.putBoolean("coreNode", on);
            try { if (node.node() != null) node.node().setNodeKind(on ? "core" : ""); }
            catch (Exception ignored) { }
            rebuild();
        }));
        nodeCard.add(k.divider());
        // Classic engine — the SAME flip the phone's Settings has: leaving classic
        // exports its contact book to the built-in store FIRST (or contacts added
        // on the jar vanish), then flips the pref and restarts the engine in place.
        boolean jarOn = DesktopNode.jarMode();
        nodeCard.add(switchRow(Language.msg("nativeSettings.classic"),
                jarOn ? Language.msg("nativeSettings.classicOn")
                        : Language.msg("desktop.builtinHelp"),
                jarOn, on -> {
            java.util.prefs.Preferences pf =
                    java.util.prefs.Preferences.userRoot().node(DesktopNode.PREFS);
            if (!on && node.isJar() && node.jarEngine() != null) {
                try {
                    int x = com.eurobuddha.maxima.desktop.jar.DesktopJarMigration
                            .exportContacts(node.dataDir().toFile(), node.jarEngine());
                    DesktopEventLog.add("engine sync: " + x
                            + " contact(s) carried to the built-in book");
                } catch (Exception ignored) { }
            }
            pf.putBoolean(DesktopNode.KEY_ENGINE_JAR, on);
            pf.putBoolean(DesktopNode.KEY_FLIP_ANNOUNCE, on);
            DesktopEventLog.add("engine switch: "
                    + (on ? "classic (jar)" : "built-in") + " — restarting");
            window.restartEngine();
        }));
        mBody.add(nodeCard);
        mBody.add(k.vgap(14));

        // ---- node link: KeetChat via a local minimaCore Desktop node ----
        mBody.add(k.sectionLabel(Language.msg("desktop.nodeLink")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel linkCard = k.card();
        javax.swing.JTextField linkUrl = new javax.swing.JTextField(
                com.eurobuddha.maxima.desktop.wallet.DesktopNodeLink.configuredUrl());
        if (linkUrl.getText().isEmpty()) {
            linkUrl.setText(com.eurobuddha.maxima.desktop.wallet.DesktopNodeLink.DEFAULT_URL);
        }
        javax.swing.JPasswordField linkPw = new javax.swing.JPasswordField(
                com.eurobuddha.maxima.desktop.wallet.DesktopNodeLink.configuredPassword());
        linkUrl.setAlignmentX(Component.LEFT_ALIGNMENT);
        linkPw.setAlignmentX(Component.LEFT_ALIGNMENT);
        javax.swing.JLabel linkState = k.sub(
                node.chat().hasKeets()
                        ? (node.chat().keetsPubkey() != null
                                ? Language.msg("desktop.linked")
                                : Language.msg("desktop.linkedWaiting"))
                        : Language.msg("desktop.unlinked"));
        Language.font(linkState, t.font(11f));
        linkState.setForeground(t.subtext);
        javax.swing.JButton linkSave = Language.bind(new javax.swing.JButton(), Language.msg("desktop.linkConnect"));
        javax.swing.JButton linkClear = Language.bind(new javax.swing.JButton(), Language.msg("common.clear"));
        linkSave.addActionListener(ev -> {
            com.eurobuddha.maxima.desktop.wallet.DesktopNodeLink.save(
                    linkUrl.getText().trim(), new String(linkPw.getPassword()));
            k.setSub(linkState, Language.msg("connection.connecting"));
            node.restartKeets();
            new Thread(() -> {
                try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
                String kpk = node.chat().keetsPubkey();
                javax.swing.SwingUtilities.invokeLater(() -> k.setSub(linkState,
                        kpk != null ? Language.msg("desktop.linked")
                                    : Language.msg("desktop.linkedNoIdentity")));
            }, "keets-link-check").start();
        });
        linkClear.addActionListener(ev -> {
            com.eurobuddha.maxima.desktop.wallet.DesktopNodeLink.clear();
            k.setSub(linkState, Language.msg("desktop.unlinked"));
        });
        javax.swing.JPanel linkRow = new javax.swing.JPanel();
        linkRow.setOpaque(false);
        linkRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        linkRow.setLayout(new BoxLayout(linkRow, BoxLayout.Y_AXIS));
        javax.swing.JLabel linkHint = k.sub(Language.msg("desktop.linkHelp"));
        Language.font(linkHint, t.font(11f));
        linkHint.setForeground(t.subtext);
        linkRow.add(linkHint);
        linkRow.add(k.vgap(6));
        linkRow.add(k.kvLine(Language.msg("desktop.rpcUrl"), ""));
        linkRow.add(linkUrl);
        linkRow.add(k.vgap(6));
        linkRow.add(k.kvLine(Language.msg("desktop.rpcPassword"), ""));
        linkRow.add(linkPw);
        linkRow.add(k.vgap(8));
        javax.swing.JPanel linkBtns = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        linkBtns.setOpaque(false);
        linkBtns.setAlignmentX(Component.LEFT_ALIGNMENT);
        linkBtns.setMinimumSize(new Dimension(0, 0));
        mActionRows.add(linkBtns);
        linkBtns.add(linkSave);
        linkBtns.add(linkClear);
        linkRow.add(linkBtns);
        linkRow.add(k.vgap(6));
        linkRow.add(linkState);
        linkRow.setBorder(new EmptyBorder(10, 12, 10, 12));
        linkCard.add(linkRow);
        mBody.add(linkCard);
        mBody.add(k.vgap(14));

        // ---- keys & security ----
        mBody.add(k.sectionLabel(Language.msg("nativeSettings.keysSecurity")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel keys = k.card();
        keys.add(k.kvLine(Language.msg("nativeSettings.seed"), Language.msg("nativeSettings.seedWords")));
        keys.add(k.sub(Language.msg("desktop.seedWarning")));
        keys.add(k.vgap(10));
        JPanel kr = rowX();
        mActionRows.add(kr);
        DKit.HoverButton show = k.dangerButton(Language.msg("nativeSettings.showSeed"));
        show.onClick(this::showSeed);
        DKit.HoverButton restore = k.ghostButton(Language.msg("nativeSettings.restoreSeed"));
        restore.onClick(this::restoreSeed);
        kr.add(show);
        kr.add(Box.createRigidArea(new Dimension(8, 0)));
        kr.add(restore);
        kr.add(Box.createHorizontalGlue());
        keys.add(kr);
        mBody.add(keys);
        mBody.add(k.vgap(14));

        // ---- about ----
        mBody.add(k.sectionLabel(Language.msg("nativeSettings.about")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel about = k.card();
        about.add(k.sub(Language.msg("desktop.about")));
        about.add(k.vgap(6));
        about.add(k.kvLine(Language.msg("common.version"), DesktopMain.APP_VERSION));
        mBody.add(about);

        mBody.add(Box.createVerticalGlue());
        if (getWidth() > 0) onWidth(getWidth());
        mBody.revalidate();
        mBody.repaint();
    }

    private void rebuild() { mBuilt = false; refresh(); }

    // ---- seed dialogs ----

    private void showSeed() {
        int ok = javax.swing.JOptionPane.showConfirmDialog(this,
                Language.text("desktop.revealQuestion"),
                Language.text("nativeDialog.showSeedQuestion"), javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        if (ok != javax.swing.JOptionPane.OK_OPTION) return;
        String phrase = node.seedPhrase();
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.add(k.sub(Language.msg("desktop.seedOffline")));
        body.add(k.vgap(10));
        JTextArea ta = new JTextArea(phrase.isEmpty() ? Language.text("desktop.unavailable") : phrase);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setEditable(false);
        ta.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 14));
        ta.setForeground(t.text);
        ta.setBackground(t.input);
        ta.setBorder(new EmptyBorder(12, 12, 12, 12));
        ta.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(ta);
        body.add(k.vgap(12));
        JPanel r = rowX();
        DKit.HoverButton copy = k.primaryButton(Language.msg("desktop.copySeed"));
        copy.onClick(() -> copySeedAutoClear(phrase));
        r.add(copy);
        r.add(Box.createHorizontalGlue());
        body.add(r);
        dialog(Language.text("desktop.yourSeed"), body, 460);
    }

    private void copySeedAutoClear(String phrase) {
        DKit.copy(phrase);
        javax.swing.Timer tm = new javax.swing.Timer(60000, e -> {
            try {
                java.awt.datatransfer.Clipboard cb =
                        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
                Object cur = cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (phrase.equals(cur)) {
                    cb.setContents(new java.awt.datatransfer.StringSelection(""), null);
                }
            } catch (Exception ignored) { }
        });
        tm.setRepeats(false);
        tm.start();
        javax.swing.JOptionPane.showMessageDialog(this, Language.text("desktop.seedCopied"));
    }

    private void restoreSeed() {
        JTextArea input = new JTextArea(3, 28);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        int ok = javax.swing.JOptionPane.showConfirmDialog(this,
                new Object[]{
                    Language.text("desktop.restoreQuestion"),
                    new JScrollPane(input)},
                Language.text("nativeSettings.restoreSeed"), javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        if (ok != javax.swing.JOptionPane.OK_OPTION) return;
        String phrase = input.getText().trim();
        if (phrase.isEmpty()) return;
        try {
            node.restoreSeed(phrase);
            javax.swing.JOptionPane.showMessageDialog(this,
                    Language.text("desktop.restored"));
            System.exit(0);
        } catch (Exception e) {
            javax.swing.JOptionPane.showMessageDialog(this, Language.text("desktop.restoreError", "error", Language.system(e.getMessage())));
        }
    }

    // ---- helpers ----

    private JComponent switchRow(CharSequence title, CharSequence hint, boolean on,
                                 java.util.function.Consumer<Boolean> cb) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 0, 6, 0));
        row.setMinimumSize(new Dimension(0, 0));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JLabel tl = Language.bind(new JLabel(), title);
        Language.font(tl, t.semibold(13.5f));
        tl.setForeground(t.text);
        tl.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel hl = k.sub(hint);
        hl.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(tl);
        left.add(hl);
        row.add(left, BorderLayout.CENTER);
        DKit.Toggle sw = k.toggle(on, cb);
        JPanel tw = new JPanel(new BorderLayout());
        tw.setOpaque(false);
        tw.add(sw, BorderLayout.NORTH);
        row.add(tw, BorderLayout.EAST);
        return row;
    }

    private JPanel rowX() {
        JPanel r = new JPanel();
        r.setOpaque(false);
        r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        r.setMinimumSize(new Dimension(0, 0));
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        return r;
    }

    /** Keep the same controls; stack long translated actions when the column is narrow. */
    @Override public void onWidth(int width) {
        int available = Math.max(1, Math.min(720, width - 44) - 56);
        for (JPanel row : mActionRows) DKit.layoutActions(row, available);
        mBody.revalidate();
    }

    private JDialog dialog(String title, JComponent body, int width) {
        JDialog d = new JDialog(window.frame(), title, false);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(t.card);
        wrap.setBorder(new EmptyBorder(16, 18, 18, 18));
        wrap.add(body, BorderLayout.CENTER);
        d.setContentPane(wrap);
        d.pack();
        d.setSize(Math.max(width, d.getWidth()), d.getHeight());
        d.setLocationRelativeTo(window.frame());
        d.setVisible(true);
        return d;
    }

    private String currentName() {
        String p = PREFS.get("name", "");
        return p.isEmpty() ? "Parlons! Desktop" : p;
    }

    /** The copyField's value JTextArea, so refresh can update the live publickey. */
    private JLabel findValueLabel(JComponent copyField) {
        return null;   // copyField renders the value in a JTextArea, not a JLabel; live-updating
                       // the publickey isn't needed (it's constant per identity).
    }

    private static JComponent holder(JComponent c) {
        JPanel h = new JPanel(new BorderLayout());
        h.setOpaque(false);
        h.add(c, BorderLayout.NORTH);
        return h;
    }
}
