package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.Frame;
import com.eurobuddha.maxima.core.net.PrivateStreams;
import com.eurobuddha.maxima.core.util.Json;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Owner-device file commands on one direct socket, including replies.
 * Tiny sealed owner RPC grants a one-use 60s ticket and independent directional keys.
 * AES-256-GCM records authenticate sequence and direction; no key/nonce is reused on retry.
 * No file content is ever put into Maxima RPC by this channel. */
public final class DirectFileChannel implements AutoCloseable {
    public static final String METHOD="parlons.files.direct";
    static final int MAX_RECORD=128*1024;
    private final Map<String,Ticket> tickets=new ConcurrentHashMap<>();
    private final ScheduledExecutorService watch=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"file-direct-watch");t.setDaemon(true);return t;});
    private volatile boolean closed;
    private final class Ticket {
        final String token=FileCrypto.randomHex(32),owner;
        final byte[] up=MaximaCrypto.randomBytes(32),down=MaximaCrypto.randomBytes(32);
        final long created=System.nanoTime(); final BooleanSupplier authorised;
        volatile Socket socket; volatile long writing; volatile boolean used;
        Ticket(String owner,BooleanSupplier authorised){this.owner=owner;this.authorised=authorised;}
        synchronized boolean claim(Socket s){if(used || expired() || closed || !authorised.getAsBoolean())return false;used=true;socket=s;return true;}
        boolean expired(){return System.nanoTime()-created>TimeUnit.SECONDS.toNanos(used?1800:60);}
        synchronized void close(){used=true;PrivateStreams.remove(token);tickets.remove(token,this);Socket s=socket;if(s!=null)try{s.close();}catch(IOException ignored){}}
    }
    public DirectFileChannel(){watch.scheduleWithFixedDelay(()->{
        for(Ticket t:tickets.values())if(t.expired() || !t.authorised.getAsBoolean()
                || (t.writing!=0 && System.nanoTime()-t.writing>TimeUnit.SECONDS.toNanos(30)))t.close();
    },5,5,TimeUnit.SECONDS);}
    public synchronized Map<String,String> issue(String owner,List<String> sources,BooleanSupplier authorised,
                                                com.eurobuddha.maxima.core.chat.FileUpload.Backend backend)throws IOException {
        if(closed || !authorised.getAsBoolean())throw new IOException("Account unavailable");
        if(sources.isEmpty())throw new IOException("Your account needs a reachable direct file connection");
        for(Ticket t:tickets.values())if(t.expired())t.close();
        if(tickets.size()>=16 || tickets.values().stream().filter(t->t.owner.equals(owner)).count()>=3)
            throw new IOException("Finish another direct file connection first");
        Ticket t=new Ticket(owner,authorised);tickets.put(t.token,t);
        PrivateStreams.register(t.token,(socket,in,out)->{
            if(!t.claim(socket))return;
            PrivateStreams.remove(t.token);
            try {
                socket.setSoTimeout(60000);socket.setTcpNoDelay(true);out.write(1);out.flush();
                Records records=new Records(in,out,t.up,t.down);
                while(!closed && !t.expired() && t.authorised.getAsBoolean()){
                    Map<String,String> request=Json.parse(new String(records.read(),StandardCharsets.UTF_8));
                    if(!t.authorised.getAsBoolean() || closed)break;
                    Map<String,String> result;
                    try{result=backend.call(request);}catch(Exception e){result=new LinkedHashMap<>();result.put("ok","false");result.put("error",e.getMessage()==null?"File command failed":e.getMessage());}
                    Json.Writer w=new Json.Writer();result.forEach(w::put);
                    t.writing=System.nanoTime();records.write(w.done().getBytes(StandardCharsets.UTF_8));t.writing=0;
                }
            }finally{t.close();}
        });
        Map<String,String> result=new LinkedHashMap<>();result.put("sources",String.join(",",sources));result.put("token",t.token);
        result.put("up",Base64.getEncoder().encodeToString(t.up));result.put("down",Base64.getEncoder().encodeToString(t.down));return result;
    }
    /** Bounded record codec. Keys are fresh for each one-use ticket, direction-separated. */
    static final class Records {
        final DataInputStream in; final DataOutputStream out; final byte[] readKey,writeKey;
        long readSequence,writeSequence;
        Records(DataInputStream in,DataOutputStream out,byte[] readKey,byte[] writeKey){
            if(readKey.length!=32 || writeKey.length!=32)throw new IllegalArgumentException("Invalid session key");
            this.in=in;this.out=out;this.readKey=readKey.clone();this.writeKey=writeKey.clone();
        }
        private byte[] crypt(int mode,byte[] key,long sequence,byte[] data)throws IOException {
            if(sequence<0 || sequence>1000000)throw new IOException("File session expired");
            try{
                byte[] iv=ByteBuffer.allocate(12).putInt(0x50463101).putLong(sequence).array();
                Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
                c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
                c.updateAAD(iv);return c.doFinal(data);
            }catch(Exception e){throw new IOException("Invalid encrypted file record",e);}
        }
        byte[] read()throws IOException {
            int size=in.readInt();if(size<16 || size>MAX_RECORD+16)throw new IOException("Invalid file record length");
            byte[] encrypted=new byte[size];in.readFully(encrypted);
            byte[] plain=crypt(Cipher.DECRYPT_MODE,readKey,readSequence,encrypted);readSequence++;return plain;
        }
        void write(byte[] plain)throws IOException {
            if(plain.length>MAX_RECORD)throw new IOException("File record too large");
            byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,writeKey,writeSequence++,plain);
            out.writeInt(encrypted.length);out.write(encrypted);out.flush();
        }
    }
    public static final class Client implements AutoCloseable {
        private final Socket socket; private final Records records;
        private volatile long writing;
        private final ScheduledExecutorService watch=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"file-device-watch");t.setDaemon(true);return t;});
        private Client(Socket socket,Records records){
            this.socket=socket;this.records=records;
            watch.scheduleWithFixedDelay(()->{if(writing!=0 && System.nanoTime()-writing>TimeUnit.SECONDS.toNanos(30))close();},5,5,TimeUnit.SECONDS);
        }
        public static Client connect(Map<String,String> ticket)throws IOException {return connect(ticket,false);}
        static Client connect(Map<String,String> ticket,boolean testLoopback)throws IOException {
            String token=ticket.getOrDefault("token","");
            if(!token.matches("[a-f0-9]{64}"))throw new IOException("Invalid direct file ticket");
            byte[] up,down;
            try{up=Base64.getDecoder().decode(ticket.get("up"));down=Base64.getDecoder().decode(ticket.get("down"));}
            catch(Exception e){throw new IOException("Invalid direct file ticket",e);}
            String sources=ticket.getOrDefault("sources","");if(sources.length()>4096)throw new IOException("Invalid direct file address");
            int count=0;
            for(String source:sources.split(",")){
                if(++count>3)break;Socket s=new Socket();
                try{
                    String addr=source.substring(source.lastIndexOf('@')+1);int colon=addr.lastIndexOf(':');
                    InetAddress host=InetAddress.getByName(addr.substring(0,colon));int port=Integer.parseInt(addr.substring(colon+1));
                    if(port<1024 || port>65535 || host.isAnyLocalAddress() || host.isMulticastAddress() || host.isLinkLocalAddress()
                            || (!testLoopback && host.isLoopbackAddress()))throw new IOException("Invalid direct file address");
                    s.connect(new InetSocketAddress(host,port),8000);s.setSoTimeout(60000);s.setTcpNoDelay(true);
                    DataInputStream in=new DataInputStream(s.getInputStream());DataOutputStream out=new DataOutputStream(s.getOutputStream());
                    Frame.write(out,Frame.body(Frame.MSG_GREETING,Greeting.commsOnly("1.0.48","",0)));out.flush();
                    byte[] greeting=Frame.readOrSkip(in,65536);
                    if(greeting==null || Frame.typeOf(greeting)!=Frame.MSG_GREETING)throw new IOException("Direct file listener unavailable");
                    byte[] request=new byte[65];request[0]=(byte)PrivateStreams.TYPE;
                    System.arraycopy(token.getBytes(StandardCharsets.US_ASCII),0,request,1,64);Frame.write(out,request);out.flush();
                    TorrentTunnel.awaitReady(in);
                    return new Client(s,new Records(in,out,down,up));
                }catch(Exception e){try{s.close();}catch(IOException ignored){}}
            }
            throw new IOException("Cannot connect directly to your account. Check its port forwarding and try again.");
        }
        public synchronized Map<String,String> call(Map<String,String> input)throws IOException {
            Json.Writer w=new Json.Writer();input.forEach(w::put);
            try{writing=System.nanoTime();records.write(w.done().getBytes(StandardCharsets.UTF_8));writing=0;return Json.parse(new String(records.read(),StandardCharsets.UTF_8));}
            catch(Exception e){close();throw e instanceof IOException?(IOException)e:new IOException("Direct file connection interrupted",e);}
        }
        @Override public void close(){try{socket.close();}catch(IOException ignored){}watch.shutdownNow();}
    }
    @Override public synchronized void close(){closed=true;for(Ticket t:tickets.values())t.close();watch.shutdownNow();}
}
