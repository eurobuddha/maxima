package com.eurobuddha.maxima.app.i18n;

import android.app.Activity;
import android.app.Application;
import android.content.*;
import android.os.Bundle;
import android.text.Editable;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.portal.CloudSession;
import com.eurobuddha.maxima.app.portal.ide.NodeApi;
import com.eurobuddha.maxima.app.portal.ide.ide.ScriptDB;
import com.eurobuddha.maxima.app.portal.ide.ide.ScriptEditorActivity;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Uses the IDE fixture lifecycle and real editor with a fake node on the disposable emulator only. */
// WindowInspector is available only on API 29+; this class is excluded from release builds.
@androidx.annotation.RequiresApi(29)
public final class LanguageScriptPreviewActivity extends AppCompatActivity {
    private static final String NAME="Saved 中文 Русский {name} $1";
    private static final String RAW="RAW 中文 Русский {value} $1";
    private static final String SOURCE="/* 中文 Русский {count} $1 */\nRETURN TRUE\n/* SI */";
    private static final String ADDRESS="0x000123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private ScriptEditorActivity editor;
    private NodeApi fake;
    private NodeApi.Cb pending;
    private Object lintSnapshot;
    private Editable editable;
    private String mode, dbSnapshot, spanSnapshot;
    private long scriptId;
    private int created,destroyed,commandsAtSnapshot,resultColor,configVisibility;
    private boolean initialTrackAllFalse, bottom;
    private View dialogRoot;
    private CheckBox trackAll;
    private final List<View> original=new ArrayList<>();
    private final List<EditText> fields=new ArrayList<>();
    private final List<String> drafts=new ArrayList<>();
    private final List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();
    private final List<String> commands=new ArrayList<>();
    private Application.ActivityLifecycleCallbacks lifecycle;
    private BroadcastReceiver receiver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Window inspection fixture requires Android 10 or newer");
        if(!"ranchu".equals(android.os.Build.HARDWARE)||!CloudSession.account(this).isEmpty()
                ||CloudSession.isPaired(this)||CloudSession.remoteOrNull()!=null)
            throw new IllegalStateException("Disposable unpaired emulator required");
        java.net.URL.setURLStreamHandlerFactory(protocol -> ("http".equals(protocol)||"https".equals(protocol)) ? new java.net.URLStreamHandler() {
            protected java.net.URLConnection openConnection(java.net.URL url)throws java.io.IOException {
                throw new java.io.IOException("Network disabled in script fixture");
            }
        }:null);
        Language.select(this,"en");
        mode=getIntent().getStringExtra("mode");
        deleteDatabase(ScriptDB.DATABASE_NAME);
        ScriptDB db=new ScriptDB(this);
        try {
            scriptId=db.insert(NAME,SOURCE);
            db.setAddress(scriptId,ADDRESS);
            db.saveTestInputs(scriptId,"{\"0\":\"0x00\",\"6\":\"001.234567890123456789\"}","{\"0\":\"0xFF\"}",
                    "{\"@COINAGE\":\"64\",\"@AMOUNT\":\"1.234567890123456789\"}","[\"0xAABB\"]");
        } finally { db.close(); }
        lifecycle=new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a,Bundle b){if(a instanceof ScriptEditorActivity)created++;}
            public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){}
            public void onActivityPostResumed(Activity a){
                if(!(a instanceof ScriptEditorActivity)||editor!=null)return;
                editor=(ScriptEditorActivity)a;
                try{((NodeApi)get(editor,"mNode")).onDestroy();}catch(Exception e){throw new IllegalStateException(e);}
                a.getWindow().getDecorView().post(LanguageScriptPreviewActivity.this::install);
            }
            public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}
            public void onActivityDestroyed(Activity a){if(a instanceof ScriptEditorActivity)destroyed++;}
        };
        getApplication().registerActivityLifecycleCallbacks(lifecycle);
        receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
            if(editor==null||dbSnapshot==null)return;
            if(i.getBooleanExtra("bottom",false)){
                ScrollView scroll=findScroll(dialogRoot==null?editor.getWindow().getDecorView():dialogRoot);
                if(scroll==null)throw new IllegalStateException("No scroll area for bottom fixture");
                scroll.fullScroll(View.FOCUS_DOWN);bottom=true;
            }else{
                String code=i.getStringExtra("code");if(!Language.supported(code))return;
                Language.select(editor,code);
            }
            editor.getWindow().getDecorView().postDelayed(LanguageScriptPreviewActivity.this::verify,500);
        }};
        ContextCompat.registerReceiver(this,receiver,new IntentFilter(getPackageName()+".LANGUAGE_SCRIPT_FIXTURE"),ContextCompat.RECEIVER_EXPORTED);
        startActivity(new Intent(this,ScriptEditorActivity.class).putExtra("script_id",scriptId));
    }
    private void install(){
        try{
            // Same AppCompat menu readiness check as LanguageIdePreviewActivity.
            if (((Toolbar)editor.findViewById(R.id.editor_toolbar)).getMenu().findItem(1)==null) {
                editor.getWindow().getDecorView().postDelayed(this::install,100);return;
            }
            fake=new NodeApi(editor){@Override public void cmd(String command,Cb cb){
                if(!Set.of("check-pending","run-pending","run-result","parse-failed","deploy-pending","deployed").contains(mode))
                    throw new IllegalStateException("Unexpected command in "+mode+": "+command);
                commands.add(command);pending=cb;
            }};
            set(editor,"mNode",fake);
            EditText source=(EditText)get(editor,"mEditor");
            if("lint".equals(mode))source.setText("/* 中文 Русский */\nLET bad_name = 1\nIF TRUE THEN RETURN @BLKNUM GT 1");
            if("invalid-json".equals(mode))((EditText)get(editor,"mState")).setText("{");
            if("unsafe-json".equals(mode))((EditText)get(editor,"mState")).setText("{\"0\":\"has space;send\"}");
            for(String name:new String[]{"mName","mEditor","mState","mPrevState","mGlobals","mSignatures"}){
                EditText field=(EditText)get(editor,name);fields.add(field);
                if(field==source){int pos=field.getText().toString().lastIndexOf("SI");field.setSelection(pos>=0?pos+2:4);}
                else field.setSelection(Math.min(1,field.length()),Math.min(3,field.length()));
            }
            invoke(editor,"updateEditorChips",new Class<?>[0]);
            switch(mode){
                case "reference":
                    if (!((Toolbar)editor.findViewById(R.id.editor_toolbar)).getMenu().performIdentifierAction(1,0))
                        throw new IllegalStateException("Reference menu action did not open");
                    break;
                case "lint":invoke(editor,"showLints",new Class<?>[0]);break;
                case "deploy":invoke(editor,"deployDialog",new Class<?>[]{ScriptDB.Script.class},((ScriptDB)get(editor,"mDB")).get(scriptId));break;
                case "deploy-pending":case "deployed":invoke(editor,"deploy",new Class<?>[]{String.class,boolean.class},"RETURN TRUE",true);break;
                case "check-pending":invoke(editor,"check",new Class<?>[0]);break;
                case "run-pending":case "run-result":case "parse-failed":case "invalid-json":case "unsafe-json":invoke(editor,"run",new Class<?>[0]);break;
                case "node-error":invoke(editor,"showNodeError",new Class<?>[]{String.class},RAW);break;
                default:break;
            }
            if("deployed".equals(mode)){pending.onResult(new JSONObject().put("status",true).put("response",new JSONObject().put("address",ADDRESS)));pending=null;}
            if("run-result".equals(mode)||"parse-failed".equals(mode)){
                boolean ok="run-result".equals(mode);
                pending.onResult(new JSONObject().put("status",true).put("response",new JSONObject()
                        .put("parseok",ok).put("success",ok).put("monotonic",false)
                        .put("clean",new JSONObject().put("address",ADDRESS).put("script","RETURN TRUE"))
                        .put("variables",new JSONObject().put("amount","001.234567890123456789").put("note",RAW))
                        .put("trace",RAW+"\\nRETURN TRUE")));pending=null;
            }
            if("deploy".equals(mode)||"lint".equals(mode)||"reference".equals(mode)){
                List<View> roots=WindowInspector.getGlobalWindowViews();dialogRoot=roots.get(roots.size()-1);
                if("deploy".equals(mode)){
                    trackAll=dialogRoot.findViewById(R.id.deploy_trackall);
                    initialTrackAllFalse=!trackAll.isChecked();trackAll.setChecked(true);
                }
            }
            editor.getWindow().getDecorView().postDelayed(this::snapshot,600);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private void snapshot(){
        try{
            collect(editor.findViewById(R.id.editor_root),original,null);
            for(EditText field:fields){drafts.add(field.getText().toString());starts.add(field.getSelectionStart());ends.add(field.getSelectionEnd());}
            editable=((EditText)get(editor,"mEditor")).getText();spanSnapshot=spans(editable);
            lintSnapshot=get(editor,"mLints");commandsAtSnapshot=commands.size();
            dbSnapshot=record(((ScriptDB)get(editor,"mDB")).get(scriptId));
            resultColor=((TextView)get(editor,"mResults")).getCurrentTextColor();
            configVisibility=((View)get(editor,"mRunConfig")).getVisibility();
            if(dialogRoot==null&&!"form".equals(mode))findScroll(editor.findViewById(R.id.editor_root)).fullScroll(View.FOCUS_DOWN);
            editor.getWindow().getDecorView().postDelayed(this::verify,300);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private void verify(){
        try{
            if(!Language.locale().equals(editor.getResources().getConfiguration().getLocales().get(0))){
                editor.getWindow().getDecorView().postDelayed(this::verify,200);return;
            }
            List<View> current=new ArrayList<>();collect(editor.findViewById(R.id.editor_root),current,null);
            boolean same=created==1&&destroyed==0&&original.equals(current)&&get(editor,"mNode")==fake
                    &&commands.size()==commandsAtSnapshot&&get(editor,"mLints")==lintSnapshot
                    &&((EditText)get(editor,"mEditor")).getText()==editable&&spanSnapshot.equals(spans(editable))
                    &&dbSnapshot.equals(record(((ScriptDB)get(editor,"mDB")).get(scriptId)))
                    &&((TextView)get(editor,"mResults")).getCurrentTextColor()==resultColor
                    &&((View)get(editor,"mRunConfig")).getVisibility()==configVisibility
                    &&(!mode.endsWith("-pending")||pending!=null);
            for(int i=0;i<fields.size();i++)same &= drafts.get(i).contentEquals(fields.get(i).getText())
                    &&starts.get(i)==fields.get(i).getSelectionStart()&&ends.get(i)==fields.get(i).getSelectionEnd();
            if(dialogRoot!=null)same &= WindowInspector.getGlobalWindowViews().contains(dialogRoot);
            if(trackAll!=null)same &= initialTrackAllFalse&&trackAll.isChecked();
            JSONArray labels=new JSONArray();collect(editor.getWindow().getDecorView(),null,labels);
            if(dialogRoot!=null)collect(dialogRoot,null,labels);
            Toolbar toolbar=editor.findViewById(R.id.editor_toolbar);JSONArray menu=new JSONArray();
            for(int i=0;i<toolbar.getMenu().size();i++)menu.put(new JSONObject().put("id",toolbar.getMenu().getItem(i).getItemId()).put("title",toolbar.getMenu().getItem(i).getTitle()));
            JSONObject proof=new JSONObject().put("mode",mode).put("locale",Language.code()).put("same_state",same)
                    .put("labels",labels).put("menu",menu).put("drafts",new JSONArray(drafts)).put("commands",new JSONArray(commands))
                    .put("pending",pending!=null).put("record",dbSnapshot).put("result",((TextView)get(editor,"mResults")).getText())
                    .put("bottom",bottom).put("trackall",trackAll!=null&&trackAll.isChecked()).put("trackall_default_false",initialTrackAllFalse);
            try(java.io.FileOutputStream out=openFileOutput("language-script-proof.json",MODE_PRIVATE)){out.write(proof.toString().getBytes(StandardCharsets.UTF_8));}
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static String record(ScriptDB.Script s)throws Exception{return new JSONObject().put("id",s.id).put("name",s.name).put("source",s.source).put("modified",s.modified).put("address",s.address).put("state",s.testState).put("prev",s.testPrevState).put("globals",s.testGlobals).put("signatures",s.testSignatures).toString();}
    private static String spans(Editable text)throws Exception{JSONArray all=new JSONArray();for(ForegroundColorSpan s:text.getSpans(0,text.length(),ForegroundColorSpan.class))all.put(new JSONArray().put(text.getSpanStart(s)).put(text.getSpanEnd(s)).put(s.getForegroundColor()));return all.toString();}
    private static ScrollView findScroll(View v){if(v instanceof ScrollView)return (ScrollView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ScrollView found=findScroll(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
    private static Field field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f;}
    private static Object get(Object o,String n)throws Exception{return field(o,n).get(o);}
    private static void set(Object o,String n,Object v)throws Exception{field(o,n).set(o,v);}
    private static Object invoke(Object o,String n,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}
    private static void collect(View v,List<View> views,JSONArray labels){if(views!=null)views.add(v);if(labels!=null&&v instanceof TextView){TextView t=(TextView)v;if(t.length()>0)labels.put(t.getText().toString());}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),views,labels);}
    @Override protected void onDestroy(){if(receiver!=null)unregisterReceiver(receiver);if(lifecycle!=null)getApplication().unregisterActivityLifecycleCallbacks(lifecycle);super.onDestroy();}
}
