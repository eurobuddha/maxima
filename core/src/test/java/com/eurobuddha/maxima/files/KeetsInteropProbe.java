package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.chat.*;
import com.eurobuddha.maxima.core.util.Json;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated real-Hyperdrive probe, launched by scripts/test-keets-file-interop.cjs. */
public final class KeetsInteropProbe {
    static final class Media implements KeetsTransport {
        final int port;final String token;final KeetsMediaClient reader;
        Media(int p,String t)throws Exception{port=p;token=t;reader=new KeetsMediaClient(p,t);}
        public boolean send(String peer,String message){throw new AssertionError("bulk data attempted messaging");}
        public String publish(String path,byte[] data)throws Exception {
            if(data.length*2+2>240000)throw new AssertionError("Android IPC overflow");
            HttpURLConnection c=(HttpURLConnection)new URL("http","127.0.0.1",port,"/media"+path).openConnection(Proxy.NO_PROXY);
            try {
                c.setConnectTimeout(5000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
                c.setRequestMethod("POST");c.setRequestProperty("x-keets-token",token);c.setDoOutput(true);c.setFixedLengthStreamingMode(data.length);
                try(OutputStream out=c.getOutputStream()){out.write(data);}
                if(c.getResponseCode()!=200)throw new IOException("publish failed");
                try(InputStream in=c.getInputStream()){return Json.parse(new String(KeetsMediaClient.read(in,4096),StandardCharsets.UTF_8)).get("url");}
            }finally{c.disconnect();}
        }
        public byte[] fetch(String url)throws Exception{return fetch(url,KeetsFiles.BLOCK);}
        public byte[] fetch(String url,int max)throws Exception{return reader.fetch(url,max);}
    }
    static InputStream input(long size,int salt){return new InputStream(){long at;public int read(){return at==size?-1:(int)((at++*31+salt)&255);}public int read(byte[] b,int off,int count){if(at==size)return -1;int n=(int)Math.min(count,size-at);for(int i=0;i<n;i++)b[off+i]=(byte)((at++*31+salt)&255);return n;}};}
    static void transfer(Media sender,Media receiver,String label,int salt)throws Exception {
        Path dir=Files.createTempDirectory("keets-interop-");
        try {
            Path source=Files.createDirectory(dir.resolve("source")),dest=Files.createDirectory(dir.resolve("receiver"));
            long size=ChatFile.TRANSFER_MAX_BYTES;
            String id=FileCrypto.randomHex(16),key=FileCrypto.randomHex(32),nonce=FileCrypto.randomHex(8);
            String digest=FileCrypto.encrypt(input(size,salt),source.resolve("payload.bin"),id,size,key,nonce,()->false,n->{});
            ChatFile f=new ChatFile(id,"100mb-test.bin","application/octet-stream",size,key,nonce,digest,"");
            f=KeetsFiles.publish(sender,source.resolve("payload.bin"),f,()->false,n->{});
            ChatMessage decoded=KeetsWire.toChatMessage(KeetsWire.decode(KeetsWire.encode(ChatMessage.text("test-"+id,f.body(),1),"synthetic")));
            f=ChatFile.fromBody(decoded.body);
            AtomicBoolean paused=new AtomicBoolean();
            try{KeetsFiles.download(receiver,f,dest,paused::get,n->{if(n>=8*1024*1024)paused.set(true);});throw new AssertionError("pause ignored");}
            catch(InterruptedIOException expected){}
            if(Files.exists(dest.resolve("verified.bin")))throw new AssertionError("partial plaintext published");
            KeetsFiles.download(receiver,f,dest,()->false,n->{});
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(dest.resolve("verified.bin"))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}
            if(Files.size(dest.resolve("verified.bin"))!=size || !f.digest.equals(FileCrypto.hex(md.digest())))throw new AssertionError("byte mismatch");
            sender.reader.sharing(f,false);receiver.reader.sharing(f,false);
            try{sender.fetch(f.torrent,KeetsFiles.MANIFEST_LIMIT);throw new AssertionError("stopped file still served locally");}catch(IOException expected){}
            sender.reader.sharing(f,true);receiver.reader.sharing(f,true);
            if(sender.fetch(f.torrent,KeetsFiles.MANIFEST_LIMIT).length==0)throw new AssertionError("Share again failed");
            System.out.println("PASS "+label+": 100 MiB, interrupted/resumed, SHA-256 identical, encrypted Hyperdrive blocks only");
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(dir)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    }
    public static void main(String[] args)throws Exception {
        Media a=new Media(Integer.parseInt(args[0]),args[2]),b=new Media(Integer.parseInt(args[1]),args[2]);
        transfer(a,b,"Parlons -> KeetChat",7);transfer(b,a,"KeetChat -> Parlons",19);
    }
}
