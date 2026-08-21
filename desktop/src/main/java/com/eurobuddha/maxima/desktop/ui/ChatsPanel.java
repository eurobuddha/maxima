package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.ChatMedia;
import com.eurobuddha.maxima.core.chat.ChatPay;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.media.MediaManifest;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Chats tab — a faithful desktop recreation of the phone's chat list + chat
 * screen. Responsive: a two-pane list+conversation when the window is wide,
 * collapsing to a single phone-style column (list → open conversation → back)
 * when narrow. The conversation carries the phone's message bubbles (reflowing,
 * payment + media aware, delivery/read ticks) and the phone's input bar: an
 * attach button, an emoji picker, a wrapping text field, and a circular send FAB.
 */
public final class ChatsPanel extends JPanel implements MaximaWindow.Tab, MaximaWindow.Responsive {

    private static final int NARROW = 640;   // below this, single-column phone mode
    private static final int HEADER_H = 58;  // list search band == conversation header band (alignment)

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;
    private MaximaWindow mHost;   // set after construction; lets the chat borrow the single wallet

    private final JPanel mListPane = new JPanel(new BorderLayout());
    private final JPanel mList = new JPanel();
    private final JScrollPane mListScroll;

    private final JPanel mConvPane = new JPanel(new BorderLayout());
    private final JPanel mThread = new JPanel();
    private final JScrollPane mThreadScroll;
    private JComponent mScrollFab;
    private final JLabel mThreadTitle;
    private final JLabel mThreadSub;
    private final JTextArea mInput;
    private final Icons.Btn mBack;

    private JTextField mSearch;
    private String mQuery = "";
    private java.util.List<Hit> mHits = new java.util.ArrayList<>();
    private String mScrollToId;          // pending scroll-to-bubble after a search hit

    /** A cross-chat message search hit — mirrors the phone's SearchActivity.Hit. */
    private static final class Hit {
        final String conversation;
        final String title;
        final String snippet;
        final long time;
        final String entryId;
        Hit(String zConv, String zTitle, String zSnippet, long zTime, String zEntryId) {
            conversation = zConv; title = zTitle; snippet = zSnippet;
            time = zTime; entryId = zEntryId;
        }
    }

    private String mOpen;
    private boolean mOpenGroup;
    private boolean mNarrow;
    private boolean mLaidOut;
    private boolean mShowList = true;   // narrow-mode: list vs conversation
    private int mPaneWidth = 700;
    private String mLastSig = "";
    private String mThreadSig = "";

