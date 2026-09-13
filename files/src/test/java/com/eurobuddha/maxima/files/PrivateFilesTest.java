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
        Fixture()throws Exception {
            Contact contact=new Contact(peer.publicKeyHex());contact.capabilities=Capabilities.phoneDefaults();node.storeContact(contact);
            chat.setStore(store);files=new PrivateFiles(node,chat,root.resolve("files"));commands=new FileCommands(files,root.resolve("files/uploads"));
        }
        Map<String,String> call(String... pairs)throws Exception {Map<String,String> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i],pairs[i+1]);return commands.call(m);}
        public void close(){commands.close();files.close();chat.close();node.stop();}
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
            }
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
