package com.eurobuddha.maxima.app.backup;

import com.eurobuddha.maxima.app.i18n.LanguageDialog;

import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
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
        askPassword(Language.msg(R.string.l10n_backup_title),
                Language.msg(R.string.l10n_backup_warning),
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
            toast(tr(R.string.l10n_native_dialog_backup_saved));
        } catch (Exception e) {
            toast(tr(R.string.l10n_native_dialog_backup_failed, SystemMessages.text(this, e.getMessage())));
        } finally {
            clearPw();
            finish();
        }
    }

    // ---- import -----------------------------------------------------------

    private void promptImportPassword(Uri zUri) {
        askPassword(Language.msg(R.string.l10n_backup_restore_title), Language.msg(R.string.l10n_backup_enter_password), false, pw -> {
            try (InputStream is = getContentResolver().openInputStream(zUri)) {
                byte[] blob = readAll(is);
                final BackupBundle bundle = BackupManager.read(blob, pw);
                new LanguageDialog(this)
                        .setTitle(Language.msg(R.string.l10n_backup_confirm_title))
                        .setMessage(Language.msg(R.string.l10n_backup_replace_warning))
                        .setNegativeButton(Language.msg(R.string.l10n_common_cancel), (d, w) -> finish())
                        .setOnCancelListener(d -> finish())
                        .setPositiveButton(Language.msg(R.string.l10n_native_dialog_restore), (d, w) -> {
                            BackupManager.apply(this, bundle);
                            finish();
                        })
                        .show();
            } catch (Exception e) {
                toast(tr(R.string.l10n_backup_read_failed, SystemMessages.text(this, e.getMessage())));
                finish();
            } finally {
                java.util.Arrays.fill(pw, '\0');
            }
        });
    }

    // ---- helpers ----------------------------------------------------------

    private void askPassword(CharSequence zTitle, CharSequence zMsg, boolean zConfirm, PwCallback zCb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, 0);
        final EditText pw = passwordField(Language.msg(R.string.l10n_backup_password));
        box.addView(pw);
        final EditText confirm = zConfirm ? passwordField(Language.msg(R.string.l10n_backup_repeat_password)) : null;
        if (confirm != null) {
            confirm.setGravity(Gravity.TOP);
            box.addView(confirm);
        }

        AlertDialog d = new LanguageDialog(this)
                .setTitle(zTitle)
                .setMessage(zMsg)
                .setView(box)
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), (di, w) -> finish())
                .setOnCancelListener(di -> finish())
                .setPositiveButton(Language.msg(R.string.l10n_common_ok), null)   // overridden below so validation can keep it open
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String p1 = pw.getText().toString();
            if (p1.length() < 6) {
                toast(tr(R.string.l10n_backup_password_length));
                return;
            }
            if (confirm != null && !p1.equals(confirm.getText().toString())) {
                toast(tr(R.string.l10n_backup_password_mismatch));
                return;
            }
            d.dismiss();
            zCb.run(p1.toCharArray());
        });
    }

    private EditText passwordField(CharSequence zHint) {
        EditText e = new EditText(this);
        Language.hint(e, zHint);
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

    private String tr(int resource, Object... args) { return Language.text(this, resource, args); }

    private void toast(String zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_LONG).show();
    }
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        com.eurobuddha.maxima.app.i18n.Language.configurationChanged(this);
    }

}
