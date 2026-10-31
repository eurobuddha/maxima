package com.eurobuddha.maxima.app.chat;

import android.app.Application;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.FrameLayout;
import androidx.appcompat.app.*;
import com.eurobuddha.maxima.app.R;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class ChatShareIntakeTest {
    public static class ReviewActivity extends AppCompatActivity {
        static int sends;
        ChatShareIntake intake;
        @Override public void onCreate(Bundle state){super.onCreate(state);setTheme(R.style.Theme_Maxima);setContentView(new FrameLayout(this));
            intake=new ChatShareIntake(this,state,"Alice",()->true,uri->new byte[0],(uri,caption)->sends++,uri->sends++);
        }
        @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);intake.save(state);}
    }
    @Before public void setup(){
        ReviewActivity.sends=0;
        com.eurobuddha.maxima.app.i18n.Language.initialize(RuntimeEnvironment.getApplication());
        ShadowContentResolver.registerProviderInternal("share.test",new ContentProvider(){
            public boolean onCreate(){return true;}
            public String getType(Uri uri){return "application/pdf";}
            public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
                MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{"minimaDocs.pdf",20});return c;
            }
            public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
            public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
            public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
        });
    }
    @Test public void lockedChatWaitsForUnlockBeforeShowingSharedContent() throws Exception {
        com.eurobuddha.maxima.app.AppLock.setEnabled(RuntimeEnvironment.getApplication(),true);
        org.robolectric.util.ReflectionHelpers.setStaticField(com.eurobuddha.maxima.app.AppLock.class,"sUnlocked",false);
        ActivityController<ReviewActivity> c=Robolectric.buildActivity(ReviewActivity.class,share()).create().start().resume().visible();
        ShadowLooper.idleMainLooper();
        android.app.Dialog dialog=ShadowDialog.getLatestDialog();assertTrue(dialog==null||!dialog.isShowing());assertEquals(0,ReviewActivity.sends);
        com.eurobuddha.maxima.app.AppLock.markUnlocked();
        ShadowLooper.idleMainLooper(300,java.util.concurrent.TimeUnit.MILLISECONDS);
        assertNotNull(review());assertEquals(0,ReviewActivity.sends);
        c.pause().stop().destroy();
    }
    private Intent share(){return new Intent().putParcelableArrayListExtra(SharedFiles.EXTRA,new ArrayList<>(Collections.singletonList(Uri.parse("content://share.test/document"))));}
    private AlertDialog review() throws Exception {
        for(int i=0;i<100;i++){
            ShadowLooper.idleMainLooper();
            android.app.Dialog d=ShadowDialog.getLatestDialog();
            if(d instanceof AlertDialog && d.isShowing())return (AlertDialog)d;
            Thread.sleep(10);
        }
        throw new AssertionError("Share review did not appear");
    }
    @Test public void sendNeedsConfirmationAndCannotRepeatAfterRecreation() throws Exception {
        ActivityController<ReviewActivity> c=Robolectric.buildActivity(ReviewActivity.class,share()).create().start().resume().visible();
        AlertDialog dialog=review();assertEquals(0,ReviewActivity.sends);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertEquals(1,ReviewActivity.sends);
        Bundle state=new Bundle();c.saveInstanceState(state).pause().stop().destroy();
        assertTrue(state.getBoolean("sharedFilesConsumed"));
        ActivityController<ReviewActivity> restored=Robolectric.buildActivity(ReviewActivity.class,share()).create(state).start().resume().visible();
        ShadowLooper.idleMainLooper();assertEquals(1,ReviewActivity.sends);assertFalse(dialog.isShowing());
        restored.pause().stop().destroy();
    }
    public static class ImageActivity extends ReviewActivity {
        static PhotoInputTest.Source source;static int photos,originals;
        @Override public void onCreate(Bundle state){super.onCreate(state);intake.close();
            intake=new ChatShareIntake(this,state,"Alice",()->true,uri->{
                try(java.io.InputStream in=getContentResolver().openInputStream(uri)){return PhotoInput.read(in);}
            },(uri,caption)->photos++,uri->originals++);
        }
    }
    @Test public void lyingImageProviderIsBoundedAndOfferedAsOriginalOnly()throws Exception {
        ShadowContentResolver.registerProviderInternal("image.test",new ContentProvider(){
            public boolean onCreate(){return true;}public String getType(Uri uri){return "image/jpeg";}
            public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
                MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{"oversize.jpg",1});return c;
            }
            public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
            public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
            public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
        });
        Uri uri=Uri.parse("content://image.test/photo");ImageActivity.source=new PhotoInputTest.Source(Long.MAX_VALUE);
        ImageActivity.photos=0;ImageActivity.originals=0;
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerInputStream(uri,ImageActivity.source);
        Intent share=new Intent().putParcelableArrayListExtra(SharedFiles.EXTRA,new ArrayList<>(Collections.singletonList(uri)));
        ActivityController<ImageActivity> c=Robolectric.buildActivity(ImageActivity.class,share).create().start().resume().visible();
        try{
            AlertDialog dialog=review();assertEquals(PhotoInput.MAX_BYTES+1L,ImageActivity.source.read);
            assertEquals(0,ImageActivity.photos);assertEquals(0,ImageActivity.originals);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();
            assertEquals(0,ImageActivity.photos);assertEquals(1,ImageActivity.originals);
        }finally{c.pause().stop().destroy();}
    }
    @Test public void cancellingShareNeverStartsAnUpload() throws Exception {
        ActivityController<ReviewActivity> c=Robolectric.buildActivity(ReviewActivity.class,share()).create().start().resume().visible();
        review().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();ShadowLooper.idleMainLooper();
        Bundle state=new Bundle();c.saveInstanceState(state);assertTrue(state.getBoolean("sharedFilesConsumed"));assertEquals(0,ReviewActivity.sends);
        c.pause().stop().destroy();
    }
}
