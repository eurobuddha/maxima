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

/** Debug-only real standalone wallet UI. Synthetic identity, HTTP blocked, service disabled. API 29+. */
// WindowInspector is available only on API 29+; this class is excluded from release builds.
@androidx.annotation.RequiresApi(29)
public final class LanguageStandaloneWalletPreviewActivity extends AppCompatActivity {
    private static final String DRAFT = "Draft 中文 Привет {count} $1";
    private String screen, mode;
    private Activity first;
    private Object wallet, publisher, signer;
    private static final String PHRASE = "abandon ".repeat(23) + "art";
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
        com.eurobuddha.maxima.app.Sha3Provider.install();
        boolean marked = getSharedPreferences("wallet_language_fixture", MODE_PRIVATE).getBoolean("synthetic", false);
        if (com.eurobuddha.maxima.app.SeedStore.hasIdentity(this) && (!marked || !PHRASE.equalsIgnoreCase(com.eurobuddha.maxima.app.SeedStore.revealPhrase(this))))
            throw new IllegalStateException("Empty or synthetic fixture identity required");
        java.net.URL.setURLStreamHandlerFactory(protocol -> ("http".equals(protocol) || "https".equals(protocol)) ? new java.net.URLStreamHandler() {
            protected java.net.URLConnection openConnection(java.net.URL url) throws java.io.IOException {
                throw new java.io.IOException("Network disabled in wallet fixture");
            }
        } : null);
        Language.select(this, "en");
        screen = "com.eurobuddha.maxima.app.MainActivity";
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "overview";
        service = new android.content.ComponentName(this, com.eurobuddha.maxima.app.MaximaService.class);
        getPackageManager().setComponentEnabledSetting(service, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP);

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

                // Stop fixture polling before its posted callback runs. The service remains disabled throughout this fixture.
                try {
                    Handler handler = (Handler) field(a, "mHandler");
                    handler.removeCallbacks((Runnable) field(a, "mTick"));

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
        new Thread(() -> {
            com.eurobuddha.maxima.app.SeedStore.importPhrase(this, PHRASE);
            getSharedPreferences("wallet_language_fixture", MODE_PRIVATE).edit().putBoolean("synthetic", true).commit();
            com.eurobuddha.maxima.app.wallet.MaximaWallet.setUsesAtLeast(this, 37);
            runOnUiThread(() -> startActivity(new Intent().setClassName(this, screen)));
        }, "synthetic-wallet-identity").start();
    }

    private void install(Activity activity) {
        try {
            wallet = ((List<?>) field(activity,"mPages")).get(2);
            publisher = field(wallet,"mPub");
            signer = field(wallet, "mWallet");
            if (signer == null) { activity.getWindow().getDecorView().postDelayed(() -> install(activity), 250); return; }
            set(wallet,"mSending",true); set(wallet,"mLastFetch",Long.MAX_VALUE);
            set(wallet,"mConfMinima","2.234567890123456789");
            set(wallet,"mSpendMinima","1.234567890123456789");
            set(wallet,"mUnconfMinima","0.000000000123456789");
            invoke("repaintHero",new Class<?>[0]);
            ((androidx.viewpager.widget.ViewPager)field(activity,"mPager")).setCurrentItem(2,false);
            List<View> before = WindowInspector.getGlobalWindowViews();
            switch(mode) {
                case "send": invoke("showSend",new Class<?>[0]);break;
                case "node": invoke("showNodeConfig",new Class<?>[0]);break;
                case "settings": invoke("showWalletSettings",new Class<?>[0]);break;
                case "import": invoke("importSeed",new Class<?>[0]);break;
                case "uses": invoke("adjustUses",new Class<?>[0]);break;
                case "help": invoke("showUsesExplainer",new Class<?>[0]);break;
                case "history":
                    com.eurobuddha.maxima.app.wallet.WalletLedger.Row row = new com.eurobuddha.maxima.app.wallet.WalletLedger.Row();
                    row.sent=true;row.amount="1.234567890123456789";row.txid="0x"+"abcd1234".repeat(8);
                    row.who="Wallet 中文 Русский";row.token="Send 中文";row.time=1767225600000L;
                    invoke("showHistoryDetail",new Class<?>[]{row.getClass()},row);break;
                case "overview": break;
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

            boolean same = created==1 && destroyed==0 && !first.isDestroyed()
                    && wallet==((List<?>)field(first,"mPages")).get(2) && publisher==field(wallet,"mPub")
                    && Boolean.TRUE.equals(field(wallet,"mSending")) && signer==field(wallet,"mWallet")
                    && ((com.eurobuddha.maxima.app.wallet.MaximaWallet)signer).uses(this)==37
                    && dialogRoot.isAttachedToWindow()
                    && getPackageManager().getComponentEnabledSetting(service)==android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
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
