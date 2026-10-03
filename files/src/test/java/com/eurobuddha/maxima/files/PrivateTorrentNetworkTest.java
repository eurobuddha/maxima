package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.chat.ChatFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static com.eurobuddha.maxima.files.PrivateTorrentTest.*;

/** Real sockets and synthetic data; exercises account listeners as well as phone listeners. */
public class PrivateTorrentNetworkTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    @Test(timeout=150000) public void largeFileTransfersThroughReverseNodeConnection() throws Exception {
        Path seed=temporary.newFolder("large-seed").toPath(), download=temporary.newFolder("large-download").toPath();
        byte[] plain=new byte[64*1024*1024+19];new Random(18).nextBytes(plain);ChatFile f=prepare(seed,plain);
        int relayPort=port();
        com.eurobuddha.maxima.server.RelayServer endpoint=new com.eurobuddha.maxima.server.RelayServer(
                com.eurobuddha.maxima.core.identity.MaximaIdentity.create().identity,relayPort,"1.0.48");
        endpoint.start();
        CountDownLatch done=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
        try(PrivateTorrent sender=new PrivateTorrent(port(),true);PrivateTorrent receiver=new PrivateTorrent(port(),true);
                TorrentTunnel incoming=new TorrentTunnel(true);TorrentTunnel outgoing=new TorrentTunnel(true)) {
            String token=FileCrypto.randomHex(32);
            incoming.expose(token,PrivateTorrent.validate(f).getTorrentId().getBytes(),receiver.port());
            sender.start(f,seed,(x,y)->{},()->{},error::set);
            receiver.start(f,download,(x,y)->{},done::countDown,error::set);
            int proxy=outgoing.connect("127.0.0.1",relayPort,token);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(110);
            while(done.getCount()>0 && System.nanoTime()<until){sender.tunnelPeer(f.id,proxy);done.await(500,TimeUnit.MILLISECONDS);}
            assertNull(error.get());assertEquals("64 MiB reverse transfer completed",0,done.getCount());
            receiver.pause(f.id);
            FileCrypto.decrypt(download.resolve("payload.bin"),download.resolve("out"),f,()->false);
            assertArrayEquals(plain,Files.readAllBytes(download.resolve("out")));
        } finally { endpoint.stop(); }
    }

    @Test(timeout=40000) public void nodeRelayPortTransfersPrivateFile() throws Exception {
        Path seed=temporary.newFolder("relay-seed").toPath(), download=temporary.newFolder("relay-download").toPath();
        byte[] plain=new byte[2*1024*1024+19];new Random(23).nextBytes(plain);ChatFile f=prepare(seed,plain);
        int relayPort=port();
        com.eurobuddha.maxima.server.RelayServer relay=new com.eurobuddha.maxima.server.RelayServer(
                com.eurobuddha.maxima.core.identity.MaximaIdentity.create().identity,relayPort,"1.0.48");
        relay.start();
        CountDownLatch done=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
        try(PrivateTorrent sender=new PrivateTorrent(port(),true);PrivateTorrent receiver=new PrivateTorrent(port(),true);
                TorrentTunnel incoming=new TorrentTunnel(true);TorrentTunnel outgoing=new TorrentTunnel(true)) {
            String token=FileCrypto.randomHex(32);
            incoming.expose(token,PrivateTorrent.validate(f).getTorrentId().getBytes(),sender.port());
            sender.start(f,seed,(x,y)->{},()->{},error::set);
            receiver.start(f,download,(x,y)->{},done::countDown,error::set);
            int proxy=outgoing.connect("127.0.0.1",relayPort,token);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(done.getCount()>0 && System.nanoTime()<until){receiver.tunnelPeer(f.id,proxy);done.await(500,TimeUnit.MILLISECONDS);}
            assertNull(error.get());assertEquals("Transfer through the account node relay listener",0,done.getCount());
            receiver.pause(f.id);
            FileCrypto.decrypt(download.resolve("payload.bin"),download.resolve("out"),f,()->false);
            assertArrayEquals(plain,Files.readAllBytes(download.resolve("out")));
        } finally { relay.stop(); }
    }

}