    private final java.util.Map<String, javax.swing.ImageIcon> mMediaCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ChatsPanel(DesktopNode zNode, Theme zTheme) {
        node = zNode;
        t = zTheme;
        k = new DKit(zTheme);
        setLayout(new BorderLayout());
        setBackground(t.bg);

        // ---- conversation list (search bar + list + floating new-chat FAB) ----
        mList.setLayout(new BoxLayout(mList, BoxLayout.Y_AXIS));
        mList.setBackground(t.card);
        mListScroll = scroll(mList, t.card);
        mListScroll.setBorder(null);
        mListPane.setBackground(t.card);
        mListPane.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, t.divider));

        // The list's top band — the SAME dark header colour and height as the
        // conversation header in the other pane, so the two form one continuous
        // top bar (no step). A translucent pill floats in it, phone header-style.
        JPanel searchWrap = new JPanel(new java.awt.GridBagLayout());
        searchWrap.setBackground(t.header);
        searchWrap.setPreferredSize(new Dimension(10, HEADER_H));
        searchWrap.setBorder(new EmptyBorder(0, 12, 0, 12));
        DKit.RoundPanel searchPill = k.round(DKit.alpha(t.onHeader, 26), 16);
        searchPill.setLayout(new BoxLayout(searchPill, BoxLayout.X_AXIS));
        searchPill.setBorder(new EmptyBorder(6, 12, 6, 12));
        searchPill.add(new Icons.Btn(Icons.SEARCH, DKit.alpha(t.onHeader, 170), null, 20, 14, 1.8f));
        searchPill.add(Box.createRigidArea(new Dimension(8, 0)));
        mSearch = new JTextField() {   // paints a "Search chats" hint when empty
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty() && !isFocusOwner()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(DKit.alpha(t.onHeader, 150));
                    g2.setFont(getFont());
                    java.awt.FontMetrics fm = g2.getFontMetrics();
                    g2.drawString("Search chats", 1,
                            (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                    g2.dispose();
                }
            }
        };
        mSearch.setOpaque(false);
        mSearch.setBorder(new EmptyBorder(4, 0, 4, 0));
        mSearch.setFont(t.font(13f));
        mSearch.setForeground(t.onHeader);
        mSearch.setCaretColor(t.onHeader);
        mSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { onSearch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { onSearch(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { onSearch(); }
        });
        mSearch.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusGained(java.awt.event.FocusEvent e) { mSearch.repaint(); }
            public void focusLost(java.awt.event.FocusEvent e) { mSearch.repaint(); }
        });
        searchPill.add(mSearch);
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        searchWrap.add(searchPill, gc);
        mListPane.add(searchWrap, BorderLayout.NORTH);

        // Floating new-chat FAB over the list. A JLayeredPane with a null layout does
        // NOT size its children on its own — it only worked when a resize event
        // happened to fire, which it didn't in two-pane/WEST mode, leaving the list
        // 0x0 and invisible. Override doLayout() so children are sized on EVERY layout
        // pass (reliable), full-bleed list + bottom-right FAB.
        final SendFabLike fab = new SendFabLike(t);
        fab.onClick(this::showNewChat);
        javax.swing.JLayeredPane layered = new javax.swing.JLayeredPane() {
            public void doLayout() {
                int w = getWidth(), h = getHeight();
                mListScroll.setBounds(0, 0, w, h);
                fab.setBounds(w - 62, h - 62, 52, 52);
            }
        };
        layered.add(mListScroll, Integer.valueOf(javax.swing.JLayeredPane.DEFAULT_LAYER));
        layered.add(fab, Integer.valueOf(javax.swing.JLayeredPane.PALETTE_LAYER));
        mListPane.add(layered, BorderLayout.CENTER);

        // ---- open conversation ----
        mConvPane.setBackground(t.chatBg);
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
        head.setBackground(t.header);
        head.setBorder(new EmptyBorder(0, 12, 0, 16));
        head.setPreferredSize(new Dimension(10, HEADER_H));   // == list search band
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, HEADER_H));
        mBack = new Icons.Btn(Icons.CHEVRON_LEFT, t.onHeader, DKit.alpha(t.onHeader, 24), 32, 20, 2.2f);
        mBack.onClick(() -> { mShowList = true; applyLayout(); });
        head.add(mBack);
        head.add(Box.createRigidArea(new Dimension(6, 0)));
        JPanel titleCol = new JPanel();
        titleCol.setOpaque(false);
        titleCol.setLayout(new BoxLayout(titleCol, BoxLayout.Y_AXIS));
        mThreadTitle = new JLabel("Select a conversation");
        mThreadTitle.setFont(t.semibold(15f));
        mThreadTitle.setForeground(t.onHeader);
        mThreadTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        mThreadSub = new JLabel(" ");
        mThreadSub.setFont(t.font(11.5f));
        mThreadSub.setForeground(DKit.alpha(t.onHeader, 175));
        mThreadSub.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleCol.add(mThreadTitle);
        titleCol.add(mThreadSub);
        head.add(titleCol);
        head.add(Box.createHorizontalGlue());
        // Call buttons (phone parity). Desktop is signaling-only, so these
        // explain that calls run on the phone; incoming calls are declined
        // gracefully by DesktopCalls.
        Icons.Btn videoCall = new Icons.Btn(Icons.VIDEO, t.onHeader, DKit.alpha(t.onHeader, 24), 34, 20, 1.8f);
        videoCall.onClick(() -> DesktopCalls.explainPlacingCalls(this));
        Icons.Btn voiceCall = new Icons.Btn(Icons.CALL, t.onHeader, DKit.alpha(t.onHeader, 24), 34, 18, 1.8f);
        voiceCall.onClick(() -> DesktopCalls.explainPlacingCalls(this));
        head.add(videoCall);
        head.add(Box.createRigidArea(new Dimension(2, 0)));
        head.add(voiceCall);
        mConvPane.add(head, BorderLayout.NORTH);

        mThread.setLayout(new BoxLayout(mThread, BoxLayout.Y_AXIS));
        mThread.setBackground(t.chatBg);
        mThread.setBorder(new EmptyBorder(16, 18, 12, 18));
        mThreadScroll = scroll(mThread, t.chatBg);
        mThreadScroll.setBorder(null);
        // Jump-to-latest FAB, shown only when scrolled up (phone parity).
        final DownFab downFab = new DownFab(t);
        downFab.onClick(this::jumpToLatest);
        downFab.setVisible(false);
        mScrollFab = downFab;
        javax.swing.JLayeredPane threadLayer = new javax.swing.JLayeredPane() {
            public void doLayout() {
                int w = getWidth(), h = getHeight();
                mThreadScroll.setBounds(0, 0, w, h);
                mScrollFab.setBounds(w - 54, h - 54, 40, 40);
            }
        };
        threadLayer.add(mThreadScroll, Integer.valueOf(javax.swing.JLayeredPane.DEFAULT_LAYER));
        threadLayer.add(mScrollFab, Integer.valueOf(javax.swing.JLayeredPane.PALETTE_LAYER));
        mThreadScroll.getVerticalScrollBar().addAdjustmentListener(e -> updateScrollFab());
        mConvPane.add(threadLayer, BorderLayout.CENTER);

        mInput = new JTextArea(1, 10);
        mInput.setLineWrap(true);
        mInput.setWrapStyleWord(true);
        mConvPane.add(buildInputBar(), BorderLayout.SOUTH);

        applyLayout();   // default two-pane; onWidth() adjusts on first resize
    }

    public String label() { return "Chats"; }
    public JComponent view() { return this; }

    public void onWidth(int contentWidth) {
        boolean narrow = contentWidth < NARROW;
        mPaneWidth = narrow ? contentWidth : Math.max(320, contentWidth - 320);
        if (narrow != mNarrow || !mLaidOut) {
            mNarrow = narrow;
            mLaidOut = true;
            applyLayout();
        }
        // Reflow bubbles to the new pane width.
        updateBubbleWidths();
    }

    // ---- responsive layout ----

    private void applyLayout() {
        removeAll();
        if (mNarrow) {
            mBack.setVisible(true);
            add(mShowList || mOpen == null ? mListPane : mConvPane, BorderLayout.CENTER);
        } else {
            mBack.setVisible(false);
            mListPane.setPreferredSize(new Dimension(320, 10));
            add(mListPane, BorderLayout.WEST);
            add(mConvPane, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    // ---- refresh ----

    private void onSearch() {
        mQuery = mSearch.getText();
        mLastSig = "";
        refresh();
    }

    private boolean matches(ChatEngine.Summary s) {
        String q = mQuery.trim().toLowerCase();
        if (q.isEmpty()) return true;
        String title = titleFor(s.conversation, node.chat().group(s.conversation) != null);
        return title.toLowerCase().contains(q)
                || (s.lastBody != null && s.lastBody.toLowerCase().contains(q));
    }

    private static final int MAX_HITS = 50;

    /** Substring scan of every message across every chat — bodies, media captions
     *  and payment memos, newest hits first, capped. Empty query → no hits (the
     *  chat list already shows conversations). Mirrors the phone's SearchActivity. */
    private List<Hit> searchMessages(String zQuery) {
        List<Hit> hits = new java.util.ArrayList<>();
        String q = zQuery.toLowerCase();
        if (q.isEmpty()) return hits;
        ChatEngine ce = node.chat();
        outer:
        for (ChatEngine.Summary s : ce.summaries()) {
            String conv = s.conversation;
            boolean group = ce.group(conv) != null;
            String title = titleFor(conv, group);
            for (ChatEngine.Entry e : ce.conversation(conv)) {
                String text = searchable(e.body);
                int at = text.toLowerCase().indexOf(q);
                if (at < 0) continue;
                hits.add(new Hit(conv, title, snippet(text, at, q.length()), e.time, e.id));
                if (hits.size() >= MAX_HITS) break outer;
            }
        }
        hits.sort((a, b) -> Long.compare(b.time, a.time));
        return hits;
    }

    /** The human-readable text of a message body — never wire artefacts. */
    private static String searchable(String zBody) {
        if (zBody == null) return "";
        if (ChatPay.isPayment(zBody)) return ChatPay.preview(zBody);
        if (ChatMedia.isMedia(zBody)) {
            String cap = ChatMedia.caption(zBody);
            int bar = cap.indexOf('|');    // voice notes: "duration|waveformhex"
            return bar >= 0 ? cap.substring(0, bar) : cap;
        }
        return zBody;
    }

    private static String snippet(String zText, int zAt, int zLen) {
        int from = Math.max(0, zAt - 28);
        int to = Math.min(zText.length(), zAt + zLen + 60);
        return (from > 0 ? "…" : "") + zText.substring(from, to)
                + (to < zText.length() ? "…" : "");
    }

    public void refresh() {
        ChatEngine ce = node.chat();
        List<ChatEngine.Summary> all = ce.summaries();
        List<ChatEngine.Summary> sums = new java.util.ArrayList<>();
        for (ChatEngine.Summary s : all) if (matches(s)) sums.add(s);
        // Cross-chat message search: when there's a query, also scan message
        // bodies / captions / payment memos across every conversation — the
        // phone's SearchActivity, folded into the same left pane.
        List<Hit> hits = searchMessages(mQuery.trim());
        StringBuilder sig = new StringBuilder(mQuery).append('#');
        for (ChatEngine.Summary s : sums) {
            sig.append(s.conversation).append(s.lastTime).append(s.unread).append('|');
        }
        sig.append("~H").append(hits.size());
        for (Hit h : hits) sig.append(h.entryId).append('|');
        if (!sig.toString().equals(mLastSig)) {
            mLastSig = sig.toString();
            mHits = hits;
            rebuildList(sums);
        }
        if (mOpen != null) {
            List<ChatEngine.Entry> conv = ce.conversation(mOpen);
            // The engine returns messages in HashMap order ("every reader sorts
            // explicitly"). Sort chronologically like the phone, else photos/voice
            // notes land wherever their id hashes instead of by time.
            conv.sort(java.util.Comparator
                    .comparingLong((ChatEngine.Entry e) -> e.time)
                    .thenComparing(e -> e.id));
            StringBuilder ts = new StringBuilder();
            for (ChatEngine.Entry e : conv) {
                ts.append(e.id).append(e.state).append(e.deliveredBy.size()).append('|');
            }
            if (!ts.toString().equals(mThreadSig)) {
                mThreadSig = ts.toString();
                rebuildThread(conv);
            }
        }
    }

    private void rebuildList(List<ChatEngine.Summary> sums) {
        mList.removeAll();
        boolean searching = !mQuery.trim().isEmpty();
        if (sums.isEmpty() && mHits.isEmpty()) {
            JPanel empty = new JPanel();
            empty.setOpaque(false);
            empty.setBorder(new EmptyBorder(40, 20, 20, 20));
            empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
            empty.add(k.sub(searching ? "No matches."
                    : "No conversations yet. Add a contact to start."));
            mList.add(empty);
        }
        if (!sums.isEmpty()) {
            if (searching) mList.add(sectionLabel("Chats"));
            for (ChatEngine.Summary s : sums) mList.add(row(s));
        }
        if (!mHits.isEmpty()) {
            mList.add(sectionLabel("Messages"));
            for (Hit h : mHits) mList.add(hitRow(h));
        }
        mList.add(Box.createVerticalGlue());
        mList.revalidate();
        mList.repaint();
    }

    private JComponent sectionLabel(String zText) {
        JLabel l = new JLabel(zText.toUpperCase(java.util.Locale.UK));
        l.setFont(t.semibold(10.5f));
        l.setForeground(t.subtext);
        l.setBorder(new EmptyBorder(14, 16, 5, 16));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(true);
        wrap.setBackground(t.card);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        wrap.add(l, BorderLayout.WEST);
        return wrap;
    }

    /** A message-search hit row: avatar, chat title + date, bold-free snippet.
     *  Clicking it opens the thread scrolled to that bubble, which then pulses. */
    private JComponent hitRow(Hit h) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(true);
        row.setBackground(t.card);
        row.setBorder(new EmptyBorder(9, 16, 9, 16));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        row.add(k.avatar(h.conversation, h.title, 40), BorderLayout.WEST);

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel(h.title);
        title.setFont(t.semibold(13.5f));
        title.setForeground(t.text);
        head.add(title, BorderLayout.CENTER);
        if (h.time > 0) {
            JLabel when = new JLabel(shortDate(h.time));
            when.setFont(t.font(11f));
            when.setForeground(t.subtext);
            head.add(when, BorderLayout.EAST);
        }
        col.add(head);
        JLabel sn = new JLabel(h.snippet);
        sn.setFont(t.font(12f));
        sn.setForeground(t.subtext);
        sn.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(sn);
        row.add(col, BorderLayout.CENTER);

        row.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { jumpToMessage(h.conversation, h.entryId); }
        });
        return row;
    }

    private String shortDate(long zMs) {
        return new java.text.SimpleDateFormat("d MMM", java.util.Locale.UK)
                .format(new java.util.Date(zMs));
    }

    /** Open a conversation and scroll to a specific message, which then pulses. */
    private void jumpToMessage(String zConv, String zEntryId) {
        mScrollToId = zEntryId;
        openConversation(zConv, node.chat().group(zConv) != null);
        // If the thread was already built (no rebuild fires), scroll now anyway.
        javax.swing.SwingUtilities.invokeLater(this::applyScrollTo);
    }

    /** Find the pending scroll-to bubble in the current thread, reveal + pulse it. */
    private void applyScrollTo() {
        if (mScrollToId == null) return;
        JComponent target = null;
        for (Component c : mThread.getComponents()) {
            if (c instanceof JComponent
                    && mScrollToId.equals(((JComponent) c).getClientProperty("entryId"))) {
                target = (JComponent) c;
                break;
            }
        }
        if (target == null) return;
        mScrollToId = null;
        final JComponent ft = target;
        java.awt.Rectangle r = ft.getBounds();
        mThread.scrollRectToVisible(new java.awt.Rectangle(
                r.x, Math.max(0, r.y - 40), r.width, r.height + 80));
        pulse(ft);
    }

    /** A brief highlight flash so the located message is obvious. */
    private void pulse(JComponent zRow) {
        final Color hi = blend(t.chatBg, t.accent, 0.32f);
        final Color lo = t.chatBg;
        zRow.setOpaque(true);
        zRow.setBackground(hi);
        zRow.repaint();
        final int[] n = {0};
        javax.swing.Timer tm = new javax.swing.Timer(230, null);
        tm.addActionListener(ev -> {
            n[0]++;
            if (n[0] >= 5) {
                tm.stop();
                zRow.setOpaque(false);
                zRow.setBackground(null);
            } else {
                zRow.setBackground((n[0] % 2 == 0) ? hi : lo);
            }
            zRow.repaint();
        });
        tm.setInitialDelay(230);
        tm.start();
    }

    private static Color blend(Color a, Color b, float f) {
        return new Color(
                Math.round(a.getRed() * (1 - f) + b.getRed() * f),
                Math.round(a.getGreen() * (1 - f) + b.getGreen() * f),
                Math.round(a.getBlue() * (1 - f) + b.getBlue() * f));
    }

    private JComponent row(ChatEngine.Summary s) {
        boolean group = node.chat().group(s.conversation) != null;
        String title = titleFor(s.conversation, group);
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(true);
        row.setBackground(s.conversation.equals(mOpen) && !mShowList ? t.selected : t.card);
        row.setBorder(new EmptyBorder(11, 16, 11, 16));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
        row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        row.add(k.avatar(s.conversation, title, 42), BorderLayout.WEST);

        JPanel mid = new JPanel();
        mid.setOpaque(false);
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(title);
        name.setFont(t.semibold(13.5f));
        name.setForeground(t.text);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        String preview = (s.lastMine ? "You: " : "") + previewBody(s.lastBody);
        JLabel last = new JLabel(clip(preview, 42));
        last.setFont(t.font(12f));
        last.setForeground(t.subtext);
        last.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(name);
        mid.add(last);
        row.add(mid, BorderLayout.CENTER);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        JLabel time = new JLabel(shortTime(s.lastTime));
        time.setFont(t.font(10.5f));
        time.setForeground(t.subtext);
        time.setAlignmentX(Component.RIGHT_ALIGNMENT);
        right.add(time);
        if (s.unread > 0) {
            right.add(Box.createRigidArea(new Dimension(0, 4)));
            right.add(unreadBadge(s.unread));
        }
        row.add(right, BorderLayout.EAST);

        final String rowTitle = title;
        row.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) open(s.conversation, group);
            }
            public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) rowMenu(s, rowTitle, e); }
            public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) rowMenu(s, rowTitle, e); }
        });
        return row;
    }

    /** Right-click a conversation: mark read, or clear its history on this machine
     *  — the phone's ChatsPage long-press menu. */
    private void rowMenu(ChatEngine.Summary s, String zTitle, MouseEvent ev) {
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        if (s.unread > 0) {
            javax.swing.JMenuItem read = new javax.swing.JMenuItem("Mark as read");
            read.addActionListener(a -> { node.chat().markRead(s.conversation); mLastSig = ""; refresh(); });
            m.add(read);
        }
        javax.swing.JMenuItem clear = new javax.swing.JMenuItem("Clear messages");
        clear.addActionListener(a -> confirmClear(s.conversation, zTitle));
        m.add(clear);
        m.show(ev.getComponent(), ev.getX(), ev.getY());
    }

    private void confirmClear(String zConv, String zTitle) {
        int r = javax.swing.JOptionPane.showConfirmDialog(this,
                "Delete every message with " + zTitle + " on THIS machine.\n"
                        + "It won't unsend anything or leave a group — the thread just\n"
                        + "starts empty again. This can't be undone.",
                "Clear this chat?", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        if (r == javax.swing.JOptionPane.OK_OPTION) {
            node.chat().clearConversation(zConv);
            if (zConv.equals(mOpen)) mThreadSig = "";
            mLastSig = "";
            refresh();
        }
    }

    private JComponent unreadBadge(int n) {
        JLabel badge = new JLabel(String.valueOf(n), javax.swing.SwingConstants.CENTER);
        badge.setFont(t.bold(10.5f));
        badge.setForeground(Color.WHITE);
        DKit.RoundPanel b = new DKit.RoundPanel(t.error, 999);
        b.setLayout(new BorderLayout());
        b.setBorder(new EmptyBorder(1, 6, 1, 6));
        b.add(badge);
        b.setAlignmentX(Component.RIGHT_ALIGNMENT);
        b.setMaximumSize(new Dimension(40, 18));
        return b;
    }

    /** Open a conversation from another tab (e.g. Contacts → Message). */
    public void openConversation(String conversation, boolean group) {
        open(conversation, group);
    }

    /** True when this exact conversation is open on screen (not the list). Used
     *  to suppress the inbound chirp for the thread you're already reading. */
    public boolean isViewing(String conversation) {
        return !mShowList && conversation != null && conversation.equals(mOpen);
    }

    /** Focus the conversation search field (top-bar magnifying glass). */
    public void focusSearch() {
        if (mSearch != null) {
            mSearch.requestFocusInWindow();
        }
    }

    private void open(String conversation, boolean group) {
        mOpen = conversation;
        mOpenGroup = group;
        mShowList = false;
        node.chat().markRead(conversation);
        mThreadTitle.setText(titleFor(conversation, group));
        mThreadSub.setText(group ? groupSub(conversation) : peerSub(conversation));
        mLastSig = "";
        mThreadSig = "";
        if (mNarrow) applyLayout();
        refresh();
        mInput.requestFocusInWindow();
    }

    private static final long CLUSTER_GAP_MS = 5 * 60 * 1000L;   // phone: 5-min clustering

    private void rebuildThread(List<ChatEngine.Entry> conv) {
        // Follow the bottom ONLY if you're already there; otherwise keep your
        // scroll position so a background refresh never yanks you out of history.
        javax.swing.JScrollBar vbar = mThreadScroll.getVerticalScrollBar();
        final boolean atBottom = vbar.getValue() + vbar.getVisibleAmount() >= vbar.getMaximum() - 48;
        final int keepVal = vbar.getValue();
        mThread.removeAll();
        String lastDay = null;
        for (int i = 0; i < conv.size(); i++) {
            ChatEngine.Entry e = conv.get(i);
            ChatEngine.Entry prev = i > 0 ? conv.get(i - 1) : null;
            ChatEngine.Entry next = i < conv.size() - 1 ? conv.get(i + 1) : null;

            // Date separator when the calendar day changes (Today / Yesterday / date).
            String day = dayKey(e.time);
            if (!day.equals(lastDay)) {
                mThread.add(dateSeparator(e.time));
                mThread.add(k.vgap(4));
                lastDay = day;
            }

            // iMessage-style clustering: same sender within 5 min on the same day.
            boolean first = prev == null || !sameCluster(prev, e);
            boolean last = next == null || !sameCluster(e, next);

            mThread.add(bubble(e, first, last));
            mThread.add(k.vgap(last ? 6 : 1));   // roomy between clusters, tight within
        }
        mThread.add(Box.createVerticalGlue());
        mThread.revalidate();
        mThread.repaint();
        javax.swing.SwingUtilities.invokeLater(() -> {
            javax.swing.JScrollBar v = mThreadScroll.getVerticalScrollBar();
            v.setValue(atBottom ? v.getMaximum() : Math.min(keepVal, v.getMaximum()));
            updateScrollFab();
            applyScrollTo();   // honour a pending search jump once the thread exists
        });
    }

    /** Show the jump-to-latest FAB only when scrolled up away from the bottom. */
    private void updateScrollFab() {
        if (mScrollFab == null) return;
        javax.swing.JScrollBar v = mThreadScroll.getVerticalScrollBar();
        boolean atBottom = v.getValue() + v.getVisibleAmount() >= v.getMaximum() - 48;
        mScrollFab.setVisible(!atBottom && mOpen != null && !mShowList);
    }

    private void jumpToLatest() {
        javax.swing.JScrollBar v = mThreadScroll.getVerticalScrollBar();
        v.setValue(v.getMaximum());
        updateScrollFab();
    }

    private int bubbleMax() {
        return Math.max(180, (int) (mPaneWidth * 0.72));
    }

    private void updateBubbleWidths() {
        int max = bubbleMax();
        for (Component row : mThread.getComponents()) {
            if (row instanceof JPanel) {
                for (Component c : ((JPanel) row).getComponents()) {
                    if (c instanceof Bubble) {
                        Dimension d = c.getPreferredSize();
                        c.setMaximumSize(new Dimension(Math.min(max, d.width), Integer.MAX_VALUE));
                    }
                }
            }
        }
        mThread.revalidate();
    }

    /** Two messages cluster if same author, same day, within the 5-min window. */
    private static boolean sameCluster(ChatEngine.Entry a, ChatEngine.Entry b) {
        return a.mine == b.mine
                && a.sender.equals(b.sender)
                && dayKey(a.time).equals(dayKey(b.time))
                && Math.abs(b.time - a.time) <= CLUSTER_GAP_MS;
    }

    private static String dayKey(long ms) {
        return new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date(ms));
    }

    /** "Today" / "Yesterday" / weekday-in-the-last-week / "d MMM yyyy". */
    private static String dateLabel(long ms) {
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.setTimeInMillis(ms);
        java.util.Calendar now = java.util.Calendar.getInstance();
        String key = dayKey(ms);
        if (key.equals(dayKey(now.getTimeInMillis()))) return "Today";
        now.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (key.equals(dayKey(now.getTimeInMillis()))) return "Yesterday";
        long ageDays = (System.currentTimeMillis() - ms) / (24L * 3600 * 1000);
        if (ageDays < 7) {
            return new java.text.SimpleDateFormat("EEEE", java.util.Locale.UK)
                    .format(new java.util.Date(ms));
        }
        return new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK)
                .format(new java.util.Date(ms));
    }

    /** Centered date pill, phone-style. */
    private JComponent dateSeparator(long ms) {
        JPanel line = new JPanel();
        line.setOpaque(false);
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        DKit.RoundPanel pill = k.round(t.input, 12);
        pill.setLayout(new BoxLayout(pill, BoxLayout.X_AXIS));
        pill.setBorder(new EmptyBorder(3, 12, 3, 12));
        JLabel lbl = new JLabel(dateLabel(ms));
        lbl.setFont(t.font(10.5f));
        lbl.setForeground(t.subtext);
        pill.add(lbl);
        pill.setMaximumSize(pill.getPreferredSize());
        line.add(Box.createHorizontalGlue());
        line.add(pill);
        line.add(Box.createHorizontalGlue());
        return line;
    }

    /** A message bubble with independent corner radii, so the first bubble of a
     *  cluster can carry a small 3px "tail" corner exactly like the phone. */
    static final class Bubble extends JPanel {
        private final Color bg;
        private final int tl, tr, br, bl;

        Bubble(Color bg, int tl, int tr, int br, int bl) {
            this.bg = bg;
            this.tl = tl;
            this.tr = tr;
            this.br = br;
            this.bl = bl;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
            p.moveTo(tl, 0);
            p.lineTo(w - tr, 0);
            p.quadTo(w, 0, w, tr);
            p.lineTo(w, h - br);
            p.quadTo(w, h, w - br, h);
            p.lineTo(bl, h);
            p.quadTo(0, h, 0, h - bl);
            p.lineTo(0, tl);
            p.quadTo(0, 0, tl, 0);
            p.closePath();
            g2.setColor(bg);
            g2.fill(p);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private JComponent bubble(ChatEngine.Entry e, boolean first, boolean last) {
        boolean mine = e.mine;
        boolean media = ChatMedia.isMedia(e.body);
        boolean pay = ChatPay.isPayment(e.body);

        JPanel line = new JPanel();
        line.setOpaque(false);
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);

        Color fg = mine ? t.bubbleOutText : t.bubbleInText;
        // Phone geometry: 8px corners, a 3px "tail" on the first bubble of a
        // cluster (top-outer corner), payment cards a uniform 16px accent.
        Bubble b;
        if (pay) {
            b = new Bubble(t.accent, 16, 16, 16, 16);
            fg = t.onAccent;
        } else {
            Color bg = mine ? t.bubbleOut : t.bubbleIn;
            int R = 8, tail = 3;
            if (!first) {
                b = new Bubble(bg, R, R, R, R);
            } else if (mine) {
                b = new Bubble(bg, R, tail, R, R);   // tail top-right
            } else {
                b = new Bubble(bg, tail, R, R, R);   // tail top-left
            }
        }
        b.setLayout(new BoxLayout(b, BoxLayout.Y_AXIS));
        b.setBorder(new EmptyBorder(8, 13, 7, 13));

        if (mOpenGroup && !mine && first) {
            JLabel who = new JLabel(nameFor(e.sender));
            who.setFont(t.semibold(11f));
            who.setForeground(DKit.alpha(fg, 200));
            who.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.add(who);
        }

        if (media) {
            addMediaTo(b, e.body, fg);
        } else if (pay) {
            JLabel dir = new JLabel(mine ? "↑ You sent" : "↓ You received");
            dir.setFont(t.font(11f));
            dir.setForeground(DKit.alpha(fg, 200));
            dir.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.add(dir);
            JLabel body = new JLabel(ChatPay.amount(e.body) + " " + ChatPay.tokenName(e.body));
            body.setFont(t.semibold(16f));
            body.setForeground(fg);
            body.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.add(body);
            String memo = ChatPay.memo(e.body);
            if (memo != null && !memo.isEmpty()) {
                JLabel mm = new JLabel(memo);
                mm.setFont(t.font(12.5f));
                mm.setForeground(DKit.alpha(fg, 210));
                mm.setAlignmentX(Component.LEFT_ALIGNMENT);
                b.add(mm);
            }
            // RULE 1: the transaction id must be recoverable in FULL. Click the
            // card to copy the complete txid to the clipboard (never truncated).
            final String txid = ChatPay.txid(e.body);
            if (txid != null && !txid.isEmpty()) {
                b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
                b.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent ev) {
                        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                                .setContents(new java.awt.datatransfer.StringSelection(txid), null);
                        info("Transaction id copied:\n" + txid);
                    }
                });
            }
        } else {
            JTextArea body = new JTextArea(e.body);
            body.setFont(t.font(13.5f));
            body.setForeground(fg);
            body.setOpaque(false);
            body.setEditable(false);
            body.setFocusable(false);
            body.setLineWrap(true);
            body.setWrapStyleWord(true);
            body.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.add(body);
        }

        if (last) {   // phone: timestamp + ticks only on the last bubble of a cluster
            String metaTxt;
            if (mine) {
                metaTxt = time(e.time) + "  " + stateGlyph(e);
            } else if (e.arrived > 0 && e.arrived - e.time >= 60_000) {
                // A late relay delivery: show both clocks, like the phone.
                metaTxt = "sent " + time(e.time) + " · arrived " + time(e.arrived);
            } else {
                metaTxt = time(e.time);
            }
            JLabel meta = new JLabel(metaTxt);
            meta.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 10));
            meta.setForeground(DKit.alpha(fg, 160));
            meta.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.add(meta);
        }

        // Right-click a bubble for the message menu (copy / copy txid / open image / info).
        final ChatEngine.Entry fe = e;
        b.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent ev) { if (ev.isPopupTrigger()) showMessageMenu(b, fe, ev.getX(), ev.getY()); }
            public void mouseReleased(MouseEvent ev) { if (ev.isPopupTrigger()) showMessageMenu(b, fe, ev.getX(), ev.getY()); }
        });

        Dimension pref = b.getPreferredSize();
        b.setMaximumSize(new Dimension(Math.min(bubbleMax(), pref.width), Integer.MAX_VALUE));

        if (mine) {
            line.add(Box.createHorizontalGlue());
            line.add(b);
        } else {
            line.add(b);
            line.add(Box.createHorizontalGlue());
        }
        line.putClientProperty("entryId", e.id);   // for search scroll-to-bubble
        return line;
    }

    private void showMessageMenu(Component anchor, ChatEngine.Entry e, int x, int y) {
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        boolean media = ChatMedia.isMedia(e.body);
        boolean pay = ChatPay.isPayment(e.body);
        if (!media && !pay) {
            javax.swing.JMenuItem copy = new javax.swing.JMenuItem("Copy text");
            copy.addActionListener(a -> clip(e.body));
            m.add(copy);
        }
        if (pay) {
            String txid = ChatPay.txid(e.body);
            if (txid != null && !txid.isEmpty()) {
                javax.swing.JMenuItem ct = new javax.swing.JMenuItem("Copy transaction id");
                ct.addActionListener(a -> { clip(txid); info("Transaction id copied:\n" + txid); });
                m.add(ct);
            }
        }
        if (media) {
            String mime = ChatMedia.mime(e.body), ref = ChatMedia.ref(e.body);
            if (mime != null && mime.startsWith("image/") && ref != null) {
                javax.swing.JMenuItem open = new javax.swing.JMenuItem("Open image");
                open.addActionListener(a -> openImage(ref, mime));
                m.add(open);
            }
        }
        javax.swing.JMenuItem info = new javax.swing.JMenuItem("Info");
        info.addActionListener(a -> showMessageInfo(e));
        m.add(info);
        m.show(anchor, x, y);
    }

    private void clip(String s) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(s == null ? "" : s), null);
    }

    private void showMessageInfo(ChatEngine.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(e.mine ? "you" : nameFor(e.sender)).append('\n');
        sb.append("Sent: ").append(new java.util.Date(e.time)).append('\n');
        if (e.arrived > 0) sb.append("Arrived: ").append(new java.util.Date(e.arrived)).append('\n');
        sb.append("Status: ").append(e.state == null ? "—" : e.state);
        if (ChatPay.isPayment(e.body)) {
            sb.append("\nAmount: ").append(ChatPay.amount(e.body)).append(' ')
                    .append(ChatPay.tokenName(e.body));
            sb.append("\nTransaction id: ").append(ChatPay.txid(e.body));
        }
        info(sb.toString());
    }

    // ---- input bar (phone: attach + emoji + field + send FAB) ----

    private JComponent buildInputBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(t.header);
        bar.setBorder(new EmptyBorder(9, 12, 11, 12));

        DKit.RoundPanel pill = new DKit.RoundPanel(t.input, 22);
        pill.setLayout(new BoxLayout(pill, BoxLayout.X_AXIS));
        pill.setBorder(new EmptyBorder(4, 10, 4, 8));

        Icons.Btn attach = new Icons.Btn(Icons.PLUS, t.subtext, null, 30, 19, 2f);
        attach.onClick(() -> showAttachMenu(attach));
        pill.add(attach);
        pill.add(Box.createRigidArea(new Dimension(2, 0)));
        Icons.Btn emoji = new Icons.Btn(Icons.SMILE, t.subtext, null, 30, 19, 1.6f);
        emoji.onClick(() -> showEmojiPicker(emoji));
        pill.add(emoji);
        pill.add(Box.createRigidArea(new Dimension(8, 0)));

        mInput.setFont(t.font(13.5f));
        mInput.setForeground(t.text);
        mInput.setCaretColor(t.text);
        mInput.setOpaque(false);
        mInput.setBorder(new EmptyBorder(7, 0, 7, 0));
        JScrollPane inScroll = new JScrollPane(mInput,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inScroll.setOpaque(false);
        inScroll.getViewport().setOpaque(false);
        inScroll.setBorder(null);
        inScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));
        pill.add(inScroll);

        bar.add(pill, BorderLayout.CENTER);

        SendFab fab = new SendFab(t);
        fab.onClick(this::sendCurrent);
        JPanel fabWrap = new JPanel(new BorderLayout());
        fabWrap.setOpaque(false);
        fabWrap.setBorder(new EmptyBorder(0, 8, 0, 0));
        fabWrap.add(fab, BorderLayout.CENTER);
        bar.add(fabWrap, BorderLayout.EAST);

        // Enter sends; Shift+Enter newline.
        mInput.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        mInput.getActionMap().put("send", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { sendCurrent(); }
        });
        mInput.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
                java.awt.event.InputEvent.SHIFT_DOWN_MASK), "newline");
        mInput.getActionMap().put("newline", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { mInput.append("\n"); }
        });
        return bar;
    }

    private void showEmojiPicker(JComponent anchor) {
        javax.swing.JPopupMenu pop = new javax.swing.JPopupMenu();
        pop.setBackground(t.card);
        pop.setBorder(new EmptyBorder(6, 6, 6, 6));
        String[] emojis = {
            "😀","😁","😂","🤣","😊","😍","😘","😎","🤔","😅","😉","🙂","🙃","😌","😴","🤗",
            "👍","👎","👏","🙏","💪","🔥","✨","🎉","❤️","💜","💯","✅","❌","⚡","💸","📎",
            "😢","😭","😡","😤","🥳","😱","🤯","👀","🙌","🤝","💀","🌟","☀️","🌙","⭐","🚀"
        };
        JPanel grid = new JPanel(new java.awt.GridLayout(0, 8, 2, 2));
        grid.setBackground(t.card);
        for (String em : emojis) {
            JLabel l = new JLabel(em, javax.swing.SwingConstants.CENTER);
            l.setFont(new java.awt.Font("Apple Color Emoji", java.awt.Font.PLAIN, 20));
            l.setPreferredSize(new Dimension(30, 30));
            l.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            l.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    mInput.insert(em, mInput.getCaretPosition());
                    pop.setVisible(false);
                    mInput.requestFocusInWindow();
                }
                public void mouseEntered(MouseEvent e) { l.setOpaque(true); l.setBackground(t.selected); l.repaint(); }
                public void mouseExited(MouseEvent e) { l.setOpaque(false); l.repaint(); }
            });
            grid.add(l);
        }
        pop.add(grid);
        pop.show(anchor, 0, -pop.getPreferredSize().height - 6);
    }

    private void sendCurrent() {
        String txt = mInput.getText().trim();
        if (txt.isEmpty() || mOpen == null) return;
        mInput.setText("");
        new Thread(() -> {
            try {
                if (mOpenGroup) {
                    node.chat().sendGroup(mOpen, txt);
                } else {
                    Contact c = node.port().contact(mOpen);
                    if (c != null) node.chat().send(c, txt);
                }
            } catch (Exception ignored) { }
            javax.swing.SwingUtilities.invokeLater(() -> { mThreadSig = ""; refresh(); });
        }, "chat-send").start();
    }

    /** The new-chat FAB: pick a contact to open (or start) a conversation. */
    private void showNewChat() {
        List<Contact> cs = node.port().contacts();
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        JDialogRef ref = new JDialogRef();
        if (cs.isEmpty()) {
            body.add(k.sub("No contacts yet. Add someone in the Contacts tab first."));
        } else {
            // A group starts here too, exactly like the phone's overflow "New group".
            JPanel grp = new JPanel(new BorderLayout(12, 0));
            grp.setOpaque(false);
            grp.setBorder(new EmptyBorder(9, 8, 9, 8));
            grp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
            grp.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            grp.add(new Icons.Btn(Icons.CONTACTS, t.accent, DKit.alpha(t.accent, 30), 38, 22, 1.8f),
                    BorderLayout.WEST);
            JLabel gl = new JLabel("New group");
            gl.setFont(t.semibold(13.5f));
            gl.setForeground(t.text);
            grp.add(gl, BorderLayout.CENTER);
            grp.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (ref.d != null) ref.d.dispose();
                    newGroup();
                }
            });
            body.add(grp);
            JPanel sep = new JPanel();
            sep.setBackground(t.divider);
            sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            body.add(sep);
            for (Contact c : cs) {
                String name = nameFor(c.publicKey);
                JPanel row = new JPanel(new BorderLayout(12, 0));
                row.setOpaque(false);
                row.setBorder(new EmptyBorder(9, 8, 9, 8));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
                row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
                row.add(k.avatar(c.publicKey, name, 38), BorderLayout.WEST);
                JLabel nm = new JLabel(name);
                nm.setFont(t.semibold(13.5f));
                nm.setForeground(t.text);
                row.add(nm, BorderLayout.CENTER);
                row.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        if (ref.d != null) ref.d.dispose();
                        open(c.publicKey, false);
                    }
                });
                body.add(row);
            }
        }
        JScrollPane sp = new JScrollPane(body,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getViewport().setBackground(t.card);
        JDialog d = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(this),
                "New chat", java.awt.Dialog.ModalityType.MODELESS);
        ref.d = d;
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(t.card);
        wrap.setBorder(new EmptyBorder(14, 14, 14, 14));
        wrap.add(sp, BorderLayout.CENTER);
        d.setContentPane(wrap);
        d.setSize(340, Math.min(520, 120 + cs.size() * 58));
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    private static final class JDialogRef { JDialog d; }

    /** Create a group — the phone's newGroup flow: pick members + name, then
     *  chat.createGroup fans the roster out (a network op, off the EDT). We are
     *  the sole admin. */
    private void newGroup() {
        List<Contact> cs = node.port().contacts();
        if (cs.isEmpty()) { info("Add some contacts first."); return; }

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new EmptyBorder(2, 2, 2, 2));

        final JTextField nameField = k.field("Group name");
        nameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(nameField);
        body.add(k.vgap(10));
        JLabel prompt = k.sub("Who is in the group?");
        prompt.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(prompt);
        body.add(k.vgap(4));

        final java.util.List<javax.swing.JCheckBox> boxes = new java.util.ArrayList<>();
        for (Contact c : cs) {
            javax.swing.JCheckBox cb = new javax.swing.JCheckBox(nameFor(c.publicKey));
            cb.setOpaque(false);
            cb.setForeground(t.text);
            cb.setFont(t.font(13f));
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            cb.putClientProperty("pk", c.publicKey);
            boxes.add(cb);
            body.add(cb);
        }

        JScrollPane sp = new JScrollPane(body,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getViewport().setBackground(t.card);

        final JDialog d = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(this),
                "New group", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(t.card);
        wrap.setBorder(new EmptyBorder(14, 14, 14, 14));
        wrap.add(sp, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(new EmptyBorder(12, 0, 0, 0));
        DKit.HoverButton cancel = k.ghostButton("Cancel");
        cancel.onClick(() -> d.dispose());
        DKit.HoverButton create = k.primaryButton("Create");
        create.onClick(() -> {
            java.util.List<String> members = new java.util.ArrayList<>();
            for (javax.swing.JCheckBox cb : boxes) {
                if (cb.isSelected()) members.add((String) cb.getClientProperty("pk"));
            }
            if (members.isEmpty()) { info("Pick at least one person."); return; }
            String n = nameField.getText().trim();
            final String name = n.isEmpty() ? "Group" : n;
            d.dispose();
            new Thread(() -> {
                try {
                    com.eurobuddha.maxima.core.chat.Group g =
                            node.chat().createGroup(name, members);
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        mLastSig = "";
                        refresh();
                        open(g.id, true);
                    });
                } catch (Exception ex) {
                    javax.swing.SwingUtilities.invokeLater(() ->
                            info("Could not create: " + ex.getMessage()));
                }
            }, "create-group").start();
        });
        buttons.add(Box.createHorizontalGlue());
        buttons.add(cancel);
        buttons.add(Box.createRigidArea(new Dimension(8, 0)));
        buttons.add(create);
        wrap.add(buttons, BorderLayout.SOUTH);

        d.setContentPane(wrap);
        d.setSize(360, Math.min(560, 200 + cs.size() * 30));
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    // ---- media ----

    private void addMediaTo(JPanel b, String body, Color fg) {
        String caption = ChatMedia.caption(body);
        String mime = ChatMedia.mime(body);
        final String ref = ChatMedia.ref(body);
        if (mime != null && mime.startsWith("audio/") && ref != null) {
            addVoiceNoteTo(b, ref, mime, caption, fg);   // voice note: waveform + play
            return;
        }
        JLabel img = new JLabel();
        img.setAlignmentX(Component.LEFT_ALIGNMENT);
        javax.swing.ImageIcon cached = ref == null ? null : mMediaCache.get(ref);
        boolean isImage = mime != null && mime.startsWith("image/");
        if (isImage && ref != null) {
            // Click the thumbnail to open the full-screen viewer (phone parity).
            img.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            img.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent ev) { openImage(ref, mime); }
            });
        }
        if (cached != null) {
            img.setIcon(cached);
        } else if (isImage) {
            img.setText("Loading image…");
            img.setFont(t.font(12f));
            img.setForeground(DKit.alpha(fg, 170));
            fetchThumb(ref, img);
        } else {
            img.setText("📎 " + (mime == null ? "attachment" : mime));
            img.setFont(t.font(12.5f));
            img.setForeground(fg);
        }
        b.add(img);
        if (caption != null && !caption.isEmpty()) {
            JLabel cap = new JLabel("<html><div style='width:320px'>" + escHtml(caption) + "</div></html>");
            cap.setFont(t.font(13f));
            cap.setForeground(fg);
            cap.setAlignmentX(Component.LEFT_ALIGNMENT);
            cap.setBorder(new EmptyBorder(5, 0, 0, 0));
            b.add(cap);
        }
    }

    private void fetchThumb(String ref, JLabel target) {
        if (ref == null || !ref.startsWith("mx1:")) return;
        new Thread(() -> {
            try {
                String json = new String(java.util.Base64.getUrlDecoder()
                        .decode(ref.substring(4)), java.nio.charset.StandardCharsets.UTF_8);
                MediaManifest mf = MediaManifest.decode(json);
                byte[] bytes = node.media().fetch(mf);
                java.awt.image.BufferedImage bi =
                        javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (bi == null) return;
                int max = 260;
                int w = bi.getWidth(), h = bi.getHeight();
                double s = Math.min(1.0, (double) max / Math.max(w, h));
                java.awt.Image scaled = bi.getScaledInstance((int) (w * s), (int) (h * s),
                        java.awt.Image.SCALE_SMOOTH);
                javax.swing.ImageIcon icon = new javax.swing.ImageIcon(scaled);
                mMediaCache.put(ref, icon);
                javax.swing.SwingUtilities.invokeLater(() -> {
                    target.setText(null);
                    target.setIcon(icon);
                    target.revalidate();
                });
            } catch (Exception e) {
                javax.swing.SwingUtilities.invokeLater(() -> target.setText("Image unavailable"));
            }
        }, "media-fetch").start();
    }

    /** Fetch the full-resolution image behind a media ref and open the viewer. */
    private void openImage(String ref, String mime) {
        new Thread(() -> {
            try {
                byte[] bytes;
                if (ref.startsWith("mx1:")) {
                    String json = new String(java.util.Base64.getUrlDecoder()
                            .decode(ref.substring(4)), java.nio.charset.StandardCharsets.UTF_8);
                    bytes = node.media().fetch(MediaManifest.decode(json));
                } else if (ref.startsWith("data:")) {
                    int comma = ref.indexOf(',');
                    bytes = java.util.Base64.getDecoder().decode(ref.substring(comma + 1));
                } else {
                    return;
                }
                java.awt.image.BufferedImage full =
                        javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (full == null) return;
                String ext = mime != null && mime.contains("png") ? "png" : "jpg";
                javax.swing.SwingUtilities.invokeLater(() -> ImageViewer.open(
                        javax.swing.SwingUtilities.getWindowAncestor(this), full, "image." + ext));
            } catch (Exception ex) {
                javax.swing.SwingUtilities.invokeLater(() ->
                        javax.swing.JOptionPane.showMessageDialog(this,
                                "Couldn't open image: " + ex.getMessage()));
            }
        }, "image-open").start();
    }

    /** Host window, so the chat's "Send payment" can use the ONE wallet instance. */
    public void setHost(MaximaWindow h) { mHost = h; }

    /** Attach sheet: Photo, and (1:1 only) Send payment — mirrors the phone. */
    private void showAttachMenu(Component anchor) {
        if (mOpen == null) return;
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem photo = new javax.swing.JMenuItem("Photo…");
        photo.addActionListener(e -> attachFile());
        m.add(photo);
        javax.swing.JMenuItem voice = new javax.swing.JMenuItem("Voice note");
        voice.addActionListener(e -> VoiceNotes.record(
                javax.swing.SwingUtilities.getWindowAncestor(this), t, this::sendVoice));
        m.add(voice);
        if (!mOpenGroup) {
            javax.swing.JMenuItem pay = new javax.swing.JMenuItem("Send payment");
            pay.addActionListener(e -> showPaymentDialog());
            m.add(pay);
        }
        m.show(anchor, 0, -m.getPreferredSize().height);
    }

    /** In-chat MINIMA payment. Signs through the SINGLE wallet the Wallet screen
     *  owns (never a second instance — WOTS key-reuse hazard), then posts the
     *  payment bubble with the resulting txid. */
    private void showPaymentDialog() {
        if (mOpen == null || mOpenGroup) return;
        final Contact contact = node.port().contact(mOpen);
        final String addr = node.chat().walletAddress(mOpen);
        if (contact == null) { info("Payments need a known contact."); return; }
        if (addr == null || addr.isEmpty() || addr.startsWith("Mx00")) {
            info("No wallet address for this contact yet — they need to share one.");
            return;
        }
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new EmptyBorder(14, 14, 14, 14));
        JLabel to = new JLabel("To " + nameFor(mOpen));
        to.setFont(t.semibold(13.5f)); to.setForeground(t.text); to.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(to);
        body.add(Box.createVerticalStrut(10));
        JTextField amt = k.field("amount (MINIMA)");
        amt.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42)); amt.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(amt); body.add(Box.createVerticalStrut(8));
        JTextField memo = k.field("note (optional)");
        memo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42)); memo.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(memo); body.add(Box.createVerticalStrut(12));
        JLabel status = new JLabel(" ");
        status.setFont(t.font(12f)); status.setForeground(t.subtext); status.setAlignmentX(Component.LEFT_ALIGNMENT);
        DKit.HoverButton send = k.primaryButton("Sign & send");
        send.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(send); body.add(Box.createVerticalStrut(6)); body.add(status);

        JDialog d = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(this),
                "Send payment", java.awt.Dialog.ModalityType.MODELESS);
        d.setContentPane(body);
        d.setSize(380, 250);
        d.setLocationRelativeTo(this);

        send.onClick(() -> {
            String a = amt.getText().trim();
            final org.minima.objects.base.MiniNumber amount;
            try { amount = new org.minima.objects.base.MiniNumber(a); }
            catch (Exception ex) { status.setText("Bad amount."); return; }
            if (!amount.isMore(org.minima.objects.base.MiniNumber.ZERO)) {
                status.setText("Amount must be greater than 0."); return;
            }
            WalletPanel wallet = mHost == null ? null : mHost.wallet();
            if (wallet == null) { status.setText("Wallet unavailable."); return; }
            final String memoText = memo.getText().trim();
            send.setButtonEnabled(false);
            status.setText("Preparing…");
            new Thread(() -> wallet.requestPayment(addr, amount, new WalletPanel.PayResult() {
                public void onStatus(String s) {
                    javax.swing.SwingUtilities.invokeLater(() -> status.setText(s));
                }
                public void onTxid(String txid) {
                    // Fund moved — now post the payment bubble carrying the FULL txid.
                    try { node.chat().sendPayment(contact, amount.toString(), "0x00",
                            "MINIMA", memoText, txid); } catch (Exception ignored) { }
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        mThreadSig = ""; refresh(); d.dispose();
                    });
                }
                public void onError(String e) {
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        status.setText(e == null ? "Send failed." : e);
                        send.setButtonEnabled(true);
                    });
                }
            }), "chat-pay").start();
        });
        d.setVisible(true);
    }

    private void info(String msg) {
        javax.swing.JOptionPane.showMessageDialog(this, msg);
    }

    /** Send a recorded voice note (WAV) with its "M:SS|hex" waveform caption. */
    private void sendVoice(byte[] wav, String caption) {
        if (mOpen == null) return;
        final String conv = mOpen;
        final boolean group = mOpenGroup;
        new Thread(() -> {
            try {
                if (group) {
                    node.chat().sendGroupMedia(conv, wav, "audio/wav", caption);
                } else {
                    Contact c = node.port().contact(conv);
                    if (c != null) node.chat().sendMedia(c, wav, "audio/wav", caption);
                }
                javax.swing.SwingUtilities.invokeLater(() -> { mThreadSig = ""; refresh(); });
            } catch (Exception ex) {
                javax.swing.SwingUtilities.invokeLater(() ->
                        javax.swing.JOptionPane.showMessageDialog(this, "Couldn't send: " + ex.getMessage()));
            }
        }, "chat-voice").start();
    }

    /** Blocking fetch of the bytes behind a media ref (mx1: manifest or data:). */
    private byte[] fetchMediaBytes(String ref) throws Exception {
        if (ref.startsWith("mx1:")) {
            String json = new String(java.util.Base64.getUrlDecoder()
                    .decode(ref.substring(4)), java.nio.charset.StandardCharsets.UTF_8);
            return node.media().fetch(MediaManifest.decode(json));
        }
        if (ref.startsWith("data:")) {
            int comma = ref.indexOf(',');
            return java.util.Base64.getDecoder().decode(ref.substring(comma + 1));
        }
        throw new IllegalArgumentException("unknown ref");
    }

    /** A voice-note bubble: play/stop control + waveform + duration (phone shape). */
    private void addVoiceNoteTo(JPanel b, String ref, String mime, String caption, Color fg) {
        String[] parts = Waveform.splitCaption(caption);
        int[] levels = Waveform.decode(parts[1]);
        if (levels == null) levels = new int[Waveform.BAR_COUNT];
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        final Waveform.Bars bars = new Waveform.Bars(levels, fg);
        bars.setPreferredSize(new Dimension(150, 26));
        bars.setMaximumSize(new Dimension(150, 26));

        final PlayButton play = new PlayButton(fg);
        play.onToggle(playing -> {
            if (!playing) { VoiceNotes.stop(); return; }
            new Thread(() -> {
                try {
                    byte[] bytes = fetchMediaBytes(ref);
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        boolean inline = VoiceNotes.play(bytes, mime, bars);
                        if (!inline) play.setPlaying(false);   // handed to the OS app
                    });
                } catch (Exception ex) {
                    javax.swing.SwingUtilities.invokeLater(() -> play.setPlaying(false));
                }
            }, "voice-fetch").start();
        });

        JLabel dur = new JLabel(parts[0].isEmpty() ? "0:00" : parts[0]);
        dur.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11));
        dur.setForeground(DKit.alpha(fg, 190));

        row.add(play);
        row.add(Box.createRigidArea(new Dimension(8, 0)));
        row.add(bars);
        row.add(Box.createRigidArea(new Dimension(8, 0)));
        row.add(dur);
        b.add(row);
    }

    /** Minimal play/stop control (drawn triangle / square, no icon font). */
    private static final class PlayButton extends JComponent {
        private final Color ink;
        private boolean playing;
        private java.util.function.Consumer<Boolean> cb;

        PlayButton(Color ink) {
            this.ink = ink;
            setPreferredSize(new Dimension(30, 30));
            setMaximumSize(new Dimension(30, 30));
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    setPlaying(!playing);
                    if (cb != null) cb.accept(playing);
                }
            });
        }

        void onToggle(java.util.function.Consumer<Boolean> c) { cb = c; }

        void setPlaying(boolean p) { playing = p; repaint(); }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(DKit.alpha(ink, 40));
            g2.fillOval(1, 1, w - 2, h - 2);
            g2.setColor(ink);
            if (playing) {
                int bw = 3, bh = 10, gap = 4, x = (w - (bw * 2 + gap)) / 2, y = (h - bh) / 2;
                g2.fillRect(x, y, bw, bh);
                g2.fillRect(x + bw + gap, y, bw, bh);
            } else {
                java.awt.geom.Path2D.Float tri = new java.awt.geom.Path2D.Float();
                int cx = w / 2 + 1, cy = h / 2;
                tri.moveTo(cx - 4, cy - 6);
                tri.lineTo(cx + 6, cy);
                tri.lineTo(cx - 4, cy + 6);
                tri.closePath();
                g2.fill(tri);
            }
            g2.dispose();
        }
    }

    private void attachFile() {
        if (mOpen == null) return;
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setDialogTitle("Send a photo");
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File f = fc.getSelectedFile();
        new Thread(() -> {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                String mime = java.nio.file.Files.probeContentType(f.toPath());
                if (mime == null) mime = "application/octet-stream";
                // Normalise photos exactly as the phone does: upright per EXIF,
                // downscaled long-edge, re-encoded JPEG — else a phone photo
                // ships sideways and full-size.
                if (mime.startsWith("image/")) {
                    DesktopImagePrep.Result r = DesktopImagePrep.prepare(bytes, mime);
                    bytes = r.bytes;
                    if (r.jpeg) mime = "image/jpeg";
                }
                if (mOpenGroup) node.chat().sendGroupMedia(mOpen, bytes, mime, "");
                else {
                    Contact c = node.port().contact(mOpen);
                    if (c != null) node.chat().sendMedia(c, bytes, mime, "");
                }
                javax.swing.SwingUtilities.invokeLater(() -> { mThreadSig = ""; refresh(); });
            } catch (Exception ex) {
                javax.swing.SwingUtilities.invokeLater(() ->
                        javax.swing.JOptionPane.showMessageDialog(this, "Couldn't send: " + ex.getMessage()));
            }
        }, "chat-attach").start();
    }

    // ---- helpers ----

    private String titleFor(String conversation, boolean group) {
        if (group) {
            com.eurobuddha.maxima.core.chat.Group g = node.chat().group(conversation);
            return g != null ? g.name : "Group";
        }
        return nameFor(conversation);
    }

    private String nameFor(String pubkey) {
        Contact c = node.port().contact(pubkey);
        if (c != null && c.name != null && !c.name.isEmpty() && !"noname".equals(c.name)) return c.name;
        return shortKey(pubkey);
    }

    private String peerSub(String pubkey) {
        Contact c = node.port().contact(pubkey);
        if (c == null) return "unknown contact";
        long ls = c.lastSeen;
        if (ls <= 0) return "offline";
        long d = System.currentTimeMillis() - ls;
        return d < 30 * 60 * 1000L ? "online" : "last seen " + ago(d);
    }

    private String groupSub(String id) {
        com.eurobuddha.maxima.core.chat.Group g = node.chat().group(id);
        return g == null ? "" : g.size() + " members";
    }

    private static String previewBody(String body) {
        if (ChatMedia.isMedia(body)) return "📷 Photo";
        if (ChatPay.isPayment(body)) return "💸 " + ChatPay.amount(body) + " " + ChatPay.tokenName(body);
        return body == null ? "" : body;
    }

    private String stateGlyph(ChatEngine.Entry e) {
        String st = e.state == null ? "" : e.state;
        if (st.contains("fail")) return "✗";                       // phone: ic_error ✗
        if (st.contains("read")) return "✓✓";
        if (st.contains("deliver") || !e.deliveredBy.isEmpty()) return "✓✓";
        if (st.contains("sent")) return "✓";
        return "⋯";                                                // sending (phone: ⋯)
    }

    private static String shortKey(String kk) {
        if (kk == null) return "?";
        return kk.length() > 12 ? kk.substring(0, 10) + "…" : kk;
    }

    private static String time(long ms) {
        return new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(ms));
    }

    private static String shortTime(long ms) {
        if (ms <= 0) return "";
        long d = System.currentTimeMillis() - ms;
        if (d < 24 * 3600_000L) return time(ms);
        return new java.text.SimpleDateFormat("dd MMM").format(new java.util.Date(ms));
    }

    private static String ago(long ms) {
        long m = ms / 60000;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h";
        return (h / 24) + "d";
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    private static String escHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", "<br>");
    }

    private JScrollPane scroll(JComponent c, Color bg) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(c, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(holder,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getViewport().setBackground(bg);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    // ---- small drawn buttons ----

    /** The circular accent send button with a paper-plane glyph. */
    private static final class SendFab extends JComponent {
        private final Theme theme;
        private boolean hover;
        private Runnable action;
        SendFab(Theme t) {
            theme = t;
            setPreferredSize(new Dimension(44, 44));
            setMaximumSize(new Dimension(44, 44));
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                public void mouseClicked(MouseEvent e) { if (action != null) action.run(); }
            });
        }
        SendFab onClick(Runnable r) { action = r; return this; }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = hover ? DKit.mix(theme.accent, theme.mode == Theme.Mode.DARK ? Color.WHITE : Color.BLACK, 0.08f) : theme.accent;
            g2.setColor(c);
            g2.fillOval(2, 2, 40, 40);
            Icons.paint(g2, Icons.SEND, 12, 12, 20, theme.onAccent, 1.6f);
            g2.dispose();
        }
    }

    /** The floating new-chat FAB: an accent disc with a compose (plus) icon. */
    private static final class SendFabLike extends JComponent {
        private final Theme theme;
        private boolean hover;
        private Runnable action;
        SendFabLike(Theme t) {
            theme = t;
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                public void mouseClicked(MouseEvent e) { if (action != null) action.run(); }
            });
        }
        SendFabLike onClick(Runnable r) { action = r; return this; }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = hover ? DKit.mix(theme.accent, theme.mode == Theme.Mode.DARK ? Color.WHITE : Color.BLACK, 0.08f) : theme.accent;
            int d = Math.min(getWidth(), getHeight());
            g2.setColor(DKit.alpha(Color.BLACK, 40));
            g2.fillOval(3, 5, d - 6, d - 6);
            g2.setColor(c);
            g2.fillOval(2, 2, d - 6, d - 6);
            Icons.paint(g2, Icons.PLUS, (d - 20) / 2 - 2, (d - 20) / 2 - 2, 20, theme.onAccent, 2.2f);
            g2.dispose();
        }
    }

    /** Jump-to-latest button: a small card disc with a down chevron. */
    private static final class DownFab extends JComponent {
        private final Theme theme;
        private Runnable action;
        DownFab(Theme t) {
            theme = t;
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) { if (action != null) action.run(); }
            });
        }
        DownFab onClick(Runnable r) { action = r; return this; }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = Math.min(getWidth(), getHeight());
            g2.setColor(DKit.alpha(Color.BLACK, 45));
            g2.fillOval(3, 5, d - 6, d - 6);
            g2.setColor(theme.card);
            g2.fillOval(2, 2, d - 6, d - 6);
            g2.setColor(theme.divider);
            g2.setStroke(new java.awt.BasicStroke(1f));
            g2.drawOval(2, 2, d - 7, d - 7);
            g2.setColor(theme.subtext);
            g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            int cx = d / 2, cy = d / 2;
            g2.drawLine(cx - 5, cy - 2, cx, cy + 3);
            g2.drawLine(cx, cy + 3, cx + 5, cy - 2);
            g2.dispose();
        }
    }
}
