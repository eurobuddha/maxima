package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.chat.ChatFile;
import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrivateTorrentTest {
    static int port()throws Exception { try(ServerSocket s=new ServerSocket(0)){return s.getLocalPort();} }
    static ChatFile prepare(Path dir,byte[] plain)throws Exception {
        String id=FileCrypto.randomHex(16),key=FileCrypto.randomHex(32),nonce=FileCrypto.randomHex(8);
        Path payload=dir.resolve("payload.bin");
        String digest=FileCrypto.encrypt(new ByteArrayInputStream(plain),payload,id,plain.length,key,nonce,()->false,n->{});
        return new ChatFile(id,"private.txt","text/plain",plain.length,key,nonce,digest,
                Base64.getEncoder().encodeToString(PrivateTorrent.make(payload)));
    }
    @Test public void authenticatedRecordsRejectTamperingAndLeaveNoPlaintext()throws Exception {
        Path dir=Files.createTempDirectory("private-crypto-"); byte[] plain=new byte[FileCrypto.PLAIN_PIECE+19];
        new Random(42).nextBytes(plain); ChatFile f=prepare(dir,plain);
        Path out=dir.resolve("result"); FileCrypto.decrypt(dir.resolve("payload.bin"),out,f,()->false);
        assertArrayEquals(plain,Files.readAllBytes(out)); Files.delete(out);
        try(RandomAccessFile r=new RandomAccessFile(dir.resolve("payload.bin").toFile(),"rw")){r.seek(60);int v=r.read();r.seek(60);r.write(v^1);}
        try {FileCrypto.decrypt(dir.resolve("payload.bin"),out,f,()->false);fail();}catch(javax.crypto.AEADBadTagException expected){}
        assertFalse(Files.exists(out));
        try(java.util.stream.Stream<Path> paths=Files.list(dir)){assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith(".verifying-")));}
    }
    @Test public void emptyFileAndEnvelopeRoundTrip()throws Exception {
        Path dir=Files.createTempDirectory("private-empty-"); ChatFile f=prepare(dir,new byte[0]);
        ChatFile parsed=ChatFile.fromBody(f.body()); assertEquals(f.id,parsed.id); assertEquals(0,parsed.size);
        FileCrypto.decrypt(dir.resolve("payload.bin"),dir.resolve("out"),parsed,()->false);
        assertEquals(0,Files.size(dir.resolve("out")));
    }
    @Test(timeout=60000) public void realTrackerlessLoopbackTransfer()throws Exception {
        Path seed=Files.createTempDirectory("private-seed-"), download=Files.createTempDirectory("private-download-");
        byte[] plain=new byte[2*1024*1024+19];new Random(12).nextBytes(plain);ChatFile f=prepare(seed,plain);
        CountDownLatch done=new CountDownLatch(1);
        try(PrivateTorrent a=new PrivateTorrent(port(),true);PrivateTorrent b=new PrivateTorrent(port(),true)){
            a.start(f,seed,(x,y)->{},()->{},msg->fail(msg));
            b.start(f,download,(x,y)->{},done::countDown,msg->fail(msg));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(done.getCount()>0 && System.nanoTime()<until){ b.peer(f.id,"127.0.0.1",a.port());done.await(500,TimeUnit.MILLISECONDS); }
            assertEquals("Direct transfer completed without a tracker or DHT",0,done.getCount());
            b.pause(f.id);
            FileCrypto.decrypt(download.resolve("payload.bin"),download.resolve("out"),f,()->false);
            assertArrayEquals(plain,Files.readAllBytes(download.resolve("out")));
        }
    }
    @Test public void malformedMetadataIsRejectedBeforeAllocation() throws Exception {
        for (String bad : new String[]{"d4:info2147483647:", "d4:infod4:name99999:xee", "d4:infod4:namellllllleeeeeeeee",
                "d4:infode4:infodee", "d8:announce1:xe", "d4:infodeeJUNK"}) {
            try { BoundedMetadata.check(bad.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); fail(bad); }
            catch(IOException expected) { }
        }
    }
    @Test(timeout=60000) public void sharedParlonsPortTransfersAndResumes() throws Exception {
        Path seed=Files.createTempDirectory("private-tunnel-seed-"), download=Files.createTempDirectory("private-tunnel-download-");
        byte[] plain=new byte[3*1024*1024+19];new Random(15).nextBytes(plain);ChatFile f=prepare(seed,plain);
        com.eurobuddha.maxima.core.net.DirectEndpoint endpoint = new com.eurobuddha.maxima.core.net.DirectEndpoint(
                com.eurobuddha.maxima.core.identity.MaximaIdentity.create().identity,"1.0.48",in -> {});
        assertTrue(endpoint.start(0)>0);
        CountDownLatch done=new CountDownLatch(1),piece=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> error = new java.util.concurrent.atomic.AtomicReference<>();
        try(PrivateTorrent a=new PrivateTorrent(port(),true);PrivateTorrent b=new PrivateTorrent(port(),true);
                TorrentTunnel incoming=new TorrentTunnel(true);TorrentTunnel outgoing=new TorrentTunnel(true)){
            String token=FileCrypto.randomHex(32);
            incoming.expose(token,PrivateTorrent.validate(f).getTorrentId().getBytes(),a.port());
            a.start(f,seed,(x,y)->{},()->{},error::set);
            b.start(f,download,(x,y)->{if(x>0)piece.countDown();},done::countDown,error::set);
            int proxy=outgoing.connect("127.0.0.1",endpoint.port(),token);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(piece.getCount()>0 && System.nanoTime()<until){b.tunnelPeer(f.id,proxy);piece.await(500,TimeUnit.MILLISECONDS);}
            assertEquals("At least one verified piece arrived through the existing port",0,piece.getCount());
            b.pause(f.id);outgoing.disconnect(proxy);
            proxy=outgoing.connect("127.0.0.1",endpoint.port(),token);
            // Restart against the same partial ciphertext, preserving the original offer and key.
            b.start(f,download,(x,y)->{},done::countDown,error::set);
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            while(done.getCount()>0 && System.nanoTime()<until){b.tunnelPeer(f.id,proxy);done.await(500,TimeUnit.MILLISECONDS);}
            assertNull(error.get());assertEquals(0,done.getCount());b.pause(f.id);
            FileCrypto.decrypt(download.resolve("payload.bin"),download.resolve("out"),f,()->false);
            assertArrayEquals(plain,Files.readAllBytes(download.resolve("out")));
        } finally { endpoint.stop(); }
    }
    @Test public void gatewayRejectsExtensionsAndOversizeRequests() throws Exception {
        TorrentTunnel.validatePacket(new byte[]{0},1);
        for(byte[] packet:new byte[][]{new byte[]{20,0},new byte[]{9,0,0},new byte[]{6,0,0,0,0,0,0,0,0,0,1,0,0}}){
            try{TorrentTunnel.validatePacket(packet,packet.length);fail();}catch(IOException expected){}
        }
    }
}
