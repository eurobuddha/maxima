package com.eurobuddha.maxima.app.portal.ide.terminal;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tab-driven completion engine for the terminal input.
 *
 * Produces a tightly scoped dropdown for whichever token the caret is in:
 *   "me"                -> commands starting with "me" (then substring matches)
 *   "megammr "          -> ONLY that command's params, required first, unused only
 *   "megammr ac"        -> its params starting with "ac"
 *   "megammr action:"   -> ONLY the allowed values of action
 *   "megammr action:e"  -> values starting with "e"
 *   "... action:export " -> the remaining unused params
 * Empty input suggests nothing (the dropdown appears as you type). Works at any
 * depth in the line, inside ; chains (the segment around the caret is completed
 * independently), and stays quiet while the caret is inside a quoted string.
 *
 * Every item carries a description mined from the node's own help pages so the
 * dropdown reads like an IDE completion list, not a row of bare pills.
 */
public class Suggest {

    public static final int KIND_COMMAND = 0;
    public static final int KIND_PARAM = 1;
    public static final int KIND_VALUE = 2;

    /**
     * One dropdown row. Accepting it splices `insert` into the input in place of the
     * token being completed: newText = text[0..tokenStart) + insert + text[caret..end).
     */
    public static class Item {
        public final String label;
        public final int tokenStart;   // where the completed token begins
        public final String insert;    // replacement for text[tokenStart..caret)
        public final String desc;      // one-line description ("" if none)
        public final boolean required; // required param (shown first + marked)
        public final int kind;         // KIND_COMMAND / KIND_PARAM / KIND_VALUE
        Item(String label, int tokenStart, String insert, String desc, boolean required, int kind) {
            this.label = label;
            this.tokenStart = tokenStart;
            this.insert = insert;
            this.desc = desc == null ? "" : desc;
            this.required = required;
            this.kind = kind;
        }
    }

    public static class Result {
        public final List<Item> items = new ArrayList<>();
        public String paramHint = null;   // full usage line for the active command, or null
    }

    /**
     * Complete the token ending at `caret` (not the end of the string) so completion
     * works even when the user edits mid-line.
     */
    public static Result suggest(Context ctx, String text, int caret) {
        Result r = new Result();
        if (caret < 0 || caret > text.length()) caret = text.length();
        String upto = text.substring(0, caret);

        // Complete only the segment after the last ';' before the caret (multi-command chains).
        int segStart = upto.lastIndexOf(';') + 1;
        String seg = upto.substring(segStart);

        // Inside an unclosed quoted string (script:"..."): free text, no completion.
        int quotes = 0;
        for (int i = 0; i < seg.length(); i++) {
            if (seg.charAt(i) == '"') quotes++;
        }
        if ((quotes & 1) == 1) return r;

        int lead = 0;
        while (lead < seg.length() && seg.charAt(lead) == ' ') lead++;
        String body = seg.substring(lead);

        int firstSpace = body.indexOf(' ');
        if (firstSpace < 0) {
            // Typing the command name itself. Empty input -> quiet dropdown.
            // Exact command fully typed (no trailing space yet): its params come FIRST
            // (appended after the command word) so Tab flows into the chosen command,
            // ahead of longer sibling commands (send -> params before sendpoll…).
            CommandRegistry.Cmd exact = CommandRegistry.get(body);
            if (exact != null) {
                r.paramHint = exact.usage();
                addParamItems(ctx, r, exact, seg, "", caret, " ");
            }
            if (!body.isEmpty()) {
                int tokStart = segStart + lead;
                List<Item> prefix = new ArrayList<>();
                List<Item> contains = new ArrayList<>();
                for (CommandRegistry.Cmd c : CommandRegistry.all()) {
                    if (c.name.equals(body)) continue;
                    String desc = ParamDocs.commandBrief(ctx, c.name);
                    if (c.name.startsWith(body)) {
                        prefix.add(new Item(c.name, tokStart, c.name + " ", desc, false, KIND_COMMAND));
                    } else if (c.name.contains(body)) {
                        contains.add(new Item(c.name, tokStart, c.name + " ", desc, false, KIND_COMMAND));
                    }
                }
                r.items.addAll(prefix);
                r.items.addAll(contains);
            }
            return r;
        }

        String cmdName = body.substring(0, firstSpace);
        CommandRegistry.Cmd cmd = CommandRegistry.get(cmdName);
        if (cmd == null) return r;
        r.paramHint = cmd.usage();

        // The token in progress = after the last space before the caret.
        int lastSpace = seg.lastIndexOf(' ');
        String token = seg.substring(lastSpace + 1);
        int tokStart = segStart + lastSpace + 1;

        int colon = token.indexOf(':');
        if (colon >= 0) {
            // Param value completion — only the legal values of THIS param.
            String key = token.substring(0, colon);
            String partial = token.substring(colon + 1);
            boolean helpCmd = cmd.name.equals("help") && key.equals("command");
            for (String v : valuesFor(cmd, key)) {
                if (!v.startsWith(partial)) continue;
                String desc = helpCmd ? ParamDocs.commandBrief(ctx, v) : "";
                // v.equals(partial) still offered: Tab then just appends the space.
                r.items.add(new Item(v, tokStart, key + ":" + v + " ", desc, false, KIND_VALUE));
            }
        } else {
            // Param name completion — only THIS command's params, unused ones.
            addParamItems(ctx, r, cmd, seg, token, tokStart, "");
        }
        return r;
    }

    /** Adds this command's unused params matching `token`, required params first. */
    private static void addParamItems(Context ctx, Result r, CommandRegistry.Cmd cmd,
                                      String seg, String token, int tokStart, String prefix) {
        Map<String, ParamDocs.Doc> docs = ParamDocs.forCommand(ctx, cmd.name);
        List<Item> required = new ArrayList<>();
        List<Item> optional = new ArrayList<>();
        for (CommandRegistry.Param p : cmd.params) {
            if (!p.name.startsWith(token)) continue;
            if (seg.contains(" " + p.name + ":")) continue;   // already used in this segment
            ParamDocs.Doc d = docs.get(p.name);
            boolean req = d != null && d.required;
            String desc = d != null ? d.desc : "";
            if (desc.isEmpty() && !p.values.isEmpty()) {
                desc = String.join(" | ", p.values);
            }
            Item it = new Item(p.name + ":", tokStart, prefix + p.name + ":", desc, req, KIND_PARAM);
            if (req) required.add(it); else optional.add(it);
        }
        r.items.addAll(required);
        r.items.addAll(optional);
    }

    /** Applies an item against the CURRENT text + caret (re-derived at accept time). */
    public static String apply(String text, int caret, Item item) {
        if (caret < 0 || caret > text.length()) caret = text.length();
        int start = Math.min(item.tokenStart, caret);
        return text.substring(0, start) + item.insert + text.substring(caret);
    }

    /** New caret position after applying `item`. */
    public static int applyCaret(String text, int caret, Item item) {
        if (caret < 0 || caret > text.length()) caret = text.length();
        int start = Math.min(item.tokenStart, caret);
        return start + item.insert.length();
    }

    private static List<String> valuesFor(CommandRegistry.Cmd cmd, String key) {
        // help command:<x> completes with every command name.
        if (cmd.name.equals("help") && key.equals("command")) {
            List<String> names = new ArrayList<>();
            for (CommandRegistry.Cmd c : CommandRegistry.all()) names.add(c.name);
            return names;
        }
        for (CommandRegistry.Param p : cmd.params) {
            if (p.name.equals(key)) return p.values;
        }
        return new ArrayList<>();
    }
}
