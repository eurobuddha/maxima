package com.eurobuddha.maxima.app.i18n;

import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.*;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.portal.ide.IdeActivity;
import com.eurobuddha.maxima.app.portal.ide.NodeApi;
import com.eurobuddha.maxima.app.portal.ide.txn.TxnView;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Transaction cases inside the existing disposable, network-disabled IDE fixture. */
// WindowInspector is available only on API 29+; this class is excluded from release builds.
@androidx.annotation.RequiresApi(29)
final class LanguageTxnFixture {
    static final String ID="txn中文Русский_{count}$1";
    static final String AMOUNT="001.234567890123456789";
    static final String RAW="RAW 中文 Русский {field} $1";
    static final String ADDRESS="0x0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final IdeActivity activity;
    private final TxnView txn;
    private final String mode;
    private final List<String> commands=new ArrayList<>();
    private final List<EditText> inputs=new ArrayList<>();
    private final List<String> drafts=new ArrayList<>();
    private final List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();
    private final List<View> dialogViews=new ArrayList<>();
    private View dialog;
    private CheckBox store;
    private Object node;
    private NodeApi.Cb pending, originalPending;
    private int initialCommands, statusColor, summaryColor;
    private boolean guard, storeDefaultFalse;
    private String clipboard;
    LanguageTxnFixture(IdeActivity activity,TxnView txn,String mode){this.activity=activity;this.txn=txn;this.mode=mode;}
    static boolean hasDialog(String mode){return Set.of("txn-output","txn-state","txn-sign","txn-post","txn-import","txn-delete","txn-coins","txn-change").contains(mode);}
    void command(String command,NodeApi.Cb cb){
        commands.add(command);
        try {
            if("txn-change-pending".equals(mode)&&command.equals("txncheck id:"+ID)){pending=cb;return;}
            if("txn-command-error".equals(mode)&&command.equals("txncreate id:"+ID)){cb.onResult(new JSONObject().put("status",false).put("error",RAW));return;}
            if("txn-export".equals(mode)&&command.equals("txnexport id:"+ID)){
                cb.onResult(new JSONObject().put("response",new JSONObject().put("data","0x0000123456789abcdef")));return;
            }
            if("txn-coins".equals(mode)&&command.equals("coins relevant:true sendable:true simplestate:true")){
                cb.onResult(new JSONObject().put("response",new JSONArray().put(new JSONObject().put("coinid",ADDRESS).put("tokenid","0x00").put("tokenamount",AMOUNT))));return;
            }
            if(Set.of("txn-summary","txn-change","txn-change-failed").contains(mode)&&command.equals("txncheck id:"+ID)){
                JSONArray coins=new JSONArray().put(new JSONObject().put("tokenid","0x00").put("input","002.234567890123456789").put("output","1").put("difference",AMOUNT));
                if("txn-change-failed".equals(mode))coins.put(new JSONObject().put("tokenid",ADDRESS).put("difference","2.000000000000000001"));
                cb.onResult(new JSONObject().put("status",true).put("response",new JSONObject().put("coins",coins).put("burn","0.000000000000000001")
                        .put("valid",new JSONObject().put("basic",true).put("signatures",false).put("mmrproofs",true).put("scripts",false))));return;
            }
            if("txn-change-failed".equals(mode)){
                if(command.equals("getaddress")){cb.onResult(new JSONObject().put("response",new JSONObject().put("miniaddress",ADDRESS)));return;}
                if(command.equals("txnoutput id:"+ID+" amount:"+AMOUNT+" address:"+ADDRESS+" storestate:false")){cb.onResult(new JSONObject().put("status",true));return;}
                if(command.equals("txnoutput id:"+ID+" amount:2.000000000000000001 address:"+ADDRESS+" tokenid:"+ADDRESS+" storestate:false")){cb.onResult(new JSONObject().put("status",false).put("error",RAW));return;}
            }
            if("txn-list".equals(mode)&&command.equals("txnlist")){
                cb.onResult(new JSONObject().put("response",new JSONArray().put(new JSONObject().put("id",ID).put("transaction",new JSONObject()
                        .put("inputs",new JSONArray().put(new JSONObject())).put("outputs",new JSONArray().put(new JSONObject()).put(new JSONObject()))))));return;
            }
            throw new IllegalStateException("Unexpected fake transaction command: "+command);
        }catch(JSONException e){throw new IllegalStateException(e);}
    }
    void setup()throws Exception{
        EditText id=(EditText)get(txn,"mTxnId");id.setText("txn-empty".equals(mode)?"":ID);id.setSelection(Math.min(2,id.length()),Math.min(5,id.length()));
        String action=null;
        switch(mode){
            case "txn-output":action="outputDialog";break;case "txn-state":action="stateDialog";break;
            case "txn-sign":action="signDialog";break;case "txn-post":action="postDialog";break;
            case "txn-import":action="importDialog";break;case "txn-delete":action="deleteDialog";break;
            case "txn-coins":action="pickCoin";break;
            case "txn-change":case "txn-change-pending":case "txn-change-failed":action="addChange";break;
            case "txn-summary":action="refresh";break;case "txn-list":action="listAll";break;case "txn-export":action="exportTxn";break;
            case "txn-command-error":action="create";break;default:break;
        }
        if(action!=null)invoke(txn,action,new Class<?>[0]);
        if("txn-node-error".equals(mode))invoke(txn,"nodeError",new Class<?>[]{String.class},RAW);
        if("txn-change-failed".equals(mode)){
            View root=top();Button add=root.findViewById(android.R.id.button1);
            if(add==null)throw new IllegalStateException("Change confirmation missing");add.performClick();
        }
        if(hasDialog(mode)){
            dialog=top();collect(dialog,null,inputs);
            String[] values=new String[0];
            switch(mode){
                case "txn-output":values=new String[]{AMOUNT,ADDRESS,"0x00"};break;
                case "txn-state":values=new String[]{"006","0x0001"};break;
                case "txn-sign":values=new String[]{ADDRESS};break;
                case "txn-post":values=new String[]{"0.000000000000000001"};break;
                case "txn-import":values=new String[]{"0x0000123456789abcdef"};break;
                default:break;
            }
            if(values.length!=inputs.size())throw new IllegalStateException("Unexpected dialog inputs");
            for(int i=0;i<inputs.size();i++){inputs.get(i).setText(values[i]);inputs.get(i).setSelection(1,Math.min(3,inputs.get(i).length()));}
            if("txn-output".equals(mode)){store=dialog.findViewById(R.id.out_storestate);storeDefaultFalse=!store.isChecked();store.setChecked(true);}
        }
        inputs.add(0,id);
    }
    void snapshot()throws Exception{
        for(EditText input:inputs){drafts.add(input.getText().toString());starts.add(input.getSelectionStart());ends.add(input.getSelectionEnd());}
        if("txn-export".equals(mode))clipboard=clipboard();
        initialCommands=commands.size();node=get(txn,"mNode");guard=(Boolean)get(txn,"mChangeInFlight");originalPending=pending;
        statusColor=((TextView)get(txn,"mStatus")).getCurrentTextColor();summaryColor=((TextView)get(txn,"mSummary")).getCurrentTextColor();
    }
    boolean same()throws Exception{
        boolean same=node==get(txn,"mNode")&&commands.size()==initialCommands&&guard==(Boolean)get(txn,"mChangeInFlight")&&originalPending==pending
                &&statusColor==((TextView)get(txn,"mStatus")).getCurrentTextColor()&&summaryColor==((TextView)get(txn,"mSummary")).getCurrentTextColor();
        if(dialog!=null){List<View> current=new ArrayList<>();collect(dialog,current,null);if(dialogViews.isEmpty())dialogViews.addAll(current);same &= WindowInspector.getGlobalWindowViews().contains(dialog)&&current.equals(dialogViews);}
        for(int i=0;i<inputs.size();i++)same &= drafts.get(i).contentEquals(inputs.get(i).getText())&&starts.get(i)==inputs.get(i).getSelectionStart()&&ends.get(i)==inputs.get(i).getSelectionEnd();
        if(store!=null)same &= storeDefaultFalse&&store.isChecked();
        if(clipboard!=null)same &= clipboard.equals(clipboard());
        return same;
    }
    JSONObject proof()throws Exception{return new JSONObject().put("commands",new JSONArray(commands)).put("drafts",new JSONArray(drafts))
            .put("pending",pending!=null).put("change_guard",get(txn,"mChangeInFlight"))
            .put("status",((TextView)get(txn,"mStatus")).getText()).put("summary",((TextView)get(txn,"mSummary")).getText())
            .put("clipboard",clipboard==null?"":clipboard()).put("storestate",store!=null&&store.isChecked()).put("storestate_default_false",storeDefaultFalse);}
    private String clipboard(){
        android.content.ClipboardManager manager=(android.content.ClipboardManager)activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        return manager.getPrimaryClip().getItemAt(0).getText().toString();
    }
    private static View top(){List<View> roots=WindowInspector.getGlobalWindowViews();return roots.get(roots.size()-1);}
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void invoke(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(o,args);}
    private static void collect(View view,List<View> views,List<EditText> fields){if(views!=null)views.add(view);if(fields!=null&&view instanceof EditText)fields.add((EditText)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),views,fields);}
}
