package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.Group;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * The desktop chat window — a faithful recreation of the phone shell: a top app
 * bar ("Maxima" + version + live status pill + theme toggle) and a fixed 5-tab
 * strip over the five pages, all painted from the shared {@link Theme}. No left
 * rail. The window reflows from full-screen down to ~360px; panels that care about
 * width implement {@link Responsive} and are told the content width on every resize
 * and tab switch. A 2-second heartbeat refreshes the visible tab, like the phone's
 * {@code MainActivity}.
 */
public final class MaximaWindow {

    /** The Parlons app icon, loaded once from the classpath (null if absent). */
    private static java.awt.image.BufferedImage sAppIcon;
    private static java.awt.image.BufferedImage appIcon() {
        if (sAppIcon == null) {
            try (java.io.InputStream in =
                         MaximaWindow.class.getResourceAsStream("/app-icon.png")) {
                if (in != null) sAppIcon = javax.imageio.ImageIO.read(in);
            } catch (Exception ignored) { }
        }
        return sAppIcon;
    }

    /** Window / taskbar icon (Windows + Linux read this; harmless on macOS). */
    private static void applyAppIcon(javax.swing.JFrame f) {
        java.awt.image.BufferedImage img = appIcon();
        if (img != null) f.setIconImage(img);
    }

    /** macOS Dock icon — JFrame.setIconImage is ignored there; use Taskbar. */
    private static void applyDockIcon() {
        java.awt.image.BufferedImage img = appIcon();
        if (img == null) return;
        try {
            if (java.awt.Taskbar.isTaskbarSupported()) {
                java.awt.Taskbar.getTaskbar().setIconImage(img);
            }
        } catch (Throwable ignored) { }
    }


    /** Every tab is a page that can repaint itself from current node state. */
    public interface Tab {
        String label();
        JComponent view();
        void refresh();
    }

    /** Panels that reflow implement this to receive the live content width. */
    public interface Responsive {
        void onWidth(int contentWidth);
    }

    private final JFrame mFrame;
    private final Theme t;
    private final DKit k;
    private final DesktopNode mNode;

    private final CardLayout mCards = new CardLayout();
    private final JPanel mContent = new JPanel(mCards);
    private final List<Tab> mTabs = new ArrayList<>();
    private TabStrip mStrip;
    private int mSelected = 0;

    private final Timer mBeat;
    private final Runnable mChangeHook;

    private JLabel mStatusText;
    private StatusDot mStatusDot;

