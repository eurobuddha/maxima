package com.eurobuddha.maxima.app.i18n;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.eurobuddha.maxima.app.R;
import java.lang.reflect.Field;

/** Debug-only: real call Activity/renderers with synthetic LIVE state. No peer, capture or signaling. */
public final class LanguageCallPreviewActivity extends AppCompatActivity {
    private String screen;
    private Object manager;
    private int created, destroyed;
    private Activity first;
    private Object local, remote;
    private final String instance = java.util.UUID.randomUUID().toString();
    private Application.ActivityLifecycleCallbacks callbacks;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean portal = !getPackageName().equals("com.eurobuddha.maxima.app");
        String type = portal ? "com.eurobuddha.maxima.app.portal.PortalCallManager" : "com.eurobuddha.maxima.app.call.CallManager";
        screen = portal ? "com.eurobuddha.maxima.app.portal.PortalCallActivity" : "com.eurobuddha.maxima.app.call.CallActivity";
        try {
            Class<?> cls = Class.forName(type);
            manager = cls.getMethod("get", Context.class).invoke(null, this);
            Class stateClass = Class.forName(type+"$State");
            set(manager,"mState",Enum.valueOf(stateClass,"LIVE"));
            set(manager,"mCallId",instance);set(manager,"mPeerKey","0x"+"1".repeat(64));
            set(manager,"mVideo",true);set(manager,"mLiveSince",System.currentTimeMillis());
        } catch (Exception error) { throw new IllegalStateException(error); }
        callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity,Bundle saved) { if (activity.getClass().getName().equals(screen)) created++; }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity activity) {
                if (!activity.getClass().getName().equals(screen)) return;
                if (first != null) return;
                first = activity;
                activity.getWindow().getDecorView().post(() -> install(activity));
            }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a,Bundle b) {}
            public void onActivityDestroyed(Activity a) { if(a.getClass().getName().equals(screen)) destroyed++; }
        };
        getApplication().registerActivityLifecycleCallbacks(callbacks);
        startActivity(new Intent().setClassName(this,screen).putExtra("peer","0x"+"1".repeat(64))
                .putExtra("name","Chats").putExtra("video",true).putExtra("call_id",instance));
    }
    private void install(Activity activity) {
        try { local=get(activity,"mLocalView");remote=get(activity,"mRemoteView"); }
        catch(Exception error){throw new IllegalStateException(error);}
        FrameLayout content=activity.findViewById(android.R.id.content);
        Button choose=new Button(activity);choose.setId(android.R.id.button1);
        Language.setText(choose,Language.msg(R.string.l10n_language_change));
        choose.setOnClickListener(v->Language.choose(activity,false));
        FrameLayout.LayoutParams button=new FrameLayout.LayoutParams(-2,-2,android.view.Gravity.TOP|android.view.Gravity.END);
        button.topMargin=100;content.addView(choose,button);
        TextView proof=new TextView(activity);proof.setId(android.R.id.text1);proof.setTextColor(0xFFFFFFFF);
        proof.setBackgroundColor(0xFF000000);FrameLayout.LayoutParams label=new FrameLayout.LayoutParams(-1,-2,android.view.Gravity.TOP);
        label.topMargin=220;content.addView(proof,label);
        Runnable verify=new Runnable(){public void run(){
            if(activity.isDestroyed())return;
            try {
                boolean same=created==1&&destroyed==0&&local!=null&&remote!=null
                        &&local==get(activity,"mLocalView")&&remote==get(activity,"mRemoteView")
                        &&local==get(manager,"mLocalSink")&&remote==get(manager,"mRemoteSink")
                        &&instance.equals(get(manager,"mCallId"));
                String result=(same?"PASS":"FAIL")+" renderers "+instance;
                if(!result.contentEquals(proof.getText()))proof.setText(result);
            }catch(Exception error){proof.setText("FAIL "+error.getClass().getSimpleName());}
            proof.postDelayed(this,500);
        }};proof.post(verify);
    }
    private static Object get(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    @Override protected void onDestroy(){if(callbacks!=null)getApplication().unregisterActivityLifecycleCallbacks(callbacks);super.onDestroy();}
}
