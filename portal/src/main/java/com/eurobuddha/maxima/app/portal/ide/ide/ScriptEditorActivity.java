package com.eurobuddha.maxima.app.portal.ide.ide;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.eurobuddha.maxima.app.portal.ide.NodeApi;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.portal.ide.terminal.OutputFormatter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * KISS VM script editor: live syntax highlighting, char counter with the ~1200-char
 * danger line, static lints for the proven silent killers, offline testing against the
 * node's own VM (runscript with state/prevstate/globals/signatures + full trace), and
 * deploy via newscript.
 */
public class ScriptEditorActivity extends AppCompatActivity {

    ScriptDB mDB;
    NodeApi mNode;
    long mScriptId;

    EditText mName;
    EditText mEditor;
    TextView mStatusLine;
    LinearLayout mRunConfig;
    EditText mState, mPrevState, mGlobals, mSignatures;
    TextView mResults;
    android.widget.HorizontalScrollView mChipScroller;
    LinearLayout mChipRow;

    List<LintEngine.Lint> mLints;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_script_editor);
        View editorRoot = findViewById(R.id.editor_root);
        ViewCompat.setOnApplyWindowInsetsListener(editorRoot, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // Pad for the keyboard too — edge-to-edge otherwise hides the editor's
            // action row and the field being typed into.
            v.setPadding(systemBars.left, systemBars.top, systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(editorRoot);

        Toolbar tb = findViewById(R.id.editor_toolbar);
        tb.setTitle("Script Editor");
        setSupportActionBar(tb);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        tb.setNavigationOnClickListener(v -> finish());
        tb.getMenu().add(0, 1, 0, "KISS VM reference");
        tb.getMenu().add(0, 2, 1, "Minima docs");
        tb.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) {
                showKissReference();
                return true;
            }
            if (mi.getItemId() == 2) {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://docs.minima.global")));
                return true;
            }
            return false;
        });

        mDB = new ScriptDB(this);
        mNode = new NodeApi(this);

        mScriptId = getIntent().getLongExtra("script_id", -1);
        ScriptDB.Script script = mDB.get(mScriptId);
        if (script == null) {
            Toast.makeText(this, "Script not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mName = findViewById(R.id.editor_name);
        mEditor = findViewById(R.id.editor_source);
        mStatusLine = findViewById(R.id.editor_status);
        mRunConfig = findViewById(R.id.editor_runconfig);
        mState = findViewById(R.id.editor_state);
        mPrevState = findViewById(R.id.editor_prevstate);
        mGlobals = findViewById(R.id.editor_globals);
        mSignatures = findViewById(R.id.editor_signatures);
        mResults = findViewById(R.id.editor_results);

        mName.setText(script.name);
        mEditor.setText(script.source);
        mState.setText(script.testState);
        mPrevState.setText(script.testPrevState);
        mGlobals.setText(script.testGlobals);
        mSignatures.setText(script.testSignatures);
        if (!script.testState.isEmpty() || !script.testPrevState.isEmpty()
                || !script.testGlobals.isEmpty() || !script.testSignatures.isEmpty()) {
            mRunConfig.setVisibility(View.VISIBLE);
            ((Button) findViewById(R.id.editor_toggle_config)).setText("▼ Test inputs");
        }
        mChipScroller = findViewById(R.id.editor_chipscroller);
        mChipRow = findViewById(R.id.editor_chiprow);

        mEditor.addTextChangedListener(new KissHighlighter(mEditor));
        mEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                updateStatus();
                updateEditorChips();
            }
        });

        mStatusLine.setOnClickListener(v -> showLints());

        Button toggle = findViewById(R.id.editor_toggle_config);
        toggle.setOnClickListener(v -> {
            boolean show = mRunConfig.getVisibility() != View.VISIBLE;
            mRunConfig.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "▼ Test inputs" : "▶ Test inputs");
        });

        findViewById(R.id.editor_check).setOnClickListener(v -> check());
        findViewById(R.id.editor_run).setOnClickListener(v -> run());
        findViewById(R.id.editor_deploy).setOnClickListener(v -> deployDialog(script));

        if (!script.address.isEmpty()) {
            mResults.setText(OutputFormatter.colored("⛓ Deployed at: " + script.address,
                    OutputFormatter.COL_STRING));
        }

        updateStatus();
        // Highlight the initial content.
        new KissHighlighter(mEditor).highlight(mEditor.getText());
    }

    private void save() {
        // mName is null when onCreate bailed out (missing script) — onPause still fires.
        if (mScriptId < 0 || mName == null) return;
        String name = mName.getText().toString().trim();
        if (name.isEmpty()) name = "untitled";
        mDB.update(mScriptId, name, mEditor.getText().toString());
        mDB.saveTestInputs(mScriptId,
                mState.getText().toString().trim(),
                mPrevState.getText().toString().trim(),
                mGlobals.getText().toString().trim(),
                mSignatures.getText().toString().trim());
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mNode != null) mNode.onDestroy();
        if (mDB != null) mDB.close();
    }

    /** Bundled KISS VM language reference (assets/kissvm.txt). */
    private void showKissReference() {
        String ref;
        try {
            java.io.InputStream in = getAssets().open("kissvm.txt");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            ref = out.toString("UTF-8");
        } catch (java.io.IOException e) {
            ref = "Reference unavailable";
        }
        TextView tv = new TextView(this);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setTextColor(OutputFormatter.COL_PLAIN);
        tv.setTextIsSelectable(true);
        tv.setText(ref);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.setPadding(pad, pad / 2, pad, pad / 2);
        scroller.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("KISS VM reference")
                .setView(scroller)
                .setPositiveButton("Close", null)
                .show();
    }

    // ---------------- editor autocomplete ----------------

    /** Suggest KISS keywords / functions / @globals for the word being typed. */
    private void updateEditorChips() {
        mChipRow.removeAllViews();
        String text = mEditor.getText().toString();
        int cur = mEditor.getSelectionStart();
        if (cur < 0 || cur > text.length()) cur = text.length();
        int start = cur;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '@') start--;
            else break;
        }
        String word = text.substring(start, cur);
        if (word.isEmpty()) {
            mChipScroller.setVisibility(View.GONE);
            return;
        }
        String uw = word.toUpperCase();

        java.util.List<String[]> matches = new java.util.ArrayList<>();   // {label, insert}
        if (uw.startsWith("@")) {
            for (String g : sorted(KissVm.GLOBALS)) {
                if (g.startsWith(uw) && !g.equals(uw)) matches.add(new String[]{g, g + " "});
            }
        } else {
            for (String k : sorted(KissVm.KEYWORDS)) {
                if (k.startsWith(uw) && !k.equals(uw)) matches.add(new String[]{k, k + " "});
            }
            for (String f : sorted(KissVm.FUNCTIONS)) {
                if (f.startsWith(uw) && !f.equals(uw)) matches.add(new String[]{f, f + "("});
            }
        }

        int count = 0;
        for (String[] m : matches) {
            if (count++ >= 20) break;
            TextView chip = new TextView(this);
            chip.setText(m[0]);
            chip.setTypeface(android.graphics.Typeface.MONOSPACE);
            chip.setTextSize(13);
            chip.setTextColor(OutputFormatter.COL_CMD);
            chip.setBackgroundResource(R.drawable.chip_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 12, 0);
            chip.setLayoutParams(lp);
            int pad = (int) (getResources().getDisplayMetrics().density * 6);
            chip.setPadding(pad * 2, pad, pad * 2, pad);
            final String insert = m[1];
            chip.setOnClickListener(v -> {
                // Re-derive the word boundaries from the CURRENT caret — the caret may
                // have moved (a tap doesn't fire the TextWatcher) since chips were built.
                String t = mEditor.getText().toString();
                int c = mEditor.getSelectionStart();
                if (c < 0 || c > t.length()) c = t.length();
                int s = c;
                while (s > 0) {
                    char ch = t.charAt(s - 1);
                    if (Character.isLetterOrDigit(ch) || ch == '@') s--;
                    else break;
                }
                mEditor.getText().replace(s, c, insert);
                mEditor.setSelection(s + insert.length());
            });
            mChipRow.addView(chip);
        }
        mChipScroller.setVisibility(mChipRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        mChipScroller.scrollTo(0, 0);
    }

    private static java.util.List<String> sorted(java.util.Set<String> set) {
        java.util.List<String> list = new java.util.ArrayList<>(set);
        java.util.Collections.sort(list);
        return list;
    }

    // ---------------- status / lints ----------------

    private void updateStatus() {
        String src = mEditor.getText().toString();
        mLints = LintEngine.lint(src);
        int errors = 0, warns = 0;
        for (LintEngine.Lint l : mLints) {
            if (l.error) errors++; else warns++;
        }
        int len = src.trim().length();
        String sizecol = len > KissVm.MAX_SAFE_CHARS ? "OVER LIMIT" : (len + "/" + KissVm.MAX_SAFE_CHARS);
        String line = sizecol + " chars";
        if (errors > 0) line += " · ✖ " + errors;
        if (warns > 0) line += " · ⚠ " + warns;
        if (errors == 0 && warns == 0) line += " · ✓ no lint issues";
        mStatusLine.setText(line);
        mStatusLine.setTextColor(errors > 0 || len > KissVm.MAX_SAFE_CHARS
                ? OutputFormatter.COL_ERROR
                : (warns > 0 ? OutputFormatter.COL_BOOL : OutputFormatter.COL_DIM));
    }

    private void showLints() {
        if (mLints == null || mLints.isEmpty()) {
            Toast.makeText(this, "No lint issues", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (LintEngine.Lint l : mLints) sb.append(l.toString()).append("\n\n");
        new AlertDialog.Builder(this)
                .setTitle("Lint results")
                .setMessage(sb.toString().trim())
                .setPositiveButton("OK", null)
                .show();
    }

    // ---------------- check / run / deploy ----------------

    /** The script flattened for a command line (the node cleans it properly). */
    private String flatScript() {
        return mEditor.getText().toString()
                .replaceAll("(?s)/\\*.*?\\*/", " ")   // strip comments (keeps on-chain size honest)
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean validScript(String flat) {
        if (flat.isEmpty()) {
            Toast.makeText(this, "Script is empty", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (flat.contains("\"")) {
            Toast.makeText(this, "Double quotes are not supported in scripts sent over the "
                    + "command line — use [ ] string blocks", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private String jsonParam(String key, EditText field, boolean array) {
        String raw = field.getText().toString().trim();
        if (raw.isEmpty()) return "";
        String minified;
        try {
            minified = array ? new JSONArray(raw).toString() : new JSONObject(raw).toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException(key + " is not valid JSON: " + e.getMessage());
        }
        // Minifying only strips whitespace BETWEEN tokens — spaces (and ';') INSIDE a JSON
        // string value survive and would break the node's space-delimited tokenizer, or
        // worse, a ';' would start a second command. These fields hold hex/number values,
        // so reject any that can't ride the command line safely.
        if (minified.contains(" ") || minified.contains(";")) {
            throw new IllegalArgumentException(key + " values can't contain spaces or ';' "
                    + "(they'd break the node command line). Use hex/number values.");
        }
        return " " + key + ":" + minified;
    }

    private void check() {
        String flat = flatScript();
        if (!validScript(flat)) return;
        save();
        setResults("… checking with the node VM", OutputFormatter.COL_DIM);
        mNode.cmd("runscript script:\"" + flat + "\"", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { showCheckResult(json, false); }
            @Override public void onError(String message) { showNodeError(message); }
        });
    }

    private void run() {
        String flat = flatScript();
        if (!validScript(flat)) return;
        save();
        String cmd;
        try {
            cmd = "runscript script:\"" + flat + "\""
                    + jsonParam("state", mState, false)
                    + jsonParam("prevstate", mPrevState, false)
                    + jsonParam("globals", mGlobals, false)
                    + jsonParam("signatures", mSignatures, true);
        } catch (IllegalArgumentException e) {
            setResults("✖ " + e.getMessage(), OutputFormatter.COL_ERROR);
            return;
        }
        setResults("… running in the node VM", OutputFormatter.COL_DIM);
        mNode.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { showCheckResult(json, true); }
            @Override public void onError(String message) { showNodeError(message); }
        });
    }

    private void showCheckResult(JSONObject json, boolean fullRun) {
        if (!json.optBoolean("status", false)) {
            setResults("✖ " + json.optString("error", "runscript failed"), OutputFormatter.COL_ERROR);
            return;
        }
        JSONObject resp = json.optJSONObject("response");
        if (resp == null) {
            setResults("✖ empty response", OutputFormatter.COL_ERROR);
            return;
        }
        boolean parseok = resp.optBoolean("parseok", false);
        JSONObject clean = resp.optJSONObject("clean");
        String address = clean != null ? clean.optString("address", "?") : "?";
        String cleanScript = clean != null ? clean.optString("script", "") : "";

        StringBuilder sb = new StringBuilder();
        sb.append(parseok ? "✓ parse OK" : "✖ PARSE FAILED — this script would make coins unspendable");
        sb.append("\nAddress: ").append(address);
        sb.append("\nClean (").append(cleanScript.length()).append(" chars): ").append(cleanScript);

        if (fullRun) {
            boolean success = resp.optBoolean("success", false);
            boolean monotonic = resp.optBoolean("monotonic", false);
            sb.append("\n\nResult: ").append(success ? "✓ RETURN TRUE (spendable)" : "✖ RETURN FALSE");
            sb.append("\nMonotonic: ").append(monotonic);
            Object vars = resp.opt("variables");
            if (vars != null) sb.append("\n\nVariables:\n").append(vars);
            Object trace = resp.opt("trace");
            if (trace != null) sb.append("\n\nTrace:\n").append(trace.toString().replace("\\n", "\n"));
            setResults(sb.toString(), success && parseok
                    ? OutputFormatter.COL_STRING : OutputFormatter.COL_ERROR);
        } else {
            setResults(sb.toString(), parseok ? OutputFormatter.COL_STRING : OutputFormatter.COL_ERROR);
        }
    }

    private void deployDialog(ScriptDB.Script script) {
        String flat = flatScript();
        if (!validScript(flat)) return;
        save();

        View v = getLayoutInflater().inflate(R.layout.dialog_deploy, null);
        CheckBox trackall = v.findViewById(R.id.deploy_trackall);
        new AlertDialog.Builder(this)
                .setTitle("Deploy script to node")
                .setMessage("newscript registers this script's address with your node so it "
                        + "tracks coins locked by it. This does not spend anything.\n\n"
                        + "NOTE: everyone who deploys the same script text gets the SAME "
                        + "address — with trackall on, your wallet shows every coin anyone "
                        + "ever locks there (this is how test/burn debris ends up in "
                        + "wallets). Leave trackall off for shared or template scripts.")
                .setView(v)
                .setPositiveButton("Deploy", (d, w) -> deploy(flat, trackall.isChecked()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deploy(String flat, boolean trackall) {
        setResults("… deploying via newscript", OutputFormatter.COL_DIM);
        mNode.cmd("newscript trackall:" + trackall + " script:\"" + flat + "\"", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    setResults("✖ " + json.optString("error", "newscript failed"), OutputFormatter.COL_ERROR);
                    return;
                }
                JSONObject resp = json.optJSONObject("response");
                String address = resp != null ? resp.optString("address", "") : "";
                if (!address.isEmpty()) {
                    mDB.setAddress(mScriptId, address);
                }
                setResults("✓ Deployed — node now tracks coins at:\n" + address
                        + "\n\nSend funds to it with:\nsend address:" + address + " amount:...",
                        OutputFormatter.COL_STRING);
            }

            @Override
            public void onError(String message) { showNodeError(message); }
        });
    }

    private void showNodeError(String message) {
        if (NodeApi.ERR_NOT_ENABLED.equals(message)) {
            setResults("✖ This app is not enabled yet.\nOpen Minima Core → Apps and enable Terminal IDE.",
                    OutputFormatter.COL_ERROR);
        } else {
            setResults("✖ " + message, OutputFormatter.COL_ERROR);
        }
    }

    private void setResults(String text, int color) {
        mResults.setText(OutputFormatter.colored(text, color));
    }
}
