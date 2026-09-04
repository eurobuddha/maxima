package com.eurobuddha.maxima.node;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.minima.system.params.ParamConfigurer;

/**
 * Minima's own startup flags, passed through to the embedded node — with a SHORT exclusion list.
 *
 * <p>{@code parlons-node.jar} never runs {@code Minima.main()} (it calls {@code System.exit}, reads
 * stdin and installs a JVM-global handler), so Minima's {@code -flags} would otherwise be lost. This
 * feeds them to the very same parser {@code Minima.main()} uses ({@link ParamConfigurer}), from
 * {@code -Dparlons.node.args="…"} or env {@code PARLONS_NODE_ARGS}, so an operator can say
 * {@code -port 9111 -host 1.2.3.4 -archive} exactly as on a stock node. Minima owns everything it
 * parses; the node's own {@code -Dparlons.node.*} properties are applied AFTERWARDS and only when set
 * explicitly, so they win on conflict and nothing else.
 *
 * <p>Excluded — refused at boot with the reason and the node knob to use instead:
 * <ul>
 *   <li>{@code -rpc -rpcenable -rpcpassword -rpccrlf}: the stock RPC binds every interface with full
 *       admin and no bind option (it was internet-reachable on a firewall-less box); the node's
 *       loopback-bound AdminRpc ({@code -Dparlons.node.rpc=true}) replaces it;</li>
 *   <li>{@code -seed -anyseed -dbpassword}: secrets in argv are readable by every process ({@code ps});
 *       use the deploy script's {@code --seed-from} / {@code --passphrase-file};</li>
 *   <li>{@code -clean -genesis -test -solo -testchainlength}: wipe the data dir or leave mainnet;</li>
 *   <li>{@code -daemon -noshutdownhook -jnlp -help}: stdin / exit / shutdown-hook behaviour the merged
 *       JVM owns itself.</li>
 * </ul>
 * The MDS flags are accepted but do nothing — this fork has no MDS package — and say so at boot.
 * {@code -conf <file>} is expanded here (each {@code key=value} line becomes a flag) so its contents
 * go through the same filter.
 */
final class MinimaFlags {

    private MinimaFlags() {
    }

    /** key (no dash, lower-case) → why it is refused + what to use. */
    private static final Map<String, String> EXCLUDED = new LinkedHashMap<>();
    static {
        String rpc = "the stock RPC binds every interface with full admin — use -Dparlons.node.rpc=true (loopback AdminRpc on p2p+4)";
        for (String k : new String[]{"rpc", "rpcenable", "rpcpassword", "rpccrlf"}) EXCLUDED.put(k, rpc);
        String secret = "a secret in argv is readable by every process — use the deploy script's --seed-from / --passphrase-file";
        for (String k : new String[]{"seed", "anyseed", "dbpassword"}) EXCLUDED.put(k, secret);
        String chain = "wipes the data dir or leaves mainnet — never on a fleet node";
        for (String k : new String[]{"clean", "genesis", "test", "solo", "testchainlength"}) EXCLUDED.put(k, chain);
        String proc = "stdin / exit / shutdown-hook behaviour that the merged Parlons Node JVM owns itself";
        for (String k : new String[]{"daemon", "noshutdownhook", "jnlp", "help"}) EXCLUDED.put(k, proc);
    }

    private static final List<String> MDS = Arrays.asList("mdsenable", "mdspassword", "mdsinit",
            "mdswrite", "nosslmds", "publicmds", "publicmdsuid", "nodefaultminidapps");

    /** The flags actually handed to Minima (for the boot log). */
    static String applied = "";
    private static List<String> appliedList = new ArrayList<>();

    /** Was this Minima key (e.g. "data", "port", "megammr") among the applied flags? */
    static boolean has(String zKey) {
        for (String a : appliedList) {
            if (a.startsWith("-") && a.replaceFirst("^-+", "").equalsIgnoreCase(zKey)) return true;
        }
        return false;
    }

