package com.eurobuddha.maxima.app.portal.ide.ide;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.eurobuddha.maxima.app.i18n.Language.msg;

/** Display-only adapter for the original lint/validation diagnostics, as in MintMessages. */
final class EditorMessages {
    private EditorMessages() {}
    private static final Pattern[] DIAGNOSTICS = {
        Pattern.compile("Variable '([A-Za-z0-9_]+)' contains an underscore — underscored names silently fail on-chain\\. Use flat names \\(e\\.g\\. '([A-Za-z0-9]*)'\\)\\."),
        Pattern.compile("Script is ([0-9]+) chars — over the ~([0-9]+) char limit scripts are silently rejected on-chain\\. Minify variable names or use MAST\\."),
        Pattern.compile("Script is ([0-9]+) chars — approaching the ~([0-9]+) char on-chain limit\\."),
        Pattern.compile("([0-9]+) unclosed (IF|WHILE) block\\(s\\) — missing (ENDIF|ENDWHILE)\\."),
        Pattern.compile("([0-9]+) extra (ENDIF|ENDWHILE)\\(s\\) without a matching (IF|WHILE)\\."),
        Pattern.compile("(state|prevstate|globals|signatures) is not valid JSON: (.*)", Pattern.DOTALL),
        Pattern.compile("(state|prevstate|globals|signatures) values can't contain spaces or ';' \\(they'd break the node command line\\)\\. Use hex/number values\\.")
    };
    private static final int[] MESSAGES = {
        R.string.l10n_script_editor_lint_variable, R.string.l10n_script_editor_lint_size,
        R.string.l10n_script_editor_lint_near_size, R.string.l10n_script_editor_lint_unclosed,
        R.string.l10n_script_editor_lint_extra, R.string.l10n_script_editor_invalid_json,
        R.string.l10n_script_editor_unsafe_json
    };
    static CharSequence diagnostic(String raw) {
        if (raw == null) return "";
        for (int i = 0; i < DIAGNOSTICS.length; i++) {
            Matcher matcher = DIAGNOSTICS[i].matcher(raw);
            if (!matcher.matches()) continue;
            Object[] arguments = new Object[matcher.groupCount()];
            for (int n = 0; n < arguments.length; n++) arguments[n] = matcher.group(n + 1);
            return msg(MESSAGES[i], arguments);
        }
        return SystemMessages.message(raw);
    }
}
