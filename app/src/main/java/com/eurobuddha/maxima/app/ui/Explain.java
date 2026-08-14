package com.eurobuddha.maxima.app.ui;

import android.app.Activity;

import androidx.appcompat.app.AlertDialog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What every number and every setting in this app actually means.
 *
 * Maxima exposes real transport internals - hosts, routing keys, a directory,
 * a mailbox, contribution counters - and a number with no explanation is worse
 * than no number: it looks like something is wrong and gives you no way to
 * decide. Every figure on screen has an entry here, reachable by tapping the
 * "?" beside it.
 *
 * Written for someone who has never heard of Maxima. No jargon goes
 * unexplained on its first use.
 */
public final class Explain {

    private static final Map<String, String[]> ENTRIES = new LinkedHashMap<>();

    private static void put(String zKey, String zTitle, String zBody) {
        ENTRIES.put(zKey, new String[]{zTitle, zBody});
    }

    static {
        // ---- the basics ----
        put("status", "Connection status",
                "Maxima is a messaging layer, not a chat app talking to a server. "
                        + "Your phone has no fixed address on the internet, so it "
                        + "borrows one: it connects OUT to a host, and that host "
                        + "passes anything addressed to you back down the same "
                        + "connection.\n\n"
                        + "CONNECTED means at least one host is holding that "
                        + "connection open for you. NOT CONNECTED means nobody can "
                        + "reach you right now - you can still write messages, and "
                        + "they will be sent when a host comes back.");

        put("address", "Your Maxima address",
                "Two parts, joined by @.\n\n"
                        + "Mx… is your identity. It is derived from your seed "
                        + "phrase and it never changes. It is also your public key, "
                        + "so anything sent to it can only be opened by you.\n\n"
                        + "host:port is whichever host is currently relaying for "
                        + "you. That part DOES change - when a host goes away you "
                        + "get a new one. Your contacts are told automatically, "
                        + "which is what the Location Service is for.");

        put("hosts", "Hosts",
                "A host is any Maxima node with a port open to the internet. It "
                        + "relays traffic for phones, which cannot accept incoming "
                        + "connections.\n\n"
                        + "You attach to several at once (multi-homing). Classic "
                        + "Maxima picks exactly one, so when that one operator goes "
                        + "down you vanish. With three, two can fail and you are "
                        + "still reachable.\n\n"
                        + "A host can read WHO you are talking to - the routing key "
                        + "is in the clear - but never WHAT: the message body is "
                        + "sealed end to end.");

        put("mls", "Location Service (MLS)",
                "A directory that answers one question: \"what is the current "
                        + "address for this identity?\"\n\n"
                        + "Because your host part changes, a contact who has not "
                        + "heard from you in a while may hold a stale address. "
                        + "Rather than lose you, they ask an MLS.\n\n"
                        + "\"from host\" means you are using whichever directory "
                        + "your host offers. PINNED means you chose one and will "
                        + "keep using it even if you change hosts.");

        put("contacts", "Contacts",
                "Someone whose identity key you hold and who holds yours. Adding a "
                        + "contact is a handshake, not a lookup: you introduce "
                        + "yourself to their address, and they reply with theirs.\n\n"
                        + "Until they reply the link is one-way and you may not be "
                        + "able to reach them.");

        put("mailbox", "Mailbox (held for others)",
                "How many encrypted messages this phone is holding on behalf of "
                        + "OTHER people who were offline when a message arrived for "
                        + "them.\n\n"
                        + "It is ciphertext addressed to their key - this phone "
                        + "cannot read a word of it. When they reappear, it is "
                        + "handed over and deleted.\n\n"
                        + "Classic Maxima simply drops a message for an offline "
                        + "peer. This is the fix, and it works because thousands of "
                        + "phones can each hold a little.");

        put("outbox", "Outbox",
                "Your own messages that have not been accepted by a host yet. They "
                        + "are retried with a widening gap between attempts. A "
                        + "non-zero outbox that never falls means no host is "
                        + "reachable.");

        put("services", "Services",
                "Requests this phone can answer for other people: directory "
                        + "lookups, mailbox pickup, address gossip, witness "
                        + "receipts.\n\n"
                        + "A phone can answer them despite having no public address "
                        + "because a reply is sent as a NEW outgoing message rather "
                        + "than as a response on the incoming connection. Dialling "
                        + "out always works. That single change is what lets a "
                        + "handset be a provider and not just a consumer.");

        // ---- contribution ----
        put("contribution", "What this phone contributes",
                "Maxima only works if enough participants carry traffic. Phones "
                        + "outnumber servers by orders of magnitude, so the network "
                        + "is far stronger if they help.\n\n"
                        + "Everything here is opt-out, capped, and gated on battery "
                        + "and network conditions. Nothing runs while you are on "
                        + "battery below the threshold, and heavy duties can be "
                        + "restricted to wifi.");

        put("witness", "Witness + directory",
                "Directory: you cache and serve identity → address lookups, so the "
                        + "directory is not a handful of servers everyone depends "
                        + "on.\n\n"
                        + "Witness: you countersign \"I saw message X at time T\". "
                        + "It costs nothing and gives the network evidence of "
                        + "delivery that classic Maxima has no way to express.");

        put("storage", "Storage",
                "Holding small encrypted blobs addressed to a routing key, "
                        + "replicated across several devices so no single one has to "
                        + "stay online. Each blob is capped well under the 256 KB "
                        + "message ceiling.");

        put("direct", "Direct reachability",
                "Normally your phone has no address of its own, so it borrows one "
                        + "from a host. When conditions allow, it can instead get "
                        + "a REAL public address and receive messages directly - "
                        + "one hop shorter, and one fewer relay that sees who you "
                        + "talk to.\n\n"
                        + "It only happens on Wi-Fi, and only when your router will "
                        + "hand out a public port (many will not). Your phone maps "
                        + "a port, then asks a host to dial it back from the "
                        + "outside - because a phone testing its own port is "
                        + "meaningless. Only once that succeeds is the address "
                        + "advertised, so a dead address is never published.\n\n"
                        + "\"unavailable\" is the normal result on mobile data or "
                        + "behind carrier NAT. Nothing is wrong - your phone stays "
                        + "reachable through hosts as always.");

        put("gates", "Battery and network gates",
                "\"unmetered\" means wifi. With Wifi only ✓, the heavier duties "
                        + "(mailbox, storage) pause on mobile data so you are never "
                        + "surprised by a data bill.\n\n"
                        + "Contribution also pauses on low battery while "
                        + "discharging. Charging on wifi is when a phone is most "
                        + "useful to the network and costs you least.");

        put("counters", "Contribution counters",
                "mail held  – encrypted messages you are storing for offline peers\n"
                        + "lookups     – directory questions you have answered\n"
                        + "blobs         – stored blobs you are replicating\n"
                        + "receipts    – witness signatures you have issued\n"
                        + "addresses – address updates you have passed on to mutual "
                        + "contacts, so a peer who changed host is not orphaned");

        // ---- chat ----
        put("ticks", "The ticks",
                "⋯  queued - not handed to a host yet\n"
                        + "✓  sent - a host accepted the bytes. This is ALL classic "
                        + "Maxima can ever tell you.\n"
                        + "✓✓ delivered - the recipient's own device replied to say "
                        + "it has the message. In a group, every current member has.\n"
                        + "✓✓ in blue - read.\n"
                        + "✗  failed - no host would take it. It stays in the outbox.");

        put("readreceipts", "Read receipts",
                "Off by default. Delivery (two grey ticks) is always reported - "
                        + "that is the transport doing its job. Telling someone you "
                        + "have READ their message is information about you, so it "
                        + "is your choice.\n\n"
                        + "Turning it on applies to everyone; there is no per-contact "
                        + "setting.");

        put("groups", "Groups",
                "A group message is sealed separately to every member and sent to "
                        + "each of them. There is NO shared group key.\n\n"
                        + "That costs one send per member, and buys two things: "
                        + "removing someone removes them immediately (there is no "
                        + "key they still hold), and breaking one member's keys does "
                        + "not open the group.\n\n"
                        + "Only an admin can change the roster, and only current "
                        + "members can post - checked on arrival, not just in the UI.");

        // ---- identity / settings ----
        put("name", "Your name",
                "What your contacts see instead of your key. Changing it sends an "
                        + "update to everyone you have - they hold the old one until "
                        + "then.");

        put("seed", "Seed phrase",
                "24 words that ARE your identity. Your Maxima key is derived from "
                        + "them, so restoring the phrase anywhere reproduces exactly "
                        + "the same address and the same contacts can still reach "
                        + "you.\n\n"
                        + "It is also a Minima WALLET seed. Anyone who reads it can "
                        + "spend any funds held against it. Write it down offline; "
                        + "never photograph it.");

        put("relays", "Adding a host",
                "host:port of any public Maxima node - a classic Minima node with "
                        + "Maxima enabled works too, this is fully interoperable.\n\n"
                        + "More hosts means more independent routes to you. Adding "
                        + "or removing one restarts the transport.");

        put("battery", "Battery optimisation",
                "Android will kill a background connection to save power, which for "
                        + "a messaging transport means silently missing messages. "
                        + "Maxima asks to be exempt and holds the connection with a "
                        + "foreground service and an exact alarm.\n\n"
                        + "If you deny the exemption the app still works, but expect "
                        + "gaps - particularly overnight.");

        put("log", "Event log",
                "Everything the transport did, newest first. This is a peer-to-peer "
                        + "network with no support desk: when something does not "
                        + "work, this is what tells you why.");

        put("ipc", "Apps using Maxima",
                "Other apps on this phone can use Maxima as their comms layer "
                        + "instead of shipping their own. They ask for permission, "
                        + "you approve them here, and each one owns its own message "
                        + "type so they cannot read each other's traffic.\n\n"
                        + "Minima Core does not use this yet - it has no Maxima "
                        + "layer of its own, which is the gap this app exists to "
                        + "fill.");
    }

    private Explain() {
    }

    public static String title(String zKey) {
        String[] e = ENTRIES.get(zKey);
        return e == null ? zKey : e[0];
    }

    public static String body(String zKey) {
        String[] e = ENTRIES.get(zKey);
        return e == null ? "No explanation written for \"" + zKey + "\" yet." : e[1];
    }

    public static void show(Activity zActivity, String zKey) {
        new AlertDialog.Builder(zActivity)
                .setTitle(title(zKey))
                .setMessage(body(zKey))
                .setPositiveButton("Got it", null)
                .show();
    }
}
