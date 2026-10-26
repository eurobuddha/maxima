package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.chat.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class KeetsFilesTest {
    /** Actual byte storage, with the existing Android IPC limit enforced on every publish. */
    static class Mesh implements KeetsTransport,AutoCloseable {
        final Path root=Files.createTempDirectory("keets-file-mesh-");
        final String drive="keets://"+"a".repeat(64);
        int fetches;boolean corrupt;
        Mesh()throws Exception{}
        public boolean send(String peer,String payload){throw new AssertionError("File bytes must not use messaging/mailboxes");}
        public String publish(String path,byte[] bytes)throws Exception {
            assertTrue("fits existing Android IPC",bytes.length*2+2<=240000);
            Path f=root.resolve(path.substring(1));Files.createDirectories(f.getParent());Files.write(f,bytes);
            return drive+path+"?hash="+FileCrypto.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        public byte[] fetch(String url)throws Exception{return fetch(url,Integer.MAX_VALUE);}
        public byte[] fetch(String url,int max)throws Exception {
            fetches++;String path=url.substring(drive.length()+1,url.indexOf('?'));
            Path file=root.resolve(path);if(Files.size(file)>max)throw new IOException("too large");
            byte[] bytes=Files.readAllBytes(file);if(corrupt && !path.endsWith("manifest"))bytes[0]^=1;return bytes;
        }
        public void close()throws Exception{erase(root);}
    }
    static void erase(Path root)throws Exception {try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    static InputStream input(long size){return new InputStream(){long at;public int read(){return at==size?-1:(int)((at++*31+7)&255);}public int read(byte[] b,int off,int count){if(at==size)return -1;int n=(int)Math.min(count,size-at);for(int i=0;i<n;i++)b[off+i]=(byte)((at++*31+7)&255);return n;}};}
    static ChatFile prepare(Path dir,long size)throws Exception {
        String id=FileCrypto.randomHex(16),key=FileCrypto.randomHex(32),nonce=FileCrypto.randomHex(8);
        String digest=FileCrypto.encrypt(input(size),dir.resolve("payload.bin"),id,size,key,nonce,()->false,n->{});
        return new ChatFile(id,"large test.bin","application/octet-stream",size,key,nonce,digest,"");
    }
    static String digest(Path path)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}return FileCrypto.hex(digest.digest());}

    @Test(timeout=120000) public void hundredMiBResumesVerifiedBlocksAcrossRestartAndRoundTripsBothDialects()throws Exception {
        Path source=Files.createTempDirectory("keets-source-"),dest=Files.createTempDirectory("keets-receiver-");
        try(Mesh mesh=new Mesh()){
            ChatFile prepared=prepare(source,ChatFile.TRANSFER_MAX_BYTES);
            ChatFile offer=KeetsFiles.publish(mesh,source.resolve("payload.bin"),prepared,()->false,n->{});
            String wire=KeetsWire.encode(ChatMessage.text("file-message",offer.body(),1),"sender");
            KeetsWire.Msg decoded=KeetsWire.decode(wire);assertEquals("file",decoded.state);
            ChatFile received=ChatFile.fromBody(KeetsWire.toChatMessage(decoded).body);assertEquals(offer.ref(),received.ref());
            AtomicBoolean pause=new AtomicBoolean();
            try{KeetsFiles.download(mesh,received,dest,pause::get,n->{if(n>=4L*KeetsFiles.BLOCK)pause.set(true);});fail();}
            catch(InterruptedIOException expected){}
            assertFalse(Files.exists(dest.resolve("verified.bin")));int before=mesh.fetches;
            KeetsFiles.download(mesh,received,dest,()->false,n->{});
            int blocks=(int)((FileCrypto.cipherSize(received.size)+KeetsFiles.BLOCK-1)/KeetsFiles.BLOCK);
            assertEquals("manifest plus only missing blocks",1+blocks-4,mesh.fetches-before);
            assertEquals(received.size,Files.size(dest.resolve("verified.bin")));
            assertEquals(received.digest,digest(dest.resolve("verified.bin")));
            // The reverse direction uses the identical KeetChat state/ref schema.
            String reverse=new com.eurobuddha.maxima.core.util.Json.Writer().put("application","maxima_chat_v1")
                    .put("id","keetchat-reverse").put("body",offer.name).put("name","KeetChat")
                    .put("state","file").put("ref",offer.ref()).put("ts","2").done();
            assertEquals(received.ref(),ChatFile.fromBody(KeetsWire.toChatMessage(KeetsWire.decode(reverse)).body).ref());
        }finally{erase(source);erase(dest);}
    }
    @Test public void emptyFileIsAuthenticatedAndDownloadIsNotTriggeredByOfferParsing()throws Exception {
        Path source=Files.createTempDirectory("keets-empty-"),dest=Files.createTempDirectory("keets-empty-recv-");
        try(Mesh mesh=new Mesh()){
            ChatFile f=KeetsFiles.publish(mesh,source.resolve("payload.bin"),prepare(source,0),()->false,n->{});
            KeetsWire.toChatMessage(KeetsWire.decode(KeetsWire.encode(ChatMessage.text("empty",f.body(),1),"")));
            assertEquals(0,mesh.fetches);KeetsFiles.download(mesh,f,dest,()->false,n->{});assertEquals(0,Files.size(dest.resolve("verified.bin")));
        }finally{erase(source);erase(dest);}
    }
    @Test public void corruptCiphertextNeverCreatesPlaintextAndCanRetry()throws Exception {
        Path source=Files.createTempDirectory("keets-corrupt-"),dest=Files.createTempDirectory("keets-corrupt-recv-");
        try(Mesh mesh=new Mesh()){
            ChatFile f=KeetsFiles.publish(mesh,source.resolve("payload.bin"),prepare(source,200000),()->false,n->{});
            mesh.corrupt=true;try{KeetsFiles.download(mesh,f,dest,()->false,n->{});fail();}catch(IOException expected){}
            assertFalse(Files.exists(dest.resolve("verified.bin")));
            mesh.corrupt=false;KeetsFiles.download(mesh,f,dest,()->false,n->{});assertEquals(f.digest,digest(dest.resolve("verified.bin")));
        }finally{erase(source);erase(dest);}
    }
    @Test public void torrentOffersCannotSilentlyBecomePhotoCaptionsAndOversizeIsRejected()throws Exception {
        Path dir=Files.createTempDirectory("keets-invalid-");
        try{
            ChatFile f=prepare(dir,1);
            try{KeetsWire.encode(ChatMessage.text("unsupported",f.body(),1),"");fail();}catch(IllegalArgumentException expected){}
            ChatFile huge=new ChatFile(f.id,f.name,f.mime,ChatFile.TRANSFER_MAX_BYTES+1,f.key,f.nonce,f.digest,"keets://"+"a".repeat(64)+"/private/"+f.id+"/manifest?hash="+"b".repeat(64));
            try{KeetsFiles.validate(huge);fail();}catch(IOException expected){}
        }finally{erase(dir);}
    }
}
