package com.eurobuddha.maxima.files;

import android.content.Context;
import com.eurobuddha.maxima.app.files.WebRtcFiles;
import com.eurobuddha.maxima.core.chat.ChatFile;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synthetic native ICE/DTLS + production encrypted torrent pipeline. No account or STUN. */
public final class RtcTransferCheck {
    public static String run(Context context)throws Exception {
        Path root=Files.createTempDirectory(context.getCacheDir().toPath(),"rtc-files-");
        Path seed=Files.createDirectory(root.resolve("seed")),download=Files.createDirectory(root.resolve("download"));
        long size=ChatFile.TRANSFER_MAX_BYTES;
        String id=FileCrypto.randomHex(16),key=FileCrypto.randomHex(32),nonce=FileCrypto.randomHex(8);
        InputStream pattern=new InputStream(){long offset;public int read(){return offset==size?-1:(int)((offset++*31+17)&255);}
            public int read(byte[] b,int off,int len){if(offset==size)return -1;int n=(int)Math.min(len,size-offset);for(int i=0;i<n;i++)b[off+i]=(byte)((offset++*31+17)&255);return n;}};
        String digest=FileCrypto.encrypt(pattern,seed.resolve("payload.bin"),id,size,key,nonce,()->false,n->{});
        ChatFile file=new ChatFile(id,"synthetic-100MiB.bin","application/octet-stream",size,key,nonce,digest,
                Base64.getEncoder().encodeToString(PrivateTorrent.make(seed.resolve("payload.bin"))));
        AtomicInteger pieces=new AtomicInteger();CountDownLatch done=new CountDownLatch(1);
        AtomicReference<String> error=new AtomicReference<>();
        try(PrivateTorrent sender=new PrivateTorrent(PrivateTorrentTest.port(),true);
                PrivateTorrent receiver=new PrivateTorrent(PrivateTorrentTest.port(),true);
                TorrentTunnel gateway=new TorrentTunnel(true)) {
            gateway.uploadLimit(4*1024*1024);
            String token=FileCrypto.randomHex(32);gateway.expose(token,PrivateTorrent.validate(file).getTorrentId().getBytes(),sender.port());
            sender.start(file,seed,(n,total)->{},()->{},error::set);
            receiver.start(file,download,(n,total)->pieces.set(n),done::countDown,error::set);
            long start=System.nanoTime();
            try(Link link=new Link(context,file.id,token,gateway.gateway(),receiver)){
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);
                while(pieces.get()<2 && error.get()==null && System.nanoTime()<deadline)Thread.sleep(100);
                if(pieces.get()<2)throw new AssertionError("Native transfer made no verified progress: "+error.get());
                receiver.pause(file.id);
            }
            int before=pieces.get();
            android.util.Log.i("PrivateFilesCheck","Native RTC interrupted after "+before+" pieces; resuming");
            receiver.start(file,download,(n,total)->pieces.set(n),done::countDown,error::set);
            try(Link link=new Link(context,file.id,token,gateway.gateway(),receiver)){
                if(!done.await(180,TimeUnit.SECONDS))throw new AssertionError("Native resume timed out at "+pieces.get()+" pieces: "+error.get());
            }
            receiver.pause(file.id);
            FileCrypto.decrypt(download.resolve("payload.bin"),download.resolve("verified.bin"),file,()->false);
            if(Files.size(download.resolve("verified.bin"))!=size)throw new AssertionError("Wrong file size");
            try(InputStream in=Files.newInputStream(download.resolve("verified.bin"))){
                byte[] b=new byte[65536];long offset=0;int n;
                while((n=in.read(b))!=-1)for(int i=0;i<n;i++)if(b[i]!=(byte)((offset++*31+17)&255))throw new AssertionError("Wrong plaintext");
            }
            return "PASS: 100 MiB native WebRTC encrypted torrent transfer, interrupted after "+before+" pieces and resumed; GCM, SHA-256 and every plaintext byte verified in "+TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)+"s. No STUN, TURN, fleet or real account.";
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    }
    private static final class Link implements AutoCloseable {
        final WebRtcFiles a,b;
        Link(Context c,String id,String token,int gateway,PrivateTorrent receiver){
            a=new WebRtcFiles(c,Collections.emptyList());b=new WebRtcFiles(c,Collections.emptyList());
            a.connect(id,"receiver",false,gateway,token,token,(kind,body)->b.signal(id,"sender",signal(kind,body)),port->{});
            b.connect(id,"sender",true,0,token,token,(kind,body)->a.signal(id,"receiver",signal(kind,body)),port->{
                try{receiver.tunnelPeer(id,port);}catch(Exception e){throw new RuntimeException(e);}
            });
        }
        static Map<String,String> signal(String kind,String body){Map<String,String> m=new HashMap<>();m.put("kind",kind);m.put("body",body);return m;}
        public void close(){a.close();b.close();}
    }
}
