package com.eurobuddha.filescheck;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);TextView text=new TextView(this);text.setPadding(32,48,32,32);
        text.setText("Testing private file encryption, direct transfer and resume…");setContentView(text);
        try {
            TextView bubble = new TextView(this);
            bubble.setTextColor(android.graphics.Color.BLACK);
            bubble.setTextIsSelectable(true);
            com.eurobuddha.maxima.app.chat.ChatLinkText.bind(bubble, "Open https://example.org and www.example.org.");
            android.text.Spanned spans = (android.text.Spanned) bubble.getText();
            if (spans.getSpans(0,spans.length(),android.text.style.URLSpan.class).length != 2
                    || !bubble.getLinksClickable()
                    || bubble.getLinkTextColors().getDefaultColor() != android.graphics.Color.BLACK)
                throw new AssertionError("Link spans, taps or light theme colour failed");
            com.eurobuddha.maxima.app.chat.ChatLinkText.reset(bubble);
            bubble.setTextIsSelectable(false); bubble.setText("Private file");
            if (bubble.getMovementMethod() != null || bubble.getLinksClickable())
                throw new AssertionError("Recycled file bubble retained link handling");
            bubble.setTextIsSelectable(true); bubble.setTextColor(android.graphics.Color.WHITE);
            com.eurobuddha.maxima.app.chat.ChatLinkText.bind(bubble, "https://example.org");
            if (bubble.getLinkTextColors().getDefaultColor() != android.graphics.Color.WHITE)
                throw new AssertionError("Dark outgoing link colour failed");
            android.util.Log.i("PrivateFilesCheck", "PASS: native chat link spans, movement, recycling and light/dark colours");
        } catch (Throwable e) {
            text.setText("FAIL native chat links: " + e); return;
        }
        new Thread(()->{
            String report;
            try {
                com.eurobuddha.maxima.app.Sha3Provider.install();
                Result result=JUnitCore.runClasses(com.eurobuddha.maxima.files.PrivateTorrentTest.class);
                StringBuilder out=new StringBuilder(result.wasSuccessful()?"PASS":"FAIL");
                out.append(" · ").append(result.getRunCount()).append(" tests\n");
                for(Failure failure:result.getFailures())out.append(failure.getTrace()).append('\n');
                report=out.toString();
            }catch(Throwable e){report="FAIL\n"+android.util.Log.getStackTraceString(e);}
            final String output=report;
            try(java.io.FileOutputStream file=openFileOutput("result.txt",MODE_PRIVATE)){file.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
            android.util.Log.i("PrivateFilesCheck",report);runOnUiThread(()->text.setText(output));
        },"private-file-smoke").start();
    }
}
