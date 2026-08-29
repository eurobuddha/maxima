package com.eurobuddha.maxima.app;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Parlons Cloud portal — shell (skeleton). Replaced by the tabbed shell (Chats / Contacts /
 * Wallet / Node) driven by the cloud account via ParlonsRemote. Package is
 * com.eurobuddha.maxima.app so the copied Parlons look-layer (R + helpers) resolves unchanged;
 * the app installs separately as com.eurobuddha.parlons.cloud.
 */
public final class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("Parlons Cloud");
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Color.parseColor("#23262B"));
        t.setTextSize(24);
        t.setBackgroundColor(Color.parseColor("#F5F4F1"));
        setContentView(t);
    }
}
