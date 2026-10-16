package com.eurobuddha.maxima.app.portal.ide.ide;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.List;
import static org.junit.Assert.*;

public class EditorMessagesTest {
    @Test public void actualLintDiagnosticsTranslateWithoutChangingSeverityOrSource() throws Exception {
        String source = "LET bad_name = 1\nLET hash = NUMBER(SHA3(0xFF))\nRETURN @BLKNUM GT 1";
        List<LintEngine.Lint> lints = LintEngine.lint(source);
        assertEquals(3, lints.size());
        assertTrue(lints.get(0).error); assertFalse(lints.get(1).error); assertTrue(lints.get(2).error);
        int[] resources = {R.string.l10n_script_editor_lint_variable,
                R.string.l10n_script_editor_lint_block_num, R.string.l10n_script_editor_lint_number};
        for (int i = 0; i < lints.size(); i++) {
            String before = lints.get(i).message;
            assertEquals(resources[i], resource(EditorMessages.diagnostic(before)));
            assertEquals(before, lints.get(i).message);
        }
        assertArrayEquals(new Object[]{"bad_name", "badname"}, arguments(EditorMessages.diagnostic(lints.get(0).message)));
    }
    @Test public void actualSizeAndBalanceChecksKeepTheirOriginalLimits() throws Exception {
        for (int length : new int[]{1050,1051,1200,1201}) {
            String source = "RETURN TRUE /*" + "x".repeat(length - 16) + "*/";
            assertEquals(length, source.length());
            List<LintEngine.Lint> lints = LintEngine.lint(source);
            if (length == 1050) { assertTrue(lints.isEmpty()); continue; }
            assertEquals(1, lints.size()); assertEquals(length > 1200, lints.get(0).error);
            CharSequence message = EditorMessages.diagnostic(lints.get(0).message);
            assertEquals(length > 1200 ? R.string.l10n_script_editor_lint_size : R.string.l10n_script_editor_lint_near_size, resource(message));
            assertArrayEquals(new Object[]{String.valueOf(length), "1200"}, arguments(message));
        }
        assertEquals(R.string.l10n_script_editor_lint_unclosed,
                resource(EditorMessages.diagnostic(LintEngine.lint("IF TRUE THEN RETURN TRUE").get(0).message)));
        assertEquals(R.string.l10n_script_editor_lint_extra,
                resource(EditorMessages.diagnostic(LintEngine.lint("RETURN TRUE ENDWHILE").get(0).message)));
        assertEquals(R.string.l10n_script_editor_lint_return,
                resource(EditorMessages.diagnostic(LintEngine.lint("LET value = 1").get(0).message)));
        assertTrue(LintEngine.lint("/* IF @BLKNUM NUMBER(SHA3 */ RETURN [WHILE IF] EQ [WHILE IF]").isEmpty());
    }
    @Test public void validationArgumentsAndUnknownDiagnosticsStayLiteral() throws Exception {
        String raw = "RAW 中文 Русский {field} $1\nSaved";
        for (String field : new String[]{"state","prevstate","globals","signatures"}) {
            assertArrayEquals(new Object[]{field, raw}, arguments(EditorMessages.diagnostic(field + " is not valid JSON: " + raw)));
            assertEquals(R.string.l10n_script_editor_unsafe_json, resource(EditorMessages.diagnostic(field
                    + " values can't contain spaces or ';' (they'd break the node command line). Use hex/number values.")));
        }
        for (String text : new String[]{raw, "prefix 2 unclosed IF block(s) — missing ENDIF.",
                "2 unclosed IF block(s) — missing ENDIF. extra", "username is not valid JSON: test"})
            assertEquals(text, EditorMessages.diagnostic(text));
        assertEquals("", EditorMessages.diagnostic(null));
    }
    private static int resource(Object value) throws Exception {
        assertTrue(value instanceof Language.Message);
        Field f=Language.Message.class.getDeclaredField("resource");f.setAccessible(true);return f.getInt(value);
    }
    private static Object[] arguments(Object value) throws Exception {
        assertTrue(value instanceof Language.Message);
        Field f=Language.Message.class.getDeclaredField("arguments");f.setAccessible(true);return (Object[])f.get(value);
    }
}
