package com.eurobuddha.maxima.app.portal;

import com.eurobuddha.maxima.app.i18n.LanguageDialog;

import android.content.Intent;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.eurobuddha.maxima.app.LockGate;
import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.cloud.ParlonsRemote;
import org.minima.utils.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** The phone APK's restore interaction, applied to the paired server instead of a local service. */
public final class CloudIdentityImportActivity extends AppCompatActivity {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LockGate lock = new LockGate(this);
    private LinearLayout body;
    private TextView status, submit, resume, cancel;
    private EditText phrase, password;
    private CheckBox custom;
    private Uri backup;
    private String id = "", oldAccount = "", target = "";
    private boolean busy, foreground;
    private androidx.appcompat.app.AlertDialog previewDialog;
    private final ActivityResultLauncher<String[]> pick = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) { backup = uri; Language.setText(status, Language.msg(R.string.l10n_identity_import_selected)); controls(); }
            });

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(Language.text(this, R.string.l10n_identity_import_title));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(com.eurobuddha.maxima.app.R.color.ux_bg));
        LinearLayout bar = new LinearLayout(this); bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getColor(com.eurobuddha.maxima.app.R.color.ux_header));
        int barPad = PortalUi.dp(this, 16); bar.setPadding(barPad, barPad, barPad, barPad);
        TextView back = new TextView(this); back.setText("‹"); back.setTextSize(26);
        back.setTextColor(getColor(com.eurobuddha.maxima.app.R.color.ux_on_header));
        back.setPadding(0, 0, PortalUi.dp(this, 18), 0); Language.description(back, Language.msg(R.string.l10n_common_back));
        back.setOnClickListener(v -> finish()); bar.addView(back);
        TextView heading = new TextView(this); Language.setText(heading, Language.msg(R.string.l10n_identity_import_heading)); heading.setTextSize(20);
        heading.setTextColor(getColor(com.eurobuddha.maxima.app.R.color.ux_on_header)); bar.addView(heading);
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = PortalUi.dp(this, 20); body.setPadding(pad, pad, pad, pad);
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        body.addView(PortalUi.title(this, Language.msg(R.string.l10n_identity_import_server_title)));
        body.addView(PortalUi.label(this, Language.msg(R.string.l10n_identity_import_intro)));
        phrase = input(Language.msg(R.string.l10n_identity_import_phrase), false);
        phrase.setMinLines(3); body.addView(phrase);
        custom = new CheckBox(this); Language.setText(custom, Language.msg(R.string.l10n_identity_import_custom)); body.addView(custom);
        body.addView(PortalUi.label(this, Language.msg(R.string.l10n_identity_import_custom_help)));
        TextView choose = PortalUi.ghost(this, Language.msg(R.string.l10n_identity_import_choose));
        choose.setOnClickListener(v -> { if (!busy && id.isEmpty()) pick.launch(new String[]{"*/*"}); });
        body.addView(choose);
        password = input(Language.msg(R.string.l10n_identity_import_password), true); body.addView(password);
        TextView usePhrase = PortalUi.ghost(this, Language.msg(R.string.l10n_identity_import_use_phrase));
        usePhrase.setOnClickListener(v -> { if (!busy && id.isEmpty()) { backup = null; Language.setText(status, Language.msg(R.string.l10n_identity_import_phrase_selected)); controls(); } });
        body.addView(usePhrase);
        status = PortalUi.value(this, ""); body.addView(status);
        submit = PortalUi.button(this, Language.msg(R.string.l10n_identity_import_review)); submit.setOnClickListener(v -> begin()); body.addView(submit);
        resume = PortalUi.ghost(this, Language.msg(R.string.l10n_identity_import_resume)); resume.setOnClickListener(v -> resume()); body.addView(resume);
        cancel = PortalUi.ghost(this, Language.msg(R.string.l10n_identity_import_cancel)); cancel.setOnClickListener(v -> cancel()); body.addView(cancel);
        id = pref("import_id"); oldAccount = pref("import_old"); target = pref("import_target");
        controls();
        if (!id.isEmpty()) Language.setText(status, Language.msg(R.string.l10n_identity_import_pending));
        lock.onCreate(); secure();
    }

    private EditText input(CharSequence hint, boolean secret) {
        EditText e = new EditText(this); Language.hint(e, hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        e.setSaveEnabled(false); e.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }
    private void secure() { getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); }
    @Override protected void onResume() { super.onResume(); foreground = true; lock.onResume(); secure(); }
    @Override protected void onStop() {
        foreground = false;
        if (previewDialog != null) { previewDialog.dismiss(); previewDialog = null; }
        lock.onStop(); super.onStop();
    }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
    private String pref(String key) { return CloudSession.prefs(this).getString(key, ""); }
    private boolean committing() { return CloudSession.prefs(this).getBoolean("import_committing", false); }
    private void controls() {
        submit.setEnabled(!busy && id.isEmpty()); resume.setEnabled(!busy && !id.isEmpty());
        cancel.setEnabled(!busy && !id.isEmpty() && !committing());
        phrase.setEnabled(!busy && id.isEmpty() && backup == null);
        custom.setEnabled(!busy && id.isEmpty() && backup == null);
        password.setEnabled(!busy && id.isEmpty() && backup != null);
        resume.setVisibility(id.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        cancel.setVisibility(id.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
    }
    private void show(CharSequence text) { runOnUiThread(() -> { if (!isDestroyed()) Language.setText(status, text); }); }
    private void run(Work work) {
        if (busy || com.eurobuddha.maxima.app.AppLock.mustUnlock(this)) return;
        busy = true; controls();
        io.execute(() -> {
            try { work.run(); }
            catch (Exception e) { show(Language.msg(R.string.l10n_identity_import_failed, safeMessage(e))); }
            finally { runOnUiThread(() -> { busy = false; if (!isDestroyed()) controls(); }); }
        });
    }
    // Never echo crypto/parser errors that might embed a phrase, password or backup content.
    private String safeMessage(Exception e) {
        return e instanceof UserMessage ? SystemMessages.text(this, e.getMessage()) : Language.text(this, R.string.l10n_identity_import_connection);
    }
    private interface Work { void run() throws Exception; }
    private static final class UserMessage extends Exception { UserMessage(String s) { super(s); } }

    private ParlonsRemote original() throws Exception {
        if (!oldAccount.equals(CloudSession.account(this))) throw new UserMessage("The selected account has changed. Return to the original account first.");
        CompletableFuture<ParlonsRemote> result = new CompletableFuture<>();
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) { result.complete(r); }
            public void err(String ignored) { result.completeExceptionally(new IOException("Connection failed")); }
        });
        return result.get(180, TimeUnit.SECONDS);
    }
    private JSONObject request(String action) {
        JSONObject p = new JSONObject(); p.put("id", id); p.put("action", action); return p;
    }
    private static JSONObject send(ParlonsRemote r, JSONObject p) throws Exception {
        JSONObject reply = r.identityImport(p);
        if (!Boolean.TRUE.equals(reply.get("ok"))) throw new UserMessage("The server rejected the import request.");
        return reply;
    }
    private void begin() {
        final String text = phrase.getText().toString(); // custom: preserve raw UTF-8, as SeedStore does
        final boolean any = custom.isChecked();
        final String pass = password.getText().toString(); final Uri uri = backup;
        if (uri == null && text.trim().isEmpty()) { show(Language.msg(R.string.l10n_identity_import_enter)); return; }
        if (uri != null && pass.isEmpty()) { show(Language.msg(R.string.l10n_backup_enter_password)); return; }
        oldAccount = CloudSession.account(this);
        run(() -> {
            show(Language.msg(R.string.l10n_identity_import_support));
            ParlonsRemote remote = original();
            if (!Boolean.TRUE.equals(remote.nodeStatus().get("identityImport")))
                throw new UserMessage("Update Parlons Node to 0.2.117 or later to import from this app.");
            byte[] bytes = uri == null ? new byte[0] : readBackup(uri);
            try {
                id = UUID.randomUUID().toString().replace("-", ""); target = "";
                if (!CloudSession.prefs(this).edit().putString("import_id", id).putString("import_old", oldAccount)
                        .remove("import_target").putBoolean("import_committing", false).commit())
                    throw new UserMessage("Could not save import recovery details.");
                JSONObject first = request("begin"); first.put("total", bytes.length); send(remote, first);
                for (int offset = 0; offset < bytes.length; offset += 60_000) {
                    JSONObject chunk = request("upload"); chunk.put("offset", offset);
                    chunk.put("chunk", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, Math.min(offset + 60_000, bytes.length))));
                    send(remote, chunk); show(Language.msg(R.string.l10n_identity_import_uploading, Math.min(100, (offset + 60_000L) * 100 / bytes.length)));
                }
                JSONObject prepare = request("prepare");
                if (uri == null) { prepare.put("phrase", text); prepare.put("anyPhrase", any); }
                else prepare.put("password", pass);
                show(Language.msg(R.string.l10n_identity_import_checking));
                JSONObject result = send(remote, prepare);
                runOnUiThread(() -> { phrase.setText(""); password.setText(""); });
                follow(remote, result);
            } finally { Arrays.fill(bytes, (byte) 0); }
        });
    }
    private byte[] readBackup(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("No file");
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_BYTES) throw new UserMessage("Backups must be no larger than 16 MB.");
                out.write(buffer, 0, n);
            }
            if (out.size() == 0) throw new UserMessage("The backup file is empty.");
            return out.toByteArray();
        }
    }
    private void resume() {
        run(() -> {
            // A committed server may no longer answer its old identity. Try the new account
            // first; only a successful owner-authenticated response completes adoption.
            if (committing() && !target.isEmpty()) {
                try { reconnectImported(); return; } catch (Exception ignored) { }
            }
            ParlonsRemote remote = original(); follow(remote, send(remote, request("status")));
        });
    }
    private void follow(ParlonsRemote remote, JSONObject result) throws Exception {
        long deadline = System.currentTimeMillis() + 180_000;
        while ("preparing".equals(result.get("state"))) {
            if (System.currentTimeMillis() > deadline) throw new UserMessage("Preparation is still running. Tap Resume shortly.");
            Thread.sleep(1000); result = send(remote, request("status"));
        }
        String state = String.valueOf(result.get("state"));
        if ("ready".equals(state)) {
            target = String.valueOf(result.get("address"));
            if (!target.startsWith("MAX#")) throw new IOException("Invalid target");
            if (!CloudSession.prefs(this).edit().putString("import_target", target).putBoolean("import_committing", false).commit()) throw new IOException("Save failed");
            final String name = String.valueOf(result.getOrDefault("name", ""));
            runOnUiThread(() -> preview(name));
        } else if ("committed".equals(state)) {
            reconnectImported();
        } else if ("failed".equals(state)) {
            show(Language.msg(R.string.l10n_identity_import_failed_cancel, SystemMessages.text(this, String.valueOf(result.getOrDefault("error", "Could not prepare the identity.")))));
        } else {
            show(Language.msg(R.string.l10n_identity_import_not_prepared));
        }
    }
    private void preview(String name) {
        if (isDestroyed() || isFinishing() || !foreground || com.eurobuddha.maxima.app.AppLock.mustUnlock(this)) {
            show(Language.msg(R.string.l10n_identity_import_prepared)); return;
        }
        LinearLayout box = PortalUi.card(this);
        box.addView(PortalUi.value(this, Language.join(name.isEmpty() ? Language.msg(R.string.l10n_identity_import_imported) : name, "\n\n", target)));
        box.addView(PortalUi.label(this, Language.msg(R.string.l10n_identity_import_confirm_help)));
        CheckBox stopped = new CheckBox(this); Language.setText(stopped, Language.msg(R.string.l10n_identity_import_stopped)); box.addView(stopped);
        ScrollView review = new ScrollView(this); review.addView(box);
        androidx.appcompat.app.AlertDialog dialog = new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_identity_import_confirm)).setView(review).setNegativeButton(Language.msg(R.string.l10n_identity_import_later), null)
                .setPositiveButton(Language.msg(R.string.l10n_identity_import_restart), null).create();
        previewDialog = dialog;
        dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getButton(-1).setEnabled(false);
        stopped.setOnCheckedChangeListener((b, checked) -> dialog.getButton(-1).setEnabled(checked));
        dialog.getButton(-1).setOnClickListener(b -> {
            dialog.dismiss();
            run(() -> {
                ParlonsRemote r = original();
                if (!CloudSession.prefs(this).edit().putBoolean("import_committing", true).commit()) throw new IOException("Save failed");
                JSONObject commit = request("commit"); commit.put("confirm", true); commit.put("oldHostStopped", true);
                try { send(r, commit); } catch (Exception lostReply) { /* resolve the committed identity below */ }
                show(Language.msg(R.string.l10n_identity_import_restarting));
                Thread.sleep(6000);
                reconnectImported();
            });
        });

    }
    private void reconnectImported() throws Exception {
        if (target.isEmpty()) throw new IOException("Missing imported address");
        show(Language.msg(R.string.l10n_identity_import_connecting));
        int generation = CloudSession.beginImportConnection(this, oldAccount);
        ParlonsRemote next = null; boolean adopted = false;
        try {
            next = new ParlonsRemote(CloudSession.deviceId(this));
            next.setSeedRelays(PortalRelayStore.get(this)); next.connect(target);
            // nodeStatus requires owner authentication; a ping would not prove preserved pairing.
            if (!Boolean.TRUE.equals(next.nodeStatus().get("ok"))) throw new IOException("Owner verification failed");
            CloudSession.adoptImported(this, oldAccount, target, next, generation); adopted = true;
            runOnUiThread(() -> {
                Toast.makeText(this, Language.msg(R.string.l10n_identity_import_connected), Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish();
            });
        } finally {
            try { if (!adopted && next != null) next.close(); }
            finally { CloudSession.endImportConnection(generation); }
        }
    }
    private void cancel() {
        run(() -> {
            send(original(), request("cancel"));
            if (!CloudSession.prefs(this).edit().remove("import_id").remove("import_old").remove("import_target")
                    .remove("import_committing").commit()) throw new IOException("Save failed");
            id = ""; target = ""; show(Language.msg(R.string.l10n_identity_import_cancelled));
        });
    }
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        setTitle(Language.text(this, R.string.l10n_identity_import_title));
    }

}
