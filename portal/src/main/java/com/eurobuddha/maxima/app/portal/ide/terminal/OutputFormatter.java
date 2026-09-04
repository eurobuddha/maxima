package com.eurobuddha.maxima.app.portal.ide.terminal;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Colorizes pretty-printed JSON node responses for the terminal. */
public class OutputFormatter {

    public static final int COL_CMD     = Color.parseColor("#82AAFF"); // command echo - blue
    public static final int COL_KEY     = Color.parseColor("#7FDBCA"); // json keys - teal
    public static final int COL_STRING  = Color.parseColor("#C3E88D"); // strings - green
    public static final int COL_NUMBER  = Color.parseColor("#F78C6C"); // numbers - orange
    public static final int COL_BOOL    = Color.parseColor("#FFCB6B"); // true/false/null - yellow
    public static final int COL_ERROR   = Color.parseColor("#FF5370"); // errors - red
    public static final int COL_PLAIN   = Color.parseColor("#D6DEEB"); // default text
    public static final int COL_DIM     = Color.parseColor("#697098"); // punctuation / banner

    private static final Pattern JSON_TOKENS = Pattern.compile(
            "(\"(?:\\\\.|[^\"\\\\])*\")(\\s*:)?"      // 1=string 2=colon => key vs value
          + "|\\b(true|false|null)\\b"                // 3=bool/null
          + "|(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)" // 4=number
    );

    /** Colorize a pretty-printed JSON string. If it's not JSON, returns plain colored text. */
    public static CharSequence json(String pretty, boolean isError) {
        SpannableStringBuilder sb = new SpannableStringBuilder(pretty);
        sb.setSpan(new ForegroundColorSpan(isError ? COL_ERROR : COL_PLAIN),
                0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (isError) return sb;

        Matcher m = JSON_TOKENS.matcher(pretty);
        while (m.find()) {
            int color;
            int start = m.start(), end = m.end();
            if (m.group(1) != null) {
                color = (m.group(2) != null) ? COL_KEY : COL_STRING;
                if (m.group(2) != null) end = m.end(1);
            } else if (m.group(3) != null) {
                color = COL_BOOL;
            } else {
                color = COL_NUMBER;
            }
            sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // Highlight "status": false and "error" lines in red for instant failure scanning.
        highlightLine(sb, pretty, "\"status\": false", COL_ERROR);
        highlightLine(sb, pretty, "\"error\":", COL_ERROR);
        return sb;
    }

    private static void highlightLine(SpannableStringBuilder sb, String text, String needle, int color) {
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            int lineStart = text.lastIndexOf('\n', idx) + 1;
            int lineEnd = text.indexOf('\n', idx);
            if (lineEnd < 0) lineEnd = text.length();
            sb.setSpan(new ForegroundColorSpan(color), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            idx = lineEnd;
        }
    }

    public static CharSequence colored(String text, int color) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        sb.setSpan(new ForegroundColorSpan(color), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }
}
