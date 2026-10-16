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

/** Debug-only real standalone Network UI. Synthetic identity, HTTP blocked, service disabled. API 29+. */
// WindowInspector is available only on API 29+; this class is excluded from release builds.
@androidx.annotation.RequiresApi(29)
public final class LanguageNetworkPreviewActivity extends AppCompatActivity {
    private static final String DRAFT = "Draft 中文 Привет {count} $1";
    private String screen, mode;
    private Activity first;
    private LanguageTabProbe tabProbe;
    private int tabSequence;
    private Object network;
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
                if (tabProbe != null) {
                    tabSequence = intent.getIntExtra("sequence", 0);
                    if (intent.getBooleanExtra("settings", false)) tabProbe.showSettings();
                    String direction = intent.getStringExtra("scroll");
                    if (direction != null) tabProbe.scroll(direction);
                }
                first.getWindow().getDecorView().postDelayed(() -> verify(), tabProbe == null ? 1000 : 2000);
            }
        };
        ContextCompat.registerReceiver(this, receiver,
                new IntentFilter(getPackageName()+".LANGUAGE_NETWORK_FIXTURE"), ContextCompat.RECEIVER_EXPORTED);
        new Thread(() -> {
            com.eurobuddha.maxima.app.SeedStore.importPhrase(this, PHRASE);
            getSharedPreferences("wallet_language_fixture", MODE_PRIVATE).edit().putBoolean("synthetic", true).commit();
            com.eurobuddha.maxima.app.wallet.MaximaWallet.setUsesAtLeast(this, 37);
            runOnUiThread(() -> startActivity(new Intent().setClassName(this, screen)));
        }, "synthetic-wallet-identity").start();
    }

    private void install(Activity activity) {
        try {
            network = ((List<?>) field(activity,"mPages")).get(3);
            ((androidx.viewpager.widget.ViewPager)field(activity,"mPager")).setCurrentItem(3,false);
            if (getIntent().getBooleanExtra("tab_check", false))
                tabProbe = new LanguageTabProbe(activity, (androidx.viewpager.widget.ViewPager)field(activity,"mPager"));
            set(network,"mVerifying",true);
            set(network,"mLastNetSig","held-fixture-state");
            set(network,"mPublicIp","203.0.113.44");
            com.eurobuddha.maxima.app.ManualForward.set(this,false,"203.0.113.44",9536);
            ((EditText)field(network,"mNewRelay")).setText(DRAFT);
            ((EditText)field(network,"mNewRelay")).setSelection(2,5);
            List<View> before = WindowInspector.getGlobalWindowViews();
            switch(mode) {
                case "manage": invoke("manageHosts",new Class<?>[0]);break;
                case "pin": invoke("pinMls",new Class<?>[0]);break;
                case "manual": invoke("showManualForward",new Class<?>[0]);break;
                case "connect": invoke("connectHost",new Class<?>[]{String.class,boolean.class},"relay中文Русский{count}$1:9501",false);break;
                case "qr": invoke("shareRelayQr",new Class<?>[]{String.class},"relay.example.invalid:9501");break;
                case "diagnostics":
                    Method diagnostic=network.getClass().getDeclaredMethod("reachDetail",String.class);diagnostic.setAccessible(true);
                    String[] details={"asking the router for a public port…","can't determine your public IP (need internet)","conditions no longer met","lease renewal failed","listener not running","manual re-check","mapped a port but it was not reachable (staying Tier 1)","network changed","network changed during mapping (staying Tier 1)","network changed during verification (staying Tier 1)","not started","off (contribution disabled)","off (needs Wi-Fi - CGNAT makes cellular pointless)","off (needs charging to start)","periodic re-verification","ready","retrying","stopping","this network has no forwardable public port (staying Tier 1)","reachable at 203.0.113.44:9536 中文 Русский {count} $1 (manual forward)","verifying 203.0.113.44:9536 中文 Русский {count} $1 from outside…","reachable at 203.0.113.44:9536 中文 Русский {count} $1 via upnp","lease renewed for 203.0.113.44:9536 中文 Русский {count} $1","203.0.113.44:9536 中文 Русский {count} $1 not reachable from outside — behind CGNAT, or the forward rule must point at Parlons port 9536","unknown diagnostic 中文 {count} $1"};
                    for(String value:details){
                        TextView detail=new TextView(activity);Language.setText(detail,(CharSequence)diagnostic.invoke(null,value));
                        detail.setTextColor(activity.getColor(R.color.ux_text));detail.setTextSize(13);
                        ((android.widget.LinearLayout)field(network,"mContrib")).addView(detail);
                    }
                    break;
                case "counters":
                case "declined":
                    com.eurobuddha.maxima.core.services.Tier1Services services=new com.eurobuddha.maxima.core.services.Tier1Services(null,null,null);
                    String[] fields={"mMailHeld","mLookupsServed","mBlobsStored","mReceiptsSigned","mGossipLearned","mRefused"};
                    long[] counts={37,91,123,456,789,mode.equals("declined")?22:0};
                    for(int i=0;i<fields.length;i++)((java.util.concurrent.atomic.AtomicLong)field(services,fields[i])).set(counts[i]);
                    Method summary=network.getClass().getDeclaredMethod("contributionSummary",services.getClass());summary.setAccessible(true);
                    TextView counter=new TextView(activity);Language.setText(counter,(CharSequence)summary.invoke(null,services));
                    counter.setTextColor(activity.getColor(R.color.ux_text));counter.setTextSize(14);
                    ((android.widget.LinearLayout)field(network,"mContrib")).addView(counter);
                    break;
                case "overview":
                    invoke("renderHostList",new Class<?>[]{List.class},java.util.Arrays.asList("relay.example.invalid:9501"));
                    invoke("renderForward",new Class<?>[0]);
                    break;
                default: throw new IllegalArgumentException(mode);
            }
            for (View root : WindowInspector.getGlobalWindowViews()) if (!before.contains(root)) dialogRoot=root;
            if (dialogRoot==null) dialogRoot=((com.eurobuddha.maxima.app.ui.Page)network).view();
            collectInputs(dialogRoot, inputs);
            for (EditText input : inputs) {
                boolean number=(input.getInputType() & android.text.InputType.TYPE_MASK_CLASS)==android.text.InputType.TYPE_CLASS_NUMBER;
                String value=number ? "19536" : DRAFT;
                input.setText(value);input.setSelection(2,5);values.add(value);
            }
            dialogRoot.postDelayed(()->verify(),500);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void invoke(String name,Class<?>[] types,Object... args)throws Exception {
        Method method=network.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(network,args);
    }
    private static void set(Object target,String name,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }

    private void verify() {
        try {

            boolean same = created==1 && destroyed==0 && !first.isDestroyed()
                    && network==((List<?>)field(first,"mPages")).get(3)
                    && Boolean.TRUE.equals(field(network,"mVerifying"))
                    && "held-fixture-state".equals(field(network,"mLastNetSig"))
                    && DRAFT.contentEquals(((EditText)field(network,"mNewRelay")).getText())
                    && ((EditText)field(network,"mNewRelay")).getSelectionStart()==2
                    && ((EditText)field(network,"mNewRelay")).getSelectionEnd()==5
                    && !com.eurobuddha.maxima.app.ManualForward.enabled(this)
                    && "203.0.113.44".equals(com.eurobuddha.maxima.app.ManualForward.publicIp(this))
                    && com.eurobuddha.maxima.app.ManualForward.port(this)==9536
                    && com.eurobuddha.maxima.app.MaximaService.node()==null
                    && com.eurobuddha.maxima.app.MaximaService.jar()==null
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
            if (mode.equals("manage")) {
                int[] actions = new int[]{0, 0};
                measureActions(dialogRoot, actions);
                result.put("readable_actions", actions[0] > 0 && actions[1] == 0).put("action_count", actions[0]);
            }
            if (tabProbe != null) result.put("tabs", tabProbe.snapshot()).put("sequence", tabSequence);
            try (java.io.FileOutputStream out = openFileOutput("language-network-proof.json", MODE_PRIVATE)) {
                out.write(result.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private void measureActions(View view, int[] counts) {
        if (view instanceof TextView && view.isClickable()) {
            TextView text = (TextView)view;
            String label = text.getText().toString();
            if (label.equals("QR") || label.equals(Language.text(this, R.string.l10n_common_remove))
                    || label.equals(Language.text(this, R.string.l10n_native_network_connect))) {
                counts[0]++;
                android.text.Layout layout = text.getLayout();
                if (layout == null || layout.getLineCount() != 1 || layout.getEllipsisCount(0) != 0
                        || layout.getLineWidth(0) > text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1)
                    counts[1]++;
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
            measureActions(((ViewGroup)view).getChildAt(i), counts);
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
