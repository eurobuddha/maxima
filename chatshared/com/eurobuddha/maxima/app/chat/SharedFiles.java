package com.eurobuddha.maxima.app.chat;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import java.util.*;

/** The external share boundary. Never accept paths or URIs into our own providers. */
public final class SharedFiles {
    public static final String EXTRA = "parlons.shared.files";
    public static ArrayList<Uri> read(Context context, Intent intent) {
        if (intent == null || !(Intent.ACTION_SEND.equals(intent.getAction())
                || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) throw new IllegalArgumentException("Unsupported share");
        LinkedHashSet<Uri> files = new LinkedHashSet<>();
        try {
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                ArrayList<?> values=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if(values!=null) for(Object value:values) {
                    if(!(value instanceof Uri)) throw new IllegalArgumentException("Invalid shared file");
                    files.add((Uri)value);
                }
            } else {
                Object value=intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if(value!=null) {
                    if(!(value instanceof Uri)) throw new IllegalArgumentException("Invalid shared file");
                    files.add((Uri)value);
                }
            }
            if(files.isEmpty() && intent.getClipData()!=null) {
                ClipData clip=intent.getClipData();
                for(int i=0;i<clip.getItemCount();i++) {
                    Uri uri=clip.getItemAt(i).getUri(); if(uri!=null) files.add(uri);
                }
            }
        } catch(RuntimeException malformed) { throw new IllegalArgumentException("Invalid shared files",malformed); }
        if(files.isEmpty() || files.size()>32) throw new IllegalArgumentException("Share between 1 and 32 files");
        for(Uri uri:files) {
            if(!"content".equals(uri.getScheme()) || uri.getAuthority()==null)
                throw new IllegalArgumentException("Share a file using a content URI");
            ProviderInfo provider=context.getPackageManager().resolveContentProvider(uri.getAuthority(),0);
            if(provider!=null && provider.applicationInfo.uid==android.os.Process.myUid())
                throw new IllegalArgumentException("Cannot share private app data");
            if(context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    !=PackageManager.PERMISSION_GRANTED) throw new IllegalArgumentException("File access was not granted");
        }
        return new ArrayList<>(files);
    }
    public static void forward(Intent destination, ArrayList<Uri> files) {
        destination.putParcelableArrayListExtra(EXTRA,files);
        ClipData clip=ClipData.newRawUri("Shared files",files.get(0));
        for(int i=1;i<files.size();i++) clip.addItem(new ClipData.Item(files.get(i)));
        destination.setClipData(clip);
        destination.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    private SharedFiles() {}
}
