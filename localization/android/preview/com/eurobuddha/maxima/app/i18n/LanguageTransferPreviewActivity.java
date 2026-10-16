package com.eurobuddha.maxima.app.i18n;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.chat.PrivateFileUi;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Debug-only: real file UI and FileProvider, synthetic backend, no account or network. */
public final class LanguageTransferPreviewActivity extends AppCompatActivity {
    private final String instance = java.util.UUID.randomUUID().toString();
    private final CountDownLatch release = new CountDownLatch(1);
    private PrivateFileUi files;
    private TextView state;
    private int begins, appends, finishes, cancellations;
    private long received;
    private static final String NAME = "Chats 中文 Привет.bin";
    private static final int SIZE = 100 * 1024;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER); root.setPadding(24,100,24,100);
        TextView id = new TextView(this); id.setId(android.R.id.text1); id.setText(instance); root.addView(id);
        state = new TextView(this); state.setId(android.R.id.text2); root.addView(state);
        Button language = new Button(this); language.setId(android.R.id.button1);
        Language.setText(language, Language.msg(R.string.l10n_language_change));
        language.setOnClickListener(v -> Language.choose(this,false));root.addView(language);
        Button resume = new Button(this); resume.setId(android.R.id.button2);resume.setText("Release synthetic upload");
        resume.setOnClickListener(v -> release.countDown());root.addView(resume);
        setContentView(root);
        files = new PrivateFileUi(this, parameters -> {
            Map<String,String> response = new HashMap<>(); response.put("ok","true");
            switch (parameters.get("action")) {
                case "begin":
                    begins++;
                    if (!NAME.equals(parameters.get("name"))) throw new IllegalStateException("Filename changed");
                    break;
                case "append":
                    appends++;
                    if (Long.parseLong(parameters.get("offset")) != received) throw new IllegalStateException("Offset changed");
                    byte[] bytes = java.util.Base64.getDecoder().decode(parameters.get("data"));
                    for (byte value : bytes) if (value != 42) throw new IllegalStateException("Content changed");
                    received += bytes.length;
                    publish();
                    if (appends == 1 && !release.await(120,TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timed out");
                    break;
                case "finish":
                    finishes++;
                    if (received != SIZE) throw new IllegalStateException("Size changed");
                    break;
                case "status":
                    response.put("name",NAME); response.put("done",Long.toString(received));
                    response.put("size",Integer.toString(SIZE)); response.put("status","Ready · sharing");
                    response.put("ready","true"); response.put("paused","false");
                    break;
                case "cancelUpload": cancellations++; break;
                default: throw new IllegalStateException("Unexpected fixture action");
            }
            publish();return response;
        },"synthetic-peer",false);
        try {
            File directory = new File(getCacheDir(),"maximapayloads"); directory.mkdirs();
            File file = new File(directory,NAME); byte[] bytes = new byte[SIZE];java.util.Arrays.fill(bytes,(byte)42);
            Files.write(file.toPath(),bytes);
            root.post(() -> files.result(PrivateFileUi.PICK, Activity.RESULT_OK,
                    new Intent().setData(FileProvider.getUriForFile(this,getPackageName()+".payloads",file))));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private void publish() {
        String result = begins+"/"+appends+"/"+finishes+"/"+cancellations+"/"+received;
        runOnUiThread(() -> state.setText(result));
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);Language.configurationChanged(this);
    }
    @Override protected void onDestroy() {
        if (files != null) files.close();super.onDestroy();
    }
}
