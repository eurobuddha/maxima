package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.*;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.rpc.*;
import com.eurobuddha.maxima.core.store.FileStore;
import com.eurobuddha.maxima.core.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** All identities and files are fresh. No node is started and no external address is supplied. */
public class PrivateFilesTest {
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("private-files-api-");
        final MaximaIdentity peer=MaximaIdentity.create().identity;
        final MaximaNode node=new MaximaNode(MaximaIdentity.create().identity,"1.0.48",1);
        final ChatEngine chat=new ChatEngine(node);
        final FileStore store=new FileStore(root.resolve("chat").toFile());
        final PrivateFiles files;
        final FileCommands commands;
        Fixture()throws Exception {this(false);}
        Fixture(boolean hosted)throws Exception {
            Contact contact=new Contact(peer.publicKeyHex());contact.capabilities=Capabilities.phoneDefaults();node.storeContact(contact);
            chat.setStore(store);files=new PrivateFiles(node,chat,root.resolve("files"),hosted);commands=new FileCommands(files,root.resolve("files/uploads"));
        }
        Map<String,String> call(String... pairs)throws Exception {Map<String,String> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i],pairs[i+1]);return commands.call(m);}
        public void close(){commands.close();files.close();chat.close();node.stop();
            try(java.util.stream.Stream<Path> paths=Files.walk(root)){
                for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);
            }catch(IOException e){throw new java.io.UncheckedIOException(e);}
        }
    }
    @Test(timeout=30000) public void rtcSignallingRequiresContactAndCurrentAcceptedCapability()throws Exception {
        try(Fixture f=new Fixture()){
            java.util.concurrent.atomic.AtomicInteger signals=new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger stopped=new java.util.concurrent.atomic.AtomicInteger();
            f.files.setPeerTransport(new PeerDataTransport(){
                public void connect(String id,String peer,boolean offerer,int gateway,String local,String remote,
                        java.util.function.BiConsumer<String,String> send,java.util.function.IntConsumer ready){}
                public void signal(String id,String peer,Map<String,String> m){signals.incrementAndGet();}
                public void disconnect(String id,String peer){stopped.incrementAndGet();}
                public void stop(String id){stopped.incrementAndGet();} public void close(){}
            });
            String id=f.files.send(new ByteArrayInputStream(new byte[]{1}),1,"one.bin","application/octet-stream",f.peer.publicKeyHex(),false);
            byte[] peerRequest=new Json.Writer().put("id",id).done().getBytes(StandardCharsets.UTF_8);
            byte[] intro=f.node.services().dispatchLocal(new ServiceRegistry.Request(PrivateFiles.PEERS,peerRequest,f.peer.publicKey(),Collections.emptyList()));
            String token=Json.parse(new String(intro,StandardCharsets.UTF_8)).get("token");
            byte[] message=new Json.Writer().put("id",id).put("token",token).put("fromToken",FileCrypto.randomHex(32))
                    .put("kind","offer").put("body","synthetic").done().getBytes(StandardCharsets.UTF_8);
            ServiceRegistry.Request valid=new ServiceRegistry.Request(PrivateFiles.SIGNAL,message,f.peer.publicKey(),Collections.emptyList());
            ServiceRegistry.Request foreign=new ServiceRegistry.Request(PrivateFiles.SIGNAL,message,MaximaIdentity.create().identity.publicKey(),Collections.emptyList());
            assertTrue(f.node.services().dispatch("x",foreign).isError());assertEquals(0,signals.get());
            assertFalse(f.node.services().dispatch("x",valid).isError());assertEquals(1,signals.get());
            byte[] complete=new Json.Writer().put("id",id).put("rtc","1").put("complete","true").done().getBytes(StandardCharsets.UTF_8);
            f.node.services().dispatchLocal(new ServiceRegistry.Request(PrivateFiles.PEERS,complete,f.peer.publicKey(),Collections.emptyList()));
            assertTrue("Complete peers release the active transport",stopped.get()>0);
            f.files.pause(id);assertTrue(stopped.get()>0);
            assertTrue(f.node.services().dispatch("x",valid).isError());
            f.files.resume(id);assertTrue(f.node.services().dispatch("x",valid).isError());assertEquals(1,signals.get());
        }
    }
    @Test public void uploadRetriesAreIdempotentAndConflictsAreRejected()throws Exception {
        try(Fixture f=new Fixture()){
            String id=FileCrypto.randomHex(16);
            f.call("action","begin","id",id,"size","3","name","test.txt","peer",f.peer.publicKeyHex());
            assertEquals("3",f.call("action","append","id",id,"offset","0","data","YWJj").get("offset"));
            assertEquals("3",f.call("action","append","id",id,"offset","0","data","YWJj").get("offset"));
            try{f.call("action","append","id",id,"offset","0","data","eHl6");fail();}catch(IOException expected){}
            try{f.call("action","begin","id",id,"size","4","name","test.txt","peer",f.peer.publicKeyHex());fail();}catch(IOException expected){}
            f.call("action","cancelUpload","id",id);
            assertFalse(Files.exists(f.root.resolve("files/uploads").resolve(id)));
        }
    }
    private static Map<String,String> awaitUpload(Fixture f,String id,boolean failed)throws Exception {
        long until=System.currentTimeMillis()+10000;
        Map<String,String> status;
        do {
            status=f.call("action","status","id",id);
            if(failed ? "Failed".equals(status.get("status")) : status.containsKey("fileId") && "true".equals(status.get("ready")))return status;
            Thread.sleep(25);
        }while(System.currentTimeMillis()<until);
        throw new AssertionError("Upload did not reach expected state: "+status);
    }
    @Test(timeout=30000) public void failedPreparationRetainsUploadAndResumesAfterCapacityIsFreed()throws Exception {
        try(Fixture f=new Fixture()) {
            List<String> active=new ArrayList<>();
            for(int i=0;i<4;i++)active.add(f.files.send(new ByteArrayInputStream(new byte[]{1}),1,"active.txt","text/plain",f.peer.publicKeyHex(),false));
            String id=FileCrypto.randomHex(16);
            f.call("action","begin","id",id,"size","3","name","retry.txt","peer",f.peer.publicKeyHex());
            f.call("action","append","id",id,"offset","0","data","YWJj");
            f.call("action","finish","id",id);
            assertTrue(awaitUpload(f,id,true).get("error").contains("4 active"));
            Path staged=f.root.resolve("files/uploads").resolve(id);
            assertArrayEquals(new byte[]{97,98,99},Files.readAllBytes(staged));
            assertTrue("Failed preparations remain accessible after closing the sheet",f.call("action","list").get("transfers").contains(id));
            f.files.pause(active.get(0));
            f.call("action","resume","id",id);
            f.call("action","finish","id",id); // lost-reply retry must not send twice
            Map<String,String> ready=awaitUpload(f,id,false);
            String fileId=ready.get("fileId");
            assertFalse(Files.exists(staged));assertEquals(5,f.files.list().size());
            try(InputStream in=f.files.open(fileId)){assertArrayEquals(new byte[]{97,98,99},in.readAllBytes());}
            f.call("action","finish","id",id);
            f.call("action","resume","id",id);
            assertEquals(fileId,f.call("action","status","id",id).get("fileId"));
            assertEquals(5,f.files.list().size());
        }
    }
    @Test(timeout=15000) public void removingFailedPreparationDeletesRetainedInput()throws Exception {
        try(Fixture f=new Fixture()) {
            String id=FileCrypto.randomHex(16);
            f.call("action","begin","id",id,"size","1","name","retry.txt","peer","removed-contact");
            f.call("action","append","id",id,"offset","0","data","AQ==");f.call("action","finish","id",id);
            awaitUpload(f,id,true);
            assertTrue(Files.exists(f.root.resolve("files/uploads").resolve(id)));
            f.call("action","remove","id",id);
            assertFalse(Files.exists(f.root.resolve("files/uploads").resolve(id)));
            assertFalse(f.call("action","list").get("transfers").contains(id));
        }
    }
    @Test public void arbitraryOffersCannotCreateDownloads()throws Exception {
        try(Fixture f=new Fixture()){
            Path source=Files.createDirectory(f.root.resolve("source"));ChatFile offer=PrivateTorrentTest.prepare(source,new byte[]{1,2,3});
            try{f.files.receive(offer.ref(),f.peer.publicKeyHex(),false);fail();}catch(SecurityException expected){}
            assertTrue(f.files.list().isEmpty());assertFalse(Files.exists(f.root.resolve("files").resolve(offer.id)));
            ServiceRegistry.Request request=new ServiceRegistry.Request(PrivateFiles.PEERS,new Json.Writer().put("id",offer.id).done().getBytes(StandardCharsets.UTF_8),f.peer.publicKey(),Collections.emptyList());
            assertTrue(f.node.services().dispatch("synthetic",request).isError());
        }
    }
    @Test public void invalidSendAlwaysClosesItsInput()throws Exception {
        try(Fixture f=new Fixture()){
            final boolean[] closed={false};InputStream input=new ByteArrayInputStream(new byte[0]){@Override public void close(){closed[0]=true;}};
            try{f.files.send(input,ChatFile.MAX_BYTES+1,"file","text/plain",f.peer.publicKeyHex(),false);fail();}catch(IOException expected){}
            assertTrue(closed[0]);assertTrue(f.files.list().isEmpty());
        }
    }
    @Test(timeout=30000) public void preparedFileSurvivesRestartPausedAndCanBeRemoved()throws Exception {
        try(Fixture f=new Fixture()){
            byte[] plain="private content".getBytes(StandardCharsets.UTF_8);
            String id=f.files.send(new ByteArrayInputStream(plain),plain.length,"private.txt","text/plain",f.peer.publicKeyHex(),false);
            long until=System.currentTimeMillis()+10000;
            while(!"true".equals(f.files.status(id).get("ready")) && System.currentTimeMillis()<until)Thread.sleep(50);
            assertEquals("true",f.files.status(id).get("ready"));
            try(InputStream in=f.files.open(id)){assertArrayEquals(plain,in.readAllBytes());}
            ServiceRegistry.Request allowed=new ServiceRegistry.Request(PrivateFiles.PEERS,new Json.Writer().put("id",id).done().getBytes(StandardCharsets.UTF_8),f.peer.publicKey(),Collections.emptyList());
            assertFalse(f.node.services().dispatch("synthetic",allowed).isError());
            ServiceRegistry.Request stranger=new ServiceRegistry.Request(PrivateFiles.PEERS,allowed.payload,MaximaIdentity.create().identity.publicKey(),Collections.emptyList());
            assertTrue(f.node.services().dispatch("synthetic",stranger).isError());
            f.files.pause(id);assertTrue(f.node.services().dispatch("synthetic",allowed).isError());
            f.files.close();
            try(PrivateFiles reopened=new PrivateFiles(f.node,f.chat,f.root.resolve("files"))){
                assertEquals("true",reopened.status(id).get("paused"));
                assertEquals("true",reopened.status(id).get("ready"));
                reopened.remove(id);assertTrue(reopened.list().isEmpty());assertFalse(Files.exists(f.root.resolve("files").resolve(id)));
            }
        }
    }
    @Test(timeout=25000) public void engineStorageFailureBecomesVisibleAndCanResume()throws Exception {
        try(Fixture f=new Fixture()) {
            String id=f.files.send(new ByteArrayInputStream(new byte[]{7}),1,"recover.txt","text/plain",f.peer.publicKeyHex(),false);
            f.files.pause(id);f.files.close();
            Path dir=f.root.resolve("files").resolve(id),payload=dir.resolve("payload.bin");
            byte[] encrypted=Files.readAllBytes(payload);
            Files.deleteIfExists(dir.resolve("verified.bin"));Files.delete(payload);Files.createDirectory(payload);
            try(PrivateFiles reopened=new PrivateFiles(f.node,f.chat,f.root.resolve("files"))) {
                reopened.resume(id);
                long until=System.currentTimeMillis()+5000;
                while(!"Failed".equals(reopened.status(id).get("status")) && System.currentTimeMillis()<until)Thread.sleep(20);
                assertEquals("Failed",reopened.status(id).get("status"));
                assertEquals("true",reopened.status(id).get("paused"));
                assertFalse(reopened.status(id).get("error").isEmpty());
                Files.delete(payload);Files.write(payload,encrypted);reopened.resume(id);
                until=System.currentTimeMillis()+5000;
                while(!"true".equals(reopened.status(id).get("ready")) && System.currentTimeMillis()<until)Thread.sleep(20);
                assertEquals("true",reopened.status(id).get("ready"));
                try(InputStream in=reopened.open(id)){assertArrayEquals(new byte[]{7},in.readAllBytes());}
            }
        }
    }
    @Test public void interruptedPreparationIsVisibleAndRemovable()throws Exception {
        try(Fixture f=new Fixture()){
            f.files.close();String id=FileCrypto.randomHex(16);Path dir=Files.createDirectory(f.root.resolve("files").resolve(id));
            Files.write(dir.resolve("payload.bin"),new byte[]{1,2,3});
            try(PrivateFiles reopened=new PrivateFiles(f.node,f.chat,f.root.resolve("files"))){
                assertEquals("Failed",reopened.status(id).get("status"));
                reopened.remove(id);assertFalse(Files.exists(dir));
            }
        }
    }
    private static void awaitSharing(Fixture f,PrivateFiles files,String id)throws Exception {
        ServiceRegistry.Request request=new ServiceRegistry.Request(PrivateFiles.PEERS,new Json.Writer().put("id",id).done().getBytes(StandardCharsets.UTF_8),f.peer.publicKey(),Collections.emptyList());
        long until=System.currentTimeMillis()+10000;
        while(f.node.services().dispatch("synthetic",request).isError() && System.currentTimeMillis()<until)Thread.sleep(25);
        assertFalse(f.node.services().dispatch("synthetic",request).isError());
        assertEquals("false",files.status(id).get("paused"));
    }
    @Test(timeout=30000) public void activeShareResumesAfterRestartAndExplicitPausePersists()throws Exception {
        try(Fixture f=new Fixture()){
            byte[] plain="restart sharing".getBytes(StandardCharsets.UTF_8);
            String id=f.files.send(new ByteArrayInputStream(plain),plain.length,"restart.txt","text/plain",f.peer.publicKeyHex(),false);
            long until=System.currentTimeMillis()+10000;
            while(!"true".equals(f.files.status(id).get("ready")) && System.currentTimeMillis()<until)Thread.sleep(25);
            assertEquals("true",f.files.status(id).get("ready"));
            f.files.close();
            try(PrivateFiles reopened=new PrivateFiles(f.node,f.chat,f.root.resolve("files"))){
                awaitSharing(f,reopened,id);
                assertEquals("Ready · sharing",reopened.status(id).get("status"));
                try(InputStream in=reopened.open(id)){assertArrayEquals(plain,in.readAllBytes());}
                reopened.pause(id);
            }
            try(PrivateFiles paused=new PrivateFiles(f.node,f.chat,f.root.resolve("files"))){
                Thread.sleep(2200);assertEquals("true",paused.status(id).get("paused"));
                paused.resume(id);
            }
            try(PrivateFiles resumed=new PrivateFiles(f.node,f.chat,f.root.resolve("files"))){awaitSharing(f,resumed,id);}
        }
    }
    @Test(timeout=30000) public void unfinishedAcceptedDownloadResumes()throws Exception {
        try(Fixture f=new Fixture()){
            f.files.close();
            Path source=Files.createDirectory(f.root.resolve("source"));ChatFile offer=PrivateTorrentTest.prepare(source,new byte[2*FileCrypto.PLAIN_PIECE]);
            Path dir=Files.createDirectory(f.root.resolve("files").resolve(offer.id));
            Files.write(dir.resolve("payload.bin"),Arrays.copyOf(Files.readAllBytes(source.resolve("payload.bin")),FileCrypto.PIECE));
            String saved=new Json.Writer().put("ref",offer.ref()).put("peer",f.peer.publicKeyHex()).put("group","false")
                    .put("mine","false").put("members",f.peer.publicKeyHex().toLowerCase(Locale.ROOT)).put("paused","false").done();
            Files.write(dir.resolve("offer.json"),saved.getBytes(StandardCharsets.UTF_8));
            try(PrivateFiles reopened=new PrivateFiles(f.node,f.chat,f.root.resolve("files"))){
                awaitSharing(f,reopened,offer.id);assertEquals("false",reopened.status(offer.id).get("ready"));
                assertEquals(1,reopened.list().size());
                java.lang.reflect.Field transfers=PrivateFiles.class.getDeclaredField("transfers");transfers.setAccessible(true);
                Object transfer=((Map<?,?>)transfers.get(reopened)).get(offer.id);
                java.lang.reflect.Field progress=transfer.getClass().getDeclaredField("lastProgressNanos");progress.setAccessible(true);
                progress.setLong(transfer,System.nanoTime()-java.util.concurrent.TimeUnit.SECONDS.toNanos(31));
                assertTrue(reopened.status(offer.id).get("error").contains("direct connection"));
                progress.setLong(transfer,System.nanoTime());
                assertEquals("",reopened.status(offer.id).get("error"));
                reopened.pause(offer.id);
                progress.setLong(transfer,System.nanoTime()-java.util.concurrent.TimeUnit.SECONDS.toNanos(31));
                assertEquals("",reopened.status(offer.id).get("error"));
            }
        }
    }
    private static java.util.concurrent.ThreadPoolExecutor fillVerificationQueue(Fixture f, java.util.concurrent.CountDownLatch release) throws Exception {
        java.lang.reflect.Field field=PrivateFiles.class.getDeclaredField("jobs");field.setAccessible(true);
        java.util.concurrent.ThreadPoolExecutor jobs=(java.util.concurrent.ThreadPoolExecutor)field.get(f.files);
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        Runnable blocker=()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        jobs.execute(blocker);assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
        for(int i=0;i<8;i++)jobs.execute(blocker);
        return jobs;
    }
    @Test(timeout=30000) public void rejectedVerificationReportsFailureWithoutBlockingTorrentWorker()throws Exception {
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        try(Fixture f=new Fixture()){
            fillVerificationQueue(f,release);
            String id=f.files.send(new ByteArrayInputStream(new byte[]{1}),1,"busy.txt","text/plain",f.peer.publicKeyHex(),false);
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
            while(!"Failed".equals(f.files.status(id).get("status")) && System.nanoTime()<until)Thread.sleep(10);
            assertEquals("Failed",f.files.status(id).get("status"));
            assertEquals("Busy; tap Resume",f.files.status(id).get("error"));
        }finally{release.countDown();}
    }
    @Test(timeout=30000) public void closeDoesNotWaitForRejectedCompletionCallback()throws Exception {
        java.util.concurrent.CountDownLatch queued=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch rejectEntered=new java.util.concurrent.CountDownLatch(1);
        Thread closing=null;
        try(Fixture f=new Fixture()){
            java.util.concurrent.ThreadPoolExecutor jobs=fillVerificationQueue(f,queued);
            jobs.setRejectedExecutionHandler((task,executor)->{
                rejectEntered.countDown();boolean interrupted=false;
                while(release.getCount()!=0)try{release.await();}catch(InterruptedException e){interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();
                throw new java.util.concurrent.RejectedExecutionException("synthetic full queue");
            });
            f.files.send(new ByteArrayInputStream(new byte[]{1}),1,"closing.txt","text/plain",f.peer.publicKeyHex(),false);
            assertTrue(rejectEntered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
            closing=new Thread(()->{try{f.files.close();}catch(Throwable e){error.set(e);}},"test-private-file-close");closing.start();
            java.lang.reflect.Field closed=PrivateFiles.class.getDeclaredField("closed");closed.setAccessible(true);
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(!closed.getBoolean(f.files) && System.nanoTime()<until)Thread.sleep(10);
            assertTrue("close owns the account monitor before the callback continues",closed.getBoolean(f.files));
            release.countDown();closing.join(3000);
            assertFalse("shutdown must not wait on its own torrent callback",closing.isAlive());
            assertNull(error.get());
        }finally{release.countDown();queued.countDown();if(closing!=null)closing.join(12000);}
    }
    @Test(timeout=30000) public void closeCleansAllTransfersAfterOneStopFails()throws Exception {
        try(Fixture f=new Fixture()){
            String a=f.files.send(new ByteArrayInputStream(new byte[]{1}),1,"first.txt","text/plain",f.peer.publicKeyHex(),false);
            String b=f.files.send(new ByteArrayInputStream(new byte[]{2}),1,"second.txt","text/plain",f.peer.publicKeyHex(),false);
            java.lang.reflect.Field engineField=PrivateFiles.class.getDeclaredField("engine");engineField.setAccessible(true);
            PrivateTorrent engine=(PrivateTorrent)engineField.get(f.files);
            try {
                PrivateTorrentTest.failAfterClientStop(engine,a);PrivateTorrentTest.failAfterClientStop(engine,b);
                Throwable error=null;try{f.files.close();}catch(Throwable e){error=e;}
                assertFalse("account close must reach runtime cleanup",PrivateTorrentTest.runtime(engine).isRunning());
                assertNull("recovered stop failure must not abort host shutdown",error);
                java.lang.reflect.Field transfers=PrivateFiles.class.getDeclaredField("transfers");transfers.setAccessible(true);
                for(Object transfer:((Map<?,?>)transfers.get(f.files)).values()){
                    java.lang.reflect.Field tunnel=transfer.getClass().getDeclaredField("tunnel");tunnel.setAccessible(true);
                    assertNull("all gateways must close even after another stop fails",tunnel.get(transfer));
                }
            }finally{PrivateTorrentTest.runtime(engine).shutdown();}
        }
    }
    @Test(timeout=30000) public void legacyStateStaysPausedUntilExplicitResume()throws Exception {
        try(Fixture f=new Fixture()){
            String id=f.files.send(new ByteArrayInputStream(new byte[]{1}),1,"legacy.txt","text/plain",f.peer.publicKeyHex(),false);
            f.files.close();Path state=f.root.resolve("files").resolve(id).resolve("offer.json");
            Map<String,String> saved=Json.parse(Files.readString(state));saved.remove("paused");
            Json.Writer writer=new Json.Writer();saved.forEach(writer::put);Files.writeString(state,writer.done());
            try(PrivateFiles legacy=new PrivateFiles(f.node,f.chat,f.root.resolve("files"))){
                Thread.sleep(2200);assertEquals("true",legacy.status(id).get("paused"));legacy.resume(id);
            }
            try(PrivateFiles reopened=new PrivateFiles(f.node,f.chat,f.root.resolve("files"))){
                // Pause during the startup delay must cancel the scheduled restoration too.
                reopened.pause(id);Thread.sleep(2200);assertEquals("true",reopened.status(id).get("paused"));
            }
        }
    }
}
