package com.eurobuddha.maxima.app.chat;

import android.app.Application;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE,application=Application.class)
public class SharedFilesTest {
    private Context context;
    private PackageManager packages;
    private final Uri document=Uri.parse("content://com.eurobuddha.minimadocs.files/document");
    @Before public void setup(){
        context=mock(Context.class);packages=mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(packages);
        when(context.checkUriPermission(any(),anyInt(),anyInt(),anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
    }
    @Test public void minimaDocsShareAndForwardRetainStreamAndGrant(){
        Intent incoming=new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,document)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        incoming.setClipData(ClipData.newRawUri("document.pdf",document));
        ArrayList<Uri> files=SharedFiles.read(context,incoming);
        assertEquals(Collections.singletonList(document),files);
        Intent chat=new Intent();SharedFiles.forward(chat,files);
        assertEquals(files,chat.getParcelableArrayListExtra(SharedFiles.EXTRA));
        assertEquals(document,chat.getClipData().getItemAt(0).getUri());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,chat.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    @Test public void multipleSharesDeduplicateAndClipOnlyWorks(){
        Uri second=Uri.parse("content://gallery/second");
        Intent many=new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,new ArrayList<>(Arrays.asList(document,second,document)));
        assertEquals(Arrays.asList(document,second),SharedFiles.read(context,many));
        Intent clip=new Intent(Intent.ACTION_SEND);clip.setClipData(ClipData.newRawUri("document",document));
        assertEquals(Collections.singletonList(document),SharedFiles.read(context,clip));
    }
    @Test public void pathsUrlsPrivateProvidersAndMissingGrantsAreRejected(){
        for(String uri:new String[]{"file:///data/user/0/private", "https://example.org/file"})reject(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,Uri.parse(uri)));
        ProviderInfo own=new ProviderInfo();own.applicationInfo=new ApplicationInfo();own.applicationInfo.uid=android.os.Process.myUid();
        when(packages.resolveContentProvider(document.getAuthority(),0)).thenReturn(own);
        reject(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,document));
        when(packages.resolveContentProvider(document.getAuthority(),0)).thenReturn(null);
        when(context.checkUriPermission(any(),anyInt(),anyInt(),anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        reject(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,document));
    }
    @Test public void emptyWrongActionAndOversizedBatchAreRejected(){
        reject(new Intent(Intent.ACTION_SEND));reject(new Intent(Intent.ACTION_VIEW).setData(document));
        ArrayList<Uri> many=new ArrayList<>();for(int i=0;i<33;i++)many.add(Uri.parse("content://gallery/"+i));
        reject(new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,many));
    }
    private void reject(Intent intent){try{SharedFiles.read(context,intent);fail("Unsafe share accepted");}catch(IllegalArgumentException expected){}}
}
