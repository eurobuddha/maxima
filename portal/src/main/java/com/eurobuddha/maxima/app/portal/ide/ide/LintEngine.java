package com.eurobuddha.maxima.app.portal.ide.ide;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static lints for the proven KISS VM silent killers (from hard-won on-chain lessons):
 * underscores in variable names, >1200 char scripts, @BLKNUM on synced nodes,
 * NUMBER() on full 32-byte hashes, unbalanced blocks.
 */
public class LintEngine {

    public static class Lint {
        public final boolean error;   // error = will fail on-chain; else warning
        public final String message;
        Lint(boolean error, String message) {
            this.error = error;
            this.message = message;
        }
        @Override public String toString() {
            return (error ? "✖ " : "⚠ ") + message;
        }
    }

    private static final Pattern LET_VAR = Pattern.compile("\\bLET\\s+([A-Za-z0-9_]+)\\s*=");

    public static List<Lint> lint(String script) {
        List<Lint> out = new ArrayList<>();
        // Blank out comments AND [ ] string literals before keyword/structure checks, so
        // KISS words inside a string (IF/WHILE/RETURN/@BLKNUM/NUMBER) don't false-positive.
        String noComments = script
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("\\[[^\\]]*\\]", " ");

        // 1. Underscored variable names silently fail at runtime.
        Matcher m = LET_VAR.matcher(noComments);
        while (m.find()) {
            String var = m.group(1);
            if (var.contains("_")) {
                out.add(new Lint(true, "Variable '" + var + "' contains an underscore — "
                        + "underscored names silently fail on-chain. Use flat names (e.g. '"
                        + var.replace("_", "") + "')."));
            }
        }

        // 2. Script size (measured on the source; the on-chain form is the cleaned script).
        int len = script.trim().length();
        if (len > KissVm.MAX_SAFE_CHARS) {
            out.add(new Lint(true, "Script is " + len + " chars — over the ~" + KissVm.MAX_SAFE_CHARS
                    + " char limit scripts are silently rejected on-chain. Minify variable names or use MAST."));
        } else if (len > KissVm.MAX_SAFE_CHARS - 150) {
            out.add(new Lint(false, "Script is " + len + " chars — approaching the ~"
                    + KissVm.MAX_SAFE_CHARS + " char on-chain limit."));
        }

        // 3. @BLKNUM is broken on synced nodes.
        if (noComments.contains("@BLKNUM")) {
            out.add(new Lint(false, "@BLKNUM is unreliable on synced nodes — store a timeout in state "
                    + "and use @COINAGE instead."));
        }

        // 4. NUMBER() straight on a 32-byte hash overflows.
        if (Pattern.compile("NUMBER\\s*\\(\\s*SHA[23]").matcher(noComments).find()) {
            out.add(new Lint(true, "NUMBER(SHA...) overflows on a 32-byte hash — truncate first: "
                    + "NUMBER(SUBSET(0 4 SHA3(...)))."));
        }

        // 5. Unbalanced blocks.
        checkBalance(noComments, "IF", "ENDIF", out);
        checkBalance(noComments, "WHILE", "ENDWHILE", out);

        // 6. Must RETURN something.
        if (!noComments.contains("RETURN")) {
            out.add(new Lint(true, "No RETURN statement — a script must RETURN TRUE to be spendable."));
        }

        return out;
    }

    private static void checkBalance(String text, String open, String close, List<Lint> out) {
        int opens = count(text, "\\b" + open + "\\b") - count(text, "\\b" + close + "\\b");
        // count(IF) also matches ELSEIF? No: \bIF\b does not match inside ELSEIF (no word boundary
        // between ELSE and IF). ENDIF similarly safe.
        if (opens > 0) {
            out.add(new Lint(true, opens + " unclosed " + open + " block(s) — missing " + close + "."));
        } else if (opens < 0) {
            out.add(new Lint(true, (-opens) + " extra " + close + "(s) without a matching " + open + "."));
        }
    }

    private static int count(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
