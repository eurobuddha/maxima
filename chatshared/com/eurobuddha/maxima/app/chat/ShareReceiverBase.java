package com.eurobuddha.maxima.app.chat;

import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.eurobuddha.maxima.app.*;
import com.eurobuddha.maxima.app.i18n.Language;
import java.util.*;
import java.util.function.Consumer;

/** Both Android clients use the same locked, explicit recipient selection. */
public abstract class ShareReceiverBase extends AppCompatActivity {
    protected static final class Target {
        public final String key,name; public final boolean group;
        public Target(String key,String name,boolean group) {this.key=key;this.name=name==null||name.isEmpty()?key:name;this.group=group;}
        @Override public String toString(){return group?name+" · "+Language.msg(R.string.l10n_chat_group):name;}
    }
    protected abstract boolean ready();
    protected abstract void load(Consumer<List<Target>> success,Consumer<String> failure);
    protected abstract Intent conversation(Target target);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final LockGate lock=new LockGate(this);
    private ArrayList<Uri> files;
    private LinearLayout root;
    private TextView status;
    private boolean resumed,loading,loaded;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(24*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        root.setBackgroundColor(getColor(R.color.ux_bg));
        status=new TextView(this);status.setTextColor(getColor(R.color.ux_text));status.setTextSize(20);
        Language.setText(status,Language.msg(R.string.l10n_share_choose));root.addView(status);
        Button cancel=new Button(this);Language.setText(cancel,Language.msg(R.string.l10n_common_cancel));
        cancel.setOnClickListener(v->finish());root.addView(cancel);setContentView(root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            view.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom);return insets;
        });
        lock.onCreate();
    }
    private final Runnable poll=new Runnable(){public void run(){
        if(!resumed || isFinishing())return;
        if(AppLock.mustUnlock(ShareReceiverBase.this)){handler.postDelayed(this,250);return;}
        if(loaded || loading)return;
        if(!ready()){Language.setText(status,Language.msg(R.string.l10n_share_setup));return;}
        try {files=SharedFiles.read(ShareReceiverBase.this,getIntent());}
        catch(Exception invalid){Language.setText(status,Language.msg(R.string.l10n_share_invalid));return;}
        loading=true;
        load(targets->runOnUiThread(()->{
            loading=false;if(isFinishing()||isDestroyed())return;
            if(!resumed || AppLock.mustUnlock(ShareReceiverBase.this)){handler.postDelayed(this,250);return;}
            loaded=true;showTargets(targets);
        }),error->runOnUiThread(()->{
            loading=false;if(isFinishing()||isDestroyed())return;
            Language.setText(status,Language.msg(R.string.l10n_share_connect));
            handler.postDelayed(this,2000);
        }));
    }};
    private void showTargets(List<Target> targets) {
        Language.setText(status,Language.msg(targets.isEmpty()?R.string.l10n_share_no_contacts:R.string.l10n_share_choose));
        targets.sort((a,b)->a.name.compareToIgnoreCase(b.name));
        EditText search=new EditText(this);search.setSingleLine(true);Language.hint(search,Language.msg(R.string.l10n_nav_contacts));root.addView(search);
        ListView list=new ListView(this);ArrayAdapter<Target> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>(targets));
        list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){adapter.getFilter().filter(s);}
            public void afterTextChanged(android.text.Editable s){}
        });
        list.setOnItemClickListener((p,v,position,id)->{
            if(AppLock.mustUnlock(this))return;
            list.setEnabled(false);
            Intent destination=conversation(adapter.getItem(position));
            SharedFiles.forward(destination,files);
            startActivity(destination);finish();
        });
    }
    @Override protected void onResume(){super.onResume();resumed=true;lock.onResume();handler.post(poll);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(poll);super.onPause();}
    @Override protected void onStop(){lock.onStop();super.onStop();}
}