    public MaximaWindow(DesktopNode zNode, Theme zTheme) {
        mNode = zNode;
        t = zTheme;
        k = new DKit(zTheme);

        mFrame = new JFrame("Parlons");
        applyAppIcon(mFrame);
        mFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        mFrame.setMinimumSize(new Dimension(360, 560));   // phone-narrow floor
        mFrame.setSize(Integer.getInteger("maxima.w", 1040), Integer.getInteger("maxima.h", 720));
        mFrame.setLocationRelativeTo(null);
        // -Dmaxima.x/-Dmaxima.y position the window (dev/QA convenience).
        Integer px = Integer.getInteger("maxima.x", null), py = Integer.getInteger("maxima.y", null);
        if (px != null && py != null) {
            mFrame.setLocation(px, py);
        }

        applyDockIcon();

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(t.bg);

        ChatsPanel chats = new ChatsPanel(mNode, t);
        chats.setHost(this);          // so the chat can borrow the single wallet
        mTabs.add(chats);
        mTabs.add(new ContactsPanel(mNode, t, this));
        mTabs.add(new WalletPanel(mNode, t));
        mTabs.add(new NetworkPanel(mNode, t));
        mTabs.add(new SettingsPanel(mNode, t, this));

        for (int i = 0; i < mTabs.size(); i++) {
            mContent.add(mTabs.get(i).view(), String.valueOf(i));
        }
        mContent.setBackground(t.bg);

        // Header block: app bar + tab strip, both on the header color.
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(t.header);
        header.add(buildAppBar());
        // Phone parity: FOUR visible tabs (Chats/Contacts/Wallet/Network).
        // Settings is the last panel (index 4) and is reached via the ⋮ overflow,
        // never shown as a tab — so the strip carries every tab but the last.
        int stripCount = mTabs.size() - 1;
        String[] labels = new String[stripCount];
        for (int i = 0; i < stripCount; i++) labels[i] = mTabs.get(i).label();
        mStrip = new TabStrip(labels, t, this::select);
        header.add(mStrip);

        root.add(header, BorderLayout.NORTH);
        root.add(mContent, BorderLayout.CENTER);
        mFrame.setContentPane(root);

        // Responsive: tell the visible panel the content width on resize.
        mContent.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) { pushWidth(); }
        });

        // -Dmaxima.tab=N opens a given tab first (dev/QA convenience; default Chats).
        int start = 0;
        try { start = Math.max(0, Math.min(mTabs.size() - 1, Integer.getInteger("maxima.tab", 0))); }
        catch (Exception ignored) { }
        select(start);

        mBeat = new Timer(2000, e -> {
            try { mTabs.get(mSelected).refresh(); } catch (Exception ignored) { }
            refreshStatus();
        });
        mBeat.start();

        mChangeHook = () -> SwingUtilities.invokeLater(() -> {
            try { mTabs.get(mSelected).refresh(); } catch (Exception ignored) { }
            refreshStatus();
        });
        mNode.addChangeListener(mChangeHook);

        // Inbound "pssssst!" chirp — phone parity. Fires only for a message from
        // someone else that is NOT in the conversation you're currently reading
        // (and not while that thread is focused on screen). Gated by the
        // Message-sound preference inside Chirp.
        mNode.addChatListener(new ChatEngine.Listener() {
            public void onMessage(ChatEngine.Entry e) {
                if (e.mine) {
                    return;
                }
                String conv = e.isGroup() ? e.groupId : e.peer;
                boolean reading = (mTabs.get(0) instanceof ChatsPanel)
                        && ((ChatsPanel) mTabs.get(0)).isViewing(conv)
                        && mFrame.isFocused();
                if (!reading) {
                    Chirp.play();
                    SwingUtilities.invokeLater(MaximaWindow.this::flashTray);
                }
            }
            public void onStateChanged(ChatEngine.Entry e) { }
            public void onGroupChanged(Group g) { }
        });

        // Tier-0 telephony: receive call signals and decline gracefully so a
        // phone caller gets an instant "unavailable" instead of ringing out.
        new DesktopCalls(mNode, mFrame, t).attach();
    }

    /** Optional visual half of a notification: nudge the tray tooltip if a tray
     *  icon exists (windowed mode may have none — then this is a no-op). */
    private void flashTray() {
        try {
            java.awt.SystemTray tray = java.awt.SystemTray.isSupported()
                    ? java.awt.SystemTray.getSystemTray() : null;
            if (tray != null && tray.getTrayIcons().length > 0) {
                tray.getTrayIcons()[0].displayMessage(
                        "Parlons", "New message", java.awt.TrayIcon.MessageType.NONE);
            }
        } catch (Exception ignored) { }
    }

    public void show() { mFrame.setVisible(true); }
    public JFrame frame() { return mFrame; }
    public DesktopNode node() { return mNode; }

    // Engine flip (built-in ↔ classic): DesktopMain wires the actual rebuild.
    private Runnable mEngineRestart;
    public void setEngineRestart(Runnable r) { mEngineRestart = r; }
    public void restartEngine() { if (mEngineRestart != null) mEngineRestart.run(); }

    /** The single wallet-owning panel, so the chat can send a payment through the
     *  one wallet instance (never a second one — WOTS key-reuse hazard). */
    public WalletPanel wallet() {
        for (Tab tab : mTabs) {
            if (tab instanceof WalletPanel) return (WalletPanel) tab;
        }
        return null;
    }

    /** Switch to the Chats tab and open a conversation with a contact (from Contacts). */
    public void openChat(String pubkey) {
        select(0);
        Tab chats = mTabs.get(0);
        if (chats instanceof ChatsPanel) {
            ((ChatsPanel) chats).openConversation(pubkey, false);
        }
    }

    /** Rebuild the whole window under a new palette, preserving geometry; no reconnect. */
    public void switchTheme(Theme.Mode m) {
        mBeat.stop();
        mNode.removeChangeListener(mChangeHook);
        MaximaWindow w = new MaximaWindow(mNode, new Theme(m));
        w.frame().setBounds(mFrame.getBounds());
        w.frame().setExtendedState(mFrame.getExtendedState());
        w.show();
        mFrame.dispose();
    }

    // ---- app bar ----

    private JComponent buildAppBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBackground(t.header);
        bar.setBorder(new EmptyBorder(10, 16, 10, 10));
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Two-line brand block, exactly like the phone: "Parlons" + version on the
        // first line, "Powered by Maxima" tiny below. Feels intentional, not heavy.
        JPanel brandCol = new JPanel();
        brandCol.setOpaque(false);
        brandCol.setLayout(new BoxLayout(brandCol, BoxLayout.Y_AXIS));
        brandCol.setAlignmentY(Component.CENTER_ALIGNMENT);

        JPanel line1 = new JPanel();
        line1.setOpaque(false);
        line1.setLayout(new BoxLayout(line1, BoxLayout.X_AXIS));
        line1.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel brand = new JLabel("Parlons");
        brand.setFont(t.extrabold(21f));
        brand.setForeground(t.onHeader);
        line1.add(brand);
        JLabel ver = new JLabel("v" + com.eurobuddha.maxima.desktop.DesktopMain.APP_VERSION);
        ver.setFont(t.font(10.5f));
        ver.setForeground(DKit.alpha(t.onHeader, 150));
        ver.setBorder(new EmptyBorder(5, 8, 0, 0));
        line1.add(ver);
        brandCol.add(line1);

        JLabel tagline = new JLabel("Powered by Maxima");
        tagline.setFont(t.font(9f));
        tagline.setForeground(DKit.alpha(t.onHeader, 110));
        tagline.setAlignmentX(Component.LEFT_ALIGNMENT);
        tagline.setBorder(new EmptyBorder(1, 1, 0, 0));
        brandCol.add(tagline);

        brandCol.setMaximumSize(brandCol.getPreferredSize());
        bar.add(brandCol);

        bar.add(javax.swing.Box.createHorizontalGlue());

        // Live status pill (dot + text), like the phone header_pill.
        DKit.RoundPanel statusPill = new DKit.RoundPanel(DKit.alpha(t.onHeader, 28), 999);
        statusPill.setLayout(new BoxLayout(statusPill, BoxLayout.X_AXIS));
        statusPill.setBorder(new EmptyBorder(5, 10, 5, 11));
        mStatusDot = new StatusDot();
        statusPill.add(mStatusDot);
        statusPill.add(javax.swing.Box.createRigidArea(new Dimension(6, 0)));
        mStatusText = new JLabel("starting…");
        mStatusText.setFont(t.medium(11f));
        mStatusText.setForeground(t.onHeader);
        statusPill.add(mStatusText);
        statusPill.setMaximumSize(statusPill.getPreferredSize());
        bar.add(statusPill);

        bar.add(javax.swing.Box.createRigidArea(new Dimension(8, 0)));

        // The phone's three top-bar action buttons, same order: theme, search, more.
        // Theme toggle (sun/moon) — light↔dark, persisted.
        Icons.Btn theme = new Icons.Btn(t.mode == Theme.Mode.DARK ? Icons.SUN : Icons.MOON,
                t.onHeader, DKit.alpha(t.onHeader, 24), 38, 20, 1.6f);
        theme.onClick(() -> {
            Theme.Mode next = t.mode == Theme.Mode.DARK ? Theme.Mode.LIGHT : Theme.Mode.DARK;
            java.util.prefs.Preferences.userRoot().node("com/eurobuddha/maxima/desktop")
                    .put("appearance", next == Theme.Mode.DARK ? "dark" : "light");
            switchTheme(next);
        });
        bar.add(theme);

        // Search (magnifying glass) → jump to Chats and focus its search (phone: showTab(0)).
        Icons.Btn search = new Icons.Btn(Icons.SEARCH, t.onHeader, DKit.alpha(t.onHeader, 24), 38, 19, 1.9f);
        search.onClick(() -> {
            select(0);
            Tab chats = mTabs.get(0);
            if (chats instanceof ChatsPanel) ((ChatsPanel) chats).focusSearch();
        });
        bar.add(search);

        // Overflow (three dots) → Settings (phone: showTab(4)).
        Icons.Btn more = new Icons.Btn(Icons.MORE, t.onHeader, DKit.alpha(t.onHeader, 24), 36, 18, 1.6f);
        more.onClick(() -> select(4));
        bar.add(more);
        return bar;
    }

    void select(int i) {
        mSelected = i;
        mCards.show(mContent, String.valueOf(i));
        if (mStrip != null) mStrip.setSelected(i);
        try { mTabs.get(i).refresh(); } catch (Exception ignored) { }
        pushWidth();
    }

    private void pushWidth() {
        int w = mContent.getWidth();
        if (w <= 0) return;
        Tab vis = mTabs.get(mSelected);
        if (vis instanceof Responsive) {
            ((Responsive) vis).onWidth(w);
        }
    }

    private void refreshStatus() {
        if (mStatusText == null) return;
        int hosts = 0;
        try { hosts = mNode.connectedCount(); } catch (Exception ignored) { }
        boolean ok = hosts > 0;
        mStatusText.setText(ok ? (hosts + (hosts == 1 ? " host" : " hosts")) : "offline");
        mStatusDot.setColor(ok ? t.success : t.error);
    }

    // ---- widgets ----

    private final class StatusDot extends JComponent {
        private Color c = t.pending;
        StatusDot() { setPreferredSize(new Dimension(8, 8)); setMaximumSize(new Dimension(8, 8)); }
        void setColor(Color zc) { c = zc; repaint(); }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c);
            g2.fillOval(0, 0, 8, 8);
            g2.dispose();
        }
    }

    /** A round-hover icon button for the app bar. */
    static final class IconButton extends JLabel {
        private boolean hover;
        private Runnable action;
        private final Theme theme;
        IconButton(String glyph, Theme t) {
            super(glyph, javax.swing.SwingConstants.CENTER);
            theme = t;
            setFont(t.medium(17f));
            setForeground(t.onHeader);
            setPreferredSize(new Dimension(38, 38));
            setMaximumSize(new Dimension(38, 38));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                public void mouseClicked(MouseEvent e) { if (action != null) action.run(); }
            });
        }
        IconButton onClick(Runnable r) { action = r; return this; }
        protected void paintComponent(Graphics g) {
            if (hover) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(DKit.alpha(theme.onHeader, 24));
                g2.fillOval(3, 3, getWidth() - 6, getHeight() - 6);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** The 5-tab strip: fixed tabs, underline indicator (not full width), on the header color. */
    static final class TabStrip extends JComponent {
        private final String[] labels;
        private int selected;
        private int hover = -1;
        private final Theme theme;
        private final java.util.function.IntConsumer onPick;

        TabStrip(String[] zLabels, Theme t, java.util.function.IntConsumer zOnPick) {
            labels = zLabels;
            theme = t;
            onPick = zOnPick;
            setPreferredSize(new Dimension(400, 44));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
                public void mouseClicked(MouseEvent e) {
                    int seg = segAt(e.getX());
                    if (seg >= 0 && onPick != null) onPick.accept(seg);
                }
            });
            addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                public void mouseMoved(MouseEvent e) {
                    int s = segAt(e.getX());
                    if (s != hover) { hover = s; repaint(); }
                }
            });
        }

        private int segAt(int x) {
            int n = labels.length;
            if (n == 0 || getWidth() <= 0) return -1;
            int seg = x * n / getWidth();
            return Math.max(0, Math.min(n - 1, seg));
        }

        void setSelected(int i) { selected = i; repaint(); }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), n = labels.length;
            int segW = w / Math.max(1, n);
            for (int i = 0; i < n; i++) {
                boolean sel = i == selected;
                g2.setFont(sel ? theme.semibold(13f) : theme.medium(13f));
                g2.setColor(sel ? theme.onHeader
                        : (i == hover ? DKit.alpha(theme.onHeader, 210) : DKit.alpha(theme.onHeader, 150)));
                java.awt.FontMetrics fm = g2.getFontMetrics();
                int lx = i * segW + (segW - fm.stringWidth(labels[i])) / 2;
                int ly = (h + fm.getAscent()) / 2 - 4;
                g2.drawString(labels[i], lx, ly);
                if (sel) {
                    int iw = Math.min(segW - 24, fm.stringWidth(labels[i]) + 14);
                    int ix = i * segW + (segW - iw) / 2;
                    g2.setColor(theme.onHeader);
                    g2.fillRoundRect(ix, h - 3, iw, 3, 3, 3);
                }
            }
            g2.dispose();
        }
    }
}
