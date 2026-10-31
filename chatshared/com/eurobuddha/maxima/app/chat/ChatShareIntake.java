package com.eurobuddha.maxima.app.chat;

import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.widget.*;
import androidx.appcompat.app.*;
import androidx.lifecycle.*;
import com.eurobuddha.maxima.app.*;
import com.eurobuddha.maxima.app.i18n.*;
import com.eurobuddha.maxima.core.chat.ChatFile;
import java.util.*;
import java.util.function.*;

/** Review incoming files inside the selected chat, after unlocking. No automatic sends. */
public final class ChatShareIntake implements LifecycleEventObserver {
    public interface Preview { byte[] read(Uri uri) throws Exception; }
    private final AppCompatActivity activity;
    private final String recipient;
    private final BooleanSupplier ready;
    private final BiConsumer<Uri,String> photo;
    private final Consumer<Uri> file;
    private final Preview preview;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean consumed,working,closed;
    private AlertDialog dialog;
    private final ArrayList<Uri> files;
    private final Intent original;
    public ChatShareIntake(AppCompatActivity activity,Bundle state,String recipient,BooleanSupplier ready,
                           Preview preview,BiConsumer<Uri,String> photo,Consumer<Uri> file) {
        this.activity=activity;this.recipient=recipient;this.ready=ready;this.preview=preview;this.photo=photo;this.file=file;
        original=activity.getIntent();files=original.getParcelableArrayListExtra(SharedFiles.EXTRA);
        consumed=state!=null && state.getBoolean("sharedFilesConsumed");
        activity.getLifecycle().addObserver(this);
    }
    public void save(Bundle state){state.putBoolean("sharedFilesConsumed",consumed);}
    private boolean active(){return !closed && activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
        && !AppLock.mustUnlock(activity) && activity.getIntent()==original;}
    private final Runnable poll=new Runnable(){public void run(){
        if(closed || consumed || files==null || files.isEmpty() || working || dialog!=null)return;
        if(!active() || !ready.getAsBoolean()){main.postDelayed(this,250);return;}
        working=true;
        new Thread(()->{
            List<String> names=new ArrayList<>();List<Boolean> images=new ArrayList<>();android.graphics.Bitmap bitmap=null;
            try {
                if(files.size()>32)throw new IllegalArgumentException();
                for(Uri uri:files){
                    String name="file",mime=activity.getContentResolver().getType(uri);long size=-1;
                    try(android.database.Cursor c=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                        if(c!=null&&c.moveToFirst()){
                            int ni=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),si=c.getColumnIndex(OpenableColumns.SIZE);
                            if(ni>=0&&!c.isNull(ni))name=c.getString(ni);
                            if(si>=0&&!c.isNull(si))size=c.getLong(si);
                        }
                    }
                    if(size>ChatFile.TRANSFER_MAX_BYTES)throw new java.io.IOException("Choose a local file up to 100 MB");
                    names.add(name);images.add(files.size()==1&&mime!=null&&mime.startsWith("image/")&&size>=0&&size<=32L*1024*1024);
                }
                if(files.size()==1 && images.get(0)) {
                    try{byte[] bytes=preview.read(files.get(0));bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);}
                    catch(Exception ignored){images.set(0,false);} // Unsupported images can still travel as original files.
                }
                final android.graphics.Bitmap thumb=bitmap;
                main.post(()->{working=false;if(active())show(names,images,thumb);else {if(thumb!=null)thumb.recycle();if(!closed)main.postDelayed(poll,250);}});
            }catch(Exception invalid){main.post(()->{
                working=false;
                if(active()){consumed=true;new LanguageDialog(activity).setMessage(Language.msg(R.string.l10n_share_invalid))
                    .setPositiveButton(Language.msg(R.string.l10n_common_ok),null).show();}
            });}
        },"share-preview").start();
    }};
    private void show(List<String> names,List<Boolean> images,android.graphics.Bitmap thumb){
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(20*activity.getResources().getDisplayMetrics().density);content.setPadding(pad,pad,pad,0);
        if(thumb!=null){ImageView view=new ImageView(activity);view.setImageBitmap(thumb);view.setAdjustViewBounds(true);view.setMaxHeight(pad*12);content.addView(view);}
        TextView summary=new TextView(activity);summary.setText(android.text.TextUtils.join("\n",names));content.addView(summary);
        EditText caption=new EditText(activity);Language.hint(caption,Language.msg(R.string.l10n_android_chat_caption));
        if(images.contains(true))content.addView(caption);
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        dialog=new LanguageDialog(activity).setTitle(Language.msg(R.string.l10n_share_to,recipient)).setView(scroll)
            .setPositiveButton(Language.msg(R.string.l10n_chat_send),(d,w)->{
                if(!active() || !ready.getAsBoolean())return;
                consumed=true;
                for(int i=0;i<files.size();i++)if(images.get(i))photo.accept(files.get(i),caption.getText().toString());else file.accept(files.get(i));
            }).setNegativeButton(Language.msg(R.string.l10n_common_cancel),(d,w)->consumed=true).create();
        dialog.setOnCancelListener(d->consumed=true);
        dialog.setOnDismissListener(d->{dialog=null;});dialog.show();
    }
    @Override public void onStateChanged(LifecycleOwner owner,Lifecycle.Event event){
        if(event==Lifecycle.Event.ON_RESUME)main.post(poll);
        if(event==Lifecycle.Event.ON_PAUSE){main.removeCallbacks(poll);if(dialog!=null)dialog.dismiss();}
        if(event==Lifecycle.Event.ON_DESTROY)close();
    }
    public void close(){closed=true;main.removeCallbacks(poll);if(dialog!=null)dialog.dismiss();activity.getLifecycle().removeObserver(this);}
}
