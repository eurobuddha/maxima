package com.eurobuddha.maxima.app.i18n;

import android.app.Activity;
import android.app.Application;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.portal.CloudSession;
import com.eurobuddha.maxima.app.portal.ide.*;
import com.eurobuddha.maxima.app.portal.ide.terminal.TerminalView;
import com.eurobuddha.maxima.app.portal.ide.terminal.HistoryDB;
import com.eurobuddha.maxima.app.portal.ide.ide.ScriptsView;
import com.eurobuddha.maxima.app.portal.ide.ide.ScriptDB;
import com.google.android.material.tabs.TabLayout;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Reuses the node-wallet fixture lifecycle. Disposable unpaired emulator only; no real commands. */
// WindowInspector is available only on API 29+; this class is excluded from release builds.
@androidx.annotation.RequiresApi(29)
public final class LanguageIdePreviewActivity extends AppCompatActivity {
    private static final String DRAFT = "status 中文 Русский {count} $1";
    private static final String RAW = "{\"status\":true,\"message\":\"Saved 中文 Русский {count} $1\",\"amount\":\"1.234567890123456789\"}";
    private static final String SOURCE = "/* 中文 Русский {count} $1 */\nRETURN TRUE";
    private static final String ADDRESS = "0x1234567890abcdef01234567890abcdef";
    private LanguageTxnFixture transaction;
    private boolean txnMode() { return mode.startsWith("txn-"); }
    private ScriptsView scripts;
    private Object scriptAdapter;
    private String scriptRows;
    private IdeActivity ide;
    private TerminalAdapter adapter;
    private TerminalView terminal;
    private NodeApi fake;
    private NodeApi.Cb pending;
    private EditText input;
    private ViewPager pager;
    private View dialogRoot;
    private Object history, suggestions;
    private String mode, output;
    private boolean helpOutput() { return Set.of("terminal-help-result","terminal-help-list").contains(mode); }
    private int selected, commands, initialCommands, created, destroyed, highlighted;
    private String draft, historyRows, favoriteRows;
    private int caretStart, caretEnd;
    private final List<EditText> dialogInputs = new ArrayList<>();
    private boolean terminalMode() { return mode.startsWith("terminal-"); }
    private boolean scriptsMode() { return mode.startsWith("scripts-"); }
    private boolean selectedMode() { return !mode.startsWith("logs-") && !terminalMode() && !scriptsMode() && !txnMode(); }
    private boolean hasDialog() { return LanguageTxnFixture.hasDialog(mode) || Set.of("about","export","terminal-favorite","terminal-history",
            "terminal-help-dialog","terminal-favorites","terminal-block","terminal-select","terminal-warning",
            "scripts-templates","scripts-name","scripts-actions","scripts-delete","scripts-node","scripts-import").contains(mode); }
    private final List<View> original = new ArrayList<>();
    private Application.ActivityLifecycleCallbacks lifecycle;
    private BroadcastReceiver receiver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Window inspection fixture requires Android 10 or newer");
        if (!"ranchu".equals(android.os.Build.HARDWARE) || !CloudSession.account(this).isEmpty()
                || CloudSession.isPaired(this) || CloudSession.remoteOrNull() != null)
            throw new IllegalStateException("Disposable unpaired emulator required");
        java.net.URL.setURLStreamHandlerFactory(protocol -> ("http".equals(protocol) || "https".equals(protocol)) ? new java.net.URLStreamHandler() {
            protected java.net.URLConnection openConnection(java.net.URL url) throws java.io.IOException {
                throw new java.io.IOException("Network disabled in IDE fixture");
            }
        } : null);
        deleteDatabase("terminalide.db");
        deleteDatabase(ScriptDB.DATABASE_NAME);
        Language.select(this, "en");
        mode = getIntent().getStringExtra("mode");
        lifecycle = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { if (a instanceof IdeActivity) created++; }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {}
            public void onActivityPostResumed(Activity a) {
                if (!(a instanceof IdeActivity) || ide != null) return;
                ide = (IdeActivity)a;
                try { ((NodeApi)get(ide,"mNode")).onDestroy(); }
                catch (Exception e) { throw new IllegalStateException(e); }
                a.getWindow().getDecorView().post(() -> install());
            }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) { if (a instanceof IdeActivity) destroyed++; }
        };
        getApplication().registerActivityLifecycleCallbacks(lifecycle);
        receiver = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                String code = i.getStringExtra("code");
                if (ide == null || output == null || !Language.supported(code)) return;
                Language.select(ide,code);
                ide.getWindow().getDecorView().postDelayed(() -> verify(),1000);
            }
        };
        ContextCompat.registerReceiver(this,receiver,new IntentFilter(getPackageName()+".LANGUAGE_IDE_FIXTURE"),ContextCompat.RECEIVER_EXPORTED);
        startActivity(new Intent(this,IdeActivity.class));
    }

    private void install() {
        try {
            // AppCompat creates options after the first layout; wait for the actual menu.
            if (((Toolbar)get(ide,"mToolbar")).getMenu().findItem(2) == null) {
                ide.getWindow().getDecorView().postDelayed(this::install,100);
                return;
            }
            fake = new NodeApi(ide) {
                @Override public void cmd(String command, Cb cb) {
                    commands++;
                    if (txnMode()) { transaction.command(command,cb);return; }
                    if (mode.startsWith("terminal-help-") && command.startsWith("help")) {
                        try {
                            JSONObject response;
                            if ("terminal-help-list".equals(mode)) response=new JSONObject().put("txnoutput","server").put("status","server").put("unknown",RAW);
                            else response=new JSONObject().put("command","terminal-help-unknown".equals(mode)?"unknown":"txnoutput")
                                    .put("fullhelp",RAW).put("help",RAW);
                            JSONObject json=new JSONObject().put("status",true).put("response",response);
                            cb.onResult(json);
                            // Later mutation of the provider's object must not alter the saved output.
                            response.put("command","mutated").put("fullhelp","mutated");
                        } catch(Exception e) { throw new IllegalStateException(e); }
                        return;
                    }
                    if ("scripts".equals(command) && ("scripts-node".equals(mode) || "scripts-pending".equals(mode))) {
                        if ("scripts-pending".equals(mode)) { pending=cb;return; }
                        try { cb.onResult(new JSONObject().put("response",new JSONArray()
                                .put(new JSONObject().put("script",SOURCE).put("address",ADDRESS).put("default",true))
                                .put(JSONObject.NULL))); } catch(Exception e) { throw new IllegalStateException(e); }
                        return;
                    }
                    if (!"status".equals(command) || !"pending".equals(mode) && !"terminal-result".equals(mode)) throw new IllegalStateException("Unexpected command: "+command);
                    pending=cb;
                }
                @Override public void nodeLog(Cb cb) {
                    commands++;
                    try {
                        if ("logs-error".equals(mode)) cb.onError("RAW 中文 Русский {count} $1");
                        else cb.onResult(new JSONObject().put("lines",new JSONArray("logs-raw".equals(mode)?List.of(RAW,"Saved"):List.of())));
                    } catch(Exception e) { throw new IllegalStateException(e); }
                }
            };
            set(ide,"mNode",fake);
            adapter=(TerminalAdapter)get(ide,"mAdapter");terminal=adapter.getTerminalView();
            terminal.setNodeApi(fake);adapter.getScriptsView().setNodeApi(fake);
            adapter.getTxnView().setNodeApi(fake);adapter.getLogsView().setNodeApi(fake);
            invoke(ide,"setBlock",new Class<?>[]{String.class},"000123456789");
            ((LinearLayout)get(terminal,"mOutput")).removeAllViews();
            if ("terminal-banner".equals(mode)) terminal.clearTerminal();
            invoke(terminal,"appendEntry",new Class<?>[]{CharSequence.class},RAW);
            input=(EditText)get(terminal,"mInput");
            draft=("terminal-completion".equals(mode) || "terminal-help-params".equals(mode))?"txnoutput id:demo ":
                    "terminal-help-completion".equals(mode)?"help command:ba":DRAFT;
            caretStart=!DRAFT.equals(draft)?draft.length():7;
            caretEnd=!DRAFT.equals(draft)?draft.length():12;
            input.setText(draft);input.setSelection(caretStart,caretEnd);
            invoke(terminal,"moveSelection",new Class<?>[]{int.class},1);
            pager=(ViewPager)get(ide,"mMainPager");
            if (txnMode()) {
                transaction=new LanguageTxnFixture(ide,adapter.getTxnView(),mode);
                pager.setCurrentItem(2,false);
                transaction.setup();
            }
            else if (scriptsMode()) {
                scripts=adapter.getScriptsView();
                ScriptDB scriptDB=(ScriptDB)get(scripts,"mDB");
                if (!"scripts-empty".equals(mode)) {
                    long id=scriptDB.insert("Saved", SOURCE);
                    scriptDB.setAddress(id,ADDRESS);
                    scriptDB.saveTestInputs(id,"{\"0\":\"001.23456789\"}","{}","{\"@COINAGE\":17}","0x012345");
                }
                pager.setCurrentItem(1,false);
            }
            else if (mode.startsWith("logs-")) pager.setCurrentItem(3,false);
            else terminal.setSelectionMode(selectedMode());
            if ("pending".equals(mode) || "terminal-result".equals(mode)) terminal.runCMD("status");
            if ("terminal-result".equals(mode)) { pending.onResult(new JSONObject(RAW).put("status",false));pending=null; }
            HistoryDB db=(HistoryDB)get(terminal,"mHistory");
            db.addHistory(DRAFT);db.addFavorite("Saved",DRAFT);
            if (helpOutput() || "terminal-help-unknown".equals(mode))
                terminal.runCMD("terminal-help-list".equals(mode)?"help":"help command:"+("terminal-help-unknown".equals(mode)?"unknown":"txnoutput"));
            switch(mode) {
                case "terminal-help-dialog": invoke(terminal,"showHelpDialog",new Class<?>[]{String.class},"txnoutput");break;
                case "terminal-favorite": invoke(terminal,"saveFavorite",new Class<?>[0]);break;
                case "terminal-history": invoke(terminal,"showHistoryDialog",new Class<?>[0]);break;
                case "terminal-favorites": invoke(terminal,"showFavoritesDialog",new Class<?>[0]);break;
                case "terminal-warning": terminal.runCMD("history max:100");break;
                case "terminal-block": invoke(terminal,"showBlockMenu",new Class<?>[]{TextView.class},
                        ((LinearLayout)get(terminal,"mOutput")).getChildAt(0));break;
                case "terminal-select": invoke(terminal,"showSelectDialog",new Class<?>[]{CharSequence.class},RAW);break;
                case "scripts-templates": invoke(scripts,"newScriptDialog",new Class<?>[0]);break;
                case "scripts-name": invoke(scripts,"nameDialog",new Class<?>[]{int.class},0);break;
                case "scripts-actions": case "scripts-delete":
                    invoke(scripts,"scriptActionsDialog",new Class<?>[]{ScriptDB.Script.class},((ScriptDB)get(scripts,"mDB")).getAll().get(0));
                    if("scripts-delete".equals(mode)) {
                        List<View> windows=WindowInspector.getGlobalWindowViews();
                        ListView actions=list(windows.get(windows.size()-1));
                        actions.performItemClick(null,2,actions.getAdapter().getItemId(2));
                    }
                    break;
                case "scripts-node": case "scripts-pending": invoke(scripts,"showNodeScripts",new Class<?>[0]);break;
                case "scripts-import": invoke(scripts,"nodeScriptActions",new Class<?>[]{String.class,String.class},ADDRESS,SOURCE);break;
                default: break;
            }
            historyRows=new JSONArray(db.getHistory(100)).toString();
            favoriteRows=favoriteSnapshot(db);
            if ("export".equals(mode)) invoke(ide,"showExportDialog",new Class<?>[0]);
            if ("about".equals(mode)) {
                android.view.MenuItem item=((Toolbar)get(ide,"mToolbar")).getMenu().findItem(2);
                if (item == null) throw new IllegalStateException("Missing About menu");
                ide.onOptionsItemSelected(item);
            }
            set(ide,"mPendingExport",RAW+"\n"+DRAFT);
            // AlertDialog dispatches button actions through its Handler. Let the
            // fake command chain finish before taking the locale-switch baseline.
            if(txnMode()) ide.getWindow().getDecorView().postDelayed(this::snapshot,500);
            else snapshot();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }

    private void snapshot() {
        try {
            if(txnMode()) transaction.snapshot();
            history=get(terminal,"mHistory");suggestions=get(terminal,"mItems");
            output=terminal.exportText();selected=pager.getCurrentItem();initialCommands=commands;highlighted=(Integer)get(terminal,"mSel");
            if (!scriptsMode()) for (BaseView page:(BaseView[])get(adapter,"mAllViews")) collect(page.getMainView(),original,null);
            if (scriptsMode()) {
                scriptAdapter=((ListView)get(scripts,"mList")).getAdapter();
                scriptRows=scriptSnapshot((ScriptDB)get(scripts,"mDB"));
            }
            List<View> roots=WindowInspector.getGlobalWindowViews();
            dialogRoot=hasDialog()?roots.get(roots.size()-1):ide.getWindow().getDecorView();
            if ("terminal-favorite".equals(mode) || "scripts-name".equals(mode)) {
                inputs(dialogRoot,dialogInputs);
                if(dialogInputs.size()!=1)throw new IllegalStateException("Expected one favorite-name input");
                dialogInputs.get(0).setText(DRAFT);dialogInputs.get(0).setSelection(2,5);
            }
            ide.getWindow().getDecorView().postDelayed(() -> verify(),800);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }

    private void verify() {
        try {
            // setApplicationLocales is asynchronous. A fixed one-second delay is
            // insufficient on a busy emulator; inspect only after the Activity
            // has received the requested configuration. Harness timeout still
            // fails if that configuration never arrives.
            if (!Language.locale().equals(ide.getResources().getConfiguration().getLocales().get(0))) {
                ide.getWindow().getDecorView().postDelayed(this::verify,200);
                return;
            }
            List<View> current=new ArrayList<>();
            for(BaseView page:(BaseView[])get(adapter,"mAllViews"))collect(page.getMainView(),current,null);
            if (scriptsMode() && original.isEmpty()) original.addAll(current);
            String currentOutput=terminal.exportText();
            boolean sameOutput=helpOutput()
                    ? currentOutput.startsWith(RAW+"\n\n❯ help") && currentOutput.substring(currentOutput.lastIndexOf("⏱")).equals(output.substring(output.lastIndexOf("⏱")))
                    : "pending".equals(mode)
                    ? currentOutput.equals(RAW+"\n\n❯ status\n\n"+Language.msg(R.string.l10n_terminal_running)+"\n\n")
                    : "terminal-banner".equals(mode)
                    ? currentOutput.equals(Language.msg(R.string.l10n_terminal_banner)+"\n\n"+RAW+"\n\n")
                    : output.equals(currentOutput);
            HistoryDB db=(HistoryDB)history;
            boolean same=created==1 && destroyed==0 && original.equals(current) && commands==initialCommands
                    && get(ide,"mNode")==fake && get(terminal,"mHistory")==history && get(terminal,"mItems")==suggestions
                    && draft.contentEquals(input.getText()) && input.getSelectionStart()==caretStart && input.getSelectionEnd()==caretEnd
                    && highlighted==(Integer)get(terminal,"mSel") && historyRows.equals(new JSONArray(db.getHistory(100)).toString())
                    && favoriteRows.equals(favoriteSnapshot(db))
                    && sameOutput && (RAW+"\n"+DRAFT).equals(get(ide,"mPendingExport"))
                    && pager.getCurrentItem()==selected && terminal.isSelectionMode()==selectedMode()
                    && WindowInspector.getGlobalWindowViews().contains(dialogRoot)
                    && (!("pending".equals(mode)||"scripts-pending".equals(mode))||pending!=null);
            if(txnMode()) same &= transaction.same();
            if(scriptsMode()) same &= scriptAdapter==((ListView)get(scripts,"mList")).getAdapter()
                    && scriptRows.equals(scriptSnapshot((ScriptDB)get(scripts,"mDB")));
            if("terminal-favorite".equals(mode) || "scripts-name".equals(mode)) {
                List<EditText> now=new ArrayList<>();inputs(dialogRoot,now);
                same &= now.equals(dialogInputs) && DRAFT.contentEquals(now.get(0).getText())
                        && now.get(0).getSelectionStart()==2 && now.get(0).getSelectionEnd()==5;
            }
            JSONArray labels=new JSONArray();collect(ide.getWindow().getDecorView(),null,labels);
            if(dialogRoot!=ide.getWindow().getDecorView())collect(dialogRoot,null,labels);
            TabLayout tabs=ide.findViewById(R.id.tabs);JSONArray tabLabels=new JSONArray();
            for(int i=0;i<tabs.getTabCount();i++)tabLabels.put(tabs.getTabAt(i).getText().toString());
            JSONArray menu=new JSONArray();Menu items=((Toolbar)get(ide,"mToolbar")).getMenu();
            for(int i=0;i<items.size();i++)menu.put(new JSONObject().put("id",items.getItem(i).getItemId()).put("title",items.getItem(i).getTitle().toString()));
            JSONObject proof=new JSONObject().put("same_state",same).put("mode",mode).put("locale",Language.code())
                    .put("labels",labels).put("tabs",tabLabels).put("menu",menu).put("output",currentOutput).put("highlighted",highlighted)
                    .put("commands",commands).put("draft",input.getText()).put("pending",pending!=null).put("dialog_inputs",dialogInputs.size());
            if(txnMode()) proof.put("transaction",transaction.proof());
            try(java.io.FileOutputStream out=openFileOutput("language-ide-proof.json",MODE_PRIVATE)) {
                out.write(proof.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static ListView list(View root) {
        if(root instanceof ListView)return (ListView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            ListView found=list(((ViewGroup)root).getChildAt(i));if(found!=null)return found;
        }
        return null;
    }
    private static String scriptSnapshot(ScriptDB db) throws Exception {
        JSONArray all=new JSONArray();
        for(ScriptDB.Script s:db.getAll())all.put(new JSONObject().put("id",s.id).put("name",s.name)
                .put("source",s.source).put("address",s.address).put("modified",s.modified)
                .put("state",s.testState).put("prevstate",s.testPrevState).put("globals",s.testGlobals).put("signatures",s.testSignatures));
        return all.toString();
    }
    private static String favoriteSnapshot(HistoryDB db) {
        JSONArray rows=new JSONArray();for(String[] row:db.getFavorites())rows.put(new JSONArray(Arrays.asList(row)));
        return rows.toString();
    }
    private static void inputs(View view,List<EditText> out) {
        if(view instanceof EditText)out.add((EditText)view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)inputs(((ViewGroup)view).getChildAt(i),out);
    }
    private static Field field(Object o,String name)throws Exception { Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f; }
    private static Object get(Object o,String name)throws Exception { return field(o,name).get(o); }
    private static void set(Object o,String name,Object value)throws Exception { field(o,name).set(o,value); }
    private static Object invoke(Object o,String name,Class<?>[] types,Object...args)throws Exception { Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args); }
    private static void collect(View v,List<View> views,JSONArray labels) {
        if(views!=null)views.add(v);
        if(labels!=null && v instanceof TextView) { TextView t=(TextView)v;if(t.length()>0)labels.put(t.getText().toString()); }
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),views,labels);
    }
    @Override protected void onDestroy() {
        if(receiver!=null)unregisterReceiver(receiver);
        if(lifecycle!=null)getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        super.onDestroy();
    }
}
