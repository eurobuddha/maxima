package com.eurobuddha.maxima.app.portal.ide.terminal;

import android.content.Context;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-parameter documentation mined from the node's own help pages (assets/help.json).
 *
 * fullhelp lists each parameter as:
 *     amount: (optional)
 *         The amount to send.
 * A bare "id:" line (no "(optional)" marker) means the parameter is required —
 * but only when at least one sibling param IS marked optional; commands whose
 * help marks nothing are treated as all-optional rather than all-required.
 */
public class ParamDocs {

    public static class Doc {
        public final boolean required;
        public final String desc;      // first description line, or ""
        Doc(boolean required, String desc) {
            this.required = required;
            this.desc = desc;
        }
    }

    private static final Map<String, Map<String, Doc>> CACHE = new HashMap<>();

    public static synchronized Map<String, Doc> forCommand(Context ctx, String command) {
        Map<String, Doc> cached = CACHE.get(command);
        if (cached != null) return cached;

        Map<String, Doc> docs = new LinkedHashMap<>();
        CommandRegistry.Cmd cmd = CommandRegistry.get(command);
        String full = HelpStore.full(ctx, command);
        if (cmd != null && full != null) {
            String[] lines = full.split("\n");
            // First pass: find each "param:" header line and whether any is marked (optional).
            Map<String, Integer> headerAt = new LinkedHashMap<>();
            Map<String, Boolean> optionalMark = new HashMap<>();
            boolean anyMarked = false;
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].trim();
                int colon = t.indexOf(':');
                if (colon <= 0) continue;
                String name = t.substring(0, colon).trim();
                if (!isParamOf(cmd, name) || headerAt.containsKey(name)) continue;
                String after = t.substring(colon + 1).trim();
                // A header line is "name:" possibly followed only by an (optional)/(required) tag.
                boolean optional = after.contains("(optional)");
                if (!after.isEmpty() && !after.startsWith("(")) continue;  // prose line, not a header
                headerAt.put(name, i);
                optionalMark.put(name, optional);
                if (optional) anyMarked = true;
            }
            // Second pass: first non-empty line under each header = the description.
            for (Map.Entry<String, Integer> e : headerAt.entrySet()) {
                String desc = "";
                for (int i = e.getValue() + 1; i < lines.length; i++) {
                    String t = lines[i].trim();
                    if (t.isEmpty()) continue;
                    // Ran into the next param header or the examples section.
                    if (t.startsWith("Examples")) break;
                    int c = t.indexOf(':');
                    if (c > 0 && isParamOf(cmd, t.substring(0, c).trim())) break;
                    desc = t;
                    break;
                }
                boolean required = anyMarked && !optionalMark.get(e.getKey());
                docs.put(e.getKey(), new Doc(required, desc));
            }
        }
        CACHE.put(command, docs);
        return docs;
    }

    /** One-line description of a command itself (help.json "help", minus the param spam). */
    public static String commandBrief(Context ctx, String command) {
        return stripParams(HelpStore.brief(ctx, command));
    }

    /**
     * "[id:] (file:) - Export a transaction" -> "Export a transaction". The summary's
     * parameter list may end in ] (required) or ) (optional); only a leading run of
     * such groups is stripped, never a " - " inside the prose.
     */
    public static String stripParams(String brief) {
        if (brief == null) return "";
        String b = brief.trim();
        int i = b.indexOf(" - ");
        if (i < 0) return b.startsWith("- ") ? b.substring(2).trim() : b;
        String head = b.substring(0, i).trim();
        if (head.isEmpty()) return b.substring(i + 3).trim();
        boolean onlyGroups = (head.startsWith("[") || head.startsWith("("))
                && (head.endsWith("]") || head.endsWith(")"));
        return onlyGroups ? b.substring(i + 3).trim() : b;
    }

    private static boolean isParamOf(CommandRegistry.Cmd cmd, String name) {
        for (CommandRegistry.Param p : cmd.params) {
            if (p.name.equals(name)) return true;
        }
        return false;
    }
}
