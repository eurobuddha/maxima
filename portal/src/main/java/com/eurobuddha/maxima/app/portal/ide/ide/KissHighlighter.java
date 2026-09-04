package com.eurobuddha.maxima.app.portal.ide.ide;

import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.widget.EditText;

import com.eurobuddha.maxima.app.portal.ide.terminal.OutputFormatter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live KISS VM syntax highlighting on an EditText via span rewriting.
 * Colors: keywords yellow, functions teal, @globals blue, hex/numbers orange,
 * strings green, comments dim.
 */
public class KissHighlighter implements TextWatcher {

    private static final Pattern TOKENS = Pattern.compile(
            "(/\\*.*?\\*/)"                    // 1 comment
          + "|(\\[[^\\]]*\\])"                 // 2 string block [..]
          + "|(0x[0-9a-fA-F]+)"                // 3 hex
          + "|(-?\\b\\d+(?:\\.\\d+)?\\b)"      // 4 number
          + "|(@[A-Z]+)"                       // 5 global
          + "|([A-Z]{2,})"                     // 6 uppercase word (keyword/function)
          , Pattern.DOTALL);

    private final EditText mEditor;
    private boolean mSelfChange = false;

    public KissHighlighter(EditText editor) {
        mEditor = editor;
    }

    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
    @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

    @Override
    public void afterTextChanged(Editable s) {
        if (mSelfChange) return;
        mSelfChange = true;
        try {
            highlight(s);
        } finally {
            mSelfChange = false;
        }
    }

    public void highlight(Editable s) {
        // Clear our old spans.
        for (ForegroundColorSpan span : s.getSpans(0, s.length(), ForegroundColorSpan.class)) {
            s.removeSpan(span);
        }
        String text = s.toString();
        Matcher m = TOKENS.matcher(text);
        while (m.find()) {
            int color = 0;
            if (m.group(1) != null) color = OutputFormatter.COL_DIM;
            else if (m.group(2) != null) color = OutputFormatter.COL_STRING;
            else if (m.group(3) != null) color = OutputFormatter.COL_NUMBER;
            else if (m.group(4) != null) color = OutputFormatter.COL_NUMBER;
            else if (m.group(5) != null) color = OutputFormatter.COL_CMD;
            else if (m.group(6) != null) {
                String word = m.group(6);
                if (KissVm.KEYWORDS.contains(word)) color = OutputFormatter.COL_BOOL;
                else if (KissVm.FUNCTIONS.contains(word)) color = OutputFormatter.COL_KEY;
                else continue;
            }
            s.setSpan(new ForegroundColorSpan(color), m.start(), m.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
