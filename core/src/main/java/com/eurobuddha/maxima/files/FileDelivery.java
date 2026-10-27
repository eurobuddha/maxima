package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.chat.ChatFile;
import com.eurobuddha.maxima.core.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Durable verified-file receipts shared by Parlons and native KeetChat.
 * Controls contain no filename, key or file bytes. The caller supplies the authenticated peer.
 * Network/stop operations run from tick(), never while holding the state lock. */
public final class FileDelivery {
    public interface Channel { void send(String peer,String control)throws Exception; }
    public interface Stop { void stop(String id)throws Exception; }
    private final Channel channel; private final Stop stop;
    private final Map<String,State> states=new LinkedHashMap<>();
    private final List<String[]> replies=new ArrayList<>();
    private static final class State {
        ChatFile file; Path path; boolean mine,complete,acked,keep,stopped,stopping;
        Set<String> peers=new TreeSet<>(),capable=new TreeSet<>(),delivered=new TreeSet<>();
        long next; int backoff; String error="";
    }
    public FileDelivery(Channel channel,Stop stop){this.channel=channel;this.stop=stop;}
    public synchronized void register(ChatFile f,Path directory,boolean mine,Collection<String> peers)throws IOException {
        State old=states.get(f.id); if(old!=null){if(!old.file.ref().equals(f.ref()))throw new IOException("Conflicting transfer");return;}
        State s=new State();s.file=f;s.path=directory.resolve("delivery.json");s.mine=mine;
        for(String peer:peers)s.peers.add(peer.toLowerCase(Locale.ROOT));
        if(Files.exists(s.path)) {
            if(Files.size(s.path)>16384)throw new IOException("Invalid delivery state");
            Map<String,String> m=Json.parse(new String(Files.readAllBytes(s.path),StandardCharsets.UTF_8));
            if(!f.digest.equals(m.get("digest")) || !Boolean.toString(mine).equals(m.get("mine")) || !String.join(",",s.peers).equals(m.get("peers")))throw new IOException("Conflicting delivery state");
            s.complete=Boolean.parseBoolean(m.get("complete"));s.acked=Boolean.parseBoolean(m.get("acked"));s.keep=Boolean.parseBoolean(m.get("keep"));s.stopped=Boolean.parseBoolean(m.get("stopped"));
            add(s.capable,m.get("capable"),s.peers);add(s.delivered,m.get("delivered"),s.peers);
        }
        states.put(f.id,s);
    }
    private static void add(Set<String> into,String csv,Set<String> allowed){if(csv!=null)for(String x:csv.split(","))if(allowed.contains(x))into.add(x);}
    public synchronized void verified(String id)throws IOException {State s=get(id);if(!s.mine&&!s.complete){s.complete=true;s.next=0;save(s);}}
    public synchronized void keep(String id,boolean keep)throws IOException {State s=get(id);if(s.stopping)throw new IOException("Sharing is stopping; try again shortly");s.keep=keep;if(keep)s.stopped=false;s.next=0;save(s);}
    public synchronized void forget(String id){states.remove(id);}
    private State get(String id)throws IOException {State s=states.get(id);if(s==null)throw new IOException("Transfer not found");return s;}
    private static String wire(State s,String kind){return new Json.Writer().put("v","1").put("id",s.file.id).put("digest",s.file.digest).put("kind",kind).done();}
    public synchronized void inbound(String peer,String control)throws IOException {
        if(control==null||control.length()>1024)return;
        Map<String,String> m=Json.parse(control);State s=states.get(m.get("id"));peer=peer.toLowerCase(Locale.ROOT);
        if(s==null||!s.peers.contains(peer)||!"1".equals(m.get("v"))||!s.file.digest.equals(m.get("digest")))return;
        String kind=m.get("kind");
        s.backoff=0; // the peer is answering: re-send at the fast cadence again
        if("hello".equals(kind)&&!s.mine)reply(peer,wire(s,"capable"));
        else if("capable".equals(kind)&&s.mine){s.capable.add(peer);save(s);}
        else if("complete".equals(kind)&&s.mine){s.capable.add(peer);s.delivered.add(peer);save(s);reply(peer,wire(s,"ack"));}
        else if("ack".equals(kind)&&!s.mine&&s.complete){s.acked=true;save(s);}
    }
    private void reply(String peer,String body){if(replies.size()<128)replies.add(new String[]{peer,body});}
    public void tick() {tick(System.currentTimeMillis());}
    /** hello/complete re-send gaps: 15 s, 30 s, ... capped at 10 min while the peer stays silent;
     * an answer from the peer makes the gap after the current one 15 s again. A fixed 15 s forever
     * pushed ~3 sealed mailbox messages every 15 s per pending file to a peer that was away
     * (Fold, 2026-10-27). Only a state that actually sends advances its gap. Package-private
     * clock for tests. */
    void tick(long now) {
        List<String[]> sends=new ArrayList<>();List<State> stops=new ArrayList<>();
        synchronized(this){
            sends.addAll(replies);replies.clear();
            for(State s:states.values()){
                if(!s.stopped&&!s.keep&&((s.mine&&!s.peers.isEmpty()&&s.delivered.containsAll(s.peers))||(!s.mine&&s.complete&&s.acked)))stops.add(s);
                if(now<s.next)continue;int before=sends.size();
                if(s.mine){for(String p:s.peers)if(!s.delivered.contains(p))sends.add(new String[]{p,wire(s,"hello")});}
                else if(s.complete&&!s.acked)for(String p:s.peers)sends.add(new String[]{p,wire(s,"complete")});
                if(sends.size()==before)continue;
                s.next=now+Math.min(15000L<<s.backoff,600000L);if(s.backoff<6)s.backoff++;
            }
        }
        for(String[] send:sends)try{channel.send(send[0],send[1]);}catch(Exception ignored){/* complete/hello are durable and retried */}
        for(State s:stops)try {
            synchronized(this){if(states.get(s.file.id)!=s||s.keep||s.stopped)continue;s.stopping=true;}
            stop.stop(s.file.id);
            synchronized(this){if(!s.keep){s.stopped=true;s.error="";s.stopping=false;save(s);}}
        }catch(Exception e){synchronized(this){s.stopping=false;s.error="Downloaded; could not stop sharing: "+e.getMessage();}}
    }
    public synchronized Map<String,String> status(String id){
        State s=states.get(id);Map<String,String> out=new LinkedHashMap<>();if(s==null)return out;
        out.put("mine",Boolean.toString(s.mine));out.put("keepSharing",Boolean.toString(s.keep));out.put("sharingStopped",Boolean.toString(s.stopped));
        out.put("downloaded",Integer.toString(s.delivered.size()));out.put("recipients",Integer.toString(s.peers.size()));out.put("digest",s.file.digest);
        if(s.mine){out.put("delivery",s.delivered.containsAll(s.peers)&&!s.peers.isEmpty()?s.stopped?"Downloaded and verified · sharing stopped":"Downloaded and verified · sharing":s.capable.isEmpty()?"Download confirmation unavailable":"Waiting for verified download");}
        if(!s.mine&&s.stopped)out.put("delivery","Downloaded and verified · sharing stopped");
        if(!s.error.isEmpty())out.put("deliveryError",s.error);return out;
    }
    private void save(State s)throws IOException {
        String json=new Json.Writer().put("digest",s.file.digest).put("mine",Boolean.toString(s.mine)).put("peers",String.join(",",s.peers))
            .put("complete",Boolean.toString(s.complete)).put("acked",Boolean.toString(s.acked)).put("keep",Boolean.toString(s.keep)).put("stopped",Boolean.toString(s.stopped))
            .put("capable",String.join(",",s.capable)).put("delivered",String.join(",",s.delivered)).done();
        Path tmp=s.path.resolveSibling("delivery.tmp");Files.write(tmp,json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp,s.path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
