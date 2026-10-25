package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.chat.ChatFile;
import com.eurobuddha.maxima.core.chat.FileUpload;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercise native recovery against the real account's idempotent staging API. */
public class FileUploadTest {
    private static byte[] data(){byte[] b=new byte[PrivateFiles.BLOCK*2+19];new Random(51).nextBytes(b);return b;}
    private static FileUpload upload(PrivateFilesTest.Fixture f,FileUpload.Backend backend,long size)throws Exception {
        return new FileUpload(backend,FileCrypto.randomHex(16),"native.bin","application/octet-stream",size,f.peer.publicKeyHex(),false);
    }
    @Test(timeout=20000) public void lostRepliesRetrySameUploadWithoutCancellingOrDuplicating()throws Exception {
        try(PrivateFilesTest.Fixture f=new PrivateFilesTest.Fixture()) {
            Set<String> lost=new HashSet<>();List<Map<String,String>> calls=new ArrayList<>();
            byte[] plain=data();
            FileUpload u=upload(f,p->{
                calls.add(new LinkedHashMap<>(p));Map<String,String> response=f.commands.call(p);
                if(lost.add(p.get("action")))throw new IOException("lost accepted reply");return response;
            },plain.length);
            u.run(new ByteArrayInputStream(plain),()->false,n->{});
            assertEquals(1,calls.stream().map(p->p.get("id")).distinct().count());
            assertFalse(calls.stream().anyMatch(p->"cancelUpload".equals(p.get("action"))));
            assertEquals(2,calls.stream().filter(p->"finish".equals(p.get("action"))).count());
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            Map<String,String> state;
            do{state=f.call("action","status","id",u.id);if("true".equals(state.get("ready")))break;Thread.sleep(20);}while(System.nanoTime()<until);
            assertEquals("true",state.get("ready"));assertEquals(1,f.files.list().size());
            try(InputStream in=f.files.open(state.get("fileId"))){assertArrayEquals(plain,in.readAllBytes());}
        }
    }
    @Test(timeout=15000) public void exhaustedRetriesResumeAtAcceptedOffsetAndExplicitCancelRemovesBytes()throws Exception {
        try(PrivateFilesTest.Fixture f=new PrivateFilesTest.Fixture()) {
            byte[] plain=data();AtomicBoolean offline=new AtomicBoolean(true);List<Long> offsets=new ArrayList<>();
            FileUpload u=upload(f,p->{
                Map<String,String> response=f.commands.call(p);
                if("append".equals(p.get("action"))){offsets.add(Long.parseLong(p.get("offset")));if(offline.get())throw new IOException("offline");}
                return response;
            },plain.length);
            try{u.run(new ByteArrayInputStream(plain),()->false,n->{});fail();}catch(IOException expected){}
            assertEquals(Arrays.asList(0L,0L,0L),offsets);
            Path staged=f.root.resolve("files/uploads").resolve(u.id);
            assertEquals(PrivateFiles.BLOCK,Files.size(staged));
            byte[] changed=plain.clone();changed[0]^=1;offline.set(false);
            try{u.run(new ByteArrayInputStream(changed),()->false,n->{});fail("Changed prefix was accepted");}catch(IOException expected){}
            assertEquals(3,offsets.size());
            // Cancel is the sole operation that deletes accepted, unsubmitted bytes.
            u.cancel();u.cancel();assertFalse(Files.exists(staged));
            FileUpload neverStarted=upload(f,f.commands::call,1);neverStarted.cancel();

            FileUpload resumed=upload(f,p->{Map<String,String> r=f.commands.call(p);
                if("append".equals(p.get("action"))&&offline.get())throw new IOException("offline");return r;},plain.length);
            offline.set(true);try{resumed.run(new ByteArrayInputStream(plain),()->false,n->{});fail();}catch(IOException expected){}
            offline.set(false);AtomicLong initial=new AtomicLong(-1);
            resumed.run(new ByteArrayInputStream(plain),()->false,n->initial.compareAndSet(-1,n));
            assertEquals(PrivateFiles.BLOCK,initial.get());
        }
    }
    @Test public void badOffsetResponseCannotFinishOrDiscardUpload()throws Exception {
        try(PrivateFilesTest.Fixture f=new PrivateFilesTest.Fixture()) {
            AtomicBoolean finished=new AtomicBoolean();byte[] plain=data();
            FileUpload u=upload(f,p->{
                if("finish".equals(p.get("action")))finished.set(true);
                Map<String,String> r=f.commands.call(p);if("append".equals(p.get("action")))r.put("offset","-1");return r;
            },plain.length);
            try{u.run(new ByteArrayInputStream(plain),()->false,n->{});fail();}catch(IOException expected){}
            assertFalse(finished.get());assertEquals(PrivateFiles.BLOCK,Files.size(f.root.resolve("files/uploads").resolve(u.id)));
        }
    }
    @Test public void hundredMiBLimitRejectsOneExtraByteBeforeStagingAndKeepsLegacyOffersReadable()throws Exception {
        try(PrivateFilesTest.Fixture f=new PrivateFilesTest.Fixture()) {
            assertEquals(104857600L,ChatFile.TRANSFER_MAX_BYTES);
            upload(f,f.commands::call,ChatFile.TRANSFER_MAX_BYTES);
            try{upload(f,f.commands::call,ChatFile.TRANSFER_MAX_BYTES+1);fail();}catch(IOException expected){}
            String id=FileCrypto.randomHex(16);
            try{f.call("action","begin","id",id,"name","oversize","size",Long.toString(ChatFile.TRANSFER_MAX_BYTES+1),"peer",f.peer.publicKeyHex());fail();}catch(IOException expected){}
            assertFalse(Files.exists(f.root.resolve("files/uploads").resolve(id)));
            String zero="0".repeat(64);
            ChatFile legacy=new ChatFile(id,"legacy.bin","application/octet-stream",ChatFile.MAX_BYTES,zero,"0".repeat(16),zero,"");
            assertEquals(ChatFile.MAX_BYTES,ChatFile.parse(legacy.ref()).size);
            try{f.files.receive(legacy.ref(),f.peer.publicKeyHex(),false);fail();}catch(IOException expected){}
            assertTrue(f.files.list().isEmpty());
        }
    }
}
