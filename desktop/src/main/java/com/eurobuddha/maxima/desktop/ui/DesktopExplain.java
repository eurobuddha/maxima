package com.eurobuddha.maxima.desktop.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
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
        put("status", "Connection status",
                "Maxima is a messaging layer, not a chat app talking to a server. "
                        + "This device may have no fixed address on the internet, so it "
                        + "borrows one: it connects OUT to a host, and that host passes "
                        + "anything addressed to you back down the same connection.\n\n"
                        + "CONNECTED means at least one host is holding that connection "
                        + "open for you. NOT CONNECTED means nobody can reach you right "
                        + "now — you can still write messages, and they will be sent when "
                        + "a host comes back.");

        put("address", "Your Maxima address",
                "Two parts, joined by @.\n\n"
                        + "Mx… is your identity. It is derived from your seed phrase and "
                        + "it never changes. It is also your public key, so anything sent "
                        + "to it can only be opened by you.\n\n"
                        + "host:port is whichever host is currently relaying for you. That "
                        + "part DOES change — when a host goes away you get a new one. Your "
                        + "contacts are told automatically, which is what the Location "
                        + "Service is for.");

        put("hosts", "Hosts",
                "A host is any Maxima node with a port open to the internet. It relays "
                        + "traffic for devices that cannot accept incoming connections.\n\n"
                        + "You attach to several at once (multi-homing). Classic Maxima "
                        + "picks exactly one, so when that one operator goes down you "
                        + "vanish. With three, two can fail and you are still reachable.\n\n"
                        + "A host can read WHO you are talking to — the routing key is in "
                        + "the clear — but never WHAT: the message body is sealed end to "
                        + "end.");

        put("mls", "Location Service (MLS)",
                "A directory that answers one question: \"what is the current address "
                        + "for this identity?\"\n\n"
                        + "Because your host part changes, a contact who has not heard from "
                        + "you in a while may hold a stale address. Rather than lose you, "
                        + "they ask an MLS.\n\n"
                        + "\"from host\" means you are using whichever directory your host "
                        + "offers. PINNED means you chose one and will keep using it even "
                        + "if you change hosts.");

        put("contacts", "Contacts",
                "Someone whose identity key you hold and who holds yours. Adding a "
                        + "contact is a handshake, not a lookup: you introduce yourself to "
                        + "their address, and they reply with theirs.\n\n"
                        + "Until they reply the link is one-way and you may not be able to "
                        + "reach them.");

        put("mailbox", "Mailbox (held for others)",
                "How many encrypted messages this device is holding on behalf of OTHER "
                        + "people who were offline when a message arrived for them.\n\n"
                        + "It is ciphertext addressed to their key — this device cannot "
                        + "read a word of it. When they reappear, it is handed over and "
                        + "deleted.\n\n"
                        + "Classic Maxima simply drops a message for an offline peer. This "
                        + "is the fix, and it works because many devices can each hold a "
                        + "little.");

        put("outbox", "Outbox",
                "Your own messages that have not been accepted by a host yet. They are "
                        + "retried with a widening gap between attempts. A non-zero outbox "
                        + "that never falls means no host is reachable.");

        put("services", "Services",
                "Requests this device can answer for other people: directory lookups, "
                        + "mailbox pickup, address gossip, witness receipts.\n\n"
                        + "A device with no public address can still answer them because a "
                        + "reply is sent as a NEW outgoing message rather than as a "
                        + "response on the incoming connection. Dialling out always works. "
                        + "That single change is what lets a client be a provider and not "
                        + "just a consumer.");

        put("contribution", "What this device contributes",
                "Maxima only works if enough participants carry traffic. Clients "
                        + "outnumber servers by orders of magnitude, so the network is far "
                        + "stronger if they help.\n\n"
                        + "Everything here is opt-out and capped.");

        put("witness", "Witness + directory",
                "Directory: you cache and serve identity → address lookups, so the "
                        + "directory is not a handful of servers everyone depends on.\n\n"
                        + "Witness: you countersign \"I saw message X at time T\". It costs "
                        + "nothing and gives the network evidence of delivery that classic "
                        + "Maxima has no way to express.");

        put("storage", "Storage",
                "Holding small encrypted blobs addressed to a routing key, replicated "
                        + "across several devices so no single one has to stay online. Each "
                        + "blob is capped well under the 256 KB message ceiling.");

        put("direct", "Direct reachability",
                "Normally this device borrows an address from a host. When conditions "
                        + "allow, it can instead get a REAL public address and receive "
                        + "messages directly — one hop shorter, and one fewer relay that "
                        + "sees who you talk to.\n\n"
                        + "It only works when your router will hand out a public port (many "
                        + "will not). The device maps a port, then asks a host to dial it "
                        + "back from the outside — because a device testing its own port is "
                        + "meaningless. Only once that succeeds is the address advertised, "
                        + "so a dead address is never published.\n\n"
                        + "\"unavailable\" is the normal result behind a NAT that won't "
                        + "forward. Nothing is wrong — you stay reachable through hosts as "
                        + "always. If your router needs a manual rule, add one under "
                        + "'Port forwarding'.");

        put("counters", "Contribution counters",
                "mail held  – encrypted messages you are storing for offline peers\n"
                        + "lookups     – directory questions you have answered\n"
                        + "blobs         – stored blobs you are replicating\n"
                        + "receipts    – witness signatures you have issued\n"
                        + "addresses – address updates you have passed on to mutual "
                        + "contacts, so a peer who changed host is not orphaned");

        put("ticks", "The ticks",
                "⋯  queued — not handed to a host yet\n"
                        + "✓  sent — a host accepted the bytes. This is ALL classic Maxima "
                        + "can ever tell you.\n"
                        + "✓✓ delivered — the recipient's own device replied to say it has "
                        + "the message. In a group, every current member has.\n"
                        + "✓✓ in blue — read.\n"
                        + "✗  failed — no host would take it. It stays in the outbox.");

        put("readreceipts", "Read receipts",
                "Off by default. Delivery (two grey ticks) is always reported — that is "
                        + "the transport doing its job. Telling someone you have READ their "
                        + "message is information about you, so it is your choice.\n\n"
                        + "Turning it on applies to everyone; there is no per-contact "
                        + "setting.");

        put("groups", "Groups",
                "A group message is sealed separately to every member and sent to each "
                        + "of them. There is NO shared group key.\n\n"
                        + "That costs one send per member, and buys two things: removing "
                        + "someone removes them immediately (there is no key they still "
                        + "hold), and breaking one member's keys does not open the group.\n\n"
                        + "Only an admin can change the roster, and only current members "
                        + "can post — checked on arrival, not just in the UI.");

        put("name", "Your name",
                "What your contacts see instead of your key. Changing it sends an "
                        + "update to everyone you have — they hold the old one until then.");

        put("appearance", "Appearance",
                "Light, dark, or follow the system. \"System\" tracks your desktop's "
                        + "light/dark setting automatically; Light and Dark force one "
                        + "regardless. It is a look, nothing about it leaves this device.");

        put("seed", "Seed phrase",
                "24 words that ARE your identity. Your Maxima key is derived from them, "
                        + "so restoring the phrase anywhere reproduces exactly the same "
                        + "address and the same contacts can still reach you.\n\n"
                        + "It is also a Minima WALLET seed. Anyone who reads it can spend "
                        + "any funds held against it. Write it down offline; never "
                        + "photograph it.");

        put("relays", "Adding a host",
                "host:port of any public Maxima node — a classic Minima node with "
                        + "Maxima enabled works too, this is fully interoperable.\n\n"
                        + "More hosts means more independent routes to you. Adding or "
                        + "removing one restarts the transport.");

        put("log", "Event log",
                "Everything the transport did, newest first. This is a peer-to-peer "
                        + "network with no support desk: when something does not work, "
                        + "this is what tells you why.");

        put("keyuses", "Key uses",
                "Your wallet signs with one-time Winternitz keys: each signing key can "
                        + "be used a fixed number of times before it is unsafe to reuse.\n\n"
                        + "The number shown is how many signatures remain on the current "
                        + "key before the wallet must move to the next one. Sending a "
                        + "payment spends one. It is a safety budget, not a balance — "
                        + "running low never loses funds, the wallet just advances keys.");

        put("nodekind", "Node type",
                "Tell your contacts what this device is.\n\n"
                        + "phone — an ordinary handset. core — an always-on Minima Core / "
                        + "full node running maxima. It only changes the label others see; "
                        + "it does not change how the transport works.");
    }

    private DesktopExplain() { }

    public static String title(String zKey) {
        String[] e = ENTRIES.get(zKey);
        return e == null ? zKey : e[0];
    }

    public static String body(String zKey) {
        String[] e = ENTRIES.get(zKey);
        return e == null ? "No explanation written for \"" + zKey + "\" yet." : e[1];
    }

    /** Pop the explanation for {@code zKey} as a modal dialog over {@code zOver}. */
    public static void show(Component zOver, String zKey) {
        JTextArea body = new JTextArea(body(zKey));
        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(0, 0, 0, 0));
        body.setFont(new JLabel().getFont().deriveFont(13f));
        JScrollPane sp = new JScrollPane(body,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.setPreferredSize(new Dimension(420, 260));

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        wrap.add(sp, BorderLayout.CENTER);

        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(zOver),
                title(zKey), java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        d.setContentPane(wrap);
        d.setSize(460, 340);
        d.setLocationRelativeTo(zOver);
        d.setVisible(true);
    }
}
