package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.*;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.rpc.RpcPeer;
import com.eurobuddha.maxima.core.util.Json;
import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Account-owned private file transfers. Bytes stay on participants, never relay blob shelves.
 * Offers reuse ChatMedia and ChatEngine's sealed send path. Only small, authenticated peer
 * introductions use Maxima RPC; bulk ciphertext uses the already forwarded endpoint directly. */
public final class PrivateFiles implements AutoCloseable {
    public static final String PEERS="parlons.files.peers";
    public static final int BLOCK=48*1024;
    public static final long STORAGE_LIMIT=2L*1024*1024*1024;
    private final MaximaNode node;
    private final ChatEngine chat;
    private final Path root;
    private final Map<String,Transfer> transfers=new ConcurrentHashMap<>();
    private final ExecutorService jobs=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),
            r->{Thread t=new Thread(r,"private-file-job");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService discovery=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"private-file-peers");t.setDaemon(true);return t;});
    private final ExecutorService peerJobs = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(24), r -> { Thread t = new Thread(r, "private-file-introduction"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private final Set<String> pendingPeers = ConcurrentHashMap.newKeySet();
    private volatile PrivateTorrent engine;
    private volatile boolean closed;
    private volatile Supplier<List<String>> extraSources = Collections::emptyList;
    public void setExtraSources(Supplier<List<String>> sources) { extraSources = sources; }
    private List<String> sources() {
        List<String> out = new ArrayList<>(node.directAddresses());
        for (String s : extraSources.get()) if (!out.contains(s) && out.size() < 3) out.add(s);
        return out;
    }

    private static final class Transfer {
        final String id,peer; final boolean group,mine; final Path dir;
        final Set<String> participants=ConcurrentHashMap.newKeySet();
        final Map<String,Integer> tunnels=new ConcurrentHashMap<>();
        volatile ChatFile file;
        volatile String status="Preparing",error="",token="";
        final Object disk = new Object();
        volatile long reservedSize;
        volatile long done; volatile boolean paused,ready,restorePending; volatile int generation;
        volatile TorrentTunnel tunnel;
        Transfer(String id,String peer,boolean group,boolean mine,Path dir){this.id=id;this.peer=peer;this.group=group;this.mine=mine;this.dir=dir;}
    }
    public PrivateFiles(MaximaNode node,ChatEngine chat,Path directory)throws IOException {
        this.node=node;this.chat=chat;this.root=directory;
        Files.createDirectories(root); privatePath(root,true);
        load();
        node.services().register(PEERS,req->{
            String id=Json.parse(req.payloadAsString()).getOrDefault("id","");
            Transfer t=transfers.get(id);
            String from=new MiniData(req.fromPublicKey).to0xString().toLowerCase(Locale.ROOT);
            if(t==null || t.file==null || t.paused || t.tunnel==null || !authorised(t,from))throw new SecurityException("Transfer unavailable");
            // Also connect back, so an unreachable sender can upload to a reachable recipient.
            Map<String,String> request=Json.parse(req.payloadAsString());
            introduce(t,request.getOrDefault("sources",""),request.getOrDefault("token",""));
            return bytes(new Json.Writer().put("sources",String.join(",",sources())).put("token",t.token).done());
        });
        discovery.scheduleWithFixedDelay(this::discover,2,12,TimeUnit.SECONDS);
    }
    private static void privatePath(Path p,boolean dir)throws IOException {
        try{Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString(dir?"rwx------":"rw-------"));}
        catch(UnsupportedOperationException ignored){}
    }
    private boolean authorised(Transfer t,String key){
        if(node.contact(key)==null)return false;
        if(!t.participants.contains(key.toLowerCase(Locale.ROOT)))return false;
        Group g=t.group?chat.group(t.peer):null;
        return !t.group || (g!=null && g.members().stream().anyMatch(k->k.equalsIgnoreCase(key)));
    }
    private synchronized PrivateTorrent engine()throws Exception {
        if(closed)throw new IOException("File sharing stopped");
        if(engine==null){int port;try(ServerSocket s=new ServerSocket(0)){port=s.getLocalPort();}engine=new PrivateTorrent(port);}
        return engine;
    }
    private synchronized void reserve(long bytes)throws IOException {
        long used=0;
        for(Transfer t:transfers.values())used+=2*FileCrypto.cipherSize(t.reservedSize);
        if(used+2*FileCrypto.cipherSize(bytes)>STORAGE_LIMIT || root.toFile().getUsableSpace()<2*FileCrypto.cipherSize(bytes)+32*1024*1024)
            throw new IOException("Not enough file-sharing storage. Remove a finished transfer first.");
        if(transfers.size()>=32)throw new IOException("Remove a finished transfer first (32 stored transfers maximum)");
    }
    private synchronized Transfer allocate(String id, long size, String peer, boolean group, boolean mine) throws IOException {
        if (closed) throw new IOException("File sharing stopped");
        reserve(size);
        Transfer t = new Transfer(id, peer, group, mine, root.resolve(id));
        t.reservedSize = size;
        members(t);
        Files.createDirectory(t.dir);
        privatePath(t.dir, true);
        transfers.put(id, t);
        return t;
    }
    public String send(InputStream input,long size,String name,String mime,String peer,boolean group)throws Exception {
        try (InputStream source = input) {
            if(size<0 || size>ChatFile.MAX_BYTES)throw new IOException("Files may be up to 512 MB");
            String id=FileCrypto.randomHex(16), key=FileCrypto.randomHex(32), nonce=FileCrypto.randomHex(8);
            new ChatFile(id,name,mime,size,key,nonce,"0000000000000000000000000000000000000000000000000000000000000000","");
            Transfer t = allocate(id, size, peer, group, true);
            try {
                synchronized(t.disk) {
                    String digest=FileCrypto.encrypt(source,t.dir.resolve("payload.bin"),id,size,key,nonce,()->closed||t.paused,n->t.done=n);
                    t.file=new ChatFile(id,name,mime,size,key,nonce,digest,Base64.getEncoder().encodeToString(PrivateTorrent.make(t.dir.resolve("payload.bin"))));
                    save(t);
                }
                start(t);
                if(group)chat.sendGroup(peer,t.file.body());else chat.send(node.contact(peer),t.file.body());
                return id;
            } catch(Exception e) { remove(id); throw e; }
        }
    }
    private void members(Transfer t)throws IOException {
        if(t.group){Group g=chat.group(t.peer);if(g==null)throw new IOException("Group no longer exists");
            for(String key:g.members())if(!key.equalsIgnoreCase(node.identity().publicKeyHex()))t.participants.add(key.toLowerCase(Locale.ROOT));
        }else{Contact c=node.contact(t.peer);if(c==null)throw new IOException("Contact no longer exists");t.participants.add(c.publicKey.toLowerCase(Locale.ROOT));}
    }
    /** Download is explicit: merely receiving or displaying an offer starts no sockets or disk allocation. */
    public synchronized String receive(String ref,String conversation,boolean group)throws Exception {
        ChatFile file=ChatFile.parse(ref);
        Transfer old=transfers.get(file.id);
        if(old!=null){if(old.file==null || !old.file.ref().equals(ref) || !old.peer.equals(conversation) || old.group!=group)throw new SecurityException("Conflicting transfer");resume(file.id);return file.id;}
        // The caller cannot smuggle an arbitrary manifest into a different conversation.
        boolean offered=chat.conversation(conversation).stream().anyMatch(e->ChatMedia.ref(e.body).equals(ref));
        if(!offered)throw new SecurityException("File offer is not in this conversation");
        PrivateTorrent.validate(file);
        Transfer t=allocate(file.id,file.size,conversation,group,false);t.file=file;
        try { save(t);start(t);return file.id; }
        catch(Exception e) { remove(file.id);throw e; }
    }
    private synchronized void start(Transfer t)throws Exception {
        if(closed)throw new IOException("File sharing stopped");
        if(t.tunnel!=null)return;
        t.restorePending=false;t.paused=false;t.error="";t.status=t.ready?"Ready · sharing":t.mine?"Sharing":"Connecting";int generation=++t.generation;
        try {
        PrivateTorrent bt=engine();
        TorrentTunnel tunnel=new TorrentTunnel();t.tunnel=tunnel;t.token=FileCrypto.randomHex(32);
        tunnel.expose(t.token,PrivateTorrent.validate(t.file).getTorrentId().getBytes(),bt.port());
        bt.start(t.file,t.dir,(complete,total)->{
            if(t.generation!=generation)return;
            t.done=Math.min(t.file.size,(long)complete*FileCrypto.PLAIN_PIECE);
            if(!t.ready && !t.mine && complete<total)t.status=complete>0?"Downloading":"Connecting";
        },()->{
            if(t.generation!=generation || t.ready)return;
            t.status="Verifying";
            try{jobs.execute(()->{
                try {
                    synchronized(t.disk) {
                        if(t.generation!=generation)return;
                        FileCrypto.decrypt(t.dir.resolve("payload.bin"),t.dir.resolve("verified.bin"),t.file,()->closed||t.paused||t.generation!=generation);
                    }
                    synchronized(PrivateFiles.this) {
                        if(t.generation==generation){t.ready=true;t.done=t.file.size;t.status="Ready · sharing";}
                    }
                }catch(Exception e){failed(t,generation,message(e));}
            });}catch(RejectedExecutionException e){failed(t,generation,"Busy; tap Resume");}
        },error->failed(t,generation,error));
        save(t);
        }catch(Exception e){failed(t,generation,message(e));throw e;}
    }
    private synchronized void failed(Transfer t,int generation,String error) {
        if(t.generation!=generation)return;
        t.restorePending=false;t.paused=true;pauseEngine(t);t.status="Failed";t.error=error;
        try{save(t);}catch(IOException e){t.error+="; could not save paused state";}
    }
    public synchronized void pause(String id)throws IOException {Transfer t=get(id);t.restorePending=false;t.paused=true;t.status=t.ready?"Ready · paused":"Paused";
        try{pauseEngine(t);}finally{if(t.file!=null)save(t);}}
    private void pauseEngine(Transfer t){t.generation++;TorrentTunnel tunnel=t.tunnel;t.tunnel=null;t.token="";t.tunnels.clear();if(tunnel!=null)tunnel.close();if(engine!=null)engine.pause(t.id);}
    public synchronized void resume(String id)throws Exception {Transfer t=get(id);if(t.file==null)throw new IOException("Choose the file again");start(t);}
    public synchronized void remove(String id)throws Exception {Transfer t=get(id);pause(id);transfers.remove(id);
        synchronized(t.disk){try(java.util.stream.Stream<Path> paths=Files.walk(t.dir)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}}
    public Map<String,String> status(String id)throws IOException {
        Transfer t=get(id);Map<String,String> m=new LinkedHashMap<>();m.put("id",id);m.put("status",t.status);m.put("error",t.error);
        m.put("done",Long.toString(t.done));m.put("size",Long.toString(t.reservedSize));m.put("ready",Boolean.toString(t.ready));
        m.put("name",t.file==null?"Interrupted preparation":t.file.name);m.put("paused",Boolean.toString(t.paused));return m;
    }
    public List<Map<String,String>> list(){List<Map<String,String>> out=new ArrayList<>();for(String id:transfers.keySet())try{out.add(status(id));}catch(Exception ignored){}return out;}
    public InputStream open(String id)throws IOException {Transfer t=get(id);if(!t.ready)throw new IOException("File is not verified yet");return Files.newInputStream(t.dir.resolve("verified.bin"));}
    public String name(String id)throws IOException{return get(id).file.name;}
    public String ref(String id)throws IOException{return get(id).file.ref();}
    private Transfer get(String id)throws IOException{Transfer t=transfers.get(id);if(t==null)throw new IOException("Transfer not found");return t;}
    private void discover(){
        if(closed)return;
        resumeRestored();
        for(Transfer t:transfers.values())if(t.file!=null && t.tunnel!=null && !t.paused){
            for(String key:t.participants){
                if(!authorised(t,key))continue;
                Contact c=node.contact(key);String addr=c==null?null:c.primaryAddress();if(addr==null)continue;
                String pending=t.id+"/"+key;
                if(!pendingPeers.add(pending))continue;
                try { peerJobs.execute(() -> {
                    try {
                        if(closed || t.paused || t.tunnel==null){pendingPeers.remove(pending);return;}
                        node.rpc().call(addr,PEERS,bytes(new Json.Writer().put("id",t.id).put("token",t.token)
                                .put("sources",String.join(",",sources())).done()),new RpcPeer.ResponseHandler(){
                            public void onResponse(byte[] payload){pendingPeers.remove(pending);try{Map<String,String> m=Json.parse(new String(payload,StandardCharsets.UTF_8));introduce(t,m.get("sources"),m.get("token"));}catch(Exception ignored){}}
                            public void onError(String error){pendingPeers.remove(pending);}
                        },10000,2000,3000);
                    } catch(Exception e) { pendingPeers.remove(pending); }
                }); } catch(RejectedExecutionException e) { pendingPeers.remove(pending); }
            }
        }
    }
    /** Wait for asynchronous Android chat loading; explicit Pause cancels pending restoration. */
    private synchronized void resumeRestored(){
        if(closed || !chat.isLoaded())return;
        for(Transfer t:transfers.values())if(t.restorePending){
            try{start(t);}catch(Exception ignored){/* start records a visible, persisted failure. */}
        }
    }
    private void introduce(Transfer t,String sources,String token){
        if(closed||t.paused||t.tunnel==null||sources==null||sources.length()>4096||token==null||!token.matches("[a-f0-9]{64}"))return;
        // DNS/dial work is off the inbound RPC pump, bounded by the current active transfers.
        try{peerJobs.execute(()->{
            int count=0;
            for(String source:sources.split(",")){
                if(++count>3||t.paused||t.tunnel==null)break;
                try{
                    String address=source.substring(source.lastIndexOf('@')+1);int split=address.lastIndexOf(':');
                    String host=address.substring(0,split);int port=Integer.parseInt(address.substring(split+1));
                    synchronized(t.tunnels) {
                        TorrentTunnel current=t.tunnel;if(current==null)break;
                        String key=address+"/"+token;Integer local=t.tunnels.get(key);
                        if(local==null){
                            for(String prior:new ArrayList<>(t.tunnels.keySet()))if(prior.startsWith(address+"/")){
                                Integer old=t.tunnels.remove(prior);if(old!=null)current.disconnect(old);
                            }
                            local=current.connect(host,port,token);t.tunnels.put(key,local);
                        }
                        PrivateTorrent bt=engine;if(bt!=null && t.tunnel==current)bt.tunnelPeer(t.id,local);
                    }
                }catch(Exception ignored){}
            }
        });}catch(RejectedExecutionException ignored){}
    }
    private void save(Transfer t)throws IOException {
        synchronized(t.disk){
        String json=new Json.Writer().put("ref",t.file.ref()).put("peer",t.peer).put("group",Boolean.toString(t.group))
                .put("mine",Boolean.toString(t.mine)).put("members",String.join(",",t.participants)).put("paused",Boolean.toString(t.paused)).done();
        Path tmp=t.dir.resolve("offer.tmp");Files.write(tmp,bytes(json));privatePath(tmp,false);Files.move(tmp,t.dir.resolve("offer.json"),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private void load()throws IOException {
        try(java.util.stream.Stream<Path> dirs=Files.list(root)){
            for(Path dir:(Iterable<Path>)dirs::iterator){if(transfers.size()>=32)break;
                Path state=dir.resolve("offer.json");
                if(!dir.getFileName().toString().matches("[a-f0-9]{32}") || !Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))continue;
                if(!Files.isRegularFile(state,LinkOption.NOFOLLOW_LINKS) || Files.size(state)>100000){orphan(dir);continue;}
                try{Map<String,String> m=Json.parse(new String(Files.readAllBytes(state),StandardCharsets.UTF_8));ChatFile f=ChatFile.parse(m.get("ref"));if(!dir.getFileName().toString().equals(f.id))continue;
                    Transfer t=new Transfer(f.id,m.get("peer"),Boolean.parseBoolean(m.get("group")),Boolean.parseBoolean(m.get("mine")),dir);
                    t.file=f;t.reservedSize=f.size;t.paused=true;
                    // Old builds did not record pause intent. Leave those files paused until
                    // the owner resumes once, rather than undoing an unknown deliberate pause.
                    t.restorePending="false".equals(m.get("paused"));
                    // This cache is only published by authenticated decryption, in an owner-only directory.
                    t.ready=Files.isRegularFile(dir.resolve("verified.bin"),LinkOption.NOFOLLOW_LINKS) && Files.size(dir.resolve("verified.bin"))==f.size;
                    t.done=t.ready?f.size:0;t.status=t.restorePending?"Resuming":t.ready?"Ready · paused":"Paused";t.participants.addAll(Arrays.asList(m.get("members").split(",")));transfers.put(f.id,t);
                }catch(Exception ignored){orphan(dir);}
            }
        }
    }
    private void orphan(Path dir)throws IOException {
        Transfer t=new Transfer(dir.getFileName().toString(),"",false,true,dir);
        Path payload=dir.resolve("payload.bin");
        t.reservedSize=Files.isRegularFile(payload,LinkOption.NOFOLLOW_LINKS)?Files.size(payload):0;
        t.paused=true;t.status="Failed";t.error="Preparation was interrupted. Remove this transfer and choose the file again.";transfers.put(t.id,t);
    }
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static String message(Exception e){return e.getMessage()==null?"File transfer failed":e.getMessage();}
    @Override public synchronized void close(){if(closed)return;closed=true;node.services().unregister(PEERS);discovery.shutdownNow();peerJobs.shutdownNow();jobs.shutdownNow();
        for(Transfer t:transfers.values()){t.paused=true;pauseEngine(t);}if(engine!=null){engine.close();engine=null;}}
}
