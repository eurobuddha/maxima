package com.eurobuddha.maxima.app.portal;

import android.content.Intent;
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
                if (uri != null) { backup = uri; status.setText("Backup selected. Enter its password, then review."); controls(); }
            });

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("Import Parlons identity");
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(com.eurobuddha.maxima.app.R.color.ux_bg));
        LinearLayout bar = new LinearLayout(this); bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getColor(com.eurobuddha.maxima.app.R.color.ux_header));
        int barPad = PortalUi.dp(this, 16); bar.setPadding(barPad, barPad, barPad, barPad);
        TextView back = new TextView(this); back.setText("‹"); back.setTextSize(26);
        back.setTextColor(getColor(com.eurobuddha.maxima.app.R.color.ux_on_header));
        back.setPadding(0, 0, PortalUi.dp(this, 18), 0); back.setContentDescription("Back");
        back.setOnClickListener(v -> finish()); bar.addView(back);
        TextView heading = new TextView(this); heading.setText("Import identity"); heading.setTextSize(20);
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
        body.addView(PortalUi.title(this, "Restore onto this server"));
        body.addView(PortalUi.label(this, "Use the same phrase or encrypted .pbk backup as the Parlons app. "
                + "This replaces the server’s Parlons identity, contacts and chats. Its Minima wallet stays unchanged. "
                + "The previous account is retained on the server for recovery."));
        phrase = input("Recovery phrase", false);
        phrase.setMinLines(3); body.addView(phrase);
        custom = new CheckBox(this); custom.setText("Custom phrase (Minima anyphrase)"); body.addView(custom);
        body.addView(PortalUi.label(this, "Custom phrases preserve every character, including spaces and capitals."));
        TextView choose = PortalUi.ghost(this, "Choose .pbk backup instead…");
        choose.setOnClickListener(v -> { if (!busy && id.isEmpty()) pick.launch(new String[]{"*/*"}); });
        body.addView(choose);
        password = input("Backup password", true); body.addView(password);
        TextView usePhrase = PortalUi.ghost(this, "Use phrase instead of file");
        usePhrase.setOnClickListener(v -> { if (!busy && id.isEmpty()) { backup = null; status.setText("Phrase selected"); controls(); } });
        body.addView(usePhrase);
        status = PortalUi.value(this, ""); body.addView(status);
        submit = PortalUi.button(this, "Review import"); submit.setOnClickListener(v -> begin()); body.addView(submit);
        resume = PortalUi.ghost(this, "Resume / check import"); resume.setOnClickListener(v -> resume()); body.addView(resume);
        cancel = PortalUi.ghost(this, "Cancel prepared import"); cancel.setOnClickListener(v -> cancel()); body.addView(cancel);
        id = pref("import_id"); oldAccount = pref("import_old"); target = pref("import_target");
        controls();
        if (!id.isEmpty()) status.setText("An import is pending. Tap Resume to check the server. Your phrase is not stored on this phone.");
        lock.onCreate(); secure();
    }

    private EditText input(String hint, boolean secret) {
        EditText e = new EditText(this); e.setHint(hint);
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
    private void show(String text) { runOnUiThread(() -> { if (!isDestroyed()) status.setText(text); }); }
    private void run(Work work) {
        if (busy || com.eurobuddha.maxima.app.AppLock.mustUnlock(this)) return;
        busy = true; controls();
        io.execute(() -> {
            try { work.run(); }
            catch (Exception e) { show("Could not finish. " + safeMessage(e) + "\nUse Resume to check a pending import before starting another."); }
            finally { runOnUiThread(() -> { busy = false; if (!isDestroyed()) controls(); }); }
        });
    }
    // Never echo crypto/parser errors that might embed a phrase, password or backup content.
    private static String safeMessage(Exception e) {
        return e instanceof UserMessage ? e.getMessage() : "Check the server connection and try again.";
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
        if (uri == null && text.trim().isEmpty()) { show("Enter a recovery phrase or choose a backup."); return; }
        if (uri != null && pass.isEmpty()) { show("Enter the backup password."); return; }
        oldAccount = CloudSession.account(this);
        run(() -> {
            show("Checking server support…");
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
                    send(remote, chunk); show("Uploading backup: " + Math.min(100, (offset + 60_000L) * 100 / bytes.length) + "%");
                }
                JSONObject prepare = request("prepare");
                if (uri == null) { prepare.put("phrase", text); prepare.put("anyPhrase", any); }
                else prepare.put("password", pass);
                show("Checking the identity…");
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
            show(String.valueOf(result.getOrDefault("error", "Could not prepare the identity.")) + "\nCancel this import before trying again.");
        } else {
            show("The upload has not been prepared. Cancel it, then select the phrase or backup again.");
        }
    }
    private void preview(String name) {
        if (isDestroyed() || isFinishing() || !foreground || com.eurobuddha.maxima.app.AppLock.mustUnlock(this)) {
            show("Identity prepared. Unlock the app and tap Resume to review it."); return;
        }
        LinearLayout box = PortalUi.card(this);
        box.addView(PortalUi.value(this, (name.isEmpty() ? "Imported identity" : name) + "\n\n" + target));
        box.addView(PortalUi.label(this, "This replaces this server’s Parlons account and restarts the node. "
                + "This phone remains paired. A phrase restores identity only; a backup also restores its saved data. "
                + "The server’s Minima wallet and its recovery phrase stay unchanged."));
        CheckBox stopped = new CheckBox(this); stopped.setText("I have stopped this identity on its original device or server"); box.addView(stopped);
        ScrollView review = new ScrollView(this); review.addView(box);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Import this identity?").setView(review).setNegativeButton("Later", null)
                .setPositiveButton("Import & restart", null).create();
        previewDialog = dialog;
        dialog.setOnShowListener(v -> {
            dialog.getButton(-1).setEnabled(false);
            stopped.setOnCheckedChangeListener((b, checked) -> dialog.getButton(-1).setEnabled(checked));
            dialog.getButton(-1).setOnClickListener(b -> {
                dialog.dismiss();
                run(() -> {
                    ParlonsRemote r = original();
                    if (!CloudSession.prefs(this).edit().putBoolean("import_committing", true).commit()) throw new IOException("Save failed");
                    JSONObject commit = request("commit"); commit.put("confirm", true); commit.put("oldHostStopped", true);
                    try { send(r, commit); } catch (Exception lostReply) { /* resolve the committed identity below */ }
                    show("Server restarting. Reconnecting to the imported identity…");
                    Thread.sleep(6000);
                    reconnectImported();
                });
            });
        });
        dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void reconnectImported() throws Exception {
        if (target.isEmpty()) throw new IOException("Missing imported address");
        show("Connecting to the imported identity…");
        int generation = CloudSession.beginImportConnection(this, oldAccount);
        ParlonsRemote next = null; boolean adopted = false;
        try {
            next = new ParlonsRemote(CloudSession.deviceId(this));
            next.setSeedRelays(PortalRelayStore.get(this)); next.connect(target);
            // nodeStatus requires owner authentication; a ping would not prove preserved pairing.
            if (!Boolean.TRUE.equals(next.nodeStatus().get("ok"))) throw new IOException("Owner verification failed");
            CloudSession.adoptImported(this, oldAccount, target, next, generation); adopted = true;
            runOnUiThread(() -> {
                Toast.makeText(this, "Identity imported and connected", Toast.LENGTH_LONG).show();
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
            id = ""; target = ""; show("Import cancelled. The server account is unchanged.");
        });
    }
}
