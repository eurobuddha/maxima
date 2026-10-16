package com.eurobuddha.maxima.app.i18n;

import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.eurobuddha.maxima.app.ui.Explain;
import java.util.List;
import org.json.*;

/** Debug-only real Explain dialog; no identity, service, account or network action. */
// WindowInspector is available only on API 29+; this class is excluded from release builds.
@androidx.annotation.RequiresApi(29)
public final class LanguageHelpPreviewActivity extends AppCompatActivity {
    private final String instance=java.util.UUID.randomUUID().toString();
    private String topic;
    private View dialogRoot;
    private BroadcastReceiver receiver;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Window inspection fixture requires Android 10 or newer");
        if(!"ranchu".equals(android.os.Build.HARDWARE))throw new IllegalStateException("Disposable emulator required");
        topic=getIntent().getStringExtra("topic");
        Language.select(this,"en");
        TextView background=new TextView(this);setContentView(background);
        receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
            String code=i.getStringExtra("code");if(dialogRoot==null||!Language.supported(code))return;
            Language.select(LanguageHelpPreviewActivity.this,code);dialogRoot.postDelayed(()->verify(),500);
        }};
        ContextCompat.registerReceiver(this,receiver,new IntentFilter(getPackageName()+".LANGUAGE_HELP_FIXTURE"),ContextCompat.RECEIVER_EXPORTED);
        background.post(()->{
            List<View> before=WindowInspector.getGlobalWindowViews();
            Explain.show(this,topic);
            for(View root:WindowInspector.getGlobalWindowViews())if(!before.contains(root))dialogRoot=root;
            if(dialogRoot==null)throw new IllegalStateException("Help dialog did not open");
            dialogRoot.postDelayed(()->verify(),500);
        });
    }
    private void verify(){try{
        JSONArray labels=new JSONArray();labels(dialogRoot,labels);
        JSONObject result=new JSONObject().put("instance",instance).put("topic",topic).put("locale",Language.code())
                .put("same_dialog",dialogRoot.isAttachedToWindow()&&!isFinishing()&&!isDestroyed()).put("labels",labels);
        try(java.io.FileOutputStream out=openFileOutput("language-help-proof.json",MODE_PRIVATE)){
            out.write(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static void labels(View v,JSONArray out){
        if(v instanceof TextView&&((TextView)v).getText().length()>0)out.put(((TextView)v).getText().toString());
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)labels(((ViewGroup)v).getChildAt(i),out);
    }
    @Override protected void onDestroy(){if(receiver!=null)unregisterReceiver(receiver);super.onDestroy();}
}
