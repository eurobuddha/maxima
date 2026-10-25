package com.eurobuddha.maxima.app.chat;

import com.eurobuddha.maxima.app.i18n.LanguageDialog;

import android.app.Activity;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.maxima.core.chat.ChatFile;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One native document picker and transfer sheet for original Android and Cloud Android. */
public final class PrivateFileUi {
    public interface Backend { Map<String,String> call(Map<String,String> parameters)throws Exception; }
    public static final int PICK=931,SAVE=932;
    private final Activity activity;private final Backend backend;private final String peer;private final boolean group;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"private-file-ui");t.setDaemon(true);return t;});
    private String savingId;private volatile boolean closed;
    public PrivateFileUi(Activity activity,Backend backend,String peer,boolean group){this.activity=activity;this.backend=backend;this.peer=peer;this.group=group;}
    public static CharSequence label(String body){try{ChatFile f=ChatFile.fromBody(body);return Language.msg(R.string.l10n_native_files_offer,f.name,size(f.size));}catch(Exception e){return Language.msg(R.string.l10n_native_files_update);}}
    public void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);activity.startActivityForResult(i,PICK);}
    public boolean result(int request,int result,Intent data){
        if(request!=PICK && request!=SAVE)return false;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;
        Uri uri=data.getData();if(request==PICK)upload(uri);else export(uri,savingId);return true;
    }
    private Map<String,String> call(String action,String... values)throws Exception{Map<String,String> m=new LinkedHashMap<>();m.put("action",action);for(int i=0;i<values.length;i+=2)m.put(values[i],values[i+1]);
        Map<String,String> r=backend.call(m);if("false".equals(r.get("ok")))throw new IOException(r.getOrDefault("error","Transfer failed"));return r;}
    private static final class PendingUpload {
        final Uri uri; final com.eurobuddha.maxima.core.chat.FileUpload upload;
        volatile boolean running,cancelled; volatile long offset; volatile String error="";
        AlertDialog dialog; ProgressBar progress;
        PendingUpload(Uri uri,com.eurobuddha.maxima.core.chat.FileUpload upload){this.uri=uri;this.upload=upload;}
    }
    private final Map<String,PendingUpload> pendingUploads=new ConcurrentHashMap<>();
    private void upload(Uri uri){
        work.execute(()->{
            try {
                String name="file",mime=activity.getContentResolver().getType(uri);long bytes=-1;
                try(android.database.Cursor c=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                    if(c!=null&&c.moveToFirst()){name=c.getString(0);if(!c.isNull(1))bytes=c.getLong(1);}}
                String id=UUID.randomUUID().toString().replace("-","");
                PendingUpload u=new PendingUpload(uri,new com.eurobuddha.maxima.core.chat.FileUpload(backend::call,id,name,
                        mime==null?"application/octet-stream":mime,bytes,peer,group));
                main.post(()->{if(closed)return;pendingUploads.put(id,u);showUpload(u);runUpload(u);});
            }catch(Exception e){main.post(()->error(e));}
        });
    }
    private void showUpload(PendingUpload u){
        if(closed||activity.isFinishing())return;
        u.progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);u.progress.setMax(100);
        u.dialog=new LanguageDialog(activity).setTitle(u.upload.name).setMessage(Language.msg(R.string.l10n_native_files_preparing))
                .setView(u.progress).setPositiveButton(Language.msg(R.string.l10n_files_resume_upload),null)
                .setNeutralButton(Language.msg(R.string.l10n_files_cancel_upload),null)
                .setNegativeButton(Language.msg(R.string.l10n_files_hide),null).show();
        u.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->runUpload(u));
        u.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
            u.cancelled=true;if(!u.running)runUpload(u);
        });
        updateUpload(u);
    }
    private void updateUpload(PendingUpload u){
        if(closed||u.dialog==null||!u.dialog.isShowing())return;
        int pct=(int)(100*u.offset/Math.max(1,u.upload.size));u.progress.setProgress(pct);
        Language.setText(u.dialog.findViewById(android.R.id.message),u.error.isEmpty()
                ?Language.msg(R.string.l10n_native_files_upload_progress,pct)
                :Language.msg(R.string.l10n_files_upload_paused_detail,SystemMessages.message(u.error)));
        u.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!u.running);
        u.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!u.cancelled);
    }
    private void runUpload(PendingUpload u){
        if(closed||u.running)return;u.running=true;u.error="";updateUpload(u);
        work.execute(()->{
            boolean finished=false,removed=false;
            try {
                if(!u.cancelled){
                    u.upload.run(activity.getContentResolver().openInputStream(u.uri),()->closed||u.cancelled,
                            n->{u.offset=n;main.post(()->updateUpload(u));});
                    finished=true;
                }
            }catch(Exception e){u.error=e.getMessage()==null?"Connection interrupted":e.getMessage();}
            if(u.cancelled&&!finished){
                try{u.upload.cancel();removed=true;}
                catch(Exception e){u.error=e.getMessage()==null?"Transfer failed":e.getMessage();u.cancelled=false;}
            }
            final boolean sent=finished,deleted=removed;
            main.post(()->{
                u.running=false;
                if(sent||deleted){
                    pendingUploads.remove(u.upload.id);
                    if(!closed&&u.dialog!=null&&u.dialog.isShowing()){
                        u.dialog.dismiss();if(sent)show(u.upload.id,null);
                    }
                }else updateUpload(u);
            });
        });
    }
    public void showBody(String body){try{ChatFile f=ChatFile.fromBody(body);show(f.id,f);}catch(Exception e){error(e);}}
    private void show(String initialId,ChatFile offer){
        if(closed||activity.isFinishing())return;
        String[] id={initialId}, filename={offer==null?"Parlons file":offer.name};boolean[] ready={false},paused={false},exists={false},busy={false},dismissed={false};
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*activity.getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,pad);
        TextView info=new TextView(activity);Language.setText(info,offer==null?Language.msg(R.string.l10n_native_files_preparing):Language.msg(R.string.l10n_native_files_description,size(offer.size)));box.addView(info);
        ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);box.addView(progress);
        Button action=new Button(activity);Language.setText(action,Language.msg(R.string.l10n_files_download));box.addView(action);
        Button save=new Button(activity);Language.setText(save,Language.msg(R.string.l10n_native_files_save));save.setEnabled(false);box.addView(save);
        Button remove=new Button(activity);Language.setText(remove,Language.msg(R.string.l10n_files_remove));remove.setEnabled(false);box.addView(remove);
        AlertDialog dialog=new LanguageDialog(activity).setTitle(offer==null?tr(R.string.l10n_files_private):offer.name).setView(box).setNegativeButton(Language.msg(R.string.l10n_language_close),null).create();
        dialog.setOnDismissListener(d->dismissed[0]=true);dialog.show();
        TextView heading=dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        if(heading!=null)Language.setText(heading,offer==null?Language.msg(R.string.l10n_files_private):offer.name);
        Runnable[] tick=new Runnable[1];tick[0]=()->{
            if(closed||dismissed[0])return;if(busy[0]){main.postDelayed(tick[0],1000);return;}busy[0]=true;
            work.execute(()->{try{Map<String,String> s=call("status","id",id[0]);main.post(()->{
                if(closed||dismissed[0])return;filename[0]=s.getOrDefault("name",filename[0]);dialog.setTitle(filename[0]);if(heading!=null)Language.setText(heading,filename[0]);id[0]=s.getOrDefault("fileId",id[0]);exists[0]=true;ready[0]="true".equals(s.get("ready"));paused[0]="true".equals(s.get("paused"))||"Failed".equals(s.get("status"));
                String state=s.getOrDefault("status","Waiting");Language.setText(info,Language.join(SystemMessages.message(state),s.getOrDefault("error","").isEmpty()?"":Language.join("\n",SystemMessages.message(s.get("error"))),"\n",size(Long.parseLong(s.getOrDefault("done","0")))," / ",size(Long.parseLong(s.getOrDefault("size","0")))));
                progress.setProgress((int)(100*Long.parseLong(s.getOrDefault("done","0"))/Math.max(1,Long.parseLong(s.getOrDefault("size","0")))));
                save.setEnabled(ready[0]);remove.setEnabled(!"Preparing".equals(state)&&!"Uploading".equals(state));
                Language.setText(action,Language.msg(paused[0]||"Failed".equals(state)?R.string.l10n_files_resume:R.string.l10n_files_pause));
                action.setEnabled(!"Preparing".equals(state)&&!"Uploading".equals(state));
            });}catch(Exception e){main.post(()->{if(!exists[0])Language.setText(info,offer==null?SystemMessages.message(e.getMessage()):Language.msg(R.string.l10n_native_files_download_help,size(offer.size)));});}
                finally{main.post(()->{busy[0]=false;if(!dismissed[0])main.postDelayed(tick[0],1200);});}});
        };
        action.setOnClickListener(v->{String command=paused[0]?"resume":"pause";action.setEnabled(false);work.execute(()->{
            try{if(!exists[0]){if(offer==null)throw new IOException("Transfer unavailable");if(offer.size>ChatFile.TRANSFER_MAX_BYTES)throw new IOException("Choose a file up to 100 MB");call("download","ref",offer.ref(),"peer",peer,"group",Boolean.toString(group));}
                else call(command,"id",id[0]);}
            catch(Exception e){main.post(()->error(e));}finally{main.post(()->action.setEnabled(true));}
        });});
        save.setOnClickListener(v->{savingId=id[0];Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(offer==null?"application/octet-stream":offer.mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,filename[0]);activity.startActivityForResult(i,SAVE);});
        remove.setOnClickListener(v->new LanguageDialog(activity).setMessage(Language.msg(R.string.l10n_native_files_remove_confirm)).setNegativeButton(Language.msg(R.string.l10n_common_cancel),null).setPositiveButton(Language.msg(R.string.l10n_common_remove),(d,w)->work.execute(()->{try{call("remove","id",id[0]);main.post(dialog::dismiss);}catch(Exception e){main.post(()->error(e));}})).show());
        tick[0].run();
    }
    public void transfers(){work.execute(()->{try{
        List<CharSequence> labels=new ArrayList<>();List<String> ids=new ArrayList<>();
        for(PendingUpload u:pendingUploads.values()){
            labels.add(Language.join(u.upload.name," · ",Language.msg(u.running?R.string.l10n_files_uploading:R.string.l10n_files_paused)));
            ids.add(u.upload.id);
        }
        org.json.JSONArray a;
        try{a=new org.json.JSONArray(call("list").get("transfers"));}
        catch(Exception e){if(labels.isEmpty())throw e;a=new org.json.JSONArray();}
        for(int i=0;i<a.length();i++){org.json.JSONObject o=a.getJSONObject(i);String id=o.getString("id");
            if(pendingUploads.containsKey(id))continue;
            labels.add(Language.join(o.getString("name")," · ",SystemMessages.message(o.getString("status"))));ids.add(id);
        }
        main.post(()->{if(closed||activity.isFinishing())return;
            if(labels.isEmpty()){Toast.makeText(activity,tr(R.string.l10n_native_files_no_transfers),Toast.LENGTH_SHORT).show();return;}
            new LanguageDialog(activity).setTitle(Language.msg(R.string.l10n_files_title)).setItems(labels.toArray(new CharSequence[0]),(d,w)->{
                PendingUpload u=pendingUploads.get(ids.get(w));if(u!=null)showUpload(u);else show(ids.get(w),null);
            }).setNegativeButton(Language.msg(R.string.l10n_language_close),null).show();
        });
    }catch(Exception e){main.post(()->error(e));}});}
    private void export(Uri destination,String id){work.execute(()->{try(OutputStream out=activity.getContentResolver().openOutputStream(destination,"w")){
        if(out==null)throw new IOException("Cannot save file");long offset=0,size;
        do{Map<String,String> r=call("read","id",id,"offset",Long.toString(offset));byte[] bytes=Base64.getDecoder().decode(r.get("data"));long next=Long.parseLong(r.get("next"));size=Long.parseLong(r.get("size"));if(closed||size<0||size>ChatFile.MAX_BYTES||next!=offset+bytes.length||next>size||(next<=offset&&offset<size))throw new IOException("Download interrupted");out.write(bytes);offset=next;}while(offset<size);
        main.post(()->Toast.makeText(activity,tr(R.string.l10n_native_files_saved),Toast.LENGTH_SHORT).show());
    }catch(Exception e){
        try{android.provider.DocumentsContract.deleteDocument(activity.getContentResolver(),destination);}catch(Exception ignored){}
        main.post(()->error(e));}});}
    private void error(Exception e){if(!closed&&!activity.isFinishing())new LanguageDialog(activity).setTitle(Language.msg(R.string.l10n_files_private)).setMessage(SystemMessages.message(e.getMessage()==null?"Transfer failed":e.getMessage())).setPositiveButton(Language.msg(R.string.l10n_common_ok),null).show();}
    private static String size(long bytes){return bytes<1024?bytes+" B":bytes<1024*1024?String.format(Language.locale(),"%.1f KB",bytes/1024.0):String.format(Language.locale(),"%.1f MB",bytes/(1024.0*1024));}
    private String tr(int resource,Object... arguments){return Language.text(activity,resource,arguments);}
    public void close(){closed=true;main.removeCallbacksAndMessages(null);work.shutdownNow();}
}
