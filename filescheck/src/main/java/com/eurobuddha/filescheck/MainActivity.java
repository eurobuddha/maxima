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
