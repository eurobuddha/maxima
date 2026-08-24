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
public final class SettingsPanel extends JPanel implements MaximaWindow.Tab {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;
    private final MaximaWindow window;
    private final JPanel mBody = new JPanel();
    private JLabel mKeyValue;
    private boolean mBuilt;

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

    public String label() { return "Settings"; }
    public JComponent view() { return this; }

    public void refresh() {
        if (!mBuilt) { mBuilt = true; build(); }
        if (mKeyValue != null) {
            try { mKeyValue.setText(node.port().publicKeyHex()); } catch (Exception ignored) { }
        }
    }

    private void build() {
        mBody.removeAll();

        // ---- your name + maxima publickey ----
        mBody.add(k.sectionLabel("Your name"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel nameCard = k.card();
        JPanel nameRow = rowX();
        JTextField name = k.field("Display name");
        name.setText(currentName());
        name.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        nameRow.add(name);
        nameRow.add(Box.createRigidArea(new Dimension(9, 0)));
        DKit.HoverButton set = k.primaryButton("Set");
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
        JComponent keyField = k.copyField("maxima publickey", node.port().publicKeyHex(), false);
        nameCard.add(keyField);
        mKeyValue = findValueLabel(keyField);
        mBody.add(nameCard);
        mBody.add(k.vgap(14));

        // ---- privacy ----
        mBody.add(k.sectionLabel("Privacy"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel priv = k.card();
        boolean rr = PREFS.getBoolean("readReceipts", true);
        node.setReadReceipts(rr);
        priv.add(switchRow("Read receipts",
                rr ? "Contacts are told when you read their message"
                        : "Contacts see delivery only, never when you read", rr, on -> {
            PREFS.putBoolean("readReceipts", on);
            node.setReadReceipts(on);
            rebuild();
        }));
        priv.add(k.divider());
        boolean snd = Chirp.enabled();
        priv.add(switchRow("Message sound",
                snd ? "Pssst! when a message arrives" : "Silent", snd, on -> {
            Chirp.setEnabled(on);
            if (on) Chirp.play();   // audition it, like flipping it on the phone
            rebuild();
        }));
        priv.add(k.divider());
        priv.add(k.kvLine("Delivery receipts", "always on"));
        mBody.add(priv);
        mBody.add(k.vgap(14));

        // ---- appearance ----
        mBody.add(k.sectionLabel("Appearance"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel appear = k.card();
        String cur = PREFS.get("appearance", "system");
        int sel = cur.equals("light") ? 1 : cur.equals("dark") ? 2 : 0;
        DKit.Segmented seg = k.segmented(new String[]{"System", "Light", "Dark"}, sel, i -> {
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
        mBody.add(k.sectionLabel("Node"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel nodeCard = k.card();
        boolean core = PREFS.getBoolean("coreNode", true);
        nodeCard.add(switchRow("Run as core node",
                core ? "Shown to contacts as an always-on core node"
                        : "Shown to contacts as an ordinary node", core, on -> {
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
        nodeCard.add(switchRow("Classic engine",
                jarOn ? "Routing runs on classic Maxima (maxima.jar)"
                        : "Routing runs on the built-in engine — flip for classic Maxima",
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

        // ---- keys & security ----
        mBody.add(k.sectionLabel("Keys & security"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel keys = k.card();
        keys.add(k.kvLine("Seed phrase", "24 words"));
        keys.add(k.sub("These words are your identity AND a spendable Minima wallet seed."));
        keys.add(k.vgap(10));
        JPanel kr = rowX();
        DKit.HoverButton show = k.dangerButton("Show seed phrase");
        show.onClick(this::showSeed);
        DKit.HoverButton restore = k.ghostButton("Restore a seed phrase");
        restore.onClick(this::restoreSeed);
        kr.add(show);
        kr.add(Box.createRigidArea(new Dimension(8, 0)));
        kr.add(restore);
        kr.add(Box.createHorizontalGlue());
        keys.add(kr);
        mBody.add(keys);
        mBody.add(k.vgap(14));

        // ---- about ----
        mBody.add(k.sectionLabel("About"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel about = k.card();
        about.add(k.sub("Maxima desktop — a decentralized, relay-routed messenger. "
                + "Your identity lives only on your devices; no server holds your account."));
        about.add(k.vgap(6));
        about.add(k.kvLine("Version", DesktopMain.APP_VERSION));
        mBody.add(about);

        mBody.add(Box.createVerticalGlue());
        mBody.revalidate();
        mBody.repaint();
    }

    private void rebuild() { mBuilt = false; refresh(); }

    // ---- seed dialogs ----

    private void showSeed() {
        int ok = javax.swing.JOptionPane.showConfirmDialog(this,
                "Your 24 words are your identity AND a spendable wallet seed.\n"
                        + "Make sure nobody is looking. Reveal them?",
                "Show seed phrase?", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        if (ok != javax.swing.JOptionPane.OK_OPTION) return;
        String phrase = node.seedPhrase();
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.add(k.sub("Write these down, offline. Anyone with them is you."));
        body.add(k.vgap(10));
        JTextArea ta = new JTextArea(phrase.isEmpty() ? "(unavailable)" : phrase);
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
        DKit.HoverButton copy = k.primaryButton("Copy (auto-clears in 60s)");
        copy.onClick(() -> copySeedAutoClear(phrase));
        r.add(copy);
        r.add(Box.createHorizontalGlue());
        body.add(r);
        dialog("Your seed phrase", body, 460);
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
        javax.swing.JOptionPane.showMessageDialog(this, "Copied — will auto-clear in 60s.");
    }

    private void restoreSeed() {
        JTextArea input = new JTextArea(3, 28);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        int ok = javax.swing.JOptionPane.showConfirmDialog(this,
                new Object[]{
                    "This REPLACES the identity on this machine with the one your words derive.\n"
                            + "Contacts and chats here are cleared. Paste your 24 words:",
                    new JScrollPane(input)},
                "Restore a seed phrase", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        if (ok != javax.swing.JOptionPane.OK_OPTION) return;
        String phrase = input.getText().trim();
        if (phrase.isEmpty()) return;
        try {
            node.restoreSeed(phrase);
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Identity restored. Maxima will now close — reopen it to use the restored identity.");
            System.exit(0);
        } catch (Exception e) {
            javax.swing.JOptionPane.showMessageDialog(this, "Couldn't restore: " + e.getMessage());
        }
    }

    // ---- helpers ----

    private JComponent switchRow(String title, String hint, boolean on,
                                 java.util.function.Consumer<Boolean> cb) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 0, 6, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JLabel tl = new JLabel(title);
        tl.setFont(t.semibold(13.5f));
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
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        return r;
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
