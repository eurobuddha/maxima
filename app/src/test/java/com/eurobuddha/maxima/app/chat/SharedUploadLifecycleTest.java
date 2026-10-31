package com.eurobuddha.maxima.app.chat;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class SharedUploadLifecycleTest {
    static CountDownLatch appendStarted, releaseAppend;
    static AtomicInteger begins, finishes, interrupts;
    public static class UploadActivity extends ChatShareIntakeTest.ReviewActivity {
        PrivateFileUi uploader;
        @Override public void onCreate(Bundle b){super.onCreate(b);intake.close();
            uploader=PrivateFileUi.obtain(this,p->{
                switch(p.get("action")){
                    case "begin":begins.incrementAndGet();return Collections.singletonMap("offset","0");
                    case "append":appendStarted.countDown();try{releaseAppend.await();}catch(InterruptedException e){interrupts.incrementAndGet();throw e;}
                        return Collections.singletonMap("offset","20");
                    case "finish":finishes.incrementAndGet();break;
                }
                return Collections.singletonMap("ok","true");
            },"alice",false);
            intake=new ChatShareIntake(this,b,"Alice",()->true,u->new byte[0],(u,c)->{},uploader::upload);
        }
        @Override public void onDestroy(){uploader.detach();super.onDestroy();}
    }
    @Test public void acceptedShareSurvivesRecreationWithoutDuplicateSend()throws Exception { checkRecreation(false); }
    @Test public void stagedUnknownSizeSourceSurvivesRecreationAndIsCleanedAfterSending()throws Exception { checkRecreation(true); }
    private void checkRecreation(boolean unknown)throws Exception {
        new ChatShareIntakeTest().setup();
        if(unknown)ShadowContentResolver.registerProviderInternal("share.test",new android.content.ContentProvider(){
            public boolean onCreate(){return true;}public String getType(Uri uri){return "application/pdf";}
            public android.database.Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
                android.database.MatrixCursor c=new android.database.MatrixCursor(new String[]{android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE});c.addRow(new Object[]{"pipe.pdf",null});return c;
            }
            public Uri insert(Uri uri,android.content.ContentValues v){throw new UnsupportedOperationException();}
            public int update(Uri uri,android.content.ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
            public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
        });
        appendStarted=new CountDownLatch(1);releaseAppend=new CountDownLatch(1);
        begins=new AtomicInteger();finishes=new AtomicInteger();interrupts=new AtomicInteger();
        Uri uri=Uri.parse("content://share.test/document");
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerInputStream(uri,new ByteArrayInputStream(new byte[20]));
        Intent share=new Intent().putParcelableArrayListExtra(SharedFiles.EXTRA,new ArrayList<>(Collections.singletonList(uri)));
        ActivityController<UploadActivity> c=Robolectric.buildActivity(UploadActivity.class,share).create().start().resume().visible();
        try {
            AlertDialog dialog=null;
            for(int i=0;i<100;i++){ShadowLooper.idleMainLooper();android.app.Dialog d=ShadowDialog.getLatestDialog();if(d instanceof AlertDialog&&d.isShowing()){dialog=(AlertDialog)d;break;}Thread.sleep(10);}
            assertNotNull(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            for(int i=0;i<100&&appendStarted.getCount()>0;i++){ShadowLooper.idleMainLooper();Thread.sleep(10);}
            assertEquals(0,appendStarted.getCount());
            File[] staged=RuntimeEnvironment.getApplication().getCacheDir().listFiles((dir,name)->name.startsWith("shared-file-"));
            if(unknown)assertEquals(1,staged.length);
            PrivateFileUi original=c.get().uploader;
            c.recreate();ShadowLooper.idleMainLooper();
            assertSame(original,c.get().uploader);
            if(unknown)assertTrue(staged[0].exists());
            assertEquals(0,interrupts.get());assertEquals(1,begins.get());
            releaseAppend.countDown();
            for(int i=0;i<100&&finishes.get()==0;i++){ShadowLooper.idleMainLooper();Thread.sleep(10);}
            assertEquals(1,finishes.get());assertEquals(1,begins.get());
            if(unknown){for(int i=0;i<100&&staged[0].exists();i++){ShadowLooper.idleMainLooper();Thread.sleep(10);}assertFalse(staged[0].exists());}
            Bundle saved=new Bundle();c.saveInstanceState(saved);assertTrue(saved.getBoolean("sharedFilesConsumed"));
        }finally{releaseAppend.countDown();c.pause().stop().destroy();}
    }
}
