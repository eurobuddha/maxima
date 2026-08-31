package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.server.RelayRuntime;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The {@code parlons} CLI — a thin terminal client for a Parlons Cloud account.
 *
 * A power-user login and the reference {@link ParlonsRemote} front-end (the desktop and Android
 * apps are the same class behind a UI). The device has its OWN identity under {@code --data}; the
 * account it drives is remembered in {@code <data>/cloud.txt}.
 *
 *   java -cp parlons-cloud.jar com.eurobuddha.maxima.cloud.Client [--data DIR] &lt;cmd&gt; [args]
 *
 * Commands: whoami · connect MAX# · pair [CODE] · ping · status · devices · approve KEY ·
 *           revoke KEY · name NAME · contacts · add ADDR · rename KEY NAME · resolve KEY ·
 *           chats · read PEER · markread PEER · send PEER BODY...
 */
public final class Client {

    public static void main(String[] args) throws Exception {
        String data = System.getProperty("user.home") + "/.parlons-client";
        String label = System.getProperty("parlons.label",
                System.getenv().getOrDefault("HOSTNAME", "device"));

        java.util.List<String> rest = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--data".equals(args[i]) && i + 1 < args.length) {
                data = args[++i];
            } else if ("--name".equals(args[i]) && i + 1 < args.length) {
                label = args[++i];
            } else {
                rest.add(args[i]);
            }
        }
        if (rest.isEmpty()) {
            usage();
            return;
        }

        Path dir = Paths.get(data);
        Files.createDirectories(dir);
        RelayRuntime.Seed seed = RelayRuntime.loadOrCreateSeed(dir);   // this DEVICE's identity (0600)
        MaximaIdentity deviceId = MaximaIdentity.fromPhrase(seed.phrase);
        Path cloudFile = dir.resolve("cloud.txt");

        String cmd = rest.get(0).toLowerCase();

        if ("whoami".equals(cmd)) {
            System.out.println("device key : " + new com.eurobuddha.maxima.core.codec.MiniData(
                    deviceId.publicKey()).to0xString());
            System.out.println("data dir   : " + dir);
            System.out.println("account    : " + (Files.exists(cloudFile)
                    ? new String(Files.readAllBytes(cloudFile), StandardCharsets.UTF_8).trim()
                    : "(none — run: parlons connect <account MAX#>)"));
            return;
        }
        if ("connect".equals(cmd)) {
            if (rest.size() < 2) {
                System.err.println("usage: connect <account MAX#>");
                System.exit(2);
            }
            Files.write(cloudFile, rest.get(1).getBytes(StandardCharsets.UTF_8));
            System.out.println("account saved. next: parlons pair <code>");
            return;
        }

        if (!Files.exists(cloudFile)) {
            System.err.println("No account set. Run: parlons connect <account MAX#>");
            System.exit(2);
        }
        String cloud = new String(Files.readAllBytes(cloudFile), StandardCharsets.UTF_8).trim();

        ParlonsRemote r = new ParlonsRemote(deviceId);
        try {
            System.err.println("connecting to your account…");
            r.connect(cloud);
            run(r, cmd, rest, label);
        } finally {
            r.close();
        }
    }

    private static void run(ParlonsRemote r, String cmd, java.util.List<String> rest, String label)
            throws Exception {
        switch (cmd) {
            case "pair": {
                String code = rest.size() > 1 ? rest.get(1) : "";
                JSONObject o = r.pair(label, code);
                String status = String.valueOf(o.getOrDefault("status", o.getOrDefault("error", "?")));
                if ("authorized".equals(status)) {
                    System.out.println("paired ✓ — this device can now drive your account.");
                } else if ("pending".equals(status)) {
                    System.out.println("pairing requested. Approve it from an already-paired device:");
                    System.out.println("    parlons approve " + r.deviceKey());
                } else if ("already".equals(status)) {
                    System.out.println("already paired ✓");
                } else {
                    System.out.println("pair: " + o);
                }
                break;
            }
            case "ping": {
                JSONObject o = r.ping();
                if (isOk(o)) {
                    System.out.println("account : " + o.get("name"));
                    System.out.println("address : " + o.get("permanent"));
                } else {
                    fail(o);
                }
                break;
            }
            case "devices": {
                JSONObject o = r.devices();
                if (!isOk(o)) { fail(o); break; }
                System.out.println("paired devices:");
                for (Object e : arr(o, "authorized")) {
                    JSONObject d = (JSONObject) e;
                    System.out.println("  " + d.get("label") + "  " + d.get("key"));
                }
                JSONArray pend = arr(o, "pending");
                if (!pend.isEmpty()) {
                    System.out.println("pending approval:");
                    for (Object k : pend) {
                        System.out.println("  " + k + "   (approve: parlons approve " + k + ")");
                    }
                }
                break;
            }
            case "approve":
                requireArg(rest, "approve <device key>");
                report(r.approve(rest.get(1)), "approved ✓");
                break;
            case "revoke":
                requireArg(rest, "revoke <device key>");
                report(r.revoke(rest.get(1)), "revoked ✓");
                break;
            case "contacts": {
                JSONObject o = r.contacts();
                if (!isOk(o)) { fail(o); break; }
                for (Object e : arr(o, "contacts")) {
                    JSONObject c = (JSONObject) e;
                    System.out.println("  " + pad(String.valueOf(c.get("name")), 18) + "  " + c.get("key"));
                }
                break;
            }
            case "add":
                requireArg(rest, "add <Mx…@host:port or MAX#>");
                report(r.addContact(rest.get(1)), "introduction sent ✓");
                break;
            case "name":
                requireArg(rest, "name <display name>");
                report(r.setName(rest.get(1)), "name set ✓ (announced to your contacts)");
                break;
            case "rename": {
                if (rest.size() < 3) {
                    System.err.println("usage: rename <contact key> <new name>");
                    System.exit(2);
                }
                report(r.renameContact(rest.get(1), rest.get(2)), "renamed ✓");
                break;
            }
            case "resolve": {
                requireArg(rest, "resolve <contact key>");
                JSONObject o = r.resolveContact(rest.get(1));
                if (!isOk(o)) { fail(o); break; }
                System.out.println("  current address: " + o.get("address"));
                System.out.println(Boolean.TRUE.equals(o.get("updated"))
                        ? "  directory answered — address refreshed ✓"
                        : "  directory answered — address unchanged");
                break;
            }
            case "markread":
                requireArg(rest, "markread <peer key>");
                report(r.markRead(rest.get(1)), "marked read ✓");
                break;
            case "pay": {
                if (rest.size() < 3) {
                    System.err.println("usage: pay <peer key> <amount> [memo…]");
                    System.exit(2);
                }
                StringBuilder memo = new StringBuilder();
                for (int i = 3; i < rest.size(); i++) {
                    if (i > 3) memo.append(' ');
                    memo.append(rest.get(i));
                }
                report(r.pay(rest.get(1), rest.get(2), memo.toString()),
                        "payment building on your node ✓ (watch the chat bubble)");
                break;
            }
            case "status": {
                JSONObject o = r.nodeStatus();
                if (!isOk(o)) { fail(o); break; }
                System.out.println("  name      : " + o.get("name"));
                System.out.println("  version   : " + o.get("version"));
                long up = num(o.get("uptime"));
                System.out.println("  uptime    : " + (up / 3600000) + "h " + ((up % 3600000) / 60000) + "m");
                System.out.println("  relays    : " + o.get("hosts") + " attached, mailbox held: " + o.get("mailboxHeld"));
                System.out.println("  pool relay: " + (Boolean.TRUE.equals(o.get("relayOn"))
                        ? ("on, mesh " + o.get("meshPeers") + " peers") : "off"));
                System.out.println("  devices   : " + o.get("pairedDevices") + " paired");
                System.out.println("  permanent : " + o.get("permanent"));
                break;
            }
            case "chats": {
                JSONObject o = r.summaries();
                if (!isOk(o)) { fail(o); break; }
                for (Object e : arr(o, "summaries")) {
                    JSONObject s = (JSONObject) e;
                    String badge = num(s.get("unread")) > 0 ? " (" + s.get("unread") + " new)" : "";
                    String who = String.valueOf(s.getOrDefault("name", s.get("peer")));
                    System.out.println("  " + pad(who, 18) + "  "
                            + trunc(String.valueOf(s.get("last")), 48) + badge);
                }
                break;
            }
            case "read": {
                requireArg(rest, "read <peer key>");
                JSONObject o = r.conversation(rest.get(1));
                if (!isOk(o)) { fail(o); break; }
                for (Object e : arr(o, "messages")) {
                    JSONObject m = (JSONObject) e;
                    boolean mine = Boolean.TRUE.equals(m.get("mine"));
                    System.out.println((mine ? "  → " : "  ← ") + m.get("body"));
                }
                break;
            }
            case "send": {
                if (rest.size() < 3) {
                    System.err.println("usage: send <peer key> <message…>");
                    System.exit(2);
                }
                StringBuilder body = new StringBuilder();
                for (int i = 2; i < rest.size(); i++) {
                    if (i > 2) body.append(' ');
                    body.append(rest.get(i));
                }
                report(r.send(rest.get(1), body.toString()), "sent ✓");
                break;
            }
            case "wallet": {
                String sub = rest.size() > 1 ? rest.get(1).toLowerCase() : "balance";
                if ("set".equals(sub)) {
                    requireArg(rest.subList(1, rest.size()), "wallet set <Mx address>");
                    report(r.setWatch(rest.get(2)), "watch address set ✓ (funds stay on your device)");
                } else if ("address".equals(sub)) {
                    JSONObject o = r.walletAddress();
                    System.out.println(isOk(o)
                            ? "watch address: " + (String.valueOf(o.get("address")).isEmpty()
                                ? "(none — wallet set <Mx>)" : o.get("address"))
                            : "error: " + o.getOrDefault("error", o));
                } else {   // balance
                    JSONObject o = r.balance();
                    if (!isOk(o)) { fail(o); break; }
                    System.out.println("address : " + o.get("address"));
                    Object bal = o.get("balance");
                    if (bal instanceof JSONObject) {
                        Object resp = ((JSONObject) bal).get("response");
                        if (resp instanceof JSONArray && !((JSONArray) resp).isEmpty()) {
                            for (Object t : (JSONArray) resp) {
                                JSONObject tk = (JSONObject) t;
                                System.out.println("  " + tk.getOrDefault("token", "Minima")
                                        + "  confirmed=" + tk.get("confirmed")
                                        + "  sendable=" + tk.get("sendable"));
                            }
                        } else {
                            System.out.println("  (no coins at this address yet)");
                        }
                    }
                }
                break;
            }
            default:
                usage();
        }
    }

    // ---- helpers ----
    private static boolean isOk(JSONObject o) { return Boolean.TRUE.equals(o.get("ok")); }
    private static void fail(JSONObject o) { System.out.println("error: " + o.getOrDefault("error", o)); }
    private static void report(JSONObject o, String okMsg) {
        System.out.println(isOk(o) ? okMsg : "error: " + o.getOrDefault("error", o));
    }
    private static JSONArray arr(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONArray ? (JSONArray) v : new JSONArray();
    }
    private static long num(Object o) {
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
    private static void requireArg(java.util.List<String> rest, String usage) {
        if (rest.size() < 2) { System.err.println("usage: " + usage); System.exit(2); }
    }
    private static String pad(String s, int n) {
        if (s == null) s = "";
        return s.length() >= n ? s.substring(0, n) : s + "                       ".substring(0, n - s.length());
    }
    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
    private static void usage() {
        System.out.println("parlons — drive your Parlons Cloud account from the terminal");
        System.out.println();
        System.out.println("  whoami                 this device's key + which account it drives");
        System.out.println("  connect <account MAX#> point this device at your cloud account");
        System.out.println("  pair [<code>]          pair this device (bootstrap code, or pending→approve)");
        System.out.println("  ping                   account name + address");
        System.out.println("  devices                list paired / pending devices");
        System.out.println("  approve <key>          approve a pending device");
        System.out.println("  revoke <key>           revoke a device");
        System.out.println("  status                 node status (uptime, relays, mesh, devices)");
        System.out.println("  name <display name>    set the ACCOUNT's name (announced to contacts)");
        System.out.println("  contacts               your contacts");
        System.out.println("  add <addr|MAX#>        add a contact");
        System.out.println("  rename <key> <name>    rename a contact (local override)");
        System.out.println("  resolve <key>          re-resolve a contact's current address NOW");
        System.out.println("  markread <peer key>    mark a conversation read");
        System.out.println("  chats                  conversation summaries");
        System.out.println("  read <peer key>        a conversation");
        System.out.println("  send <peer key> <msg>  send a message");
        System.out.println("  pay <key> <amt> [memo] pay a contact from the ACCOUNT's wallet");
        System.out.println("  wallet address         your account's watch-only address");
        System.out.println("  wallet set <Mx>        set the address to watch (funds stay on your device)");
        System.out.println("  wallet balance         watch-only balance (read-only; the node can't spend)");
    }
}
