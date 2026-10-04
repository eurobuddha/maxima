package com.eurobuddha.filescheck;

/** Isolated UI harness: synthetic text only; intercepts browser/share intents. */
public final class ChatLinkCheckActivity extends android.app.Activity {
    private android.widget.TextView status;
    @Override public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        android.widget.LinearLayout column = new android.widget.LinearLayout(this);
        column.setOrientation(1); column.setPadding(30, 70, 30, 30);
        status = new android.widget.TextView(this); status.setText("No action");
        android.widget.TextView body = new android.widget.TextView(this);
        body.setTextSize(20); body.setPadding(0, 20, 0, 30);
        body.setTextColor(android.graphics.Color.BLACK); body.setTextIsSelectable(true);
        com.eurobuddha.maxima.app.chat.ChatLinkText.bind(body, "example.com\nhttps://example.org\nPlain message text", () -> status.setText("MESSAGE MENU"));
        column.addView(body); column.addView(status); setContentView(column);
        getSystemService(android.content.ClipboardManager.class).addPrimaryClipChangedListener(() -> {
            android.content.ClipData clip = getSystemService(android.content.ClipboardManager.class).getPrimaryClip();
            if (clip != null) status.setText("COPIED " + clip.getItemAt(0).getText());
        });
    }
    @Override public void startActivity(android.content.Intent intent) {
        status.setText("OPEN " + intent.getDataString());
    }
}
