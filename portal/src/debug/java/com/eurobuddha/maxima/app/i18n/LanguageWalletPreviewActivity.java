package com.eurobuddha.maxima.app.i18n;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.eurobuddha.maxima.app.R;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only real Cloud wallet UI with synthetic cached data. No network or signing. API 29+. */
// WindowInspector is available only on API 29+; this class is excluded from release builds.
@androidx.annotation.RequiresApi(29)
public final class LanguageWalletPreviewActivity extends AppCompatActivity {
    private static final String DRAFT = "Draft 中文 Привет {count} $1";
    private String screen, mode;
    private Activity first;
    private Object wallet, publisher;
    private android.content.ComponentName service;
    private View dialogRoot;
    private final List<EditText> inputs = new ArrayList<>();
    private final List<String> values = new ArrayList<>();
    private int created, destroyed;
    private Application.ActivityLifecycleCallbacks callbacks;
    private BroadcastReceiver receiver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Window inspection fixture requires Android 10 or newer");
        if (!"ranchu".equals(android.os.Build.HARDWARE)) throw new IllegalStateException("Disposable emulator required");
        if (!com.eurobuddha.maxima.app.portal.CloudSession.account(this).isEmpty()) throw new IllegalStateException("Empty fixture account required");
        Language.select(this, "en");
        screen = "com.eurobuddha.maxima.app.MainActivity";
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "overview";
        service = new android.content.ComponentName(this, com.eurobuddha.maxima.app.portal.PortalService.class);
        getPackageManager().setComponentEnabledSetting(service, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP);
        com.eurobuddha.maxima.app.portal.CloudSession.prefs(this).edit().putBoolean("paired",true).commit();
        callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {
                if (!a.getClass().getName().equals(screen)) return;
                created++;
            }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {}
            public void onActivityPostResumed(Activity a) {
                if (!a.getClass().getName().equals(screen) || first != null) return;
                first = a;
                // MainActivity's pairing guard and onResume must finish before cleanup.
                com.eurobuddha.maxima.app.portal.CloudSession.prefs(a).edit().putBoolean("paired",false).commit();
                getPackageManager().setComponentEnabledSetting(service, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, android.content.pm.PackageManager.DONT_KILL_APP);
                // Stop fixture polling before its posted callback runs. No account is paired.
                try {
                    Handler handler = (Handler) field(a, "mHandler");
                    handler.removeCallbacks((Runnable) field(a, "mTick"));
                    set(a,"mPolling",true);
                } catch (Exception e) { throw new IllegalStateException(e); }
                a.getWindow().getDecorView().post(() -> install(a));
            }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) { if (a.getClass().getName().equals(screen)) destroyed++; }
        };
        getApplication().registerActivityLifecycleCallbacks(callbacks);
        receiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (first == null || dialogRoot == null) return;
                String code = intent.getStringExtra("code");
                if (!Language.supported(code)) return;
                Language.select(first, code);
                first.getWindow().getDecorView().postDelayed(() -> verify(), 1000);
            }
        };
        ContextCompat.registerReceiver(this, receiver,
                new IntentFilter(getPackageName()+".LANGUAGE_WALLET_FIXTURE"), ContextCompat.RECEIVER_EXPORTED);
        startActivity(new Intent().setClassName(this, screen));
    }

    private void install(Activity activity) {
        try {
            wallet = ((List<?>) field(activity,"mPages")).get(2);
            publisher = field(wallet,"mPub");
            set(wallet,"mBusy",true); set(wallet,"mSending",true);
            set(wallet,"mAddress","0x"+"abcd1234".repeat(8));
            set(wallet,"mSendable","1.234567890123456789"); set(wallet,"mConfirmed","2.234567890123456789");
            set(wallet,"mUnconfirmed","0.000000000123456789"); set(wallet,"mWholeWallet",true);
            set(wallet,"mUses",37); set(wallet,"mMaxUses",262144); set(wallet,"mCoins",22);
            set(wallet,"mBalanceAt",System.currentTimeMillis()-120000L);
            if ("watch".equals(mode)) { set(wallet,"mAddress","");set(wallet,"mError","no watch"); }
            invoke("rebuild",new Class<?>[0]);
            ((androidx.viewpager.widget.ViewPager)field(activity,"mPager")).setCurrentItem(2,false);
            List<View> before = WindowInspector.getGlobalWindowViews();
            switch(mode) {
                case "send": invoke("sendSheet",new Class<?>[0]);break;
                case "resync": invoke("resyncFlow",new Class<?>[0]);break;
                case "uses": invoke("usesSheet",new Class<?>[0]);break;
                case "raise": invoke("confirmUses",new Class<?>[]{int.class},10038);break;
                case "confirm": invoke("confirmSend",new Class<?>[]{String.class,String.class},"0x"+"abcd1234".repeat(8),"1.234567890123456789");break;
                case "sweep": invoke("confirmDetach",new Class<?>[]{String.class},"0x"+"abcd1234".repeat(8));break;
                case "overview": case "watch": break;
                default: throw new IllegalArgumentException(mode);
            }
            for (View root : WindowInspector.getGlobalWindowViews()) if (!before.contains(root)) dialogRoot=root;
            if (dialogRoot==null) dialogRoot=((com.eurobuddha.maxima.app.ui.Page)wallet).view();
            collectInputs(dialogRoot, inputs);
            for (EditText input : inputs) {
                boolean number=(input.getInputType() & android.text.InputType.TYPE_MASK_CLASS)==android.text.InputType.TYPE_CLASS_NUMBER;
                String value=number ? ("uses".equals(mode) ? "10038" : "1.234567890123456789") : DRAFT;
                input.setText(value);input.setSelection(2,5);values.add(value);
            }
            dialogRoot.postDelayed(()->verify(),500);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void invoke(String name,Class<?>[] types,Object... args)throws Exception {
        Method method=wallet.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(wallet,args);
    }
    private static void set(Object target,String name,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }

    private void verify() {
        try {
            if ("watch".equals(mode)) invoke("rebuild", new Class<?>[0]);
            boolean same = created==1 && destroyed==0 && !first.isDestroyed()
                    && wallet==((List<?>)field(first,"mPages")).get(2) && publisher==field(wallet,"mPub")
                    && Boolean.TRUE.equals(field(wallet,"mSending")) && ((Integer)field(wallet,"mUses"))==37
                    && dialogRoot.isAttachedToWindow()
                    && !com.eurobuddha.maxima.app.portal.CloudSession.isPaired(this)
                    && com.eurobuddha.maxima.app.portal.CloudSession.remoteOrNull()==null;
            List<EditText> current = new ArrayList<>(); collectInputs(dialogRoot, current);
            same &= current.equals(inputs);
            for (int i = 0; i < inputs.size(); i++) same &= values.get(i).contentEquals(inputs.get(i).getText())
                    && inputs.get(i).getSelectionStart() == 2 && inputs.get(i).getSelectionEnd() == 5;
            JSONArray labels = new JSONArray(); collectLabels(dialogRoot, labels);
            JSONObject result = new JSONObject().put("same_views_and_drafts", same)
                    .put("locale", Language.code()).put("mode", mode).put("labels", labels)
                    .put("created", created).put("destroyed", destroyed).put("input_count", inputs.size());
            try (java.io.FileOutputStream out = openFileOutput("language-wallet-proof.json", MODE_PRIVATE)) {
                out.write(result.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void collectInputs(View view, List<EditText> out) {
        if (view instanceof EditText) out.add((EditText)view);
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) collectInputs(((ViewGroup)view).getChildAt(i),out);
    }
    private static void collectLabels(View view, JSONArray out) {
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            if (text.getText().length()>0) out.put(text.getText().toString());
            if (text.getHint()!=null) out.put(text.getHint().toString());
        }
        if (view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) collectLabels(((ViewGroup)view).getChildAt(i),out);
    }
    @Override protected void onDestroy() {
        if (receiver != null) unregisterReceiver(receiver);
        if (callbacks != null) getApplication().unregisterActivityLifecycleCallbacks(callbacks);
        super.onDestroy();
    }
}
