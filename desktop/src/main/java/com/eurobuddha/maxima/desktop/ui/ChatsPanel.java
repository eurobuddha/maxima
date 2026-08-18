package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.ChatMedia;
import com.eurobuddha.maxima.core.chat.ChatPay;
import com.eurobuddha.maxima.core.chat.Group;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.media.MediaManifest;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Chats tab: a conversation list on the left and the open conversation on the
 * right (message bubbles + a send bar), mirroring the phone's chat list + chat
 * screen in one desktop-native split view.
 */
public final class ChatsPanel extends JPanel implements MaximaWindow.Tab {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;

    private final JPanel mList = new JPanel();
    private final JPanel mThread = new JPanel();
    private final JScrollPane mThreadScroll;
    private final JLabel mThreadTitle;
    private final JLabel mThreadSub;
    private final JTextField mInput;

    private String mOpen;         // conversation key (peer pubkey or group id)
    private boolean mOpenGroup;
    private String mLastSig = "";
    private String mThreadSig = "";
    /** ref → scaled thumbnail, so media isn't re-fetched on every thread rebuild. */
    private final java.util.Map<String, javax.swing.ImageIcon> mMediaCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ChatsPanel(DesktopNode zNode, Theme zTheme) {
        node = zNode;
        t = zTheme;
        k = new DKit(zTheme);
        setLayout(new BorderLayout());
        setBackground(t.bg);

        // ---- left: conversation list ----
        mList.setLayout(new BoxLayout(mList, BoxLayout.Y_AXIS));
        mList.setBackground(t.card);
        JScrollPane listScroll = scroll(mList);
        listScroll.setPreferredSize(new Dimension(320, 10));
        listScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, t.divider));
        add(listScroll, BorderLayout.WEST);

        // ---- right: open conversation ----
        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(t.chatBg);

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
        head.setBackground(t.header);
        head.setBorder(new EmptyBorder(12, 18, 12, 18));
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
        right.add(head, BorderLayout.NORTH);

        mThread.setLayout(new BoxLayout(mThread, BoxLayout.Y_AXIS));
        mThread.setBackground(t.chatBg);
        mThread.setBorder(new EmptyBorder(16, 20, 16, 20));
        mThreadScroll = scroll(mThread);
        mThreadScroll.setBorder(null);
        right.add(mThreadScroll, BorderLayout.CENTER);

        // input bar
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(t.header);
        bar.setBorder(new EmptyBorder(10, 14, 12, 14));
        DKit.RoundPanel pill = new DKit.RoundPanel(t.input, 999);
        pill.setLayout(new BorderLayout());
        pill.setBorder(new EmptyBorder(2, 6, 2, 8));
        DKit.HoverButton attach = k.ghostButton("＋");
        attach.setFont(t.bold(16f));
        attach.onClick(this::attachFile);
        pill.add(attach, BorderLayout.WEST);
        mInput = new JTextField();
        mInput.setFont(t.font(13.5f));
        mInput.setForeground(t.text);
        mInput.setCaretColor(t.text);
        mInput.setOpaque(false);
        mInput.setBorder(new EmptyBorder(8, 0, 8, 0));
        pill.add(mInput, BorderLayout.CENTER);
        DKit.HoverButton send = k.primaryButton("Send");
        send.onClick(this::sendCurrent);
        pill.add(send, BorderLayout.EAST);
        bar.add(pill, BorderLayout.CENTER);
        right.add(bar, BorderLayout.SOUTH);

        // Enter to send.
        mInput.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        mInput.getActionMap().put("send", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { sendCurrent(); }
        });

        add(right, BorderLayout.CENTER);
    }

    public String label() { return "Chats"; }
    public JComponent view() { return this; }

    public void refresh() {
        ChatEngine ce = node.chat();
        List<ChatEngine.Summary> sums = ce.summaries();
        // Signature: only rebuild the list when it actually changes.
        StringBuilder sig = new StringBuilder();
        for (ChatEngine.Summary s : sums) {
            sig.append(s.conversation).append(s.lastTime).append(s.unread).append('|');
        }
        if (!sig.toString().equals(mLastSig)) {
            mLastSig = sig.toString();
            rebuildList(sums);
        }
        if (mOpen != null) {
            // Only rebuild the open thread when its content actually changed —
            // rebuilding every 2s tick would flicker and fight the user's scroll.
            List<ChatEngine.Entry> conv = ce.conversation(mOpen);
            StringBuilder ts = new StringBuilder();
            for (ChatEngine.Entry e : conv) {
                ts.append(e.id).append(e.state).append(e.deliveredBy.size()).append('|');
            }
            if (!ts.toString().equals(mThreadSig)) {
                mThreadSig = ts.toString();
                rebuildThread();
            }
        }
    }

    private void rebuildList(List<ChatEngine.Summary> sums) {
        mList.removeAll();
        if (sums.isEmpty()) {
            JPanel empty = new JPanel();
            empty.setOpaque(false);
            empty.setBorder(new EmptyBorder(40, 20, 20, 20));
            empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
            JLabel l = k.sub("No conversations yet. Add a contact to start.");
            empty.add(l);
            mList.add(empty);
        }
        for (ChatEngine.Summary s : sums) {
            mList.add(row(s));
        }
        mList.add(Box.createVerticalGlue());
        mList.revalidate();
        mList.repaint();
    }

    private JComponent row(ChatEngine.Summary s) {
        boolean group = node.chat().group(s.conversation) != null;
        String title = titleFor(s.conversation, group);
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(true);
        row.setBackground(s.conversation.equals(mOpen) ? t.selected : t.card);
        row.setBorder(new EmptyBorder(12, 16, 12, 16));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        row.add(k.avatar(s.conversation, title, 40), BorderLayout.WEST);

        JPanel mid = new JPanel();
        mid.setOpaque(false);
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(title);
        name.setFont(t.semibold(13.5f));
        name.setForeground(t.text);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        String preview = (s.lastMine ? "You: " : "") + previewBody(s.lastBody);
        JLabel last = new JLabel(clip(preview, 40));
        last.setFont(t.font(12f));
        last.setForeground(t.subtext);
        last.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(name);
        mid.add(last);
        row.add(mid, BorderLayout.CENTER);

        if (s.unread > 0) {
            JLabel badge = new JLabel(String.valueOf(s.unread), SwingConstants.CENTER);
            badge.setOpaque(false);
            badge.setFont(t.bold(10.5f));
            badge.setForeground(Color.WHITE);
            DKit.RoundPanel b = new DKit.RoundPanel(t.error, 999);
            b.setLayout(new BorderLayout());
            b.setBorder(new EmptyBorder(2, 7, 2, 7));
            b.add(badge);
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setOpaque(false);
            wrap.add(b, BorderLayout.CENTER);
            row.add(wrap, BorderLayout.EAST);
        }

        row.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { open(s.conversation, group); }
        });
        return row;
    }

    private void open(String conversation, boolean group) {
        mOpen = conversation;
        mOpenGroup = group;
        node.chat().markRead(conversation);
        mThreadTitle.setText(titleFor(conversation, group));
        mThreadSub.setText(group ? groupSub(conversation) : peerSub(conversation));
        mLastSig = "";       // force list repaint (selection + unread cleared)
        mThreadSig = "";     // force thread rebuild for the newly opened conversation
        refresh();
    }

    private void rebuildThread() {
        List<ChatEngine.Entry> conv = node.chat().conversation(mOpen);
        mThread.removeAll();
        for (ChatEngine.Entry e : conv) {
            mThread.add(bubble(e));
            mThread.add(k.vgap(6));
        }
        mThread.add(Box.createVerticalGlue());
        mThread.revalidate();
        mThread.repaint();
        // Scroll to bottom.
        javax.swing.SwingUtilities.invokeLater(() -> {
            javax.swing.JScrollBar v = mThreadScroll.getVerticalScrollBar();
            v.setValue(v.getMaximum());
        });
    }

    private JComponent bubble(ChatEngine.Entry e) {
        boolean mine = e.mine;
        boolean pay = ChatPay.isPayment(e.body);
        boolean media = ChatMedia.isMedia(e.body);

        JPanel line = new JPanel();
        line.setOpaque(false);
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        Color bg = mine ? t.bubbleOut : t.bubbleIn;
        Color fg = mine ? t.bubbleOutText : t.bubbleInText;
        DKit.RoundPanel b = new DKit.RoundPanel(bg, 16);
        b.setLayout(new BoxLayout(b, BoxLayout.Y_AXIS));
        b.setBorder(new EmptyBorder(8, 13, 7, 13));

        if (mOpenGroup && !mine) {
            JLabel who = new JLabel(nameFor(e.sender));
            who.setFont(t.semibold(11f));
            who.setForeground(DKit.alpha(fg, 200));
            who.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.add(who);
        }

        if (media) {
            addMediaTo(b, e.body, fg);
        } else {
            String bodyText = pay
                    ? "💸  " + ChatPay.amount(e.body) + " " + ChatPay.tokenName(e.body)
                    : e.body;
            JLabel body = new JLabel("<html><div style='width:360px'>"
                    + escHtml(bodyText) + "</div></html>");
            body.setFont(t.font(13.5f));
            body.setForeground(fg);
            body.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.add(body);
        }

        JLabel meta = new JLabel(time(e.time) + (mine ? "  " + stateGlyph(e) : ""));
        meta.setFont(t.font(10f));
        meta.setForeground(DKit.alpha(fg, 160));
        meta.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.add(meta);
        b.setMaximumSize(b.getPreferredSize());

        if (mine) {
            line.add(Box.createHorizontalGlue());
            line.add(b);
        } else {
            line.add(b);
            line.add(Box.createHorizontalGlue());
        }
        return line;
    }

    private void sendCurrent() {
        String txt = mInput.getText().trim();
        if (txt.isEmpty() || mOpen == null) {
            return;
        }
        mInput.setText("");
        new Thread(() -> {
            try {
                if (mOpenGroup) {
                    node.chat().sendGroup(mOpen, txt);
                } else {
                    Contact c = node.node().contact(mOpen);
                    if (c != null) {
                        node.chat().send(c, txt);
                    }
                }
            } catch (Exception ignored) {
            }
            javax.swing.SwingUtilities.invokeLater(this::rebuildThread);
        }, "chat-send").start();
    }

    // ---- media ----

    /** Render an image message: caption text (if any) plus a thumbnail fetched
     *  from the media mesh off-EDT and cached by ref so it isn't re-fetched. */
    private void addMediaTo(DKit.RoundPanel b, String body, Color fg) {
        String caption = ChatMedia.caption(body);
        String mime = ChatMedia.mime(body);
        final String ref = ChatMedia.ref(body);

        JLabel img = new JLabel();
        img.setAlignmentX(Component.LEFT_ALIGNMENT);
        javax.swing.ImageIcon cached = ref == null ? null : mMediaCache.get(ref);
        if (cached != null) {
            img.setIcon(cached);
        } else if (mime != null && mime.startsWith("image/")) {
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
            JLabel cap = new JLabel("<html><div style='width:340px'>" + escHtml(caption) + "</div></html>");
            cap.setFont(t.font(13f));
            cap.setForeground(fg);
            cap.setAlignmentX(Component.LEFT_ALIGNMENT);
            cap.setBorder(new EmptyBorder(5, 0, 0, 0));
            b.add(cap);
        }
    }

    private void fetchThumb(String ref, JLabel target) {
        if (ref == null || !ref.startsWith("mx1:")) {
            return;
        }
        new Thread(() -> {
            try {
                String json = new String(java.util.Base64.getUrlDecoder()
                        .decode(ref.substring(4)), java.nio.charset.StandardCharsets.UTF_8);
                MediaManifest mf = MediaManifest.decode(json);
                byte[] bytes = node.media().fetch(mf);
                java.awt.image.BufferedImage bi =
                        javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (bi == null) {
                    return;
                }
                int max = 260;
                int w = bi.getWidth(), h = bi.getHeight();
                double s = Math.min(1.0, (double) max / Math.max(w, h));
                java.awt.Image scaled = bi.getScaledInstance(
                        (int) (w * s), (int) (h * s), java.awt.Image.SCALE_SMOOTH);
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

    private void attachFile() {
        if (mOpen == null) {
            return;
        }
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setDialogTitle("Send a photo");
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.io.File f = fc.getSelectedFile();
        new Thread(() -> {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                String mime = java.nio.file.Files.probeContentType(f.toPath());
                if (mime == null) {
                    mime = "application/octet-stream";
                }
                if (mOpenGroup) {
                    node.chat().sendGroupMedia(mOpen, bytes, mime, "");
                } else {
                    Contact c = node.node().contact(mOpen);
                    if (c != null) {
                        node.chat().sendMedia(c, bytes, mime, "");
                    }
                }
                javax.swing.SwingUtilities.invokeLater(() -> { mThreadSig = ""; refresh(); });
            } catch (Exception ex) {
                javax.swing.SwingUtilities.invokeLater(() ->
                        javax.swing.JOptionPane.showMessageDialog(this,
                                "Couldn't send: " + ex.getMessage()));
            }
        }, "chat-attach").start();
    }

    // ---- helpers ----

    private String titleFor(String conversation, boolean group) {
        if (group) {
            Group g = node.chat().group(conversation);
            return g != null ? g.name : "Group";
        }
        return nameFor(conversation);
    }

    private String nameFor(String pubkey) {
        Contact c = node.node().contact(pubkey);
        if (c != null && c.name != null && !c.name.isEmpty() && !"noname".equals(c.name)) {
            return c.name;
        }
        return shortKey(pubkey);
    }

    private String peerSub(String pubkey) {
        Contact c = node.node().contact(pubkey);
        if (c == null) {
            return "unknown contact";
        }
        long ls = c.lastSeen;
        if (ls <= 0) {
            return "offline";
        }
        long d = System.currentTimeMillis() - ls;
        return d < 30 * 60 * 1000L ? "online" : "last seen " + ago(d);
    }

    private String groupSub(String id) {
        Group g = node.chat().group(id);
        return g == null ? "" : g.size() + " members";
    }

    private static String previewBody(String body) {
        if (ChatPay.isPayment(body)) {
            return "💸 " + ChatPay.amount(body) + " " + ChatPay.tokenName(body);
        }
        return body == null ? "" : body;
    }

    private String stateGlyph(ChatEngine.Entry e) {
        String st = e.state == null ? "" : e.state;
        if (st.contains("read")) {
            return "✓✓";
        }
        if (st.contains("deliver") || !e.deliveredBy.isEmpty()) {
            return "✓✓";
        }
        if (st.contains("sent")) {
            return "✓";
        }
        return "·";
    }

    private static String shortKey(String k) {
        if (k == null) {
            return "?";
        }
        return k.length() > 12 ? k.substring(0, 10) + "…" : k;
    }

    private static String time(long ms) {
        return new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(ms));
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

    private JScrollPane scroll(JComponent c) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(c, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(holder,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getViewport().setBackground(c.getBackground());
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }
}
