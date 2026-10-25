package com.eurobuddha.maxima.app.files;

import android.content.Context;
import android.os.PowerManager;
import com.eurobuddha.maxima.app.call.GatheredCallSdp;
import com.eurobuddha.maxima.files.PeerDataTransport;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.webrtc.*;

/** Direct-only ICE/DTLS byte transport. No media tracks, TURN, public swarm or file RPC.
 * A 16 x 16KiB acknowledgement window bounds both native and Java buffering. */
public final class WebRtcFiles implements PeerDataTransport {
    private final Context context;
    private final List<String> stun;
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService io = Executors.newCachedThreadPool(); // <= 8 sessions, two pumps each
    private final Map<String,Session> sessions = new HashMap<>(); // control thread only
    private PeerConnectionFactory factory;
    private volatile boolean closed;
    private final AtomicInteger queued=new AtomicInteger();
    public WebRtcFiles(Context context) {
        this(context,Arrays.asList("stun:95.179.179.181:9501","stun:65.109.31.226:9501",
                "stun:45.77.246.226:9501","stun:78.141.237.9:9501"));
    }
    /** Also used by the isolated native test with no STUN servers. */
    public WebRtcFiles(Context context,List<String> stun) {
        this.context=context.getApplicationContext(); this.stun=new ArrayList<>(stun);
        for(String s:stun)if(!s.startsWith("stun:"))throw new IllegalArgumentException("STUN only");
    }
    private void dispatch(Runnable r) {
        if(queued.incrementAndGet()>256){queued.decrementAndGet();return;}
        try { control.execute(()->{try{r.run();}finally{queued.decrementAndGet();}}); }
        catch(RejectedExecutionException ignored){queued.decrementAndGet();}
    }
    @Override public void connect(String id,String peer,boolean offerer,int gateway,String localToken,
            String remoteToken,BiConsumer<String,String> signal,IntConsumer ready) {
        dispatch(()->{
            if(closed)return;
            String key=id+"/"+peer; Session old=sessions.get(key);
            if(old!=null && old.localToken.equals(localToken) && old.remoteToken.equals(remoteToken))return;
            if(old!=null)old.close();
            if(sessions.size()>=8)return;
            try {
                if(factory==null){
                    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
                    factory=PeerConnectionFactory.builder().createPeerConnectionFactory();
                }
                Session s=new Session(key,id,offerer,gateway,localToken,remoteToken,signal,ready);
                sessions.put(key,s); s.start();
            }catch(Exception e){Session s=sessions.get(key);if(s!=null)s.close();}
        });
    }
    @Override public void signal(String id,String peer,Map<String,String> signal) {
        String kind=signal.getOrDefault("kind",""),body=signal.getOrDefault("body","");
        if(body.length()>20000)return;
        dispatch(()->{Session s=sessions.get(id+"/"+peer);if(s!=null)s.signal(kind,body);});
    }
    @Override public void disconnect(String id,String peer) { dispatch(()->{
        Session s=sessions.get(id+"/"+peer);if(s!=null)s.close();
    }); }
    @Override public void stop(String id) { dispatch(()->{
        for(Session s:new ArrayList<>(sessions.values()))if(s.id.equals(id))s.close();
    }); }
    @Override public void close() {
        closed=true;
        try { control.execute(()->{
            for(Session s:new ArrayList<>(sessions.values()))s.close();
            if(factory!=null){factory.dispose();factory=null;}
            io.shutdownNow(); control.shutdown();
        }); }catch(RejectedExecutionException ignored){}

    }
    static boolean directCandidate(String candidate) {
        return !candidate.matches("(?s).*\\btyp\\s+relay\\b.*");
    }
    static boolean directSdp(String sdp) {
        if(sdp.length()>20000 || !sdp.contains("m=application ") || sdp.contains("m=audio ") || sdp.contains("m=video "))return false;
        for(String line:sdp.split("\\r?\\n"))if(line.startsWith("a=candidate:") && !directCandidate(line))return false;
        return true;
    }
    static boolean directPair(Map<String,RTCStats> stats) {
        for(RTCStats transport:stats.values())if("transport".equals(transport.getType())) {
            Object selected=transport.getMembers().get("selectedCandidatePairId");
            RTCStats pair=stats.get(String.valueOf(selected));if(pair==null)continue;
            RTCStats local=stats.get(String.valueOf(pair.getMembers().get("localCandidateId")));
            RTCStats remote=stats.get(String.valueOf(pair.getMembers().get("remoteCandidateId")));
            if(local!=null && remote!=null && directType(local) && directType(remote))return true;
        }
        return false;
    }
    private static boolean directType(RTCStats candidate) {
        Object type=candidate.getMembers().get("candidateType");
        return "host".equals(type) || "srflx".equals(type) || "prflx".equals(type);
    }
    private final class Session {
        final String key,id,localToken,remoteToken;
        final boolean offerer; final int gateway;
        final BiConsumer<String,String> send; final IntConsumer ready;
        final AtomicBoolean ended=new AtomicBoolean();
        final ArrayBlockingQueue<byte[]> incoming=new ArrayBlockingQueue<>(16);
        final Semaphore credits=new Semaphore(16);
        final AtomicInteger outstanding=new AtomicInteger();
        final Map<Runnable,ScheduledFuture<?>> timers=new HashMap<>();
        final List<IceCandidate> earlyIce=new ArrayList<>();
        final Map<String,List<IceCandidate>> waitingIce=new HashMap<>();
        int waitingCount;
        volatile Socket socket; volatile ServerSocket listener;
        volatile long lastProgress=System.nanoTime(), writeStarted;
        PeerConnection pc; DataChannel channel; GatheredCallSdp batch;
        boolean remoteSet,offered,opened,checkingPath;
        String connection;
        PowerManager.WakeLock wake;
        ScheduledFuture<?> watch;
        Session(String key,String id,boolean offerer,int gateway,String localToken,String remoteToken,
                BiConsumer<String,String> send,IntConsumer ready){
            this.key=key;this.id=id;this.offerer=offerer;this.gateway=gateway;
            this.localToken=localToken;this.remoteToken=remoteToken;this.send=send;this.ready=ready;
            this.connection=offerer?UUID.randomUUID().toString():"";
        }
        void start(){
            List<PeerConnection.IceServer> servers=new ArrayList<>();
            for(String url:stun)servers.add(PeerConnection.IceServer.builder(url).createIceServer());
            PeerConnection.RTCConfiguration cfg=new PeerConnection.RTCConfiguration(servers);
            cfg.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
            pc=factory.createPeerConnection(cfg,new Observer(){
                @Override public void onIceCandidate(IceCandidate c){dispatch(()->{if(!ended.get() && directCandidate(c.sdp))batch.candidate(c);});}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s){dispatch(()->{if(!ended.get())batch.gathering(s);});}
                @Override public void onDataChannel(DataChannel c){dispatch(()->{if(offerer || channel!=null || ended.get()){c.dispose();close();}else attach(c);});}
                @Override public void onConnectionChange(PeerConnection.PeerConnectionState s){
                    if(s==PeerConnection.PeerConnectionState.FAILED || s==PeerConnection.PeerConnectionState.CLOSED)dispatch(Session.this::close);
                }
            });
            if(pc==null)throw new IllegalStateException("WebRTC unavailable");
            batch=new GatheredCallSdp(pc,()->!ended.get(),WebRtcFiles.this::dispatch,
                    (r,ms)->timers.put(r,control.schedule(r,ms,TimeUnit.MILLISECONDS)),
                    r->{ScheduledFuture<?> f=timers.remove(r);if(f!=null)f.cancel(false);},
                    (kind,body)->{if(kind.equals("ice") || directSdp(body))send.accept(kind,connection+"\n"+body);else close();},error->close());
            PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
            if(pm!=null){wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Parlons:private-file");wake.acquire(20*60*1000L);}
            long born=System.nanoTime();
            watch=control.scheduleAtFixedRate(()->{
                long now=System.nanoTime();
                if(now-lastProgress>TimeUnit.SECONDS.toNanos(opened?120:45)
                        || now-born>TimeUnit.MINUTES.toNanos(19)
                        || (writeStarted!=0 && now-writeStarted>TimeUnit.SECONDS.toNanos(30)))close();
            },5,5,TimeUnit.SECONDS);
            if(offerer){attach(pc.createDataChannel("parlons-file-v1",new DataChannel.Init()));pc.createOffer(new Sdp(){
                @Override public void onCreateSuccess(SessionDescription s){dispatch(()->{if(!ended.get())batch.start(s);});}
            },new MediaConstraints());}
        }
        void signal(String kind,String body){
            if(ended.get())return;
            try {
                int split=body.indexOf('\n');if(split<0)return;
                String session=body.substring(0,split);body=body.substring(split+1);
                if(!session.matches("[a-f0-9-]{36}"))return;
                if(!offerer && kind.equals("offer") && !offered){
                    connection=session;
                    earlyIce.addAll(waitingIce.getOrDefault(session,Collections.emptyList()));
                    waitingIce.clear();waitingCount=0;
                }
                if(!connection.equals(session) && !(connection.isEmpty() && kind.equals("ice")))return;
                if(kind.equals("ice")){
                    String[] p=body.split("\n",3);
                    if(p.length!=3 || !directCandidate(p[2])){close();return;}
                    IceCandidate c=new IceCandidate(p[0],Integer.parseInt(p[1]),p[2]);
                    if(connection.isEmpty()) {
                        if(++waitingCount>64){close();return;}
                        waitingIce.computeIfAbsent(session,k->new ArrayList<>()).add(c);
                    }else if(remoteSet)pc.addIceCandidate(c);else if(earlyIce.size()<64)earlyIce.add(c);else close();
                    return;
                }
                if(!(kind.equals("offer") || kind.equals("answer")) || !directSdp(body)) {close();return;}
                if(offerer!=kind.equals("answer") || offered)return;
                offered=true;
                pc.setRemoteDescription(new Sdp(){@Override public void onSetSuccess(){dispatch(()->{
                    if(ended.get())return; remoteSet=true;
                    for(IceCandidate c:earlyIce)pc.addIceCandidate(c);earlyIce.clear();
                    if(!offerer)pc.createAnswer(new Sdp(){@Override public void onCreateSuccess(SessionDescription s){
                        dispatch(()->{if(!ended.get())batch.start(s);});
                    }},new MediaConstraints());
                });}},new SessionDescription(kind.equals("offer")?SessionDescription.Type.OFFER:SessionDescription.Type.ANSWER,body));
            }catch(Exception e){close();}
        }
        void attach(DataChannel c){
            if(c==null || !"parlons-file-v1".equals(c.label())){if(c!=null)c.dispose();close();return;}
            channel=c;
            c.registerObserver(new DataChannel.Observer(){
                @Override public void onBufferedAmountChange(long previous){}
                @Override public void onStateChange(){dispatch(()->{
                    if(ended.get())return;
                    if(c.state()==DataChannel.State.OPEN)open();
                    else if(c.state()==DataChannel.State.CLOSED)close();
                });}
                @Override public void onMessage(DataChannel.Buffer buffer){
                    if(ended.get())return;
                    ByteBuffer b=buffer.data; int n=b.remaining();
                    if(!buffer.binary || n<1 || n>16385){dispatch(Session.this::close);return;}
                    byte type=b.get();
                    if(type==1 && n==1){
                        if(outstanding.decrementAndGet()<0){dispatch(Session.this::close);return;}
                        credits.release(); lastProgress=System.nanoTime();
                    }else if(type==0 && n>1){
                        byte[] data=new byte[n-1];b.get(data); // callback memory expires on return
                        if(!incoming.offer(data))dispatch(Session.this::close);
                    }else dispatch(Session.this::close);
                }
            });
            if(c.state()==DataChannel.State.OPEN)open();
        }
        void open(){
            if(opened || checkingPath || ended.get())return;
            checkingPath=true;
            pc.getStats(report->dispatch(()->{
                checkingPath=false;
                if(ended.get())return;
                if(!directPair(report.getStatsMap())){close();return;}
                openVerified();
            }));
        }
        void openVerified(){
            if(opened || ended.get())return;opened=true;lastProgress=System.nanoTime();
            io.execute(()->{
                try {
                    if(ended.get())return;
                    if(offerer){
                        listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));listener.setSoTimeout(15000);
                        ready.accept(listener.getLocalPort()); socket=listener.accept();listener.close();
                    }else{
                        Socket s=new Socket();socket=s;s.connect(new InetSocketAddress("127.0.0.1",gateway),3000);s.setSoTimeout(15000);
                        s.getOutputStream().write(localToken.getBytes(StandardCharsets.US_ASCII));s.getOutputStream().flush();
                        if(s.getInputStream().read()!=1)throw new IOException("Transfer unavailable");
                    }
                    if(ended.get())throw new IOException("Transfer stopped");
                    socket.setSoTimeout(120000);socket.setTcpNoDelay(true);
                    io.execute(this::receive);
                    byte[] block=new byte[16385];block[0]=0;
                    InputStream in=socket.getInputStream();int n;
                    while(!ended.get() && (n=in.read(block,1,16384))!=-1){
                        if(!credits.tryAcquire(30,TimeUnit.SECONDS))throw new IOException("Peer stopped reading");
                        outstanding.incrementAndGet();
                        transmit(Arrays.copyOf(block,n+1));
                    }
                }catch(Exception ignored){}finally{
                    try{if(socket!=null)socket.close();}catch(IOException ignored){}
                    try{if(listener!=null)listener.close();}catch(IOException ignored){}
                    dispatch(this::close);
                }
            });
        }
        void receive(){
            try {
                OutputStream out=socket.getOutputStream();
                while(!ended.get()){
                    byte[] data=incoming.poll(120,TimeUnit.SECONDS);if(data==null || ended.get())break;
                    writeStarted=System.nanoTime();out.write(data);out.flush();writeStarted=0;
                    lastProgress=System.nanoTime();transmit(new byte[]{1});
                }
            }catch(Exception ignored){}finally{dispatch(this::close);}
        }
        synchronized void transmit(byte[] data)throws IOException {
            if(ended.get() || channel.bufferedAmount()>512*1024 || !channel.send(new DataChannel.Buffer(ByteBuffer.wrap(data),true)))
                throw new IOException("Direct connection interrupted");
        }
        void close(){
            if(!ended.compareAndSet(false,true))return;
            credits.release(16);incoming.offer(new byte[0]);
            try{if(socket!=null)socket.close();}catch(IOException ignored){}
            try{if(listener!=null)listener.close();}catch(IOException ignored){}
            if(batch!=null)batch.close();if(watch!=null)watch.cancel(false);
            for(ScheduledFuture<?> f:timers.values())f.cancel(false);timers.clear();
            synchronized(this){if(channel!=null){channel.unregisterObserver();channel.close();channel.dispose();}}
            if(pc!=null){pc.close();pc.dispose();}
            if(wake!=null && wake.isHeld())wake.release();
            // A short tombstone stops discovery/signalling from creating a retry storm.
            if(closed)sessions.remove(key,this);
            else control.schedule(()->sessions.remove(key,this),15,TimeUnit.SECONDS);
        }
        class Sdp implements SdpObserver {
            public void onCreateSuccess(SessionDescription s){} public void onSetSuccess(){}
            public void onCreateFailure(String e){dispatch(Session.this::close);} public void onSetFailure(String e){dispatch(Session.this::close);}
        }
    }
    private static class Observer implements PeerConnection.Observer {
        public void onSignalingChange(PeerConnection.SignalingState s){} public void onIceConnectionChange(PeerConnection.IceConnectionState s){}
        public void onIceConnectionReceivingChange(boolean b){} public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
        public void onIceCandidate(IceCandidate c){} public void onIceCandidatesRemoved(IceCandidate[] c){}
        public void onAddStream(MediaStream s){} public void onRemoveStream(MediaStream s){} public void onDataChannel(DataChannel c){}
        public void onRenegotiationNeeded(){} public void onAddTrack(RtpReceiver r,MediaStream[] s){}
    }
}
