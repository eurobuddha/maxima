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

/** Debug-only real Cloud Node UI with synthetic status. No paired account or remote actions. API 29+. */
// WindowInspector is available only on API 29+; this class is excluded from release builds.
@androidx.annotation.RequiresApi(29)
public final class LanguageNodePreviewActivity extends AppCompatActivity {
    private static final String DRAFT = "Draft 中文 Привет {count} $1";
    private String screen, mode;
    private Activity first;
    private LanguageTabProbe tabProbe;
    private int tabSequence;
    private Object node, device;
    private Object authorized, pending;
    private Object figures, status, log, mls;
    private String rawFigures, rawStatus, rawLog, rawMls;
    private final List<View> panelViews=new ArrayList<>();
    private boolean panelMode() { return mode.startsWith("panel-"); }
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
        if (!com.eurobuddha.maxima.app.portal.CloudSession.account(this).isEmpty()
                || com.eurobuddha.maxima.app.portal.CloudSession.isPaired(this)
                || com.eurobuddha.maxima.app.portal.CloudSession.remoteOrNull()!=null) throw new IllegalStateException("Empty fixture account required");
        java.net.URL.setURLStreamHandlerFactory(protocol -> ("http".equals(protocol) || "https".equals(protocol)) ? new java.net.URLStreamHandler() {
            protected java.net.URLConnection openConnection(java.net.URL url) throws java.io.IOException {
                throw new java.io.IOException("Network disabled in node language fixture");
            }
        } : null);
        Language.select(this, "en");
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "overview";
        screen = panelMode() ? "com.eurobuddha.maxima.app.portal.CloudNodePanelActivity" : "com.eurobuddha.maxima.app.MainActivity";
        if (!panelMode()) {
        service = new android.content.ComponentName(this, com.eurobuddha.maxima.app.portal.PortalService.class);
        getPackageManager().setComponentEnabledSetting(service, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP);
        com.eurobuddha.maxima.app.portal.CloudSession.prefs(this).edit().putBoolean("paired",true).commit();
        }
        callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivityPreCreated(Activity a, Bundle b) {
                if(panelMode() && a.getClass().getName().equals(screen))try { set(a,"mBusy",true); }
                catch(Exception e) { throw new IllegalStateException(e); }
            }
            public void onActivityCreated(Activity a, Bundle b) {
                if (!a.getClass().getName().equals(screen)) return;
                created++;
            }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {}
            public void onActivityPostResumed(Activity a) {
                if (!a.getClass().getName().equals(screen) || first != null) return;
                first = a;
                if (!panelMode()) {
                // MainActivity's pairing guard and onResume must finish before cleanup.
                com.eurobuddha.maxima.app.portal.CloudSession.prefs(a).edit().putBoolean("paired",false).commit();
                getPackageManager().setComponentEnabledSetting(service, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, android.content.pm.PackageManager.DONT_KILL_APP);
                // Stop fixture polling before its posted callback runs. No account is paired.
                try {
                    Handler handler = (Handler) field(a, "mHandler");
                    handler.removeCallbacks((Runnable) field(a, "mTick"));
                    set(a,"mPolling",true);
                } catch (Exception e) { throw new IllegalStateException(e); }
                }
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
                new IntentFilter(getPackageName()+".LANGUAGE_NODE_FIXTURE"), ContextCompat.RECEIVER_EXPORTED);
        startActivity(new Intent().setClassName(this, screen));
    }

    private void install(Activity activity) {
        try {
            if(panelMode()) { installPanel(activity);return; }
            node = ((List<?>) field(activity,"mPages")).get(3);
            set(node,"mBusy",true);
            set(node,"mName","Owner 中文 Русский {count} $1");
            set(node,"mPermanent","MAX#FixtureLiteral中文Русский{count}$1");
            set(node,"mVersion","0.11.117"); set(node,"mUptime",183840000L);
            set(node,"mHosts",3); set(node,"mMeshPeers",2);
            set(node,"mStatusOk",true); set(node,"mMailboxHeld",true); set(node,"mRelayOn",true);
            set(node,"mNodeConsole",true); set(node,"mHosting",true); set(node,"mAdminPassword",true);
            java.lang.reflect.Constructor<?> ctor=Class.forName(node.getClass().getName()+"$Dev").getDeclaredConstructor();
            ctor.setAccessible(true);device=ctor.newInstance();
            set(device,"key","FixtureKey中文Русский");set(device,"label","Device 中文 Русский {count} $1");
            authorized=field(node,"mAuthorized"); ((List)authorized).add(device);
            pending=field(node,"mPending"); ((List)pending).add("PendingKey中文Русский");
            invoke("rebuild",new Class<?>[0]);
            ((androidx.viewpager.widget.ViewPager)field(activity,"mPager")).setCurrentItem(3,false);
            if (getIntent().getBooleanExtra("tab_check", false))
                tabProbe = new LanguageTabProbe(activity, (androidx.viewpager.widget.ViewPager)field(activity,"mPager"));
            List<View> before = WindowInspector.getGlobalWindowViews();
            switch(mode) {
                case "name": invoke("promptSetName",new Class<?>[0]);break;
                case "revoke": invoke("confirmRevoke",new Class<?>[]{device.getClass()},device);break;
                case "admin": invoke("newCode",new Class<?>[0]);break;
                case "qr": invoke("showQr",new Class<?>[]{String.class},"MAX#FixtureLiteral中文Русский{count}$1");break;
                case "overview":break;
                case "error":invoke("showUnavailable",new Class<?>[]{String.class},"Node 中文 Русский {count} $1");break;
                default:
                    if (!mode.startsWith("hosting"))throw new IllegalArgumentException(mode);
                    org.minima.utils.json.JSONObject reply=new org.minima.utils.json.JSONObject();
                    reply.put("online",true);reply.put("managed",true);reply.put("enabled",true);
                    reply.put("access","chat-only");reply.put("limit",5);
                    org.minima.utils.json.JSONObject row=new org.minima.utils.json.JSONObject();
                    row.put("name","running");row.put("state","running");row.put("paused",false);
                    org.minima.utils.json.JSONArray rows=new org.minima.utils.json.JSONArray();rows.add(row);reply.put("accounts",rows);
                    invoke("showHostedAccounts",new Class<?>[]{org.minima.utils.json.JSONObject.class},reply);
                    if(mode.equals("hosting-create"))latest().findViewById(android.R.id.button1).performClick();
                    if(mode.equals("hosting-options"))latest().findViewById(android.R.id.button3).performClick();
                    if(mode.equals("hosting-actions") || mode.equals("hosting-pause")) {
                        clickItem(latest(),0);
                        if(mode.equals("hosting-pause"))clickItem(latest(),1);
                    }
            }
            activity.getWindow().getDecorView().postDelayed(() -> {
                for (View root : WindowInspector.getGlobalWindowViews())
                    if (!before.contains(root) && root.isAttachedToWindow()) dialogRoot=root;
                if (dialogRoot==null) dialogRoot=((com.eurobuddha.maxima.app.ui.Page)node).view();
                collectInputs(dialogRoot, inputs);
                for (EditText input : inputs) {
                    input.setText(DRAFT);input.setSelection(2,5);values.add(DRAFT);
                }
                dialogRoot.postDelayed(()->verify(),500);
            },200);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void invoke(String name,Class<?>[] types,Object... args)throws Exception {
        Method method=node.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(node,args);
    }
    private static void set(Object target,String name,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }

    private void verify() {
        try {
            if(panelMode()) { verifyPanel();return; }
            boolean same = created==1 && destroyed==0 && !first.isDestroyed()
                    && node==((List<?>)field(first,"mPages")).get(3)
                    && Boolean.TRUE.equals(field(node,"mBusy"))
                    && authorized==field(node,"mAuthorized") && pending==field(node,"mPending")
                    && ((List<?>)authorized).size()==1 && ((List<?>)authorized).get(0)==device
                    && ((List<?>)pending).equals(java.util.Collections.singletonList("PendingKey中文Русский"))
                    && "Owner 中文 Русский {count} $1".equals(field(node,"mName"))
                    && "MAX#FixtureLiteral中文Русский{count}$1".equals(field(node,"mPermanent"))
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
            if (tabProbe != null) result.put("tabs", tabProbe.snapshot()).put("sequence", tabSequence);
            try (java.io.FileOutputStream out = openFileOutput("language-node-proof.json", MODE_PRIVATE)) {
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
    private void installPanel(Activity activity)throws Exception {
        node=activity;
        org.minima.utils.json.JSONObject fig=new org.minima.utils.json.JSONObject();
        fig.put("contacts",23);fig.put("mailboxHeld",7);fig.put("outbox",3);
        fig.put("relayConnections",4);fig.put("relayRelayed",123456789L);fig.put("relayStored",42);
        org.minima.utils.json.JSONArray mesh=new org.minima.utils.json.JSONArray();mesh.add("Peer中文Русский");fig.put("meshPeers",mesh);
        if(mode.equals("panel-direct")) { fig.put("directlyReachable",true);fig.put("directAddress","203.0.113.7:9536"); }
        else { fig.put("ownRelay","relay.example:9501");fig.put("ownRelayVerified",true);fig.put("ownRelayAttached",true); }
        fig.put("builtin",true);
        org.minima.utils.json.JSONObject host=new org.minima.utils.json.JSONObject();host.put("host","relay.example:9501");host.put("connected",true);
        org.minima.utils.json.JSONArray hosts=new org.minima.utils.json.JSONArray();hosts.add(host);fig.put("hosts",hosts);
        org.minima.utils.json.JSONObject st=new org.minima.utils.json.JSONObject();
        st.put("version","0.11.117");st.put("uptime",183840000L);st.put("permanent","MAX#FixtureLiteral中文Русский{count}$1");
        org.minima.utils.json.JSONArray events=new org.minima.utils.json.JSONArray();events.add("12:34 Node 中文 Русский {count} $1");
        org.minima.utils.json.JSONObject location=new org.minima.utils.json.JSONObject();
        location.put("pinned",mode.equals("panel-pinned"));location.put("permanent","MAX#FixtureLiteral中文Русский{count}$1");
        figures=fig;status=st;log=events;mls=location;
        rawFigures=fig.toString();rawStatus=st.toString();rawLog=events.toString();rawMls=location.toString();
        set(node,"mFigures",fig);set(node,"mStatus",st);set(node,"mLog",events);set(node,"mMls",location);
        invoke("rebuild",new Class<?>[0]);
        dialogRoot=activity.getWindow().getDecorView();
        collectInputs(dialogRoot,inputs);
        for(EditText input:inputs) { input.setText(DRAFT);input.setSelection(2,5);values.add(DRAFT); }
        dialogRoot.postDelayed(()-> { collectViews(dialogRoot,panelViews);verify(); },500);
    }
    private void verifyPanel()throws Exception {
        List<View> current=new ArrayList<>();collectViews(dialogRoot,current);
        boolean same=created==1 && destroyed==0 && !first.isDestroyed() && current.equals(panelViews)
                && Boolean.TRUE.equals(field(first,"mBusy"))
                && figures==field(first,"mFigures") && status==field(first,"mStatus")
                && log==field(first,"mLog") && mls==field(first,"mMls")
                && rawFigures.equals(figures.toString()) && rawStatus.equals(status.toString())
                && rawLog.equals(log.toString()) && rawMls.equals(mls.toString())
                && !com.eurobuddha.maxima.app.portal.CloudSession.isPaired(this)
                && com.eurobuddha.maxima.app.portal.CloudSession.remoteOrNull()==null;
        for(EditText input:inputs)same &= DRAFT.contentEquals(input.getText()) && input.getSelectionStart()==2 && input.getSelectionEnd()==5;
        JSONArray labels=new JSONArray();collectLabels(dialogRoot,labels);
        JSONObject result=new JSONObject().put("same_views_and_drafts",same).put("locale",Language.code()).put("mode",mode)
                .put("labels",labels).put("created",created).put("destroyed",destroyed).put("input_count",inputs.size());
        try(java.io.FileOutputStream out=openFileOutput("language-node-proof.json",MODE_PRIVATE)) { out.write(result.toString().getBytes(StandardCharsets.UTF_8)); }
    }
    private static void collectViews(View view,List<View> out) {
        out.add(view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectViews(((ViewGroup)view).getChildAt(i),out);
    }
    private static View latest() {
        List<View> roots=WindowInspector.getGlobalWindowViews();return roots.get(roots.size()-1);
    }
    private static android.widget.ListView list(View view) {
        if(view instanceof android.widget.ListView)return (android.widget.ListView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            android.widget.ListView found=list(((ViewGroup)view).getChildAt(i));if(found!=null)return found;
        }
        return null;
    }
    private static void clickItem(View root,int position) {
        android.widget.ListView list=list(root);
        list.performItemClick(list.getAdapter().getView(position,null,list),position,list.getAdapter().getItemId(position));
    }
    @Override protected void onDestroy() {
        if (receiver != null) unregisterReceiver(receiver);
        if (callbacks != null) getApplication().unregisterActivityLifecycleCallbacks(callbacks);
        super.onDestroy();
    }
}
