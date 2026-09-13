package com.eurobuddha.maxima.app.chat;

import android.app.Activity;
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
    public static String label(String body){try{ChatFile f=ChatFile.fromBody(body);return "📎 "+f.name+"\n"+size(f.size)+" · Private file\nTap for transfer controls";}catch(Exception e){return "Private file · update Parlons to open";}}
    public void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);activity.startActivityForResult(i,PICK);}
    public boolean result(int request,int result,Intent data){
        if(request!=PICK && request!=SAVE)return false;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;
        Uri uri=data.getData();if(request==PICK)upload(uri);else export(uri,savingId);return true;
    }
    private Map<String,String> call(String action,String... values)throws Exception{Map<String,String> m=new LinkedHashMap<>();m.put("action",action);for(int i=0;i<values.length;i+=2)m.put(values[i],values[i+1]);
        Map<String,String> r=backend.call(m);if("false".equals(r.get("ok")))throw new IOException(r.getOrDefault("error","Transfer failed"));return r;}
    private void upload(Uri uri){
        final ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Sending private file").setMessage("Preparing…").setView(progress).setNegativeButton("Hide",null).show();
        work.execute(()->{String id=UUID.randomUUID().toString().replace("-","");
            try{
                String name="file",mime=activity.getContentResolver().getType(uri);long bytes=-1;
                try(android.database.Cursor c=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                    if(c!=null&&c.moveToFirst()){name=c.getString(0);if(!c.isNull(1))bytes=c.getLong(1);}}
                if(bytes<0||bytes>ChatFile.MAX_BYTES)throw new IOException("Choose a local file up to 512 MB");
                call("begin","id",id,"name",name,"mime",mime==null?"application/octet-stream":mime,"size",Long.toString(bytes),"peer",peer,"group",Boolean.toString(group));
                long offset=0;byte[] buffer=new byte[48*1024];
                try(InputStream in=activity.getContentResolver().openInputStream(uri)){
                    if(in==null)throw new IOException("Cannot open file");int n;
                    while((n=in.read(buffer))!=-1){if(closed)throw new InterruptedIOException();if(n==0)continue;
                        call("append","id",id,"offset",Long.toString(offset),"data",Base64.getEncoder().encodeToString(Arrays.copyOf(buffer,n)));offset+=n;
                        final int pct=(int)(100*offset/Math.max(1,bytes));main.post(()->{progress.setProgress(pct);dialog.setMessage("Uploading "+pct+"%");});
                    }
                }
                call("finish","id",id);
                main.post(()->{dialog.dismiss();show(id,null);});
            }catch(Exception e){try{call("cancelUpload","id",id);}catch(Exception ignored){}main.post(()->{dialog.dismiss();error(e);});}
        });
    }
    public void showBody(String body){try{ChatFile f=ChatFile.fromBody(body);show(f.id,f);}catch(Exception e){error(e);}}
    private void show(String initialId,ChatFile offer){
        if(closed||activity.isFinishing())return;
        String[] id={initialId}, filename={offer==null?"Parlons file":offer.name};boolean[] ready={false},paused={false},exists={false},busy={false},dismissed={false};
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*activity.getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,pad);
        TextView info=new TextView(activity);info.setText(offer==null?"Preparing…":size(offer.size)+"\nEncrypted · shared directly with this conversation\nDownloads may also upload encrypted pieces while active.");box.addView(info);
        ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);box.addView(progress);
        Button action=new Button(activity);action.setText("Download");box.addView(action);
        Button save=new Button(activity);save.setText("Save file…");save.setEnabled(false);box.addView(save);
        Button remove=new Button(activity);remove.setText("Remove local transfer");remove.setEnabled(false);box.addView(remove);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(offer==null?"Private file":offer.name).setView(box).setNegativeButton("Close",null).create();
        dialog.setOnDismissListener(d->dismissed[0]=true);dialog.show();
        Runnable[] tick=new Runnable[1];tick[0]=()->{
            if(closed||dismissed[0])return;if(busy[0]){main.postDelayed(tick[0],1000);return;}busy[0]=true;
            work.execute(()->{try{Map<String,String> s=call("status","id",id[0]);main.post(()->{
                if(closed||dismissed[0])return;filename[0]=s.getOrDefault("name",filename[0]);dialog.setTitle(filename[0]);id[0]=s.getOrDefault("fileId",id[0]);exists[0]=true;ready[0]="true".equals(s.get("ready"));paused[0]="true".equals(s.get("paused"))||"Failed".equals(s.get("status"));
                String state=s.getOrDefault("status","Waiting");info.setText(state+(s.getOrDefault("error","").isEmpty()?"":"\n"+s.get("error"))+"\n"+size(Long.parseLong(s.getOrDefault("done","0")))+" / "+size(Long.parseLong(s.getOrDefault("size","0"))));
                progress.setProgress((int)(100*Long.parseLong(s.getOrDefault("done","0"))/Math.max(1,Long.parseLong(s.getOrDefault("size","0")))));
                save.setEnabled(ready[0]);remove.setEnabled(!"Preparing".equals(state)&&!"Uploading".equals(state));
                action.setText(paused[0]||"Failed".equals(state)?"Resume":"Pause sharing");
                action.setEnabled(!"Preparing".equals(state)&&!"Uploading".equals(state));
            });}catch(Exception e){main.post(()->{if(!exists[0])info.setText(offer==null?e.getMessage():size(offer.size)+"\nTap Download to receive this encrypted file. Keep a participant online until it finishes.");});}
                finally{main.post(()->{busy[0]=false;if(!dismissed[0])main.postDelayed(tick[0],1200);});}});
        };
        action.setOnClickListener(v->{String command=paused[0]?"resume":"pause";action.setEnabled(false);work.execute(()->{
            try{if(!exists[0]){if(offer==null)throw new IOException("Transfer unavailable");call("download","ref",offer.ref(),"peer",peer,"group",Boolean.toString(group));}
                else call(command,"id",id[0]);}
            catch(Exception e){main.post(()->error(e));}finally{main.post(()->action.setEnabled(true));}
        });});
        save.setOnClickListener(v->{savingId=id[0];Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(offer==null?"application/octet-stream":offer.mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,filename[0]);activity.startActivityForResult(i,SAVE);});
        remove.setOnClickListener(v->new AlertDialog.Builder(activity).setMessage("Remove this device’s transfer and stop sharing it? Saved copies are kept.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->work.execute(()->{try{call("remove","id",id[0]);main.post(dialog::dismiss);}catch(Exception e){main.post(()->error(e));}})).show());
        tick[0].run();
    }
    public void transfers(){work.execute(()->{try{org.json.JSONArray a=new org.json.JSONArray(call("list").get("transfers"));String[] labels=new String[a.length()],ids=new String[a.length()];
        for(int i=0;i<a.length();i++){org.json.JSONObject o=a.getJSONObject(i);labels[i]=o.getString("name")+" · "+o.getString("status");ids[i]=o.getString("id");}
        main.post(()->{if(closed||activity.isFinishing())return;if(labels.length==0){Toast.makeText(activity,"No local file transfers",Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(activity).setTitle("File transfers").setItems(labels,(d,w)->show(ids[w],null)).setNegativeButton("Close",null).show();});
    }catch(Exception e){main.post(()->error(e));}});}
    private void export(Uri destination,String id){work.execute(()->{try(OutputStream out=activity.getContentResolver().openOutputStream(destination,"w")){
        if(out==null)throw new IOException("Cannot save file");long offset=0,size;
        do{Map<String,String> r=call("read","id",id,"offset",Long.toString(offset));byte[] bytes=Base64.getDecoder().decode(r.get("data"));long next=Long.parseLong(r.get("next"));size=Long.parseLong(r.get("size"));if(closed||size<0||size>ChatFile.MAX_BYTES||next!=offset+bytes.length||next>size||(next<=offset&&offset<size))throw new IOException("Download interrupted");out.write(bytes);offset=next;}while(offset<size);
        main.post(()->Toast.makeText(activity,"File saved",Toast.LENGTH_SHORT).show());
    }catch(Exception e){
        try{android.provider.DocumentsContract.deleteDocument(activity.getContentResolver(),destination);}catch(Exception ignored){}
        main.post(()->error(e));}});}
    private void error(Exception e){if(!closed&&!activity.isFinishing())new AlertDialog.Builder(activity).setTitle("Private file").setMessage(e.getMessage()==null?"Transfer failed":e.getMessage()).setPositiveButton("OK",null).show();}
    private static String size(long bytes){return bytes<1024?bytes+" B":bytes<1024*1024?String.format(Locale.UK,"%.1f KB",bytes/1024.0):String.format(Locale.UK,"%.1f MB",bytes/(1024.0*1024));}
    public void close(){closed=true;main.removeCallbacksAndMessages(null);work.shutdownNow();}
}
