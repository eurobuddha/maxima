package com.eurobuddha.maxima.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.eurobuddha.maxima.core.MaximaNode;

/** Minimal status screen. The real work is in the service. */
public final class MainActivity extends AppCompatActivity {

    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mStatus = findViewById(R.id.status);

        Sha3Provider.install();
        requestBatteryExemption();
        MaximaService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        MaximaNode node = MaximaService.node();
        StringBuilder sb = new StringBuilder();
        if (node == null) {
            sb.append("Transport starting...");
        } else {
            sb.append("Identity\n").append(node.identity().mxIdentity(), 0, 40).append("...\n\n");
            sb.append("Relays: ").append(node.pool().activeCount()).append('\n');
            for (String a : node.myAddresses()) {
                sb.append("  ").append(a.substring(a.indexOf('@'))).append('\n');
            }
            sb.append("\nContacts: ").append(node.contacts().size());
            sb.append("\nServices: ").append(node.services().methods().size());
            sb.append("\nMailbox items: ").append(node.mailbox().totalItems());
            sb.append("\nOutbox: ").append(node.outbox().size());
        }
        mStatus.setText(sb.toString());
    }

    /**
     * Without this exemption the OS will not let a broadcast receiver start our
     * foreground service, which breaks the heartbeat chain entirely.
     */
    private void requestBatteryExemption() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) {
        }
    }
}
