package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.ChatMessage;
import com.eurobuddha.maxima.core.contacts.Contact;

import java.awt.Component;
import java.awt.Window;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Desktop call handling — Tier 0 (signaling only, no media). Calls carry their
 * audio/video over the phone's native WebRTC, which the JVM doesn't have; but
 * the SIGNALING is ordinary chat control messages, so the desktop can still be a
 * well-behaved participant: when a phone rings it, it shows an incoming-call
 * banner and replies 'bye' the moment you decline (or after a short timeout), so
 * the caller gets an instant honest "unavailable" instead of ringing out for
 * ~45 seconds. Placing a call with real media stays a phone-app feature.
 */
final class DesktopCalls implements ChatEngine.CallSignals {

    private final DesktopNode node;
    private final Window owner;
    private final Theme t;
    private final Set<String> handled = ConcurrentHashMap.newKeySet();   // callIds already dealt with

    DesktopCalls(DesktopNode node, Window owner, Theme theme) {
        this.node = node;
        this.owner = owner;
        this.t = theme;
    }

    void attach() {
        node.chat().setCallListener(this);
    }

    @Override
    public void onCallSignal(String fromKey, ChatMessage m) {
        // React only to a fresh OFFER; answer/ice/bye/busy are irrelevant when
        // we never hold a call. Dedup re-delivered offers by callId.
        if (m == null || !"offer".equals(m.state)) {
            return;
        }
        String callId = m.ref;
        if (callId != null && !handled.add(callId)) {
            return;
        }
        Contact c = node.node().contact(fromKey);
        boolean video = "video".equals(m.memo);
        if (c == null) {
            decline(fromKey, callId);   // unknown caller → polite immediate bye
            return;
        }
        SwingUtilities.invokeLater(() -> showIncoming(c, callId, video));
    }

    private void showIncoming(Contact c, String callId, boolean video) {
        String name = c.name != null && !c.name.isEmpty() && !"noname".equals(c.name)
                ? c.name : "Someone";
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new EmptyBorder(16, 18, 16, 18));

        JLabel title = new JLabel(name + (video ? " — incoming video call" : " — incoming call"));
        title.setFont(t.semibold(15f));
        title.setForeground(t.text);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel hint = new JLabel("<html><div style='width:300px'>Answer on your phone — "
                + "the desktop app doesn't carry call audio/video yet.</div></html>");
        hint.setFont(t.font(12.5f));
        hint.setForeground(t.subtext);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(title);
        body.add(Box.createVerticalStrut(8));
        body.add(hint);
        body.add(Box.createVerticalStrut(14));

        DKit k = new DKit(t);
        DKit.HoverButton decline = k.primaryButton("Decline");
        decline.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(decline);

        JDialog d = new JDialog(owner, "Incoming call", JDialog.ModalityType.MODELESS);
        d.setContentPane(body);
        d.setSize(360, 190);
        d.setLocationRelativeTo(owner);

        // Auto-decline if ignored, so the caller isn't left ringing.
        final javax.swing.Timer timeout = new javax.swing.Timer(30_000, e -> {
            decline(c.publicKey, callId);
            d.dispose();
        });
        timeout.setRepeats(false);
        timeout.start();

        decline.onClick(() -> {
            timeout.stop();
            decline(c.publicKey, callId);
            d.dispose();
        });
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) {
                timeout.stop();
                decline(c.publicKey, callId);
            }
        });
        d.setVisible(true);
    }

    /** Send a 'bye' so the caller stops ringing immediately. Fire-and-forget. */
    private void decline(String toKey, String callId) {
        try {
            Contact c = node.node().contact(toKey);
            if (c != null && callId != null) {
                node.chat().sendCallSignal(c, ChatMessage.call(callId, "bye", ""));
            }
        } catch (Exception ignored) {
        }
    }

    /** The explanation shown when a desktop user taps a header call button. */
    static void explainPlacingCalls(Component parent) {
        javax.swing.JOptionPane.showMessageDialog(parent,
                "Audio and video calls run on the Parlons phone app.\n\n"
                        + "This desktop app receives calls and declines them politely, "
                        + "so a caller is never left ringing.",
                "Calls", javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }
}
