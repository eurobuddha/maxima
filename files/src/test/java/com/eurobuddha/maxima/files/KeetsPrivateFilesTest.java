package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.chat.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the real account file controller + chat router, beyond codec-only fixtures. */
public class KeetsPrivateFilesTest {
    private static final String ALICE="0x"+"1".repeat(64),BOB="0x"+"2".repeat(64);
    static final class Mesh implements KeetsTransport {
        final Map<String,byte[]> media=new java.util.concurrent.ConcurrentHashMap<>();
        volatile String message;
        public boolean send(String to,String payload){message=payload;return true;}
        public String publish(String path,byte[] bytes)throws Exception {
            String url="keets://"+"a".repeat(64)+path+"?hash="+FileCrypto.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            media.put(url,bytes.clone());return url;
        }
        public byte[] fetch(String url)throws Exception {byte[] b=media.get(url);if(b==null)throw new IOException("offline");return b.clone();}
    }
    private static void ready(PrivateFiles files,String id)throws Exception {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(!"true".equals(files.status(id).get("ready")) && System.nanoTime()<end)Thread.sleep(20);
        assertEquals(files.status(id).toString(),"true",files.status(id).get("ready"));
    }
    @Test(timeout=30000) public void accountControllerRoutesKeetsWithoutTorrentAndPersistsVerifiedDownload()throws Exception {
        try(PrivateFilesTest.Fixture a=new PrivateFilesTest.Fixture();PrivateFilesTest.Fixture b=new PrivateFilesTest.Fixture()) {
            Mesh mesh=new Mesh();a.chat.setKeetsTransport(mesh);b.chat.setKeetsTransport(mesh);
            a.node.storeKeetsContact(BOB,"Bob");b.node.storeKeetsContact(ALICE,"Alice");
            byte[] plain=new byte[2*1024*1024];new Random(3).nextBytes(plain);
            String id=a.files.send(new ByteArrayInputStream(plain),plain.length,"cross-chat.bin","application/octet-stream",BOB,false);
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(mesh.message==null && System.nanoTime()<end)Thread.sleep(20);
            assertNotNull(mesh.message);assertEquals("file",KeetsWire.decode(mesh.message).state);
            assertTrue(b.chat.onKeetsInbound(ALICE,mesh.message,"transport-id"));
            ChatFile offer=ChatFile.fromBody(b.chat.conversation(ALICE).get(0).body);
            assertTrue(offer.isKeets());assertTrue(b.files.list().isEmpty()); // no unsolicited download
            b.files.receive(offer.ref(),ALICE,false);ready(b.files,id);ready(a.files,id);
            try(InputStream in=b.files.open(id)){assertArrayEquals(plain,in.readAllBytes());}
            java.lang.reflect.Field engine=PrivateFiles.class.getDeclaredField("engine");engine.setAccessible(true);
            assertNull("No torrent or fleet route for a Keets file",engine.get(a.files));assertNull(engine.get(b.files));
            b.files.pause(id);b.files.close();
            try(PrivateFiles restored=new PrivateFiles(b.node,b.chat,b.root.resolve("files"))){
                assertEquals("true",restored.status(id).get("ready"));
                try(InputStream in=restored.open(id)){assertArrayEquals(plain,in.readAllBytes());}
            }
        }
    }
}
