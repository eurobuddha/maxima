package com.eurobuddha.maxima.app.i18n;

import android.app.Activity;
import android.app.Application;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Real account dialogs with synthetic inputs. No export, import, seed retrieval or remote action. */
// WindowInspector is available only on API 29+; this class is excluded from release builds.
@androidx.annotation.RequiresApi(29)
public final class LanguageAccountDialogsPreviewActivity extends AppCompatActivity {
    private static final String DRAFT="Draft 中文 Привет {count} $1";
    private static final String WORDS="one two three four five six seven eight nine ten eleven twelve";
    private String mode,screen;
    private Activity first;
    private View dialogRoot;
    private final List<EditText> inputs=new ArrayList<>();
    private int created,destroyed;
    private boolean validationKeptOpen=true,gateInitiallyDisabled=true;
    private Application.ActivityLifecycleCallbacks callbacks;
    private BroadcastReceiver receiver;
    private java.util.concurrent.ExecutorService heldLane;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Window inspection fixture requires Android 10 or newer");
        if(!"ranchu".equals(android.os.Build.HARDWARE))throw new IllegalStateException("Disposable emulator required");
        mode=getIntent().getStringExtra("mode");
        boolean portal=getPackageName().equals("com.eurobuddha.parlons.cloud");
        if(mode==null || portal==mode.equals("backup"))throw new IllegalArgumentException("Wrong fixture app/mode");
        if(portal)try {
            Class<?> session=Class.forName("com.eurobuddha.maxima.app.portal.CloudSession");
            if(!((String)session.getMethod("account",Context.class).invoke(null,this)).isEmpty()
                    || (Boolean)session.getMethod("isPaired",Context.class).invoke(null,this)
                    || session.getMethod("remoteOrNull").invoke(null)!=null)throw new IllegalStateException("Empty fixture account required");
            // Hold the existing serial lane before Settings queues its fetch. Never release queued remote work.
            heldLane=(java.util.concurrent.ExecutorService)session.getMethod("io").invoke(null);
            java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
            heldLane.execute(()->{entered.countDown();try{new java.util.concurrent.CountDownLatch(1).await();}catch(InterruptedException ignored){}});
            if(!entered.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Fixture lane busy");
        }catch(Exception e){throw new IllegalStateException(e);}
        Language.select(this,"en");
        screen=mode.equals("backup")?"com.eurobuddha.maxima.app.backup.BackupActivity"
                :mode.startsWith("identity-")?"com.eurobuddha.maxima.app.portal.CloudIdentityImportActivity"
                :"com.eurobuddha.maxima.app.portal.CloudSettingsActivity";
        callbacks=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){if(a.getClass().getName().equals(screen))created++;}
            public void onActivityStarted(Activity a){} public void onActivityResumed(Activity a){}
            public void onActivityPostResumed(Activity a){if(a.getClass().getName().equals(screen)&&first==null){first=a;a.getWindow().getDecorView().post(()->install());}}
            public void onActivityPaused(Activity a){} public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}
            public void onActivityDestroyed(Activity a){if(a.getClass().getName().equals(screen))destroyed++;}
        };
        getApplication().registerActivityLifecycleCallbacks(callbacks);
        receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
            String code=i.getStringExtra("code");if(first==null||dialogRoot==null||!Language.supported(code))return;
            Language.select(first,code);dialogRoot.postDelayed(()->verify(),700);
        }};
        ContextCompat.registerReceiver(this,receiver,new IntentFilter(getPackageName()+".LANGUAGE_ACCOUNT_DIALOG_FIXTURE"),ContextCompat.RECEIVER_EXPORTED);
        startActivity(new Intent().setClassName(this,screen).putExtra("mode","export"));
    }
    private void install(){try{
        if(mode.startsWith("identity-")){
            set(first,"target","MAX#Fixture中文Русский{count}$1");
            invoke("preview",new Class<?>[]{String.class},"Owner 中文 Русский {count} $1");
        } else if(mode.equals("cloud-backup"))invoke("promptBackupPassphrase",new Class<?>[0]);
        else if(mode.equals("relays"))invoke("showRelays",new Class<?>[0]);
        else if(mode.equals("seed-warning"))invoke("confirmShowSeed",new Class<?>[0]);
        else if(mode.equals("seed-reveal"))invoke("showSeedDialog",new Class<?>[]{String.class},WORDS);
        else if(!mode.equals("backup"))throw new IllegalArgumentException(mode);
        dialogRoot=latest();
        dialogRoot.postDelayed(()->prepare(),250);
    }catch(Exception e){throw new IllegalStateException(e);}}
    private void prepare(){try{
        collectInputs(dialogRoot,inputs);
        if(mode.equals("backup")){
            inputs.get(0).setText("abc");inputs.get(1).setText("abc");
            dialogRoot.findViewById(android.R.id.button1).performClick();
            validationKeptOpen=dialogRoot.isAttachedToWindow()&&!first.isFinishing()&&field(first,"mPassword")==null;
            inputs.get(0).setText("abcdef");inputs.get(1).setText("different");
            dialogRoot.findViewById(android.R.id.button1).performClick();
            validationKeptOpen &= dialogRoot.isAttachedToWindow()&&!first.isFinishing()&&field(first,"mPassword")==null;
        }
        for(EditText input:inputs){input.setText(DRAFT);input.setSelection(2,5);}
        if(mode.startsWith("identity-")){
            View confirm=dialogRoot.findViewById(android.R.id.button1);gateInitiallyDisabled=!confirm.isEnabled();
            CheckBox acknowledgement=checkbox(dialogRoot);
            if(mode.equals("identity-on"))acknowledgement.setChecked(true);
        }
        if(mode.equals("seed-reveal"))dialogRoot.findViewById(android.R.id.button3).performClick();
        dialogRoot.postDelayed(()->verify(),500);
    }catch(Exception e){throw new IllegalStateException(e);}}
    private void verify(){try{
        boolean same=created==1&&destroyed==0&&!first.isDestroyed()&&!first.isFinishing()&&dialogRoot.isAttachedToWindow()&&validationKeptOpen;
        List<EditText> current=new ArrayList<>();collectInputs(dialogRoot,current);same &= current.equals(inputs);
        for(EditText input:inputs)same &= DRAFT.contentEquals(input.getText())&&input.getSelectionStart()==2&&input.getSelectionEnd()==5;
        if(mode.equals("backup"))same &= field(first,"mPassword")==null;
        if(mode.startsWith("identity-"))same &= gateInitiallyDisabled
                && dialogRoot.findViewById(android.R.id.button1).isEnabled()==mode.equals("identity-on")
                && "MAX#Fixture中文Русский{count}$1".equals(field(first,"target"));
        if(mode.equals("seed-reveal")){
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            same &= clipboard.hasPrimaryClip()&&WORDS.contentEquals(clipboard.getPrimaryClip().getItemAt(0).getText());
        }
        if(!mode.equals("backup")){
            Class<?> session=Class.forName("com.eurobuddha.maxima.app.portal.CloudSession");
            same &= session.getMethod("remoteOrNull").invoke(null)==null;
        }
        boolean secure=dialogRoot.getLayoutParams() instanceof WindowManager.LayoutParams
                && ((((WindowManager.LayoutParams)dialogRoot.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
        if(mode.equals("seed-reveal")||mode.startsWith("identity-"))same &= secure;
        JSONArray labels=new JSONArray();labels(dialogRoot,labels);
        JSONObject result=new JSONObject().put("mode",mode).put("locale",Language.code()).put("same_state",same)
                .put("created",created).put("destroyed",destroyed).put("input_count",inputs.size())
                .put("secure",secure).put("validation_kept_open",validationKeptOpen).put("gate_initially_disabled",gateInitiallyDisabled).put("labels",labels);
        try(java.io.FileOutputStream out=openFileOutput("language-account-dialog-proof.json",MODE_PRIVATE)){out.write(result.toString().getBytes(StandardCharsets.UTF_8));}
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static View latest(){
        // Backup opens its dialog in onCreate, before its activity decor is attached.
        // Window insertion order therefore does not identify the dialog.
        for(View view:WindowInspector.getGlobalWindowViews())
            if(view.findViewById(android.R.id.button1)!=null)return view;
        throw new IllegalStateException("Account dialog did not open");
    }
    private void invoke(String name,Class<?>[] types,Object...args)throws Exception{Method m=first.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(first,args);}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static void collectInputs(View v,List<EditText> out){if(v instanceof EditText)out.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collectInputs(((ViewGroup)v).getChildAt(i),out);}
    private static CheckBox checkbox(View v){if(v instanceof CheckBox)return (CheckBox)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){CheckBox c=checkbox(((ViewGroup)v).getChildAt(i));if(c!=null)return c;}return null;}
    private static void labels(View v,JSONArray out){if(v instanceof TextView){TextView t=(TextView)v;if(t.getText().length()>0)out.put(t.getText().toString());if(t.getHint()!=null)out.put(t.getHint().toString());}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)labels(((ViewGroup)v).getChildAt(i),out);}
    @Override protected void onDestroy(){if(receiver!=null)unregisterReceiver(receiver);if(callbacks!=null)getApplication().unregisterActivityLifecycleCallbacks(callbacks);if(heldLane!=null)heldLane.shutdownNow();super.onDestroy();}
}