    /**
     * Read {@code -Dparlons.node.args} / {@code PARLONS_NODE_ARGS}, expand {@code -conf}, refuse the
     * excluded keys, and run the survivors through {@link ParamConfigurer}. Returns true if any flag
     * was applied. Refusal prints the reason and exits 2 — before the node touches its data dir.
     */
    static boolean apply() {
        String raw = System.getProperty("parlons.node.args", "");
        if (raw.trim().isEmpty()) {
            String env = System.getenv("PARLONS_NODE_ARGS");
            raw = env == null ? "" : env;
        }
        List<String> args = expandConf(tokenise(raw));
        if (args.isEmpty()) {
            return false;
        }
        List<String> refused = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        for (String a : args) {
            if (!a.startsWith("-")) continue;
            String key = a.replaceFirst("^-+", "").toLowerCase(Locale.ROOT);
            if (EXCLUDED.containsKey(key)) {
                refused.add("  " + a + "\n      " + EXCLUDED.get(key));
            } else if (MDS.contains(key)) {
                dead.add(a);
            }
        }
        if (!refused.isEmpty()) {
            System.err.println("[parlons-node] REFUSING to start: these Minima flags are not allowed on a Parlons Node");
            for (String r : refused) System.err.println(r);
            System.err.println("[parlons-node] every other Minima flag passes straight through (-Dparlons.node.args / PARLONS_NODE_ARGS)");
            System.exit(2);
        }
        if (!dead.isEmpty()) {
            System.out.println("[parlons-node] WARNING: " + String.join(" ", dead)
                    + " — this node has no MDS (the fork strips it); the flag is accepted but does nothing");
        }
        try {
            new ParamConfigurer().usingProgramArgs(args.toArray(new String[0])).configure();
        } catch (ParamConfigurer.UnknownArgumentException ex) {
            System.err.println("[parlons-node] REFUSING to start: " + ex.getMessage()
                    + " (not a Minima flag — run stock minima.jar -help for the list)");
            System.exit(2);
        }
        applied = String.join(" ", args);
        appliedList = args;
        return true;
    }

    /** Shell-style split: whitespace separates, single/double quotes group, backslash escapes. */
    static List<String> tokenise(String zRaw) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean have = false;
        for (int i = 0; i < zRaw.length(); i++) {
            char c = zRaw.charAt(i);
            if (quote != 0) {
                if (c == quote) { quote = 0; }
                else if (c == '\\' && i + 1 < zRaw.length() && quote == '"') { cur.append(zRaw.charAt(++i)); }
                else { cur.append(c); }
                have = true;
            } else if (c == '\'' || c == '"') {
                quote = c; have = true;
            } else if (c == '\\' && i + 1 < zRaw.length()) {
                cur.append(zRaw.charAt(++i)); have = true;
            } else if (Character.isWhitespace(c)) {
                if (have) { out.add(cur.toString()); cur.setLength(0); have = false; }
            } else {
                cur.append(c); have = true;
            }
        }
        if (have) out.add(cur.toString());
        return out;
    }

    /** {@code -conf FILE} → the file's {@code key=value} lines as {@code -key value} flags, in place,
     *  so they are filtered like everything else (Minima's own conf-file step is never used). */
    private static List<String> expandConf(List<String> zArgs) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < zArgs.size(); i++) {
            String a = zArgs.get(i);
            if (a.equalsIgnoreCase("-conf") && i + 1 < zArgs.size()) {
                File f = new File(zArgs.get(++i));
                try {
                    for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#")) continue;
                        int eq = t.indexOf('=');
                        String k = eq < 0 ? t : t.substring(0, eq).trim();
                        String v = eq < 0 ? "" : t.substring(eq + 1).trim();
                        out.add("-" + k.replaceFirst("^-+", ""));
                        if (!v.isEmpty()) out.add(v);
                    }
                } catch (Exception e) {
                    System.err.println("[parlons-node] REFUSING to start: cannot read -conf " + f + ": " + e);
                    System.exit(2);
                }
            } else {
                out.add(a);
            }
        }
        return out;
    }
}
