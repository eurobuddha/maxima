package com.eurobuddha.maxima.files;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.metainfo.MetadataService;
import bt.metainfo.Torrent;
import bt.net.InetPeer;
import bt.peer.IPeerRegistry;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import bt.torrent.maker.TorrentBuilder;
import com.eurobuddha.maxima.core.chat.ChatFile;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** A closed swarm: no tracker URLs, DHT, PEX, LSD, web seeds or module auto-loading.
 * Metadata and explicit peer candidates arrive exclusively through sealed Parlons messages.
 * The random ciphertext infohash is a capability; payload confidentiality is independent of it. */
public final class PrivateTorrent implements AutoCloseable {
    private final BtRuntime runtime;
    private final Map<String, BtClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Torrent> torrents = new ConcurrentHashMap<>();
    private final Map<String,Run> runs = new ConcurrentHashMap<>();
    private final Map<bt.metainfo.TorrentId,Run> stoppingEvents = new ConcurrentHashMap<>();
    private static final class Run {
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);
        boolean stopping; Thread thread;
        synchronized void enter() { thread=Thread.currentThread();if(stopping)thread.interrupt();entered.countDown(); }
        synchronized void cancel() { stopping=true;if(thread!=null)thread.interrupt(); }
        synchronized boolean active() { return !stopping; }
        synchronized void finished() { thread=null;entered.countDown();stopped.countDown(); }
    }
    private final boolean testLoopback;
    private final int port;

    public PrivateTorrent(int port) throws Exception { this(port, false); }
    PrivateTorrent(int port, boolean testLoopback) throws Exception {
        this.testLoopback = testLoopback;
        this.port = port;
        Config config = new Config();
        config.setAcceptorAddress(InetAddress.getByName("127.0.0.1"));
        config.setAcceptorPort(port);
        config.setNumOfHashingThreads(1);
        // Our records are already AES-GCM ciphertext. A standard BT handshake lets the
        // capability gateway check the exact swarm before exposing the loopback engine.
        config.setMseDisabled(true);
        config.setTransferBlockSize(16384);config.setMaxTransferBlockSize(16384);
        config.setPeerConnectionTimeout(Duration.ofSeconds(8));
        config.setPeerHandshakeTimeout(Duration.ofSeconds(8));
        config.setShutdownHookTimeout(Duration.ofSeconds(3));
        config.setMaxConcurrentlyActivePeerConnectionsPerTorrent(8);
        runtime = BtRuntime.builder(config).disableStandardExtensions().disableAutomaticShutdown()
                .module(new ClosedSwarmModule(id -> {
                    Run run=stoppingEvents.remove(id);if(run!=null)run.finished();
                })).build();
        runtime.startup();
    }
    public int port() { return port; }
    public void tunnelPeer(String id, int localPort) throws Exception {
        Torrent t=torrents.get(id);
        if(t!=null)runtime.service(IPeerRegistry.class).addPeer(t.getTorrentId(),InetPeer.builder(InetAddress.getByName("127.0.0.1"),localPort).build());
    }
    public static byte[] make(Path encrypted) {
        return new TorrentBuilder().addFile(encrypted).rootPath(encrypted.getParent())
                .pieceSize(FileCrypto.PIECE).numHashingThreads(1).maxNumOpenFiles(8)
                .privateFlag(true).createdBy("Parlons private files").build();
    }
    static Torrent validate(ChatFile file) throws IOException {
        final Torrent t;
        try { byte[] metadata = Base64.getDecoder().decode(file.torrent);
            BoundedMetadata.check(metadata);
            t = new MetadataService().fromByteArray(metadata); }
        catch (Exception e) { throw new IOException("Invalid transfer metadata"); }
        if (t.getAnnounceKey().isPresent() || !t.isPrivate() || t.getFiles().size()!=1
                || !"payload.bin".equals(t.getName())
                || !Collections.singletonList("payload.bin").equals(t.getFiles().get(0).getPathElements())
                || t.getSize()!=FileCrypto.cipherSize(file.size) || t.getChunkSize()!=FileCrypto.PIECE)
            throw new IOException("Unsafe transfer metadata");
        int count=0;
        for(byte[] hash:t.getChunkHashes()) { if(hash.length!=20 || ++count>514)throw new IOException("Invalid pieces"); }
        if(count!=(t.getSize()+FileCrypto.PIECE-1)/FileCrypto.PIECE)throw new IOException("Invalid piece count");
        return t;
    }
    public void start(ChatFile file, Path directory, BiConsumer<Integer,Integer> progress,
                      Runnable complete, java.util.function.Consumer<String> failure) throws Exception {
        final Run run;
        final BtClient client;
        final java.util.concurrent.CompletableFuture<?> processing;
        synchronized(this) {
            if(clients.containsKey(file.id)) return;
            Run prior=runs.get(file.id);
            if(prior!=null && prior.stopped.getCount()!=0)throw new IOException("Transfer is still stopping; retry shortly");
            if(clients.size()>=4)throw new IOException("Pause another transfer first (4 active transfers maximum)");
            Torrent torrent=validate(file);
            run=new Run();runs.put(file.id,run);
            stoppingEvents.put(torrent.getTorrentId(),run);
            client=Bt.client(runtime).afterFilesChosen(run::enter).storage(new FileSystemStorage(directory,2)).torrent(()->torrent)
                    .afterDownloaded(t -> {if(run.active())complete.run();}).build();
            torrents.put(file.id,torrent); clients.put(file.id,client);
            processing=client.startAsync(state -> progress.accept(state.getPiecesComplete(),state.getPiecesTotal()),500);
        }
        // An already completed future invokes this inline, so attach outside our monitor too.
        processing.whenComplete((ignored,error)->processingEnded(file.id,client,run,failure));
    }
    private void processingEnded(String id, BtClient client, Run run, java.util.function.Consumer<String> failure) {
        // Bt's TerminateOnErrorProcessingStage logs I/O failures and completes normally.
        // A sharing client should run until explicitly paused, even after download completes.
        String message="Transfer interrupted; tap Resume";
        synchronized(this) {
            if(clients.get(id)!=client || !run.active())return;
            try { pause(id); }
            catch(RuntimeException e) { message="Transfer interrupted; cleanup is still pending. Retry Resume shortly"; }
        }
        // PrivateFiles takes its own monitor. Never call it while holding the engine monitor.
        failure.accept(message);
    }
    public void peer(String id, String host, int port) throws Exception {
        Torrent t=torrents.get(id);
        if(t==null || port<1024 || port>65535) return;
        InetAddress address=InetAddress.getByName(host);
        if(address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()
                || (!testLoopback && address.isLoopbackAddress())) throw new IOException("Invalid peer address");
        runtime.service(IPeerRegistry.class).addPeer(t.getTorrentId(),InetPeer.builder(address,port).build());
    }
    public synchronized void pause(String id) {
        BtClient c=clients.get(id); Torrent t=torrents.get(id);Run run=runs.get(id);
        if(c!=null){
            if(run!=null){
                run.cancel();
                // Bt.stop completes its processing future. If its task is still queued,
                // CompletableFuture skips the task entirely and no stopped event fires.
                // Let preparation enter (or fail) before stopping that future.
                try {
                    if(!run.entered.await(8,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Transfer is still starting; retry shortly");
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Transfer stop interrupted",e);}
            }
            clients.remove(id);torrents.remove(id);
            c.stop();
            if(t!=null)runtime.service(bt.net.IPeerConnectionPool.class).visitConnections(t.getTorrentId(),bt.net.PeerConnection::closeQuietly);
            if(run!=null)try{
                if(!run.stopped.await(8,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Transfer is still stopping; retry shortly");
                // The stopped event also closes the disk descriptor. Wait for that listener too.
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                while(t!=null && runtime.service(bt.torrent.TorrentRegistry.class).getDescriptor(t.getTorrentId()).isPresent()) {
                    if(System.nanoTime()>until)throw new IllegalStateException("Transfer is still stopping; retry shortly");
                    Thread.sleep(10);
                }
                runs.remove(id,run);
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
    }
    @Override public synchronized void close() {
        for(String id:new ArrayList<>(clients.keySet()))pause(id);
        runtime.shutdown();
    }
}
