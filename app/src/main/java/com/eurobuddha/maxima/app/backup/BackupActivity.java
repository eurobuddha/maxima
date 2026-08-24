package com.eurobuddha.maxima.app.backup;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A thin, headless activity that owns the Storage-Access-Framework plumbing for
 * encrypted identity backup. Launched from Settings with EXTRA_MODE = "export"
 * or "import"; shows only dialogs + the system file picker, then finishes.
 */
public final class BackupActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";

    private ActivityResultLauncher<String> mCreate;
    private ActivityResultLauncher<String[]> mOpen;
    private char[] mPassword;   // held between the password prompt and the file pick

    private interface PwCallback {
        void run(char[] password);
    }

    @Override
    protected void onCreate(Bundle zState) {
        super.onCreate(zState);
        mCreate = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                uri -> {
                    if (uri != null) {
                        doExport(uri);
                    } else {
                        finish();
                    }
                });
        mOpen = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        promptImportPassword(uri);
                    } else {
                        finish();
                    }
                });

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if ("export".equals(mode)) {
            startExport();
        } else if ("import".equals(mode)) {
            mOpen.launch(new String[]{"*/*"});
        } else {
            finish();
        }
    }

    // ---- export -----------------------------------------------------------

    private void startExport() {
        askPassword("Back up identity",
                "Choose a password to protect this backup. You'll need it to restore — "
                        + "it is NOT recoverable if you lose it.",
                true,
                pw -> {
                    mPassword = pw;
                    mCreate.launch("parlons-backup.pbk");
                });
    }

    private void doExport(Uri zUri) {
        try (OutputStream os = getContentResolver().openOutputStream(zUri)) {
            os.write(BackupManager.export(this, mPassword));
            os.flush();
            toast("Backup saved");
        } catch (Exception e) {
            toast("Backup failed: " + e.getMessage());
        } finally {
            clearPw();
            finish();
        }
    }

    // ---- import -----------------------------------------------------------

    private void promptImportPassword(Uri zUri) {
        askPassword("Restore from backup", "Enter the backup password.", false, pw -> {
            try (InputStream is = getContentResolver().openInputStream(zUri)) {
                byte[] blob = readAll(is);
                final BackupBundle bundle = BackupManager.read(blob, pw);
                new AlertDialog.Builder(this)
                        .setTitle("Restore this backup?")
                        .setMessage("This REPLACES the identity, contacts and chats on THIS phone "
                                + "with the ones in the backup. It can't be undone.")
                        .setNegativeButton("Cancel", (d, w) -> finish())
                        .setOnCancelListener(d -> finish())
                        .setPositiveButton("Restore", (d, w) -> {
                            BackupManager.apply(this, bundle);
                            finish();
                        })
                        .show();
            } catch (Exception e) {
                toast("Could not read backup: " + e.getMessage());
                finish();
            } finally {
                java.util.Arrays.fill(pw, '\0');
            }
        });
    }

    // ---- helpers ----------------------------------------------------------

    private void askPassword(String zTitle, String zMsg, boolean zConfirm, PwCallback zCb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, 0);
        final EditText pw = passwordField("Password");
        box.addView(pw);
        final EditText confirm = zConfirm ? passwordField("Repeat password") : null;
        if (confirm != null) {
            confirm.setGravity(Gravity.TOP);
            box.addView(confirm);
        }

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(zTitle)
                .setMessage(zMsg)
                .setView(box)
                .setNegativeButton("Cancel", (di, w) -> finish())
                .setOnCancelListener(di -> finish())
                .setPositiveButton("OK", null)   // overridden below so validation can keep it open
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String p1 = pw.getText().toString();
            if (p1.length() < 6) {
                toast("Use at least 6 characters");
                return;
            }
            if (confirm != null && !p1.equals(confirm.getText().toString())) {
                toast("Passwords don't match");
                return;
            }
            d.dismiss();
            zCb.run(p1.toCharArray());
        }));
        d.show();
    }

    private EditText passwordField(String zHint) {
        EditText e = new EditText(this);
        e.setHint(zHint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private static byte[] readAll(InputStream zIs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zIs.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private void clearPw() {
        if (mPassword != null) {
            java.util.Arrays.fill(mPassword, '\0');
            mPassword = null;
        }
    }

    private int dp(int zDp) {
        return Math.round(zDp * getResources().getDisplayMetrics().density);
    }

    private void toast(String zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_LONG).show();
    }
}
