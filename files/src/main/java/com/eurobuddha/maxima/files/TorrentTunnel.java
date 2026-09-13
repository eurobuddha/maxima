package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.net.Frame;
import com.eurobuddha.maxima.core.net.PrivateStreams;
import com.eurobuddha.maxima.core.msg.Greeting;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Bridges the proven framed listener to Bt's loopback-only TCP engine. Fixed destinations,
 * capability AND infohash checks; never a general proxy. Bounded sockets, idle/write watchdog,
 * and one upload budget per transfer. */
final class TorrentTunnel implements AutoCloseable {
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final Set<ServerSocket> listeners=ConcurrentHashMap.newKeySet();
    private final Set<String> tokens=ConcurrentHashMap.newKeySet();
    private final Semaphore slots=new Semaphore(16);
    private final ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"private-file-stream");t.setDaemon(true);return t;});
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"private-file-watch");t.setDaemon(true);return t;});
    private volatile boolean closed;
    private final boolean testLoopback;
    TorrentTunnel() { this(false); }
    TorrentTunnel(boolean testLoopback) { this.testLoopback = testLoopback; }
    private volatile long bytesPerSecond=1024*1024;
    private long nextWrite;
    void uploadLimit(long rate) { if(rate<64*1024 || rate>16*1024*1024)throw new IllegalArgumentException("Invalid upload limit"); bytesPerSecond=rate; }
    private void pace(int bytes)throws IOException {
        long wait;
        synchronized(this){long now=System.nanoTime();nextWrite=Math.max(now,nextWrite)+bytes*1_000_000_000L/bytesPerSecond;wait=nextWrite-now;}
        try{TimeUnit.NANOSECONDS.sleep(wait);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
    }
    synchronized void expose(String token, byte[] hash, int enginePort) {
        if(closed)throw new IllegalStateException("File sharing stopped");
        tokens.add(token);
        PrivateStreams.register(token,(remote,in,out)->{
            if(closed || !slots.tryAcquire())return;
            sockets.add(remote);
            try {
                remote.setSoTimeout(15000);
                out.write(1);out.flush();
                byte[] hello=new byte[68];in.readFully(hello);
                if(hello[0]!=19 || !"BitTorrent protocol".equals(new String(hello,1,19,StandardCharsets.US_ASCII))
                        || !java.security.MessageDigest.isEqual(hash,Arrays.copyOfRange(hello,28,48)))return;
                // No extension negotiation, including extension-handshake port replacement.
                // Peers use the authenticated gateway port, never the engine's private port.
                Arrays.fill(hello,20,28,(byte)0);
                try(Socket engine=new Socket()){
                    engine.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),enginePort),3000);
                    engine.setSoTimeout(15000);
                    engine.getOutputStream().write(hello);engine.getOutputStream().flush();
                    byte[] reply=new byte[68];new DataInputStream(engine.getInputStream()).readFully(reply);
                    Arrays.fill(reply,20,28,(byte)0);out.write(reply);out.flush();
                    bridge(engine,engine.getInputStream(),engine.getOutputStream(),remote,in,out);
                }
            } finally {sockets.remove(remote);slots.release();}
        });
    }
    void withdraw(String token) { tokens.remove(token);PrivateStreams.remove(token); }
    synchronized int connect(String host,int port,String token)throws Exception {
        if(closed || listeners.size()>=24)throw new IOException("Too many transfer peers");
        if(port<1024 || port>65535 || !token.matches("[a-f0-9]{64}"))throw new IOException("Invalid transfer peer");
        InetAddress address=InetAddress.getByName(host);
        if(address.isAnyLocalAddress() || (!testLoopback && address.isLoopbackAddress()) || address.isLinkLocalAddress() || address.isMulticastAddress())
            throw new IOException("Invalid transfer peer address");
        ServerSocket listen=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));listeners.add(listen);
        workers.execute(()->{
            while(!closed && !listen.isClosed()){
                try {
                    Socket local=listen.accept();
                    if(!slots.tryAcquire()){local.close();continue;}
                    sockets.add(local);
                    try { workers.execute(()->{
                        try(Socket l=local;Socket remote=new Socket()){
                            sockets.add(remote);
                            remote.connect(new InetSocketAddress(address,port),8000);remote.setSoTimeout(15000);
                            DataOutputStream out=new DataOutputStream(remote.getOutputStream());
                            DataInputStream in=new DataInputStream(remote.getInputStream());
                            Frame.write(out,Frame.body(Frame.MSG_GREETING,Greeting.commsOnly("1.0.48","",0)));out.flush();
                            byte[] greeting=Frame.readOrSkip(in,65536);
                            if(greeting==null || Frame.typeOf(greeting)!=Frame.MSG_GREETING)throw new IOException("Peer has no file listener");
                            byte[] request=new byte[65];request[0]=(byte)PrivateStreams.TYPE;
                            System.arraycopy(token.getBytes(StandardCharsets.US_ASCII),0,request,1,64);
                            Frame.write(out,request);out.flush();
                            if(in.read()!=1)throw new IOException("Private transfer unavailable");
                            byte[] hello=new byte[68];new DataInputStream(l.getInputStream()).readFully(hello);
                            Arrays.fill(hello,20,28,(byte)0);out.write(hello);out.flush();
                            byte[] reply=new byte[68];in.readFully(reply);
                            if(reply[0]!=19 || !java.security.MessageDigest.isEqual(Arrays.copyOfRange(hello,28,48),Arrays.copyOfRange(reply,28,48)))throw new IOException("Wrong transfer swarm");
                            Arrays.fill(reply,20,28,(byte)0);l.getOutputStream().write(reply);l.getOutputStream().flush();
                            bridge(l,l.getInputStream(),l.getOutputStream(),remote,in,out);
                        }catch(Exception ignored){}finally{sockets.remove(local);sockets.removeIf(Socket::isClosed);slots.release();}
                    }); } catch (RejectedExecutionException e) { sockets.remove(local);local.close();slots.release();break; }
                }catch(Exception e){break;}
            }
        });
        return listen.getLocalPort();
    }
    synchronized void disconnect(int port) {
        for (ServerSocket listener : new ArrayList<>(listeners)) if (listener.getLocalPort() == port) {
            listeners.remove(listener); try { listener.close(); } catch (IOException ignored) { }
        }
    }
    private void bridge(Socket local,InputStream localIn,OutputStream localOut,
                        Socket remote,InputStream remoteIn,OutputStream remoteOut)throws Exception {
        sockets.add(local);sockets.add(remote);
        local.setSoTimeout(120000);remote.setSoTimeout(120000);
        long[] writes={0,0};
        ScheduledFuture<?> timer=watchdog.scheduleAtFixedRate(()->{
            long now=System.nanoTime();
            synchronized(writes){for(long start:writes)if(start!=0 && now-start>TimeUnit.SECONDS.toNanos(30)){close(local);close(remote);}}
        },5,5,TimeUnit.SECONDS);
        Future<?> outbound=workers.submit(()->{try{copy(localIn,remoteOut,true,writes,0);}catch(Exception ignored){}finally{close(local);close(remote);}});
        try{copy(remoteIn,localOut,false,writes,1);}
        finally{close(local);close(remote);outbound.cancel(true);timer.cancel(false);sockets.remove(local);sockets.remove(remote);}
    }
    private void copy(InputStream in,OutputStream out,boolean upload,long[] writes,int index)throws IOException {
        DataInputStream input=new DataInputStream(in);DataOutputStream output=new DataOutputStream(out);
        byte[] packet=new byte[16384+9];
        while(!closed){
            int length=input.readInt();
            if(length<0 || length>packet.length)throw new IOException("Invalid transfer packet");
            input.readFully(packet,0,length);
            validatePacket(packet,length);
            if(upload)pace(length+4);
            synchronized(writes){writes[index]=System.nanoTime();}
            try{output.writeInt(length);output.write(packet,0,length);output.flush();}
            finally{synchronized(writes){writes[index]=0;}}
        }
    }
    /** Admit only bounded BEP-3 messages. Extension/magnet metadata never reaches Bt's decoder. */
    static void validatePacket(byte[] packet,int length)throws IOException {
        if(length==0)return;
        int type=packet[0]&255;
        boolean valid=type<=3 ? length==1 : type==4 ? length==5 : type==5 ? length<=66
                : type==6 || type==8 ? length==13 : type==7 && length>=9 && length<=16393;
        if(!valid)throw new IOException("Unsupported transfer packet type="+type+" length="+length);
        if(type==6 || type==8){int count=java.nio.ByteBuffer.wrap(packet,9,4).getInt();
            if(count<1||count>16384)throw new IOException("Invalid transfer block");}
    }
    private static void close(Socket s){try{s.close();}catch(Exception ignored){}}
    @Override public synchronized void close(){closed=true;tokens.forEach(PrivateStreams::remove);tokens.clear();sockets.forEach(TorrentTunnel::close);
        listeners.forEach(s->{try{s.close();}catch(Exception ignored){}});listeners.clear();workers.shutdownNow();watchdog.shutdownNow();}
}
