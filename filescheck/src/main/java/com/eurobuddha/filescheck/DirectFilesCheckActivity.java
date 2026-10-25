package com.eurobuddha.filescheck;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public final class DirectFilesCheckActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setShowWhenLocked(true);setTurnScreenOn(true);
        android.content.Intent service=new android.content.Intent(this,TransferCheckService.class);
        startForegroundService(service);
        TextView text=new TextView(this);text.setPadding(32,48,32,32);text.setText("Testing 100 MiB direct WebRTC, interruption and resume…");setContentView(text);
        new Thread(()->{
            String result;
            try{com.eurobuddha.maxima.app.Sha3Provider.install();result=com.eurobuddha.maxima.files.RtcTransferCheck.run(this);}
            catch(Throwable e){result="FAIL: "+android.util.Log.getStackTraceString(e);}
            try(java.io.OutputStream out=openFileOutput("direct-result.txt",MODE_PRIVATE)){out.write(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
            stopService(service);
            android.util.Log.i("PrivateFilesCheck",result);final String report=result;runOnUiThread(()->text.setText(report));
        },"direct-file-check").start();
    }
}
